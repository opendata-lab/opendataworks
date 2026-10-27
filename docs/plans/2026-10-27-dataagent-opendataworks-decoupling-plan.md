# DataAgent 与 OpenDataWorks 解耦实施计划

**Design:** `docs/design/2026-09-14-dataagent-opendataworks-decoupling-design.md`
**Date:** 2026-10-27

涉及两个仓库：

- `opendataworks`：worktree `/Users/guoruping/project/bigdata/opendataworks-dataagent-split`，分支 `feat/dataagent-split`；
- `opendataagent`：`/Users/guoruping/project/bigdata/opendataagent`，新分支 `feat/import-dataagent`（从 `main` 切出）。

## 1. 任务

### 1.1 opendataagent：导入 DataAgent

- 删除仓库原有全部受控文件（保留 `.git`）。
- 从 `opendataworks/dataagent/` 平移 `dataagent-backend`、`dataagent-frontend`、`dataagent-runtime-pi`、`contracts`、`.claude/skills`、`README.md` 到仓库根；不带 `portal-mcp`、`dataagent_runtime/`、`node_modules`、虚拟环境和构建产物。
- 平移 `tools/dataagent-evals/`、根 `tests/test_*dataagent*` 与 `tests/test_deepeval_packaging_hooks.py`，修正其中的相对路径。
- 新建 `deploy/`：`docker-compose.yml`（mysql、redis、dataagent-home-init、dataagent-backend、dataagent-sandbox-runner、dataagent-frontend、log-collector 视原 compose 需要）、`.env.example`、`docker/dataagent/*` 配置示例、`docker/nginx/dataagent-frontend.conf`、MySQL 初始化脚本中 `dataagent` schema 与用户部分。不含 OpenDataWorks 服务和变量。
- 新建 `.github/workflows/docker-build.yml`，构建 `opendataworks-dataagent-frontend/backend/runner` 与两个评测镜像，Dockerfile 路径改为新位置。
- 新建 `AGENTS.md`（`CLAUDE.md` 用 `@AGENTS.md` 引用）：从 opendataworks `AGENTS.md` 带过来通用 Working Rules、Design & Plan Workflow、Backlog、Verification Expectations 中适用部分，以及全部 Intelligent Query 附录；路径改为新布局，删除 OpenDataWorks 相关的冒烟前提（`MYSQL_DATABASE=opendataworks`、`agent_opendataworks`、portal-mcp）。
- 新建 `docs/TODO.md`，承接 opendataworks `docs/TODO.md` 中只属于 DataAgent 的条目（定时消息完善、`ToolOutputRenderer` 既有失败、未跟踪本地 skill 目录等），并新增 Plugin 打包与导入条目。
- `.gitignore`、`.nvmrc`、`.python-version` 按 DataAgent 实际需要重写。

### 1.2 opendataagent：去掉 OpenDataWorks 耦合

按设计第 4、5、6 节：

- 删除 portal bootstrap、默认 MCP、`portal` 专属分支和常量、`DATAAGENT_PORTAL_MCP_*` 配置；
- `permission_gate` 改为按 MCP tool annotations 判断风险；Pi runtime 改为通用 `mcp-client` 命名；
- 身份头 `X-Agent-User-Id`、`X-Agent-Id` 对所有 HTTP MCP 一致附加；
- 删除 `readonly_query_proxy`、datasource resolver、`odw-cli` fallback 与 OpenDataWorks 元数据库/Doris 配置及其测试；
- 删除内置 ODW 智能体 bootstrap，`agent_default` 描述通用化；
- 提示词去掉 OpenDataWorks、Portal MCP、平台脚本；
- 通用内置 Skill 按设计第 3 节筛选与合并，ODW 专属 Skill 从本仓库删除（源码迁往 `odw-skills/`）；
- 会话来源 `portal` → `web`；widget 改名 `DataAgentWidget` / `dataagent-widget.bundle.js`；请求头 `X-ODW-*` → `X-DataAgent-*`；
- 新增 Alembic 迁移（`down_revision` 接当前 head）：`portal` MCP 转普通记录、ODW 内置智能体转普通智能体、会话来源改写与列默认值改为 `web`。

删除要干净：代码、测试、前端调用、文档引用一起删，不留空壳。

