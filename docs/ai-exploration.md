# AI 辅助探索：设计契约

<!-- 职责：约定 AI 查询、上下文包、链路、解释四项功能共用的数据格式、命令和接口，供各模块按同一契约实现。 -->

定位：**静态分析给可核对的事实，AI 负责解释，解释回到图上并被校验。** 四项功能：

| 编号 | 功能 | 命名空间 | 命令 | 网页接口 |
| :--- | :--- | :--- | :--- | :--- |
| P0 | 精确查询、改动影响 | `arch-view.query.*` | `arch-view query …`、`arch-view impact` | — |
| P0/P3 | 上下文包、让本机 AI 解释 | `arch-view.context.*` | `arch-view context …` | `GET /api/context`、`POST /api/ask`、`POST /api/ask/stream`、`/api/agent/sessions` |
| P1 | 链路：保存、逐跳校验 | `arch-view.trace.*` | `arch-view trace …` | `GET /api/traces`、`GET /api/trace` |
| P2 | 解释持久化、过期检测 | `arch-view.notes.*` | `arch-view notes …` | `GET /api/notes`、`POST /api/note` |

## 共享基础

### `arch-view.workspace`：工作区与目标解析

所有功能函数都接收同一个**工作区** `ws`：`{:architecture <core/load-architecture 的结果> :root <java.nio.file.Path 规范化项目根> :opts {...}}`。网页服务的 `state` 本身就含 `:architecture`、`:root`、`:opts`，可直接当 `ws` 用。

- `(workspace/data-dir ws)` → `<root>/.arch-view` 的 `java.io.File`（不自动创建）。
- `(workspace/relative ws file-or-path)` → 相对项目根、正斜杠分隔的字符串。
- `(workspace/module-for-path ws path)` → 源码文件对应的模块名或 `nil`；`path` 可为相对或绝对路径。
- `(workspace/source-file ws module)` → 模块源码文件的相对路径或 `nil`。
- `(workspace/resolve-target ws target)` → 把用户/AI 给出的目标解析为：
  `{:kind :module|:package|:file :target "<规范名>" :modules ["模块" ...]}`；
  依次尝试：完整模块名 → 源码文件路径 → 包前缀（按 `.` 分段的前缀，含构建模块名）。都不匹配时抛 `ex-info`（`{:status 404 :candidates [...]}`，候选为名称包含该文本的前 10 个模块）。
- `(workspace/git-head ws)` → 当前提交哈希或 `nil`（非 git 仓库时）。

### `arch-view.anchor`：代码锚点

```json
{"path": "src/arch_view/core.clj", "startLine": 40, "endLine": 66, "symbol": "load-architecture", "hash": "sha256:…"}
```

- `path` 相对项目根、正斜杠；行号 1 起、闭区间；`symbol` 可选；`hash` 是该行区间“每行去掉行尾空白、换行统一为 `\n`”后的 SHA-256。
- `(anchor/make-anchor ws {:path :startLine :endLine :symbol})` → 补上 `:hash` 并规范 `:path`；文件不存在、越出项目根或行号越界时抛 `ex-info {:status 400}`。
- `(anchor/check-anchor ws anchor)` → 原锚点加 `:status`：
  - `"fresh"`：原位置哈希一致；
  - `"moved"`：同文件其他位置找到相同哈希的等长区间，附 `:currentStartLine`、`:currentEndLine`；
  - `"stale"`：文件在，但找不到相同内容；
  - `"missing"`：文件不存在。
- `(anchor/relocate anchor checked)` → 若 `moved` 返回更新行号后的锚点，否则原样返回。

### `arch-view.cli-support`：子命令公共工具

