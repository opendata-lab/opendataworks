# 技能地图

先结论：本 Skill 只决定通用问数方法和工具路径；业务术语、指标口径、本体、别名和歧义消解由已启用的业务知识 Skill 负责。

## 问题类型到方法路径

| 问题类型 | 先确认 | 优先工具 | 默认输出 |
| --- | --- | --- | --- |
| 统计 | 指标、过滤、时间范围、目标表 | `mcp__portal__portal_query_readonly`；无 MCP 时 `validate_sql.py` -> `run_sql.py` | 表格 |
| 对比 | 指标、分组维度、共同过滤、时间范围 | metadata / datasource / query tools | 条形图或表格 |
| 趋势 | 指标、时间字段、时间粒度、时间范围 | metadata / datasource / query tools | 折线图或表格 |
| 占比 | 指标、分类维度、类别数量 | metadata / datasource / query tools | 饼图或表格 |
| 明细 | 明细对象、字段、过滤、排序、行数 | metadata / datasource / query tools | 表格 |
| 诊断 | 目标对象、所需证据类型、范围 | lineage / DDL / metadata / query tools | 表格和结论 |
| 术语或口径 | 业务知识是否已定义 | 业务知识 Skill | 中文解释 |
| SQL 示例 | 语义、库表、字段、约束 | 业务知识 Skill + 本 Skill 方法检查 | SQL 模板说明 |

## 快速判断规则

- 问“多少、数量、总数、次数”通常是统计。
- 问“各类、各状态、各对象”通常是对比或占比。
- 问“最近、按天、按周、变化、趋势”通常是趋势。
- 问“明细、列表、最近记录”通常是明细。
- 问“上游、下游、血缘、DDL、建表语句、路由到哪里”通常是诊断。
- 问“是什么、怎么算、口径、别名”先走业务知识 Skill。

## 先追问的情形

- 业务术语或指标口径没有被用户或业务知识 Skill 确认。
- 目标 database、schema 或 table 不唯一。
- 时间范围或时间粒度不清。
- 对比问题没有指定分组维度。
- 趋势问题没有指定指标或时间字段。
- 候选表跨不同数据源。
- 查询需要 JOIN、去重、明细定位或血缘映射，但关联键不清。

## 何时执行工具

- SQL 所需语义、库表、字段、过滤、时间范围已经确认。
- DDL、lineage、metadata 或 datasource 是回答问题的必要证据。
- 用户要真实数据结果，且 runtime 提供可用 MCP 或 script fallback。

## 何时停止

- 第一次 `sql_execution` 已经能回答问题。
- 第一次 `chart_spec` 已经匹配结果形状。
- 工具返回不可重试失败，并且失败原因足以向用户说明缺口。
