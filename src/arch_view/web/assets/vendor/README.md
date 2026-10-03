# 浏览器依赖

这些库随项目附带，网页工作台无需连接外部内容分发网络。

- Marked 18.0.14: https://github.com/markedjs/marked (MIT, see MARKED-LICENSE)
- DOMPurify 3.4.16: https://github.com/cure53/DOMPurify (Apache-2.0 OR MPL-2.0, see DOMPURIFY-LICENSE)
- Mermaid 12.1.0: https://github.com/mermaid-js/mermaid (MIT, see MERMAID-LICENSE)

说明文档使用 Marked 解析，并在插入页面前通过 DOMPurify 净化；不执行文档中的脚本和样式。升级依赖时一起更新对应的许可文件。

Mermaid 使用严格安全模式在本地绘制文档中的数据流，图形在插入页面前也会净化，不执行文档声明的点击回调。子系统实现状态只读取文档中的人工标记，不根据代码推断。
