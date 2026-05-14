# 工具 Recipes

先结论：优先 `portal-mcp`，没有 MCP 再回退脚本调用。固定管线是：语义匹配 → SQL 生成 → SQL 验证 → run_sql.py 执行 → 结果收口。`validate_sql.py` 是唯一推荐的 SQL 验证入口，`run_sql.py` 是唯一推荐的 SQL 执行入口，二者都不是盲猜工具；但如果问题已经明确指向 `opendataworks` 平台核心表且字段清楚，可以直接进入 `database=opendataworks`、`engine=mysql` 的只读查询路径。这里的“直接执行”仍然是 backend 代执行，不是 skill/runtime 直连 MySQL。

## 统一命令规则

- fallback 脚本统一通过：`"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/<name>.py" ...`
- 若运行时暴露了 `mcp__portal__portal_*`，优先直接调用这些 MCP tools，不要先绕回脚本。
- 固定脚本只有：`inspect_metadata.py`、`resolve_datasource.py`、`get_lineage.py`、`get_table_ddl.py`、`validate_sql.py`、`run_sql.py`、`build_chart_spec.py`、`format_answer.py`、`query_opendataworks_metadata.py`
- `validate_sql.py` 是唯一推荐的 SQL 验证入口；脚本 fallback 下必须先 `validate_sql.py`，再 `run_sql.py`。
- `run_sql.py` 是唯一推荐的 SQL 执行入口；不要新增或猜测其他 SQL 执行脚本。
- 业务知识 Skill 只提供本体、口径、关系和 SQL example；不要在业务 Skill 中寻找或维护 SQL 验证/执行脚本。
- 不要自己拼脚本路径或脚本名；禁止使用 `/app/scripts/...`、`scripts/<name>.py`、`resolvedadatsource.py` 这类猜测路径或拼写。
- 动态 metadata 与只读 SQL 的固定实现是：Python 脚本内部优先调用 skill 自带 `${DATAAGENT_SKILL_ROOT}/bin/odw-cli`，再由 CLI 请求 backend `/api/v1/ai/*`。
- 执行任何 metadata 相关脚本前，先检查 `${DATAAGENT_SKILL_ROOT}/bin/odw-cli` 是否存在。
- 部署时如果 bind mount 丢了执行位，运行时会自动退回 `sh "${DATAAGENT_SKILL_ROOT}/bin/odw-cli" ...`；但宿主机仍建议保留 `+x`。
- 如果该固定路径缺少 CLI，立即提示用户先自行安装到 `${DATAAGENT_SKILL_ROOT}/bin/odw-cli`，不要尝试自动下载、恢复或安装。
- 不要执行 `pip install`、`uv add`、`which python`、`python --version` 这类环境探测或依赖安装命令。
- 如果脚本报错，优先收敛输入参数或向用户追问，不要切换解释器反复试探。
- 没有真实 Bash 报错时，不要自行下结论说“缺少依赖”或“环境异常”。
- 统计 / 对比 / 趋势 / 占比 / 明细 / 诊断问题，不要用读取 `assets/*.json` 代替脚本执行；`assets` 只用于术语解释或 SQL 示例补充。
- 已确认 SQL 时，先通过 `validate_sql.py` 校验，再通过 `run_sql.py` 拿真实只读结果后回答；不得只输出 SQL 或要求用户自行执行。
- 看不到 run_sql.py 或 backend 查询不可用时，只说明缺少执行入口或 backend 查询能力，不要假装已执行。

## portal-mcp 首选工具

- 元数据检索：`mcp__portal__portal_search_tables`
- 血缘查询：`mcp__portal__portal_get_lineage`
- datasource 摘要 / 路由：`mcp__portal__portal_resolve_datasource`
- metadata 导出：`mcp__portal__portal_export_metadata`
- 表 DDL：`mcp__portal__portal_get_table_ddl`
- 只读 SQL：`mcp__portal__portal_query_readonly`
- 只有当前 run 看不到这些工具时，才回退到 `inspect_metadata.py` / `resolve_datasource.py` / `get_lineage.py` / `get_table_ddl.py` / `validate_sql.py` / `run_sql.py` / `odw-cli`