- `(cli-support/parse-args args spec)`：`spec` 为 `{:values #{"--depth" …} :flags #{"--strict" …}}`；公共参数 `--project-path --language --source-path --in-edn --format --env-file` 总是可用；返回 `{:options {...} :positional [...]}`。`--format` 取 `md`（默认）或 `json`。
- `(cli-support/load-workspace options)` → `ws`；分析过程中的进度输出转到标准错误，标准输出只留命令结果。
- `(cli-support/emit! options data markdown-fn)`：`json` 时输出 `data` 的 JSON，`md` 时输出 `(markdown-fn data)`。
- `(cli-support/read-json-file file)` → 关键字键的数据；`(cli-support/write-json-file! file data)` 写出带缩进的 JSON（UTF-8，自动建目录）。
- `(cli-support/valid-id? id)`、`(cli-support/read-json-text text)`、`(cli-support/common-help)`。
- 失败一律抛 `ex-info`，由 `arch-view.cli` 统一打印并以退出码 1 结束；`run!` 返回正整数时作为退出码（如 `--strict` 用 2），其它返回值忽略。`run!` 要 `(:refer-clojure :exclude [run!])`。

### 命令与路由挂载点

- 每个功能命名空间 `arch-view.<feature>.cli` 提供 `(run! args)`：自行解析参数和 `--help`。`arch-view.cli` 只负责分派：`query`、`impact` → `arch-view.query.cli/run!`（`impact` 时 args 首个元素是 `"impact"`）；`context` → `arch-view.context.cli/run!`；`trace` → `arch-view.trace.cli/run!`；`notes` → `arch-view.notes.cli/run!`。
- 每个功能命名空间 `arch-view.<feature>.web` 提供 `routes`：`{["GET" "/api/xxx"] (fn [state params] data)}`。`params` 是查询参数字符串映射；POST 请求体（UTF-8 文本）放在 `params` 的 `"body"` 键。抛 `ex-info {:status n}` 设定状态码。`server.clj` 会合并这些路由。
- 命令的 `--help` 文本用中文，风格与现有 `arch-view serve --help` 一致。

## 数据目录 `.arch-view/`

放在项目根，可在核对后随项目提交：

```text
.arch-view/
  traces/<id>.json   链路
  notes/<id>.json    解释
```

`id` 只允许 `[a-z0-9][a-z0-9-]{0,63}`，等于文件名。读写都必须校验 `id`，防止路径穿越。

## P0 查询：`arch-view query` / `arch-view impact`

目标参数都走 `workspace/resolve-target`，可传模块、文件或包。包作为整体：只算包外依赖。

| 命令 | 输出 |
| :--- | :--- |
| `query deps <目标> [--depth N]` | 下游依赖（默认 1 层），每项含模块、层级、依赖类型（`direct`/`abstract`）、是否在环中、源码路径 |
| `query rdeps <目标> [--depth N]` | 上游调用方，同上 |
| `query path <起点> <终点> [--max N]` | 起点到终点的依赖路径（最短优先，默认最多 5 条） |
| `query cycles` | 全部依赖环（强连通分量）与成环边 |
| `query layers [--prefix 包]` | 分层：层号 → 模块列表 |
| `query find <关键字> [--limit N]` | 按模块名、文件路径、文件头说明模糊搜索 |
| `query module <目标>` | 单个目标概况：类型、成员数、层级、上下游数量、环、文件头说明 |
| `impact [--since <git 引用>] [--staged]` | 改动文件 → 模块 → 受影响的上游闭包（按距离分组），并列出改动模块参与的环 |

`impact` 默认比较工作区与 `HEAD`（含未跟踪文件）；`--since` 比较指定引用与 HEAD 的共同祖先到当前工作区；`--staged` 只看暂存区。无法映射到模块的文件单独列出。

## P0/P3 上下文包：`arch-view context`

`arch-view context <目标> [--max-source-lines N] [--question "…"] [--format md|json]`

上下文包是“交给 AI 解释的最小充分材料”，Markdown 按以下顺序：

1. 目标：名称、类型（模块/包/文件）、源码路径、所在层级。
2. 架构文档：命中的子系统（`ARCHITECTURE.md` 及包级文档中 `#module=` 链接匹配目标或其祖先的小节）的标题、实现状态、职责正文。
3. 文件头说明。
4. 依赖：上游、下游（各最多 20，超出注明总数），标出抽象依赖与环。
5. 复杂度：有 crap 结果时列复杂度最高的函数（网页用已有结果；命令行可 `--crap auto|off`，默认 off）。
6. 已有解释与链路：引用到目标的 notes/traces 标题与新鲜度（只读 `.arch-view/`，不依赖 notes/trace 命名空间，直接读 JSON）。
7. 源码：模块为文件内容（超过上限截断并注明）；包为各成员文件的头说明列表，不贴全部源码。
8. 若给了 `--question`，最后附“请回答的问题”和作答要求：引用代码时写 `路径:行号`；静态依赖里没有的调用关系要标明为推断。

