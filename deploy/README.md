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
   后端主库连接通过 `.env` 中的 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 注入。默认值指向本 compose 内的 `mysql:3306/opendataworks`，如连接外部 MySQL，请同步调整这三个变量。

3. **Start Services**:
   ```bash
   ../scripts/start.sh
   ```

   主链路默认地址：
   - 门户首页: `http://localhost:8081/`
   - 主前端智能问数入口: `http://localhost:8081/intelligent-query`
   - DataAgent Frontend: `http://localhost:8901/chat`
   - DataAgent Backend: `http://localhost:8900`
   - Portal MCP Health: `http://localhost:8801/health`
   - Portal MCP Streamable HTTP: `http://localhost:8801/mcp/`

   说明：
   - 大模型供应商、Token 与候选模型在主前端配置页中维护，后端保存到 DataAgent provider registry。
   - 可直接编辑挂载文件后生效：
     - `dataagent/.claude/skills/`
     - `dataagent/.claude/skills/`（Skills 目录）
   - 动态元数据查询示例在 platform tools skill 的 `reference/` / `scripts/` 中，不再由后端同步生成 metadata 快照
   - OpenDataWorks 内部部署默认 MCP-first：DataAgent runtime 会向当前 run 动态注入 `portal-mcp`，优先直接调用 `portal_search_tables` / `portal_get_lineage` / `portal_resolve_datasource` / `portal_export_metadata` / `portal_get_table_ddl` / `portal_query_readonly`
   - 非 MCP 智能体或 MCP 未注入时，DataAgent 才回退到 platform tools skill 自带的 `opendataworks-platform-tools/bin/odw-cli` 调 backend `/api/v1/ai/*` 只读入口获取 metadata / lineage / datasource 解析，并通过 `"$DATAAGENT_PYTHON_BIN" "${SKILLS_ROOT_DIR}/opendataworks-platform-tools/scripts/run_sql.py" -> odw-cli -> /api/v1/ai/query/read` 执行只读 SQL；需保证 `AGENT_API_SERVICE_TOKEN` 在 backend 与 DataAgent 容器中一致
   - `ODW_BACKEND_BASE_URL` 的推荐值为 `http://backend:8080/api/v1/ai`；CLI 兼容旧值 `/api/v1/ai/metadata`，但部署默认值已切到 AI 根路径
   - `DATAAGENT_PORTAL_MCP_ENABLED`、`DATAAGENT_PORTAL_MCP_BASE_URL` 与 `PORTAL_MCP_TOKEN` 只在 registry 中尚无 `portal` 行时作为一次性 bootstrap 输入；导入后 DataAgent runtime 只读 `da_mcp_server`，页面禁用或修改不会被重启时的环境变量覆盖。`PORTAL_MCP_TOKEN_HEADER_NAME` 仍同时定义 frontdoor 与初始 client header
   - skill/runtime 不再需要外部数据源的 host / port / user / password；datasource 解析结果只保留定位摘要
   - 若对应 skill 目录下缺少 `opendataworks-platform-tools/bin/odw-cli`，需由用户先自行安装到该固定路径，再启动 DataAgent
   - `scripts/start.sh` 会在启动前对挂载的 `odw-cli` 执行一次宿主机侧 `chmod +x`；即使 bind mount 丢了执行位，DataAgent runtime 也会回退为 `sh /app/.claude/skills/opendataworks-platform-tools/bin/odw-cli ...`
   - `portal-mcp` 是 DataAgent 当前默认主链路的远程 MCP 入口，默认通过 `X-Portal-MCP-Token` 访问；它调用 backend `/api/v1/ai/metadata/*` 与 `/api/v1/ai/query/read`
   - `portal-mcp` 继续随根部署提供，但它不是 `opendataagent` 共享平台 skill 的主链入口
   - `opendataagent` 不随这里的 compose 自动启动，需要单独进入 `opendataagent/deploy/` 部署
   - `skills/` 根目录中的共享 skill 主要服务 `opendataagent`；当前生产智能问数主链使用 DataAgent system prompt、`opendataworks-business-knowledge` 与 `opendataworks-platform-tools`
   - 主前端默认通过同源 `/dataagent/widget/opendataworks-widget.bundle.js` 加载 DataAgent widget，并通过 `/api/v1/nl2sql/*` 代理访问 DataAgent 后端；若需要改成独立域名，源码构建主前端时设置 `VITE_DATAAGENT_WIDGET_JS_URL`
   - DataAgent backend/runner 额外持久化宿主目录 `DATAAGENT_HOST_ROOT`（默认 `/dataagent_runtime`），compose 挂载到容器内固定运行时根 `/dataagent_runtime`。topic 根目录默认位于 `/dataagent_runtime/<topic_id>/`，agent cwd 位于 `workspace/`，Claude SDK session 文件位于同一 topic 下的 `home/.claude/projects/<sanitized-cwd>/`；同一 topic 多轮复用该目录，不同 topic 不共享 `.claude` 状态
   - `DATAAGENT_HOST_ROOT` 可在 `.env` 中改成任意自定义宿主机目录：绝对路径直接生效；相对路径按 `deploy/` 解析，`scripts/start.sh` 会在启动 compose 前统一展开成宿主机绝对路径并 `export`，保证 backend 卷挂载与 sandbox runner 反查的 child bind 源指向同一目录。若不经 `start.sh` 直接运行 `docker compose`，请填写绝对路径，否则 runner 会把相对路径误解析成容器内 `/app/<rel>` 导致 child bind 源错位
   - `dataagent-home-init` 是一次性 init 服务（以 root 运行），在 backend/runner 启动前确保 `DATAAGENT_HOST_ROOT` 对应的 `/dataagent_runtime` bind mount 存在，并把属主改成 `DATAAGENT_RUNTIME_UID/GID`。这避免了 Docker 把缺失的 bind mount 宿主目录默认建成 `root:root 755`、导致非 root 的 `dataagent-backend` 写 `/dataagent_runtime` 时报 `permission denied` 的问题；属主修好后，每个 topic 的具体 skill 目录仍由运行时按需在各自 topic 工作区下创建，init 不预设任何按 skill 划分的目录；若改了 `DATAAGENT_RUNTIME_UID/GID`，该 init 服务会自动按新值修正属主
   - `dataagent-backend` 与 `dataagent-sandbox-runner` 采用 master/worker 形态：backend 负责 topic/task 协调，runner 使用独立 `opendataworks-dataagent-runner` 镜像负责执行入口；设置 `DATAAGENT_SANDBOX_MODE` 后，backend 会把任务流式委托给 runner，runner 再启动每 task child 容器，并只把当前 topic 目录挂到 child 的 `/mnt/workspace`
   - task child 内部路径与 backend/runner 服务路径分离：`/mnt/workspace` 是当前 task workspace 和 project skills 目录，`/mnt/home` 是 Claude HOME，`/tmp` 只用于临时 scratch；child 不注入 `DATAAGENT_WORKSPACE_DIR`、`DATAAGENT_WORKSPACE_PREPARED`，也不暴露共享运行时根
   - 每个 task child 容器都是一次性容器，runner 使用 `--rm` 启动；正常结束自动删除，取消或异常时 runner 会 kill child，并在启动时清理带 `dataagent.sandbox.managed_by=dataagent-sandbox-runner` 标签的遗留 child 容器
   - `DATAAGENT_DOCKER_SOCKET` 只挂到 `dataagent-sandbox-runner`，不会挂到 task child 容器；runner 默认用 `DATAAGENT_RUNNER_UID/GID=0:0` 访问 Docker socket，child task 仍用 `DATAAGENT_RUNTIME_UID/GID` 运行；若手动删除 `DATAAGENT_HOST_ROOT`，Claude SDK 本地 session 文件和 topic 工作区都会被清空，此时旧话题会退回到“重放历史 prompt”的兼容路径，直到该话题再次跑出新的真实 SDK session id

   > **💡 数据库自动初始化**: MySQL 容器首次启动时，会自动执行 `deploy/database/mysql/` 目录下的初始化脚本，创建 `opendataworks` / `dataagent` 数据库，并分别初始化 `opendataworks`、`dataagent` 两个应用用户。DataAgent 容器启动时会先执行 `alembic upgrade head`，再启动服务。
   >
   > 若保留旧的 `mysql-data` volume 升级，初始化脚本不会重跑；切换到独立 `dataagent` 用户前，需要先手动补建该用户或清空 volume 重新初始化。
   > 升级到引入 `api_format` 的版本时，Alembic 会清空旧的 `da_model_provider` 供应商配置；升级完成后需在管理页重新添加供应商、凭据和模型。
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
   新版离线包只包含 `deploy/.env.example`，不会携带打包机的
   `deploy/.env`。升级时即使把包内容同步到已有部署目录，也不会覆盖服务器现有的
   `deploy/.env`。

