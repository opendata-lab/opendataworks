# DataAgent Prompt / Skill Boundary Plan

> Superseded by [2026-05-21-dataagent-nl2sql-skill-removal-plan.md](2026-05-21-dataagent-nl2sql-skill-removal-plan.md). The current plan removes `dataagent-nl2sql` and moves the remaining generic query methodology into a Markdown system prompt.

> Design: [2026-05-15-dataagent-three-layer-prompt-skill-boundary-design.md](../design/2026-05-15-dataagent-three-layer-prompt-skill-boundary-design.md)

**Goal:** 将 DataAgent 问数上下文落到 system prompt、通用问数 skill、业务 knowledge skill、平台 tools skill 四个清晰职责面。
**Tech Stack:** DataAgent 后端 FastAPI / Claude Agent SDK；内置 Skills；pytest。

## Architecture Summary

`core/agent_runtime.py` 只负责高层身份、边界、路由、优先级和工作顺序。`dataagent-nl2sql` 继续作为 primary skill，但内容收敛为通用问数方法，不再包含脚本和平台工具契约。`opendataworks-business-knowledge` bundled skill 保存 OpenDataWorks 平台术语、本体、指标口径、别名、歧义消解和规则例外。新增 `opendataworks-platform-tools` bundled skill 保存平台能力脚本、CLI、MCP/tool recipes、SQL 验证/执行和工具输出契约。默认运行时启用三个 bundled skills，并通过 `DATAAGENT_PLATFORM_SKILL_ROOT` 暴露平台工具脚本根目录。

## Task 1: Add regression tests first

**Files:**
- `dataagent/dataagent-backend/tests/test_agent_runtime.py`
- `dataagent/dataagent-backend/tests/test_builtin_skill_content.py`
- `dataagent/dataagent-backend/tests/test_skill_admin_service.py`

**Steps:**
1. 修改 system prompt 测试，要求包含四职责面路由原则，不包含 `run_sql.py --database`、`DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1` 等低层命令。
2. 修改 skill 内容测试，分别读取 `dataagent-nl2sql` 和 `opendataworks-business-knowledge`。
3. 增加 `opendataworks-platform-tools` 内容测试，断言平台 tools skill 包含 scripts/bin/tool contracts，且不包含业务口径资产。
4. 增加断言：通用 SQL skill 不再包含业务语义资产，也不包含 scripts/bin 或平台脚本命令模板；业务 skill 包含 terms/ontology/metrics/rules 且没有 scripts/bin。
5. 增加后端 runtime 测试：默认 settings 缺少显式 `skill_runtime` 时，三个 bundled skills 都 enabled，runtime env 注入 `DATAAGENT_PLATFORM_SKILL_ROOT`。
6. 运行聚焦测试，确认先失败。

**Expected Result:**
- 测试失败点对应尚未完成的 platform tools skill 缺失、脚本迁移和 runtime 默认启用策略。

## Task 2: Split bundled skill content and platform tools

**Files:**
- `dataagent/.claude/skills/dataagent-nl2sql/SKILL.md`
- `dataagent/.claude/skills/dataagent-nl2sql/reference/*.md`
- `dataagent/.claude/skills/opendataworks-business-knowledge/SKILL.md`
- `dataagent/.claude/skills/opendataworks-business-knowledge/reference/*.md`
- `dataagent/.claude/skills/opendataworks-business-knowledge/assets/*.json`
- `dataagent/.claude/skills/opendataworks-platform-tools/SKILL.md`
- `dataagent/.claude/skills/opendataworks-platform-tools/reference/*.md`
- `dataagent/.claude/skills/opendataworks-platform-tools/assets/*.json`
- `dataagent/.claude/skills/opendataworks-platform-tools/scripts/*.py`
- `dataagent/.claude/skills/opendataworks-platform-tools/bin/odw-cli`