接口：

- `GET /api/context?target=…[&question=…]` → `{:target … :markdown "…" :data {…}}`。
- `POST /api/ask?target=…[&sessionId=…]`，请求体为问题文本 → 由全局 `ARCH_VIEW_AGENT_PROVIDER=auto|pi|pi-acp|command` 和 `ARCH_VIEW_AGENT_COMMAND` 决定适配器；在项目根、强制只读系统沙箱中执行。`pi-acp` 把上下文包作为 ACP `session/prompt` 发送，可续聊；`pi`/`command` 仍从标准输入一次性传入。`pi-acp`/`pi` 模式命令可省略；`auto` 按可执行文件名识别。命令按空白及双引号拆分，不经过 shell，也不接受 HTTP 参数指定启动命令。
- `POST /api/ask/stream`：与 ask 相同的参数，SSE 事件为 `session`、`update`、`result` 或 `error`。
- `GET /api/agent/sessions?target=…`、`GET /api/agent/session?id=…`、`POST /api/agent/cancel?id=…`、`POST /api/agent/close?id=…`：列出/读取展示历史、取消当前轮次、释放活动进程。
- 超时 `ARCH_VIEW_AGENT_TIMEOUT`（秒，默认 180）；返回 `{:answer "…" :command "…" :provider "pi|pi-acp|command" :sandbox {:kind "linux-landlock|macos-sandbox" :readOnly true} :elapsedMs n :anchors [...] :sessionId?}`。锚点在执行命令前为实际收录的源码区间建立；只含文件头说明的包目标不生成整文件锚点。未配置返回 409，隔离无法建立返回 503，命令失败返回 502，超时返回 504。

### pi 适配与只读沙箱

`context/provider.clj` 只允许 pi 的模型选择参数。一次性 `pi` 强制 `--print --mode text --no-session --tools read,grep,find,ls`；`pi-acp` 保留 adapter argv，通过 `ARCH_VIEW_PI_COMMAND` 启动私有只读 RPC（`--mode rpc` 与同样的只读工具）。两者都关闭扩展、MCP、项目配置、技能、上下文文件、模板与启动更新。把认证、模型文件和筛选后的模型/网络设置复制到私有临时目录，重定向 `PI_CODING_AGENT_DIR`，清除父会话环境；不修改原 pi 配置。`pi-acp` 的原生会话快照保存在 `ARCH_VIEW_AGENT_STATE_DIR`（默认 `~/.local/state/arch-view/acp`），按项目根哈希隔离。

pi 仅在本次私有配置内对 `.lock` 目录虚拟化时间戳，以兼容 proper-lockfile 的同步加锁与续租，覆盖 callback、sync、promise 和 ESM 接口；宿主的元数据 syscall 禁止规则保持不变。认证副本与兼容脚本均由调用结束后的清理删除。

`context/sandbox.clj` 为每次调用生成独占临时目录并包装命令。Linux helper 使用 Python 3、Landlock ABI ≥3 和系统 libseccomp：除临时目录及 `/dev/null` 外，禁止宿主文件写入；通过 seccomp 补足 Landlock 不覆盖的元数据修改、跨进程写入与 io_uring。限制会由子进程继承；标准输入输出使用管道，不能继承已打开的宿主可写文件。macOS 使用 `sandbox-exec` 禁止宿主 `file-write*`；其它系统拒绝执行。隔离不可用时没有无保护回退。文件读取和模型联网保留。

## P1 链路：`arch-view trace`

AI 写草稿 → `trace save` 补锚点哈希、校验、落盘 → 网页叠加显示。

草稿 / 存储格式：

