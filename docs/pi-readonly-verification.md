# pi 与只读沙箱实测

本次在 Linux arm64 上使用已安装的 pi 1.5.0 做真实模型调用。调用入口为 `arch-view.context.agent/ask!`，与网页 `/api/ask` 使用同一条执行路径。

## 真实项目

- 项目：`/home/ubuntu/project/fetch-sub`，Python / FastAPI。
- 目标：`app/auth.py`。
- 问题：解释 `require_admin` 的鉴权优先级，并实际读取 `app/main.py` 中调用它的 middleware，引用两个文件的行号。
- 配置：`ARCH_VIEW_AGENT_PROVIDER=pi`，命令 `pi --model magpie/codex/gpt-6.1-sol --thinking low`。
- 返回：`provider=pi`，`sandbox={kind: linux-landlock, readOnly: true}`，耗时约 58.5 秒。

回答正确指出：

1. 未配置服务 token 时先返回 500。
2. 有效 Cookie 优先放行；Cookie 无效时才检查请求头。
3. 优先选择 `X-Upload-Token`，它为空时才选 Bearer；前者有值但错误时不会回退尝试后者。
4. 引用了 `app/main.py:83` 的 `auth.require_admin` 调用及 `app/main.py:80` 的公开请求提前放行逻辑。该文件未包含在目标 `auth.py` 的源码片段中。

调用前后核对了项目 6 个 Python 源文件，以及原 pi 的 `auth.json`、`models.json`、`settings.json`：9 份文件的 SHA-256、权限、修改时间均保持一致。调用结束后没有遗留 `arch-view-sandbox-*` 临时目录。

## 文件写入限制

Linux 使用 Landlock + seccomp，实测 Landlock ABI 8。对项目内外临时测试文件验证了以下操作被拒绝：新建、覆盖、删除、重命名、截断、硬链接、经软链接写入、修改权限/所有者/时间戳/扩展属性，以及子进程写入。原内容和元数据保持不变。

读取、私有临时目录写入、模型联网正常。隔离建立失败时返回错误，不会改为普通运行。

pi 的 proper-lockfile 会更新私有认证/设置锁目录的时间。为此仅对本次私有 `pi-agent` 内的真实 `.lock` 目录虚拟化锁时间，兼容同步加锁与定时续租；宿主元数据 syscall 仍由 seccomp 拒绝。此处理不修改 pi 安装或用户原配置。

## 回归

- Clojure：384 个用例、1436 个断言，全部通过。
- Python：24 项测试，全部通过，无跳过。
- Node：9 项测试，全部通过；包括本机 pi 使用的 proper-lockfile 同步加锁、冲突检测及两次真实定时续租。

macOS 的 sandbox-exec 路径已实现，尚未真机验证；Windows 当前拒绝直接调用 AI，仍可复制上下文包。此沙箱限制文件写入，保留读取与联网能力。
