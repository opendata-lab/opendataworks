# DataAgent Methodology System Prompt Design

**Date:** 2026-05-08
**Goal:** 将通用 DataAgent 工作方法论沉淀到现有 Python DataAgent 运行时系统提示词，并将 NL2SQL 质量门禁沉淀到内置 skill。
**Tech Stack:** DataAgent 后端 FastAPI / Claude Agent SDK；内置 `dataagent-nl2sql` skill bundle。

## Scope

- 覆盖 `dataagent/dataagent-backend/core/agent_runtime.py` 中真实传给 Claude Agent SDK 的 system prompt。
- 覆盖 `dataagent/.claude/skills/dataagent-nl2sql/SKILL.md` 中的 NL2SQL 执行纪律。
- 覆盖相关后端单测和 skill 内容快照测试。

不覆盖 `opendataagent/`、共享 `skills/` 目录、前端交互、数据库 schema、部署模板或模型 provider 配置。

## Current State

DataAgent 主执行链路由 `core/task_executor.py` 构造 `ClaudeAgentOptions(system_prompt=...)`，system prompt 来自 `core/agent_runtime.py::_build_system_prompt()`。该提示词已经要求优先使用已启用 Skills、portal-mcp、血缘和 DDL 专用工具，并限制只读执行。

内置 `dataagent-nl2sql` skill 已经承载 OpenDataWorks 平台术语、平台表、工具调用、图表契约和数据中台通用规则。更旧的 `prompts/system_prompt.py` LF/JSON 模板不在当前主执行链路中，已作为遗留源码移除。

## Problem

现有运行时提示词偏工具路由，缺少通用 DataAgent 工作方法论：先判定意图与缺口、获取上下文、制定最小执行路径、执行并基于真实工具结果收口。

现有 skill 有执行顺序和工具规则，但 SQL 前质量门禁不够集中，尤其是字段、指标、时间范围、维度粒度，以及 JOIN、去重、明细定位、血缘映射时的主键、唯一键或关联键确认规则。

## Design

运行时 system prompt 只承载通用方法论和不可违反原则：

- 内部工作循环：判定意图与信息缺口 -> 获取必要上下文 -> 制定最小执行路径 -> 基于真实工具结果执行和收口。
- 不向用户暴露隐藏推理，只输出可验证结论、必要口径和缺口。
- 不臆造表、字段、指标口径、业务默认值或租户知识。
- 不绕过已启用 Skills、portal-mcp 优先级、只读限制或专用工具路径。
- 不重复试探等价 SQL；工具结果不足时最小追问或说明缺口。

内置 `dataagent-nl2sql` skill 承载 NL2SQL 质量门禁：

- 执行 SQL 前确认目标、库/引擎/schema、表、使用字段、指标公式、过滤条件、时间范围、维度/粒度。
- 涉及 JOIN、去重、明细定位、血缘映射或结果粒度敏感场景时，确认主键、唯一键或关联键。
- 主键、唯一键或关联键不是所有简单聚合的硬门槛，避免为简单 COUNT/趋势问题引入不必要往返。

## Interfaces / Data Model

不新增或修改 API、数据库表、请求响应结构、前端契约或部署环境变量。

现有运行时契约保持不变：

- `DATAAGENT_PYTHON_BIN`
- `DATAAGENT_SKILL_ROOT`
- `DATAAGENT_ENABLED_SKILLS`
- portal-mcp first
- Python script fallback
- read-only SQL execution

## Risks / Alternatives

更强的提示词可能增加模型在执行前的自检成本。为降低延迟，主键/唯一键确认仅绑定到 JOIN、去重、明细定位、血缘映射等真实需要场景，不作为所有查询硬门槛。

另一种方案是把全部方法论写入 skill。该方案会让通用 DataAgent 行为依赖某个具体 skill，不利于后续多 skill；因此只把 NL2SQL 专用门禁放入 skill。

## Verification

- 扩展 `tests/test_agent_runtime.py`，断言真实 runtime system prompt 包含方法论和不可违反原则。
- 断言旧 `prompts/system_prompt.py` LF/JSON 模板已删除，避免再次混淆真实运行链路。
- 扩展 `tests/test_builtin_skill_content.py`，断言内置 skill 包含数据问数质量门禁，且业务租户默认值仍不会回流。
- 执行 `pytest tests/test_agent_runtime.py tests/test_builtin_skill_content.py tests/test_task_executor.py`。
- 若本地 provider、MySQL、Redis 和配置可用，补充一条 DataAgent HTTP smoke；不可用时记录具体缺口。
