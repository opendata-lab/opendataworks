# chart_spec 契约

图表输出统一通过 `chart_spec` 表达，由 `build_chart_spec.py` 产出。
本文件是该契约的唯一真相源；契约与数据来源无关，不假设输入必须来自 SQL。

## 图表类型

由 `chart_type` 区分：

- `table`：明细表格
- `bar`：分类对比 / TopN（`--stack` 可堆叠，`orientation:horizontal` 可横向）
- `line`：时间趋势
- `area`：趋势 + 累积量强调（line 的填充版，`--stack` 可堆叠）
- `scatter`：两个数值字段的相关性（x、y 均为数值轴）
- `combo`：组合双轴，首个数值走柱状/左轴，其余走折线/右轴
- `radar`：多指标对比（每行一个指标轴，建议指标轴 ≥ 3）
- `funnel`：转化漏斗（阶段 + 单数值）
- `gauge`：单 KPI 仪表盘（取首行单数值）
- `pie`：占比（类别 2~8）

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

- 时间维度 + 数值指标：优先 `line`；强调累积量用 `area`
- 分类维度 + 对比或 TopN：优先 `bar`；多分组堆叠用 `--stack`
- 占比分析且类别数 2 到 8：优先 `pie`
- 两个数值字段的相关性：用 `scatter`
- 同一维度上量级与比率/增速混合对比：用 `combo`（双轴）
- 少数对象在多指标上的对比：用 `radar`
- 阶段转化、逐级流失：用 `funnel`
- 单一关键指标当前值：用 `gauge`
- 明细场景且明确要求独立表格时，才输出 `table`
- 不适合图表时，不输出 `chart_spec`，只保留上游的结果输出
- 生成图表时，优先把完整的行数据 JSON 直接作为输入传入；只有 JSON 过长时才用 `--input-file` 落临时文件。
- `chart_spec.dataset` 必须是传入的完整结果行集，不得抽样或只截取前 N 行；时间序列按完整时间范围渲染，前端可自行做标签抽稀，但不能丢数据点。
- 如果输入数据已被 `has_more=true`、`truncated_by_size=true` 或 `error_code=result_truncated` 标记为不完整，不得生成图表；必须先把取数收敛到完整有界的结果，再重新生成 `chart_spec`。
- 对比 / 趋势 / 占比场景，必须显式传 `--chart-type`。

## 前端渲染边界

- 前端是唯一图表渲染器；后端和脚本不生成 PNG、SVG 或静态图片 URL。
- `table` 必须显式提供 `columns`。
- `bar` / `line` / `area` / `scatter` / `combo` / `radar` 必须显式提供 `x_field` 和 `series`。
- `pie` / `funnel` 必须且只能提供 1 个 `series`。
- `gauge` 必须且只能提供 1 个 `series`，`x_field` 可选（取首行单值）。
- `combo` 的 `series[].axis` 取 `left`/`right`，`series[].type` 取 `bar`/`line`。
- `scatter` 的 `x_field` 必须是数值字段。
- `dataset` 顺序由技能决定，前端按原顺序渲染。
- `version` 当前固定为 `1`。
