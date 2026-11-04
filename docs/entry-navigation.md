# 业务入口导航：用户故事与规则契约

<!-- 职责：约定左侧导航从「包树」升级为「业务入口」的产品目标、入口识别规则格式、规则起草与校验命令、分期与验收，供实现与核对使用。 -->

**状态**：Java 的 E0～E2 功能已实现：业务入口导航、方法调用图、出口识别、分叉 trace 保存与校验。Kotlin、E3、E4 待实现；单测补齐、全量回归、§12 完整数量验收与大仓性能验证尚未进行。  
**范围**：Java / Kotlin 的 Spring 系项目；网页工作台（`arch-view serve`）与新增 `arch-view entries` 命令。Clojure / Python 暂不识别入口。  
**相关**：导航与钉住全局架构见 [探索导航用户故事](navigation-ux-stories.md)；锚点、链路格式与 `.arch-view/` 数据目录以 [AI 辅助探索设计契约](ai-exploration.md) 为准。

---

## 1. 背景与定位

右上角钉住的全局架构回答「系统由什么组成」，左侧模块树（US-1）回答「文件在哪」，和文件管理器区别不大。缺的是第三个问题：**一个请求、一次定时任务、一条消息进来后，代码怎么走。**

左侧导航因此改为两个 tab：

```text
[入口]  [目录]                                          分组来源：架构文档 ▾
[全部] [HTTP 4] [RPC-TR 29] [RPC-网关 25] [RPC-SPI 9] [OpenAPI 2] [MQ 17] [MsgBroker 2] [定时 9]
🔍 搜索路径 / 类名 / operationType / topic / 说明
▾ 事件中心 · 12
  RPC-TR    UserAtomEventFacade             原子事件写入/查询          3 ▸
  RPC-TR    UserTaskStatusRecallFacade      任务状态召回
  MQ        EventCenterUserTriggerConsumer  TP_MED_PROMO_COMMON_EVENT
  RPC-网关  EventNofifyRpc                  …app.event.nofify
▸ 阿福增长活动 · 45
▸ 基础设施/其它 · 2
▸ 测试/示例 · 2

目录：保留 US-1 的构建模块 → 包 → 文件树，用于定位
```

只有**业务域**一层折叠，布局与 §12 的真实系统样例一致：每个业务域一节，节内一行一个处理类，机制是行首标签。

- **业务域**：现有架构文档的子系统多按技术组件划分，数据流图里同时有接入层（`rpc`、`mq`、`scheduler`）、业务域（`aq`、`activecare`…）和基础设施（`dal`、`integration`）。入口归入「往下进入的业务子系统」，而不是处理类所在的包，规则见 §4.6。
- **接入机制**：由识别入口的规则决定（RPC-TR、RPC-网关、RPC-SPI…）。不是层级，而是行首标签加顶部筛选；业务域内按机制顺序排列，同类入口自然聚在一起。同一个 `rpc` 子系统里有多种机制，子系统粒度太粗，故不用接入层子系统做标签。
- **处理类**：一行一个类，显示标签（路径、operationType、topic、cron）和说明。类里只有一个入口时这行就是入口，单击定位、双击看源码；多个入口时行尾显示数量和 ▸，点开才列方法。
- 业务域默认全部展开；入口总数超过 200 时默认折叠，只显示组名和计数。搜索或筛选时自动展开有结果的组。

两个前提决定了方案：

1. **公司入口注解是对 Spring 的封装，且定义在公共 jar 里。** 分析不带 classpath，源码里看不到这些注解的定义，无法顺着元注解自动认出它是 `@RestController`。所以需要**项目级规则**，告诉工具「`com.xx.web.XxApi` 等价于 `@RestController`」。
2. **规则由 AI 起草，工具负责验证。** AI 只看每类入口的一个样例写规则，变体遗漏靠 `entries check` 的统计和告警发现，不靠 AI 通读仓库。

原则不变：**纯静态、不执行目标代码；静态确定不了的地方标出来，不猜。**

---

## 2. 用户故事

### E0 入口清单

**作为** 刚接手 Spring 多模块仓库的人，  
**我希望** 左侧直接列出全部 HTTP 接口、RPC 服务、消息消费者和定时任务，点一下跳到处理方法，  
**以便** 从业务动作而不是包结构开始读代码。

| ID | 需求 | 验收 |
| :--- | :--- | :--- |
| E0-1 | 内置 Spring 及常用框架规则（见 §4.3），无需配置即可识别标准注解 | 标准 Spring Boot 示例项目打开后，入口 tab 列出所有 `@GetMapping` 等方法，路径为类前缀与方法路径拼接 |
| E0-2 | 读取项目规则 `.arch-view/entry-rules.json`，规则可把自定义注解映射到内置语义 | 夹具里的 `@XxApi` + `@XxGet` 被识别为 HTTP 入口，结果与等价的 Spring 写法一致 |
| E0-3 | 入口 tab 按业务域分节（§4.6），节内一行一个处理类、行首为机制标签，多入口的类点开看方法；组名显示计数；支持按路径、类名、方法名、operationType、topic、cron、说明搜索，搜索时保留分组 | 输入 `bodyscale` 只留下体脂秤相关的 RPC-网关与 HTTP 入口及其所在分组 |
| E0-4 | 单入口行或展开后的方法行单击打开调用链（E1）；多入口类的类名单击仍定位处理类，双击打开源码到方法行 | 复用现有主图、`locateModule` 与源码抽屉，不新建源码视图 |
| E0-5 | 规则文件缺失、格式错误或某条规则失效，不影响其它功能 | 规则错误时入口 tab 顶部显示错误和「重新加载」，内置规则的结果照常显示，代码图正常 |
| E0-6 | `arch-view entries list / candidates / check` 命令（§5） | AI 只靠这三个命令和读样例源码就能写出规则，`check` 无告警 |
| E0-7 | 业务域按 §4.6 从项目规则、架构文档、注解分组属性或推断得出；跨多个业务域的入口在每个组下都出现并标「多归属」；只进入基础设施子系统的进「基础设施/其它」；完全对不上文档的进「未归类」 | 见 §12.2 的归属用例 |
| E0-8 | 二级接入机制取命中规则的 `channel`；内置规则有默认机制名；都没有时用入口类型（HTTP / RPC / 消息 / 定时 / 启动 / 事件） | 同为 `rpc` 类型的 TR、网关、SPI、OpenAPI 入口分成四个机制组 |
| E0-9 | 一级分组来源可切换，同一时间只用一种来源，避免不同来源的组名混排；业务组来自架构文档时，单击组名在钉住的全局架构上高亮该子系统 | 切到「注解分组」后列表按 `@Tag` 名称重排；切回不丢选中的入口 |
| E0-10 | 列表顶部显示机制筛选按钮（每种机制的入口数），点击只看该机制，可多选 | 数字与 `entries check` 的机制总览一致 |
| E0-11 | 入口说明：`summary` 属性 → 方法 Javadoc 首句 → 处理类 Javadoc 首句（复用 `javadoc.clj`）；都没有时不显示，不让 AI 生成 | 有 Javadoc 的 Facade 在列表里直接显示一句说明 |
| E0-12 | 规则 `aside` 按类名把测试、示例入口收进单独的折叠组，不删除；不内置默认通配 | `RpcDemo`、`BusinessTimeTestRpc` 出现在「测试/示例」；类名带 Test 的业务入口 `ChallengeV4WeightLossTestFacade` 仍在阿福增长活动 |
| E0-13 | 订阅写在 Spring XML、由公共 listener 按 key 分发给 handler 的入口（§4.7）：每个 XML 订阅一个入口，挂在 key 对得上的 handler 上；详情列出 XML 位置、分发者和 key 常量位置；两处对不上时 `check` 报警 | MsgBroker 两个订阅显示为 `TP_C_USER / EC_SNS_INSPIRE_PRIZE_NOTICE` → `SendPrizeMessageHandler`、`TP_C_BILL / EC_member_benefit_event` → `MsgBrokerPublishHealthGiftPackImpl`；改动任一 handler key 的一个字符后，`check` 同时报「订阅无人处理」和「handler 收不到消息」 |

