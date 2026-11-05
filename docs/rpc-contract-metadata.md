<!-- 职责：约定 RPC 接口契约与执行实现的展示元数据、说明优先级，以及 BUG-08 的回归与验收范围。 -->

# RPC 接口契约与实现说明

接口上的入口注解和 Javadoc 属于服务契约，调用图继续从真实实现方法展开。业务代码不需要复制注释或移动注解。

## 字段与位置

`/api/entries` 和 `/api/entry/chain` 保留技术身份，并提供下列说明字段。业务名称仅在已映射且非空时展示；不生成不存在的业务说明。

| 字段 | 含义 |
| --- | --- |
| `businessName` | 当前入口规则映射出的 `name`，例如“服务推荐Rpc”。列表和根卡片优先展示它，同时保留技术方法名。 |
| `summary` | 入口列表中的说明；调用图节点上的当前有效说明，优先级见下文。 |
| `methodSummary` | 方法 Javadoc 的有效说明，与入口注解的说明分开保留。 |
| `entrySummary` | 仅在当前入口处理节点上保留原始入口说明；实现自己的 Javadoc 优先显示时仍可查看它。 |
| `description` | 规则映射出的入口 `summary` 属性，例如 OperationType 的 `desc`。 |
| `docSource` / `methodDocSource` | 当前说明、方法 Javadoc 的来源，包含 `kind`、声明类型、方法、参数和真实源码锚点。 |
| `contractSource` / `contractAnchor` | 实际命中的接口或父类契约及其位置；接口锚点不会替换实现锚点。 |
| `implementationAnchor` | 真实实现方法的源码位置。抽象声明没有可定位的执行实现时为空。`anchor` 仍保留节点自身的位置。 |
| `docStatus` / `docCandidates` | 方法说明为自身、继承、缺失或歧义；多个来源的候选锚点供复核。相同说明可以展示，但保留所有来源。 |
| `contractStatus` / `contractCandidates` | 同一规则、operation 与实现对应多个契约时，显式标记 `ambiguous`，保留候选及 `ambiguous-contract` 警告，不任取一个接口。 |
| `operation` / `handler.signature` | 方法图处理节点保留当前 operation；入口 handler 额外保留完整参数类型，用于定位同简单名重载。 |

类名、方法名、完整签名、operation 与规则属性仍可在详情查看和复制。卡片和详情的“查看接口定义／查看父类定义”与“查看实现”分别打开各自文件和方法区间。

## 说明优先级

1. **入口列表**保持既有规则语义：明确映射的 `summary` 属性 → 方法有效 Javadoc → 处理类 Javadoc。方法自身有效 Javadoc 优先于接口或父类说明。
2. **调用图中的当前入口处理节点**：实现方法自己的有效 Javadoc → 当前入口的说明 → 方法图中已确认的继承说明。入口注解说明另存为 `entrySummary`，与方法说明一起在详情展示。
3. **普通中间方法**：自身有效 Javadoc → 真实重写关系中最具体的接口或父类 Javadoc。空正文或只有 `{@inheritDoc}` 不阻止回退。
4. 入口继承说明优先使用识别该入口时实际命中的契约；不按父类型全名排序取第一个。普通方法存在多个不同说明时，摘要留空并提供来源候选。多个来源的内容一致时可展示一致文本，但不伪造唯一来源。

源码阶段保留完整参数类型和类型参数，沿继承链替换泛型实参，避免“方法名＋参数数量”把重载混在一起。建立方法图时使用 Javac `Elements.overrides` 校验真实重写关系，不从静态同名方法继承说明。缺失依赖、无法确认的关系仍保留静态分析边界。

入口业务元数据只绑定到当前入口的响应切片，不写回共享方法节点。同一实现可以对应多个规则或机制；入口 ID 包含规则标识以避免相同 operation 的碰撞。更新工具后应重新加载入口列表，旧的临时入口 ID 不再用于请求新链路。

项目规则示例（注解全名按实际项目填写）：

```json
{"id":"gateway-operation","annotation":"com.example.OperationType","as":"kind:rpc","channel":"网关 RPC",
 "attrs":{"value":"operation","name":"name","desc":"summary"}}
```

未映射的自定义属性不会自动变成业务名。仅有 `value` 时保留 operation，并使用有效 Javadoc；没有说明时正常显示技术方法名和参数。

## 回归与真实项目验收

后端夹具为 `spec/arch_view/entries/contract_fixture.clj`，测试为 `contract_docs_spec.clj`。11 项专项回归覆盖接口注解与 Javadoc、中间实现方法、实现说明优先、多级泛型替换、数组泛型与前向类型界限、同简单名且同一行的重载、父类说明、静态方法隔离、多入口机制、契约冲突及一致文档的多来源。

本次全量 `spec/arch_view` 验证结果为 **536 个用例、2,346 个断言、0 失败、0 错误**；真实 HTTP 服务与 Chromium 的浏览器回归通过。JavaScript 语法检查、修改的 JS/CSS 主动 LSP 诊断及 `git diff --check` 通过。

浏览器回归为 `spec/browser/entry_contract_test.cjs`：启动真实 Clojure HTTP 服务，用 Chromium 检查实际列表、卡片和详情文本；核对接口、实现按钮打开的文件与高亮起始行；交错切换多个入口，并验证普通中间节点不受入口元数据污染。通过 `PLAYWRIGHT_MODULE` 指定可用 Playwright 运行库；`CHROMIUM_EXECUTABLE` 可指定浏览器。无 Clojure CLI 时可设置包含 `src`、`spec` 及依赖的 `ARCH_VIEW_TEST_CLASSPATH`。

真实项目验收仍以 `AppRecRpcImpl#appRec` 为准：重新加载入口后，检查业务名称、说明和来源；分别点击接口与实现，核对文件、方法和行号；检查链路中间方法的说明。不能只凭 HTTP 200 验收，也不能把 mock 结果当作远程业务源码验证。
