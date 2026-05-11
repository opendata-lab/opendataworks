---
name: dataagent-nl2sql
description: "Use this built-in skill for Chinese OpenDataWorks intelligent-query and NL2SQL work: platform metadata, workflow, lineage, datasource routing, generic table discovery, read-only SQL generation/execution, metric explanation, and chart-oriented answers across MySQL and Doris. Use it whenever the user asks for 数据问答、取数、统计、对比、趋势、占比、明细、诊断、血缘排查、指标口径、术语解释 or SQL 示例 on OpenDataWorks or managed tables. Prefer MCP-first via portal-mcp when `mcp__portal__portal_*` tools are visible, and fall back to the built-in CLI/scripts only when MCP is unavailable. Prefer platform terms and generic data-platform rules; do not assume tenant-specific business terminology, business environment defaults, or hidden business knowledge."
compatibility: "Requires DATAAGENT_PYTHON_BIN, DATAAGENT_SKILL_ROOT, and either visible `mcp__portal__portal_*` tools or `${DATAAGENT_SKILL_ROOT}/bin/odw-cli` with ODW_BACKEND_BASE_URL, ODW_AGENT_SERVICE_TOKEN, and host sh+curl."
tools: [Bash, Read]
---

# DataAgent NL2SQL Skill

Convert Chinese natural-language data questions into read-only SQL, execute against MySQL or Doris, and return structured results with optional chart specs. Prefer `portal-mcp` tools when the runtime exposes them; otherwise fall back to the built-in Python scripts.

## Scope

**Covered scenarios:** 数据问答、取数、统计、对比、趋势、占比、明细、诊断、血缘排查、指标口径、术语解释、SQL 示例

**Out of scope:** general chat, write/update/delete SQL, cross-source federated joins, and questions that depend on tenant-specific business terms or default filters not present in this built-in skill or returned by real metadata tools.

## Builtin Boundary

- This built-in skill only carries OpenDataWorks platform terms, platform tables, metadata/lineage/datasource flows, and generic data-platform rules such as `df/di`, `ds`, schema prefixes, and single-source routing.
- Do not assume tenant-specific business objects, environment naming conventions, hidden field mappings, or business defaults.
- If the answer depends on tenant-specific business knowledge that is not present in the current skill or metadata output, ask a minimal clarifying question instead of guessing.

## Iron Laws

1. **ALWAYS** read [`reference/00-skill-map.md`](reference/00-skill-map.md) first, then progressively load references as needed. Never bulk-read all assets upfront.
2. **NEVER** execute `run_sql.py` without first confirming the target database, engine, metrics, dimensions, and required time range.
3. **ALWAYS** write table names as `<schema>.<table>` in SQL. Never omit the schema, and never use engine names (`mysql`/`doris`) as schema.
4. **NEVER** execute INSERT, UPDATE, DELETE, DROP, TRUNCATE, ALTER, or CREATE statements.
5. **ALWAYS** include a LIMIT clause on SELECT queries (default 100).
6. **ALWAYS** ask the user to clarify before guessing when terminology, target table, time range, comparison dimension, or tenant-specific business semantics are ambiguous.
7. **NEVER** run `pip install`, `uv add`, `which python`, `python --version`, or any environment probing commands.
8. **ALWAYS** use `mcp__portal__portal_*` first when these tools are visible. Only when MCP tools are unavailable may you fall back to Python scripts and `${DATAAGENT_SKILL_ROOT}/bin/odw-cli`.
9. **ALWAYS** respond in Chinese: conclusion first, then evidence. Never echo back raw tool output verbatim.
10. **ALWAYS** stop after the first correct `sql_execution` or `chart_spec`. Do not re-execute equivalent SQL or continue reading assets once the answer is grounded.
11. **ALWAYS** prefer global metadata search first when the user did not explicitly provide a database. Only add `--database` after the user or metadata has already narrowed the scope.
12. **ALWAYS** do a small synonym or related-term expansion when the first metadata search is too sparse, but keep the expansion limited and grounded in the user’s wording.
13. **ALWAYS** treat upstream/downstream/lineage questions as lineage-tool-first. For these questions, `run_sql.py` now hard-blocks first-pass `data_lineage` SQL unless `DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1` is explicitly set for a clearly scoped supplemental query.

