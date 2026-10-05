---
name: arch-view
description: 使用 arch-view 命令查看项目架构图、代码依赖、模块关系、循环依赖、函数复杂度和文档数据流，或按其模板生成、维护 ARCHITECTURE.md。适用于新项目架构草案和老项目架构整理。
---

<!-- 职责：指导 AI 配合 arch-view 命令生成宏观架构说明，并打开可视化工作台。 -->

# 架构说明与可视化

在目标项目目录操作；参数按需查看 `arch-view --help` 或 `arch-view <命令> --help`。找不到 `arch-view` 命令时，提示用户在工具目录运行一次安装脚本（Windows 用 `bin/install.ps1`，Linux/macOS 用 `bash bin/install.sh`）并确认已安装 Java，不要自行拼接启动命令。

1. 检查 `ARCHITECTURE.md`。不存在时读取[架构模板](references/architecture-template.md)和[生成提示词](references/architecture-prompts.md)，按新老项目对应流程编写；已存在时轻量检查标题层级、Mermaid 数据流、子系统标识和三项实现状态。符合模板就直接查看，不重复生成；不符合时对照模板仅做必要适配，保留正文和有效人工状态，不整篇重写。需要补写内容时再读取生成提示词，缺失的业务事实列出供用户确认。模板只在技能内读取，不复制到用户项目。
2. 文档准备好后，默认通过 `arch-view serve .` 打开网页界面。AI 自动化调用用持久或后台进程执行 `arch-view serve . --no-browser`，读取就绪输出中的实际地址并打开给用户，服务会常驻，不等待它退出。仅在用户明确要求分析或导出时用 `arch-view scan`，明确要求桌面界面时用 `arch-view desktop`。
3. 文档与代码不一致时说明差异，由用户决定更新文档还是调整代码。

保持文档简短，只维护项目背景、子系统职责和关键数据流；主要职责、边界或数据流变化时才更新。Mermaid 节点标识与子系统标题一致，探索链接单独填写实际代码包路径；数据流不等同于代码依赖。实现状态必须写进 `ARCHITECTURE.md`，网页只保存用户的选择，不会按源码自动补齐；生成文档不代表获准修改业务代码。

按需读取参考：

- 编写或补齐文档、确定实现状态：[架构模板](references/architecture-template.md)、[生成提示词](references/architecture-prompts.md)。
- 写代码、整理老项目的文件头说明、为大包建包级 `ARCHITECTURE.md`，或用户关心函数复杂度、门禁：[网页读取规则](references/web-behavior.md)。
- 用户询问或需要调整默认行为（连线范围、语言、Python 解释器等）：[配置说明](references/configuration.md)。配置是工具安装目录下的全局 `.env`，不在用户项目中创建或读取配置文件。
