# DataAgent Three-Layer Prompt / Skill Boundary Plan

> Design: [2026-05-15-dataagent-three-layer-prompt-skill-boundary-design.md](../design/2026-05-15-dataagent-three-layer-prompt-skill-boundary-design.md)

**Goal:** 将 DataAgent 问数上下文落到 system prompt、通用 SQL skill、业务 knowledge skill 三层清晰边界。
**Tech Stack:** DataAgent 后端 FastAPI / Claude Agent SDK；内置 Skills；pytest。

## Architecture Summary

`core/agent_runtime.py` 只负责高层身份、边界、路由、优先级和工作顺序。`dataagent-nl2sql` 继续作为 primary skill 和脚本 fallback 根目录，但内容收敛为通用 SQL 方法和工具契约。新增 `opendataworks-business-knowledge` bundled skill 保存 OpenDataWorks 平台术语、本体、指标口径、别名、歧义消解和规则例外，并默认与通用 SQL skill 一起启用。

## Task 1: Add regression tests first

**Files:**
- `dataagent/dataagent-backend/tests/test_agent_runtime.py`
- `dataagent/dataagent-backend/tests/test_builtin_skill_content.py`
- `dataagent/dataagent-backend/tests/test_skill_admin_service.py`

**Steps:**
1. 修改 system prompt 测试，要求包含三层路由原则，不包含 `run_sql.py --database`、`DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1` 等低层命令。
2. 修改 skill 内容测试，分别读取 `dataagent-nl2sql` 和 `opendataworks-business-knowledge`。
3. 增加断言：通用 SQL skill 不再包含业务语义资产；业务 skill 包含 terms/ontology/metrics/rules 且没有 scripts/bin。
4. 增加后端 runtime 测试：默认 settings 缺少显式 `skill_runtime` 时，两个 bundled skills 都 enabled。
5. 运行聚焦测试，确认先失败。

**Expected Result:**
- 测试失败点对应尚未完成的 system prompt 精简、business skill 缺失和 runtime 默认启用策略。

## Task 2: Split bundled skill content

**Files:**
- `dataagent/.claude/skills/dataagent-nl2sql/SKILL.md`
- `dataagent/.claude/skills/dataagent-nl2sql/reference/*.md`
- `dataagent/.claude/skills/dataagent-nl2sql/assets/*.json`
- `dataagent/.claude/skills/dataagent-nl2sql/scripts/build_reference_digest.py`
- `dataagent/.claude/skills/opendataworks-business-knowledge/SKILL.md`
- `dataagent/.claude/skills/opendataworks-business-knowledge/reference/*.md`
- `dataagent/.claude/skills/opendataworks-business-knowledge/assets/*.json`

**Steps:**
1. 新增 `opendataworks-business-knowledge` skill，写清它只提供业务语义，不提供执行脚本。
2. 将术语、本体、指标、语义映射和业务规则资产迁入 business skill。
3. 重写 business skill reference，覆盖术语、指标、规则、本体和歧义消解。
4. 重写 `dataagent-nl2sql` 的 `SKILL.md` 和 references，只保留 SQL 方法、检查项、工具 recipes、失败处理和输出契约。
5. 从通用 SQL skill 删除业务语义资产和业务 reference。
6. 删除或停止暴露通用 skill 中只服务业务资产生成的 `build_reference_digest.py`。

**Expected Result:**
- 通用 SQL skill 不夹带业务术语或指标口径；业务 skill 不包含 SQL 执行/验证脚本。

## Task 3: Simplify system prompt and runtime defaults

**Files:**
- `dataagent/dataagent-backend/core/agent_runtime.py`
- `dataagent/dataagent-backend/core/skill_admin_service.py`
- `dataagent/README.md`

**Steps:**
1. 将 `_build_system_prompt()` 改成高层身份、边界、优先级、三层路由和工作顺序。
2. 使用 `# Role`、`# Primary Goal`、`# Boundaries`、`# Instruction Priority`、`# Workflow`、`# Routing Rules`、`# Output Requirements` 模板化结构组织 system prompt。
3. 移除 system prompt 中的脚本命令模板、lineage fallback、平台表和评测细节。
4. 将 `opendataworks-business-knowledge` 加入 bundled skill 集合。
5. 调整默认 runtime 归一化逻辑，在没有显式配置或旧配置缺失新 bundled skill 时启用两个内置 skill，primary 保持 `dataagent-nl2sql`。
6. 更新 DataAgent README，说明两层内置 skill 和 primary skill 兼容契约。

**Expected Result:**
- 新部署默认可发现通用 SQL skill 和业务 knowledge skill；旧 `DATAAGENT_SKILL_ROOT` 兼容不变。

## Task 4: Verify and document gaps

**Files:**
- `dataagent/dataagent-backend/tests/*`
- `docs/design/2026-05-15-dataagent-three-layer-prompt-skill-boundary-design.md`
- `docs/plans/2026-05-15-dataagent-three-layer-prompt-skill-boundary-plan.md`

**Steps:**
1. 运行 `pytest tests/test_agent_runtime.py tests/test_builtin_skill_content.py tests/test_skill_admin_service.py`。
2. 如失败，按测试输出修正最小必要代码或文档。
3. 检查 `rg` 结果，确认通用 skill 不再保留业务语义资产名和平台指标 reference。
4. 记录是否执行本地 DataAgent HTTP smoke；如果环境不可用，明确说明未覆盖端到端。

**Expected Result:**
- 聚焦单测通过；若未跑 HTTP smoke，最终说明只完成了静态和单元验证。

## Verification

```bash
cd dataagent/dataagent-backend
pytest tests/test_agent_runtime.py tests/test_builtin_skill_content.py tests/test_skill_admin_service.py
```

## Rollout / Backout

- Rollout：随 DataAgent 后端镜像复制 `dataagent/.claude`，两个 bundled skills 都进入运行时发现目录。
- Backout：回退本变更中的 system prompt、skill 内容拆分和 bundled skill 默认启用逻辑；primary `skills_output_dir` 没变，因此回退不涉及配置迁移。