## inspect_metadata.py

- 用途：定位托管数据表的数据库、表、字段、血缘
- 返回原则：
  - 只返回匹配到的客观候选，不做推荐、打分或排序
  - 由模型根据场景、字段和 reference 规则自己决定用哪张表
- 适用场景：
  - 用户没有给出明确表名
  - 需要确认托管数据表中的指标字段和维度字段
  - 需要判断候选数据库
  - 需要在表名、表注释、字段名、字段注释中做关键词检索
- 不适用场景：
  - 问题已经明确指向 `data_table`、`data_lineage`、`data_task`、`data_workflow`、`workflow_*`、`doris_*` 这些平台核心表
- 典型调用参数：
  - `--table`
  - `--keyword`
  - `--database`
- 命令模板：
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/inspect_metadata.py" --keyword "工作流发布"`
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/inspect_metadata.py" --database doris_ods --table some_table_di`
- 典型顺序：
  - 托管数据表场景的第一脚本
  - 用户没有明确给 database 时，先不要传 `--database`，让 backend 做全局检索
  - 首次结果过少时，可以基于用户原词做少量同义词 / 相关词补检，但不要无限扩词

## resolve_datasource.py

- 用途：根据 database 判断引擎和数据源
- 适用场景：
  - metadata 已经确定 database
  - 还不清楚是 MySQL 还是 Doris
- 不适用场景：
  - 平台核心表问题；这类问题固定使用 `database=opendataworks`、`engine=mysql` 的只读查询路径
- 必须满足：
  - `--database` 必填，值直接取自 metadata 返回的 `db_name`
  - 成功一次后不要重复调用
  - `--database` 表示真实 database / schema，不是引擎名
- 命令模板：
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/resolve_datasource.py" --database doris_ods`
- 典型顺序：
  - `inspect_metadata.py` 之后
  - `run_sql.py` 之前

## get_lineage.py

- 用途：查看目标表的上游 / 下游 / 血缘快照
- 适用场景：
  - 用户明确问“某个表的上游表 / 下游表 / 血缘关系”
  - 当前 run 没有 `mcp__portal__portal_get_lineage`
  - 需要避免手写 `data_lineage + data_table` SQL 的字段猜测
- 固定链路：
  - `get_lineage.py -> odw-cli lineage -> backend /api/v1/ai/metadata/lineage`
- 必须满足：
  - `--table` 或 `--table-id` 至少提供一个
  - 同名表不唯一时，先补充 `--db-name`
- 命令模板：
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/get_lineage.py" --table some_table --db-name doris_ods --depth 2`
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/get_lineage.py" --table-id 123 --depth 2`
- 收口规则：
  - `lineage_snapshot` 返回后优先基于结果回答
  - 只有 lineage 快照缺少用户明确要的字段时，才追加 `validate_sql.py` -> `run_sql.py`
  - 如果必须补 `data_lineage + data_table` 的 SQL，显式使用 `DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1`

## get_table_ddl.py

- 用途：查看 live 表 DDL，等价于 skill 的标准 `SHOW CREATE TABLE` / DDL 路径
- 适用场景：
  - 用户明确要求看建表语句、DDL、`SHOW CREATE TABLE`
  - 需要确认真实字段顺序、注释、分区或建表属性
  - 当前 run 没有 `mcp__portal__portal_get_table_ddl`
- 固定链路：
  - `get_table_ddl.py -> odw-cli ddl -> backend /api/v1/ai/metadata/ddl`
- 必须满足：
  - `--table-id` 或 `--database + --table` 至少提供一组
  - 同名表不唯一时，先补充 `database` 或 `table_id`
