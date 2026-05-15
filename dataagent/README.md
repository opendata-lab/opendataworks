# dataagent

统一后的 DataAgent 目录：

- `dataagent-backend`：Python/FastAPI 服务（原 `nl2sql-service`）
- `.claude/`：DataAgent 运行时配置与 Skills 目录

说明：

- 主应用 `frontend` 已统一承载智能问数页面，入口为 `/intelligent-query`。
- 原 Java `dataagent-backend` 模块已删除。
- `dataagent/.claude/skills/dataagent-nl2sql` 是当前运行时 primary skill，负责通用问数 SQL 方法、工具 recipes、校验/执行契约，以及 skill-local `scripts/` / `bin/` fallback 入口。
- `dataagent/.claude/skills/opendataworks-business-knowledge` 是随仓库发布的业务知识 skill，负责 OpenDataWorks 平台术语、本体、指标口径、别名、歧义消解和业务规则例外；它不提供 SQL 验证或执行脚本。
- 租户私有业务知识仍应拆到仓库外或未提交的扩展 skill 中，不应写入通用 SQL skill。
- 表元数据、血缘、数据源的动态查询入口放在 skill 的 `references/` 和 `scripts/` 中，不再同步成大块 JSON 快照。
- OpenDataWorks 内部部署下，DataAgent runtime 默认 MCP-first：若当前 run 已注入 `portal-mcp`，模型会优先直接调用 `portal_search_tables` / `portal_get_lineage` / `portal_resolve_datasource` / `portal_export_metadata` / `portal_get_table_ddl` / `portal_query_readonly`。
- `inspect_metadata.py` / `resolve_datasource.py` / `query_opendataworks_metadata.py` / `run_sql.py` 仍保留为非 MCP 智能体或 MCP 未注入场景下的 fallback，它们会通过 skill 自带的 `dataagent/.claude/skills/dataagent-nl2sql/bin/odw-cli` 调用 backend 的 `/api/v1/ai/*` 只读接口，而不是由 skill/runtime 直接访问平台元数据库或业务数据库。
- metadata 检索默认先做全局搜索；只有用户明确给出 database 时才加 database 过滤。
- `resolve_datasource.py` 与 datasource export 只返回 datasource 摘要，不再向 skill/runtime 暴露 host、port、user、password、readonly_* 等连接信息。
- 如果把 `dataagent-nl2sql` skill 复制到其他智能体平台，支持远程 MCP 时优先挂 `portal-mcp`；不支持 MCP 时，再检查 `dataagent/.claude/skills/dataagent-nl2sql/bin/odw-cli` 是否存在并走 fallback。业务语义需要同时复制或启用对应 knowledge skill。
- `dataagent-backend` 的表结构现在由 Alembic 管理；启动前需对 `SESSION_MYSQL_DATABASE` 执行 `alembic upgrade head`。
