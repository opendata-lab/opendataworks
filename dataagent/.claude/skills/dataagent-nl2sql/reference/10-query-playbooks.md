# 场景 Playbooks

先结论：本技能优先覆盖统计、对比、趋势、占比、明细、诊断六类问题。若当前 run 已注入 `portal-mcp`，优先直接使用 `mcp__portal__portal_*`；否则再走脚本 fallback。对于 `opendataworks` 平台核心表问题，字段已清楚时可以直接进入 `database=opendataworks`、`engine=mysql` 的只读查询路径；对于托管数据表问题，再走 metadata -> datasource -> SQL。脚本 fallback 下固定先 `validate_sql.py`，再 `run_sql.py`。

## 托管数据表通用规则

- 一旦 metadata 确认 `db_name`，SQL 统一写成 `<db_name>.<table_name>`；不要只写裸表名。
- `mcp__portal__portal_search_tables`、`mcp__portal__portal_resolve_datasource`、`mcp__portal__portal_query_readonly` 是首选工具；`inspect_metadata.py`、`resolve_datasource.py`、`validate_sql.py`、`run_sql.py` 是兼容 fallback，其中 `run_sql.py` 仍然通过 backend 只读查询路径执行。
- `doris` / `mysql` 是引擎类型，不是 schema 名；不要把引擎名误写到 `FROM doris.xxx` 这种 SQL 里。
- 若 Doris 表名体现 `df` 快照含义，默认视为按 `ds` 存储的每日全量快照表。
- 非归因分析、非历史回溯、非用户显式指定历史区间时，`df` 快照表优先只查最新 `ds`。
- 若 Doris 表名体现 `di` 增量含义，默认视为按 `ds` 存储的每日增量表。
- `di` 增量表必须按时间范围查询；若用户未给范围，先追问，不要只查最新 `ds`，也不要扫全量历史。
- 如果问题依赖当前内置 skill 没定义的租户私有术语、私有对象或默认过滤，先追问，不要内置猜测。

## 统计

- 典型问题：当前 active 状态的数据表数量、最近 30 天新增工作流数量
- 先确认：
  - 统计指标
  - 时间范围
  - 是否需要过滤状态
- 推荐顺序：
  1. `21-metric-index.md`
  2. 优先 `mcp__portal__portal_query_readonly`
  3. 无 MCP 且平台核心表已明确时，直接进入 `validate_sql.py` -> `run_sql.py` 只读查询快路径
  4. 托管数据表场景才用 `inspect_metadata.py`
- 默认输出：表格
- 追问条件：
  - 指标口径不清
  - 时间范围不清
  - 命中 Doris `di` 增量表，但用户没有给时间范围
  - 问题依赖当前内置 skill 没定义的租户业务口径

## 对比

- 典型问题：各数据层表数量对比、各工作流任务数对比
- 先确认：
  - 对比维度
  - 指标
  - 时间范围是否一致
- 推荐顺序：
  1. `21-metric-index.md`
  2. `20-term-index.md`
  3. 优先 `mcp__portal__portal_query_readonly`
  4. 无 MCP 且平台核心表已明确时，直接进入 `validate_sql.py` -> `run_sql.py` 只读查询快路径
  5. 托管数据表场景才用 `inspect_metadata.py`
  6. `build_chart_spec.py --chart-type bar`
- 默认图表：条形图
- 回退输出：表格

## 趋势分析

- 典型问题：最近 30 天工作流发布次数趋势、最近 14 天新增任务趋势
- 先确认：
  - 指标
  - 时间粒度（日 / 周 / 月）
  - 时间范围
- 推荐顺序：
  1. `21-metric-index.md`
  2. `22-sql-example-index.md`
  3. 优先 `mcp__portal__portal_query_readonly`
  4. 无 MCP 且平台核心表已明确时，直接进入 `validate_sql.py` -> `run_sql.py` 只读查询快路径
  5. 托管数据表场景才用 `inspect_metadata.py`
  6. `build_chart_spec.py --chart-type line`
- 第一条真实工具动作：
  - MCP 可用：平台核心表场景直接 `mcp__portal__portal_query_readonly`；托管数据表场景先 `mcp__portal__portal_search_tables`
  - 无 MCP：平台核心表场景直接进入 `validate_sql.py` -> `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/run_sql.py" --database opendataworks --engine mysql --sql "SELECT ..."` 只读查询快路径；托管数据表场景先 `inspect_metadata.py`
- 选表规则：
  - 平台核心表问题优先直接用已知表结构，不要先兜圈读资产。
  - 托管数据表候选由模型根据字段与 reference 自己判断，不依赖脚本推荐。
  - 若时间字段不唯一，优先使用 `created_at`、`ds` 或明确的业务记录时间；仍不明确就追问。
- 数据源规则：
  - 平台核心表固定使用 `database=opendataworks`、`engine=mysql` 的只读查询路径，由 backend 代执行。
  - 托管数据表若已确定 `db_name`，优先 `mcp__portal__portal_resolve_datasource`；无 MCP 再调用 `resolve_datasource.py`；成功一次后不要重复调用。
- 快路径示例：
  - `最近 30 天工作流发布次数趋势` 命中 `workflow_publish_record` 时，固定按 `21-metric-index.md` -> `22-sql-example-index.md` -> `validate_sql.py` -> `run_sql.py` -> `build_chart_spec.py --chart-type line` 执行。
  - 默认使用 `workflow_publish_record.created_at` 按天聚合发布记录数；第一次返回口径正确的 `sql_execution` 和 `chart_spec` 后就直接总结，不再重复执行等价 SQL。
