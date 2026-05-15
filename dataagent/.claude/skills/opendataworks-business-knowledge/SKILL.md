---
name: opendataworks-business-knowledge
description: "当请求需要 OpenDataWorks 平台通用业务语义时使用：元数据术语、工作流术语、血缘术语、平台指标定义、别名、歧义消解和业务规则例外。不用于领域专属本体、NL2SQL 方法或平台工具命令。"
tools: [Read]
---

# OpenDataWorks 通用业务知识技能

这是 OpenDataWorks 通用平台业务知识技能，只提供语义知识：术语、平台对象映射、指标口径、别名、歧义消解和业务规则例外。

它不提供领域专属本体、SQL 验证、SQL 执行、元数据搜索、数据源路由、图表生成、环境探测或运维命令。查询方法交给 `dataagent-nl2sql`，真实平台访问交给 `opendataworks-platform-tools`。

## 范围

负责：

- 平台术语和别名。
- 平台对象映射和表归属提示。
- 指标定义和默认时间字段。
- 从业务名称到候选物理字段的语义映射。
- 歧义和澄清建议。
- 业务规则例外，例如两个平台状态不能混用的场景。

不负责：

- SQL 生成方法。
- 工具选择或命令模板。
- 运行时环境设置。
- SQL 验证或执行脚本。
- 领域专属术语、本体或指标口径。
- 本技能未包含的租户私有业务术语。

## 读取顺序

1. 读 [`reference/00-knowledge-map.md`](reference/00-knowledge-map.md)，选择语义资产。
2. 读 [`reference/10-term-index.md`](reference/10-term-index.md)，确认术语、别名、歧义和澄清建议。
3. 读 [`reference/20-metric-index.md`](reference/20-metric-index.md)，确认指标定义和默认时间字段。
4. 读 [`reference/30-ontology.md`](reference/30-ontology.md)，确认 OpenDataWorks 平台对象映射和相关表。
5. 读 [`reference/40-business-rules.md`](reference/40-business-rules.md)，确认业务规则例外。
6. 只有引用摘要不足时，才查看 `assets/*.json`。

## 边界规则

- 提供语义事实，并引用相关术语、指标或规则。
- 术语有歧义时，只返回最小澄清问题。
- 指标映射到候选表字段时，把映射作为语义口径说明，不写成执行计划。
- 问题属于领域专属语义时，交给对应领域语义技能，不要用本技能的通用映射回答。
- 不虚构租户专属默认值。
- 不提供 SQL 执行路径。
- 不复制通用 SQL 方法。

## Assets

- [`assets/term_explanations.json`](assets/term_explanations.json) — 术语、别名、歧义和追问文案。
- [`assets/business_concepts.json`](assets/business_concepts.json) — 业务概念和默认映射。
- [`assets/semantic_mappings.json`](assets/semantic_mappings.json) — 别名和候选表字段映射。
- [`assets/metrics.json`](assets/metrics.json) — 指标 key、公式和默认时间字段。
- [`assets/business_rules.json`](assets/business_rules.json) — 业务规则例外。
- [`assets/ontology.json`](assets/ontology.json) — OpenDataWorks 平台对象映射和相关物理表。

## 最终输出

直接使用本技能时，用中文回答，语义结论先行，再给相关口径或澄清问题。用户要真实数据结果时，语义确认后交给通用 SQL/问数技能。