- 命令模板：
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/get_table_ddl.py" --database opendataworks --table workflow_publish_record`
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/get_table_ddl.py" --table-id 123`
- 收口规则：
  - 返回 `table_ddl` 后就优先基于该结果回答，不要再手写等价 `SHOW CREATE TABLE`

## validate_sql.py

- 用途：执行脚本 fallback 下的 SQL 验证。
- 适用场景：
  - SQL 已形成，准备进入 `run_sql.py`
  - 需要统一检查只读、安全、schema 前缀、`SELECT *`、相对日期和占位符
  - 业务知识 Skill 提供了 ontology，需要用该 ontology 校验表名和字段候选
- 固定链路：
  - `validate_sql.py -> run_sql.py`
  - 业务知识 Skill 只提供 `assets/ontology.json`、口径和 SQL example，不提供验证脚本
- 命令模板：
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/validate_sql.py" --json "SELECT COUNT(*) AS table_cnt FROM opendataworks.data_table WHERE deleted = 0 LIMIT 20"`
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/validate_sql.py" --ontology .claude/skills/<business-skill>/assets/ontology.json --json "<domain SQL>"`
- 收口规则：
  - 验证失败时修正 SQL 后最多重跑一次同类验证
  - 验证仍失败时按 `errors` 说明缺口，不要跳过验证直接执行
  - 验证通过后立即进入 `run_sql.py`，不要再去业务 Skill 中查找 `validate_sql.py`

## run_sql.py

- 用途：执行只读 SQL
- 适用场景：
  - 数据库明确
  - 引擎明确
  - SQL 已形成
- 可以直接进入只读查询快路径的场景：
  - 已明确要查 `opendataworks` 的平台核心表，且字段名已知
- 固定链路：
  - `run_sql.py -> odw-cli query-readonly -> backend /api/v1/ai/query/read`
  - skill/runtime 不再直连外部数据源，也不接收 datasource 凭据
- 结果归因：
  - `result_state=success`：已拿到真实结果，直接收口回答
  - `result_state=empty_result`：查询成功但无数据，说明口径和空结果，不要继续换表、换字段或重复试探
  - `result_state=failed`：按 `error_code`、`failure_attribution`、`stop_reason` 说明权限、数据源、表字段或超时问题
- 血缘硬约束：
  - `run_sql.py` 会读取 `DATAAGENT_ORIGINAL_QUESTION`
  - 当前问题命中“上游 / 下游 / 血缘”且 SQL 命中 `data_lineage` / `upstream_table_id` / `downstream_table_id` / `lineage_type` 时，默认拒绝执行
  - 只有明确是 lineage 快照后的补充查询时，才允许显式带 `DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1`
- 必须先满足：
  - 指标清楚
  - 时间范围清楚
  - 维度清楚
  - 数据库清楚
  - SQL 已通过 `validate_sql.py`
- SQL 编写规则：
  - 平台核心表统一写 `opendataworks.<table>`
  - 托管数据表统一写 `<db_name>.<table>`
  - `mysql` / `doris` 只放在 `--engine`，不要写进 SQL schema
  - 对 Doris `df` 快照表，未指定历史区间时默认加最新 `ds` 过滤
  - 对 Doris `di` 增量表，必须显式加时间范围过滤，优先使用 `ds >= ... AND ds <= ...`