2. **Load Images**:
   This loads all required Docker images from the local archive. 新版离线包将全部镜像去重保存为单个 `deploy/docker-images/all-images.tar`，加载脚本会自动识别（旧版逐镜像 `*.tar` 也兼容）。
   ```bash
   scripts/load-images.sh
   ```

3. **Configure Environment**:
   ```bash
   # 仅首次安装、deploy/.env 不存在时执行
   test -f deploy/.env || cp deploy/.env.example deploy/.env
   # Edit .env and configure settings
   vim deploy/.env
   ```
   后端主库连接通过 `deploy/.env` 中的 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 注入。默认值指向包内 compose 的 `mysql:3306/opendataworks`，如连接外部 MySQL，请同步调整这三个变量。

4. **Start Services**:
   ```bash
   scripts/start.sh
   ```

   离线包中的主链地址：
   - 门户首页: `http://localhost:8081/`
   - 主前端智能问数入口: `http://localhost:8081/intelligent-query`
   - DataAgent Frontend: `http://localhost:8901/chat`
   - DataAgent Backend: `http://localhost:8900`
   - Portal MCP Health: `http://localhost:8801/health`
   - Portal MCP Streamable HTTP: `http://localhost:8801/mcp/`

   说明：
   - 离线包内保留 `deploy/dataagent-runtime/skills/` 可直接编辑。
   - 大模型供应商、Token 与候选模型仍通过主前端配置页管理，并持久化到 DataAgent provider registry。
   - 离线包内保留 `deploy/dataagent-runtime/skills/` 可直接编辑
   - 大模型供应商、Token 与候选模型仍通过主前端配置页管理
   - 动态元数据查询示例保留在 platform tools skill 的 `reference/` / `scripts/` 中
   - OpenDataWorks 内部部署默认 MCP-first：DataAgent runtime 会向当前 run 动态注入 `portal-mcp`，优先直接调用 `portal_search_tables` / `portal_get_lineage` / `portal_resolve_datasource` / `portal_export_metadata` / `portal_get_table_ddl` / `portal_query_readonly`
   - 非 MCP 智能体或 MCP 未注入时，DataAgent 才回退到 platform tools skill 自带的 `opendataworks-platform-tools/bin/odw-cli` 调 backend `/api/v1/ai/*` 只读入口获取 metadata / lineage / datasource 解析，并通过 `"$DATAAGENT_PYTHON_BIN" "${SKILLS_ROOT_DIR}/opendataworks-platform-tools/scripts/run_sql.py" -> odw-cli -> /api/v1/ai/query/read` 执行只读 SQL；需保证 `AGENT_API_SERVICE_TOKEN` 在 backend 与 DataAgent 容器中一致
   - `ODW_BACKEND_BASE_URL` 的推荐值为 `http://backend:8080/api/v1/ai`；CLI 兼容旧值 `/api/v1/ai/metadata`，但部署默认值已切到 AI 根路径
   - `DATAAGENT_PORTAL_MCP_ENABLED`、`DATAAGENT_PORTAL_MCP_BASE_URL` 与 `PORTAL_MCP_TOKEN` 只在 registry 中尚无 `portal` 行时作为一次性 bootstrap 输入；导入后 DataAgent runtime 只读 `da_mcp_server`，页面禁用或修改不会被重启时的环境变量覆盖。`PORTAL_MCP_TOKEN_HEADER_NAME` 仍同时定义 frontdoor 与初始 client header
   - skill/runtime 不再需要外部数据源的 host / port / user / password；datasource 解析结果只保留定位摘要
   - 若对应 skill 目录下缺少 `opendataworks-platform-tools/bin/odw-cli`，需由用户先自行安装到该固定路径，再启动 DataAgent
   - `scripts/start.sh` 会在启动前对挂载的 `odw-cli` 执行一次宿主机侧 `chmod +x`；即使 bind mount 丢了执行位，DataAgent runtime 也会回退为 `sh /app/.claude/skills/opendataworks-platform-tools/bin/odw-cli ...`
   - `portal-mcp` 作为独立远程 MCP 服务一并部署，客户端需带 `X-Portal-MCP-Token`
   - `opendataagent` 需要用它自己的部署包或 compose 单独部署，不包含在这里的离线包主链描述中
   - DataAgent backend/runner 额外持久化宿主目录 `DATAAGENT_HOST_ROOT`（默认 `/dataagent_runtime`），compose 挂载到容器内固定运行时根 `/dataagent_runtime`。topic 根目录默认位于 `/dataagent_runtime/<topic_id>/`，agent cwd 位于 `workspace/`，Claude SDK session 文件位于同一 topic 下的 `home/.claude/projects/<sanitized-cwd>/`；同一 topic 多轮复用该目录，不同 topic 不共享 `.claude` 状态
   - `DATAAGENT_HOST_ROOT` 可在 `.env` 中改成任意自定义宿主机目录：绝对路径直接生效；相对路径按 `deploy/` 解析，`scripts/start.sh` 会在启动 compose 前统一展开成宿主机绝对路径并 `export`，保证 backend 卷挂载与 sandbox runner 反查的 child bind 源指向同一目录。若不经 `start.sh` 直接运行 `docker compose`，请填写绝对路径，否则 runner 会把相对路径误解析成容器内 `/app/<rel>` 导致 child bind 源错位
   - `dataagent-home-init` 是一次性 init 服务（以 root 运行），在 backend/runner 启动前确保 `DATAAGENT_HOST_ROOT` 对应的 `/dataagent_runtime` bind mount 存在，并把属主改成 `DATAAGENT_RUNTIME_UID/GID`。这避免了 Docker 把缺失的 bind mount 宿主目录默认建成 `root:root 755`、导致非 root 的 `dataagent-backend` 写 `/dataagent_runtime` 时报 `permission denied` 的问题；属主修好后，每个 topic 的具体 skill 目录仍由运行时按需在各自 topic 工作区下创建，init 不预设任何按 skill 划分的目录；若改了 `DATAAGENT_RUNTIME_UID/GID`，该 init 服务会自动按新值修正属主
   - `dataagent-backend` 与 `dataagent-sandbox-runner` 采用 master/worker 形态：backend 负责 topic/task 协调，runner 使用独立 `opendataworks-dataagent-runner` 镜像负责执行入口；设置 `DATAAGENT_SANDBOX_MODE` 后，backend 会把任务流式委托给 runner，runner 再启动每 task child 容器，并只把当前 topic 目录挂到 child 的 `/mnt/workspace`
   - task child 内部路径与 backend/runner 服务路径分离：`/mnt/workspace` 是当前 task workspace 和 project skills 目录，`/mnt/home` 是 Claude HOME，`/tmp` 只用于临时 scratch；child 不注入 `DATAAGENT_WORKSPACE_DIR`、`DATAAGENT_WORKSPACE_PREPARED`，也不暴露共享运行时根
   - 每个 task child 容器都是一次性容器，runner 使用 `--rm` 启动；正常结束自动删除，取消或异常时 runner 会 kill child，并在启动时清理带 `dataagent.sandbox.managed_by=dataagent-sandbox-runner` 标签的遗留 child 容器
   - `DATAAGENT_DOCKER_SOCKET` 只挂到 `dataagent-sandbox-runner`，不会挂到 task child 容器；runner 默认用 `DATAAGENT_RUNNER_UID/GID=0:0` 访问 Docker socket，child task 仍用 `DATAAGENT_RUNTIME_UID/GID` 运行；若手动删除 `DATAAGENT_HOST_ROOT`，Claude SDK 本地 session 文件和 topic 工作区都会被清空，此时旧话题会退回到“重放历史 prompt”的兼容路径，直到该话题再次跑出新的真实 SDK session id

   > **💡 数据库自动初始化**: MySQL 容器首次启动时，会自动执行 `deploy/database/mysql/` 目录下的初始化脚本，创建 `opendataworks` / `dataagent` 数据库，并分别初始化 `opendataworks`、`dataagent` 两个应用用户。DataAgent 容器启动时会先执行 `alembic upgrade head`，再启动服务。
   >
   > 若保留旧的 `mysql-data` volume 升级，初始化脚本不会重跑；切换到独立 `dataagent` 用户前，需要先手动补建该用户或清空 volume 重新初始化。
   > 升级到引入 `api_format` 的版本时，Alembic 会清空旧的 `da_model_provider` 供应商配置；升级完成后需在管理页重新添加供应商、凭据和模型。
   >
   > 离线包中的 DataAgent 也默认以非 root 用户运行（`DATAAGENT_RUNTIME_UID/GID`，默认 `1000:1000`）。若 `deploy/dataagent-runtime/skills/` 无法写入，请把这两个值改成目标机器目录拥有者的 UID/GID，或先调整目录权限。

