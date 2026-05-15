# 运行时元数据与数据源说明

先结论：本页只说明 runtime 提供哪些通用工具能力，不保存业务语义、平台表清单或指标口径。

## 推荐入口

- `mcp__portal__portal_search_tables`
- `mcp__portal__portal_get_lineage`
- `mcp__portal__portal_resolve_datasource`
- `mcp__portal__portal_export_metadata`
- `mcp__portal__portal_get_table_ddl`
- `mcp__portal__portal_query_readonly`
- [`scripts/inspect_metadata.py`](../scripts/inspect_metadata.py)
- [`scripts/resolve_datasource.py`](../scripts/resolve_datasource.py)
- [`scripts/get_lineage.py`](../scripts/get_lineage.py)
- [`scripts/get_table_ddl.py`](../scripts/get_table_ddl.py)
- [`scripts/validate_sql.py`](../scripts/validate_sql.py)
- [`scripts/run_sql.py`](../scripts/run_sql.py)

## 使用原则

- MCP 不可用时，fallback 脚本统一使用 `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/<name>.py" ...`。
- MCP 优先的运行时下，portal 工具直接由运行时暴露给模型。
- 只有 MCP 不可用时，Python 脚本才作为兼容回退调用 backend API。
- 执行元数据相关脚本前，确认技能自带 CLI 路径存在；如果缺少，说明缺失能力，不尝试下载或安装。
- `inspect_metadata.py` 只返回客观候选，不负责业务推荐。
- `resolve_datasource.py` 只返回 datasource 摘要，不暴露 host / port / user / password 等敏感字段。
- `validate_sql.py` 负责脚本回退下的 SQL 验证；如需领域表字段校验，由上游技能把对应 ontology 路径作为 `--ontology` 输入。
- `run_sql.py` 通过 backend 只读查询接口执行，不直连外部数据源。
- 一旦 database/schema 明确，SQL 必须写 `<schema>.<table>`。

## 运行时环境

- `DATAAGENT_PYTHON_BIN`：当前 DataAgent Python 解释器。
- `DATAAGENT_SKILL_ROOT`：primary 技能根目录，不用于平台脚本回退。
- `DATAAGENT_PLATFORM_SKILL_ROOT`：OpenDataWorks 平台工具技能根目录，平台脚本回退只使用该根目录。
- `DATAAGENT_ENABLED_SKILLS`：本轮启用的技能文件夹列表。
- `DATAAGENT_ENABLED_SKILL_ROOTS`：启用技能到绝对路径的映射。
- `DATAAGENT_QUERY_LIMIT`：查询结果限制。
- `DATAAGENT_RESULT_PREVIEW_ROWS`：结果摘要预览行数。
- `DATAAGENT_SQL_READ_TIMEOUT_SECONDS`：只读 SQL 超时。
- `DATAAGENT_ORIGINAL_QUESTION`：原始用户问题。

## Backend API 边界

- 元数据、血缘、DDL、数据源和只读查询都通过运行时暴露的 MCP 或 backend 服务路径执行。
- 技能/runtime 不接收数据库账号密码。
- 工具输出只暴露执行所需摘要和结构化结果。
