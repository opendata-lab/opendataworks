---
name: dataagent-nl2sql
description: "Use this built-in skill for DataAgent generic Chinese data-question methodology: intent classification, semantic handoff, table and field discovery strategy, SQL generation checks, safe query planning, and result summarization. It owns method, not business semantics or OpenDataWorks platform tools."
compatibility: "Pair this method skill with an enabled business knowledge Skill for semantics and the OpenDataWorks Platform Tools Skill for real metadata, lineage, validation, execution, and chart-capability access."
tools: [Read]
---

# DataAgent Generic SQL Skill

This is the 通用问数 SQL 方法 skill. It owns methodology, steps, checks, failure handling, and answer收口. It does not define business terms, ontology, metric口径, aliases, business-rule exceptions, platform script names, CLI paths, or tool output schemas.

Use this skill after the user asks a data question. If business semantics are unclear, first use the enabled 业务知识 Skill. If real platform evidence is needed, hand off to the OpenDataWorks Platform Tools Skill. This skill decides what must be known before SQL is generated; the platform tools skill supplies the actual platform capabilities.

## Scope

Covered:

- Intent classification for data questions.
- Minimal clarification when necessary inputs are missing.
- 通用问数 SQL 方法, including SQL 前检查 and result收口.
- Strategy for finding candidate tables and fields from data sources, metadata keywords, table comments, field comments, DDL, lineage clues, and similar tables.
- Rules for when to trust a confirmed business ontology versus when to run generic metadata discovery.
- Safety rules for single-source SQL planning, schema qualification, LIMIT protection, and read-only intent.
- Failure handling for empty results, permission errors, schema mismatch, datasource mismatch, timeout, and unsafe SQL.

Out of scope:

- Business terminology.
- Object ontology.
- Metric definitions.
- Domain aliases and ambiguity rules.
- Business default filters or business-rule exceptions.
- OpenDataWorks platform script paths, CLI usage, MCP tool names, SQL validation scripts, SQL execution scripts, or chart output contracts.

## Core Boundary

- 业务知识 Skill answers: “这个业务词是什么意思、指标怎么算、默认口径是什么、哪些对象和字段候选最符合业务定义。”
- This generic SQL skill answers: “为了把已确认语义变成 SQL，还缺哪些槽位；应如何找候选表、候选字段、关联键、过滤条件和时间字段。”
- OpenDataWorks Platform Tools Skill answers: “如何真实获取表、获取字段、获取血缘、获取 DDL、解析数据源、验证 SQL、执行只读 SQL、生成图表契约。”

## Fixed Method Pipeline

1. 语义确认：优先使用业务知识 Skill 或用户明确输入确认指标、对象、口径和歧义。
2. 候选定位：当口径不能直接确定物理表字段时，用平台工具收集数据源、表、字段、DDL、血缘和相似表证据。
3. SQL 前检查：确认 database/schema、engine、table、field、filter、time range、dimension、grain、metric formula、join key。
4. SQL 生成：只在必要槽位齐备后生成单源、只读、带 schema 前缀和 LIMIT 的查询。
5. 平台执行：需要真实验证或结果时，交给 OpenDataWorks Platform Tools Skill。
6. 结果收口：基于真实结果、空结果或失败归因回答；不要在失败后换表试探。

## 数据问数质量门禁

- 执行 SQL 前必须确认目标、库/引擎/schema、表、使用字段、指标公式、过滤条件、时间范围、维度/粒度。
- 涉及 JOIN、去重、明细定位、血缘映射时，必须确认主键、唯一键或关联键。
- 主键、唯一键或关联键不是所有简单聚合的硬门槛；简单 COUNT、趋势、占比或明细预览可在表、字段、口径和时间范围已确认后执行。
- 如果业务知识 Skill、metadata、DDL 或用户输入不能确认上述门禁，先做最小追问，不要用猜测补齐字段、关联关系或业务口径。

## 相似表与字段发现原则

