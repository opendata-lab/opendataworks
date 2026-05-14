# 运行时元数据与数据源说明

先结论：只有在 `SKILL.md + 00 + 10 + 11 + 20/21/22` 仍然不能消除具体疑问时，才需要阅读本页或执行工具。若当前 run 已注入 `portal-mcp`，优先使用 `mcp__portal__portal_*`；否则再执行脚本 fallback。

## 何时需要本页

- 需要确认平台核心表的关键字段
- 需要确认上下游血缘或任务关系
- 需要确认目标数据库落在 MySQL 还是 Doris
- 需要解释为什么平台核心表可直接进入 `database=opendataworks` 的只读查询路径，而托管数据表必须先 metadata 再 datasource 再 SQL

## 推荐入口

- `mcp__portal__portal_search_tables`
- `mcp__portal__portal_get_lineage`
- `mcp__portal__portal_resolve_datasource`
- `mcp__portal__portal_get_table_ddl`
- `mcp__portal__portal_query_readonly`
- [`scripts/inspect_metadata.py`](../scripts/inspect_metadata.py)
- [`scripts/resolve_datasource.py`](../scripts/resolve_datasource.py)
- [`scripts/get_lineage.py`](../scripts/get_lineage.py)
- [`scripts/get_table_ddl.py`](../scripts/get_table_ddl.py)
- [`scripts/validate_sql.py`](../scripts/validate_sql.py)
- [`scripts/query_opendataworks_metadata.py`](../scripts/query_opendataworks_metadata.py)

## 使用原则

- MCP 不可用时，fallback 脚本统一使用 `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/<name>.py" ...`，不要自己拼 `/app/scripts/...` 或 `scripts/<name>.py`。
- MCP-first 运行时下，`portal_search_tables`、`portal_get_lineage`、`portal_resolve_datasource`、`portal_export_metadata`、`portal_get_table_ddl`、`portal_query_readonly` 会直接由 `portal-mcp` 暴露给模型。
- 只有 MCP 不可用时，`inspect_metadata.py`、`resolve_datasource.py`、`get_lineage.py`、`get_table_ddl.py`、`validate_sql.py`、`query_opendataworks_metadata.py`、`run_sql.py` 才作为兼容 fallback 通过 skill 自带 `${DATAAGENT_SKILL_ROOT}/bin/odw-cli` 调 backend agent API。
- 执行 metadata 相关脚本前，先检查 `${DATAAGENT_SKILL_ROOT}/bin/odw-cli` 是否存在。
- 部署时如果 bind mount 丢了执行位，运行时会自动退回 `sh "${DATAAGENT_SKILL_ROOT}/bin/odw-cli" ...`；但宿主机仍建议保留 `+x`。
- 如果该固定路径缺少 CLI，必须先由用户自行安装到 `${DATAAGENT_SKILL_ROOT}/bin/odw-cli`，然后再执行 metadata 相关脚本。
- `inspect_metadata.py` 在 database 未指定时默认做全局检索，匹配范围覆盖表名、表注释、字段名、字段注释；只返回客观候选，不负责判断“哪张表最好”。
- 平台核心表结构已在本页给出，字段明确时可直接写 SQL，并交给 `portal_query_readonly` 或 `validate_sql.py` -> `run_sql.py` 通过 backend 执行。
- `resolve_datasource.py` 只负责确认引擎与数据源摘要；对外不暴露 host / port / user / password / readonly_*。
- `get_lineage.py` 是上游 / 下游问题的标准 fallback，优先级高于手写血缘 SQL。
- `get_table_ddl.py` 不直接执行数据库连接，而是通过 `odw-cli ddl` 调 backend `/api/v1/ai/metadata/ddl`。
- `validate_sql.py` 负责脚本 fallback 下的 SQL 验证。业务知识 Skill 只提供 ontology、口径和 SQL example；如需领域表字段校验，把业务 ontology 作为 `--ontology` 输入。
- `run_sql.py` 不再直连数据库，而是通过 `odw-cli query-readonly` 调 backend `/api/v1/ai/query/read`。
- runtime 会把原始用户问题注入 `DATAAGENT_ORIGINAL_QUESTION`；`run_sql.py` 会据此拦截首轮 `data_lineage` 类 SQL，避免把上游 / 下游问题退化成反复猜字段。
- 一旦数据库明确，SQL 必须写 `<schema>.<table>`；平台核心表固定用 `opendataworks.<table>`。

## 平台核心表速查

### 数据表与字段

- `data_table`
  - `id`, `db_name`, `table_name`, `table_comment`, `layer`, `status`, `owner`, `created_at`