---

## 3. DataAgent Online Evaluation

DataAgent 通用问数评测工具用于部署完成并配置可用模型后手动运行在线评测。builtin 与 DeepEval 是两个并列评测引擎，均位于 `tools/dataagent-evals/`，且均独立于 DataAgent runtime。私有评测集不随 GitHub 或离线包内置，运行时必须通过 `--dataset` 或 `DATAAGENT_EVAL_DATASET` 指定。

> **📦 评测镜像改由独立附加包提供**：评测镜像默认不随服务启动，且 `deepeval` 依赖较重，已从主离线包拆出，单独发布为 `opendataworks-evals-offline-*.tar.xz`。需要在线评测时单独下载该附加包并加载：
> ```bash
> tar -xJf opendataworks-evals-offline-*.tar.xz
> cd opendataworks-evals-offline
> scripts/load-evals-images.sh
> ```
> 主离线包仍保留 `scripts/run-dataagent-evals.sh` / `run-dataagent-deepeval-evals.sh` 与 `tools/dataagent-evals/`，加载附加包镜像后即可直接运行。

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

## DataX Sync Prerequisites

DataX 同步任务以 DolphinScheduler 的 `DATAX` 任务节点执行（见 `docs/design/2026-06-10-datax-data-integration-design.md`），平台本身不内置 DataX 运行时。启用 DataX 同步前需确保：