**不是**：不推断运行时路由（网关转发、动态注册的 Servlet、`RouterFunction` 函数式路由在 E0 不识别）；不读 jar、不读 `application.yml` 里的 `server.servlet.context-path`。

### E1 入口链路图

**作为** 盯着某个接口的人，  
**我希望** 点开入口就看到「这个方法往下调到了哪些类、哪些方法」，  
**以便** 不用自己在 Controller → Service → Impl → Mapper 之间来回跳。

| ID | 需求 | 验收 |
| :--- | :--- | :--- |
| E1-1 | 建方法级调用图：只记录解析到项目源码内的方法调用 | 同一个 Controller 的两个接口，链路互不混入对方调用的类 |
| E1-2 | 接口调用按类层次展开到项目内实现（CHA）；唯一实现画实线，多实现画分叉并标「多实现」 | `OrderService.create` 只有 `OrderServiceImpl` 一个实现时，链路直通到实现类 |
| E1-3 | 按类分泳道展示，方法作为泳道内的步骤；默认深度 6、节点上限 60，超出注明「还有 N 个」并可展开 | 中心 Service 被几十个入口共用时，单个入口的链路仍可读 |
| E1-4 | 静态不确定的边用虚线并标原因：多实现、反射、`@Async`、事件发布、Lambda / 方法引用跨线程 | 图上实线全部对应源码中可解析的调用 |
| E1-4b | 按 key 分发的入口，链路从分发者（如 `CommonListener`）开始，到 handler 的边画虚线并标「按 key 分发」和 key 值 | 静态调用图里看不到的 Map 分发不会让链路断开 |
| E1-5 | 节点单击看详情、双击打开处理方法源码；点连线或详情中的调用位置打开调用方的对应行；可退出链路回到当前包视图 | 手势与代码依赖图一致，返回时恢复原画布与选中项 |
| E1-6 | 钉住的全局架构高亮这条链路经过的宏观模块（复用 US-3 反向定位） | 链路跨三个子系统时，pin 上三个节点同时标出 |

E1 取代 [US-2 叶子全链路](navigation-ux-stories.md)：入口链路是方法级、有方向的业务切片，比类级 deps / rdeps 的并集更准。

### E2 出口与另存链路

| ID | 需求 | 验收 |
| :--- | :--- | :--- |
| E2-1 | 规则可声明出口（`"role": "exit"`）：下游服务客户端（`@FeignClient`、公司 RPC 客户端）、数据库（`@Mapper`、`JpaRepository`）、消息发送（`KafkaTemplate.send` 等；XML 声明的 `<sofa:publisher>` 用 §4.7 的 `xml` 规则读取） | 链路末端显示「下游服务 user-service」「DB」「topic order-paid」而不是断在接口上 |
| E2-2 | 「另存为链路」把当前入口链路写成 `.arch-view/traces/<id>.json`，走现有 `trace save` 校验 | 保存后 `trace verify` 能逐跳核对；默认不写盘 |

### E3 跨入口串联（远期）

消息发送与消费按 topic 常量对上、Feign 客户端与 Controller 按路径对上，拼出跨服务业务流。误连风险高，只连两端都是**字面量或可解析常量**且完全相等的情况，并标「推断」。

### E4 读取公共 jar 的元注解（远期，可选）

用户通过 `--classpath` 或配置提供 jar（如本机 Maven 仓库中的公司公共包），工具只读注解的元数据，顺着元注解自动认出 `@XxApi` → `@RestController`，减少需要手写的规则。不执行 jar 中的代码，也不解析构建脚本推断依赖。

---

## 3. 入口数据模型

```json
{
  "id": "http:GET:/api/orders/{id}#order-api.com.xx.order.web.OrderController.get",
  "kind": "http",
  "label": "GET /api/orders/{id}",
  "module": "order-api.com.xx.order.web.OrderController",
  "buildModule": "order-api",
  "handler": {"type": "com.xx.order.web.OrderController", "method": "get", "params": ["java.lang.Long"]},
  "anchor": {"path": "order-api/src/main/java/com/xx/order/web/OrderController.java", "startLine": 42, "endLine": 58, "symbol": "OrderController#get"},
  "attrs": {"httpMethod": "GET", "path": "/api/orders/{id}", "group": "订单管理"},
  "summary": "按 id 查询订单详情",
  "tags": [],
  "channel": {"id": "http", "label": "HTTP", "source": "builtin"},
  "domains": {
    "architecture": [{"id": "order", "label": "订单"}],
    "annotation": [{"id": "订单管理", "label": "订单管理"}],
    "inferred": [{"id": "orders", "label": "orders"}]
  },
  "rule": "xx-get",
  "warnings": []
}
```

| 字段 | 说明 |
| :--- | :--- |
| `kind` | `http`、`rpc`、`mq`、`schedule`、`startup`、`event` |
| `label` | 列表显示文字：HTTP 为「方法 路径」，定时为 cron 或 `fixedRate 5000`，消息为 topic，RPC 为「接口#方法」 |
| `module` | 处理类对应的图节点名，可直接传给 `/api/locate` |
| `anchor` | 处理方法的区间，格式同 `arch-view.anchor`（输出时不带 `hash`，另存为链路时再补） |
| `attrs` | 规则抽取出的属性，键名见 §4.4 |
| `summary` | 入口说明，来源顺序见 E0-11 |
| `tags` | 展示用标签，如「顺序消费」「广播」「filter=new」，来自规则 `attrs` 的 `flag:` / `extra:` 映射 |
| `channel` | 二级接入机制；`source` 为 `rule`（项目规则）、`builtin`（内置规则默认名）或 `kind`（按入口类型） |
| `domains` | 各来源得出的业务域，键为 `rules`、`architecture`、`annotation`、`inferred`，没有结果的来源省略（即该来源下「未归类」）；同一来源多个值即多归属。`architecture` 的 `id` 为子系统标识，供网页在钉住的架构上高亮；`{"id": "_infra", "label": "基础设施/其它"}` 表示只落在非业务子系统。下划线开头的 id 保留给这类特殊组 |
| `aside` | 可选。命中规则 `aside` 时为分组名，此时没有 `domains` |
| `rule` | 命中的规则 id；内置规则为 `builtin:<id>` |
| `warnings` | 本入口的问题，如 `unresolved-attr`（属性用了解析不了的表达式） |
| `sources` | 可选。入口声明分在多处时列出各处锚点：`{"kind": "xml" \| "dispatcher" \| "dispatch-key", "anchor": {…}}`，见 §4.7 |