## 数据问数质量门禁

- 执行 SQL 前必须确认目标、库/引擎/schema、表、使用字段、指标公式、过滤条件、时间范围、维度/粒度。
- 涉及 JOIN、去重、明细定位、血缘映射时，必须确认主键、唯一键或关联键。
- 主键、唯一键或关联键不是所有简单聚合的硬门槛；简单 COUNT、趋势、占比或明细预览可在表、字段、口径和时间范围已确认后执行。
- 如果 metadata、DDL、reference 或用户输入不能确认上述门禁，先做最小追问，不要用猜测补齐字段、关联关系或业务口径。

## Anti-Patterns

| Anti-Pattern | Why It Fails | Correct Approach |
|---|---|---|
| Bulk-reading `assets/*.json` at the start | Wastes tokens and ignores progressive disclosure | Follow the fixed reading order; only drill into assets when references are insufficient |
| Using `mysql` or `doris` as SQL schema | Engine type is not a database name | Use metadata-returned `db_name` as the schema prefix |
| Running `run_sql.py` without database confirmation | Generates blind-guess SQL | Route through `inspect_metadata.py` → `resolve_datasource.py` first for managed tables |
| Querying Doris `di` table without time range | Full-table scan on incremental data | Always require explicit `ds BETWEEN ... AND ...` for `di` tables |
| Querying Doris `df` table across full history | Unnecessary data scan on snapshot tables | Default to latest `ds` partition unless the user explicitly requests historical range |
| Inventing tenant-specific business defaults | Built-in skill does not own tenant business knowledge | Limit yourself to current skill docs, metadata, and explicit user input; otherwise ask |
| Retrying with different interpreters on script error | Probes the environment instead of fixing the input | Diagnose from the actual error message; adjust parameters or ask the user |
| Generating a chart when data is unsuitable | Forces visual output on 1-row or text results | Only produce `chart_spec` when the data structure genuinely fits a chart |
| Re-executing equivalent SQL after getting results | Wastes resources and confuses the answer | Stop after the first correct result |

## Fixed Reading Order

Process any question in this order; stop as soon as sufficient context is gathered:

1. Read [`reference/00-skill-map.md`](reference/00-skill-map.md) to classify the question type and execution path
2. Read [`reference/10-query-playbooks.md`](reference/10-query-playbooks.md) to match the concrete playbook
3. If datasource routing is unclear, read [`reference/11-datasource-routing.md`](reference/11-datasource-routing.md)
4. If platform terminology or generic semantics are unclear, read [`reference/20-term-index.md`](reference/20-term-index.md), [`reference/21-metric-index.md`](reference/21-metric-index.md), [`reference/22-sql-example-index.md`](reference/22-sql-example-index.md)
5. If script usage details are unclear, read [`reference/30-tool-recipes.md`](reference/30-tool-recipes.md), [`reference/40-runtime-metadata.md`](reference/40-runtime-metadata.md), [`reference/50-tool-output-contract.md`](reference/50-tool-output-contract.md)
6. Only when all references above are insufficient, drill into `assets/*` or execute `scripts/*`

## Fixed Execution Order

### Step A: Classify the Question

Assign one primary type: 统计 | 对比 | 趋势 | 占比 | 明细 | 诊断 | 术语解释 | SQL 示例

If a question spans multiple types, identify the primary goal first, then supplement.

### Step B: Determine Whether to Ask for Clarification

Ask before guessing when any of these apply:

- Terminology ambiguity (`数据层级`, `发布状态`, `任务依赖`, `Doris 只读账号`)
- Target database or table is not unique
- Time range or granularity is unspecified
- User says "对比" without specifying the comparison dimension
- User says "趋势" without specifying the metric
- The question depends on tenant-specific business terms, default filters, or objects not defined in this built-in skill
- Doris `df` table but unclear whether to query latest snapshot or historical range
- Doris `di` table but no time range provided