- DolphinScheduler 的 worker 节点已安装 DataX 运行时。
- 源 / 目标数据源已在 DolphinScheduler 数据源中心登记（平台按名称解析其 id 与类型）。
- 如所用 DolphinScheduler 版本要求 DATAX 节点绑定 environment，请在其中配置好 DataX 环境；当前平台下发的任务定义使用默认 `environmentCode=-1`，必要时在 DolphinScheduler 侧为 DATAX 任务补充环境。

列映射（`column_mapping`）支持三种形式：留空（全列同步）、列清单（逗号分隔或 JSON 数组 / 源到目标的 JSON 对象映射）、完整 DataX 作业 JSON（含 `job` 键，按自定义模式 `customConfig=1` 下发）。

## DataAgent Authentication (Optional)

DataAgent（智能问数）支持可选的 OAuth2 + 本地管理员登录，以及非认证的运行时配置覆盖（`DATAAGENT_SETTINGS`）。配置采用 **Superset `docker/pythonpath_dev` 同款模式**：`deploy/docker/dataagent/` 目录被 compose 整体挂载进 `dataagent-backend` 容器（`/app/docker/dataagent`，只读），并加入 `sys.path`。

目录布局：

| 文件 | 说明 |
|---|---|
| `docker/dataagent/dataagent_config.py` | 仓库自带基础配置（默认 `AUTH_ENABLED=False`，**不要直接改**，升级会覆盖）；末尾自动加载同目录用户覆盖 |
| `docker/dataagent/dataagent_config_docker.py.example` | 用户覆盖示例（认证 + `DATAAGENT_SETTINGS` 运行时覆盖）；拷贝为 `dataagent_config_docker.py` 后填写 |
| `docker/dataagent/custom_sso_user_info.py.example` | UserInfo 获取与归一化钩子示例（Superset `SecurityManager.oauth_user_info` 对应物）；拷贝为 `custom_sso_user_info.py` 并在覆盖配置里挂 `OAUTH_USER_INFO` |
| `docker/dataagent/.gitignore` | 忽略一切用户文件，只保留自带文件与 `.example` |
| `docker/nginx/frontend.conf`、`docker/nginx/dataagent-frontend.conf` | 两个前端 nginx 配置的宿主机副本；默认仍用镜像内构建版本，取消 compose 中对应服务 volumes 注释即可切换为宿主机管理 |

