---
name: opendataworks-business-knowledge
description: "当请求需要 OpenDataWorks 平台通用业务语义时使用：元数据术语、工作流术语、血缘术语、平台指标定义、别名、歧义消解和业务规则例外。不用于领域专属本体、NL2SQL 方法或平台工具命令。"
tools: [Read]
---

# OpenDataWorks 通用业务知识技能

OpenDataWorks Business Knowledge Skill。业务知识 Skill。

这是 OpenDataWorks 通用平台业务知识技能，只提供语义知识：术语、平台对象映射、指标口径、别名、歧义消解和业务规则例外。

它不提供领域专属本体、SQL 验证、SQL 执行、元数据搜索、数据源路由、图表与报告生成、环境探测或运维命令。它不提供 SQL 验证或执行脚本。通用问数方法和 SQL 就绪规则由 DataAgent system prompt 约束，真实平台访问交给 `opendataworks-platform-tools`。

## 范围

负责：

- 平台术语和别名。
- 平台对象映射和表归属提示。
- 平台管理表排查语义路径，包括字段、数据快照、关联任务、任务 SQL、执行日志和上下游表。
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

## 按需读取

不要为每个问题顺序读完全部参考文件。重复读取会增加轮次，也容易让已经明确的口径被后续探索稀释。先按问题类型选择最小资料集，拿到足够语义后立即停止读取：

- 不确定问题属于哪类时，先读 [`reference/00-knowledge-map.md`](reference/00-knowledge-map.md)。类型已经明确时跳过它。
- 术语解释或概念区别：读 [`reference/10-term-index.md`](reference/10-term-index.md)；涉及不能混用的状态或默认过滤时，再读 [`reference/40-business-rules.md`](reference/40-business-rules.md)。
- 指标问数：读 [`reference/20-metric-index.md`](reference/20-metric-index.md) 和相关的 [`reference/40-business-rules.md`](reference/40-business-rules.md)；只有需要对象关系或关联字段时才读 [`reference/30-ontology.md`](reference/30-ontology.md)。
- 血缘、任务或平台对象关系：读 [`reference/30-ontology.md`](reference/30-ontology.md) 和相关的 [`reference/10-term-index.md`](reference/10-term-index.md) / [`reference/40-business-rules.md`](reference/40-business-rules.md)。
- 引用摘要不能回答具体字段或关系时，才查看相关的单个 `assets/*.json`；不要批量读取所有资产。

同一条语义事实已经由参考文件确认后，不再为“验证”它而读取其他文件或查询实时样例。

## 语义收口规则

- 用户只问定义、区别或口径时，直接回答已有语义事实。除非用户明确要求当前取值、数量或样例，否则不要查询实时数据来扩写定义。
- 将默认过滤和禁止混用规则作为后续问数不可丢失的约束。例如数据表数量和数据层级分布默认排除 `deleted=1`；历史发布结果与当前发布状态不能互换。
- 表血缘必须先唯一定位目标表。用户只给 `table_name`、未给 `db_name` 时，先只追问 `db_name`，不要先做精确查询、模糊搜索或候选表推荐。
- 语义和必要槽位已经明确后，立即交回 DataAgent 通用问数流程；不要继续查不影响答案的状态分布、样例记录或旁证。
- 真实查询返回空结果时，按已确认口径说明“未找到”，不要擅自换表、放宽名称或改过滤条件继续试探。
- 层级分布使用 `data_table.layer`，默认排除 `deleted=1`，并明确说明层级不是 `business_domain` / `data_domain`。

## 边界规则

- 提供语义事实，并引用相关术语、指标或规则。
- 术语有歧义时，只返回最小澄清问题。
- 指标映射到候选表字段时，把映射作为语义口径说明，不写成执行计划。
- 问题属于领域专属语义时，交给对应领域语义技能，不要用本技能的通用映射回答。
- 不虚构租户专属默认值。
- 不提供 SQL 执行路径。
- 不复制通用 SQL 方法。
- 不用无关的实时查询补充已有定义，也不在缺少唯一定位槽位时提前查询。

## Assets

- [`assets/term_explanations.json`](assets/term_explanations.json) — 术语、别名、歧义和追问文案。
- [`assets/business_concepts.json`](assets/business_concepts.json) — 业务概念和默认映射。
- [`assets/semantic_mappings.json`](assets/semantic_mappings.json) — 别名和候选表字段映射。
- [`assets/metrics.json`](assets/metrics.json) — 指标 key、公式和默认时间字段。
- [`assets/business_rules.json`](assets/business_rules.json) — 业务规则例外。
- [`assets/ontology.json`](assets/ontology.json) — OpenDataWorks 平台对象、字段、关系和平台管理表排查语义路径。

## 最终输出

直接使用本技能时，用中文回答，语义结论先行，再给相关口径或澄清问题。用户要真实数据结果时，语义确认后按 DataAgent system prompt 的问数流程继续，并通过平台工具获取真实证据。