`id` 由 `kind`、规范化标签和处理方法组成，同一源码重复分析时保持稳定，用于网页记住选中项和另存链路。消息入口的标签包含 topic、group 和 tag，同一 topic 的多个消费方（如主链路与只落库分支）是不同入口；一个类订阅多个 topic 时每个订阅一个入口。

RPC 入口的标签：有 `operation`（网关 operationType、OpenAPI 接口名）时用它，否则用「接口#方法」。

---

## 4. 规则契约

### 4.1 文件位置与原则

- 项目规则写在 `<项目根>/.arch-view/entry-rules.json`，随项目提交，团队共享。与全局 `.env` 无关。
- 规则**只能声明，不能执行**：没有脚本、表达式求值或正则匹配源码文本；模板只做 `{字段}` 替换，XML 路径只支持元素名、`..` 和 `@属性`。
- 未知字段、未知 `as` 值、重复 `id` 一律报错（指出规则 id 和字段），不静默忽略，避免 AI 写错后看起来「没效果」。
- 项目规则在内置规则之后应用；同一个方法被多条规则命中时取项目规则，并在 `check` 中提示覆盖。
- `"disableBuiltin": ["spring-scheduled"]` 可关闭指定内置规则（如公司封装后原注解另有含义）。

### 4.2 格式

```json
{
  "version": 1,
  "disableBuiltin": [],
  "rules": [
    {"id": "xx-api",  "annotation": "com.xx.web.XxApi",  "as": "spring:RestController",
     "attrs": {"value": "prefix", "path": "prefix"}},
    {"id": "xx-get",  "annotation": "com.xx.web.XxGet",  "as": "spring:GetMapping",
     "attrs": {"value": "path"}},
    {"id": "xx-route", "annotation": "com.xx.web.XxRoute", "as": "spring:RequestMapping",
     "attrs": {"url": "path", "verb": "httpMethod"}},
    {"id": "xx-task", "annotation": "com.xx.job.XxTask", "as": "kind:schedule",
     "attrs": {"cron": "cron", "name": "name"}},
    {"id": "xx-mq",   "annotation": "com.xx.mq.XxConsumer", "on": "class", "as": "kind:mq",
     "attrs": {"topic": "topic", "group": "consumerGroup"},
     "methods": {"implementsOf": "com.xx.mq.MessageHandler"}},
    {"id": "xx-rpc",  "implements": "com.xx.rpc.RpcProvider", "as": "kind:rpc",
     "methods": "public"},
    {"id": "xx-rpc-ann", "annotation": "com.xx.rpc.XxProvider", "as": "kind:rpc",
     "methods": {"interfaceMethods": true}},
    {"id": "xx-doc",  "annotation": "com.xx.doc.ApiGroup", "on": "class", "as": "meta",
     "attrs": {"value": "group"}}
  ],
  "entryLayers": ["rpc", "mq", "scheduler"],
  "domainSubsystems": ["aq", "eventcenter"],
  "domains": [
    {"id": "order", "label": "订单", "paths": ["/api/orders"], "packages": ["com.xx.order"], "topics": ["order-paid"]}
  ],
  "aside": [
    {"label": "测试/示例", "classes": ["RpcDemo", "BusinessTimeTestRpc"]}
  ]
}
```

顶层 `entryLayers`、`domainSubsystems`、`domains` 都可省略，用法见 §4.6。`aside` 的 `classes` 匹配处理类或其父类型（如 Facade 接口）的简单名或全名，支持 `*` 通配，命中的入口只进该组。按名称通配容易误伤（业务类名也可能含 Test），建议写具体类名。

| 字段 | 必填 | 说明 |
| :--- | :--- | :--- |
| `id` | 是 | `[a-z0-9][a-z0-9-]{0,63}`，文件内唯一 |
| `annotation` / `implements` / `extends` / `xml` | 四选一 | 注解或父类型的**全限定名**。`implements`、`extends` 匹配项目内沿父类和接口向上的全部父类型（含外部 jar 类型，按导入解析全名），只对具体类生效；`xml` 见 §4.7 |
| `role` | 否 | 默认 `entry`；`exit` 表示出口，见 §4.8；出口不生成入口行 |
| `type` / `method` / `args` | 否 | 仅出口：`type` 按接收者或声明类型全名匹配（代替 annotation/implements/extends/xml），`method` 限定方法名，`args` 把从 0 起的参数映射到语义键 |
| `label` | 否 | 入口或出口标签模板，`{字段}` 替换为提取字段，如 `{topic} / {eventCode}`；字段缺失时回退默认标签 |
| `dispatch` | 否 | 按 key 分发，目前只能与 `xml` 一起使用，见 §4.7 |
| `on` | 否 | `class` 或 `method`；出口另可写 `field` 且默认 `class`。入口 `as` 为 `spring:*` 时由被映射注解决定，其余注解入口默认 `method` |
| `as` | 是 | 映射到的语义，见 §4.3 |
| `channel` | 否 | 二级接入机制名，如 `RPC-TR`、`RPC-网关`；省略时沿用映射目标的内置机制名或入口类型 |
| `when` | 否 | 属性条件，全部满足才命中。键为属性路径，`.` 进入嵌套注解，数组任一元素满足即可；值为字面量（相等）、`{"in": […]}`、`{"not": …}`、`{"present": true}`。例：`{"bindings.bindingType": "tr"}` |
| `attrs` | 否 | 「注解属性名 → 语义键」，见 §4.4；未写的属性忽略 |
| `defaults` | 否 | 语义键的默认值，如 `{"httpMethod": "POST"}`（公司注解默认 POST 时） |
| `methods` | 类级规则用 | 哪些方法算入口：`"public"`（类中声明的公有非静态方法）、`{"interfaceMethods": true}`（实现的项目内接口中声明的方法；外部接口取带 `@Override` 的公有方法）、`{"interfaceFromAttr": "interfaceType"}`（接口由注解属性的 `X.class` 给出）、`{"implementsOf": "<全名>"}`（只取实现该接口的方法）、`{"annotation": "<全名>"}`（只取带该注解的方法） |

方法级注解也在处理方法重写的**项目内接口方法**上查找（如网关的 `@OperationType` 常写在接口上）。入口挂在实现方法上；多个实现各一个入口并带 `multi-impl` 警告，没有实现时挂在接口方法上并带 `no-impl` 警告。

### 4.3 `as` 取值

