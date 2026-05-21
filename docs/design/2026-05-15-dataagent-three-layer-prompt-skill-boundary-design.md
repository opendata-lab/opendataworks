# DataAgent Prompt / Skill Boundary Design

> Superseded by [2026-05-21-dataagent-nl2sql-skill-removal-design.md](2026-05-21-dataagent-nl2sql-skill-removal-design.md). The current design removes `dataagent-nl2sql` and moves the remaining generic query methodology into a Markdown system prompt.

**Date:** 2026-05-15
**Goal:** 将 DataAgent 问数上下文拆成 system prompt、通用问数方法 skill、业务知识 skill、平台工具 skill 四个清晰职责面，并消除职责重叠。
**Tech Stack:** DataAgent 后端 FastAPI / Claude Agent SDK；内置 Skills `dataagent-nl2sql`、`opendataworks-business-knowledge` 与 `opendataworks-platform-tools`；pytest。

## Scope

- 覆盖 `dataagent/dataagent-backend/core/agent_runtime.py` 中真实传给 Claude Agent SDK 的 system prompt。
- 覆盖 `dataagent/.claude/skills/dataagent-nl2sql` 的通用问数方法、表字段发现方法、SQL 生成前检查和结果收口策略。
- 新增 `dataagent/.claude/skills/opendataworks-business-knowledge`，承接 OpenDataWorks 平台术语、本体、指标口径、别名、歧义消解和业务规则例外。
- 新增 `dataagent/.claude/skills/opendataworks-platform-tools`，承接 OpenDataWorks 平台工具能力、脚本 fallback、CLI、SQL 验证/执行和工具输出契约。
- 覆盖默认内置 skill 运行时启用策略、相关单测和 DataAgent README。

不覆盖前端交互、数据库 schema、provider 配置、portal-mcp 协议或真实业务租户私有知识导入。

## Current State

当前真实执行链路由 `core/task_executor.py` 构造 `ClaudeAgentOptions(system_prompt=...)`，system prompt 来自 `core/agent_runtime.py::_build_system_prompt()`。

当前 system prompt 已经写入大量问数细节，包括 lineage、DDL、脚本 fallback、分层管线、SQL 验证、SQL 执行、失败处理和评测场景规则。这使 system prompt 偏低抽象，维护时容易把某个 skill 的局部规则提升成全局规则。

当前内置 `dataagent-nl2sql` 已经剥离出一部分业务知识，但仍同时承担两类职责：

- 通用问数 SQL 方法：分类、澄清、表字段发现方法、SQL 生成前检查、结果收口。
- OpenDataWorks 平台工具能力：metadata 检索、datasource 路由、DDL、lineage、SQL 验证、只读执行、图表契约和脚本 fallback。

后端已经支持多 skill 发现和启停，并已具备 bundled skill 默认启用逻辑。`dataagent-nl2sql` 仍是 primary skill，`DATAAGENT_SKILL_ROOT` 仍指向 primary skill，因此脚本如果继续依赖 primary root 会阻碍平台工具拆分。

## Problem

三层职责混叠导致几个问题：

- system prompt 过长且过低抽象时，容易把某个 skill 的局部工具规则提升为全局规则。
- 通用问数 skill 如果继续保存脚本命令和平台工具契约，会同时扮演“方法”和“工具箱”，后续难以复用到非 OpenDataWorks 平台。
- 业务语义、通用找表找字段方法、平台脚本能力如果没有独立边界，模型容易在业务口径不足时直接用相似字段猜 SQL。
- 新增平台工具 skill 后，运行时必须暴露稳定的工具根目录；否则 primary `DATAAGENT_SKILL_ROOT` 与脚本真实位置会冲突。

## Design

### 1. System Prompt: identity, boundary, routing, priority

System prompt 只保留“合适高度”的全局行为：

