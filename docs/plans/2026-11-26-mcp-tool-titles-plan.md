# MCP 工具中文标题实施计划

日期：2026-11-26

设计：[设计文档](../design/2026-11-26-mcp-tool-titles-design.md)

1. 在 `tools.json` 补全中文标题，在 `McpServerConfiguration` 注册到 MCP 工具对象，并更新现有 MCP 工具清单。
2. 在 Java 和 Python 契约检查中覆盖所有工具的标题。使用本地 JDK 17 和 Maven 执行离线 Java 契约测试，并运行 Python 契约检查。
3. 随常规发布部署 Java 后端。需要回退时撤销本次提交，无数据库迁移。
