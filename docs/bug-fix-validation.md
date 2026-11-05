<!-- 职责：记录 docs/bug.md 所列缺陷的 mock 回归范围与用户项目验收步骤。 -->

# 入口扫描缺陷修复验证

本次基于 `3bca111` 修复 BUG-01～07。验证使用仓库中的独立 Java mock 与假 ACP 进程，没有远程 `medusergrowcenter` 业务源码，真实项目仍由用户验收。

| 缺陷 | 修复与回归 |
| --- | --- |
| BUG-01 | 显式遍历 Java 子节点，避免重复 `scan(TreePath)` 清空父路径，以及 `proxy-super` 递归跳过同名 visitor。覆盖嵌套 lambda、方法引用、匿名回调及后续普通调用。 |
| BUG-02 | `methods.names` 与 XML `entryMethods.names` 精确选取当前类 public、非 static、非 abstract 方法，严格校验合法方法名。7 个任务 mock 只产生 7 个 `handle` 入口，getter 与非 public 重载不计入。 |
| BUG-03 | 无法从源码定位外部继承方法的 RPC 发布类保留未解析声明、类锚点及专门警告；不把 protected hook 当作公开入口。请求其方法链返回 422 与原因，不触发建图。 |
| BUG-04 | 方法候选按 `[type method arity]` 建索引并缓存回退查询；直接绑定业务子系统的入口不建立方法图，需追踪调用的入口仍保留方法归属。源码、调用解析和建图的进度与耗时只写 stderr。 |
| BUG-05 | 区分有规则证据的 `suspect` 警告与仅按类名猜测的 `suspect-name` 信息；JSON、Markdown 保留全部条目。回归覆盖 28 个命名候选及 12 个框架候选。 |
| BUG-06 | JSON 字符串正则改用占有量词，避免长文本回溯栈溢出。覆盖 10,000 / 500,000 字符、长转义文本及非法转义、控制字符、未闭合字符串。 |
| BUG-07 | 匿名、局部类使用源码位置标识，保留重写方法和业务调用；模板回调参数连接标为虚线并说明不确定性，避免全仓匿名实现混入每个入口。覆盖 RPC / HTTP 模板、缺外部依赖的 diamond 写法、同名局部类、链路接口业务节点与方法级业务分组。 |

主要 mock 位于 `spec/arch_view/entries/callback_mock_spec.clj`、`declaration_suspect_mock_spec.clj`、`performance_mock_spec.clj`；方法选择与 XML 回归位于已有规则测试中。

## 验证环境与性能范围

环境为 OpenJDK 25.0.4.1、Clojure 1.11.1，JVM 堆上限 768 MiB。全量单测运行 `spec/arch_view`，结果为 **525 个用例、2,265 个断言、0 失败、0 错误**；桌面测试使用 Xvfb，Python 测试显式使用 `/usr/bin/python3`。`spec/browser` 下含独立服务启动脚本，不作为单测加载。

候选回退的隔离 mock 为 5,000 个方法节点、1,000 次同签名缺符号调用（经父类型匹配）；关闭出口匹配以单独测量 `chain/build`。相同 JDK、堆上限下，各运行 4 轮，首轮预热后中位耗时：原实现约 1,426 ms，修复后约 26 ms；两者均生成 1,000 条边。此结果不含 Java 解析、架构加载或真实项目全量耗时，不能据此承诺生产项目命令耗时。

## 用户项目验收

1. 更新工具并重新加载入口规则；规则中的 `methods.names` 现在受正式支持。AntScheduler 使用 `{"names":["handle"]}`，MsgBroker handler 同样按已读源码填写 `entryMethods.names`，事务回查使用 `onUniformEventTxSynchronized`。
2. 执行 `arch-view entries check --format json`，检查 stdout 为完整 JSON，stderr 给出分析进度；确认任务、订阅入口不再包含 getter，完整候选可逐项核查。
3. 检查 RpcProvider 的外部继承发布类是否出现未解析声明及警告。它只保留发布事实，不证明精确方法名、生产注册数量或 hook 调用关系。
4. 在网页重新加载入口，查看 `AppRecRpcImpl#appRec`、`BodyScaleBridgeController#issueBridgeMain`：核实各自 `execute/checkParams` 中的业务节点与源码锚点，检查虚线原因；不能仅凭接口返回 200 验收。
5. 复核推荐、体脂秤等入口的业务分组，以及仍在“基础设施/其它”的入口；不以 `unclassified=0` 代表全部归类正确。记录真实项目各阶段耗时，另验证一次长回答的 ACP 会话。
