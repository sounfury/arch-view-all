# 职责：以 Landlock 和 seccomp 强制限制文件系统写入，再直接执行 AI 命令。
# 核心入口：main；仅依赖 Python 标准库、Linux 内核和系统 libseccomp.so.2。

import argparse
import ctypes
import errno
import os
import stat
import sys


class SandboxUnavailable(RuntimeError):
    pass


class Ruleset(ctypes.Structure):
    _fields_ = [("handled_access_fs", ctypes.c_uint64)]


class PathBeneath(ctypes.Structure):
    _pack_ = 1
    _fields_ = [("allowed_access", ctypes.c_uint64), ("parent_fd", ctypes.c_int32)]


class SyscallArgument(ctypes.Structure):
    _fields_ = [("argument", ctypes.c_uint), ("operator", ctypes.c_int),
                ("value", ctypes.c_uint64), ("mask", ctypes.c_uint64)]


# 元数据 syscall 在 scratch 内也禁用，因为 seccomp 无法安全地按路径区分。
DENIED_SYSCALLS = frozenset((
    "chmod", "fchmod", "fchmodat", "fchmodat2",
    "chown", "fchown", "lchown", "fchownat", "chown32", "fchown32", "lchown32",
    "utime", "utimes", "futimesat", "utimensat", "utimensat_time64",
    "setxattr", "lsetxattr", "fsetxattr", "removexattr", "lremovexattr", "fremovexattr",
    "setxattrat", "removexattrat",
    "ptrace", "process_vm_writev", "pidfd_getfd", "io_uring_setup", "ioctl",
))


def checked(result, action):
    if result < 0:
        error = ctypes.get_errno()
        raise SandboxUnavailable(f"{action}失败：{os.strerror(error)}")
    return result


def libraries():
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long
    libc.syscall.argtypes = [ctypes.c_long]
    libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_ulong,
                           ctypes.c_ulong, ctypes.c_ulong]
    libc.free.argtypes = [ctypes.c_void_p]
    seccomp = ctypes.CDLL("libseccomp.so.2", use_errno=True)
    signatures = {
        "seccomp_syscall_resolve_name": (ctypes.c_int, [ctypes.c_char_p]),
        "seccomp_syscall_resolve_num_arch": (ctypes.c_void_p, [ctypes.c_uint32, ctypes.c_int]),
        "seccomp_arch_native": (ctypes.c_uint32, []),
        "seccomp_init": (ctypes.c_void_p, [ctypes.c_uint32]),
        "seccomp_rule_add": (ctypes.c_int, [ctypes.c_void_p, ctypes.c_uint32,
                                          ctypes.c_int, ctypes.c_uint]),
        "seccomp_rule_add_array": (ctypes.c_int, [ctypes.c_void_p, ctypes.c_uint32,
                                                ctypes.c_int, ctypes.c_uint,
                                                ctypes.POINTER(SyscallArgument)]),
        "seccomp_load": (ctypes.c_int, [ctypes.c_void_p]),
        "seccomp_release": (None, [ctypes.c_void_p]),
    }
    for name, (result, args) in signatures.items():
        function = getattr(seccomp, name)
        function.restype, function.argtypes = result, args
    return libc, seccomp


def syscall(libc, seccomp, name, *args):
    number = seccomp.seccomp_syscall_resolve_name(name.encode("ascii"))
    if number < 0:
        raise SandboxUnavailable(f"系统不认识必要调用 {name}")
    return checked(libc.syscall(number, *args), name)


def write_rights(abi):
    if abi < 3:
        raise SandboxUnavailable(f"需要 Landlock ABI 至少 3，当前为 {abi}")
    # WRITE_FILE、REMOVE_*、MAKE_*、REFER (ABI 2)、TRUNCATE (ABI 3)。
    rights = (1 << 1) | sum(1 << bit for bit in range(4, 15))
    if abi >= 5:
        rights |= 1 << 15  # IOCTL_DEV；普通文件 ioctl 另由 seccomp 禁止。
    return rights


