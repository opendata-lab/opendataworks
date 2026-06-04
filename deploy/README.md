# OpenDataWorks Deployment Guide

This guide covers both Online (source code) and Offline (deployment package) deployment methods.

## Deployment Topology

当前仓库维持两条并行智能体产品线：

- 主前端内嵌的“智能问数”
  - 跟随根 `deploy/` 一起部署
  - 主门户只通过远程 widget JS 嵌入问答入口
  - UI 运行时由 `dataagent-frontend` 提供，API 运行时依赖 `dataagent-backend`
  - 当前仍是生产可用主链
- 独立的 `opendataagent`
  - 使用 [opendataagent/deploy/docker-compose.yml](/Users/guoruping/.codex/worktrees/92ff/opendataworks/opendataagent/deploy/docker-compose.yml) 单独部署
  - 不由主前端内嵌，也不通过主前端菜单跳转
  - 与现有智能问数并行存在，不互相替换

根 `deploy/` 文档只覆盖主门户与现有智能问数链路。`opendataagent` 的部署说明见 [opendataagent/README.md](/Users/guoruping/.codex/worktrees/92ff/opendataworks/opendataagent/README.md)。

## Directory Contents

- `../scripts/start.sh`: Starts the application. Checks for `.env` and creates it if missing.
- `../scripts/stop.sh`: Stops all services.
- `../scripts/restart.sh`: Restarts all services.
- `../scripts/load-images.sh`: Loads Docker images from `docker-images/` (Offline mode).
- `../scripts/create-offline-package.sh`: Utility to generate an offline deployment package.
- `docker-compose.prod.yml`: Production configuration.
- `.env.example`: Template for environment variables.

---

## 1. Online Deployment (From Source)

Use this method if you have internet access and are deploying directly from the source code repository.

### Prerequisites
- Docker and Docker Compose installed.
- Internet access to pull images from Docker Hub.

### Steps
1. **Navigate to deploy directory**:
   ```bash
   cd deploy
   ```

2. **Configure Environment**:
   ```bash
   cp .env.example .env
   # Edit .env for database credentials; DolphinScheduler config is set in System Settings after startup
   vim .env
   ```