- 命令模板：
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/run_sql.py" --database opendataworks --engine mysql --sql "SELECT layer, COUNT(*) AS table_cnt FROM opendataworks.data_table WHERE deleted = 0 GROUP BY layer ORDER BY table_cnt DESC LIMIT 20"`
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/run_sql.py" --database doris_ods --engine doris --sql "SELECT * FROM doris_ods.some_table_df WHERE ds = (SELECT MAX(ds) FROM doris_ods.some_table_df) LIMIT 100"`
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/run_sql.py" --database doris_ods --engine doris --sql "SELECT * FROM doris_ods.some_table_di WHERE ds BETWEEN '2026-03-01' AND '2026-03-13' LIMIT 100"`
  - `DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1 "$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/run_sql.py" --database opendataworks --engine mysql --sql "SELECT dl.lineage_type, ut.table_name AS upstream_table, dt.table_name AS downstream_table FROM opendataworks.data_lineage dl LEFT JOIN opendataworks.data_table ut ON ut.id = dl.upstream_table_id AND ut.deleted = 0 LEFT JOIN opendataworks.data_table dt ON dt.id = dl.downstream_table_id AND dt.deleted = 0 WHERE (ut.table_name = 'your_table' OR dt.table_name = 'your_table') ORDER BY dl.id DESC LIMIT 100"`
- 禁止：
  - 没定位到数据库就执行
  - 用来“试着猜一下”
  - 跳过 `validate_sql.py`
- 收口规则：
  - `sql_execution` 返回后就优先结束本轮推理
  - 首次返回非空且口径正确的 `sql_execution` 后，不要继续换表、换字段或重复执行等价 SQL
- 若 `row_count = 0`，直接说明无数据，不要继续无休止换表或重复试探
- 若返回 `permission_denied`、`datasource_mismatch`、`unknown_table`、`unknown_column` 或 `tool_timeout`，按 `stop_reason` 收口，不要再换库、换表、换字段重试。

## odw-cli 内部命令

- 作用：
  - 这是非 MCP fallback 路径下的内部 CLI；skill 脚本通过 `${DATAAGENT_SKILL_ROOT}/bin/odw-cli` 调 backend agent API
  - 模型不要把 CLI 当成首选入口，但需要知道它支持哪些动作和参数，便于理解脚本 fallback 行为
- 入口环境变量：
  - `ODW_BACKEND_BASE_URL`：固定指向 backend AI 根路径，规范值为 `http://backend:8080/api/v1/ai`
  - `ODW_AGENT_SERVICE_TOKEN`：backend service token
  - `ODW_BACKEND_TIMEOUT_SECONDS`：CLI 默认 HTTP 超时
- 兼容规则：
  - CLI 兼容旧值 `http://backend:8080/api/v1/ai/metadata`
  - 迁移后仍以 `/api/v1/ai` 作为文档和部署默认值
- 支持命令：
  - `inspect --keyword KW [--database DB] [--table TABLE] [--table-limit N]`
  - `lineage [--table TABLE] [--db-name DB] [--table-id ID] [--depth N]`
  - `resolve-datasource --database DB [--engine mysql|doris]`
  - `export --kind metadata|lineage|datasource [--database DB] [--table TABLE] [--table-id ID]`
  - `ddl [--database DB --table TABLE] [--table-id ID]`
  - `query-readonly --database DB --sql SQL [--preferred-engine mysql|doris] [--limit N] [--timeout-seconds S]`
- 输出边界：
  - `resolve-datasource` 与 `export kind=datasource` 只返回 datasource 摘要，不返回 host / port / user / password / readonly_* 等敏感字段

## build_chart_spec.py

- 用途：把 SQL 结果转换成图表规范
- 典型决策：
  - 分类对比 -> `bar`
  - 时间趋势 -> `line`
  - 占比分析 -> `pie`
  - 用户明确要独立表格 -> `table`
  - 其他 -> 只保留 `sql_execution`
- 默认保底：
  - 不适合图表时不输出图表，直接保留 `sql_execution`
- 收口规则：
  - 成功返回一次 `chart_spec` 后就结束本轮，不要再次调用图表脚本
- 参数规则：
  - 优先使用 `--input '<sql_execution_json>'`
  - 只有 JSON 过长时才使用 `--input-file`
  - 对比必须显式传 `--chart-type bar`
  - 趋势必须显式传 `--chart-type line`
  - 占比必须显式传 `--chart-type pie`
  - 只有用户明确要独立表格时才传 `--chart-type table`
