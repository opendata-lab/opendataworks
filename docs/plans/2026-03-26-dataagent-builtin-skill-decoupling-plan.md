# DataAgent Builtin Skill Decoupling Implementation Plan

> Design: [../design/2026-03-26-dataagent-builtin-skill-decoupling-design.md](../design/2026-03-26-dataagent-builtin-skill-decoupling-design.md)

**Goal:** 让仓库内置 `dataagent-nl2sql` 只承载 OpenDataWorks 平台通用问数能力，并把拆出的业务知识整理到本地未提交的 `business-domain-assistant` skill。

## Task 1: Clean builtin skill content

**Files**

- `dataagent/.claude/skills/dataagent-nl2sql/SKILL.md`
- `dataagent/.claude/skills/dataagent-nl2sql/assets/*.json`
- `dataagent/.claude/skills/dataagent-nl2sql/reference/*.md`
- `dataagent/.claude/skills/dataagent-nl2sql/scripts/build_reference_digest.py`

**Steps**

1. 清理 `assets/business_rules.json`、`few_shots.json`、`term_explanations.json`、`sql_examples.json`
2. 保留平台通用规则和 `df/di`、`ds` 约束
3. 重写 `SKILL.md` 与手工 reference，去掉租户私有默认值
4. 运行 `build_reference_digest.py` 重新生成 `20/21/22` 索引

## Task 2: Separate builtin path semantics and update UI language

**Files**

- `dataagent/dataagent-backend/core/skill_discovery.py`
- `dataagent/dataagent-backend/core/nl2sql_agent.py`
- `frontend/src/views/intelligence/toolPresentation.js`
- `frontend/src/views/settings/DataAgentConfig.vue`
- `frontend/src/views/settings/SkillStudio.vue`
- `frontend/src/views/settings/ConfigurationManagement.vue`
- `dataagent/README.md`

**Steps**

1. 在后端内部区分 builtin skill root 与 skill discovery root
2. 调整系统提示词，明确当前内置 skill 不提供租户私有默认值
3. 去掉前端对 `dataagent-nl2sql` 常量的 skill 识别依赖
4. 将设置页和 Skill Studio 文案收敛为“内置 skill 管理”
5. 更新 README 对内置 skill 职责边界的说明

## Task 3: Add regression coverage and docs

**Files**

- `dataagent/dataagent-backend/tests/test_builtin_skill_content.py`
- `frontend/src/views/intelligence/__tests__/toolPresentation.spec.js`
- `docs/design/2026-03-26-dataagent-builtin-skill-decoupling-design.md`
- `docs/plans/2026-03-26-dataagent-builtin-skill-decoupling-plan.md`

**Steps**

1. 增加内置 skill 静态防回流测试
2. 增加前端通用 skill 识别测试
3. 补齐设计与实施计划文档

## Task 4: Create local business-domain-assistant skill without committing it

**Files**

- worktree-local Git exclude
- repo-root `.claude/skills/business-domain-assistant/**` (local only)

**Steps**

1. 在当前 worktree 的 `.git/info/exclude` 中排除根目录 `.claude/`
2. 用 `skill-creator` 初始化 `business-domain-assistant`
3. 将从 builtin skill 移出的业务知识整理到 `references/`
4. 运行 `quick_validate.py`

## Verification

- 后端：
  - `pytest dataagent/dataagent-backend/tests/test_builtin_skill_content.py dataagent/dataagent-backend/tests/test_skill_discovery.py dataagent/dataagent-backend/tests/test_admin_routes.py dataagent/dataagent-backend/tests/test_task_executor.py`
- 前端：
  - 先执行 `export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use`
  - 再跑 `vitest` 的相关测试文件
- 本地 skill：
  - `quick_validate.py` 校验 `business-domain-assistant`

## Rollout

1. 先合入 builtin skill 去业务化与测试
2. 保持当前运行时仍只发现内置 skill
3. 后续若要支持上传压缩包与显式启用，再基于这次边界继续扩展

## Backout

- 若需要回退，只需恢复 builtin skill 资产、前后端文案和 path helper
- 本次不涉及 schema migration，回退无需数据库回滚