3. **Start Services**:
   ```bash
   ../scripts/start.sh
   ```

   主链路默认地址：
   - 门户首页: `http://localhost:8081/`
   - 主前端智能问数入口: `http://localhost:8081/intelligent-query`
   - DataAgent Frontend: `http://localhost:8901/`
   - DataAgent Backend: `http://localhost:8900`
   - Portal MCP Health: `http://localhost:8801/health`
   - Portal MCP Streamable HTTP: `http://localhost:8801/mcp/`

   说明：
   - 大模型供应商、Token 与候选模型在主前端配置页中维护，后端保存到 DataAgent 配置存储。
   - 可直接编辑挂载文件后生效：
     - `dataagent/.claude/skills/`
     - `dataagent/.claude/skills/`（Skills 目录）
   - 动态元数据查询示例在 platform tools skill 的 `reference/` / `scripts/` 中，不再由后端同步生成 metadata 快照
   - OpenDataWorks 内部部署默认 MCP-first：DataAgent runtime 会向当前 run 动态注入 `portal-mcp`，优先直接调用 `portal_search_tables` / `portal_get_lineage` / `portal_resolve_datasource` / `portal_export_metadata` / `portal_get_table_ddl` / `portal_query_readonly`
   - 非 MCP 智能体或 MCP 未注入时，DataAgent 才回退到 platform tools skill 自带的 `opendataworks-platform-tools/bin/odw-cli` 调 backend `/api/v1/ai/*` 只读入口获取 metadata / lineage / datasource 解析，并通过 `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/run_sql.py" -> odw-cli -> /api/v1/ai/query/read` 执行只读 SQL；需保证 `AGENT_API_SERVICE_TOKEN` 在 backend 与 DataAgent 容器中一致
   - `ODW_BACKEND_BASE_URL` 的推荐值为 `http://backend:8080/api/v1/ai`；CLI 兼容旧值 `/api/v1/ai/metadata`，但部署默认值已切到 AI 根路径
   - DataAgent 的 MCP client 配置由 `DATAAGENT_PORTAL_MCP_ENABLED`、`DATAAGENT_PORTAL_MCP_BASE_URL`、`DATAAGENT_PORTAL_MCP_TOKEN`、`DATAAGENT_PORTAL_MCP_TOKEN_HEADER_NAME` 控制；默认值已在 compose 中接到 `portal-mcp`
   - skill/runtime 不再需要外部数据源的 host / port / user / password；datasource 解析结果只保留定位摘要
   - 若对应 skill 目录下缺少 `opendataworks-platform-tools/bin/odw-cli`，需由用户先自行安装到该固定路径，再启动 DataAgent
   - `scripts/start.sh` 会在启动前对挂载的 `odw-cli` 执行一次宿主机侧 `chmod +x`；即使 bind mount 丢了执行位，DataAgent runtime 也会回退为 `sh /app/.claude/skills/opendataworks-platform-tools/bin/odw-cli ...`
   - `portal-mcp` 是 DataAgent 当前默认主链路的远程 MCP 入口，默认通过 `X-Portal-MCP-Token` 访问；它调用 backend `/api/v1/ai/metadata/*` 与 `/api/v1/ai/query/read`
   - `portal-mcp` 继续随根部署提供，但它不是 `opendataagent` 共享平台 skill 的主链入口
   - `opendataagent` 不随这里的 compose 自动启动，需要单独进入 `opendataagent/deploy/` 部署
   - `skills/` 根目录中的共享 skill 主要服务 `opendataagent`；当前生产智能问数主链使用 DataAgent system prompt、`opendataworks-business-knowledge` 与 `opendataworks-platform-tools`
   - 主前端默认通过同源 `/dataagent/widget/opendataworks-widget.bundle.js` 加载 DataAgent widget，并通过 `/api/v1/nl2sql/*` 代理访问 DataAgent 后端；若需要改成独立域名，源码构建主前端时设置 `VITE_DATAAGENT_WIDGET_JS_URL`
   - DataAgent 额外持久化宿主目录 `DATAAGENT_HOME_HOST_DIR`（默认 `/tmp/dataagent-home`）。当前 topic 级工作区默认位于 `/tmp/dataagent-home/.dataagent/runtime/topics/<topic_id>/`，Claude SDK session 文件落在该 topic 根目录的 `.claude/projects/<sanitized-cwd>/` 下；同一 topic 多轮复用该目录，不同 topic 不共享 `.claude` 状态
   - `dataagent-backend` 与 `dataagent-sandbox-runner` 采用 master/worker 形态：backend 负责 topic/task 协调，runner 使用独立 `opendataworks-dataagent-runner` 镜像负责执行入口；设置 `DATAAGENT_SANDBOX_MODE` 后，backend 会把任务流式委托给 runner，runner 再启动每 task child 容器，并只把当前 topic 目录挂到 child 的 `/workspace`
   - 每个 task child 容器都是一次性容器，runner 使用 `--rm` 启动；正常结束自动删除，取消或异常时 runner 会 kill child，并在启动时清理带 `dataagent.sandbox.managed_by=dataagent-sandbox-runner` 标签的遗留 child 容器
   - `DATAAGENT_DOCKER_SOCKET` 只挂到 `dataagent-sandbox-runner`，不会挂到 task child 容器；runner 默认用 `DATAAGENT_RUNNER_UID/GID=0:0` 访问 Docker socket，child task 仍用 `DATAAGENT_RUNTIME_UID/GID` 运行；若手动删除 `DATAAGENT_HOME_HOST_DIR`，Claude SDK 本地 session 文件和 topic 工作区都会被清空，此时旧话题会退回到“重放历史 prompt”的兼容路径，直到该话题再次跑出新的真实 SDK session id

   > **💡 数据库自动初始化**: MySQL 容器首次启动时，会自动执行 `deploy/database/mysql/` 目录下的初始化脚本，创建 `opendataworks` / `dataagent` 数据库，并分别初始化 `opendataworks`、`dataagent` 两个应用用户。DataAgent 容器启动时会先执行 `alembic upgrade head`，再启动服务。
   >
   > 若保留旧的 `mysql-data` volume 升级，初始化脚本不会重跑；切换到独立 `dataagent` 用户前，需要先手动补建该用户或清空 volume 重新初始化。
   >
   > DataAgent 在 `docker-compose.prod.yml` 中默认以非 root 用户运行（`DATAAGENT_RUNTIME_UID/GID`，默认 `1000:1000`）。若 `dataagent/.claude/skills/` 无法写入，请把这两个值改成宿主机目录拥有者的 UID/GID，或先调整目录权限。

