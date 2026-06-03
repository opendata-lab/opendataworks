# dataagent

统一后的 DataAgent 目录：

- `dataagent-backend`：Python/FastAPI 服务（原 `nl2sql-service`）
- `dataagent-frontend`：DataAgent 独立前端与可嵌入 Widget 构建产物
- `.claude/`：DataAgent 运行时配置与 Skills 目录

说明：

- 主应用 `frontend` 不再编译智能问数页面源码；入口 `/intelligent-query` 通过远程 `OpenDataWorksWidget` JS 以内嵌模式加载 DataAgent 问答页。
- `dataagent-frontend` 是模型、Skills、智能体、Widget 接入配置等 DataAgent 管理 UI 的归属目录，并负责构建 `/widget/opendataworks-widget.bundle.js`。
- 原 Java `dataagent-backend` 模块已删除。
- 通用问数 SQL 方法、表字段发现策略、SQL 前检查和结果收口已收敛到 `dataagent-backend/prompts/data_agent_system_prompt.md`，不再由独立通用问数 skill 承载。
- `dataagent/.claude/skills/opendataworks-business-knowledge` 是随仓库发布的业务知识 skill，负责 OpenDataWorks 平台术语、本体、指标口径、别名、歧义消解和业务规则例外；它不提供 SQL 验证或执行脚本。
- `dataagent/.claude/skills/opendataworks-platform-tools` 是随仓库发布的平台工具 skill，负责 metadata、DDL、血缘、数据源、SQL 验证、只读执行、结果格式化、图表契约以及 `scripts/` / `bin/` fallback 入口。
- 租户私有业务知识仍应拆到仓库外或未提交的扩展 skill 中，不应写入 system prompt 或内置平台通用 skill。
- 表元数据、血缘、数据源的动态查询入口放在 platform tools skill 的 `reference/` 和 `scripts/` 中，不再同步成大块 JSON 快照。
- OpenDataWorks 内部部署下，DataAgent runtime 默认 MCP-first：若当前 run 已注入 `portal-mcp`，模型会优先直接调用 `portal_search_tables` / `portal_get_lineage` / `portal_resolve_datasource` / `portal_export_metadata` / `portal_get_table_ddl` / `portal_query_readonly`。
- `inspect_metadata.py` / `resolve_datasource.py` / `query_opendataworks_metadata.py` / `run_sql.py` 仍保留为非 MCP 智能体或 MCP 未注入场景下的 fallback，统一通过 `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/<name>.py" ...` 调用；这些脚本会通过 `dataagent/.claude/skills/opendataworks-platform-tools/bin/odw-cli` 调用 backend 的 `/api/v1/ai/*` 只读接口，而不是由 skill/runtime 直接访问平台元数据库或业务数据库。
- metadata 检索默认先做全局搜索；只有用户明确给出 database 时才加 database 过滤。
- `resolve_datasource.py` 与 datasource export 只返回 datasource 摘要，不再向 skill/runtime 暴露 host、port、user、password、readonly_* 等连接信息。
- 如果把内置 skills 复制到其他智能体平台，支持远程 MCP 时优先挂 `portal-mcp`；不支持 MCP 时，需要同时复制或启用 `opendataworks-platform-tools` 并检查其 `bin/odw-cli` 是否存在。业务语义需要同时复制或启用对应 knowledge skill。
- `dataagent-backend` 的表结构现在由 Alembic 管理；启动前需对 `SESSION_MYSQL_DATABASE` 执行 `alembic upgrade head`。
