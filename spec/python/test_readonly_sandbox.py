# 职责：用临时宿主文件真实验证 Landlock、seccomp、子进程继承和失败关闭。

import contextlib
import errno
import importlib.util
import io
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock


HELPER = Path(__file__).resolve().parents[2] / "src/arch_view/context/readonly_sandbox.py"
SPEC = importlib.util.spec_from_file_location("readonly_sandbox", HELPER)
assert SPEC is not None and SPEC.loader is not None
sandbox = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sandbox)


@unittest.skipUnless(sys.platform == "linux", "真实 Landlock 测试仅适用于 Linux")
class ReadonlySandboxTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as scratch:
            result = subprocess.run(
                [sys.executable, "-I", "-B", str(HELPER), "--scratch",
                 str(Path(scratch).resolve()), "--check"], capture_output=True, text=True)
        if result.returncode == 125 and "只读沙箱不可用：" in result.stderr:
            raise unittest.SkipTest(result.stderr.strip())
        if result.returncode:
            raise AssertionError(result.stderr)

    def setUp(self):
        self.project_temp = tempfile.TemporaryDirectory(prefix="sandbox-project-")
        self.outside_temp = tempfile.TemporaryDirectory(prefix="sandbox-outside-")
        self.scratch_temp = tempfile.TemporaryDirectory(prefix="sandbox-scratch-")
        for directory in (self.project_temp, self.outside_temp, self.scratch_temp):
            self.addCleanup(directory.cleanup)
        self.project = Path(self.project_temp.name).resolve()
        self.outside = Path(self.outside_temp.name).resolve()
        self.scratch = Path(self.scratch_temp.name).resolve()
        self.files = [self.project / "source.txt", self.outside / "private.txt"]
        self.xattrs = {}
        for path in self.files:
            path.write_text("original content", encoding="utf-8")
            path.chmod(0o640)
            try:
                os.setxattr(path, "user.sandbox", b"original attribute")
                self.xattrs[path] = b"original attribute"
            except OSError as error:
                if error.errno != errno.EOPNOTSUPP:
                    raise
            # 避免读取本身在 relatime 文件系统更新 atime，便于逐项比较元数据。
            os.utime(path, ns=(time.time_ns() + 3600_000_000_000, time.time_ns()))
        self.before = {str(path): self.snapshot(path) for path in self.files}

    def snapshot(self, path):
        info = path.stat()
        return (info.st_mode, info.st_uid, info.st_gid, info.st_atime_ns,
                info.st_mtime_ns, info.st_ctime_ns, info.st_nlink, info.st_size)

    def run_code(self, code, *args, **options):
        result = subprocess.run(
            [sys.executable, "-I", "-B", str(HELPER), "--scratch", str(self.scratch),
             "--", sys.executable, "-I", "-B", "-c", code, *map(str, args)],
            cwd=self.project, capture_output=True, text=True, timeout=15, **options)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout

    def assert_originals(self):
        for path in self.files:
            self.assertEqual("original content", path.read_text(encoding="utf-8"))
            self.assertEqual(self.before[str(path)], self.snapshot(path))

    def test_read_scratch_write_and_python_child_inherit_restrictions(self):
        child = """
import errno, os, sys
from pathlib import Path
host, scratch = map(Path, sys.argv[1:])
assert host.read_text() == 'original content'
(scratch / 'child.txt').write_text('child success')
try:
    host.write_text('child escaped')
except OSError as error:
    assert error.errno in (errno.EPERM, errno.EACCES)
else:
    raise AssertionError('子进程逃出了沙箱')
print('child inherited')
"""
        output = self.run_code("""
import os, subprocess, sys
from pathlib import Path
host, outside, scratch = map(Path, sys.argv[1:4])
assert host.read_text() == outside.read_text() == 'original content'
assert all(os.environ[key] == str(scratch) for key in ('TMPDIR', 'TMP', 'TEMP'))
(scratch / 'output.txt').write_text('scratch success')
(scratch / 'nested').mkdir()
(scratch / 'nested' / 'old').write_text('rename')
(scratch / 'nested' / 'old').rename(scratch / 'renamed')
with open('/dev/null', 'w') as null:
    null.write('standard redirect')
subprocess.run([sys.executable, '-I', '-B', '-c', sys.argv[4], str(host), str(scratch)], check=True)
""", *self.files, self.scratch, child)
        self.assertIn("child inherited", output)
        self.assertEqual("scratch success", (self.scratch / "output.txt").read_text())
        self.assertEqual("child success", (self.scratch / "child.txt").read_text())
        self.assert_originals()

    def test_all_host_data_and_directory_mutations_are_denied(self):
        for root in (self.project, self.outside):
            (root / "empty-dir").mkdir()
        (self.scratch / "local.txt").write_text("local")
        output = self.run_code("""
import errno, json, os, sys
from pathlib import Path
scratch = Path(sys.argv[1])
failures = []
for name in sys.argv[2:]:
    host = Path(name)
    link = scratch / (host.name + '.symlink')
    link.symlink_to(host)
    directory_link = scratch / (host.name + '.directory')
    directory_link.symlink_to(host.parent, target_is_directory=True)
    actions = {
        'overwrite': lambda: host.write_text('escaped'),
        'append': lambda: open(host, 'a'),
        'new': lambda: (host.parent / 'new.txt').write_text('escaped'),
        'delete': lambda: host.unlink(),
        'rename': lambda: host.rename(host.parent / 'renamed'),
        'move_out': lambda: host.rename(scratch / 'stolen'),
        'move_in': lambda: (scratch / 'local.txt').rename(host),
        'truncate': lambda: os.truncate(host, 0),
        'hardlink_in_scratch': lambda: os.link(host, scratch / 'hardlink'),
        'hardlink_in_host': lambda: os.link(host, host.parent / 'hardlink'),
        'symlink_write': lambda: link.write_text('escaped'),
        'symlink_directory_write': lambda: (directory_link / 'new.txt').write_text('escaped'),
        'mkdir': lambda: (host.parent / 'new-dir').mkdir(),
        'rmdir': lambda: (host.parent / 'empty-dir').rmdir(),
        'symlink_in_host': lambda: (host.parent / 'new-link').symlink_to(scratch),
        'fifo': lambda: os.mkfifo(host.parent / 'new-fifo'),
    }
    for action, function in actions.items():
        try:
            function()
        except OSError as error:
            assert error.errno in (errno.EPERM, errno.EACCES, errno.EXDEV), (action, error)
            failures.append(action)
        else:
            raise AssertionError('宿主写入成功：' + action)
print(json.dumps(failures))
""", self.scratch, *self.files)
        self.assertEqual(32, len(json.loads(output)))
        self.assert_originals()
        for root in (self.project, self.outside):
            self.assertTrue((root / "empty-dir").is_dir())
            self.assertEqual(2, len(list(root.iterdir())))

    def test_host_metadata_mutations_and_fd_variants_are_denied(self):
        output = self.run_code("""
import errno, json, os, sys
from pathlib import Path
failures = []
for name in sys.argv[1:]:
    host = Path(name)
    fd = os.open(host, os.O_RDONLY)
    try:
        actions = {
            'chmod': lambda: os.chmod(host, 0o777),
            'fchmod': lambda: os.fchmod(fd, 0o777),
            'chown': lambda: os.chown(host, os.getuid(), os.getgid()),
            'lchown': lambda: os.lchown(host, os.getuid(), os.getgid()),
            'fchown': lambda: os.fchown(fd, os.getuid(), os.getgid()),
            'utime': lambda: os.utime(host, ns=(1, 1)),
            'futime': lambda: os.utime(fd, ns=(1, 1)),
            'setxattr': lambda: os.setxattr(host, 'user.sandbox', b'escaped'),
            'fsetxattr': lambda: os.setxattr(fd, 'user.sandbox', b'escaped'),
            'lsetxattr': lambda: os.setxattr(host, 'user.sandbox', b'escaped', follow_symlinks=False),
            'removexattr': lambda: os.removexattr(host, 'user.sandbox'),
            'fremovexattr': lambda: os.removexattr(fd, 'user.sandbox'),
            'lremovexattr': lambda: os.removexattr(host, 'user.sandbox', follow_symlinks=False),
        }
        for action, function in actions.items():
            try:
                function()
            except OSError as error:
                assert error.errno == errno.EPERM, (action, error)
                failures.append(action)
            else:
                raise AssertionError('宿主元数据修改成功：' + action)
    finally:
        os.close(fd)
print(json.dumps(failures))
""", *self.files)
        self.assertEqual(26, len(json.loads(output)))
        self.assert_originals()
        for path, value in self.xattrs.items():
            self.assertEqual(value, os.getxattr(path, "user.sandbox"))

    def test_each_known_sensitive_syscall_and_new_xattr_calls_return_eperm(self):
        self.run_code("""
import ctypes, errno, importlib.util, platform, sys
spec = importlib.util.spec_from_file_location('helper', sys.argv[1])
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
libc, seccomp = helper.libraries()
for name in helper.DENIED_SYSCALLS:
    number = seccomp.seccomp_syscall_resolve_name(name.encode())
    if number < 0:
        continue
    ctypes.set_errno(0)
    result = libc.syscall(number, 0, 0, 0, 0, 0, 0)
    assert result == -1 and ctypes.get_errno() == errno.EPERM, name
# 新通用 syscall 编号：宿主 libseccomp 可能尚无这些名称。
if platform.machine() in ('aarch64', 'x86_64'):
    for number in (463, 466):
        assert libc.syscall(number, 0, 0, 0, 0, 0, 0) == -1
        assert ctypes.get_errno() == errno.EPERM
""", HELPER)
        self.assert_originals()

    def test_network_is_available(self):
        with socket.socket() as server:
            server.settimeout(5)
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            self.run_code("""
import socket, sys
with socket.create_connection(('127.0.0.1', int(sys.argv[1])), timeout=5) as client:
    client.sendall(b'network preserved')
""", server.getsockname()[1])
            connection, _ = server.accept()
            with connection:
                self.assertEqual(b"network preserved", connection.recv(100))

    def test_inherited_nonstandard_writable_descriptor_is_closed(self):
        descriptor = os.open(self.files[0], os.O_WRONLY)
        try:
            self.run_code("""
import errno, os, sys
try:
    os.write(int(sys.argv[1]), b'escaped')
except OSError as error:
    assert error.errno == errno.EBADF
else:
    raise AssertionError('继承了宿主可写文件描述符')
""", descriptor, pass_fds=(descriptor,))
        finally:
            os.close(descriptor)
        self.assert_originals()

    def test_inherited_writable_host_standard_stream_is_rejected(self):
        with open(self.files[0], "r+b") as host_stream:
            result = subprocess.run(
                [sys.executable, "-I", "-B", str(HELPER), "--scratch", str(self.scratch),
                 "--", sys.executable, "-I", "-B", "-c", "print('escaped')"],
                stdout=host_stream, stderr=subprocess.PIPE, text=True, timeout=15)
        self.assertEqual(125, result.returncode)
        self.assertIn("请使用管道", result.stderr)
        self.assert_originals()

    def test_python_environment_hooks_are_ignored_before_isolation(self):
        marker = self.outside / "hook-ran"
        (self.project / "sitecustomize.py").write_text(
            f"from pathlib import Path; Path({str(marker)!r}).write_text('escaped')")
        environment = dict(os.environ, PYTHONPATH=str(self.project))
        self.run_code("print('isolated startup')", env=environment)
        self.assertFalse(marker.exists())

    def test_invalid_scratch_and_missing_target_fail_closed(self):
        self.project.chmod(0o755)
        symlink = self.scratch / "linked-directory"
        symlink.symlink_to(self.project, target_is_directory=True)
        for arguments in (["--scratch", str(self.project), "--", "touch", "escaped"],
                          ["--scratch", str(symlink), "--check"],
                          ["--scratch", "relative-directory", "--check"],
                          ["--scratch", str(self.scratch / "missing"), "--check"],
                          ["--scratch", str(self.scratch)]):
            result = subprocess.run([sys.executable, "-I", "-B", str(HELPER), *arguments],
                                    capture_output=True, text=True, cwd=self.project)
            self.assertEqual(125, result.returncode)
            self.assertIn("只读沙箱不可用：", result.stderr)
        self.assertFalse((self.project / "escaped").exists())

    def test_missing_command_is_a_launch_error_after_isolation(self):
        result = subprocess.run(
            [sys.executable, "-I", "-B", str(HELPER), "--scratch", str(self.scratch),
             "--", "/no-such-command"], capture_output=True, text=True, cwd=self.project)
        self.assertEqual(127, result.returncode)
        self.assertIn("无法启动本机 AI 命令：", result.stderr)
        self.assert_originals()