- 命令模板：
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/build_chart_spec.py" --chart-type bar --input '{"kind":"sql_execution","rows":[...]}'`
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/build_chart_spec.py" --chart-type line --input-file /tmp/sql_execution.json`

## format_answer.py

- 用途：整理最终中文结论
- 使用时机：
  - 已经拿到 SQL 执行结果
  - 需要压缩成用户可直接消费的结论
- 命令模板：
  - `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/format_answer.py" --input-file /tmp/sql_execution.json`

## 推荐脚本序列

- 统计：平台核心表可直接进入 `validate_sql.py` -> `run_sql.py` 只读查询快路径；托管数据表用 `inspect_metadata.py` -> `validate_sql.py` -> `run_sql.py`
- 对比：平台核心表可直接进入 `validate_sql.py` -> `run_sql.py` 只读查询快路径 -> `build_chart_spec.py --chart-type bar`；托管数据表用 `inspect_metadata.py` -> `validate_sql.py` -> `run_sql.py` -> `build_chart_spec.py --chart-type bar`
- 趋势：平台核心表可直接进入 `validate_sql.py` -> `run_sql.py` 只读查询快路径 -> `build_chart_spec.py --chart-type line`；托管数据表用 `inspect_metadata.py` -> `validate_sql.py` -> `run_sql.py` -> `build_chart_spec.py --chart-type line`
- 占比：平台核心表可直接进入 `validate_sql.py` -> `run_sql.py` 只读查询快路径 -> `build_chart_spec.py --chart-type pie`；托管数据表用 `inspect_metadata.py` -> `validate_sql.py` -> `run_sql.py` -> `build_chart_spec.py --chart-type pie`
- 明细：平台核心表可直接进入 `validate_sql.py` -> `run_sql.py` 只读查询快路径；托管数据表用 `inspect_metadata.py` -> `validate_sql.py` -> `run_sql.py`
- 诊断：优先 `mcp__portal__portal_get_lineage` / `mcp__portal__portal_get_table_ddl`；无 MCP 时优先 `get_lineage.py` / `get_table_ddl.py`，只有结果仍不足时再 `inspect_metadata.py` -> `resolve_datasource.py` -> `validate_sql.py` -> `run_sql.py`
- 工作流发布趋势快路径：`21-metric-index.md` -> `22-sql-example-index.md` -> `validate_sql.py` -> `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/run_sql.py" --database opendataworks --engine mysql --sql "<按 created_at 按天聚合 workflow_publish_record 的 SQL>"` -> `build_chart_spec.py --chart-type line`；首个有效结果返回后直接总结

## 诊断直达规则

- 对 `workflow_publish_record` 或任意已给出明确表名的平台核心表诊断问题，不要再搜索仓库代码、测试文件或文档实现。
- 上游 / 下游 / 血缘问题的第一动作应是 `mcp__portal__portal_get_lineage`；无 MCP 时使用 `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/get_lineage.py" --table <table> [--db-name <db>]`。
- 只有 lineage 快照里缺少用户明确需要的额外字段时，才允许再用 `validate_sql.py` -> `run_sql.py` 查询 `data_lineage + data_table`。
- 如果 `run_sql.py` 返回“请先使用 `portal_get_lineage` / `get_lineage.py`”之类的 guard 错误，不要继续猜等价 SQL，直接切回 lineage 专用路径。
- 如果第一次 lineage 工具结果已返回非空数据，即使部分 `upstream_table` / `downstream_table` 为空，也直接基于现有结果总结；不要为了补齐空列继续追加第二条 SQL。
- 只有表名不唯一、数据库不清或字段不清时，才允许退回 `inspect_metadata.py` 或追问。

## 何时必须先追问

- 数据层级定义不清
- 发布状态口径不清
- 用户说“对比”但没说维度
- 用户说“趋势”但没说指标
- 目标表名存在多个候选
