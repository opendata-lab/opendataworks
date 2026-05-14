# DataAgent Three-Layer Prompt / Skill Boundary Design

**Date:** 2026-05-15
**Goal:** 将 DataAgent 问数上下文拆成系统提示词、通用问数 SQL skill、业务知识 skill 三层，并消除职责重叠。
**Tech Stack:** DataAgent 后端 FastAPI / Claude Agent SDK；内置 Skills `dataagent-nl2sql` 与 `opendataworks-business-knowledge`；pytest。

## Scope

- 覆盖 `dataagent/dataagent-backend/core/agent_runtime.py` 中真实传给 Claude Agent SDK 的 system prompt。
- 覆盖 `dataagent/.claude/skills/dataagent-nl2sql` 的通用 SQL 方法、工具 recipes、验证与执行契约。
- 新增 `dataagent/.claude/skills/opendataworks-business-knowledge`，承接 OpenDataWorks 平台术语、本体、指标口径、别名、歧义消解和业务规则例外。
- 覆盖默认内置 skill 运行时启用策略、相关单测和 DataAgent README。

不覆盖前端交互、数据库 schema、provider 配置、portal-mcp 协议或真实业务租户私有知识导入。

## Current State

当前真实执行链路由 `core/task_executor.py` 构造 `ClaudeAgentOptions(system_prompt=...)`，system prompt 来自 `core/agent_runtime.py::_build_system_prompt()`。

当前 system prompt 已经写入大量问数细节，包括 lineage、DDL、脚本 fallback、分层管线、SQL 验证、SQL 执行、失败处理和评测场景规则。这使 system prompt 偏低抽象，维护时容易把某个 skill 的局部规则提升成全局规则。

当前内置 `dataagent-nl2sql` 同时承担两类职责：

- 通用问数 SQL 方法：分类、澄清、metadata 检索、datasource 路由、SQL 验证、只读执行、图表输出和失败收口。
- OpenDataWorks 业务知识：数据层级、工作流发布记录、血缘关系、平台核心表、本体、指标公式、SQL example 和 few-shot。

后端已经支持多 skill 发现和启停，但内置不可卸载 skill 列表目前只有 `dataagent-nl2sql`。没有显式 `skill_runtime` 时，运行时只启用 primary skill。

## Problem

三层职责混叠导致几个问题：

- system prompt 过长且过低抽象，包含大量可由 skill 管理的工具和业务规则。
- 通用 SQL skill 混入了指标口径、平台术语和 SQL example，容易与业务 knowledge skill 抢职责。
- 业务术语、本体和指标口径没有独立 skill 边界，后续导入租户业务知识时难以判断该放在哪一层。
- 新增业务 skill 后，如果默认运行时仍只启用 primary skill，三层结构在新部署中不会自然生效。

## Design

### 1. System Prompt: identity, boundary, routing, priority

System prompt 只保留“合适高度”的全局行为：

- 身份：DataAgent 智能问数助手。
- 边界：只读、不可臆造、不要暴露隐藏推理、中文结论优先。
- 优先级：用户显式约束 > 已启用业务知识 skill 的术语和口径 > 真实 metadata / DDL / 工具结果 > 通用 SQL 方法。
- 路由：业务语义交给业务知识 skill，SQL 方法和工具链交给通用问数 skill。
- 工作顺序：路由问题 -> 补齐业务语义 -> 获取必要 metadata -> 生成/验证/执行只读查询 -> 基于结果回答或说明缺口。

System prompt 采用模板化结构，使用 `# Role`、`# Primary Goal`、`# Boundaries`、`# Instruction Priority`、`# Workflow`、`# Routing Rules`、`# Output Requirements` 分节。每节使用简单直接语言描述身份、边界、优先级和工作顺序，不再硬编码具体脚本名、命令模板、平台表、lineage fallback 细节或图表生成细节。

### 2. Generic SQL skill: method only

`dataagent-nl2sql` 保留为 primary skill，因为它仍拥有 script fallback 和 `DATAAGENT_SKILL_ROOT` 兼容契约。

它只承载：