---

## 2. Offline Deployment (Using Package)

Use this method for isolated environments without internet access. You will use the `opendataworks-deployment-*.tar.xz` package.

### Prerequisites
- Docker or Podman installed on the target machine.
- `xz`/`xz-utils` installed on the target machine (used to decompress the package).
- The offline deployment package (`opendataworks-deployment-*.tar.xz`).

### Steps
1. **Extract Package**:
   ```bash
   # 新版离线包为 xz 压缩
   tar -xJf opendataworks-deployment-*.tar.xz
   # 若某些精简系统的 tar 未链接 xz，可改用管道：
   #   xz -dc opendataworks-deployment-*.tar.xz | tar -xf -
   cd opendataworks-deployment
   ```

2. **Load Images**:
   This loads all required Docker images from the local archive. 新版离线包将全部镜像去重保存为单个 `deploy/docker-images/all-images.tar`，加载脚本会自动识别（旧版逐镜像 `*.tar` 也兼容）。
   ```bash
   scripts/load-images.sh
   ```

3. **Configure Environment**:
   ```bash
   cp deploy/.env.example deploy/.env
   # Edit .env and configure settings
   vim deploy/.env
   ```

4. **Start Services**:
   ```bash
   scripts/start.sh
   ```

   离线包中的主链地址：
   - 门户首页: `http://localhost:8081/`
   - 主前端智能问数入口: `http://localhost:8081/intelligent-query`
   - DataAgent Frontend: `http://localhost:8901/`
   - DataAgent Backend: `http://localhost:8900`
   - Portal MCP Health: `http://localhost:8801/health`
   - Portal MCP Streamable HTTP: `http://localhost:8801/mcp/`

   说明：
   - 离线包内保留 `deploy/dataagent-runtime/skills/` 可直接编辑。
   - 大模型供应商、Token 与候选模型仍通过主前端配置页管理。
   - 离线包内保留 `deploy/dataagent-runtime/skills/` 可直接编辑
   - 大模型供应商、Token 与候选模型仍通过主前端配置页管理
   - 动态元数据查询示例保留在 platform tools skill 的 `reference/` / `scripts/` 中
   - OpenDataWorks 内部部署默认 MCP-first：DataAgent runtime 会向当前 run 动态注入 `portal-mcp`，优先直接调用 `portal_search_tables` / `portal_get_lineage` / `portal_resolve_datasource` / `portal_export_metadata` / `portal_get_table_ddl` / `portal_query_readonly`
   - 非 MCP 智能体或 MCP 未注入时，DataAgent 才回退到 platform tools skill 自带的 `opendataworks-platform-tools/bin/odw-cli` 调 backend `/api/v1/ai/*` 只读入口获取 metadata / lineage / datasource 解析，并通过 `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/run_sql.py" -> odw-cli -> /api/v1/ai/query/read` 执行只读 SQL；需保证 `AGENT_API_SERVICE_TOKEN` 在 backend 与 DataAgent 容器中一致
   - `ODW_BACKEND_BASE_URL` 的推荐值为 `http://backend:8080/api/v1/ai`；CLI 兼容旧值 `/api/v1/ai/metadata`，但部署默认值已切到 AI 根路径
   - DataAgent 的 MCP client 配置由 `DATAAGENT_PORTAL_MCP_ENABLED`、`DATAAGENT_PORTAL_MCP_BASE_URL`、`DATAAGENT_PORTAL_MCP_TOKEN`、`DATAAGENT_PORTAL_MCP_TOKEN_HEADER_NAME` 控制；默认值已在 compose 中接到 `portal-mcp`
   - skill/runtime 不再需要外部数据源的 host / port / user / password；datasource 解析结果只保留定位摘要
   - 若对应 skill 目录下缺少 `opendataworks-platform-tools/bin/odw-cli`，需由用户先自行安装到该固定路径，再启动 DataAgent
   - `scripts/start.sh` 会在启动前对挂载的 `odw-cli` 执行一次宿主机侧 `chmod +x`；即使 bind mount 丢了执行位，DataAgent runtime 也会回退为 `sh /app/.claude/skills/opendataworks-platform-tools/bin/odw-cli ...`
   - `portal-mcp` 作为独立远程 MCP 服务一并部署，客户端需带 `X-Portal-MCP-Token`
   - `opendataagent` 需要用它自己的部署包或 compose 单独部署，不包含在这里的离线包主链描述中
   - DataAgent 额外持久化宿主目录 `DATAAGENT_HOME_HOST_DIR`（默认 `/tmp/dataagent-home`）。当前 topic 级工作区默认位于 `/tmp/dataagent-home/.dataagent/runtime/topics/<topic_id>/`，Claude SDK session 文件落在该 topic 根目录的 `.claude/projects/<sanitized-cwd>/` 下；同一 topic 多轮复用该目录，不同 topic 不共享 `.claude` 状态
   - `dataagent-backend` 与 `dataagent-sandbox-runner` 采用 master/worker 形态：backend 负责 topic/task 协调，runner 使用独立 `opendataworks-dataagent-runner` 镜像负责执行入口；设置 `DATAAGENT_SANDBOX_MODE` 后，backend 会把任务流式委托给 runner，runner 再启动每 task child 容器，并只把当前 topic 目录挂到 child 的 `/workspace`
   - 每个 task child 容器都是一次性容器，runner 使用 `--rm` 启动；正常结束自动删除，取消或异常时 runner 会 kill child，并在启动时清理带 `dataagent.sandbox.managed_by=dataagent-sandbox-runner` 标签的遗留 child 容器
   - `DATAAGENT_DOCKER_SOCKET` 只挂到 `dataagent-sandbox-runner`，不会挂到 task child 容器；runner 默认用 `DATAAGENT_RUNNER_UID/GID=0:0` 访问 Docker socket，child task 仍用 `DATAAGENT_RUNTIME_UID/GID` 运行；若手动删除 `DATAAGENT_HOME_HOST_DIR`，Claude SDK 本地 session 文件和 topic 工作区都会被清空，此时旧话题会退回到“重放历史 prompt”的兼容路径，直到该话题再次跑出新的真实 SDK session id

   > **💡 数据库自动初始化**: MySQL 容器首次启动时，会自动执行 `deploy/database/mysql/` 目录下的初始化脚本，创建 `opendataworks` / `dataagent` 数据库，并分别初始化 `opendataworks`、`dataagent` 两个应用用户。DataAgent 容器启动时会先执行 `alembic upgrade head`，再启动服务。
   >
   > 若保留旧的 `mysql-data` volume 升级，初始化脚本不会重跑；切换到独立 `dataagent` 用户前，需要先手动补建该用户或清空 volume 重新初始化。
   >
   > 离线包中的 DataAgent 也默认以非 root 用户运行（`DATAAGENT_RUNTIME_UID/GID`，默认 `1000:1000`）。若 `deploy/dataagent-runtime/skills/` 无法写入，请把这两个值改成目标机器目录拥有者的 UID/GID，或先调整目录权限。