- `data_field`
  - `table_id`, `field_name`, `field_type`, `field_comment`, `is_partition`, `is_primary`, `field_order`

### 血缘与任务关系

- `data_lineage`
  - `task_id`, `upstream_table_id`, `downstream_table_id`, `lineage_type`, `created_at`
- `table_task_relation`
  - `task_id`, `table_id`, `relation_type`, `created_at`
- `data_task`
  - `task_name`, `task_code`, `task_type`, `engine`, `status`, `owner`, `datasource_name`, `datasource_type`, `created_at`

### 工作流治理

- `data_workflow`
  - `workflow_code`, `workflow_name`, `status`, `publish_status`, `current_version_id`, `last_published_version_id`, `created_at`
- `workflow_task_relation`
  - `workflow_id`, `task_id`, `upstream_task_count`, `downstream_task_count`, `version_id`, `created_at`
- `workflow_version`
  - `workflow_id`, `version_no`, `change_summary`, `trigger_source`, `created_at`
- `workflow_publish_record`
  - `workflow_id`, `version_id`, `target_engine`, `operation`, `status`, `engine_workflow_code`, `operator`, `created_at`

### Doris 数据源

- `doris_cluster`
  - `cluster_name`, `fe_host`, `fe_port`, `username`, `is_default`, `status`
- `doris_database_users`
  - `cluster_id`, `database_name`, `readonly_username`, `readwrite_username`, `created_at`

## Backend CLI 环境变量

- `ODW_BACKEND_BASE_URL=http://backend:8080/api/v1/ai`
- `ODW_AGENT_SERVICE_TOKEN=<shared-token>`
- `ODW_BACKEND_TIMEOUT_SECONDS=30`

## 原始查询示例

### 各数据层表数量对比

```sql
SELECT layer, COUNT(*) AS table_cnt
FROM opendataworks.data_table
WHERE deleted = 0
GROUP BY layer
ORDER BY table_cnt DESC
LIMIT 20;
```

### 最近 30 天工作流发布次数趋势

```sql
SELECT DATE(created_at) AS stat_day, COUNT(*) AS publish_cnt
FROM opendataworks.workflow_publish_record
WHERE created_at >= DATE_SUB(CURRENT_DATE(), INTERVAL 29 DAY)
GROUP BY DATE(created_at)
ORDER BY stat_day
LIMIT 100;
```

### 某张表的上下游血缘

```sql
SELECT dl.lineage_type,
       ut.db_name AS upstream_db,
       ut.table_name AS upstream_table,
       dt.db_name AS downstream_db,
       dt.table_name AS downstream_table
FROM opendataworks.data_lineage dl
LEFT JOIN opendataworks.data_table ut ON ut.id = dl.upstream_table_id AND ut.deleted = 0
LEFT JOIN opendataworks.data_table dt ON dt.id = dl.downstream_table_id AND dt.deleted = 0
WHERE (ut.table_name = 'your_table' OR dt.table_name = 'your_table')
ORDER BY dl.id DESC
LIMIT 100;
```

诊断类硬规则：

- 用户已经给出明确表名时，优先 `mcp__portal__portal_get_lineage`；无 MCP 时优先 `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/get_lineage.py" --table <table> [--db-name <db>]`，不要先搜索仓库里的 lineage/血缘代码实现。
- 只有 lineage 快照缺少用户明确要看的字段时，才允许追加 `validate_sql.py` -> `run_sql.py` 查询 `data_lineage + data_table`。
- 如果确实需要这类补充 SQL，显式带 `DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1`；否则 `run_sql.py` 会直接拒绝。
- 只要第一次 lineage 工具结果已返回非空数据，就直接总结；即使 `downstream_table` 或 `upstream_table` 有空值，也不要因为补空列再继续追加第二条 SQL。
- 只有同名表不唯一或用户没给出表名时，才退回 metadata 检索和追问。

### 查看表 DDL

- live DDL 的首选入口是 `mcp__portal__portal_get_table_ddl`
- 无 MCP 时使用 `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/get_table_ddl.py" --database <db> --table <table>`
- 只有明确需要验证只读 SQL 兼容性时，才退回 `validate_sql.py` -> `run_sql.py --sql "SHOW CREATE TABLE ..."`；平时优先走标准 DDL 路径

### datasource 摘要定位

- `resolve_datasource.py` 和 `query_opendataworks_metadata.py --kind datasource` 只用于确认 database 对应的 `engine`、`source_type`、`cluster_id`、`cluster_name`、`resolved_by`
- 不要期待这两个入口返回只读账号、host、port 或密码；真实只读查询统一走 backend `/api/v1/ai/query/read`
