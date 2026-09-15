# DataAgent Skill Invocation Anchor Unification Plan

**Date:** 2026-09-15
**Design:** [2026-09-15-dataagent-skill-invocation-anchor-unification-design.md](../design/2026-09-15-dataagent-skill-invocation-anchor-unification-design.md)

## Tasks

### T1 运行时停止注入

- `dataagent/dataagent-backend/core/agent_runtime.py`
  - 删除 `platform_skill_root` 计算（657-661）与 `DATAAGENT_PLATFORM_SKILL_ROOT` 注入（699-700）
  - 保留 `PLATFORM_TOOLS_SKILL_FOLDER` 常量：`:245` 与 `skill_admin_service` 的启用逻辑仍在用
  - 同步更新 `_build_*_env` docstring（643-651）中列出的契约变量
- `dataagent/dataagent-backend/sandbox_runner_main.py`
  - 删除子容器注入（414-415）；`_FORWARDED_ENV_KEYS` 无需改动（本就不含该键）
  - 若 `PLATFORM_TOOLS_SKILL_FOLDER` import 变为未使用则一并清理（`:445` 仍在用，预期保留）
- `dataagent/dataagent-runtime-pi/src/tools/tool-registry.ts`
  - 从透传白名单（`:46`）移除该键

### T2 脚本内部解析收敛为单层

- `dataagent/.claude/skills/opendataworks-platform-tools/scripts/_opendataworks_runtime.py`
  - `skill_root_dir()` 三层回落收敛为 `Path(__file__).resolve().parents[1]`
- `dataagent/.claude/skills/opendataworks-methodology-dag/scripts/engine.py`
  - `_platform_skill_candidates()` 移除 env 覆盖候选与 `PLATFORM_TOOLS_ROOT_ENV` 常量，保留同级目录解析
  - 同步更新该函数 docstring 中关于「optional override」的描述

### T3 命令模板统一

统一为 `"$DATAAGENT_PYTHON_BIN" "${SKILLS_ROOT_DIR}/opendataworks-platform-tools/scripts/<name>.py" ...`：

- `dataagent/.claude/skills/opendataworks-platform-tools/SKILL.md`（`:4` compatibility、`:43-44`）
- `dataagent/.claude/skills/opendataworks-platform-tools/reference/30-tool-recipes.md`（`:7`、`:15`、`:46`、`:53`、`:60`、`:67`、`:75-76`、`:88`、`:99`）
- `dataagent/.claude/skills/opendataworks-platform-tools/reference/40-runtime-metadata.md`（`:22`、`:35-36`）
- `dataagent/.claude/skills/opendataworks-methodology-dag/SKILL.md`（`:24`）
- `dataagent/.claude/skills/opendataworks-methodology-dag/reference/30-invocation.md`（`:112` 环境变量表）
- `dataagent/dataagent-backend/prompts/data_agent_system_prompt.md`（`:92` export_query 模板）
- `dataagent/README.md`（`:22`）
- `deploy/README.md`（`:75`、`:160`）
- `AGENTS.md`（`:154` 变量清单、`:156` 平台工具专属形式整条删除）

### T4 测试契约

- `dataagent/dataagent-backend/tests/test_builtin_skill_content.py`
  - 将 `test_generic_presentation_skills_use_shared_skills_dir_anchor` 的覆盖面扩展到
    `opendataworks-platform-tools` 与 `opendataworks-methodology-dag`
  - 新增断言：`DATAAGENT_PLATFORM_SKILL_ROOT` 不出现在任何 skill 文档与脚本中
  - `:113`、`:497` 的 forbidden_tokens 清单据此复核
- `dataagent/dataagent-backend/tests/test_agent_runtime.py`
  - `:51`、`:117` 改为断言该键**不存在**于 runtime_env
  - `:53-54` 关于 `SKILLS_ROOT_DIR` 与 `DATAAGENT_SKILL_ROOT` 的断言保留
- `dataagent/dataagent-backend/tests/test_sandbox_runner_main.py`
  - `:1197-1201` 改为断言子容器命令中不含该键，`SKILLS_ROOT_DIR` 断言保留
- `dataagent/dataagent-backend/tests/test_metadata_cli_bridge.py`
  - `:214`、`:228` 改为覆盖单层 `__file__` 解析路径
- `dataagent/dataagent-runtime-pi/test/tool_env.test.ts`
  - `:24`、`:42` 移除该键的透传断言

## Verification

- `pytest tests/test_builtin_skill_content.py tests/test_agent_runtime.py tests/test_sandbox_runner_main.py tests/test_metadata_cli_bridge.py`
  （解释器用 `dataagent/dataagent-backend/.venv-py313`）
- `dataagent-runtime-pi`：`npm run build && npm test`
- 全仓检索确认无残留：`rg DATAAGENT_PLATFORM_SKILL_ROOT`，预期仅命中本设计与计划文档
- 本地 intelligent-query 端到端冒烟（AGENTS.md「Intelligent Query local smoke method」）：
  - 因为本变更改的是模型实际执行的命令模板，targeted 测试不足以证明可用
  - 必须走真实 NL2SQL 路径（推荐 prompt：`最近 30 天工作流发布次数趋势`），确认平台工具脚本仍被成功调起
  - 需显式设置 `DATAAGENT_RUNTIME_KIND`：默认 `pi_agent_core` 才是生产路径
  - 提交请求时带 `execution_mode="auto"`，避免落到 360s 的 interactive 档

## Rollout

单次提交完成 T1-T4。不可分批：文档模板与运行时注入必须同时切换，否则模型会拿到指向未注入变量的命令。

## Backout

`git revert` 单个提交即可。该变量非部署可配置项（`deploy/*.yml` 从不设置它），
回滚不需要任何环境或数据变更。

## Out of Scope（后续可选）

- `core/agent_runtime.py:245-248` 的 workspace 边界 sibling 推导与 `:237-239`
  的 `enabled_roots` 遍历重复，属同类冗余，但作用于文件访问边界而非调用契约，另开变更处理。