- 身份：DataAgent 智能问数助手。
- 边界：只读、不可臆造、不要暴露隐藏推理、中文结论优先。
- 优先级：用户显式约束 > 已启用业务知识 skill 的术语和口径 > 真实 metadata / DDL / 工具结果 > 通用 SQL 方法。
- 路由：业务语义交给业务知识 skill，通用问数方法交给 `dataagent-nl2sql`，真实平台能力交给 `opendataworks-platform-tools`。
- 工作顺序：路由问题 -> 补齐业务语义 -> 用通用问数方法定位数据对象 -> 通过平台工具获取 metadata/DDL/lineage 或验证执行 SQL -> 基于结果回答或说明缺口。

System prompt 采用模板化结构，使用 `# Role`、`# Primary Goal`、`# Boundaries`、`# Instruction Priority`、`# Workflow`、`# Routing Rules`、`# Output Requirements` 分节。每节使用简单直接语言描述身份、边界、优先级和工作顺序，不再硬编码具体脚本名、命令模板、平台表、lineage fallback 细节或图表生成细节。

### 2. Generic query skill: method only

`dataagent-nl2sql` 保留为 primary skill，但只作为通用问数方法 skill，不再拥有 `scripts/`、`bin/` 或平台工具输出契约。

它只承载：

- 问题分类、最小追问、上下文收集、表/字段候选发现方法、SQL 生成前检查和结果收口。
- 当业务知识 skill 已给出口径时，负责把语义转成可查询槽位。
- 当业务知识 skill 无法确定 SQL 所需表字段时，负责通过平台工具做 metadata 相似表、字段、DDL 和 lineage 证据收集。
- SQL 安全、schema 前缀、只读、LIMIT、单源路由等通用方法规则。

它不再承载：

- 平台术语解释。
- 平台本体或平台核心表字段清单。
- 指标公式、默认时间字段、业务别名。
- 业务 SQL example、few-shot、业务规则例外。
- 平台脚本命令模板、CLI、MCP tool 清单、工具输出 JSON 契约。

当 SQL 方法需要语义或口径时，它只引用已启用业务知识 skill；当需要真实平台能力时，它只引用 `opendataworks-platform-tools`，不复制脚本细节。

### 3. Business knowledge skill: semantics only

新增 `opendataworks-business-knowledge`，作为随仓库发布的 bundled skill，承接当前仓库内属于 OpenDataWorks 平台业务语义的内容：

- `assets/term_explanations.json`
- `assets/business_concepts.json`
- `assets/semantic_mappings.json`
- `assets/metrics.json`
- `assets/business_rules.json`
- `assets/ontology.json`
- 由这些资产整理出的 reference 文档

该 skill 不包含 `scripts/` 或 `bin/`，不负责 SQL 验证、执行、图表生成、metadata 检索或环境命令。它可以给出术语、口径、候选表字段和歧义追问建议，但执行链路必须回到 `dataagent-nl2sql` 的通用方法，再由 `opendataworks-platform-tools` 获取真实平台能力。

业务 SQL example 和 few-shot 从通用 SQL skill 移除。若后续需要保留示例，必须把它们表述为业务口径示例，并放在业务 skill 中，不能作为通用 SQL 方法的一部分。

### 4. Platform tools skill: capability only

新增 `opendataworks-platform-tools`，作为 bundled skill 承接所有 OpenDataWorks 平台工具能力：

- `scripts/inspect_metadata.py`
- `scripts/query_opendataworks_metadata.py`
- `scripts/resolve_datasource.py`
- `scripts/get_table_ddl.py`
- `scripts/get_lineage.py`
- `scripts/validate_sql.py`
- `scripts/run_sql.py`
- `scripts/build_chart_spec.py`
- `scripts/format_answer.py`
- `bin/odw-cli`
- `assets/constraints.json`、`assets/policies.json`、`assets/chart-template/*.json`
- tool recipes、runtime metadata、tool output contract reference

该 skill 不解释业务口径，不维护本体，不决定指标语义。它只说明平台能力、输入参数、脚本调用、MCP-first 策略、只读执行和失败归因。

