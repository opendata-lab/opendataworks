---
name: opendataworks-business-knowledge
description: "Use this bundled skill only for OpenDataWorks business semantics: terms, ontology, metric definitions, aliases, ambiguity resolution, and business rule exceptions. It does not validate, execute, or format SQL. Pair it with a generic SQL/query skill for data access."
tools: [Read]
---

# OpenDataWorks Business Knowledge Skill

This is the bundled OpenDataWorks 业务知识 Skill. It provides semantic knowledge only: 术语、本体、指标口径、别名、歧义消解、业务规则例外.

It does not provide SQL validation, SQL execution, metadata search, datasource routing, chart generation, environment probing, or operational commands. 不提供 SQL 验证或执行脚本. Use a generic SQL/query skill for those methods.

## Scope

Covered:

- Platform terms and aliases.
- Object ontology and table ownership hints.
- Metric definitions and default time fields.
- Semantic mappings from business names to candidate physical fields.
- Ambiguity and clarification guidance.
- Business rule exceptions, such as when two platform states must not be mixed.

Out of scope:

- SQL generation methodology.
- Tool selection or command templates.
- Runtime environment setup.
- SQL validation or execution scripts.
- Tenant-private business terminology not included in this skill.

## Reading Order

1. Read [`reference/00-knowledge-map.md`](reference/00-knowledge-map.md) to choose the semantic asset.
2. Read [`reference/10-term-index.md`](reference/10-term-index.md) for terms, aliases, ambiguity, and clarification guidance.
3. Read [`reference/20-metric-index.md`](reference/20-metric-index.md) for metric definitions and default time fields.
4. Read [`reference/30-ontology.md`](reference/30-ontology.md) for object ontology and related tables.
5. Read [`reference/40-business-rules.md`](reference/40-business-rules.md) for business rule exceptions.
6. Only inspect `assets/*.json` when the reference summary is insufficient.

## Boundary Rules

- Provide semantic facts and cite the relevant term, metric, or rule.
- When a term is ambiguous, return the smallest clarification needed.
- When a metric maps to candidate tables or fields, state the mapping as a semantic口径, not as an execution plan.
- Do not invent tenant-specific defaults.
- Do not provide a SQL execution path.
- Do not duplicate generic SQL methodology.

## Assets

- [`assets/term_explanations.json`](assets/term_explanations.json) — terms, aliases, ambiguity, ask-back text.
- [`assets/business_concepts.json`](assets/business_concepts.json) — business concepts and default mappings.
- [`assets/semantic_mappings.json`](assets/semantic_mappings.json) — aliases and candidate table-field mappings.
- [`assets/metrics.json`](assets/metrics.json) — metric keys, formulas, and default time fields.
- [`assets/business_rules.json`](assets/business_rules.json) — business rule exceptions.
- [`assets/ontology.json`](assets/ontology.json) — object ontology and related physical tables.

## Final Output

When used directly, answer in Chinese with the semantic conclusion first, then the relevant口径 or clarification question. If the user asks for data results, hand off to the generic SQL/query skill after the semantics are resolved.
