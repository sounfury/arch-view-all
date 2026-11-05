<!-- 职责：指导 AI 用 arch-view entries 的统计与检查命令，为公司自定义入口注解、XML 声明的订阅和业务分组起草项目入口规则，并以检查结果为准迭代。 -->

# 起草业务入口规则

`arch-view entries` 识别 HTTP、RPC、消息、定时、启动和事件入口。Spring、JAX-RS、Kafka、RabbitMQ、RocketMQ、Dubbo、SOFA、gRPC、XXL-JOB、Quartz、ElasticJob 等标准写法由内置规则识别；公司在公共 jar 里封装的注解、写在 Spring XML 里的订阅，要在项目根写规则 `.arch-view/entry-rules.json`。当前只支持 Java。

原则：**读统计、只看样例、以检查为准。** 不 grep 全仓、不逐个打开 Controller；注解全名取自 `candidates` 输出，不凭记忆填写；规则写完必须跑 `check`。

## 流程

1. `arch-view entries check`：看内置规则已识别多少、有无规则文件、有无报错；看「接入层与业务子系统的判定」是否符合 `ARCHITECTURE.md` 数据流图的含义，不符合时写 `entryLayers` / `domainSubsystems` 纠正（见下文「业务分组」）。
2. `arch-view entries candidates`：规则未覆盖的注解（含层级、属性名、低基数属性的取值分布、同类上常见的其它注解）、外部父类型、XML 声明元素。按 HTTP、RPC、消息、定时**每类挑一个**最像入口的候选。
3. 每个候选只读示例位置附近的代码：处理方法、所在类的注解；接口方法上的注解看一个实现类。判断它属于哪种入口、哪些属性是路径/topic/cron，写一条规则并给出机制名 `channel`（如 `RPC-TR`、`RPC-网关`）。
4. 写入 `.arch-view/entry-rules.json`，再跑 `arch-view entries check`，按警告修改：
   - `zero-hit`：全名、`on` 或 `when` 写错；
   - `unresolved-attr`：属性用了外部常量或方法调用，确认无法静态求值后保留；
   - `duplicate-route`：类前缀没取到，补类级规则的 `attrs`；
   - `suspect`：有入口规则证据但未识别出入口，检查方法选择与规则；`suspect-name` 只是按类名推断的信息，先核实是否为内部处理器，不要为消除提示虚构入口。JSON、Markdown 均保留完整候选；
   - `ambiguous-doc` / `ambiguous-contract`：多个接口或父类的说明或契约来源冲突，按候选锚点复核，不通过复制注释到 Impl 消除问题；
   - `unresolved-declaration`：类上已声明发布 RPC，但入口方法继承自外部依赖，当前源码无法定位。保留声明并交由用户复核，不把 protected hook 当成公开入口，也不把声明数当作精确方法数；
   - `orphan-subscription` / `unsubscribed-handler` / `duplicate-key`：XML 订阅与 handler key 不一致，这是代码问题，列给用户，不要改规则掩盖；
   - `unclassified`：默认来源下超过 20% 的入口未归类。先看架构文档的子系统是否绑定了代码（`#module=`、覆盖范围）；文档分不出业务时，按 `candidates` 末尾的「未归类入口」统计起草 `domains`，业务名用中文，拿不准的写「待确认」交给用户；
   - `unknown-subsystem` / `domain-zero-hit` / `aside-zero-hit`：id、路径或类名写错。
5. 重复第 4 步，最多三轮；剩余警告逐条说明原因交给用户。
6. 回答中贴出 `check` 的机制总览、业务分组表和规则文件，由用户确认后提交。

## 规则写法

```json
{
  "version": 1,
  "rules": [
    {"id": "xx-api", "annotation": "com.xx.web.XxApi", "as": "spring:RestController", "attrs": {"value": "prefix"}},
    {"id": "xx-get", "annotation": "com.xx.web.XxGet", "as": "spring:GetMapping", "attrs": {"value": "path"}},
    {"id": "sofa-tr", "annotation": "com.alipay.sofa.runtime.api.annotation.SofaService", "on": "class",
     "as": "kind:rpc", "channel": "RPC-TR", "when": {"bindings.bindingType": "tr"},
     "attrs": {"interfaceType": "service"}, "methods": {"interfaceFromAttr": "interfaceType"}},
    {"id": "mobilegw", "annotation": "com.xx.gw.OperationType", "as": "kind:rpc", "channel": "RPC-网关",
     "attrs": {"value": "operation", "name": "name", "desc": "summary"}},
    {"id": "sofamq", "annotation": "com.xx.mq.MessageConsumer", "as": "kind:mq", "channel": "MQ",
     "attrs": {"topic": "topic", "group": "consumerGroup", "orderly": "flag:顺序消费", "filter": "extra:filter"}},
    {"id": "simple-job", "implements": "com.xx.job.ISimpleJobHandler", "as": "kind:schedule", "channel": "定时",
     "methods": {"names": ["handle"]}}
  ]
}
```

