# DataAgent Skills Directory Out of .claude Plan

**Date:** 2026-09-15
**Design:** [2026-09-15-dataagent-skills-dir-out-of-dot-claude-design.md](../design/2026-09-15-dataagent-skills-dir-out-of-dot-claude-design.md)

## Tasks

### T1 目录迁移

- `git mv dataagent/.claude/skills dataagent/skills`（保留历史）
- 迁空后删除 `dataagent/.claude/`
- 确认 `git status` 显示为 rename 而非 delete+add，否则 7 个 skill 的历史会断

### T2 .gitignore 收敛

- 删除 `.gitignore:47-65` 全部反向放行规则，保留 `:46` 的全局 `.claude/`
- 私有业务 skill 资产排除规则改按资产类型在 `dataagent/skills/` 下表达
- `tests/test_deepeval_packaging_hooks.py:108-117` 同步：
  - `"/dataagent/.claude/skills/*/"` 与 `"!/dataagent/.claude/skills/dataagent-nl2sql/"` 两条断言删除
    （后者锁的是已删除 skill，本就是残留）
  - 改为断言私有资产在新路径下仍被排除、且 `create-offline-package.sh` 的 `--exclude` 不变

### T3 发现根校验放宽

- `dataagent/dataagent-backend/core/skill_discovery.py:40-41`
  - 去掉 `root.parent.name != ".claude"` 条件，保留 `root.name != "skills"` 与 `is_dir()` 校验
  - 同步错误文案

### T4 消除路径形状嗅探

- `dataagent/dataagent-backend/core/agent_runtime.py`
  - `skill_runtime` 字典新增 `discovery_root`，在 `:555` 构造点写入
  - `core/skill_admin_service.py:1639` 构造点同步写入
  - env 构建改为直接读取该字段，删除 `_skills_dir_from()`（`:623-634`）
  - 未携带该字段时回落到 `primary_root` 的父目录，保持「SKILLS_ROOT_DIR 未配置时不抛错」的既有行为

### T5 skills_output_dir 兼容与迁移

- `core/skill_admin_service.py:621-622` 校验接受 `.claude/skills/<folder>` 与 `skills/<folder>`
- `core/skill_admin_service.py:44` `DEFAULT_SKILLS_OUTPUT_DIR` 改为 `../skills/<folder>`
- `dataagent/dataagent-backend/config.py:83` 默认值同步改为 `../skills/opendataworks-business-knowledge`
- 新增 Alembic 迁移：`da_agent_settings.skills_output_dir` 的 `.claude/skills/` 前缀重写为 `skills/`
  - downgrade 反向重写

### T6 部署与脚本

- `deploy/.env.example:155-156` 默认值与注释
- `deploy/docker-compose.dev.yml:211,268`、`deploy/docker-compose.prod.yml:235,294` 挂载源端默认值
  - **目标端 `/app/.claude/skills` 与 `SKILLS_ROOT_DIR` 保持不变**
- `scripts/start.sh:123` 内联默认值
- `scripts/create-offline-package.sh` 源目录路径（改写后的目标值 `./dataagent-runtime/skills` 不变）
- `dataagent/dataagent-backend/tests/test_runner_dockerfile.py:18,35` 不改：断言的是容器内路径

### T7 活文档

只更新描述**当前状态**的文档；`docs/design/` 与 `docs/plans/` 下的历史文档保持原样，它们记录的是各自日期的状态。

- `AGENTS.md:18`（本次已改过的那行）、`:233` 的 `SKILLS_ROOT_DIR=<repo>/dataagent/.claude/skills`
- `dataagent/README.md:17,18,22`
- `deploy/README.md`
- `docs/handbook/architecture.md:65`（顺带：该行仍引用已删除的 `dataagent-nl2sql`）
- `tools/dataagent-evals/opik/README.md:30`

## Verification

- `pytest tests/test_skill_discovery.py tests/test_skill_admin_service.py tests/test_skill_admin_store.py tests/test_agent_runtime.py tests/test_admin_routes.py tests/test_builtin_skill_content.py tests/test_sandbox_runner_main.py tests/test_topic_files.py tests/test_widget_runtime_routes.py tests/test_runner_dockerfile.py`
  （解释器 `dataagent/dataagent-backend/.venv-py313`）
- 仓库根 `pytest tests/test_deepeval_packaging_hooks.py`
- `alembic upgrade head` 后确认 `da_agent_settings.skills_output_dir` 无 `.claude` 前缀残留，再 `downgrade` 验证可逆
- `git log --follow dataagent/skills/opendataworks-platform-tools/SKILL.md` 确认历史未断
- 全仓检索：`rg "dataagent/\.claude"`，预期仅命中历史 design/plan 文档
- 本地 intelligent-query 端到端冒烟（AGENTS.md「Intelligent Query local smoke method」）：
  - 本变更改的是 skill 发现根，targeted 测试无法覆盖「skill 是否真的被发现并复制进 topic workspace」
  - 必须确认 `prepare_topic_workspace` 把启用 skill 落到 `<workspace>/.claude/skills/`（该路径不变）
  - 显式设 `DATAAGENT_RUNTIME_KIND`（默认 `pi_agent_core` 才是生产路径），
    `SKILLS_ROOT_DIR=<repo>/dataagent/skills`，提交带 `execution_mode="auto"`
  - 推荐 prompt：`最近 30 天工作流发布次数趋势`

## Rollout

单次提交完成 T1-T7。目录迁移与所有路径引用必须同时切换。

升级说明需写入 `deploy/README.md`：已在 `.env` 中显式设置
`DATAAGENT_SKILLS_DIR=../dataagent/.claude/skills` 的部署，升级时必须改为 `../dataagent/skills`。
按设计不提供静默双路径回落，启动期以明确报错暴露。

## Backout

`git revert` 单个提交 + `alembic downgrade`。
若已有部署完成升级，回滚需同步把 `.env` 中的 `DATAAGENT_SKILLS_DIR` 改回旧路径。

## Out of Scope（后续处理）

- `dataagent/skills/ontology-modeling-assistant/SKILL.md:47-50` 使用裸 `python3` + 仓库相对路径调用脚本，
  既违反 AGENTS.md 的调用契约，也会因本次迁移失效。归入
  [锚点统一变更](2026-09-15-dataagent-skill-invocation-anchor-unification-plan.md) 一并修正。
