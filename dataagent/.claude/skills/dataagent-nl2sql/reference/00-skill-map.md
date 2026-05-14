# 技能地图

先结论：任何问题都先分类，再决定阅读哪类摘要或执行哪类工具。优先 `portal-mcp`，没有 MCP 再回退脚本。不要一上来扫描全部资产。

## 问题类型到执行路径

| 问题类型 | 先看什么 | 优先工具 | 默认结果 |
| --- | --- | --- | --- |
| 统计 | `10-query-playbooks.md`、`21-metric-index.md` | 优先 `mcp__portal__portal_query_readonly`；无 MCP 时已知平台核心表可直接 `validate_sql.py` -> `run_sql.py`，否则 `inspect_metadata.py` -> `validate_sql.py` -> `run_sql.py` | 表格 |
| 对比 | `10-query-playbooks.md`、`21-metric-index.md` | 优先 `mcp__portal__portal_search_tables` / `mcp__portal__portal_query_readonly`；无 MCP 时已知平台核心表可直接 `validate_sql.py` -> `run_sql.py`，否则 `inspect_metadata.py` -> `validate_sql.py` -> `run_sql.py` -> `build_chart_spec.py` | 条形图 / 表格 |
| 趋势 | `10-query-playbooks.md`、`21-metric-index.md` | 优先 `mcp__portal__portal_query_readonly`；无 MCP 时已知平台核心表可直接 `validate_sql.py` -> `run_sql.py`，否则 `inspect_metadata.py` -> `validate_sql.py` -> `run_sql.py` -> `build_chart_spec.py` | 折线图 / 表格 |
| 占比 | `10-query-playbooks.md`、`20-term-index.md` | 优先 `mcp__portal__portal_query_readonly`；无 MCP 时已知平台核心表可直接 `validate_sql.py` -> `run_sql.py`，否则 `inspect_metadata.py` -> `validate_sql.py` -> `run_sql.py` -> `build_chart_spec.py` | 饼图 / 表格 |
| 明细 | `10-query-playbooks.md`、`30-tool-recipes.md` | 优先 `mcp__portal__portal_query_readonly`；无 MCP 时已知平台核心表可直接 `validate_sql.py` -> `run_sql.py`，否则 `inspect_metadata.py` -> `validate_sql.py` -> `run_sql.py` | 表格 |
| 诊断 | `10-query-playbooks.md`、`40-runtime-metadata.md` | 平台核心表优先 `mcp__portal__portal_query_readonly` / `mcp__portal__portal_get_lineage` / `mcp__portal__portal_get_table_ddl`；托管数据表优先 `mcp__portal__portal_search_tables`，无 MCP 再回退脚本 | 表格 |
| 术语解释 | `20-term-index.md` | 无，必要时回看资产 | 中文解释 |
| SQL 示例 | `22-sql-example-index.md` | 无，必要时回看资产 | SQL 模板示例 |

## 快速判断规则

- 问“多少、数量、总数、次数”通常是统计
- 问“各层级、各状态、各工作流、各引擎”通常是对比或占比
- 问“最近 30 天、按天变化、趋势”通常是趋势
- 问“明细、列表、最近失败记录”通常是明细
- 问“某张表的上游下游、某数据库路由到哪个集群、某任务被哪个工作流管理”通常是诊断
- 问“什么是数据层级、血缘关系、工作流发布记录”属于术语解释
- 问“给个 SQL、类似 SQL 怎么写”属于 SQL 示例

## 平台核心表直达规则

如果问题明确指向下列平台核心表，并且字段也足够清楚，可以直接进入 `database=opendataworks`、`engine=mysql` 的只读查询路径，不必先走 `inspect_metadata.py`。若 `mcp__portal__portal_query_readonly` 可见，优先直接调用它；无 MCP 时先走 `validate_sql.py`，再走 `run_sql.py --database opendataworks --engine mysql`。这两条路径最终都经由 backend 代执行，不是 skill/runtime 直连 MySQL：

- `data_table`
- `data_field`
- `data_lineage`
- `data_task`
- `table_task_relation`
- `data_workflow`
- `workflow_task_relation`
- `workflow_version`
- `workflow_publish_record`
- `doris_cluster`
- `doris_database_users`

## 先追问的情形

- 数据层级、发布状态、任务依赖、Doris 只读账号口径不清
- 用户命中 Doris `di` 增量表，但没有给时间范围
- 对比维度没说清
- 趋势指标没说清
- 同名表可能存在于多个数据库
- 时间范围与时间粒度不清
- 问题依赖当前内置 skill 没定义的租户私有术语、私有对象或默认过滤

## 何时下钻资产

- `20-term-index.md` 仍无法消除术语歧义时，查看 `assets/term_explanations.json`
- `21-metric-index.md` 仍无法确认默认聚合或时间字段时，查看 `assets/metrics.json`、`assets/business_rules.json`
- `22-sql-example-index.md` 仍无法找到合适模板时，查看 `assets/sql_examples.json`

## 何时执行脚本

- 平台核心表问题且字段已清楚：优先 `mcp__portal__portal_query_readonly`；无 MCP 时先 `validate_sql.py` 再 `run_sql.py`
- 需要上游 / 下游 / 血缘快照：优先 `mcp__portal__portal_get_lineage`；无 MCP 时用 `get_lineage.py`
- 需要 live DDL / `SHOW CREATE TABLE`：优先 `mcp__portal__portal_get_table_ddl`；无 MCP 时用 `get_table_ddl.py`
- 托管数据表、字段或库表不清：优先 `mcp__portal__portal_search_tables`；无 MCP 时先 `inspect_metadata.py`
- 引擎不清：优先 `mcp__portal__portal_resolve_datasource`；无 MCP 时再 `resolve_datasource.py`
- 结果结构适合图表：再 `build_chart_spec.py`