- 匹配方式三选一：`annotation`、`implements`（含经由项目内父类实现）、`extends`；或用 `xml` 读 XML 声明（见下文）。
- `as`：行为与某个 Spring 注解一致时优先映射到 `spring:RestController`、`spring:RequestMapping`、`spring:GetMapping` 等，路径拼接复用内置逻辑；否则用 `kind:http|rpc|mq|schedule|startup|event`；只补 `group`、`summary` 用 `meta`。
- `on`：`class` 或 `method`，`kind:*` 的注解规则默认 `method`。写在接口方法上的注解会落到项目内实现方法。
- `attrs`：注解属性名 → 语义键：`prefix` `path` `httpMethod` `cron` `fixedRate` `fixedDelay` `name` `topic` `consumerGroup` `tag` `service` `version` `uniqueId` `operation` `group`（业务分组）`summary`，以及展示标签 `flag:<名称>`、`extra:<名称>`。省略属性名的写法（`@XxGet("/a")`）属性名是 `value`。
- `when`：属性条件，`.` 进入嵌套注解，数组任一元素满足即可；值可写字面量、`{"in": [...]}`、`{"not": ...}`、`{"present": true}`。
- `methods`（类级规则）：`"public"`、`{"interfaceMethods": true}`（默认）、`{"interfaceFromAttr": "属性名"}`、`{"implementsOf": "全名"}`、`{"annotation": "全名"}`、`{"names": ["handle"]}`。选择器只写一种；`names` 必须是非空的合法 Java 方法名数组，精确选择当前类声明的 public、非 static、非 abstract 方法及全部同名重载，不支持通配或签名。已知任务/消息方法时优先用 `names`，避免 `getName`、`getThreadPool`、`getHandlerKey` 被算作入口。
- `defaults`：语义键默认值；`disableBuiltin`：按 id 关闭内置规则。
- 未知字段、无效取值会报错并指出规则 id；规则只能声明，不能执行代码。

## XML 订阅与按 key 分发

订阅写在 Spring XML、由公共 listener 按 key 分发给 handler 时（如 MsgBroker），先读一次该 XML 文件确定结构，再写：

```json
{"id": "msgbroker", "as": "kind:mq", "channel": "MsgBroker",
 "xml": {"files": ["**/spring/*.xml"],
         "each": "sofa:consumer/sofa:channels/sofa:channel/sofa:event",
         "fields": {"consumerGroup": "../../../@group", "topic": "../@value", "eventCode": "@eventCode",
                    "bean": "../../../sofa:listener/@ref"}},
 "label": "{topic} / {eventCode}",
 "dispatch": {"key": "{topic}_{eventCode}",
              "handlers": {"implements": "<handler 接口全名>", "keyMethod": "getHandlerKey",
                           "entryMethods": {"names": ["handle"]}}}}
```

- `files` 相对各模块 `src/main/resources`；`each` 第一段在文件任意位置匹配，其后逐段是子元素，元素名按文件写法带前缀。
- `fields` 相对每个单元：`..` 父元素、`名称` 第一个同名子元素、`@属性` 取值。`bean` 按 XML `<bean>`、`@Service("名")` 或类名首字母小写找到类。
- `dispatch.handlers.entryMethods` 与 `methods` 使用相同选择器和校验；方法名应按已读样例填写。事务回查也可在 XML 规则写 `"methods": {"names": ["onUniformEventTxSynchronized"]}`。
- 有 `dispatch` 时，入口挂在 `keyMethod` 返回值（单个 `return` 字面量或常量拼接）与 `key` 一致的 handler 上；对不上时挂在分发者上并报 `orphan-subscription`。没有 `dispatch` 时入口挂在 `bean` 类上，方法按 `methods` 取（可用于 XML 声明的 `<sofa:service>`）。

## 业务分组

`entries list` 按业务域分节，行首为机制。来源有四种，`check` 的「各业务分组来源的覆盖情况」给出每种的覆盖率和默认来源，`list --group-by` 可切换：

| 来源 | 依据 |
| :--- | :--- |
| `rules` | 规则文件 `domains`：HTTP 路径前缀、处理类包前缀、topic 全等，取最具体的 |
| `architecture` | `ARCHITECTURE.md`：接入层直接下游为业务子系统；处理类所在或沿该入口方法调用向下 3 跳进入的业务子系统即业务域，同一类不同方法可归入不同业务 |
| `annotation` | 属性 `group`（Swagger `@Tag` 等，或规则映射到 `group`） |
| `inferred` | HTTP 路径首段或包名去掉技术词后的最后一段，只作兜底 |

