# DataAgent Config Consolidation Plan

## Goal

Consolidate DataAgent deployment configuration so `.env` exposes only the
host-owned runtime root, skills source, runner image, and shared Portal MCP
token settings. Keep service-internal values in Compose instead of requiring
operators to configure equivalent variables twice.

## Tasks

1. Backend settings and path code.
   - Replace the old externally named sandbox root settings with
     `DATAAGENT_HOST_ROOT` for host-side runner binds.
   - Use fixed container runtime root `/dataagent_runtime` for topic
     `workspace/`, `home/`, and `logs/` layout.
   - Remove explicit sandbox host skills setting; have the runner use the
     discovered `/app/.claude/skills` mount source for child binds.

2. Images and runtime defaults.
   - Set backend and runner image `HOME` to `/dataagent_runtime`.
   - Create `/dataagent_runtime` and `/app/.claude/skills` at image build time.
   - Do not publish a container runtime-root environment knob.

3. Compose and `.env`.
   - Add `DATAAGENT_HOST_ROOT=/dataagent_runtime` to `.env.example`.
   - Mount `${DATAAGENT_HOST_ROOT:-/dataagent_runtime}:/dataagent_runtime`.
   - Derive internal sandbox image from `OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE`.
   - Keep sandbox runner URL and sandbox network as fixed internal Compose
     values.
   - Replace duplicated Portal MCP token variables with
     `PORTAL_MCP_TOKEN` and `PORTAL_MCP_TOKEN_HEADER_NAME`.

4. Startup and offline package scripts.
   - Remove startup script write-back for the old sandbox host skills variable.
   - Keep only `DATAAGENT_SKILLS_DIR` resolution for the mounted skills source.
   - Make offline packaging emit only
     `OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE` and `DATAAGENT_SKILLS_DIR`.

5. Tests and docs.
   - Update focused DataAgent tests for `/dataagent_runtime` and the
     auto-discovered skills mount source.
   - Add package/deploy regression checks for the consolidated variable set.
   - Update `deploy/README.md` and the paired design document.

## Verification

Run:

```bash
dataagent/dataagent-backend/.venv-py313/bin/python -m pytest \
  dataagent/dataagent-backend/tests/test_topic_workspace.py \
  dataagent/dataagent-backend/tests/test_topic_files.py \
  dataagent/dataagent-backend/tests/test_sandbox_runner_main.py \
  dataagent/dataagent-backend/tests/test_task_executor.py \
  dataagent/dataagent-backend/tests/test_runner_dockerfile.py \
  dataagent/dataagent-backend/tests/test_agent_runtime.py \
  tests/test_deepeval_packaging_hooks.py -q
docker compose -f deploy/docker-compose.dev.yml config
docker compose -f deploy/docker-compose.prod.yml config
rg "DATAAGENT_HOME_HOST_DIR|DATAAGENT_SANDBOX_ROOT|DATAAGENT_SANDBOX_HOST_ROOT|DATAAGENT_SANDBOX_HOST_SKILLS_DIR|DATAAGENT_SANDBOX_IMAGE=|/workspaces" deploy dataagent/dataagent-backend scripts
```

When local Docker/MySQL/Redis and model credentials are available, follow with a
DataAgent smoke run through the real HTTP entrypoint and verify topic files land
under `/dataagent_runtime/<topic_id>/workspace`, `home`, and `logs`.

## Backout

Revert the settings, Compose, image, startup script, package script, tests, and
docs in this change. Deployments that already moved data to
`DATAAGENT_HOST_ROOT` would need the previous bind path restored before rollback.

## 2026-10-16 follow-up: 默认优先

1. 保存现有 dev/prod Compose 展开快照；仅在临时受限文件保存凭据，用于本地值
   前后比较。复用本主题设计，不覆盖前一轮运行预算修改。
2. 精简 deploy/.env.example，只保留常规安装项，部署可选项使用注释；删除未生效
   的 dev 子网、代码默认策略及废弃设置。
3. 两套 Compose 共用 x-dataagent-environment，移除重复默认和前端 env_file；
   Spring DB 从 MYSQL_* 推导且保留外部 DB 覆盖，修复 MySQL 健康检查密码来源。
4. 删除 settings.json / DATAAGENT_LLM_JSON_FILE 离线制品遗留和审批等待设置。
   更新启动提示及部署/运维文档，明确原生高级字段与旧前缀迁移。
5. 验证默认和非默认的真实 Compose 展开；更新离线归档回归，校验打包后的模板
   与服务接线；运行相关 Python 测试、bash -n、git diff --check。
6. 对实际 deploy/.env 仅清理等价重复和确认无消费者的字段，保存 0600 临时备份；
   不改凭据或重启运行中的服务。写验证报告，说明未部署/未运行的新覆盖边界。
7. 回退本阶段的 env/Compose/script/doc 修改即可，数据卷不变；原本地 .env 可从
   临时备份恢复。保留前一阶段预算修复。

本阶段已完成；规模、配置迁移和验证边界见
[2026-10-16 验证报告](../reports/2026-10-16-deploy-config-consolidation-validation.md)。