**映射到内置注解（`spring:*`）**：公司注解行为与某个 Spring 注解一致时用，路径拼接、HTTP 方法、类与方法的组合规则全部复用内置实现。

| 值 | 等价于 |
| :--- | :--- |
| `spring:RestController`、`spring:Controller` | 类成为 Web 控制器 |
| `spring:RequestMapping` | 类上为路径前缀，方法上为入口；`httpMethod` 缺省为 `ANY` |
| `spring:GetMapping`、`PostMapping`、`PutMapping`、`DeleteMapping`、`PatchMapping` | 方法级入口，HTTP 方法固定 |
| `spring:Scheduled` | 定时任务，属性 `cron` / `fixedRate` / `fixedDelay` |
| `spring:KafkaListener`、`spring:RabbitListener`、`spring:EventListener` | 消息 / 事件消费 |

**直接声明类型（`kind:*`）**：`kind:http`、`kind:rpc`、`kind:mq`、`kind:schedule`、`kind:startup`、`kind:event`。不做路径拼接，类级规则的 `prefix` 与方法级的 `path` 仍会拼接。出口额外支持 `kind:db`，并只接受 `http`、`rpc`、`mq`、`db` 四种类型。

**只补属性（`meta`）**：不产生入口，只给类或方法上已识别的入口补属性，主要用于 `group`、`summary`。类上的值作用于类中全部入口，方法上的值优先。

**内置规则**（E0），每条带默认机制名：

| id | 识别 |
| :--- | :--- |
| `spring-mvc` | `@RestController` / `@Controller` 类内带 `@RequestMapping` 系列注解的方法；WebFlux 注解写法相同 |
| `sofa-service` | `com.alipay.sofa.runtime.api.annotation.SofaService`，只收 `bindings` 中有非 `jvm` 绑定的服务（只有 jvm 绑定是进程内服务，不是入口）；方法取 `interfaceType` 接口，机制名为 `SOFA-<bindingType>` |
| `jaxrs` | `@Path`（`javax.ws.rs` / `jakarta.ws.rs`）及 `@GET` 等 |
| `spring-scheduled` | `@Scheduled` |
| `spring-kafka`、`spring-rabbit`、`rocketmq` | `@KafkaListener`、`@RabbitListener`、`@RocketMQMessageListener`（类级，取 `onMessage`） |
| `spring-event` | `@EventListener`、`@TransactionalEventListener` |
| `dubbo` | `@DubboService`，以及 `com.alibaba.dubbo.config.annotation.Service` / `org.apache.dubbo.config.annotation.Service`（只认全名，不与 Spring `@Service` 混淆） |
| `grpc` | 继承以 `ImplBase` 结尾的外部类型的类，取其重写方法 |
| `xxl-job`、`quartz`、`elastic-job` | `@XxlJob`；实现 `org.quartz.Job`；实现 `SimpleJob` / `DataflowJob` |
| `startup` | `public static void main(String[])`、Kotlin 顶层 `main`；实现 `CommandLineRunner` / `ApplicationRunner` |
| `openapi`（`meta`） | Swagger / OpenAPI 的 `@Tag(name)`、`@Api(tags)`、`@Operation(tags, summary)`、`@ApiOperation(value)`，补 `group` 和 `summary` |

### 4.4 语义键与属性取值

| 语义键 | 用于 |
| :--- | :--- |
| `prefix` | 类级 HTTP 路径前缀 |
| `path` | 方法级 HTTP 路径；数组展开为多个入口 |
| `httpMethod` | `GET` 等；支持 `RequestMethod.GET` 这类枚举常量，取最后一段 |
| `cron`、`fixedRate`、`fixedDelay`、`name` | 定时任务 |
| `topic`、`consumerGroup`、`tag` | 消息（`group` 留给业务分组，消费组用 `consumerGroup`） |
| `service`、`version`、`uniqueId` | RPC |
| `operation` | RPC 操作名：网关 operationType、OpenAPI 接口名等，作为入口标签 |
| `flag:<显示名>` | 属性为真或存在时加标签，如 `{"orderly": "flag:顺序消费"}` |
| `extra:<显示名>` | 属性值以「显示名=值」加标签，如 `{"filter": "extra:filter"}` |
| `group` | 业务分组名，用于 §4.6 的「注解分组」来源；数组为多归属 |
| `summary` | 入口说明，列表悬停和搜索使用 |

属性值只做静态求值：

1. 字符串、数字、布尔字面量，枚举常量（取名称），类字面量 `X.class`（解析为全名），嵌套注解，以及它们组成的数组；
2. 项目源码内的 `static final` 常量（Kotlin `const val`），包括跨类引用和 `+` 拼接；
3. 其它情况（方法调用、外部 jar 常量、`${...}` 占位符）原样保留源码文本，入口加 `unresolved-attr` 警告，`label` 用原文显示。占位符 `${order.topic}` 不去读配置文件。

注解属性名省略时（`@XxGet("/x")`）视为 `value`。

### 4.5 名称解析

不带 classpath 时注解类型解析不出来，按源码写法确定全名：

1. 写的是全名（`@com.xx.web.XxApi`）→ 直接比对；
2. 有单类型导入 `import com.xx.web.XxApi` → 全名为导入名；
3. 同包中存在同名类型 → 该类型；
4. 只有通配导入 `import com.xx.web.*`，且规则全名的包在通配导入中、简单名一致 → 视为命中；多个通配包都可能时不命中，计入 `check` 的歧义告警；
5. Kotlin 另支持导入别名 `import com.xx.web.XxApi as Api`。

项目源码内定义的注解按元注解自动展开（例如仓库里自己定义了带 `@RestController` 的注解），无需规则。

### 4.6 业务分组

**二级：接入机制（`channel`）**：命中规则的 `channel` → 内置规则默认机制名 → 入口类型。与子系统无关。

**接入层子系统**（只用于计算业务域）：

1. 规则写了 `entryLayers`（子系统 id 列表）时以它为准；写 `[]` 表示没有接入层。
2. 未写时自动判定：子系统含有入口，且数据流图中指向它的边全部来自没有代码绑定的外部节点（如 `client`、`upstream`）。夹具中 `rpc`、`mq`、`scheduler` 满足。
3. 子系统归属按文档绑定（`#module=`、覆盖范围，与 US-3 反向定位同一套匹配）。

**一级：业务域（`domains`）**，四种来源各自计算，网页同一时间只用一种：

| 来源 | 计算方式 | 默认优先级 |
| :--- | :--- | :--- |
| `rules` | 规则文件 `domains`：入口路径前缀（按 `/` 分段匹配）、处理类包前缀、topic 全等，任一命中即归入；多条命中取前缀最长的，同长则多归属 | 1 |
| `architecture` | 见下文，基于架构文档的子系统绑定和数据流图 | 2 |
| `annotation` | 属性 `group`（内置 `openapi` 或项目规则映射得到） | 3 |
| `inferred` | HTTP 取路径中去掉 `api`、版本号（`v1` 等）后的第一段；其它入口取处理类包名去掉 `web`、`controller`、`api`、`rpc`、`facade`、`impl`、`job`、`mq`、`consumer`、`listener` 等技术词后的最后一段。界面标「推断」 | 4 |