### Step C: Execute MCP Tools or Fallback Scripts

Follow this priority order:

1. **Terminology unclear** → consult `reference/20-*`, `21-*`, `22-*`
2. **If `mcp__portal__portal_*` tools are visible**:
   - table search → `mcp__portal__portal_search_tables`
   - lineage → `mcp__portal__portal_get_lineage`
   - datasource summary → `mcp__portal__portal_resolve_datasource`
   - metadata export → `mcp__portal__portal_export_metadata`
   - table DDL → `mcp__portal__portal_get_table_ddl`
   - read-only SQL → `mcp__portal__portal_query_readonly`
3. **If MCP tools are unavailable**:
   - upstream/downstream lineage → `get_lineage.py`
   - need live table DDL → `get_table_ddl.py`
   - platform core table with known fields → go straight to the `run_sql.py` read-only query path
   - managed table, fields unclear → `inspect_metadata.py` first
   - engine unclear → `resolve_datasource.py`
   - SQL confirmed → `run_sql.py`
4. **Result suits a chart** → `build_chart_spec.py` with explicit `--chart-type bar|line|pie`
5. **User explicitly wants a standalone table** → `build_chart_spec.py --chart-type table`
6. **Need summary** → `format_answer.py`

Do not execute `run_sql.py` without confirmed database, metrics, and dimensions.

### Step D: Execution Rules

All scripts execute via: `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/<name>.py" ...`

Allowed scripts only: `inspect_metadata.py`, `resolve_datasource.py`, `get_lineage.py`, `get_table_ddl.py`, `run_sql.py`, `build_chart_spec.py`, `format_answer.py`, `query_opendataworks_metadata.py`, `build_reference_digest.py`

Preferred MCP tools when available:

- `mcp__portal__portal_search_tables`
- `mcp__portal__portal_get_lineage`
- `mcp__portal__portal_resolve_datasource`
- `mcp__portal__portal_export_metadata`
- `mcp__portal__portal_get_table_ddl`
- `mcp__portal__portal_query_readonly`

Command templates:

```bash
# Metadata inspection
"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/inspect_metadata.py" --keyword <keyword> [--table <table>] [--database <db>]

# Datasource resolution
"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/resolve_datasource.py" --database <db_name> [--engine mysql|doris]

# Lineage snapshot
"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/get_lineage.py" --table <table_name> [--db-name <db_name>] [--depth <n>]

# Live table DDL
"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/get_table_ddl.py" --database <db_name> --table <table_name>

# SQL execution
"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/run_sql.py" --database <db_name> --engine <mysql|doris> --sql "<SQL>"

# Lineage-only supplemental SQL after snapshot is still insufficient
DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1 "$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/run_sql.py" --database opendataworks --engine mysql --sql "<supplemental lineage SQL>"

# Chart generation
"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/build_chart_spec.py" --chart-type <bar|line|pie|table> --input '<sql_execution_json>'

# Answer formatting
"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/format_answer.py" --input '<sql_execution_json>'
```

Prohibitions:

- Never fabricate script paths or names (no `/app/scripts/...`, no bare `scripts/<name>.py`)
- Never call `odw-cli` directly unless you are already inside the documented Python-script fallback path; it is not the primary agent interface
- Never run environment probing commands (`pip install`, `which python`, etc.)
- If a script returns an error, diagnose from the error output; do not blindly retry with different interpreters
- Read the corresponding `reference/*` before executing a script; do not interleave reading and executing
- For 统计/对比/趋势/占比/明细/诊断 questions, the first real action must be a concrete script call or a clarifying question, not reading `assets/*.json`
- Once MCP or fallback-script parameters are clear, always execute the real tool; do not skip execution and give SQL conclusions based solely on references
- Do not invent or expose datasource credentials; skill/runtime only receives datasource summary fields and all metadata / read-only SQL go through `portal-mcp` or `odw-cli -> backend /api/v1/ai/*`
- For upstream/downstream lineage questions, prefer `portal_get_lineage` or `get_lineage.py` before writing custom SQL; only use `run_sql.py` when the lineage snapshot still lacks required fields
- For upstream/downstream lineage questions, do not retry guessed `data_lineage` SQL after the guard fires; switch to `portal_get_lineage` or `get_lineage.py`, and only use `DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1` for a clearly scoped supplemental query

