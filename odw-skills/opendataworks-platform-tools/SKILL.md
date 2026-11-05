---
name: opendataworks-platform-tools
description: "使用 OpenDataWorks MCP 查询元数据、血缘、数据源、DDL 和只读数据。"
tools: [Read]
---

# OpenDataWorks 平台工具

先在智能体配置中选择管理员添加的 OpenDataWorks HTTP MCP。
所有平台访问只调用 MCP 工具，执行身份由 DataAgent 后端填写。

1. `odw_search_tables` 获取候选表与字段证据。
2. `odw_resolve_datasource` 确认数据库与引擎；同名库歧义时先澄清。
3. `odw_get_table_ddl` / `odw_get_lineage` 核对结构和血缘。
4. `odw_query_readonly` 执行单条只读 SQL，传 database、sql 与可选 preferredEngine、limit、timeoutSeconds。
5. `odw_export_metadata` 导出元数据。

参数结构以 MCP tools/list 为准。数据库账号映射缺失或权限不足时如实报告，不切换访问通道。
结果完整后使用单独安装的 chart-visualization / report-generation 处理图表或报告。

## 当前 MCP 契约

连接后端 `/api/mcp/`（`/api/mcp` 同样支持），使用平台服务账号 Bearer Token。全部 23 个工具可用，实际数据库操作受该服务账号自身映射和原生权限约束。
参数直接放在工具 arguments 顶层；固定参数及嵌套字段统一 camelCase，不添加 params 包装或旧参数别名。展示意图仅使用可选 description。发布时重新执行领域预检，confirmDiff 缺省 false，仅在接受当前差异时显式设为 true；预览不签发凭据。