默认来源取「至少把一半入口归入两个以上业务域」的最高优先级来源，都不满足时取 `inferred`；覆盖率按 `aside` 以外的入口计算，「基础设施/其它」不算归入业务域。网页可切换并按项目记住选择。

子系统、数据流的读取与网页一致：项目级架构文档「核心子系统与职责」下的小节，标题标识对应流程节点，探索链接 `#module=` 与「覆盖范围」给出代码路径（图节点名去掉命名空间根层后按路径段前缀匹配，取最具体的）；数据流取「数据流」小节中的 Mermaid flowchart，只认 `a -->|文字| b` 连线。

`architecture` 来源的计算：

1. **业务子系统**：数据流图中接入层节点的直接下游，且有代码绑定、本身不是接入层的子系统。夹具中为 `aq`、`eventcenter`、`attribute`、`activecare`、`rec`、`benefit`；`dal`、`integration` 不是接入层的直接下游，不算。规则可用 `"domainSubsystems": ["aq", …]` 覆盖。
2. **处理类本身在业务子系统内**：直接归入该子系统（如放在 `core/service/eventcenter/` 下的 Consumer）。
3. **否则向下找**：从处理类出发沿依赖边向下走，只穿过接入层子系统内或没有文档归属的模块，遇到业务子系统的模块即记录并停止该分支，遇到其它有绑定的子系统也停止；最多 3 跳。记录到的全部业务子系统即该入口的业务域，多于一个为多归属。处理类放在异名子包（如 `facade/impl/aq/EventCenterDataFacadeImpl`）时仍按实际进入的子系统归类。E0 用类级依赖图，并补上「项目内接口或抽象类 → 唯一具体实现」的边（多个实现时不补，避免公共接口把入口带进所有子系统）；当前 Java 已改用该入口方法的调用图，唯一实现直达、多实现保留多归属，不混入同类其它入口方法的调用。
4. **没有接入层时**（按业务拆包的项目，`entryLayers: []`）：处理类所在的子系统即业务域。
5. **基础设施/其它**：没走到业务子系统，但处理类本身有文档归属（含接入层），或下游落在其它有绑定的子系统（如 `integration`、`dal`）。处理类本身在非业务、非接入层的子系统时直接归入此组，不再向下找。
6. **未归类**：处理类及 3 跳内下游都没有文档归属。

`aside` 命中的入口不参与以上计算。

这样分组只依赖文档里人工声明过的绑定和数据流，不按名称相似度猜子系统。

### 4.7 XML 声明与按 key 分发

有些入口没有注解承载订阅信息。以 MsgBroker 为例，声明分在两处，靠手工保持一致：

| 内容 | 位置 | 地位 |
| :--- | :--- | :--- |
| 订阅关系：consumer group、topic、eventCode、listener bean | Spring XML（`biz-service-uniform-event.xml`） | 唯一事实源 |
| 分发：`CommonListener` 按 `topic + "_" + eventCode` 查 `messageHandlerMap` | `CommonListener.java:63`，map 由 `@Autowired List<UniformEventMessageHandler>` 按 `getHandlerKey()` 建成（`:91`） | 运行时机制 |
| handler key：手写字符串 `"TP_C_USER_EC_SNS_INSPIRE_PRIZE_NOTICE"` | 各 handler 的 `getHandlerKey()` | 冗余副本 |

注解（`CommonListener` 上的 `@SofaService`、handler 上的 `@Service`）只用于装配 bean，不能作为入口依据。规则用 `xml` 取订阅、用 `dispatch` 对接 handler：

```json
{
  "id": "msgbroker-sub",
  "xml": {
    "files": ["**/spring/*.xml"],
    "each": "sofa:consumer/sofa:channels/sofa:channel/sofa:event",
    "fields": {
      "consumerGroup": "../../../@group",
      "topic": "../@value",
      "eventCode": "@eventCode",
      "bean": "../../../sofa:listener/@ref"
    }
  },
  "as": "kind:mq",
  "channel": "MsgBroker",
  "label": "{topic} / {eventCode}",
  "dispatch": {
    "key": "{topic}_{eventCode}",
    "handlers": {
      "implements": "<UniformEventMessageHandler 全名>",
      "keyMethod": "getHandlerKey",
      "entryMethods": {"interfaceMethods": true}
    }
  }
}
```

上例的 XML 路径是示意，以实际文件结构为准，由 AI 读一次该 XML 后填写。

**`xml` 声明**

- `files`：相对各构建模块 `src/main/resources` 的通配；不在主资源目录下的文件忽略。不跟随 `<import resource>`。
- `each`：第一段在文档任意位置匹配，其后逐段为子元素；每个匹配到的元素是一个单元。一个 consumer 下多个 channel / event 时自然得到多个单元。元素名按文件中的写法（含前缀）匹配。
- `fields`：相对单元的路径，`..` 为父元素，`名称` 为第一个同名子元素，`@属性` 取值；取不到时该单元带 `unresolved-attr`。字段名即语义键（§4.4），另有专用字段 `bean`。
- `bean` 解析为类：同批 XML 中 `<bean id="…" class="…">` → 源码中显式命名的 `@Service("…")` / `@Component("…")` 等 → Spring 默认名（类简单名首字母小写，`commonListener` → `CommonListener`）。没有 `dispatch` 时，入口挂在该类上，方法按 `methods` 取。
- 入口 `sources` 记录 XML 单元所属顶层元素（如 `<sofa:consumer>`）的行区间。
- 没有 `each` 可以直接写顶层元素，如 `"each": "sofa:service"`，也用于 XML 声明的 RPC 服务。

**`dispatch` 分发**（目前只支持与 XML 规则组合）

- `key`：用单元字段或注解属性拼出的模板。
- `handlers`：项目内实现 `implements` 接口的类；`keyMethod` 的返回值按 §4.4 静态求值（方法体须为单个 `return` 字面量、常量或其拼接）；`entryMethods` 同 `methods` 写法。
- 入口挂在 key 一致的 handler 的入口方法上；处理类取 handler，业务分组从 handler 算。`sources` 另记分发者（`bean` 对应的类）和 `keyMethod` 的返回语句位置。
- 以 XML 为准：入口标签和属性取 XML 字段；key 对不上时仍生成入口、挂在分发者上并带 `orphan-subscription` 警告；反过来没有订阅的 handler 不生成入口，只在 `check` 中报 `unsubscribed-handler`。

**发布侧**：`<sofa:publisher>`（如 `TP_S_CHARGE`）是出口，归 E2。其 `tx-callback` 指向的 bean（`hdfVouchersBillTxCallbackListener`）由消息中间件回调，是事务消息的兜底回查，默认不列为入口；需要时可用同样的 `xml` 规则读取：`"each": "sofa:publisher"`、`"bean": "@tx-callback"`、标签 `{topic} 事务回查`。

---