- 问题分类、最小追问、上下文收集、SQL 生成前检查、验证、执行、结果收口。
- MCP-first 与 script fallback 的工具 recipes。
- SQL 安全、schema 前缀、只读、LIMIT、单源路由、空结果/权限/超时处理。
- 图表输出契约。

它不再承载：

- 平台术语解释。
- 平台本体或平台核心表字段清单。
- 指标公式、默认时间字段、业务别名。
- 业务 SQL example、few-shot、业务规则例外。

当 SQL 方法需要语义或口径时，它只引用“已启用业务知识 skill”，不复制业务内容。

### 3. Business knowledge skill: semantics only

新增 `opendataworks-business-knowledge`，作为随仓库发布的 bundled skill，承接当前仓库内属于 OpenDataWorks 平台业务语义的内容：

- `assets/term_explanations.json`
- `assets/business_concepts.json`
- `assets/semantic_mappings.json`
- `assets/metrics.json`
- `assets/business_rules.json`
- `assets/ontology.json`
- 由这些资产整理出的 reference 文档

该 skill 不包含 `scripts/` 或 `bin/`，不负责 SQL 验证、执行、图表生成、metadata 检索或环境命令。它可以给出术语、口径、候选表字段和歧义追问建议，但执行链路必须回到 `dataagent-nl2sql` 或 portal-mcp。

业务 SQL example 和 few-shot 从通用 SQL skill 移除。若后续需要保留示例，必须把它们表述为业务口径示例，并放在业务 skill 中，不能作为通用 SQL 方法的一部分。

### 4. Default runtime enablement

后端把 `dataagent-nl2sql` 和 `opendataworks-business-knowledge` 都标记为 bundled。

当 settings 中没有显式 `skill_runtime` 时，默认启用这两个 bundled skills，primary 仍保持 `dataagent-nl2sql`，保证脚本 fallback 的 `DATAAGENT_SKILL_ROOT` 不变。

当 settings 中已有显式 `skill_runtime` 且缺少新业务 skill 条目时，迁移时补一个 enabled=true 条目；如果用户之后显式禁用该 skill，则不再覆盖。

## Interfaces / Data Model

不新增 API、数据库表、请求响应字段或部署环境变量。

保持现有运行时契约：

- `skills_output_dir` 继续指向 primary skill，默认仍是 `../.claude/skills/dataagent-nl2sql`。
- `DATAAGENT_SKILL_ROOT` 继续指向 primary skill。
- `DATAAGENT_ENABLED_SKILLS` 和 `DATAAGENT_ENABLED_SKILL_ROOTS` 暴露所有启用 skill。
- `opendataworks-business-knowledge` 是 bundled skill，不可卸载；可在现有 skill runtime 管理中启停。

## Risks / Alternatives

把 OpenDataWorks 平台语义拆到独立 skill 后，模型首次处理平台问题时可能多一次 Skill 路由。通过默认启用 business skill，并在 system prompt 中明确语义优先级降低该风险。

另一种方案是只精简 system prompt，不拆 skill 内容。该方案不能解决职责重叠，也无法为后续业务知识扩展提供边界。

另一种方案是继续把 business skill 放在仓库外。该方案适合租户私有知识，但当前仓库已有 OpenDataWorks 平台语义，需要一个可测试、可发布的 bundled knowledge skill 承接。

## Verification

- 扩展 `tests/test_agent_runtime.py`，断言 system prompt 不再包含低层脚本命令和平台业务表细节，并包含三层路由原则。
- 扩展 `tests/test_builtin_skill_content.py`，断言通用 SQL skill 不包含业务术语/指标资产，业务 knowledge skill 包含术语、本体、指标和规则，且不包含脚本目录。
- 扩展 `tests/test_skill_admin_service.py`，断言默认 skill runtime 同时启用两个 bundled skills，且新增 business skill 不可卸载。
- 执行聚焦测试：`pytest tests/test_agent_runtime.py tests/test_builtin_skill_content.py tests/test_skill_admin_service.py`。
- 如本地 provider、MySQL、Redis 与 settings 可用，再补 DataAgent HTTP smoke；若不可用，明确记录未跑端到端。
