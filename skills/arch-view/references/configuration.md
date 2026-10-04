<!-- 职责：说明 arch-view 全局配置文件的位置、优先级和可配置项，供技能在用户询问或需要调整配置时读取。 -->

# arch-view 配置

## 位置与优先级

- 配置文件是**工具安装目录**下的 `.env`（与 `bin/`、`src/` 同级），安装脚本 `bin/install.ps1` 在文件不存在时从 `.env.example` 复制一份，已有则不覆盖。
- 它是全局偏好，对所有项目生效；**不读取被分析项目自己的 `.env`**，也不在用户项目里生成配置。
- `--env-file <文件>` 可换用其它配置文件；显式指定的文件不存在或格式错误会直接报错。
- 优先级：命令行参数 > 进程环境变量 > 配置文件 > 内置默认值。
- 格式为每行 `KEY=VALUE`，`#` 开头为注释，值可用引号包裹，留空等同未设置。无法解析的行会报错。

## 可配置项

| 配置项 | 取值 | 默认 | 作用 | 生效命令 |
|---|---|---|---|---|
| `ARCH_VIEW_EDGE_SCOPE` | `focus` / `all` | `focus` | 网页初始连线范围：当前模块或全部模块；页面下拉框仍可切换 | `serve` |
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
- 命令行参数见 `arch-view <命令> --help`。