```json
{
  "id": "web-reanalyze",
  "title": "网页点击重新分析",
  "question": "点击重新分析按钮后发生了什么？",
  "summary": "Markdown 概述",
  "steps": [
    {"title": "前端发起请求", "explanation": "Markdown",
     "anchor": {"path": "src/arch_view/web/assets/app.js", "startLine": 10, "endLine": 20, "symbol": "reanalyze"},
     "via": "http"}
  ],
  "author": "ai",
  "createdAt": "2026-11-03T10:00:00Z",
  "commit": "<保存时的 git HEAD>"
}
```

- `via`（可选）描述“上一步 → 本步”的方式：`call`、`http`、`event`、`db`、`config`、`return`、`other`。
- `trace save <草稿.json> [--force]`：校验格式、为每步生成锚点哈希、填 `createdAt`、`commit`，写入 `.arch-view/traces/<id>.json`；同 id 已存在时需 `--force`。输出与 `verify` 相同的校验结果。
- `trace list`、`trace show <id>`、`trace verify [<id>|--all] [--fix-moved] [--strict]`：`--fix-moved` 把 `moved` 锚点改到新行号；`--strict` 有 `stale`/`missing` 或 `unverified` 跳时退出码 2。

逐跳校验（第 i−1 步 → 第 i 步），先用锚点路径解析模块：

| `hop.status` | 条件 |
| :--- | :--- |
| `same-module` | 两步在同一模块 |
| `direct` | 静态图中存在 前 → 后 的边 |
| `indirect` | 存在 ≤3 跳的依赖路径，附 `:path` |
| `reverse` | 只存在 后 → 前 的边（回调、返回值） |
| `unverified` | 两端都是模块，但静态图中找不到关联：AI 推断，需要人复核 |
| `unmapped` | 至少一端不是已分析的源码模块（前端、配置、资源等） |

校验结果：`{:trace <链路> :steps [{:index i :module m :anchor <check-anchor 结果>}] :hops [{:from i-1 :to i :fromModule … :toModule … :status … :path […]}] :summary {:fresh n :moved n :stale n :missing n :direct n … :unverified n} :verdict "ok|needs-review|stale"}`。索引从 0 起；这里核对模块依赖，不证明函数调用或运行顺序。

接口：`GET /api/traces` → 各链路 `{:id :title :question :stepCount :summary :verdict}`；`GET /api/trace?id=…` → 完整校验结果。

## P2 解释：`arch-view notes`

```json
{
  "id": "web-model-overview",
  "title": "web.model 做什么",
  "targets": ["arch-view.web.model"],
  "body": "Markdown 正文",
  "anchors": [{"path": "src/arch_view/web/model.clj", "startLine": 1, "endLine": 40, "hash": "sha256:…"}],
  "author": "ai",
  "source": "explain",
  "createdAt": "…",
  "commit": "…"
}
```

- `targets` 为模块名或包前缀（保存时经 `resolve-target` 规范化）；`anchors` 是解释所依据的代码区间，可为空。
- `notes save <草稿.json> [--force]`：同 trace。输入锚点若已带哈希，保存前保留并检查原始内容；只移动时更新行号，内容已变或文件消失时返回 409，要求重新解释或核对。
- `notes list [--target 目标]`：目标为包时也列出其下成员模块的解释，模块时也列出其祖先包的解释。
- `notes check [--fix-moved] [--strict]`：逐条检查锚点，整条解释状态取最差锚点（`missing` > `stale` > `moved` > `fresh`；无锚点为 `unanchored`）。
- 接口：`GET /api/notes[?target=…]` → 解释列表（含正文和状态）；`POST /api/note`，请求体为草稿 JSON → 保存（`id` 缺省时由标题生成，冲突时追加序号），返回保存结果。

## 实现约束

- 纯静态、只读源码；只有 `trace save`、`notes save`、`--fix-moved`、`POST /api/note` 会写 `.arch-view/`，不改其它文件。
- 单个函数圈复杂度不超过 12（`crap.toml`）；文件开头写一两行中文职责注释（`;; 职责：…`）。
- 不新增第三方依赖；JSON 用 `arch-view.web.json`。
- 测试用 Speclj，放在 `spec/arch_view/<feature>/`，用临时目录构造小项目或直接构造 `ws`（`{:architecture {:graph {...} :layout {...} :classified-edges [...] :guidance {...}} :root path}`）。