## Multi-Datasource Constraints

- Platform core tables (`data_table`, `data_field`, `data_lineage`, `data_task`, `data_workflow`, `workflow_*`, `doris_*`) always use the backend read-only query path with `database=opendataworks` and `engine=mysql`
- Managed tables may be on MySQL or Doris; always do single-source routing first
- If candidate tables span different engines or databases within the same answer, ask the user to narrow scope
- If the answer depends on tenant-specific business defaults not present in this skill, ask instead of inferring

## Chart Constraints

| Chart Type | When to Use | Explicit Flag |
|---|---|---|
| Table | Default fallback; always safe | `--chart-type table` (only when the user explicitly requests a standalone table) |
| Bar | Category comparison, TopN, ranking | `--chart-type bar` |
| Line | Time-series trends | `--chart-type line` |
| Pie | Proportional analysis with 2–8 categories | `--chart-type pie` |

Do not generate `chart_spec` when data is unsuitable for visualization. Retain `sql_execution` only. Always pass explicit `--chart-type`; never let the script auto-guess.

## Assets & Scripts Reference

### Reference Documents

- [`reference/00-skill-map.md`](reference/00-skill-map.md) — question type to execution path mapping
- [`reference/10-query-playbooks.md`](reference/10-query-playbooks.md) — concrete playbooks per question type
- [`reference/11-datasource-routing.md`](reference/11-datasource-routing.md) — MySQL vs. Doris routing rules
- [`reference/20-term-index.md`](reference/20-term-index.md) — platform glossary and generic data-platform rules
- [`reference/21-metric-index.md`](reference/21-metric-index.md) — metric formulas and constraints
- [`reference/22-sql-example-index.md`](reference/22-sql-example-index.md) — SQL templates by scenario
- [`reference/30-tool-recipes.md`](reference/30-tool-recipes.md) — detailed script usage recipes
- [`reference/40-runtime-metadata.md`](reference/40-runtime-metadata.md) — core table schema and runtime details
- [`reference/50-tool-output-contract.md`](reference/50-tool-output-contract.md) — output format contracts

### Scripts

- [`scripts/inspect_metadata.py`](scripts/inspect_metadata.py) — locate managed tables
- [`scripts/resolve_datasource.py`](scripts/resolve_datasource.py) — resolve engine and datasource
- [`scripts/get_lineage.py`](scripts/get_lineage.py) — fetch lineage snapshot through the backend metadata path
- [`scripts/get_table_ddl.py`](scripts/get_table_ddl.py) — fetch live table DDL through the backend metadata path
- [`scripts/run_sql.py`](scripts/run_sql.py) — execute read-only SQL through the backend query path
- [`scripts/build_chart_spec.py`](scripts/build_chart_spec.py) — generate chart spec from SQL results
- [`scripts/format_answer.py`](scripts/format_answer.py) — summarize results for the final answer
- [`scripts/query_opendataworks_metadata.py`](scripts/query_opendataworks_metadata.py) — export platform metadata
- [`scripts/build_reference_digest.py`](scripts/build_reference_digest.py) — regenerate reference index files from assets

### Structured Assets

- `assets/term_explanations.json`, `assets/business_concepts.json`, `assets/semantic_mappings.json`
- `assets/metrics.json`, `assets/business_rules.json`, `assets/constraints.json`
- `assets/sql_examples.json`, `assets/few_shots.json`
- `assets/chart-template/*.json`

## Final Answer Requirements

- Lead with the conclusion, then provide supporting evidence
- If a query was executed, cite the structured result; do not repeat raw tool output
- For pure terminology or SQL example questions, SQL execution is not required
- If information is insufficient, state what is missing and ask a minimal clarifying question
- If the blocker is tenant-specific business knowledge missing from the built-in skill, say so explicitly instead of guessing
- Never expose internal steps (reading docs, locating scripts, preparing execution) in the final answer
