# architecture-viewer (架构可视化工具)

Clojure 开发的代码架构可视化工具，用于将 Clojure 与 Python 项目可视化为清晰的分层模块与依赖指示图。（已预留 Java 适配器扩展目录，目前尚未实现）。

![Empire 顶层架构示意图](images/empire-top-level.svg)

---

## 目录
- [多语言适配架构](#多语言适配架构)
  - [Clojure 适配器](#clojure-适配器)
  - [Python 适配器](#python-适配器)
  - [Java 适配器（规划中）](#java-适配器规划中)
  - [通用流水线](#通用流水线)
- [分层原理](#分层原理)
- [视觉图例](#视觉图例)
- [交互与导航](#交互与导航)
- [在其他项目中引入使用](#在其他项目中引入使用)
- [运行指南](#运行指南)
  - [CLI 参数说明](#cli-参数说明)
  - [运行示例](#运行示例)
- [测试套件](#测试套件)

---

## 多语言适配架构

架构分析器采用模块化适配层设计，适配器位于 `src/arch_view/input/{clojure,python,java}`，并在 `arch-view.input.languages` 中统一注册与分派。

```
src/arch_view/input/
├── languages.clj                  # 多语言统一抽象与探测入口
├── clojure/
│   └── dependency_extract.clj     # Clojure 依赖解析适配器
├── python/
│   ├── analyzer.py                # 基于 Python 标准库的 AST 依赖分析器
│   └── dependency_extract.clj     # Python 依赖提取桥接
└── java/
    └── README.md                  # Java 适配器（规划与预留接口）
```

### Clojure 适配器
- **扫描机制**：扫描 `--project-path` 下配置的源码路径（默认 `src`）。
- **依赖分析**：解析每个源码文件的 `ns` 形式，提取命名空间之间的 `:require` 依赖关系。
- **抽象识别**：自动记录各命名空间的源文件路径，并将包含多态声明（`defprotocol`、`defmulti`、`definterface`）的命名空间标记为抽象模块。

### Python 适配器 (`--language python`)
- **零依赖 AST 解析**：使用 Python 标准库自带的 `ast` 模块进行静态代码分析，**无需 `import` 或执行目标项目代码**，也无需预先在本地安装目标项目的第三方依赖包。
- **环境要求**：只需安装 Python 3.9+ 并在系统的 `PATH` 中可用；或者通过设置环境变量 `ARCH_VIEW_PYTHON` 指定 Python 解释器路径。
- **源码根目录推断与多根目录支持**：
  - 默认优先探测 `src` 目录，若不存在则使用项目根目录。
  - 支持重复指定 `--source-path` 以定义多个导入根目录（例如应用代码与测试代码处于不同目录）。
  - 模块命名基于相对于根目录的路径生成；`pkg/__init__.py` 对应模块 `pkg`。
- **完善的 Import 语法支持**：
  - 支持 `import foo`、`from foo import bar`、导入别名 (`as`)、多行导入括号、相对导入 (`from . import sibling`)。
  - 支持函数体与条件分支内部的局部导入。
  - 仅保留扫描范围内部模块之间的依赖关系。
  - 对于 `from pkg import child`，若存在则同时关联包初始化模块 (`pkg`) 与子模块 (`child`)。
- **抽象类与协议识别**：
  - 自动识别继承自 `abc.ABC`、元类为 `ABCMeta`、使用 `@abstractmethod` 装饰器，以及继承 `typing.Protocol` 的类（包括别名导入），并标记为抽象模块。
- **自动忽略无关目录**：
  - 自动跳过隐藏目录、虚拟环境 (`venv`, `.venv`, `env`)、`__pycache__`、`site-packages`、`node_modules`、`build`、`dist` 等。
- **健壮的错误提示**：
  - 遇到语法错误（SyntaxError）或同名模块冲突时提供明确路径报错，避免因静默忽略而生成残缺架构图。
  - 支持无 `__init__.py` 的命名空间包（Namespace Packages），展示为分组容器而非源文件叶子节点。
- **源码安全展示**：
  - Python 源文件在内置查看器中以转义纯文本与行号形式展示，避免特殊字符导致渲染异常，同时完整保留 Clojure 高亮能力。

### Java 适配器（规划中）
- 适配器目录已就位（`src/arch_view/input/java/`），预留给未来通过字节码分析或 Java AST 解析器实现 Java 架构视图扩展。

### 通用流水线
无论使用何种语言适配器，最终都输出统一的模块依赖图，无缝接入通用的**分层排版（Layering）**、**循环依赖分析（Cycle Detection）**与**交互式渲染（Interactive GUI Renderer）**模块。

---

## 分层原理

- **自顶向下分层**：顶部呈现高层命名空间/模块，底部呈现源码模块（叶子命名空间/源文件）。
- **消除依赖环**：在计算层级之前，算法会先识别图中的所有循环依赖，并临时移除致环依赖，将图转换为有向无环图（DAG）。
- **拓扑排序定级**：基于该 DAG 的拓扑排序决定各模块的纵向垂直层级。
- **同级并列排布**：同一层级的命名空间视为同级对等模块，水平并列展示。
- **保留环依赖指示**：被移除的致环依赖仍会以醒目的高亮指示标出，确保依赖循环问题清晰可见。

---

## 视觉图例

- **高层命名空间**：左侧带有两个凸出小耳（凸起连接块）的矩形。
  - 默认呈现为**浅蓝色**；若内部包含抽象模块则呈现为**绿色**。
- **叶子命名空间 / 源码文件**：无凸出小耳的直角矩形，具有粗黑边框。
- **模块名称颜色**：默认深色文字。
  - <span style="color:red">当该命名空间子树内存在循环依赖时，文字变红。</span>
- **依赖指示三角形**：
  - 上边沿正中央的小三角形：表示**输入依赖（Incoming Dependencies）**。
  - 下边沿正中央的小三角形：表示**输出依赖（Outgoing Dependencies）**。
  - 默认颜色为黑色；<span style="color:red">若该方向上的任何依赖属于环的一部分，三角形变红。</span>
- **悬停浮窗列表**：
  - 鼠标悬停在三角形上会弹出具体的依赖链路列表。
  - <span style="color:red">路径省略当前顶层命名空间，致环项以红色高亮，长列表支持鼠标滚轮滚动。</span>
- **循环依赖列表**：
  - 当当前视图内检测到依赖环时，会在图表底部以 `a->b->c->a` 的形式展示具体成环链路，多个环以列表形式逐项列出。

---

## 交互与导航

- **下钻展开（Drill Down）**：点击非叶子命名空间名称，可下钻查看该命名空间内部的子模块架构图（替换当前场景并将该命名空间作为新根节点）。
- **查看源码（View Source）**：点击叶子/源码模块，即可在内置的代码查看器中浏览其源代码及行号。
- **向上返回（Back）**：点击顶部工具栏的 `Back: <name>` 按钮返回上一层级，自动恢复先前的命名空间路径与滚动位置。
- **重新分析（Reanalyze）**：点击工具栏的 `Reanalyze` 按钮可重新扫描目标项目并即时重绘视图（保留当前选择的语言与源码路径设置）。此按钮在 `--project-path` 模式下可用，在 `--in-edn` 文件加载模式下自动隐藏。
- **自适应与缩放**：窗口支持自由拉伸调整大小。窗口尺寸改变时，图表会自动重新排版并水平居中。
- **滚动支持**：当图表或环列表超出视口范围时，自动出现水平与垂直滚动条。

---

## 在其他项目中引入使用

若想在其他 Clojure 项目中快速使用本工具，推荐在目标项目的 `deps.edn` 中配置专属 `:arch-view` 别名，并使用 `:replace-deps` 隔离环境：

```clojure
:arch-view
{:replace-deps
 {io.github.sounfury/arch-view-all
  {:git/url "https://github.com/sounfury/arch-view-all.git"
   :git/sha "d53c6978ec1d7ddc458392cf99a8ea3f044ef3c5"}}
 :main-opts ["-m" "arch-view.core"]}
```

配置后，在目标项目根目录下直接运行：

```bash
clj -M:arch-view --project-path .
```

> **提示**：使用 `:replace-deps` 可以使架构查看器与目标项目自身的运行时依赖完全隔离，避免 classpath 和库版本冲突。

---

## 运行指南

### CLI 参数说明

运行 `clj -M:run --help` 可以查看所有支持的命令行选项：

| 参数 | 说明 |
| :--- | :--- |
| `--help` | 打印使用帮助并退出 |
| `--project-path <path>` | 待扫描的项目根目录路径（默认：当前目录 `.`） |
| `--language <name>` | 指定语言：`clojure`（默认）或 `python` |
| `--source-path <path>` | 相对于项目根目录的源码路径；可多次指定以包含多个源码根目录 |
| `--in-edn <file>` | 从已导出的 EDN 文件加载架构，跳过源码扫描 |
| `--out <file>` | 将解析得到的架构数据以 EDN 格式写入指定文件 |
| `--no-gui` | 无头模式运行（不启动交互式图形界面窗口） |

### 运行示例

#### 1. 分析 Clojure 项目
```bash
# 查看帮助
clj -M:run --help

# 启动交互式 GUI 视图分析指定项目
clj -M:run --project-path /path/to/clojure-project

# 无头模式导出架构数据到 EDN 文件
clj -M:run --project-path /path/to/clojure-project --no-gui --out architecture.edn
```

#### 2. 分析 Python 项目
```bash
# 分析默认源码目录的 Python 项目
clj -M:run --language python --project-path /path/to/python-project

# 指定自定义源码目录并导出 EDN
clj -M:run --language python --project-path /path/to/project --source-path backend --no-gui --out architecture.edn

# 多源码根目录示例（PowerShell）
clj -M:run --language python --project-path D:/projects/ZhiYing --source-path backend

# 仅聚焦特定子包
clj -M:run --language python --project-path D:/projects/ZhiYing --source-path backend/app
```

---

## 测试套件

### Windows 窗口与字体

窗口缩放使用 Processing 4.4.1，修复旧版 AWT 绘制线程在拖拽窗口时的死锁。
拖拽期间保持当前图，尺寸稳定 200 毫秒后重新布局。高 DPI 屏幕（包含 Windows
125% 缩放）使用双倍像素画布，并启用原生字体微调，点击坐标仍使用逻辑尺寸。

Windows 默认以 125% 显示缩放启动；Mac 和其他平台保留系统缩放。
可用 JVM 参数覆盖，例如 Java 的 `-Dsun.java2d.uiScale=1.5` 或 Clojure CLI 的
`-J-Dsun.java2d.uiScale=1.5` 设置为 150%。修改后需重新启动窗口。

使用 Clojure CLI 启动会按 `deps.edn` 加载依赖。手工拼接 Java classpath 时，
必须把 Processing 4.4.1 放在 Quil 之前，因为 Quil 的 jar 内也包含旧版 Processing 类。
已打开的旧窗口需要关闭后重新启动。

可选的真实桌面回归测试（需要图形环境，自动调整窗口 60 次并保存截图）：

```powershell
clj -M:smoke-ui target/zhiying-architecture.edn target/ui-smoke.png
# 模拟 Windows 125% 显示缩放
clj -J-Dsun.java2d.uiScale=1.25 -M:smoke-ui target/zhiying-architecture.edn target/ui-125.png
```

### 自动化测试

```bash
# 检查 Clojure 命名空间
clj -M:check spec/

# 运行 Clojure Speclj 规范测试
clj -M:spec

# 运行 Python AST 分析器单元测试
python -B -m unittest discover -s spec/python -v
```