### 4.8 出口规则（E2）

出口沿方法调用出现，不加入入口计数。内置规则包括 `@FeignClient`、MyBatis `@Mapper`、Spring Data `Repository` / `JpaRepository` / `CrudRepository`、Dubbo/SOFA 字段引用、`JdbcTemplate`、`KafkaTemplate.send`、RabbitMQ `convertAndSend`、RocketMQ `convertAndSend` / `syncSend`。公司包装使用同一个规则文件声明：

```json
{"id": "bank-client", "role": "exit", "annotation": "com.xx.rpc.Remote", "on": "field",
 "as": "kind:rpc", "attrs": {"service": "service", "version": "version"}}
```

- `role` 默认 `entry`。出口的 `as` 仅为 `kind:http`、`kind:rpc`、`kind:db`、`kind:mq`；字段引用写 `on: "field"`，类型注解默认 `class`，方法注解可写 `method`。
- `annotation` 仍复用全名解析、元注解、`when`、`attrs` 与 `defaults`；`implements` / `extends` 可识别项目内接口继承的外部 Repository；直接调用外部类时用 `type`，与其它匹配方式互斥。
- `method` 可限定一次调用的方法名；`args` 为「从 0 起的参数序号 → 语义键」。参数只做静态求值，不解析配置或执行表达式，例如：

```json
{"id": "company-mq-send", "role": "exit", "type": "com.xx.mq.MessagePublisher", "method": "send",
 "as": "kind:mq", "args": {"0": "topic"}}
```

XML 发布侧按 bean 名关联调用方字段（支持 `@Qualifier` / `@Resource` 指定名称），并从声明中读取 topic：

```json
{"id": "msgbroker-out", "role": "exit", "as": "kind:mq", "method": "publish",
 "xml": {"files": ["spring/publishers.xml"], "each": "sofa:publisher",
         "fields": {"bean": "@id", "topic": "sofa:channel/@value"}}}
```

XML 路径以实际文件为准。没有 bean 名到调用字段的明确关联时，不把该声明猜连到任意发送方法；`tx-callback` 仍不列入口。出口节点保留调用位置、属性、规则和可选 XML 声明位置；属性未解析时显示原表达式并提示 `unresolved-attr`。出口规则在链路中匹配，不参与入口规则的 `zero-hit` 统计。

---

## 5. 命令：`arch-view entries`

命名空间 `arch-view.entries.*`，按 [设计契约](ai-exploration.md) 的子命令约定实现（`run!`、`--format md|json`、公共参数）。

| 命令 | 输出 |
| :--- | :--- |
| `entries list [--kind http\|rpc\|mq\|schedule\|startup\|event] [--module <构建模块>] [--domain <业务域 id 或名称>] [--group-by rules\|architecture\|annotation\|inferred] [--limit N]` | 入口列表（§3 数据模型），按业务域 → 接入机制 → 处理类排列，业务域按入口数降序，其后是「基础设施/其它」、`aside` 分组、「未归类」；多归属入口在每组都出现并标「多归属」，`--domain` 只显示该组；不传 `--group-by` 时用默认来源 |
| `entries candidates [--limit N]` | 疑似入口但当前规则没覆盖的注解与父类型统计，供 AI 挑样例 |
| `entries check [--strict]` | 规则校验与覆盖报告；`--strict` 有告警时退出码 2 |

### 5.1 `candidates`

统计项目源码中**类上**和**方法上**出现的注解、类直接声明的外部父类型，按全名分组，输出：

```text
注解 com.xx.web.XxGet  方法 ×214  示例 order-api/.../OrderController.java:42
  属性：value, desc    同时出现：com.xx.web.XxApi(类)
注解 com.xx.job.XxTask  方法 ×9   示例 job/.../ReconcileJob.java:18
  属性：cron, name
父类型 com.xx.rpc.RpcProvider  类 ×31  示例 user-rpc/.../UserProviderImpl.java:15
```

- 排除已被规则（内置或项目）消费的注解和父类型；
- 排除常见非入口注解：Spring 装配类（`@Service`、`@Component`、`@Autowired`、`@Bean`、`@Configuration`、`@Value`、`@Transactional` 等）、Lombok、校验（`@NotNull` 等）、序列化、日志、`@Override`；
- 按「方法级 → 类级 → 父类型」及出现次数排序，默认前 30 条；
- 每条最多给 1 个示例位置和见过的属性名，不贴源码；
- 取值种类不超过 8 种且有重复取值的字面量属性（含一层嵌套注解属性）附取值分布，如 `bindings.bindingType: tr ×29, jvm ×120`，供 AI 写 `when` 和 `channel`；每个注解最多 3 项；
- 写在项目内接口方法上的注解按「接口方法」单独计数。

另列资源目录 XML 中带命名空间前缀的声明级元素（根元素的子元素，跨过不带前缀的容器），排除 `context:`、`aop:`、`tx:` 等 Spring 基础命名空间和已被 `xml` 规则读取的元素，最多 15 条，如 `sofa:consumer ×2`。

另附默认来源下「未归类入口」的 HTTP 路径前两段和处理类包名统计（各前 10），供 AI 起草 `domains`；没有未归类入口时不输出。

### 5.2 `check`

| 检查 | 级别 |
| :--- | :--- |
| 规则文件格式、未知字段、未知 `as`、重复 id | 错误，退出码 1 |
| 每条规则的命中数及前 3 个示例 | 信息 |
| 规则命中 0 个 | 警告 |
| 属性解析失败（`unresolved-attr`）的入口数及示例 | 警告 |
| 同一 HTTP 方法 + 路径、同一 operation 出现在多个处理方法 | 警告（可能是前缀没取到） |
| 同一 topic 有多个消费方 | 信息（列出 group / tag 区别） |
| `multi-impl`、`no-impl` 的接口方法入口 | 警告 |
| `orphan-subscription`：XML 订阅找不到 key 一致的 handler（消息到达后无人处理） | 警告，列出 XML 位置和最接近的 handler key |
| `unsubscribed-handler`：handler 的 key 不在任何 XML 订阅中（永远收不到消息） | 警告 |
| `duplicate-key`：多个 handler key 相同（建 map 时会覆盖或报错） | 警告 |
| key 方法返回值无法静态求值；`bean` 解析不到类；XML 文件解析失败（跳过该文件） | 警告 |
| 通配导入导致的歧义 | 警告 |
| 项目规则覆盖了内置规则 | 信息 |
| 接入层、业务子系统的判定结果及依据（自动判定或规则指定） | 信息 |
| 各业务分组来源的覆盖率、多归属入口数，以及当前默认来源 | 信息 |
| 默认来源下「未归类」超过 20% | 警告 |
| `domains` 中命中 0 个入口的业务域；`entryLayers` / `domainSubsystems` 中不存在的子系统 id；`aside` 命中 0 个 | 警告 |
| **疑似遗漏**：类名以 `Controller`、`Api`、`Endpoint`、`Facade`、`Provider`、`Job`、`Task`、`Listener`、`Consumer`、`Handler` 结尾，但没有任何入口的类 | 警告，列前 10 个 |