### 1.3 opendataworks：MCP server 与 Skill

- `git mv dataagent/portal-mcp odw-mcp-servers/opendataworks`，按设计第 7 节改包名、server name、环境变量、前门 token 头、工具名，补 annotations，更新测试。
- ODW 专属 Skill 迁到 `odw-skills/<原目录名>/`，按设计第 8 节改写为只走 MCP 工具。
- 删除 `dataagent/` 剩余内容（已迁往 opendataagent）、`tools/dataagent-evals/`、对应根 `tests/`。

### 1.4 opendataworks：部署、前端与文档

- `deploy/docker-compose.dev.yml`、`docker-compose.prod.yml`：`portal-mcp` → `odw-mcp-server`；DataAgent 服务去掉 `DATAAGENT_PORTAL_MCP_*`、Doris 与平台库变量、对 `odw-mcp-server` 的依赖和 `../dataagent/.claude/skills` 挂载；`.env.example` 同步。
- `.github/workflows/docker-build.yml`：删除 DataAgent 与评测镜像，`portal-mcp` 改为 `odw-mcp-server`；`npm-publish-sdk.yml` 若依赖 DataAgent 源码，一并迁到 opendataagent。
- 主前端 `dataagentWidgetLoader.js` 及测试、`vite.config.js`、`Dockerfile`、`nginx.conf`、`mockServer.js`、`api/nl2sql.js` 中对 DataAgent 源码路径和旧 widget 名的引用改为新名称。
- `backend` 中 `AgentSettingsController` 等对 DataAgent 路径的引用核对后更新。
- `deploy/README.md` 写接入步骤（设计 Deployment 节）；`README.md`、`website/`、`.gitignore` 更新路径；`AGENTS.md` 删除 DataAgent 附录与 Tech Stack 中 DataAgent 条目，改为指向 opendataagent 仓库。
- `docs/TODO.md`：迁出的条目移到“已完成”并注明“已迁往 opendataagent docs/TODO.md”；新增本次未覆盖项。

## 2. 验证

- opendataagent：
  - `.venv-py313`（不迁移虚拟环境，在新位置重建 Python 3.13 venv 并装 `requirements.txt`）跑全部 pytest；
  - `nvm use` 后 `dataagent-frontend` test 与 build（含 `build:widget`）、`dataagent-runtime-pi` test 与 build；
  - 干净 `dataagent` schema 上 `alembic upgrade head`，启动 `uvicorn main:app`，确认无 ODW MCP/智能体/Skill，`POST /api/v1/nl2sql/topics` 成功，用 `agent_default` 跑一次 `你好，请直接回复 smoke-ok。`（先探测模型账号，不可用就在报告里写明）；
  - 在带旧数据的库上验证新 Alembic 迁移；
  - 静态扫描 `OpenDataWorks|portal|PORTAL_MCP|ODW_|odw_`，只允许出现在迁移脚本与历史文档。
- opendataworks：
  - `odw-mcp-servers/opendataworks` 的 pytest；
  - 主前端 `nvm use` 后 test 与 build；
  - backend `mvn -s <临时 mirror settings> -o -q -pl backend -am test`，失败只允许既有 7 个 DolphinScheduler 用例；
  - `docker compose -f deploy/docker-compose.dev.yml config` 校验；
  - 静态扫描无 `dataagent/` 源码路径与 `portal-mcp` / `portal_` 残留。
- 联调：本地起 backend、`backend-agent-api`、`odw-mcp-server` 与 opendataagent 的 DataAgent，按 `deploy/README.md` 新增 MCP、导入 Skill、建智能体，对真实 PostgreSQL 或 MySQL 数据源问一次数；确认身份头到达、缺账号映射被拒绝。
- 结果写 `opendataworks/docs/reports/2026-10-27-dataagent-opendataworks-decoupling-verification.md`。

## 3. 上线与回退

- 两个仓库同一次发布：先发 opendataagent 镜像，再发 opendataworks（`odw-mcp-server` 与主前端）。
- 回退：opendataworks 回到上一版本镜像与 compose；DataAgent 回到上一版本镜像，并执行新 Alembic 迁移的 downgrade（来源改回 `portal`，记录属性恢复）。
