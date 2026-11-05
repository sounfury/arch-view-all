# Woodpecker 质量检查模板

统一生成认知复杂度、圈复杂度、嵌套深度、参数个数、文件行数、死代码、重复代码、密钥扫描这八个独立工作流。支持 Clojure 和 Kotlin；普通回归测试继续由项目自行配置。本项目的原有测试移到了 `.woodpecker/回归测试.yaml`。

Woodpecker 3.19 的普通 YAML 没有跨仓库 `include` / reusable workflow。官方的[配置扩展](https://woodpecker-ci.org/docs/usage/extensions/configuration-extension)可以通过 HTTP 服务集中生成流水线。本模板选择不增加常驻服务：一个生成器、一份项目策略和共享分析镜像即可接入；生成结果纳入 Git，方便审查。

## 当前项目

策略在 [`../../quality/woodpecker.json`](../../quality/woodpecker.json)，生成后执行：

```bash
python3 ci/woodpecker/generate.py --update
woodpecker-cli lint --strict .woodpecker/
```

回归流水线会运行 `generate.py --check`，防止策略和生成结果不一致，并运行生成器、死代码过滤和 Clojure 计分样例的测试。

## 新项目复用

在这台机器上直接调用此模板，指定项目、语言和生产源码目录：

```bash
quality_template=/home/ubuntu/project/arch-view-all/ci/woodpecker

# Clojure
python3 "$quality_template/generate.py" \
  --project /path/to/project --profile clojure --source src

# Kotlin（可以是另一个仓库的 backend 子目录）
python3 "$quality_template/generate.py" \
  --project /path/to/project --profile kotlin --source backend
```

已有根目录 `.woodpecker.yaml` 或 `.woodpecker.yml` 时，加 `--migrate-tests`，生成器会将它原样移动到 `.woodpecker/回归测试.yaml`。生成器默认拒绝覆盖已有配置；确认修改后使用 `--update`。它不启用远端仓库、不提交、不推送；完成审查后正常提交这些文件，并在 Woodpecker 中启用仓库即可。

生成器和分析工具的源码集中在本目录。以后接同类项目只需上面的命令；升级模板后，对项目策略执行同一个生成命令即可。Kotlin 模板沿用 ZhiYing 的 Detekt 2.0.0-alpha.6 镜像及规则参数，并显式启用 `--analysis-mode full`，防止 `light` 模式跳过需要类型解析的规则。本次没有改动 ZhiYing 的现有配置。

Kotlin 策略额外支持 `kotlin_classpath` 列表，默认使用 CLI jar 内的 Kotlin 标准库。业务项目需要把编译后的依赖 jar / classes 路径加入此列表，并在工作流中准备这些依赖，才能获得完整的类型解析；模板不会自动推断各项目的 Gradle 构建方式。已使用干净样例验证正常代码通过、超过 5 个参数时 `LongParameterList` 实际失败。

## 每个 Agent 只需准备一次镜像

当前服务器已构建 `local/clojure-quality:1.0.0`，所有 Clojure 项目共享，不会每次 CI 安装分析器。新增 Agent 时构建一次：

```bash
sudo docker build -t local/clojure-quality:1.0.0 ci/woodpecker
```

镜像固定了 crap-everything 的提交、clj-kondo 版本和 rewrite-clj 版本，构建时执行计分测试。修改分析器后应更新镜像版本、重新构建，并更新生成器的 `IMAGE`；不要让旧版本标签悄悄指向不同规则。基础 Clojure 镜像跟随指定 JDK 25 标签。Kotlin Agent 需要现有的 `local/detekt-cli:2.0.0-alpha.6` 镜像。两个本地镜像都设置了 `pull: false`，不会从远端拉取同名镜像；新机器需先准备镜像。

## 阈值和语言口径

| 检查 | 默认阈值 | Clojure 后端 | Kotlin 后端 |
| --- | --- | --- | --- |
| 认知复杂度 | 最大 15 | 下述源码结构计分 | Detekt CognitiveComplexMethod |
| 圈复杂度 | 最大 12，12 通过 | crap-everything / crap4clj | Detekt CyclomaticComplexMethod |
| 嵌套深度 | 最大 4 | 决策结构深度 | Detekt NestedBlockDepth |
| 参数个数 | 最大 5 | 每个 arity，取最大值 | Detekt LongParameterList；构造器 6 |
| 文件行数 | 最大 600 | Clojure 文件物理行数 | Detekt LargeClass 的类行数 |
| 死代码 | 0 条发现 | clj-kondo unused 系列 | Detekt unused 系列 |
| 重复代码 | 0% | jscpd 5.4.1 | jscpd 5.4.1 |
| 密钥扫描 | 0 条命中 | gitleaks 8.30.1 全部默认规则 | 同左 |

这些阈值沿用 ZhiYing。工具对语言的语义不同，数字不能直接用于跨语言排名。

Clojure 认知复杂度是本模板明确约定的结构指标，不声称与 Detekt 或 Sonar 完全等价：

- `if`、`when` 及其变体、`cond`、`case`、条件线程、循环、`for`、`doseq`、`catch` 等，每个结构计 `1 + 外层决策深度`；`and` / `or` 每个表达式加 1。
- 嵌套深度只计算上述决策结构，`let`、`do` 和函数外壳不增加深度；`cond` / `case` 整体作为一个结构。
- `defn`、`defn-`、`defmacro`、`defmethod`、`fn` 和 `#()` 分别统计；内层函数不会重复计入外层函数。多 arity 参数取最大值，解构参数算一个参数，`& rest` 算一个可变参数。
- 忽略字符串、注释、引用数据、syntax quote、`#_` 和 `comment`。不加载生产命名空间、不做宏展开，宏生成的控制流不在这个静态口径内。
- 圈复杂度使用项目现有 `crap.toml` 相同的 crap4clj 计分后端，只统计其支持的顶层 `defn` / `defn-`；生成器将最大 12 换算成 CLI 的 `--fail-on-complexity 13`。判定覆盖全部函数，日志最多展示前 50 项。
- 死代码检查只报告 unused binding/private var/namespace/import/referred var；同时保留语法错误和工具故障，其他 lint 维度不冒充死代码。反射和外部动态使用仍需人工判断。
- 复杂度、参数、嵌套和行数检查生产 Clojure 源码；`duplication_sources` 可单独指定 JS/Python/Java 等生产目录。重复代码使用最少 5 行、50 token。

## 已核实误报的精确豁免

在项目策略 `quality/woodpecker.json` 的可选 `secret_allowlists` 中记录已审查的误报，再运行生成器。每条必须包含 `description`（核查依据）、`rule`（规则 ID）、`path`（完整项目相对路径）和 `value`（扫描器提取的原始字面值，不是正则表达式）。

生成器将路径和值转义并加上首尾锚点，以 `condition = "AND"` 同时限定规则、文件和值；新项目默认没有豁免。本项目只豁免 Mermaid 12.1.0 中已与官方 npm 文件逐字节核对过的一段变量赋值，依据及 SHA-256 保存在策略说明中。

不要把真实凭据写入豁免。凭据应撤销并轮换；误报才使用精确例外。不要用整个 `vendor/` 路径、某类扩展名或关闭 `generic-api-key` 来处理这一项。豁免不依赖行号；该文件内出现其他密钥仍会失败，库升级后匹配内容变了则需要重新核查。

## 跳过和不阻断

与 ZhiYing 使用相同的手动流水线变量：

| 检查 | 跳过 | 不阻断 |
| --- | --- | --- |
| 认知复杂度 | `SKIP_COMPLEXITY=true` | `COMPLEXITY_NONBLOCK=true` |
| 圈复杂度 | `SKIP_CYCLOMATIC=true` | `CYCLOMATIC_NONBLOCK=true` |
| 嵌套深度 | `SKIP_NESTING=true` | `NESTING_NONBLOCK=true` |
| 参数个数 | `SKIP_PARAMS=true` | `PARAMS_NONBLOCK=true` |
| 文件行数 | `SKIP_FILE_LENGTH=true` | `FILE_LENGTH_NONBLOCK=true` |
| 死代码 | `SKIP_DEAD_CODE=true` | `DEAD_CODE_NONBLOCK=true` |
| 重复代码 | `SKIP_DUPLICATION=true` | `DUPLICATION_NONBLOCK=true` |

默认全部阻断。密钥扫描没有跳过或不阻断开关，不生成 baseline；日志全量脱敏，同时配置完整 Git 历史和工作区扫描。默认规则对第三方压缩文件也可能报警，只有显式记录在项目策略中的已核实误报才生成精确 allowlist。

接入门禁不会顺便重构现有超标代码。CI 中的红色检查和对应文件行号就是待处理问题；检查器故障、配置错误、没有可分析源码都会失败，不按通过处理。