脚本 fallback 的 canonical invocation 改为：

```bash
"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/<name>.py" ...
```

`DATAAGENT_PLATFORM_SKILL_ROOT` 由运行时在 `opendataworks-platform-tools` 启用时注入。脚本内部优先使用 `DATAAGENT_PLATFORM_SKILL_ROOT`，其次从 `DATAAGENT_ENABLED_SKILL_ROOTS` 解析 `opendataworks-platform-tools`，最后回退到脚本自身所在 skill 根目录。

### 5. Default runtime enablement

后端把 `dataagent-nl2sql`、`opendataworks-business-knowledge` 和 `opendataworks-platform-tools` 都标记为 bundled。

当 settings 中没有显式 `skill_runtime` 时，默认启用这三个 bundled skills，primary 仍保持 `dataagent-nl2sql`，保证 `DATAAGENT_SKILL_ROOT` 的 primary 兼容含义不变，同时通过 `DATAAGENT_PLATFORM_SKILL_ROOT` 暴露平台工具根目录。

当 settings 中已有显式 `skill_runtime` 且缺少新 bundled skill 条目时，迁移时补 enabled=true 条目；如果用户之后显式禁用某个 bundled skill，则不再覆盖。

## Interfaces / Data Model

不新增 API、数据库表、请求响应字段或部署环境变量。

保持现有运行时契约：

- `skills_output_dir` 继续指向 primary skill，默认仍是 `../.claude/skills/dataagent-nl2sql`。
- `DATAAGENT_SKILL_ROOT` 继续指向 primary skill，即 `dataagent-nl2sql`。
- `DATAAGENT_PLATFORM_SKILL_ROOT` 指向 `opendataworks-platform-tools`，用于平台脚本 fallback。
- `DATAAGENT_ENABLED_SKILLS` 和 `DATAAGENT_ENABLED_SKILL_ROOTS` 暴露所有启用 skill。
- `opendataworks-business-knowledge` 和 `opendataworks-platform-tools` 是 bundled skill，不可卸载；可在现有 skill runtime 管理中启停。

## Risks / Alternatives

把 OpenDataWorks 平台工具拆到独立 skill 后，脚本路径从 primary skill 切到 platform tools skill；旧文档或外部智能体若仍使用 `${DATAAGENT_SKILL_ROOT}/scripts` 会失败。通过 README、skill references 和测试统一改成 `${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts` 降低迁移风险。

另一种方案是只精简 system prompt，不拆 skill 内容。该方案不能解决职责重叠，也无法为后续业务知识扩展提供边界。

另一种方案是把平台工具继续留在 `dataagent-nl2sql`，只拆业务知识。该方案短期最稳，但通用问数 skill 仍无法脱离 OpenDataWorks 平台工具复用。

## Verification

- 扩展 `tests/test_agent_runtime.py`，断言 system prompt 不再包含低层脚本命令和平台业务表细节，并包含四职责面路由原则与 `DATAAGENT_PLATFORM_SKILL_ROOT`。
- 扩展 `tests/test_builtin_skill_content.py`，断言通用 SQL skill 不包含业务资产或平台脚本目录，业务 knowledge skill 包含语义资产且没有脚本，平台 tools skill 包含脚本/CLI/tool contracts 且没有业务口径。
- 扩展 `tests/test_skill_admin_service.py`，断言默认 skill runtime 同时启用三个 bundled skills，且新增 platform tools skill 不可卸载。
- 执行聚焦测试：`pytest tests/test_agent_runtime.py tests/test_builtin_skill_content.py tests/test_skill_admin_service.py tests/test_skill_discovery.py tests/test_metadata_cli_bridge.py tests/test_odw_cli.py tests/test_build_chart_spec_script.py`。
- 如本地 provider、MySQL、Redis 与 settings 可用，再补 DataAgent HTTP smoke；若不可用，明确记录未跑端到端。
