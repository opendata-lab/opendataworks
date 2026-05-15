# 工具输出契约

先结论：结果表达统一走工具输出。`sql_validation` 负责 SQL 执行前门禁，`sql_execution` 负责结果明细，`chart_spec` 负责前端渲染所需的严格图表契约。

## 输出种类

- `metadata_snapshot`
- `datasource_resolution`
- `table_ddl`
- `sql_validation`
- `sql_execution`
- `python_execution`
- `chart_spec`

## SQL 验证

`sql_validation` 示例：

```json
{
  "kind": "sql_validation",
  "valid": true,
  "errors": [],
  "warnings": [],
  "passed": ["只读 SQL 检查通过", "表名 schema 前缀检查通过"],
  "error_code": null,
  "failure_attribution": [],
  "retryable": false,
  "stop_reason": "",
  "ontology": "<ontology-path-from-caller>"
}
```

验证失败时，必须修正 SQL 后再进入 `run_sql.py`；不要绕过 `sql_validation` 直接执行。

## SQL 执行

`sql_execution` 示例：

```json
{
  "kind": "sql_execution",
  "tool_label": "SQL 执行",
  "engine": "mysql",
  "database": "example_schema",
  "sql": "SELECT category, COUNT(*) AS row_count FROM example_schema.example_table GROUP BY category LIMIT 20",
  "columns": ["category", "row_count"],
  "rows": [{"category": "A", "row_count": 3}],
  "row_count": 1,
  "has_more": false,
  "duration_ms": 120,
  "summary": "返回分组统计结果",
  "result_state": "success",
  "error_code": null,
  "failure_attribution": [],
  "retryable": false,
  "stop_reason": "",
  "error": null
}
```

`sql_execution` 必须提供这些收口字段：

- `result_state`: `success`、`empty_result` 或 `failed`
- `error_code`: 成功时为 `null`；空结果为 `empty_result`；失败时为 `permission_denied`、`datasource_mismatch`、`unknown_table`、`unknown_column`、`tool_timeout`、`non_readonly_sql` 或 `query_failed`
- `failure_attribution`: 用于报告归因，例如 `empty_result`、`permission_denied`、`schema_mismatch`、`datasource_mismatch`、`tool_timeout`、`invalid_sql`
- `retryable`: 当前 agent 是否应该继续重试
- `stop_reason`: 失败或空结果时给模型的中文收口理由

## 图表契约

图表输出统一通过 `chart_spec`，由 `chart_type` 区分：

- `table`
- `bar`
- `line`
- `pie`

```json
{
  "kind": "chart_spec",
  "version": 1,
  "chart_type": "line",
  "title": "趋势图",
  "description": "按时间展示指标变化",
  "x_field": "stat_time",
  "series": [
    { "name": "数量", "field": "metric_value", "type": "line" }
  ],
  "dataset": [
    { "stat_time": "2026-05-01", "metric_value": 3 }
  ],
  "error": null
}
```

## 图表规则

- 时间维度 + 数值指标：优先 `line`
- 分类维度 + 对比或 TopN：优先 `bar`
- 占比分析且类别数 2 到 8：优先 `pie`
- 明细场景且明确要求独立表格时，才输出 `table`
- 不适合图表时，不输出 `chart_spec`，只保留 `sql_execution`
- 生成图表时，优先把完整 `sql_execution` JSON 直接作为输入传入；只有 JSON 过长时才落临时文件。
- 对比 / 趋势 / 占比场景，必须显式传 `--chart-type`。

## 前端渲染边界

- 前端是唯一图表渲染器；后端和脚本不生成 PNG、SVG 或静态图片 URL。
- `table` 必须显式提供 `columns`。
- `bar` / `line` / `pie` 必须显式提供 `x_field` 和 `series`。
- `pie` 必须且只能提供 1 个 `series`。
- `dataset` 顺序由技能决定，前端按原顺序渲染。
- `version` 当前固定为 `1`。
