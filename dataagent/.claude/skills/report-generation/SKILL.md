---
name: report-generation
description: "当需要把一组行数据导出成 Excel 或 HTML 报告文件，或需要从结果 JSON 中提炼结构化摘要时使用。用户提到生成报告、导出报表、导出 Excel、下载表格、结果摘要时必须使用。只负责结果加工与导出，不负责取数。"
compatibility: "需要 DATAAGENT_PYTHON_BIN 与 SKILLS_ROOT_DIR，以及 pandas 与 openpyxl。不需要任何数据平台、MCP 或外部服务。"
tools: [Bash, Read]
---

# 报告生成技能

把一组已经拿到的行数据导出为 Excel 或 HTML 报告文件，以及从结果 JSON 中提炼摘要观察项。

本技能与数据来源无关：行数据可以来自 SQL 查询、CSV 文件、上游工具输出或用户直接提供。
本技能不取数、不连接数据库、不调用任何平台接口。

## 范围

负责：

- 生成带样式的 Excel（`.xlsx`）报表。
- 生成自包含的 HTML 报告。
- 从结果 JSON 提炼摘要观察项。

不负责：

- 查询数据、执行 SQL、访问元数据。
- 图表契约构建（交给 `chart-visualization`）。
- 生成 PDF 或其它格式。

## 调用方式

### 生成报告文件

```bash
"$DATAAGENT_PYTHON_BIN" "${SKILLS_ROOT_DIR}/report-generation/scripts/generate_report.py" \
  --input '<行数据 JSON 或 CSV 文件路径>' \
  --output <输出路径，须以 .xlsx 或 .html 结尾> \
  [--title "<报告标题>"]
```

`--input` 接受三种形态：CSV 文件路径、JSON 数组、含 `rows` 字段的 JSON 对象。
输出目录不存在时会自动创建。输出后缀决定格式，`.xlsx` 走 Excel，`.html` 走 HTML，其它后缀直接报错。

### 提炼结果摘要

```bash
"$DATAAGENT_PYTHON_BIN" "${SKILLS_ROOT_DIR}/report-generation/scripts/format_answer.py" \
  --input '<结果 JSON>'
```

输入含 `rows` 与可选 `summary`、`has_more`。输出 `result.observations`，
包含首行样例；`has_more` 为真时追加结果被截断的提示。

数据较大时两个脚本都改用 `--input-file <路径>`，避免命令行超长。

## 硬规则

- 必须通过 Bash 工具实际执行脚本生成文件。不得声称已生成报告却没有真实调用，
  也不得凭记忆编造 `output_path` 或 `file_size`。
- 报告内容必须基于真实且完整的数据，不得捏造行、抽样或只截取前 N 行。
  数据已被标记为不完整时应先把取数收敛到完整结果，再导出。
- 输出路径必须落在当前工作区内，不得写入工作区之外的绝对路径。
- 脚本失败时返回 `status: "failed"` 与 `error`，此时必须如实说明失败原因，
  不得把失败当成功回报。
- 报告是文件产物，不是图表。需要图表时用 `chart-visualization`，两者可以同时使用。

## 输出契约

`generate_report.py` 成功时输出 `kind: "report_generation"`，含 `status`、`output_path`、
`format`、`row_count`、`file_size`、`summary`；失败时 `status` 为 `failed` 且 `error` 非空。

`format_answer.py` 输出 `kind: "python_execution"`，含 `summary` 与 `result.observations`。