启用步骤：

1. `cd deploy/docker/dataagent && cp dataagent_config_docker.py.example dataagent_config_docker.py`
2. 按注释填写 `SECRET_KEY`（`secrets.token_urlsafe(32)` 生成）、`LOCAL_ADMINS`（bcrypt 哈希）、单项 `OAUTH_PROVIDERS`（Superset/FAB `remote_app` 形态，`icon` 支持 Font Awesome 4 class）、`ADMIN_USERS`（`provider:sub` 稳定标识），置 `AUTH_ENABLED = True`。
   OAuth 的 `redirect_uri` 必须注册为 `https://<dataagent-host>/oauth-authorized/<provider_name>`；`api_base_url`、`response_type` 与 `grant_type` 在 `remote_app` 中配置。OAuth 启用时还必须从自定义模块导入 `OAUTH_USER_INFO(provider, token_response, oauth_remotes)`；钩子可通过 `oauth_remotes[provider].get("userinfo")` 获取并归一化用户信息。
   早期版本的扁平 `OAUTH` 不再接受；检测到非空旧配置时服务会
   fail-closed 启动失败并提示迁移，避免静默丢失 OAuth 登录。
3. 重启 `dataagent-backend`。无需修改 compose。需要自定义扩展模块（如自研 SSO 适配）时，直接把 `.py` 放进同目录并在覆盖文件里 import（目录在 `sys.path` 上）。

