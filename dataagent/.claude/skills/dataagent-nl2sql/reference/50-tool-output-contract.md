# 工具输出契约

先结论：结果表达统一走工具输出。`sql_validation` 负责 SQL 执行前门禁，`sql_execution` 负责结果明细，`chart_spec` 负责前端渲染所需的严格图表契约。

`build_chart_spec.py` 不负责生成图片文件，只负责输出结构化 `chart_spec`。真正生图由前端根据 `chart_spec` 渲染。

## 输出种类

- `metadata_snapshot`
  - 表、字段、血缘定位
- `datasource_resolution`
  - engine / database / cluster 确认
- `table_ddl`
  - live DDL、字段摘要、来源集群信息
- `sql_validation`
  - SQL 执行前验证结果、错误、警告和停止原因
- `sql_execution`
  - SQL 文本、表格结果、耗时、错误
- `python_execution`
  - 脚本执行摘要和结构化返回
- `chart_spec`
  - 表格、条形图、折线图、饼图

## SQL 表格承载

`table_ddl` 示例：

```json
{
  "kind": "table_ddl",
  "database": "opendataworks",
  "table_name": "workflow_publish_record",
  "engine": "mysql",
  "cluster_name": null,
  "fields": [
    { "field_name": "workflow_id", "field_comment": "工作流ID" }
  ],
  "ddl": "CREATE TABLE `workflow_publish_record` (...)",
  "error": null
}
```

默认明细结果仍然由 `sql_execution` 承载；只有技能明确需要独立表格展示时，才额外输出 `chart_type=table` 的 `chart_spec`。

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
  "ontology": ".claude/skills/business-domain-assistant/assets/ontology.json"
}
```

验证失败时，必须修正 SQL 后再进入 `run_sql.py`；不要绕过 `sql_validation` 直接执行。

```json
{
  "kind": "sql_execution",
  "tool_label": "SQL 执行",
  "engine": "mysql",
  "database": "opendataworks",
  "sql": "select date(created_at) as stat_day, count(*) as publish_cnt from workflow_publish_record ...",
  "columns": ["stat_day", "publish_cnt"],
  "rows": [{"stat_day": "2026-03-01", "publish_cnt": 3}],
  "row_count": 30,
  "has_more": false,
  "duration_ms": 120,
  "summary": "返回最近30天工作流发布次数趋势数据",
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
- `error_code`: 成功时为 `null`；空结果为 `empty_result`；失败时为 `permission_denied`、`datasource_mismatch`、`unknown_table`、`unknown_column`、`tool_timeout`、`non_readonly_sql`、`lineage_guard` 或 `query_failed`
- `failure_attribution`: 用于报告归因，例如 `empty_result`、`permission_denied`、`schema_mismatch`、`datasource_mismatch`、`tool_timeout`、`invalid_sql`
- `retryable`: 当前 agent 是否应该继续重试；第一版固定由工具给出，模型不得自行扩大重试
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
  "title": "最近30天工作流发布趋势",
  "description": "按天展示 workflow_publish_record 发布次数变化",
  "x_field": "stat_day",
  "series": [
    { "name": "发布次数", "field": "publish_cnt", "type": "line" }
  ],
  "dataset": [
    { "stat_day": "2026-03-01", "publish_cnt": 3 }
  ],
  "error": null
}
```

`table` 类型示例：

```json
{
  "kind": "chart_spec",
  "version": 1,
  "chart_type": "table",
  "title": "最近工作流发布记录",
  "description": "以表格展示最近工作流发布记录",
  "columns": ["workflow_id", "version_id", "target_engine", "status", "created_at"],
  "dataset": [
    {
      "workflow_id": 173,
      "version_id": 546,
      "target_engine": "dolphin",
      "status": "success",
      "created_at": "2026-02-26 16:34:27"
    }
  ],
  "error": null
}
```

## 图表规则

- 时间维度 + 数值指标：优先 `line`
- 分类维度 + 对比或 TopN：优先 `bar`
- 占比分析且类别数 2 到 8：优先 `pie`
- 明细场景且技能明确要求独立表格时，才输出 `table`
- 不适合图表时，不输出 `chart_spec`，只保留 `sql_execution`
- 生成图表时，优先把完整 `sql_execution` JSON 直接作为 `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/build_chart_spec.py" --input ...` 传入；只有 JSON 过长时才落临时文件。
- 对比 / 趋势 / 占比场景，必须显式传 `--chart-type`，不要把图表类型完全交给脚本猜。
- 前端不再从 `dataset` 自动推断 `series`、`x_field` 或排序规则；这些字段必须由技能显式给出。

## 前端渲染约束

- 前端是唯一图表渲染器；后端和脚本不生成 PNG、SVG 或静态图片 URL。
- `table` 必须显式提供 `columns`
- `bar` / `line` / `pie` 必须显式提供 `x_field` 和 `series`
- `pie` 必须且只能提供 1 个 `series`
- `dataset` 顺序由技能决定，前端按原顺序渲染，不再自动重排
- `version` 当前固定为 `1`

## 图表模板来源

图表语义模板在：

- `assets/chart-template/table.json`
- `assets/chart-template/bar.json`
- `assets/chart-template/line.json`
- `assets/chart-template/pie.json`

`chart_spec` 应当与这些模板的语义约束保持一致，而不是任意扩写前端 option。
