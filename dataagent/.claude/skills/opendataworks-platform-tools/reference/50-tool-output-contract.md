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
  "truncated_by_size": false,
  "notice": null,
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
- `error_code`: 成功时为 `null`；空结果为 `empty_result`；按体积截断为 `result_truncated`；失败时为 `permission_denied`、`datasource_mismatch`、`unknown_table`、`unknown_column`、`tool_timeout`、`non_readonly_sql` 或 `query_failed`
- `failure_attribution`: 用于报告归因，例如 `empty_result`、`permission_denied`、`schema_mismatch`、`datasource_mismatch`、`tool_timeout`、`invalid_sql`
- `retryable`: 当前 agent 是否应该继续重试
- `stop_reason`: 失败、空结果或按体积截断时给模型的中文收口理由

结果体积守卫：

- 后端 `/v1/ai/query/read` 在源头按字节预算（默认 512KB）截断返回行，避免单条工具结果撑爆运行时 JSON 缓冲。
- 截断时返回 `truncated_by_size=true`、`has_more=true` 与中文 `notice`/`stop_reason`。
- 收到截断信号应缩小查询范围（增加过滤、聚合或降低 LIMIT）后再查，不要对同一口径重复执行；若样本已足够回答也可直接基于已返回行作答并说明结果不完整。

## SQL 导出

`export_query.py` 把全量结果写工作区 CSV，只回路径与预览（`kind=sql_export`）：

```json
{
  "kind": "sql_export",
  "tool_label": "SQL 导出",
  "engine": "mysql",
  "database": "example_schema",
  "sql": "SELECT ...",
  "file_path": "/path/to/workspace/exports/result.csv",
  "file_format": "csv",
  "columns": ["col_a", "col_b"],
  "row_count": 621,
  "has_more": false,
  "preview_rows": [{ "col_a": "x", "col_b": 1 }],
  "summary": "已导出 621 行到 /path/to/workspace/exports/result.csv",
  "result_state": "success",
  "error_code": null,
  "failure_attribution": [],
  "retryable": false,
  "stop_reason": "",
  "error": null
}
```

- 全量数据在文件里，不在 `preview_rows`；后续处理（如生成 Excel）应让 Python 读 `file_path`，不要把整份 CSV 读进上下文。
- `has_more=true` 表示命中行数上限（默认/最大 10000），应改用更精确的过滤或聚合。

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
