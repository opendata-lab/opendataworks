---
name: chart-visualization
description: "当回答涉及趋势、分布、排行、占比、对比等可视化需求，需要把一组行数据转成前端可渲染的图表契约时使用。用户提到画图、图表、可视化、趋势图、饼图、柱状图、折线图时必须使用。只负责图表契约构建与图表选型，不负责取数。"
compatibility: "需要 DATAAGENT_PYTHON_BIN 与 SKILLS_ROOT_DIR。仅依赖 Python 标准库，不需要任何数据平台、MCP 或外部服务。"
tools: [Bash, Read]
---

# 图表可视化技能

把一组已经拿到的行数据转换成 `chart_spec` 契约 JSON，由前端渲染成图表。

本技能与数据来源无关：行数据可以来自 SQL 查询、文件读取、上游工具输出或用户直接提供。
本技能不取数、不连接数据库、不调用任何平台接口。

## 范围

负责：

- 根据数据结构和分析意图选择图表类型。
- 构建并校验 `chart_spec` 契约 JSON。
- 在数据不适合成图时给出明确判断。

不负责：

- 查询数据、执行 SQL、访问元数据。
- 生成 PNG/SVG 图片或图片链接。
- 报告与文件导出（交给 `report-generation`）。

## 调用方式

图表契约只能由脚本实际执行产出。`build_chart_spec.py` 不是独立注册的工具名，
它是通过 Bash 工具执行的脚本：

```bash
"$DATAAGENT_PYTHON_BIN" "${SKILLS_ROOT_DIR}/chart-visualization/scripts/build_chart_spec.py" \
  --chart-type <bar|line|area|scatter|combo|radar|funnel|gauge|pie|table> \
  --input '<行数据 JSON>' \
  [--title "<标题>"] [--x-field <维度字段>] [--y-field <度量字段>] [--stack]
```

`--input` 接受两种形态：JSON 数组，或含 `rows` 字段的 JSON 对象。
数据较大时改用 `--input-file <路径>`，避免命令行超长。

## 硬规则

- 必须通过 Bash 工具实际调用脚本。不得凭记忆把契约 JSON 写进回答，也不得用 ASCII 或 Unicode 字符模拟图表。
- 回答中出现的 `chart_spec` JSON 只能是本轮脚本实际运行后 stdout 的原样内容，不加标签、不加代码块包裹。
- 严禁手写 `<chart_spec>` / `</chart_spec>` 标签、` ```chart ` 代码块，或 `type=bar` 之类自创的图表配置块。
- 严禁用 Markdown 图片语法（`![](...)`）、图片链接、`chart_spec://`、`sandbox:`、`attachment:`
  等伪 URL 渲染图表。脚本从不生成图片，前端是唯一渲染器，写出图片链接只会让用户看到裂图。
- 图表必须基于真实且完整的数据构建，不得捏造、抽样或只截取前 N 个数据点。
  数据已被 `has_more`、`truncated_by_size` 或 `result_truncated` 标记为不完整时不得出图，
  应先把取数收敛到完整有界的结果。
- 数据为空或不足以构成有意义的图表时，说明原因，不强行生成空图表。
  纯文字解释类回答不需要图表。

## 图表选型

| 场景 | 类型 | 说明 |
|---|---|---|
| 时间趋势 | `line` | 强调累积量时用 `area` |
| 排行、分类对比 | `bar` | 多分组堆叠加 `--stack` |
| 占比结构 | `pie` | 类别数较多时改用 `bar` |
| 相关性 | `scatter` | 两个数值字段 |
| 量级 + 比率混合 | `combo` | 双轴 |
| 少数对象多指标对比 | `radar` | |
| 阶段转化 | `funnel` | |
| 单一关键指标 | `gauge` | |
| 明细展示 | `table` | 维度过多、不适合图形化时 |

每种类型的适用条件、禁用条件和字段要求见 `assets/chart-template/<type>.json`，
其中 `when_to_use`、`avoid_when`、`required_fields`、`spec_hints` 可直接用于选型判断。
不确定选哪种时先读对应模板，不要凭印象猜。
