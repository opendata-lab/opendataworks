---
name: opendataworks-platform-tools
description: "Use this bundled skill for OpenDataWorks platform capabilities: metadata lookup, table and field discovery, datasource routing, lineage, DDL, read-only SQL validation and execution, result formatting, and chart-contract output. It owns tools, not business semantics or generic query methodology."
compatibility: "Requires DATAAGENT_PYTHON_BIN, DATAAGENT_PLATFORM_SKILL_ROOT, and either visible portal MCP tools or this skill's bin/odw-cli with backend service access."
tools: [Bash, Read]
---

# OpenDataWorks Platform Tools Skill

This is the OpenDataWorks 平台工具 Skill. It exposes real platform capabilities: 获取表、获取字段、获取血缘、获取 DDL、解析数据源、验证 SQL、执行只读 SQL、格式化结果和生成图表契约.

It does not define business terms, ontology, metric口径, aliases, ambiguity rules, or query methodology. Use business knowledge skills for semantics and the generic SQL skill for method. Use this skill only when real OpenDataWorks platform evidence or execution is required.

## Scope

Covered:

- Metadata search and candidate table or field inspection.
- Datasource routing and engine/database resolution.
- Table DDL and field detail lookup.
- Lineage lookup.
- Read-only SQL validation.
- Read-only SQL execution through backend platform APIs.
- SQL execution result formatting.
- `sql_execution` and `chart_spec` tool output contracts.
- MCP-first, script fallback second.

Out of scope:

- Business terminology or metric definitions.
- Object ontology and semantic mappings.
- Domain aliases and ambiguity rules.
- Generic NL2SQL reasoning methodology.
- Tenant-private knowledge.

## Iron Laws

1. **ALWAYS** prefer portal-mcp tools when they are visible in the current run.
2. **ONLY** use script fallback when portal-mcp tools are unavailable.
3. **ALWAYS** call fallback scripts through:
   `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/<name>.py" ...`
4. **NEVER** use primary `DATAAGENT_SKILL_ROOT` for platform scripts.
5. **ALWAYS** validate confirmed SQL before script-fallback execution.
6. **ALWAYS** execute confirmed SQL through the single read-only SQL execution entrypoint.
7. **NEVER** execute INSERT, UPDATE, DELETE, DROP, TRUNCATE, ALTER, CREATE, REPLACE, or other write statements.
8. **ALWAYS** include a LIMIT clause on SELECT queries unless the validated statement type cannot support it.
9. **NEVER** expose datasource credentials or direct database connection details.
10. **ALWAYS** stop after the first sufficient platform result or non-retryable failure attribution.

## Reading Order

1. Read [`reference/30-tool-recipes.md`](reference/30-tool-recipes.md) for the exact platform capability path.
2. Read [`reference/40-runtime-metadata.md`](reference/40-runtime-metadata.md) if runtime environment or root variables are unclear.
3. Read [`reference/50-tool-output-contract.md`](reference/50-tool-output-contract.md) when interpreting validation, execution, or chart output.
4. Inspect scripts only when a recipe requires exact arguments not visible in the reference.

## Capability Map

| Capability | Use When | Preferred Path |
| --- | --- | --- |
| 获取表 / 获取字段 | Need candidate tables, similar tables, table comments, field names, or field comments | portal-mcp metadata search, then script fallback |
| 获取血缘 | Need upstream/downstream evidence or lineage diagnosis | portal-mcp lineage, then script fallback |
| 获取 DDL | Need live table structure, field order, partitions, comments, or create statement | portal-mcp DDL, then script fallback |
| 解析数据源 | Need engine/database routing before SQL execution | portal-mcp datasource resolution, then script fallback |
| 验证 SQL | SQL is ready for script fallback execution | validation script |
| 执行只读 SQL | SQL passed validation and real results are required | read-only SQL execution script |
| 图表契约 | SQL result shape is suitable for frontend chart rendering | chart contract script |

## Final Output

Return Chinese conclusions based on structured platform results. Do not invent platform data. If a platform tool fails with a non-retryable error, report the failure attribution and stop instead of guessing another table or field.