开头输出机制总览表（同 §12.1 格式：机制、规则、数量），最后一行输出汇总：`入口 97（RPC-TR 29 / RPC-网关 25 / MQ 17 / …），业务域 6，未归类 0，警告 2`。

---

## 6. 技能流程：AI 起草项目规则

写进 `skills/arch-view/SKILL.md`，用户本机的 agent 执行（网页 AI 在只读沙箱中，只给出草稿，见 §7）：

1. `arch-view entries check`：先看内置规则已识别多少，有没有规则文件，以及接入层、业务子系统的判定是否与 `ARCHITECTURE.md` 数据流图的含义一致；不一致时写 `entryLayers` / `domainSubsystems` 纠正。
2. `arch-view entries candidates`：从统计里按 HTTP、RPC、消息、定时**各挑一个**最可能的候选。
3. 每个候选只读示例位置附近的代码（处理方法及所在类的注解），判断语义，写一条映射规则并给出 `channel` 机制名；能映射到 `spring:*` 时优先映射。同一注解按属性区分机制时（如 `bindingType`），按 `candidates` 的取值分布写多条带 `when` 的规则。
4. 写入 `.arch-view/entry-rules.json` 后再跑 `check`：
   - 命中 0 → 全名或 `on` 写错；
   - `unresolved-attr`、重复路径 → 再读 1 个示例补 `attrs` / `defaults`；
   - 疑似遗漏 → 读其中 1 个，判断是新入口写法还是误报。
5. 重复第 4 步直到没有警告，或剩余警告已确认是误报；最多三轮，之后把剩余问题交给用户。
   - 「未归类」超过 20% 且文档子系统分不出业务时，按 `candidates` 的未归类统计起草 `domains`，业务名用中文，拿不准的写「待确认」交给用户。
6. 回答中贴出 `check` 汇总和规则文件，由用户确认后提交。

预算原则：AI 读的是统计结果和少量样例，不 grep 全仓、不逐个打开 Controller。

---

## 7. 网页接口

| 接口 | 说明 |
| :--- | :--- |
| `GET /api/entries` | `{:entries [...] :rules {:file "…" :status "ok\|missing\|error" :errors ["…"]} :grouping {:sources [{:id "architecture" :coverage 0.82 :domains 6 :multi 0 :unclassified 0}] :default "architecture" :entryLayers {:ids [...] :basis "auto\|rule"} :domainSubsystems {:ids [...] :basis "downstream\|no-entry-layer\|rule"}} :channels [{:label "RPC-TR" :rules ["sofa-tr"] :count 29}] :summary {...} :warnings [...]}`；分组在网页端按选中来源组装；首次读取时计算并在当前分析状态中缓存，重新分析时刷新 |
| `POST /api/entries/reload` | 只重新读取规则文件与源码语法树、重算入口并替换缓存，不重新做项目依赖分析；响应结构同 `GET /api/entries` |
| `GET /api/entry/chain?id=…[&depth=N&limit=N]` | `{:entry {...} :root "方法 id" :lanes [...] :nodes [...] :edges [{:from "…" :to "…" :style "solid\|dashed" :reason "…" :anchor {...}}] :modules [...] :depth 6 :limit 60 :remaining 0 :frontier [...] :summary {...} :unresolved [...]}`；按入口与范围缓存最多 64 份子图 |
| `POST /api/entry/chain/save` | JSON `{id, title?, entryId, depth?, limit?}`；按服务端子图生成 trace、补锚点并校验，返回既有 trace 校验结构；同 id 返回 409，源码改变后返回 409 要求重新加载；默认不写盘 |
| `POST /api/entry-rules/draft`（E0 之后） | 网页 AI 生成的规则草稿经格式校验后保存；与 `POST /api/note` 一样只写 `.arch-view/` |

网页的「业务入口 / 代码目录」页签和分组来源按项目记住选择。首次打开 Java 项目时，有入口默认选业务入口，无入口选代码目录；读取失败保留重试入口，非 Java 项目继续使用代码目录。

业务域默认展开，测试/示例等 `aside` 分组默认收起。每个处理类一行、机制为行首标签，多入口类点三角看方法；机制按钮可多选，搜索保留业务分组并展开匹配的方法。单入口行或方法行单击打开方法链路，多入口类的类名仍定位处理类；双击在既有源码抽屉打开到方法行。行末详情按钮展示入口说明、属性与警告，以及 XML、分发者、key 的来源位置；来源位置复用源码抽屉。切换分组来源保留已选入口，单击架构来源的业务组会在钉住的项目数据流里高亮并居中。

方法图按类分泳道、按最短调用深度排列，排列不表示运行顺序。默认显示 6 层、60 个方法/出口，继续展开每次增加 3 层、60 个节点，最多 30 层、500 个节点；剩余数为当前入口全部可达节点中未显示的数量。默认保持可读比例，可手动适应全图。节点详情列出相关调用及虚线原因，钉住架构同时标出链路经过的源码子系统；「返回包视图」恢复打开前的画布。保存仅保存当前显示范围，保留分叉与虚线，不默认覆盖已有链路。

---

## 8. 实现要点

- **E0 不改已有依赖图**。入口注解、常量和 Javadoc 用一次只解析语法的 JavacTask；E1 另建隔离 classpath 的 JavacTask 做方法符号归属与类型层次分析，关闭注解处理、不生成 class、不执行目标代码。有架构绑定时方法图用于入口分组，否则在首次打开调用链时建立；网页在同一份分析状态内复用这份图。`POST /api/entries/reload` 重读规则和源码、使方法图与子图缓存一起失效，不重算原来的类级架构分析。Kotlin 支持尚未实现。
- **入口说明**：源码语法树用 `DocTrees` 读取 Javadoc，复用 `web/javadoc.clj` 提取首句；优先 `summary` 属性，其次方法 Javadoc（自身没有时查对应父类型方法），最后处理类 Javadoc。没有说明时留空。
- 命令与其它 AI 探索子命令一致，项目目录用 `--project-path` 指定。
- **E1 方法调用解析**：
  - Java：`visitMethodInvocation` / `visitNewClass` / `visitMemberReference` 取 `ExecutableElement`，用擦除后的完整参数类型区分重载；缺依赖导致符号不完整时，只按已声明接收者的项目内方法匹配并标虚线。普通外部或未解析调用不扩展，在详情中列出；识别到的出口和反射目标单独显示。
  - `Elements.overrides` 与具体类型继承方法用于 CHA：唯一代码实现为实线，多实现为虚线；`@Async`、事件发布、Lambda/方法引用的执行时机未确定时保留原因。仅在项目源码内展开，不读 jar 推测运行时实现。
  - Kotlin：待实现，后续用 PSI 和显式声明类型推断接收者。