- 先从用户词、业务知识 Skill 给出的对象名、指标名、别名和过滤词提取关键词。
- 用平台工具查找候选表时，同时比较表名、表注释、字段名、字段注释、分层、数据源和血缘上下游。
- 候选表只能作为证据，不等于已确认口径；多个候选表都合理时先追问或说明候选差异。
- 字段选择必须能解释它在指标、维度、时间范围或过滤条件中的作用。
- 时间字段、状态字段、关联键不能只按名称相似猜测；需要业务知识、DDL、metadata 或用户确认。

## Iron Laws

1. **ALWAYS** read [`reference/00-skill-map.md`](reference/00-skill-map.md) first, then progressively load references as needed. Never bulk-read all assets upfront.
2. **ALWAYS** ask a minimal clarification before guessing when terminology, target table, time range, comparison dimension, or business semantics are ambiguous.
3. **ALWAYS** write planned table names as `<schema>.<table>` once schema is confirmed. Never use an engine name as schema.
4. **NEVER** plan INSERT, UPDATE, DELETE, DROP, TRUNCATE, ALTER, CREATE, or other write operations.
5. **ALWAYS** include a LIMIT clause on SELECT queries unless the validated statement type cannot support it.
6. **ALWAYS** use enabled business knowledge skills for semantics. Business knowledge skills only provide semantics.
7. **ALWAYS** use the OpenDataWorks Platform Tools Skill for real platform access. This skill must not duplicate platform tool instructions.
8. **ALWAYS** respond in Chinese: conclusion first, then evidence.
9. **ALWAYS** stop after the first grounded result or sufficient failure attribution. Do not re-execute equivalent SQL or keep reading once the answer is grounded.

## Anti-Patterns

| Anti-Pattern | Why It Fails | Correct Approach |
|---|---|---|
| Encoding metric口径 in this skill | It competes with business knowledge skills | Ask the business knowledge Skill for semantics, then return here for query method |
| Treating similar table names as confirmed口径 | Name similarity is not business evidence | Use table comments, fields, DDL, lineage, and user/semantic confirmation |
| Storing platform script commands here | It couples generic method to OpenDataWorks runtime | Hand off platform access to OpenDataWorks Platform Tools Skill |
| Running before database confirmation | Creates blind-guess queries | Resolve metadata and datasource first through platform tools |
| Retrying equivalent SQL after a usable result | Wastes resources and confuses the answer | Stop after the first grounded result |
| Treating tool errors as permission to guess | Produces unverifiable answers | Report the verified failure and the minimal next step |

## 失败处理

- Empty result: explain the confirmed口径 and that the query returned no rows.
- Permission error: report the permission boundary and do not retry with guessed tables.
- Schema or field mismatch: report the mismatched object and ask for the minimal missing metadata.
- Datasource mismatch: resolve or ask for one data source; do not produce cross-source SQL.
- Timeout: state the timeout and smallest next step, such as narrowing filters or moving to background execution.

## Fixed Reading Order

Process any question in this order; stop as soon as sufficient context is gathered:

1. Read [`reference/00-skill-map.md`](reference/00-skill-map.md) to classify the method path.
2. If semantics are unclear, invoke or read the relevant business knowledge Skill; do not solve business semantics in this skill.
3. Read [`reference/10-query-playbooks.md`](reference/10-query-playbooks.md) to choose the generic query playbook.
4. If datasource routing is unclear, read [`reference/11-datasource-routing.md`](reference/11-datasource-routing.md).
5. If real metadata, DDL, lineage, validation, execution, or chart capability is needed, hand off to the OpenDataWorks Platform Tools Skill.

## Final Answer Requirements

- Lead with the conclusion, then provide supporting evidence.
- If a query was executed by platform tools, cite the structured result; do not repeat raw tool output.
- For pure terminology or metric口径 questions, hand off to the relevant business knowledge Skill; SQL execution is not required.
- If information is insufficient, state what is missing and ask a minimal clarifying question.
- Never expose internal steps such as reading docs or preparing execution.