class ReadonlySandboxFailureTest(unittest.TestCase):
    def test_abi_three_is_required_and_all_write_rights_are_handled(self):
        with self.assertRaisesRegex(sandbox.SandboxUnavailable, "至少 3"):
            sandbox.write_rights(2)
        self.assertEqual((1 << 1) | sum(1 << bit for bit in range(4, 15)), sandbox.write_rights(3))
        self.assertEqual(sandbox.write_rights(3) | (1 << 15), sandbox.write_rights(8))

    def test_seccomp_rule_or_load_failure_prevents_execution(self):
        for rule_result, load_result in ((-errno.EINVAL, 0), (0, -errno.EACCES)):
            seccomp = mock.Mock()
            seccomp.seccomp_init.return_value = 123
            seccomp.seccomp_rule_add.return_value = rule_result
            seccomp.seccomp_load.return_value = load_result
            with mock.patch.object(sandbox, "known_syscalls", return_value=[("write", 64)]), \
                    mock.patch.object(sandbox, "allow_safe_ioctls"), \
                    self.assertRaises(sandbox.SandboxUnavailable):
                sandbox.restrict_syscalls(mock.Mock(), seccomp)
            seccomp.seccomp_release.assert_called_once_with(123)

    def test_safe_ioctl_rule_failure_is_fatal(self):
        seccomp = mock.Mock()
        seccomp.seccomp_syscall_resolve_name.return_value = 29
        seccomp.seccomp_rule_add_array.return_value = -errno.EINVAL
        with self.assertRaisesRegex(sandbox.SandboxUnavailable, "安全 ioctl"):
            sandbox.allow_safe_ioctls(seccomp, 123)

    def test_isolation_failures_return_125_without_exec_fallback(self):
        for failure in (OSError("缺少 libseccomp"), sandbox.SandboxUnavailable("Landlock 不可用")):
            error = io.StringIO()
            with mock.patch.object(sandbox, "install", side_effect=failure), \
                    mock.patch.object(sandbox.os, "execvp") as execute, \
                    contextlib.redirect_stderr(error):
                self.assertEqual(125, sandbox.main(["--scratch", "unused-scratch", "--", "target"]))
            execute.assert_not_called()
            self.assertIn("只读沙箱不可用：", error.getvalue())


if __name__ == "__main__":
    unittest.main()
