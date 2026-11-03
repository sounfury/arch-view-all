<!-- 职责：说明 arch-view 全局配置文件的位置、优先级和可配置项，供技能在用户询问或需要调整配置时读取。 -->

# arch-view 配置

## 位置与优先级

- 配置文件是**工具安装目录**下的 `.env`（与 `bin/`、`src/` 同级），安装脚本（`bin/install.ps1` 或 `bin/install.sh`）在文件不存在时从 `.env.example` 复制一份，已有则不覆盖；Linux 上没有 `python` 只有 `python3` 时会自动追加 `ARCH_VIEW_PYTHON=python3`。
- 它是全局偏好，对所有项目生效；**不读取被分析项目自己的 `.env`**，也不在用户项目里生成配置。
- `--env-file <文件>` 可换用其它配置文件；显式指定的文件不存在或格式错误会直接报错。
- 优先级：命令行参数 > 进程环境变量 > 配置文件 > 内置默认值。
- 格式为每行 `KEY=VALUE`，`#` 开头为注释，值可用引号包裹，留空等同未设置。无法解析的行会报错。

## 可配置项

| 配置项 | 取值 | 默认 | 作用 | 生效命令 |
|---|---|---|---|---|
| `ARCH_VIEW_EDGE_SCOPE` | `focus` / `all` | `focus` | 网页初始连线范围：当前模块或全部模块；页面下拉框仍可切换 | `serve` |
| `ARCH_VIEW_CRAP` | `auto` / `off` | `auto` | 发现 crap 命令时在后台分析函数圈复杂度，卡片和详情显示结果；只跑复杂度、不运行测试、不写报告文件 | `serve` |
| `ARCH_VIEW_CRAP_COMMAND` | 可执行文件路径 | 在 PATH 中查找 `crap` | crap 不在 PATH 上时指定其路径 | `serve` |
| `ARCH_VIEW_AGENT_PROVIDER` | `auto` / `pi-acp` / `pi` / `command` | `auto` | 网页 AI 适配器；`pi-acp` 多轮对话，`pi` 一次性提问，均可省略命令 | `serve` |
| `ARCH_VIEW_AGENT_COMMAND` | 命令及参数 | 未配置；`pi-acp`/`pi` 模式为其自身 | 例如 `pi-acp`、`pi --model …`、`claude -p`、`codex exec`；均在只读沙箱内执行 | `serve` |
| `ARCH_VIEW_PI_COMMAND` | 命令及参数 | `pi` | 仅 `pi-acp`：底层 pi 的路径和模型（`--model` / `--provider` / `--thinking` / `--models`） | `serve` |
| `ARCH_VIEW_AGENT_STATE_DIR` | 目录 | `~/.local/state/arch-view/acp` | `pi-acp` 会话快照根目录，按项目隔离；不写入被分析仓库 | `serve` |
| `ARCH_VIEW_AGENT_TIMEOUT` | 正整数秒 | `180` | AI 命令超时，超时后终止进程 | `serve` |
| `ARCH_VIEW_LANGUAGE` | `auto` / `clojure` / `python` / `kotlin` / `java` | `auto` | 分析语言，`auto` 按项目文件识别 | 全部 |
| `ARCH_VIEW_INCLUDE_TESTS` | `true` / `false` | `false` | 是否分析 Python 测试文件 | 全部 |
| `ARCH_VIEW_UI_SCALE` | 正数，如 `1.25` | `1.25` | 桌面界面与字体缩放 | `desktop` |
| `ARCH_VIEW_ZOOM` | 正数，如 `1.0` | `1.0` | 桌面图与标签的初始缩放 | `desktop` |
| `ARCH_VIEW_NO_GUI` | `true` / `false` | `false` | 只分析不开窗口 | `desktop` |
| `ARCH_VIEW_PYTHON` | 可执行文件路径或命令 | `python` | 分析 Python 项目所用的解释器，没有 `python` 命令时设为 `python3` | 全部 |
| `ARCH_VIEW_PROJECT_PATH` | 目录 | 当前目录 | 默认分析的项目 | `desktop`、`scan`（`serve` 以命令中的目录为准） |
| `ARCH_VIEW_SOURCE_PATHS` | 用 `;` 分隔的目录 | 自动识别 | 覆盖源码目录 | 全部 |
| `ARCH_VIEW_OUT` | 文件路径 | 不导出 | 把分析结果导出为 EDN | `desktop`、`scan` |

## 使用建议

- 全局文件只放个人偏好（连线范围、缩放、语言识别、Python 解释器）。`ARCH_VIEW_PROJECT_PATH`、`ARCH_VIEW_SOURCE_PATHS`、`ARCH_VIEW_OUT` 会作用于所有项目，按项目区分的设置优先用命令行参数传入。
- 网页与桌面共用这份配置；端口、架构说明文件等只有命令行参数。
- 修改配置后重新启动命令即可生效；网页服务需要停掉后重新 `arch-view serve`。
- Java/Kotlin 没有显式源码路径且发现至少两个 Maven/Gradle 构建模块时，自动按“构建模块 → 包 → 文件”浏览，只扫描模块主源码目录，不扫描标准测试目录。构建模块按构建文件及目录发现，不读取或执行构建脚本。
- `--source-path`（可重复）或 `ARCH_VIEW_SOURCE_PATHS` 会覆盖自动源码目录并关闭构建模块分组，节点继续使用原包名格式；自定义布局和需要扫描测试时使用该选项。仅发现一个构建模块时输出保持原样。
- 模块名取目录名，`.` 替换为 `_`；重名时用 `:` 连接相对路径。根架构文档用 `#module=user-api` 等链接匹配构建模块角色。
- 网页 AI 调用强制使用系统只读沙箱，不提供关闭隔离的配置。Linux 需要 Python 3、Landlock ABI ≥3 和系统 `libseccomp.so.2`；macOS 使用 `sandbox-exec`。无法建立沙箱时拒绝调用；仍可复制上下文包给外部 AI。
- pi 模式使用私有临时配置，不保存会话，关闭写工具、扩展、MCP 和项目配置；只开放 `read/grep/find/ls`。命令仅支持模型选择参数和安全的非交互标志。认证和模型文件来自本机 pi 配置，原文件不改动。
- 命令行参数见 `arch-view <命令> --help`。
