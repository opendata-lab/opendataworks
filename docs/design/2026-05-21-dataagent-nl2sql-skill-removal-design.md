# DataAgent NL2SQL Skill Removal Design

**Date:** 2026-05-21
**Goal:** 删除 `dataagent-nl2sql` 通用问数 skill，将必要方法规则合并进 DataAgent system prompt，并把 system prompt 抽成 Markdown 文件维护。
**Tech Stack:** DataAgent 后端 FastAPI / Claude Agent SDK；内置 Skills `opendataworks-business-knowledge` 与 `opendataworks-platform-tools`；pytest。

## Scope

- 覆盖 DataAgent 真实执行链路中传给 Claude Agent SDK 的 system prompt。
- 覆盖内置 skill 默认启用、primary skill 兼容路径、skill 管理接口返回和运行时环境注入。
- 删除 `dataagent/.claude/skills/dataagent-nl2sql` 静态 skill bundle。
- 更新 DataAgent 文档中关于内置 skills 和 prompt 边界的说明。

不覆盖前端交互重构、数据库 schema 变更、provider 配置、portal-mcp 协议或平台工具脚本迁移。

## Current State

当前 `_build_system_prompt()` 直接在 `core/agent_runtime.py` 中拼接 system prompt。`dataagent-nl2sql` 作为 primary skill 承载通用问数方法：任务分类、澄清、SQL 前检查、找表找字段策略、只读边界和失败收口。

经过前序拆分后，业务语义已经在 `opendataworks-business-knowledge`，真实平台访问、SQL 验证和只读执行已经在 `opendataworks-platform-tools`。剩余 `dataagent-nl2sql` 只保存方法论文本，与 system prompt 的职责高度重叠。

## Problem

- 通用方法规则分散在 system prompt 和 `dataagent-nl2sql`，模型需要额外触发 skill 才能读到已经应当全局适用的行为规则。
- `dataagent-nl2sql` 不再包含脚本、资产或独立运行能力，继续作为 primary skill 会增加默认启用和路径兼容复杂度。
- Python 代码中内联长 prompt 可读性差，调整行为边界时不方便 review。

## Design

### 1. System Prompt Markdown

新增 `dataagent/dataagent-backend/prompts/data_agent_system_prompt.md`，保存完整 Data Agent system prompt。

该 prompt 合并 `dataagent-nl2sql` 中仍有价值的通用规则：

- 角色定位：企业级智能问数 Data Agent，不是单纯 SQL 生成器。
- 行为边界：不编造、不跳过 schema/字段/口径确认、不做写操作、不伪造工具结果。
- 任务路由：语义、元数据、数据结果、归因分析四类路径。
- 标准工作流：理解问题、选择路径、补齐信息、获取证据、组织输出。
- 澄清、SQL 使用、归因分析、输出和失败处理规则。

`core/agent_runtime.py::_build_system_prompt()` 只负责读取 Markdown，并追加运行时上下文：

- 当前启用 skills。
- 用户显式提供的 database hint。

### 2. Built-in Skills After Removal

内置 bundled skills 收敛为：

- `opendataworks-business-knowledge`：只负责 OpenDataWorks 平台业务语义、术语、指标口径、本体、别名和歧义消解。
- `opendataworks-platform-tools`：只负责真实平台能力、metadata、DDL、血缘、SQL 验证、只读执行、结果格式化和图表契约。

`dataagent-nl2sql` 从 bundled skill 集合、默认启用集合和仓库静态目录中移除。

### 3. Legacy Settings Compatibility

已有配置可能仍包含：

- `skills_output_dir=../.claude/skills/dataagent-nl2sql`
- `skill_runtime.dataagent-nl2sql.enabled=true`

运行时归一化时将该 legacy skill 视为迁移信号：

- 不再把 `dataagent-nl2sql` 暴露为 enabled skill。
- 自动启用现有 bundled skills：`opendataworks-business-knowledge` 与 `opendataworks-platform-tools`。
- 若 primary 指向已删除目录，解析运行时时切到第一个仍可用的 enabled skill。
- 新默认 `skills_output_dir` 指向 `../.claude/skills/opendataworks-business-knowledge`，避免新部署 primary 指向不存在目录。

### 4. Runtime Contracts

保持不变：

- `DATAAGENT_ENABLED_SKILLS`
- `DATAAGENT_ENABLED_SKILL_ROOTS`
- `DATAAGENT_PLATFORM_SKILL_ROOT`
- platform tools skill 中的脚本调用形式：`"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/<name>.py" ...`

变化：

- `DATAAGENT_SKILL_ROOT` 不再默认指向 `dataagent-nl2sql`，而是指向当前 primary enabled skill。
- system prompt 不再要求调用通用 SQL skill；通用问数方法由 system prompt 直接提供。

## Risks / Tradeoffs

- 历史文档和外部脚本如果仍假设 `dataagent-nl2sql` 存在，会失效。通过 README、测试和 legacy settings 迁移降低风险。
- 删除通用 skill 后，prompt 会变长；但这部分规则本来就全局适用，放在 system prompt 更稳定。
- 旧数据库中仍可能持久化 legacy runtime 字段；本轮在运行时归一化，不做 schema 迁移，避免数据库变更。

## Verification

- `pytest tests/test_agent_runtime.py tests/test_builtin_skill_content.py tests/test_skill_admin_service.py tests/test_skill_discovery.py`
- `rg dataagent-nl2sql dataagent/dataagent-backend dataagent/.claude/skills dataagent/README.md deploy/README.md`

本轮不要求完整 HTTP smoke；如果未运行，最终说明只完成静态和单元验证。