语义与回滚（fail-closed）：

- 覆盖文件不存在或 `AUTH_ENABLED=False`（默认） = 认证关闭，行为与无认证版本完全一致。
- 容器内 `DATAAGENT_CONFIG` env 被注释掉 = 彻底关闭外置配置机制（终极回滚手段）。
- env 已设置但配置文件缺失 / 不可读 / 有语法错误 / 启用却缺合法 `SECRET_KEY` = 服务启动失败，不会静默降级为无认证。
- 关闭后果：曾经登录用户名下的会话会重新出现在共享匿名池（恢复完整旧语义，不保留半套隔离）。
- widget 外嵌会话（`X-ODW-Client: widget`）与主门户嵌入页的匿名会话完全不受认证影响。

详见 `docs/design/2026-07-01-dataagent-auth-design.md` 与 `docs/handbook/`。

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

### Check Logs / 日志排障

所有服务统一使用 `json-file` 日志驱动并轮转（单服务上限 `20m × 5 = 100MB`），容器重启不丢、磁盘不被撑爆：

```bash
# 实时查看某个服务日志（例如 backend）
docker-compose -f docker-compose.prod.yml logs -f backend
```

#### 宿主机日志文件（方便排障/打包带走）

- **服务日志（实时落盘）**：`log-collector` sidecar 自动把各服务日志实时写到包目录
  `deploy/logs/<container>.log`，打开目录即可查看，无需敲命令。
  - 默认只覆盖 4 个应用服务：`backend`、`dataagent-backend`、`dataagent-sandbox-runner`、`portal-mcp`。
  - 不收集 mysql/redis 基础设施与两个 nginx 前端；如需增减，在 `deploy/.env` 设置 `LOG_COLLECTOR_CONTAINERS`（空格分隔容器名）。
  - 收集器复用 runner 镜像自带的 docker CLI，离线包不新增镜像；只读挂载 docker socket。
- **task child 日志（已落盘）**：DataAgent 每个 `--rm` task child 的输出由 runner 实时写在挂载卷上
  `<DATAAGENT_HOST_ROOT>/<topic_id>/logs/<task_id>.log`，`--rm` 删容器不影响留存。
- **一键汇总/导出**：需要把全部服务日志 + 所有 task child 日志固化到一处时，运行：
  ```bash
  scripts/dump-logs.sh
  # 产物：
  #   deploy/logs/services/<service>.log     各服务日志快照
  #   deploy/logs/task-child/<topic>/*.log   所有 task child 日志
  # 打包带走：tar -czf odw-logs.tgz -C deploy logs
  ```
