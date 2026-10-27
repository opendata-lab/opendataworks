# 输出契约

平台访问只使用已配置的 MCP 工具，见 opendataworks-platform-tools/SKILL.md。
方法论头部和每节点 trace 作为额外字段挂在同一层，消费方可以完全按既有方式处理结果。

## 成功

```json
{
  "kind": "sql_execution",
  "tool_label": "方法论执行",
  "methodology": {
    "id": "table_growth_ratio",
    "version": "1.0.0",
    "name_zh": "数据表分层环比增长",
    "caliber": "口径字段为 data_table.created_at；只统计 deleted = 0 的表；……",
    "owner": "data-platform",
    "params": {"days": 30, "layer": null}
  },
  "engine": "mysql",
  "database": "opendataworks",
  "sql": "<target 节点绑定后的 SQL>",
  "columns": ["layer", "current_cnt", "previous_cnt", "growth"],
  "rows": [{"layer": "ODS", "current_cnt": 12, "previous_cnt": 6, "growth": 1.0}],
  "row_count": 1,
  "has_more": false,
  "duration_ms": 842,
  "truncated_by_size": false,
  "notice": null,
  "summary": "返回 1 行结果",
  "error": null,
  "result_state": "success",
  "error_code": null,
  "failure_attribution": [],
  "retryable": false,
  "stop_reason": "",
  "trace": {
    "executed": 3,
    "pruned": 0,
    "nodes": [
      {"name": "current",  "type": "sql",    "status": "success", "duration_ms": 210, "row_count": 5},
      {"name": "previous", "type": "sql",    "status": "success", "duration_ms": 605, "row_count": 5},
      {"name": "growth",   "type": "sqlite", "status": "success", "duration_ms": 3,   "row_count": 5}
    ]
  }
}
```

## 字段说明

| 字段 | 说明 |
|---|---|
| `methodology.caliber` | 本次统计的口径。**回答里必须带上。** |
| `methodology.params` | 解析后的实际参数，含默认值填充结果。未提供的可选参数为 `null`。 |
| `sql` | `target` 节点绑定后的 SQL。中间节点的 SQL 在 trace 里，不在这里。 |
| `columns` / `rows` | `target` 节点的结果表。 |
| `trace.nodes[]` | 每个**实际执行过**的节点。没执行的节点不出现——这本身就是剪枝生效的证据。 |
| `trace.nodes[].status` | `success` 表示真实执行，`mock` 表示走了 mock 模式。 |
| `trace.nodes[].branch` / `pruned_branch` | 仅 `conditional` 节点有：选中和被剪掉的分支名。 |
| `trace.pruned` | 被剪掉的分支数量。 |

per-node trace 是声明式结构的直接红利：方法论作者不写任何埋点，就拿到了每步的耗时和行数。

## result_state

| 取值 | 含义 | 该怎么办 |
|---|---|---|
| `success` | 拿到真实结果 | 直接收口回答，带上口径 |
| `empty_result` | 执行成功但 0 行 | 说明口径与空结果，**不要**换方法论、改参数或换表试探 |
| `failed` | 执行失败 | 按 `error_code` 与 `stop_reason` 说明，不等价重试 |

`result_state=success` 且 `error_code=result_truncated` 表示结果按体积被截断，
此时**不得**据此出图；先缩小参数范围拿到完整有界结果。

## error_code

本层新增的错误码：

| error_code | 含义 | 处置 |
|---|---|---|
| `methodology_not_found` | 注册表里没有这个 id | 回落常规问数链路，不要猜 id |
| `methodology_invalid` | 工件本身没通过静态校验 | 修正注册表定义，不要重试 |
| `param_missing` | 缺必填参数 | 只追问 `stop_reason` 里列出的槽位 |
| `param_rejected` | 参数取值非法（类型、范围、枚举、绑定失败）| 修正参数取值，不要绕过方法论自己写 SQL |
| `methodology_timeout` | 总执行预算耗尽 | 缩小参数范围，或改后台执行 |
| `platform_tools_unavailable` | 同级目录与覆盖变量都找不到完整的 `opendataworks-platform-tools` | 说明缺少执行入口；这不是可以换个方式绕过的问题 |

查询节点失败时，`error_code`、`failure_attribution`、`stop_reason` 直接透传
平台访问只使用已配置的 MCP 工具，见 opendataworks-platform-tools/SKILL.md。
`datasource_mismatch`、`tool_timeout`、`non_readonly_sql`、`query_failed` 等），
所以既有的失败处置规则原样适用。

内存计算节点（`sqlite`）的 SQL 有误时报 `error_code=query_failed`、
`failure_attribution=["invalid_sql"]`——这是方法论定义的问题，需要改注册表，不是重试能解决的。

## 检索输出

`lookup_methodology.py` 产出 `kind=methodology_lookup`：

```json
{
  "kind": "methodology_lookup",
  "tool_label": "方法论检索",
  "query": "最近30天工作流发布次数趋势",
  "matched": 1,
  "results": [
    {
      "id": "workflow_publish_trend",
      "version": "1.0.0",
      "name_zh": "工作流发布次数趋势",
      "intent": "……",
      "caliber": "……",
      "params": [{"name": "days", "type": "int", "required": false, "default": 30, "values": [], "description": "……"}],
      "output_fields": ["stat_date", "publish_cnt", "failed_cnt", "failed_rate"],
      "node_count": 4,
      "score": 0.6667
    }
  ],
  "stop_reason": ""
}
```

`matched=0` 时 `stop_reason` 会明确要求回落常规问数链路。

## 校验输出

`validate_methodology.py` 产出 `kind=methodology_validation`，
含 `valid`、`checked`、`results[]`（每项有 `id`、`valid`、`errors[]`、`warnings[]`）。
退出码 0 表示全部通过，非 0 表示有工件未通过。