**Steps:**
1. 新增 `opendataworks-business-knowledge` skill，写清它只提供业务语义，不提供执行脚本。
2. 将术语、本体、指标、语义映射和业务规则资产迁入 business skill。
3. 重写 business skill reference，覆盖术语、指标、规则、本体和歧义消解。
4. 新增 `opendataworks-platform-tools` skill，写清它只提供平台能力、脚本、MCP-first/tool recipes、验证执行和输出契约。
5. 将 `dataagent-nl2sql/scripts`、`bin`、tool assets、tool recipes、runtime metadata 和 tool output contract 迁入 platform tools skill。
6. 重写 `dataagent-nl2sql` 的 `SKILL.md` 和 references，只保留通用问数方法、表字段发现策略、SQL 生成前检查、失败收口和对 platform tools skill 的高层 handoff。
7. 从通用 SQL skill 删除业务语义资产、业务 reference、平台脚本命令模板、MCP tool 清单和 tool output JSON 契约。

**Expected Result:**
- 通用问数 skill 不夹带业务术语或平台脚本；业务 skill 不包含 SQL 执行/验证脚本；platform tools skill 是唯一脚本 fallback 所在 skill。

## Task 3: Simplify system prompt and runtime defaults

**Files:**
- `dataagent/dataagent-backend/core/agent_runtime.py`
- `dataagent/dataagent-backend/core/skill_admin_service.py`
- `dataagent/README.md`

**Steps:**
1. 将 `_build_system_prompt()` 改成高层身份、边界、优先级、三层路由和工作顺序。
2. 使用 `# Role`、`# Primary Goal`、`# Boundaries`、`# Instruction Priority`、`# Workflow`、`# Routing Rules`、`# Output Requirements` 模板化结构组织 system prompt。
3. 移除 system prompt 中的脚本命令模板、lineage fallback、平台表和评测细节。
4. 将 `opendataworks-business-knowledge` 和 `opendataworks-platform-tools` 加入 bundled skill 集合。
5. 调整默认 runtime 归一化逻辑，在没有显式配置或旧配置缺失新 bundled skill 时启用三个内置 skill，primary 保持 `dataagent-nl2sql`。
6. 在 `_build_runtime_env()` 中从 enabled roots 解析 `opendataworks-platform-tools`，注入 `DATAAGENT_PLATFORM_SKILL_ROOT`。
7. 更新 DataAgent README，说明三类内置 skill 和 primary/platform skill root 兼容契约。

**Expected Result:**
- 新部署默认可发现通用问数 skill、业务 knowledge skill 和平台 tools skill；旧 `DATAAGENT_SKILL_ROOT` 继续表示 primary skill；平台脚本统一使用 `DATAAGENT_PLATFORM_SKILL_ROOT`。

## Task 4: Verify and document gaps

**Files:**
- `dataagent/dataagent-backend/tests/*`
- `docs/design/2026-05-15-dataagent-three-layer-prompt-skill-boundary-design.md`
- `docs/plans/2026-05-15-dataagent-three-layer-prompt-skill-boundary-plan.md`

**Steps:**
1. 运行 `pytest tests/test_agent_runtime.py tests/test_builtin_skill_content.py tests/test_skill_admin_service.py`。
2. 如失败，按测试输出修正最小必要代码或文档。
3. 增补运行 `pytest tests/test_skill_discovery.py tests/test_metadata_cli_bridge.py tests/test_odw_cli.py tests/test_build_chart_spec_script.py`，覆盖 enabled skill symlink、平台脚本和 CLI 路径迁移。
4. 检查 `rg` 结果，确认通用 skill 不再保留业务语义资产名、平台指标 reference 或脚本命令模板。
5. 记录是否执行本地 DataAgent HTTP smoke；如果环境不可用，明确说明未覆盖端到端。

**Expected Result:**
- 聚焦单测通过；若未跑 HTTP smoke，最终说明只完成了静态和单元验证。

## Verification

```bash
cd dataagent/dataagent-backend
pytest tests/test_agent_runtime.py tests/test_builtin_skill_content.py tests/test_skill_admin_service.py tests/test_skill_discovery.py tests/test_metadata_cli_bridge.py tests/test_odw_cli.py tests/test_build_chart_spec_script.py
```

## Rollout / Backout

- Rollout：随 DataAgent 后端镜像复制 `dataagent/.claude`，三个 bundled skills 都进入运行时发现目录。
- Backout：回退本变更中的 system prompt、skill 内容拆分、platform tools skill 和 bundled skill 默认启用逻辑；primary `skills_output_dir` 没变，因此回退不涉及配置迁移。