有架构文档时优先修文档绑定，不要用 `domains` 重写一遍文档里已有的子系统。需要时写在规则文件顶层：

```json
{
  "entryLayers": ["rpc", "mq", "scheduler"],
  "domainSubsystems": ["eventcenter", "aq"],
  "domains": [{"id": "order", "label": "订单", "paths": ["/api/orders"], "packages": ["com.xx.order"], "topics": ["order-paid"]}],
  "aside": [{"label": "测试/示例", "classes": ["RpcDemo", "BusinessTimeTestRpc"]}]
}
```

- `entryLayers`：接入层子系统 id（与文档标题标识一致）。省略时自动判定：含入口，且数据流图中只被没有代码绑定的节点（外部调用方、上游消息）指向；写 `[]` 表示按业务拆包、没有接入层，处理类所在子系统即业务域。
- `domainSubsystems`：业务子系统 id，省略时取接入层在数据流图中的直接下游。
- 只落在 `integration`、`dal` 这类非业务子系统的入口进「基础设施/其它」；完全对不上文档的进「未归类」。
- `aside`：测试、示例入口收进单独分组，`classes` 匹配处理类或其 Facade 接口的简单名或全名。写具体类名，不要用 `*Test*` 这类通配：类名带 Test 的也可能是业务入口。

## 方法调用图与出口

网页选择一个入口后，按类分泳道查看这个方法的调用。默认 6 层、60 个方法/出口，可继续展开，最多 30 层、500 个节点。单击节点看详情，双击打开处理方法；调用连线和详情中的文件位置可以打开调用方对应行。虚线会保留多实现、`@Async`、事件发布、回调、反射和按 key 分发的原因，不把这些关系当成确定的执行顺序。

RPC 注解与业务 Javadoc 只在接口上时，仍关联到真实实现。按规则映射 `name` 为业务名、`desc` 为入口说明；卡片保留技术方法名和 operation，分别提供“查看接口定义／查看实现”。实现方法有有效 Javadoc 时，方法说明优先使用它；否则回退到当前入口实际命中的契约。普通中间方法按真实重写关系回退；说明冲突时展示候选。不要要求用户把接口注释或注解复制到 Impl。

匿名/局部类使用源码位置标识保留重写方法。传入模板的回调实现用虚线连接，并说明是否调用及执行时机尚未确定；不把全仓匿名实现混到每个模板调用里。外部继承声明无法展示精确方法链时会返回专门提示。入口源码解析、Java 调用解析与方法图构建的进度及耗时写入 stderr，不影响 JSON stdout；可直接按架构绑定归类的入口不会触发全仓方法图。

Feign、MyBatis、Spring Data、JDBC、Dubbo/SOFA 引用和常见消息发送器有内置出口规则。公司包装在同一个规则文件的 `rules` 中补 `role: "exit"`，出口不计入入口数量：

```json
{"id": "remote-client", "role": "exit", "annotation": "com.xx.rpc.Remote", "on": "field",
 "as": "kind:rpc", "attrs": {"service": "service"}}
```

- 出口 `as` 为 `kind:http` / `kind:rpc` / `kind:db` / `kind:mq`。类型注解默认 `on: "class"`，字段引用用 `field`，方法注解用 `method`。
- 直接匹配外部类调用时用 `type`、`method`、`args`，例如 `{"id":"mq-send","role":"exit","type":"com.xx.MessagePublisher","method":"send","as":"kind:mq","args":{"0":"topic"}}`。参数从 0 起，只静态求值字面量和项目常量。
- XML publisher 用 `xml.fields.bean` 与调用方字段名、`@Qualifier` 或 `@Resource` 明确关联，再抽取 topic；不要把一个 publisher 声明猜连到所有发送方法。示例结构：`{"id":"mq-out","role":"exit","as":"kind:mq","method":"publish","xml":{"files":["spring/publishers.xml"],"each":"sofa:publisher","fields":{"bean":"@id","topic":"sofa:channel/@value"}}}`。XML 路径按真实文件填写。
- 出口规则在调用图中匹配，不参与入口 `zero-hit` 统计；规则格式仍用 `entries check` 校验。

「另存为链路」只保存当前显示范围，保留分叉边和虚线原因，走现有 trace 锚点与模块依赖校验。默认不写盘，同 id 不自动覆盖；源码改过后应先重载入口。保存后可用 `arch-view trace verify <id> --strict` 复核，虚线或缺少静态证据时仍返回需要复核，不要为了通过而改成实线。
