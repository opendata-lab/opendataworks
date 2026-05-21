# DataAgent NL2SQL Skill Removal Plan

> Design: [2026-05-21-dataagent-nl2sql-skill-removal-design.md](../design/2026-05-21-dataagent-nl2sql-skill-removal-design.md)

**Goal:** 删除 `dataagent-nl2sql` skill，将通用问数方法收敛到 Markdown system prompt，并保持 DataAgent 运行时兼容旧配置。
**Tech Stack:** DataAgent 后端 FastAPI / Claude Agent SDK；Markdown prompt；pytest。

## Task 1: Add Regression Tests First

**Files:**
- `dataagent/dataagent-backend/tests/test_agent_runtime.py`
- `dataagent/dataagent-backend/tests/test_builtin_skill_content.py`
- `dataagent/dataagent-backend/tests/test_skill_admin_service.py`
- `dataagent/dataagent-backend/tests/test_skill_discovery.py`

**Steps:**
1. 更新 system prompt 测试，要求 `_build_system_prompt()` 读取 Markdown prompt，包含用户给定的企业级 Data Agent 行为规则，并追加 enabled skills/database hint。
2. 增加断言：system prompt 不再引用 `dataagent-nl2sql` 或“通用 SQL skill”。
3. 更新 skill 内容测试，断言 `dataagent-nl2sql` 目录不存在，业务知识和平台工具 skill 不再把 SQL 方法交给该 skill。
4. 更新 skill runtime 测试，断言默认 bundled skills 只有 `opendataworks-business-knowledge` 和 `opendataworks-platform-tools`。
5. 增加 legacy settings 测试：旧 `dataagent-nl2sql` 配置会迁移到现有 bundled skills，且不继续暴露 deleted skill。
6. 运行聚焦测试，确认失败点对应尚未实现的 prompt 文件、默认配置和 skill 删除。

## Task 2: Extract System Prompt

**Files:**
- Create: `dataagent/dataagent-backend/prompts/data_agent_system_prompt.md`
- Modify: `dataagent/dataagent-backend/core/agent_runtime.py`

**Steps:**
1. 新建 Markdown prompt 文件，写入企业级智能问数 Data Agent 角色、边界、路由、工作流、澄清、SQL、归因、输出和失败处理规则。
2. 将 `_build_system_prompt()` 改为读取 Markdown 文件。
3. 在读取结果后追加 `# 运行时上下文`，写入 enabled skills 和 database hint。
4. 保持低层脚本名、平台表名、MCP 工具名不进入 system prompt。

## Task 3: Remove Built-in NL2SQL Skill Runtime Dependency

**Files:**
- Modify: `dataagent/dataagent-backend/config.py`
- Modify: `dataagent/dataagent-backend/core/skill_discovery.py`
- Modify: `dataagent/dataagent-backend/core/skill_admin_service.py`
- Delete: `dataagent/.claude/skills/dataagent-nl2sql/*`

**Steps:**
1. 将新默认 `skills_output_dir` 改为 `../.claude/skills/opendataworks-business-knowledge`。
2. 从 bundled skill 常量和默认启用集合中删除 `dataagent-nl2sql`。
3. 保留 legacy 归一化逻辑：旧 `dataagent-nl2sql` 配置触发启用 business knowledge 和 platform tools，但不再暴露 legacy skill。
4. 删除 `dataagent/.claude/skills/dataagent-nl2sql` 下的 Markdown 文件。
5. 确认 `resolve_enabled_skill_runtime()` 在旧 primary 不存在时选择仍可用的 enabled skill。

## Task 4: Update Remaining Skill Docs and Repository Docs

**Files:**
- Modify: `dataagent/.claude/skills/opendataworks-business-knowledge/SKILL.md`
- Modify: `dataagent/.claude/skills/opendataworks-platform-tools/SKILL.md`
- Modify: `dataagent/README.md`
- Modify: `deploy/README.md`

**Steps:**
1. 将 business knowledge skill 中“查询方法交给 dataagent-nl2sql”改为“通用问数方法由 system prompt 约束”。
2. 将 platform tools skill 中“SQL 方法交给 dataagent-nl2sql”改为“SQL 就绪规则由 system prompt 约束”。
3. 更新 DataAgent README，说明当前内置 skills 只剩业务知识和平台工具，通用问数行为来自 Markdown system prompt。
4. 更新 deploy README，移除生产主链依赖 `dataagent-nl2sql` 的表述。

## Task 5: Verify

**Commands:**

```bash
cd dataagent/dataagent-backend
pytest tests/test_agent_runtime.py tests/test_builtin_skill_content.py tests/test_skill_admin_service.py tests/test_skill_discovery.py
```

```bash
rg dataagent-nl2sql dataagent/dataagent-backend dataagent/.claude/skills dataagent/README.md deploy/README.md
```

**Expected Result:**
- 聚焦 pytest 通过。
- `rg` 只允许出现测试中的 legacy 兼容断言；运行时默认、prompt 和 skill 文档不再依赖 deleted skill。

## Rollout / Backout

- Rollout：随 DataAgent 镜像复制新的 prompt 文件和剩余 bundled skills；旧 settings 自动在运行时归一化到现有 bundled skills。
- Backout：恢复 `dataagent-nl2sql` 目录、默认 `skills_output_dir`、bundled skill 常量和 `_build_system_prompt()` 旧内联 prompt。
