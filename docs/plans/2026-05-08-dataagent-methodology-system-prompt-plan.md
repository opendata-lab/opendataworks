# DataAgent Methodology System Prompt Plan

> Design: [2026-05-08-dataagent-methodology-system-prompt-design.md](../design/2026-05-08-dataagent-methodology-system-prompt-design.md)

**Goal:** 优化现有 Python DataAgent 的运行时系统提示词和内置 NL2SQL skill，使通用方法论与数据问数质量门禁成为可测试契约。
**Tech Stack:** DataAgent 后端 FastAPI / Claude Agent SDK；内置 `dataagent-nl2sql` skill bundle；pytest。

## Architecture Summary

真实 system prompt 继续由 `core/agent_runtime.py::_build_system_prompt()` 生成并传入 Claude Agent SDK。通用 DataAgent 方法论放在 runtime prompt；OpenDataWorks / NL2SQL 专用执行规则继续放在内置 skill 文档中。

## Task 1: Add Regression Tests

**Files:**
- `dataagent/dataagent-backend/tests/test_agent_runtime.py`
- `dataagent/dataagent-backend/tests/test_builtin_skill_content.py`

**Steps:**
1. 为 `_build_system_prompt()` 增加断言，覆盖内部工作循环、隐藏推理不外显、不可臆造、工具优先、只读、禁止重复等价 SQL。
2. 为内置 skill 快照增加断言，覆盖 SQL 前质量门禁和按场景确认主键/唯一键/关联键。
3. 先运行测试确认新增断言失败，证明测试能捕捉当前缺口。

**Expected Result:**
- 测试在实现前因缺少新提示词和 skill 门禁而失败。

## Task 2: Update Runtime System Prompt

**Files:**
- `dataagent/dataagent-backend/core/agent_runtime.py`
- `dataagent/dataagent-backend/prompts/system_prompt.py`

**Steps:**
1. 在 `_build_system_prompt()` 中加入通用 DataAgent 内部工作循环。
2. 加入不可违反原则，覆盖禁止臆造、工具优先、只读、禁止重复试探和结果不足时最小追问。
3. 保留现有 enabled skills、portal-mcp、lineage、DDL、script fallback 和中文回答约束。
4. 删除未被主执行链路引用的旧 LF/JSON system prompt 模板。

**Expected Result:**
- system prompt 更清晰地约束 DataAgent 执行方法论，旧 LF/JSON 模板不再留在代码中造成混淆。

## Task 3: Update Builtin Skill Quality Gate

**Files:**
- `dataagent/.claude/skills/dataagent-nl2sql/SKILL.md`

**Steps:**
1. 在 Iron Laws 附近增加“数据问数质量门禁”。
2. 明确 SQL 执行前必须确认目标、库/引擎/schema、表、使用字段、指标公式、过滤条件、时间范围、维度/粒度。
3. 明确 JOIN、去重、明细定位、血缘映射时必须确认主键、唯一键或关联键。
4. 明确简单聚合不把主键/唯一键作为硬门槛，避免无谓返工。

**Expected Result:**
- NL2SQL 专用方法论保留在 skill 层，符合内置 skill 是问数执行纪律来源的边界。

## Verification

- `pytest tests/test_agent_runtime.py tests/test_builtin_skill_content.py`
- `pytest tests/test_agent_runtime.py tests/test_builtin_skill_content.py tests/test_task_executor.py`
- 可用环境下执行 DataAgent HTTP smoke；若 provider 或本地服务不可用，记录未覆盖的端到端路径。

## Rollout / Backout

该变更只影响提示词和文档化 skill 约束，不改 API、数据库、前端或部署。若线上效果变差，可回退 `core/agent_runtime.py` 的新增 prompt 文本和 `SKILL.md` 的质量门禁段落，测试断言同步回退。
