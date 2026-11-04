---
name: arch-view
description: 使用 arch-view 命令查看项目架构图、代码依赖、模块关系、循环依赖、函数复杂度和文档数据流，按其模板生成、维护 ARCHITECTURE.md；也用于解释模块、文件或包的职责，梳理一条调用链路（如“点击后发生了什么”），查询谁依赖谁、改动影响哪些模块，列出 HTTP、RPC、消息、定时等业务入口并为公司自定义注解起草入口规则，并把解释和链路保存为可检查过期的项目知识。适用于新项目架构草案、老项目架构整理和接手陌生代码库。
---

<!-- 职责：指导 AI 配合 arch-view 命令生成宏观架构说明、打开可视化工作台，并用查询、上下文包、链路和解释命令辅助理解代码。 -->

# 架构说明与可视化

在目标项目目录操作；参数按需查看 `arch-view --help` 或 `arch-view <命令> --help`。找不到 `arch-view` 命令时，提示用户在工具目录运行一次安装脚本（Windows 用 `bin/install.ps1`，Linux/macOS 用 `bash bin/install.sh`）并确认已安装 Java，不要自行拼接启动命令。

1. 检查 `ARCHITECTURE.md`。不存在时读取[架构模板](references/architecture-template.md)和[生成提示词](references/architecture-prompts.md)，按新老项目对应流程编写；已存在时轻量检查标题层级、Mermaid 数据流、子系统标识和三项实现状态。符合模板就直接查看，不重复生成；不符合时对照模板仅做必要适配，保留正文和有效人工状态，不整篇重写。需要补写内容时再读取生成提示词，缺失的业务事实列出供用户确认。模板只在技能内读取，不复制到用户项目。
2. 文档准备好后，默认通过 `arch-view serve .` 打开网页界面。AI 自动化调用用持久或后台进程执行 `arch-view serve . --no-browser`，读取就绪输出中的实际地址并打开给用户，服务会常驻，不等待它退出。仅在用户明确要求分析或导出时用 `arch-view scan`，明确要求桌面界面时用 `arch-view desktop`。
3. 文档与代码不一致时说明差异，由用户决定更新文档还是调整代码。

保持文档简短，只维护项目背景、子系统职责和关键数据流；主要职责、边界或数据流变化时才更新。Mermaid 节点标识与子系统标题一致，探索链接单独填写实际代码包路径；数据流不等同于代码依赖。实现状态必须写进 `ARCHITECTURE.md`，网页只保存用户的选择，不会按源码自动补齐；生成文档不代表获准修改业务代码。

## 解释代码与链路

用户问“这个模块/文件/包做什么”“这条流程怎么走”“谁依赖它”“改这里影响哪些地方”时，先读[代码与链路解释](references/ai-exploration.md)。要点：先 `arch-view notes list` / `trace list` 看已有知识；解释前用 `arch-view context <目标> --question "…"` 取上下文包，用 `arch-view query …` 查依赖事实，不凭文件名猜；链路写成草稿后 `arch-view trace save` 逐跳校验，对缺少静态证据的跳说明依据；用户认可的解释用 `arch-view notes save` 保存。引用代码写 `路径:行号`。

## 业务入口

用户想看“系统有哪些接口、RPC、消息消费、定时任务”，或入口用了公司自定义注解、XML 订阅时，读[起草业务入口规则](references/entry-rules.md)。Java 项目网页左侧有「业务入口 / 代码目录」页签：入口按业务域分节，可搜索、按机制多选筛选、切换分组来源；选择入口查看按类分泳道的方法调用图、出口与虚线原因，可继续展开、查看源码、返回包视图或另存 trace；多入口类点三角选择方法。改了规则点「重新读取入口规则」，无需重新分析原有依赖图。

命令流程：`arch-view entries list` 列入口，`entries candidates` 找规则没覆盖的写法，每类只读一个样例写 `.arch-view/entry-rules.json`，以 `entries check` 的结果为准迭代；入口按 `ARCHITECTURE.md` 的业务子系统分组，分不出时再写 `domains`；订阅与 handler key 不一致属于代码问题，列给用户。

按需读取参考：

- 解释模块、梳理链路、查询依赖与改动影响、保存或检查解释：[代码与链路解释](references/ai-exploration.md)。
- 列出业务入口、为自定义注解或 XML 订阅编写入口规则：[起草业务入口规则](references/entry-rules.md)。

- 编写或补齐文档、确定实现状态：[架构模板](references/architecture-template.md)、[生成提示词](references/architecture-prompts.md)。
- 写代码、整理老项目的文件头说明、为大包建包级 `ARCHITECTURE.md`，或用户关心函数复杂度、门禁：[网页读取规则](references/web-behavior.md)。
- 用户询问或需要调整默认行为（连线范围、语言、Python 解释器等）：[配置说明](references/configuration.md)。配置是工具安装目录下的全局 `.env`，不在用户项目中创建或读取配置文件。