---

## 3. DataAgent Online Evaluation

离线包内置 DataAgent 通用问数评测工具，部署完成并配置可用模型后，可手动运行在线评测。builtin 与 DeepEval 是两个并列评测引擎，均位于离线包根目录 `tools/dataagent-evals/`，且均独立于 DataAgent runtime。私有评测集不随 GitHub 或离线包内置，运行时必须通过 `--dataset` 或 `DATAAGENT_EVAL_DATASET` 指定。

```bash
DATAAGENT_EVAL_JUDGE_BASE_URL=https://api.example.com \
DATAAGENT_EVAL_JUDGE_TOKEN=... \
DATAAGENT_EVAL_JUDGE_MODEL=claude-opus-4-6 \
bash scripts/run-dataagent-evals.sh --base-url http://127.0.0.1:8900 --dataset /path/to/private-cases.jsonl
```

评测集由部署人员手动放置并通过 `--dataset` 指定。评测脚本位于离线包根目录，不放入 `deploy/dataagent-runtime/`。

builtin 评测镜像为 `opendataworks-dataagent-evals-builtin:<tag>`，由 `scripts/run-dataagent-evals.sh` 默认调用。若只做本地 dry-run，可设置 `DATAAGENT_BUILTIN_RUN_LOCAL=1`。

