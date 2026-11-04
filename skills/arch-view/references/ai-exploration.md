<!-- 职责：指导 AI 用 arch-view 的查询、上下文包、链路和解释命令探索代码库，让解释有静态依赖可核对、能保存并发现过期。 -->

# 用 arch-view 解释代码与链路

原则：**事实先查、解释后写、结论可核对。** 依赖、层级、环、路径以 `arch-view query` 为准，不凭文件名猜测；静态依赖里查不到的调用（依赖注入、反射、消息、HTTP、动态分派）可以写，但要标明是推断。

所有命令在目标项目根执行，默认输出紧凑 Markdown，加 `--format json` 得到结构化数据；目标可写模块名、源码文件路径或包名。每条命令都会重新分析源码，可连续执行。

## 先读已有知识

```bash
arch-view notes list --target <目标>       # 已保存的解释，带“代码未变/已移动/已改动”状态
arch-view trace list                       # 已保存的链路
```

已有解释状态为“代码已改动/文件已不存在”时，不要直接引用，先核对源码再更新。

## 解释一个模块、文件或包

1. `arch-view context <目标> --question "<用户的问题>"`：一次拿到职责文档、文件头说明、上下游、复杂度、已有解释和源码。多数问题读这一份就够，不要再逐个打开上下游文件。
2. 需要更多事实时再查：
   - `arch-view query deps <目标> --depth 2` / `query rdeps <目标>`：依赖谁、被谁依赖；
   - `arch-view query path <起点> <终点>`：两处之间的依赖路径；
   - `arch-view query find <关键字>`：按名称、路径、文件头说明找模块；
   - `arch-view query module <目标>`、`query layers`、`query cycles`。
3. 回答时引用 `路径:行号`，说明在哪一层、上下游是谁；不确定的地方直说。
4. 用户认可或要求保存时，写草稿并保存为解释（见下文），以后可以复用并检查是否过期。

## 解释一条链路（如“点击按钮后发生了什么”“下单流程”）

1. 先找入口（路由、命令、事件监听、前端处理函数；Java 项目可先 `arch-view entries list` 按机制列出接口、消息消费和定时任务及其处理方法位置），用 `query find`、`query rdeps`、`query path` 和源码阅读串起每一步，每步都定位到具体文件和行区间。
2. 写链路草稿（JSON，保存在临时目录，不要放进业务代码目录）：

```json
{
  "id": "order-create",
  "title": "用户下单",
  "question": "用户点击下单后发生了什么？",
  "summary": "一两句话概述。",
  "steps": [
    {"title": "接收请求", "explanation": "做了什么、为什么。",
     "anchor": {"path": "src/order/api.py", "startLine": 20, "endLine": 48, "symbol": "create_order"}},
    {"title": "校验库存", "via": "call",
     "anchor": {"path": "src/stock/service.py", "startLine": 10, "endLine": 35}}
  ]
}
```

   `id` 用小写字母、数字和连字符；`via` 写上一步到本步的方式：`call`、`http`、`event`、`db`、`config`、`return`、`other`。
3. `arch-view trace save <草稿.json>`：补代码哈希并逐跳对照静态依赖。逐跳结果：
   - `✓ direct`、`≈ indirect`、`= same-module`：有静态证据；
   - `↩ reverse`：只有反向依赖，常见于回调或返回值，确认方向写对了；
   - `? unverified`：静态依赖里找不到。重新核对这一跳；确实是依赖注入、消息等运行时关联时保留，并在 `explanation` 写明依据；
   - `· unmapped`：一端不是已分析的源码（前端、配置等），无法校验。
   修改草稿后用 `--force` 覆盖保存。
4. 向用户讲解链路时，同时说明哪些跳有静态证据、哪些是推断；提示可在网页“调用链路”中查看叠加在架构图上的路线。

## 保存解释

```json
{
  "title": "web.model 如何生成当前层数据",
  "targets": ["arch-view.web.model"],
  "body": "Markdown 正文，引用 路径:行号。",
  "anchors": [{"path": "src/arch_view/web/model.clj", "startLine": 36, "endLine": 62}]
}
```

`arch-view notes save <草稿.json>`。`targets` 写模块名或包名；`anchors` 写解释所依据的关键代码区间（越准确，过期检测越有用）。只在用户认可或要求时保存；保存的文件在 `.arch-view/`，建议随项目提交。

## 检查是否过期

```bash
arch-view notes check            # 解释引用的代码是否变了
arch-view trace verify --all     # 链路的代码与逐跳证据是否仍成立
```

加 `--fix-moved` 把只是移动了位置的引用更新到新行号；加 `--strict` 在发现过期或未验证跳时返回非零退出码，可用于提交检查。内容已改动时，重读源码后更新解释或链路，不要只改行号。

## 改动影响

`arch-view impact`（工作区相对 HEAD）、`arch-view impact --since main`、`arch-view impact --staged`：列出改动文件对应的模块、受影响的上游模块（按距离）和改动模块参与的循环依赖。写代码前后用它说明影响面。

## 网页

`arch-view serve .` 打开后：模块详情的“AI 解释”区可以查看已保存的解释及其状态、复制上下文包，或在配置 `ARCH_VIEW_AGENT_PROVIDER=pi-acp`（需安装 `pi` 与 `pi-acp`，可用 `ARCH_VIEW_PI_COMMAND` 指定模型）后让本机 AI 多轮解释并一键保存；侧栏“调用链路”可以逐步查看链路，图上按校验结果画出每一跳。

AI 命令强制在系统只读沙箱内执行。pi / pi-acp 使用独立临时配置和只读工具，关闭扩展、MCP 与启动更新；`pi-acp` 会保留 Agent 原生会话以便续聊。无法建立沙箱时直接报错，不能改用无隔离命令绕过。配置位置和系统要求见[配置说明](configuration.md)。
