# 工具 Recipes

先结论：优先 `portal-mcp`，没有 MCP 再回退脚本调用。本页只描述工具怎么调用；语义确认和 SQL 生成由 DataAgent system prompt 约束。

## 统一命令规则

- fallback 脚本统一通过：`"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/<name>.py" ...`
- 若运行时暴露了 `mcp__portal__portal_*`，优先直接调用 MCP tools，不要先绕回脚本。
- 固定脚本只有：`inspect_metadata.py`、`resolve_datasource.py`、`get_lineage.py`、`get_table_ddl.py`、`validate_sql.py`、`run_sql.py`、`export_query.py`、`build_chart_spec.py`、`format_answer.py`、`query_opendataworks_metadata.py`
- validate_sql.py 是唯一推荐的 SQL 验证入口；脚本 fallback 下必须先 `validate_sql.py`，再 `run_sql.py` 或 `export_query.py`。
- run_sql.py 是唯一推荐的 SQL 执行入口；不要新增或猜测其他 SQL 执行脚本。
- 大结果或需落盘的场景改用 `export_query.py`（结果写文件、不进上下文）；它服务于导出，不替代 run_sql.py 把结果回上下文的入口地位。
- 标准链路：语义确认 → SQL 生成 → SQL 验证 → run_sql.py 执行 → 结果收口。
- 语义技能只提供语义；不要在语义技能中寻找或维护 SQL 验证/执行脚本。
- 不要自己拼脚本路径或脚本名；禁止使用 primary `DATAAGENT_SKILL_ROOT`、部署绝对路径、裸相对路径或猜测脚本名。
- 不要执行环境探测或依赖安装命令。
- 如果脚本报错，优先收敛输入参数或向用户追问，不要切换解释器反复试探。
- 已确认 SQL 时，先通过 `validate_sql.py` 校验，再通过 `run_sql.py` 必须拿到真实只读结果后回答；不得只输出 SQL 或要求用户自行执行。
- 看不到 run_sql.py 或 backend 查询不可用时，只说明缺少执行入口或 backend 查询能力，不要假装已执行。

## portal-mcp 首选工具

- 元数据检索：`mcp__portal__portal_search_tables`
- 血缘查询：`mcp__portal__portal_get_lineage`
- datasource 摘要 / 路由：`mcp__portal__portal_resolve_datasource`
- metadata 导出：`mcp__portal__portal_export_metadata`
- 表 DDL：`mcp__portal__portal_get_table_ddl`
- 只读 SQL：`mcp__portal__portal_query_readonly`

只有当前 run 看不到这些工具时，才回退到脚本。

## inspect_metadata.py

- 用途：定位候选表、字段和 metadata。
- 适用场景：用户没有给出明确表名；需要确认指标字段和维度字段；需要在元数据中做关键词检索。
- 调用原则：用户没有明确给 database 时，先不要传 `--database`；首次结果过少时可做少量同义词补检。
- 命令模板：`"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/inspect_metadata.py" --keyword "<keyword>"`

## resolve_datasource.py

- 用途：根据 database 判断 engine 和 datasource 摘要。
- 必须满足：`--database` 必填，值直接取自已确认 database/schema。
- 成功一次后不要重复调用。
- 命令模板：`"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/resolve_datasource.py" --database <database>`

## get_lineage.py

- 用途：查看目标对象的上下游或血缘快照。
- 必须满足：目标对象已确认；同名对象不唯一时先补充 database/schema。
- 收口规则：返回足够证据后优先基于结果回答。
- 命令模板：`"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/get_lineage.py" --table <table> [--db-name <db>]`

## get_table_ddl.py

- 用途：查看 live DDL、字段顺序、注释、分区或建表属性。
- 必须满足：目标 database/table 或 table id 已确认。
- 收口规则：返回 `table_ddl` 后优先基于该结果回答。
- 命令模板：`"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/get_table_ddl.py" --database <db> --table <table>`

## validate_sql.py

- 用途：执行脚本 fallback 下的 SQL 验证。
- 适用场景：SQL 已形成，准备进入 `run_sql.py`。
- 检查范围：只读、安全、schema 前缀、`SELECT *`、相对日期、占位符；可选 ontology 表字段检查。
- 命令模板：
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/validate_sql.py" --json "<SQL>"`
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/validate_sql.py" --ontology <ontology-path-from-caller> --json "<SQL>"`
- 验证失败时修正 SQL 后最多重跑一次同类验证；仍失败则说明缺口。

## run_sql.py

- 用途：执行只读 SQL。
- 必须满足：database、engine、SQL 都已确认，且 SQL 已通过验证。
- 固定链路：`run_sql.py -> backend 只读查询 API`。
- 结果归因：
  - `result_state=success`：已拿到真实结果，直接收口回答。
  - `result_state=empty_result`：查询成功但无数据，说明口径和空结果，不换表试探。
  - `result_state=failed`：按 `error_code`、`failure_attribution`、`stop_reason` 说明原因。
- 命令模板：`"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/run_sql.py" --database <db> --engine <mysql|doris> --sql "<SQL>"`
- 注意：`run_sql.py` 与 `portal_query_readonly` 的结果会进模型上下文，受结果字节守卫（默认 512KB）约束；超限会返回 `truncated_by_size=true` 与 `error_code=result_truncated`。大结果或要落盘时改用 `export_query.py`。

## export_query.py

- 用途：把只读 SQL 的**全量结果**写入工作区 CSV 文件，只把路径、列、行数与少量预览回给模型；全量数据不进模型上下文，因此不触发结果字节守卫，也不会撑爆运行时缓冲。
- 适用场景：结果行数多、或需要落盘供后续 Python 处理（如读 CSV 生成多 sheet Excel）。
- 固定链路：`export_query.py -> backend 只读查询 API（导出模式，绕过字节守卫，仍受行数上限保护）`。
- 行数上限：默认且最大 10000 行；命中上限时 `has_more=true`，应改用更精确的过滤或聚合。
- 后续处理：模型用 Bash/Python 读取返回的 `file_path`（CSV）再生成最终文件，不要把 CSV 内容整体读进上下文。
- 命令模板：`"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/export_query.py" --database <db> --engine <mysql|doris> --sql "<SQL>" --output <relative/or/abs/path.csv>`

## build_chart_spec.py

- 用途：把 SQL 结果转换成图表契约。
- 适用场景：结果结构适合图表，且用户问题需要可视化或对比展示。
- 参数规则：必须显式传 `--chart-type bar|line|pie|table`。
- 输入方式（二选一）：
  - `--data '<JSON rows>'`：直接传 JSON 数组，适用于已有行数据。
  - `--input '<sql_execution JSON>'`：传 `run_sql.py` 的完整输出 JSON（含 `rows` 字段）。
- 可选参数：`--title "标题"`, `--x-field <维度字段>`, `--y-field <度量字段>`。
- 收口规则：成功返回一次 `chart_spec` 后结束本轮，不重复生成。
- 命令模板：`"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/build_chart_spec.py" --chart-type <bar|line|pie|table> --data '<JSON rows>' [--title "<标题>"] [--x-field <维度字段>] [--y-field <度量字段>]`
- 注意：`build_chart_spec.py` 不是独立注册的工具名，必须通过 Bash 工具执行上述命令。

## format_answer.py

- 用途：把结构化 SQL 结果压缩成中文结论。
- 使用原则：只格式化已有结果，不补造业务事实。
