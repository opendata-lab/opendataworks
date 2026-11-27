# MCP 工具中文标题

日期：2026-11-26

计划：[实施计划](../plans/2026-11-26-mcp-tool-titles-plan.md)

OpenDataWorks MCP 的调用标识是英文，消费端可用 MCP 标准的 `title` 展示中文名称。23 个工具的标题统一维护在 `backend/mcp-server/src/main/resources/mcp/tools.json`，采用 2–6 个汉字的动宾短语，在同一服务内唯一。Java MCP 注册时将该值设置到工具对象的顶层 `title`，两个 MCP 路径使用同一份清单。

工具调用继续使用原有 `name`，参数、处理器和风险标记保持原有契约。完整映射见 [MCP 工具清单](2026-11-03-backend-mcp-auth-consolidation-design.md)。