- **XML 读取**：只读规则 `files` 匹配、位于主资源目录、不超过 1 MB 的文件；用 JDK SAX 解析并由 `Locator` 记录行号；不加载外部 DTD 与外部实体（实体解析返回空内容）、关闭 XInclude、开启安全处理限制实体展开，不联网。允许内部 DOCTYPE，兼容老式 DTD 头的 Spring XML。只给入口识别用，不进入依赖图。
- **性能目标（待大仓验证）**：入口识别与调用图各自在 ≥5000 个 Java 文件的仓库中增加的分析时间不超过 20%；入口列表一次返回，方法图复用、子图按入口与范围缓存。
- **保存一致性**：解析时记录源码、规则文件与匹配 XML 声明的内容指纹，保存时再次核对，拒绝把旧调用图按新输入补锚点；trace 的节点与分叉边由服务端生成。
- 约束同 [设计契约](ai-exploration.md)：圈复杂度 ≤12、中文职责注释、不新增第三方依赖、Speclj 测试。规则匹配、属性求值、名称解析各自有单元测试；夹具项目包含「公共 jar 自定义注解」场景（注解定义不在源码中）。

---

## 9. 分期

| 顺序 | 内容 | 依赖 |
| :--- | :--- | :--- |
| E0a | 规则模型、名称解析、属性求值；内置 Spring 规则；`entries list` / `check` | — |
| E0b | 项目规则文件、`candidates`、技能流程；`xml` 声明与 `dispatch` 分发及一致性检查 | E0a |
| E0c | 业务分组：接入层与业务子系统判定、四种来源、`check` 分组报告 | E0a |
| E0d | 网页入口 tab：机制筛选、按业务域分节的列表、来源切换、搜索、定位到处理方法、pin 高亮业务组 | E0b、E0c |
| E1 | 方法级调用图、CHA、链路视图、pin 高亮 | E0 |
| E2 | 出口规则、另存为链路 | E1 |
| E3 / E4 | 跨入口串联；读 jar 元注解 | E2 |

---

## 10. 非目标

- 不读 `application.yml` / Nacos 等配置求值占位符；不推断 `context-path` 和网关前缀。
- 不识别函数式路由（`RouterFunction`）、动态注册的 Servlet / 消息监听器（E0、E1 均不做）。
- 不通用地解析 Spring XML（不建 bean 图、不跟随 import、不求值 `${…}`）；只按项目规则的 `xml` 声明抽取字段。
- 不在链路里用 AI 补静态图上没有的调用。
- 不支持 Clojure / Python 入口（后续另立文档）。
- 不改桌面 Quil 窗口。

---

## 11. 待决

- 已采用的默认页签：识别到入口时选「业务入口」，否则选「代码目录」；按项目记住用户选择。
- `check` 的「疑似遗漏」后缀表是否允许项目规则文件里追加（如公司约定 `*Rest`）。
- 已采用的多归属展示：在每个业务组下重复显示，标「多归属」。
- 「未归类」20% 的警告阈值是否合适。

---

## 12. 验收样例：真实系统的目标效果

以下是用户提供的 medusergrowcenter 入口梳理结果（人工 + AI 整理），E0 的输出应与之对齐。夹具用同构的脱敏小项目复现这些机制和归属关系，不复制业务代码。

### 12.1 机制总览与规则要点

| 机制（`channel`） | 识别方式 | 规则要点 | 数量 |
| :--- | :--- | :--- | :--- |
| HTTP | `@RestController` | 内置 `spring-mvc` | 4 个端点 |
| RPC-TR | `@SofaService` + `bindingType="tr"` | `when: {"bindings.bindingType": "tr"}`，`methods: {"interfaceFromAttr": "interfaceType"}`，`channel` 覆盖内置名 | 29 |
| RPC-网关 | `@OperationType`（mobilegw） | 方法级，多写在接口方法上；`value` → `operation` | 25 |
| RPC-SPI | `@RpcProvider`（外部平台扩展点） | 类级；`uniqueId` → `uniqueId`，`interfaceType` → 方法来源 | 9 |
| OpenAPI | `@OpenapiProcessor`（superapi） | 方法级；接口名 → `operation` | 2 |
| MQ | SOFAMQ `@MessageConsumer` | `topic`、`group`；`filter` → `extra:filter`；顺序、广播 → `flag:` | 17 个消费方法 |
| MsgBroker | XML `<sofa:consumer>` 订阅 + `CommonListener` 按 `topic_eventCode` 分发 | `xml` + `dispatch`（§4.7）；注解只装配 bean，不作依据 | 2 个订阅 |
| 定时 | 实现 `ISimpleJobHandler`；`@Scheduler` | 两条规则同用 `channel: "定时"`；cron 能静态求值时显示 | 6 + 3 |

公共 jar 中注解的全名由 `candidates` 给出，AI 不凭记忆填写。

### 12.2 业务分组用例

一级分组为架构文档中的业务子系统：事件中心、阿福增长活动、归因与投放、主动关怀、推荐与卡片、会员权益与交易，另有「基础设施/其它」和「测试/示例」。

| 入口 | 处理类位置 | 期望归属 | 依据（§4.6） |
| :--- | :--- | :--- | :--- |
| `UserAtomEventFacade`（RPC-TR） | rpc 层 `eventcenter/impl/` | 事件中心 | 向下进入 eventcenter |
| `EventCenterDataFacade`（RPC-TR） | rpc 层 `aq/` 子包 | 事件中心 | 按实际进入的子系统，不按包名 |
| `UserTaskStatusUpdateConsumer`（MQ） | `core/service/eventcenter/` | 事件中心 | 处理类本身在业务子系统内 |
| `TaskRpc`（RPC-网关，3 个 operation） | rpc 层 | 阿福增长活动 | 一行显示类，点开是 3 个方法 |
| `TP_C_USER / EC_SNS_INSPIRE_PRIZE_NOTICE`、`TP_C_BILL / EC_member_benefit_event`（MsgBroker） | XML 订阅，handler 分别为 `SendPrizeMessageHandler`、`MsgBrokerPublishHealthGiftPackImpl` | 会员权益与交易 | 从 handler 算归属；两个订阅共用 `commonListener`，仍是两个入口 |
| `EventCenterUserTriggerConsumer` / `EventCenterStoreOnlyConsumer` | mq 层 | 事件中心 | 同 topic、不同 group，是两个入口，不报重复 |
| `UserGrowthCoreConsumer` | mq 层 | 阿福增长活动 | 订阅两个 topic，生成两个入口 |
| `CareActionConsumer` | mq 层 | 主动关怀 | 带「顺序消费」标签 |
| `MedContentSyncConsumerV2` | `common/service/integration` | 基础设施/其它 | 只落在 integration |
| `RpcDemo`、`BusinessTimeTestRpc` | — | 测试/示例 | `aside` |
| `ChallengeV4WeightLossTestFacade`、`ActiveCareV2TestFacade` | rpc 层 | 阿福增长活动、主动关怀 | 不在 `aside` 中，按进入的子系统归类；类名含 Test 不影响 |

验收：同构夹具上 `entries check` 的机制总览数量与 §12.1 一致；§12.2 每行归属正确；默认分组来源为 `architecture`，「未归类」为 0。