常用参数：

```bash
# 只校验评测集与报告目录，不调用服务
DATAAGENT_BUILTIN_RUN_LOCAL=1 bash scripts/run-dataagent-evals.sh --dry-run --dataset /path/to/private-cases.jsonl

# 只跑指定用例
bash scripts/run-dataagent-evals.sh --dataset /path/to/private-cases.jsonl --case CASE_ID

# 覆盖 DataAgent 执行模型，并独立配置 judge 模型
DATAAGENT_EVAL_JUDGE_BASE_URL=https://api.example.com \
DATAAGENT_EVAL_JUDGE_TOKEN=... \
DATAAGENT_EVAL_JUDGE_MODEL=claude-opus-4-6 \
bash scripts/run-dataagent-evals.sh --dataset /path/to/private-cases.jsonl --provider-id openrouter --model anthropic/claude-sonnet-4.5
```

输出目录默认为当前离线包目录下的 `reports/dataagent-evals/<timestamp>/`。Docker/Podman 模式会把包目录挂载为 `/workspace`，默认输出会持久化回宿主机包目录而不是容器临时文件系统。输出包含：

- `cases.jsonl`: 每条用例的执行、证据抽取和裁判结果
- `summary.json`: 准入指标、阈值和上线建议
- `report.md`: Markdown 评测报告
- `raw/<case_id>.json`: 单用例原始明细

评测脚本通过真实 DataAgent HTTP 任务链路执行问题，并调用独立配置的 judge 模型完成 10 分制打分。DataAgent backend 不暴露评测路由，也不承载评测裁判逻辑；judge token 仅通过运行时参数或环境变量传入，不写入镜像和离线包。

### DeepEval Parallel Evaluation

离线包也包含 DeepEval 并行评测镜像 `opendataworks-dataagent-evals-deepeval:<tag>`，用于和 builtin 评测结果横向比对。两个评测镜像都只包含评测工具，不包含私有评测集或 DataAgent backend，也不会随 `load-package-and-start.sh` 默认启动。

运行示例：

```bash
DATAAGENT_EVAL_JUDGE_BASE_URL=https://api.example.com \
DATAAGENT_EVAL_JUDGE_TOKEN=... \
DATAAGENT_EVAL_JUDGE_MODEL=claude-opus-4-6 \
bash scripts/run-dataagent-deepeval-evals.sh --base-url http://127.0.0.1:8900 --dataset /path/to/private-cases.jsonl
```

DeepEval 评测同样要求通过 `--dataset` 指定私有 JSONL，输出包含 `cases.jsonl`、`summary.json`、`report.md` 和 `raw/<case_id>.json`。

---

## Common Operations

### Stop Services
```bash
# Online (from root)
scripts/stop.sh
# Offline (from package root)
scripts/stop.sh
```

### Restart Services
```bash
# Online (from root)
scripts/restart.sh
# Offline (from package root)
scripts/restart.sh
```

### Check Logs
```bash
# View logs for a specific service (e.g., backend)
docker-compose -f docker-compose.prod.yml logs -f backend
```