- 执行结果规则：
  - `run_sql.py` 返回 `sql_execution` 后就直接基于结果收口。
  - 如果结果为空，直接说明“当前筛选条件下无数据”，不要继续无休止切换口径。
  - `build_chart_spec.py` 成功返回一次 `chart_spec` 后就直接结束本轮。
- 强约束：
  - 完成 `21` 和 `22` 后就进入脚本，不要继续读取原始 JSON。
  - 只要脚本参数已明确，就必须真实执行 Bash；不要停留在 reference 阅读层直接给最终 SQL。
  - 没有实际 Bash 报错时，不要声称“缺少依赖”或“环境异常”。
  - Doris `df` 快照表若未明确要求历史区间，不要默认扫描全历史，优先只查最新 `ds`。
  - Doris `di` 增量表若未给时间范围，先追问；不要默认只查最新 `ds`，也不要直接扫全量日期。
- 默认图表：折线图
- 回退输出：表格

## 占比

- 典型问题：各工作流发布操作类型占比、各发布状态工作流占比
- 先确认：
  - 分类维度
  - 指标
  - 类别数量是否适合占比图
- 推荐顺序：
  1. `20-term-index.md`
  2. `21-metric-index.md`
  3. 优先 `mcp__portal__portal_query_readonly`
  4. 无 MCP 且平台核心表已明确时，直接进入 `validate_sql.py` -> `run_sql.py` 只读查询快路径
  5. 托管数据表场景才用 `inspect_metadata.py`
  6. `build_chart_spec.py --chart-type pie`
- 默认图表：饼图
- 回退条件：
  - 类别超过 8 个
  - 更适合条形图

## 明细

- 典型问题：最近工作流发布记录、某个数据库下的数据表清单
- 先确认：
  - 明细对象
  - 过滤条件
  - 需要哪些字段
- 推荐顺序：
  1. `20-term-index.md`
  2. `30-tool-recipes.md`
  3. 优先 `mcp__portal__portal_query_readonly`
  4. 无 MCP 且平台核心表已明确时，直接进入 `validate_sql.py` -> `run_sql.py` 只读查询快路径
  5. 托管数据表场景才用 `inspect_metadata.py`
- 默认输出：表格
- 约束：
  - 必须带 LIMIT
  - 不要强行出图
  - 命中 Doris `df` 快照表且未指定历史区间时，默认先过滤到最新 `ds`
  - 命中 Doris `di` 增量表时，必须带明确时间范围过滤，优先使用 `ds`

## 诊断

- 典型问题：某张表有哪些上游下游血缘、某个数据库路由到哪个 Doris 集群、某张表的 DDL / `SHOW CREATE TABLE`
- 先确认：
  - 目标表或目标数据库
  - 是否需要补充 `db_name`
  - 是否要看表级血缘还是任务级关系
- 推荐顺序：
  1. `20-term-index.md`
  2. `40-runtime-metadata.md`
  3. 上游 / 下游 / 血缘问题优先 `mcp__portal__portal_get_lineage`
  4. 平台核心表已明确且要补平台表字段时，再用 `mcp__portal__portal_query_readonly`
  5. 查看 DDL 时优先 `mcp__portal__portal_get_table_ddl`
  6. 托管数据表场景优先 `mcp__portal__portal_search_tables`
  7. 无 MCP 且上游 / 下游 / 血缘问题时直接 `get_lineage.py`
  8. 无 MCP 且需要 live DDL 时直接 `get_table_ddl.py`
  9. 无 MCP 时再使用 `inspect_metadata.py`
  10. 必要时 `mcp__portal__portal_resolve_datasource`；无 MCP 时再 `resolve_datasource.py`
- 默认输出：表格 + 诊断结论
- 强约束：
  - 用户已给出具体表名时，不要在仓库代码、测试文件或参考文档中搜索 lineage/血缘实现。
  - 用户问上游 / 下游 / 血缘时，第一动作必须是 `portal_get_lineage` 或 `get_lineage.py`；不要先猜 `run_sql.py`。
  - 只有 lineage 快照里缺少必要字段时，才允许追加 `validate_sql.py` -> `run_sql.py` 查询 `data_lineage + data_table` 补充。
  - `run_sql.py` 现在会根据 `DATAAGENT_ORIGINAL_QUESTION` 默认拒绝首轮 `data_lineage` 类 SQL；只有确定是补充查询时，才允许显式带 `DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1`。
  - 第一次 lineage 工具结果已返回非空数据时，直接基于结果总结；不要继续追加等价 SQL。

## 术语解释

- 典型问题：什么是数据层级、什么是工作流发布记录
- 推荐顺序：
  1. `20-term-index.md`
  2. 必要时回看 `assets/term_explanations.json`
- 通常不执行 SQL

## SQL 示例

- 典型问题：给我一个工作流发布趋势 SQL、血缘定位 SQL 怎么写
- 推荐顺序：
  1. `22-sql-example-index.md`
  2. 必要时回看 `assets/sql_examples.json`
- 输出要求：
  - 标明适用场景和引擎
  - 明确“示例仅用于参考，落地前需按真实库表校正”