def restrict_filesystem(libc, seccomp, scratch):
    abi = syscall(libc, seccomp, "landlock_create_ruleset", None, 0, 1)
    rights = write_rights(abi)
    attributes = Ruleset(rights)
    ruleset = syscall(libc, seccomp, "landlock_create_ruleset",
                      ctypes.byref(attributes), ctypes.sizeof(attributes), 0)
    try:
        parent = os.open(scratch, os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            info = os.fstat(parent)
            if info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700:
                raise SandboxUnavailable("scratch 必须由当前用户独占，权限为 0700")
            rule = PathBeneath(rights, parent)
            syscall(libc, seccomp, "landlock_add_rule", ruleset, 1, ctypes.byref(rule), 0)
        finally:
            os.close(parent)
        allow_null_device(libc, seccomp, ruleset)
        syscall(libc, seccomp, "landlock_restrict_self", ruleset, 0)
    finally:
        os.close(ruleset)


def allow_null_device(libc, seccomp, ruleset):
    descriptor = os.open("/dev/null", os.O_PATH | os.O_NOFOLLOW)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISCHR(info.st_mode) or info.st_rdev != os.makedev(1, 3):
            raise SandboxUnavailable("/dev/null 不是预期的空设备")
        # 仅此设备的 WRITE_FILE，不允许 /dev 目录下的其它写入。
        rule = PathBeneath(1 << 1, descriptor)
        syscall(libc, seccomp, "landlock_add_rule", ruleset, 1, ctypes.byref(rule), 0)
    finally:
        os.close(descriptor)


def known_syscalls(libc, seccomp):
    architecture = seccomp.seccomp_arch_native()
    # 原生 Linux syscall 表；超出已知表的调用同样默认拒绝。
    for number in range(1024):
        pointer = seccomp.seccomp_syscall_resolve_num_arch(architecture, number)
        if not pointer:
            continue
        try:
            name = ctypes.string_at(pointer)
        finally:
            libc.free(pointer)
        resolved = seccomp.seccomp_syscall_resolve_name(name)
        if resolved >= 0:
            yield name.decode("ascii"), resolved


def allow_safe_ioctls(seccomp, context):
    import termios

    number = seccomp.seccomp_syscall_resolve_name(b"ioctl")
    if number < 0:
        raise SandboxUnavailable("无法识别必要调用 ioctl")
    # 只放行非阻塞切换、可读字节数、只读终端信息和 fd 的 CLOEXEC 操作。
    names = ("FIONBIO", "FIONREAD", "TCGETS", "TIOCGWINSZ", "TIOCGPGRP", "FIOCLEX", "FIONCLEX")
    for name in names:
        request = getattr(termios, name, None)
        if request is None:
            continue
        comparison = SyscallArgument(1, 4, request, 0)  # SCMP_CMP_EQ
        result = seccomp.seccomp_rule_add_array(context, 0x7FFF0000, number, 1,
                                               ctypes.byref(comparison))
        if result < 0:
            raise SandboxUnavailable(f"设置安全 ioctl {name} 失败：{os.strerror(-result)}")


def restrict_syscalls(libc, seccomp):
    # 默认 EPERM：旧 libseccomp 不认识的新 setxattrat/removexattrat 也不能穿透。
    context = seccomp.seccomp_init(0x00050000 | errno.EPERM)
    if not context:
        raise SandboxUnavailable("无法创建 seccomp 过滤器")
    try:
        for name, number in known_syscalls(libc, seccomp):
            if name not in DENIED_SYSCALLS:
                result = seccomp.seccomp_rule_add(context, 0x7FFF0000, number, 0)
                if result < 0:
                    raise SandboxUnavailable(f"设置 seccomp 调用 {name} 失败：{os.strerror(-result)}")
        allow_safe_ioctls(seccomp, context)
        result = seccomp.seccomp_load(context)
        if result < 0:
            raise SandboxUnavailable(f"加载 seccomp 失败：{os.strerror(-result)}")
    finally:
        seccomp.seccomp_release(context)


def validate_standard_descriptors():
    import fcntl

    for descriptor in range(3):
        try:
            info = os.fstat(descriptor)
            flags = fcntl.fcntl(descriptor, fcntl.F_GETFL)
        except OSError as error:
            if error.errno == errno.EBADF:
                continue
            raise
        if flags & os.O_ACCMODE == os.O_RDONLY:
            continue
        if stat.S_ISREG(info.st_mode) or stat.S_ISBLK(info.st_mode):
            raise SandboxUnavailable("标准流不能继承宿主普通文件或块设备的写权限，请使用管道")
        if stat.S_ISCHR(info.st_mode) and info.st_rdev != os.makedev(1, 3) and not os.isatty(descriptor):
            raise SandboxUnavailable("标准流仅支持管道、终端或 /dev/null")


def close_inherited_descriptors():
    # Landlock 无法撤销已打开文件的写权限；只保留 runner 提供的标准流。
    descriptors = os.listdir("/proc/self/fd")
    for descriptor in descriptors:
        number = int(descriptor)
        if number > 2:
            try:
                os.close(number)
            except OSError as error:
                if error.errno != errno.EBADF:
                    raise


def install(scratch):
    if sys.platform != "linux":
        raise SandboxUnavailable("此 helper 仅支持 Linux")
    if not os.path.isabs(scratch) or os.path.realpath(scratch) != scratch:
        raise SandboxUnavailable("scratch 必须是无符号链接的绝对目录")
    libc, seccomp = libraries()
    checked(libc.prctl(38, 1, 0, 0, 0), "设置 NO_NEW_PRIVS")
    validate_standard_descriptors()
    close_inherited_descriptors()
    restrict_filesystem(libc, seccomp, scratch)
    restrict_syscalls(libc, seccomp)
    os.environ.update(dict.fromkeys(("TMPDIR", "TMP", "TEMP"), scratch))


def arguments(argv):
    # 自行拆分 --，目标参数保持原样；检查模式也实际安装完整隔离。
    separator = argv.index("--") if "--" in argv else len(argv)
    parser = argparse.ArgumentParser(description="强制只读文件系统沙箱")
    parser.add_argument("--scratch", required=True)
    parser.add_argument("--check", action="store_true")
    options = parser.parse_args(argv[:separator])
    command = argv[separator + 1:]
    if not options.check and not command:
        raise SandboxUnavailable("缺少要执行的命令（请在 -- 后提供参数）")
    return options, command


def main(argv=None):
    try:
        options, command = arguments(sys.argv[1:] if argv is None else argv)
        install(options.scratch)
        if options.check:
            print("只读沙箱可用：Landlock + seccomp")
            return 0
    except (Exception, SystemExit) as error:
        print(f"只读沙箱不可用：{error}", file=sys.stderr)
        return 125
    try:
        os.execvp(command[0], command)  # nosec B606: 目标必须直接执行，不能经 shell。
    except OSError as error:
        print(f"无法启动本机 AI 命令：{error}", file=sys.stderr)
        return 127


if __name__ == "__main__":
    sys.exit(main())
