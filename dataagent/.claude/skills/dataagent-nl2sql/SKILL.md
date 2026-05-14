---
name: dataagent-nl2sql
description: "Use this built-in skill for DataAgent generic Chinese data-question and read-only SQL work: intent classification, semantic handoff, metadata lookup, datasource routing, SQL generation, SQL validation, read-only execution, result summarization, and chart-contract output. It owns method and tools, not business terminology or metric definitions."
compatibility: "Requires DATAAGENT_PYTHON_BIN, DATAAGENT_SKILL_ROOT, and either visible `mcp__portal__portal_*` tools or `${DATAAGENT_SKILL_ROOT}/bin/odw-cli` with backend service access."
tools: [Bash, Read]
---

# DataAgent Generic SQL Skill

This is the 通用问数 SQL 方法 skill. It owns methodology, steps, checks, tool usage, failure handling, and output contracts. It does not define business terms, ontology, metric口径, aliases, or business-rule exceptions.

The fixed query pipeline is: 语义确认 → SQL 生成 → SQL 验证 → run_sql.py 执行 → 结果收口. validate_sql.py 是唯一推荐的 SQL 验证入口 for script fallback; run_sql.py 是唯一推荐的 SQL 执行入口 for script fallback and calls the backend read-only query API without connecting to databases directly.

## Scope

Covered:

- Intent classification for data questions.
- Minimal clarification when necessary inputs are missing.
- Metadata, lineage, datasource, DDL, validation, read-only execution, and chart-contract tool flow.
- SQL 前检查, SQL generation, SQL validation, SQL execution, result summarization.
- Failure handling for empty results, permission errors, schema mismatch, datasource mismatch, timeout, and unsafe SQL.

Out of scope:

- Business terminology.
- Object ontology.
- Metric definitions.
- Domain aliases and ambiguity rules.
- Business default filters or business-rule exceptions.
- Tenant-private knowledge.

Use enabled business knowledge skills for semantics. Once semantics are resolved, use this skill for the generic SQL method and tools.

## Iron Laws

1. **ALWAYS** read [`reference/00-skill-map.md`](reference/00-skill-map.md) first, then progressively load references as needed. Never bulk-read all assets upfront.
2. **NEVER** execute `run_sql.py` without first confirming target database, engine, tables, fields, filters, metric formula, dimensions, and required time range.
3. **ALWAYS** write table names as `<schema>.<table>` in SQL. Never omit the schema, and never use an engine name as schema.
4. **NEVER** execute INSERT, UPDATE, DELETE, DROP, TRUNCATE, ALTER, or CREATE statements.
5. **ALWAYS** include a LIMIT clause on SELECT queries unless the validated statement type cannot support it.
6. **ALWAYS** ask a minimal clarification before guessing when terminology, target table, time range, comparison dimension, or business semantics are ambiguous.
7. **NEVER** run `pip install`, `uv add`, `which python`, `python --version`, or any environment probing commands.
8. **ALWAYS** use `mcp__portal__portal_*` first when these tools are visible. Only when MCP tools are unavailable may you fall back to Python scripts and `${DATAAGENT_SKILL_ROOT}/bin/odw-cli`.
9. **ALWAYS** respond in Chinese: conclusion first, then evidence. Never echo raw tool output verbatim.
10. **ALWAYS** stop after the first correct `sql_execution` or `chart_spec`. Do not re-execute equivalent SQL or keep reading once the answer is grounded.
11. **ALWAYS** validate confirmed SQL with `validate_sql.py` before script-fallback execution; if a business knowledge skill supplied ontology, pass that ontology path into the generic validator.
12. **ALWAYS** execute confirmed SQL through `run_sql.py`; 必须拿到真实只读结果后回答, and 不得只输出 SQL 或要求用户自行执行.
13. **NEVER** add another SQL execution script. `run_sql.py` remains the single script fallback entrypoint for SQL execution.
14. **NEVER** ask a business knowledge skill to own SQL validation or execution. Business knowledge skills only provide semantics.

## 数据问数质量门禁

- 执行 SQL 前必须确认目标、库/引擎/schema、表、使用字段、指标公式、过滤条件、时间范围、维度/粒度。
- 涉及 JOIN、去重、明细定位、血缘映射时，必须确认主键、唯一键或关联键。
- 主键、唯一键或关联键不是所有简单聚合的硬门槛；简单 COUNT、趋势、占比或明细预览可在表、字段、口径和时间范围已确认后执行。
- 如果 business knowledge skill、metadata、DDL 或用户输入不能确认上述门禁，先做最小追问，不要用猜测补齐字段、关联关系或业务口径。

## Anti-Patterns

| Anti-Pattern | Why It Fails | Correct Approach |
|---|---|---|
| Encoding metric口径 in this skill | It competes with business knowledge skills | Ask the business knowledge skill for semantics, then return here for SQL method |
| Bulk-reading all references or assets | Wastes context and hides the minimal path | Follow the fixed reading order |
| Using engine names as SQL schema | Engine type is not a database name | Use metadata-returned schema/database as prefix |
| Running SQL before database confirmation | Creates blind-guess queries | Resolve metadata and datasource first |
| Retrying equivalent SQL after a usable result | Wastes resources and confuses the answer | Stop after the first grounded result |
| Treating tool errors as permission to guess | Produces unverifiable answers | Report the verified failure and the minimal next step |
| Generating a chart when data is unsuitable | Forces visuals onto weak result shapes | Keep `sql_execution` only unless the dataset fits a chart |

## 失败处理

- Empty result: explain the confirmed口径 and that the query returned no rows.
- Permission error: report the permission boundary and do not retry with guessed tables.
- Schema or field mismatch: report the mismatched object and ask for the minimal missing metadata.
- Datasource mismatch: resolve or ask for one data source; do not produce cross-source SQL.
- Timeout: state the timeout and smallest next step, such as narrowing filters or moving to background execution.

## Fixed Reading Order

Process any question in this order; stop as soon as sufficient context is gathered:

1. Read [`reference/00-skill-map.md`](reference/00-skill-map.md) to classify the method path.
2. If semantics are unclear, invoke or read the relevant business knowledge skill; do not solve business semantics in this skill.
3. Read [`reference/10-query-playbooks.md`](reference/10-query-playbooks.md) to choose the generic query playbook.
4. If datasource routing is unclear, read [`reference/11-datasource-routing.md`](reference/11-datasource-routing.md).
5. If script usage details are unclear, read [`reference/30-tool-recipes.md`](reference/30-tool-recipes.md), [`reference/40-runtime-metadata.md`](reference/40-runtime-metadata.md), [`reference/50-tool-output-contract.md`](reference/50-tool-output-contract.md).
6. Execute the minimal real tool path.

## Final Answer Requirements

- Lead with the conclusion, then provide supporting evidence.
- If a query was executed, cite the structured result; do not repeat raw tool output.
- For pure terminology or metric口径 questions, hand off to the relevant business knowledge skill; SQL execution is not required.
- If information is insufficient, state what is missing and ask a minimal clarifying question.
- For `sql_execution`, use `result_state`, `error_code`, and `failure_attribution` to decide whether to answer, report empty data, or stop on permission/schema/timeout failures.
- Never expose internal steps such as reading docs, locating scripts, or preparing execution.
