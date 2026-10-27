# OpenDataWorks Deployment Guide

This guide covers both Online (source code) and Offline (deployment package) deployment methods.

## 部署输入：默认优先

`deploy/.env.example` 只展开常规安装项。首次安装复制为 `.env`，设置数据库凭据、
JWT 密钥和共享服务令牌即可。密码须与实际 MySQL 用户一致；复用已有数据卷时，
修改 `.env` 不会重建用户或改写库内密码。

| 配置位置 | 内容 |
| --- | --- |
| `.env` | 凭据、镜像和必要部署选择 |
| Compose | 固定服务接线、共享令牌映射 |
| `docker/nginx/frontend.conf` | 门户 nginx（宿主机挂载），含 DataAgent 转发示例 |
| 代码默认 | 调度扫描、缓存/池、超时等策略 |

生产网络默认 `10.71.0.0/24`、网关 `10.71.0.1`，遇到 VPN/宿主网段冲突再修改；
dev 使用 Compose 默认网络。

`ODW_MCP_SERVER_TOKEN_HEADER_NAME` 默认 `X-ODW-MCP-Token`。ODW MCP Server keep-alive
默认 600 秒（镜像入口维护），不要在 Compose 重复写默认。版本升级使用 `compose pull`
后重建对应服务。

## Deployment Topology

本目录只部署 OpenDataWorks 门户：MySQL、Redis、backend、frontend、`odw-mcp-server`
和日志收集 sidecar。DataAgent（智能问数）由独立仓库
[opendataagent](https://github.com/MingkeVan/opendataagent) 单独部署，可以在另一台机器上；
接入方式见文末“接入 DataAgent”。

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
   后端主库连接由 Compose 从 `.env` 的 `MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD` 推导，默认连接 `mysql:3306`。外部 MySQL 仍可显式填写 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`，三项优先于推导值。

3. **Start Services**:
   ```bash
   ../scripts/start.sh
   ```

   主链路默认地址：
   - 门户首页: `http://localhost:8081/`
   - 主前端智能问数入口: `http://localhost:8081/intelligent-query`（需先接入 DataAgent）
   - ODW MCP Server Health: `http://localhost:8801/health`
   - ODW MCP Server Streamable HTTP: `http://localhost:8801/mcp/`

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
   后端主库连接由 Compose 从 `deploy/.env` 的 `MYSQL_*` 推导。仅当使用外部 MySQL 时，显式填写优先级更高的三项 `SPRING_DATASOURCE_*` 覆盖。

4. **Start Services**:
   ```bash
   scripts/start.sh
   ```

   离线包中的地址同上。

   > **💡 数据库自动初始化**: MySQL 容器首次启动时，会自动执行 `deploy/database/mysql/` 目录下的初始化脚本，创建 `opendataworks` 数据库和用户。保留旧的 `mysql-data` volume 升级时初始化脚本不会重跑。

---

## DataX Sync Prerequisites

DataX 同步任务以 DolphinScheduler 的 `DATAX` 任务节点执行（见 `docs/design/2026-06-10-datax-data-integration-design.md`），平台本身不内置 DataX 运行时。启用 DataX 同步前需确保：

- DolphinScheduler 的 worker 节点已安装 DataX 运行时。
- 源 / 目标数据源已在 DolphinScheduler 数据源中心登记（平台按名称解析其 id 与类型）。
- 如所用 DolphinScheduler 版本要求 DATAX 节点绑定 environment，请在其中配置好 DataX 环境；当前平台下发的任务定义使用默认 `environmentCode=-1`，必要时在 DolphinScheduler 侧为 DATAX 任务补充环境。

列映射（`column_mapping`）支持三种形式：留空（全列同步）、列清单（逗号分隔或 JSON 数组 / 源到目标的 JSON 对象映射）、完整 DataX 作业 JSON（含 `job` 键，按自定义模式 `customConfig=1` 下发）。

## 数据源主体账号映射（V54）

升级前备份平台库和 DataAgent 会话库（DataAgent 侧为 Alembic `20261026_000029`），检查并人工清理 `sys_user.external_id` 的非空重复值（V54 加唯一约束），停服务后执行两库迁移并整套发布。旧三表及旧权限接口直接删除，不迁移旧授权；没有映射即拒绝，没有默认账号兜底。回滚必须停服务并同时恢复两库备份和旧程序，不能仅回滚镜像。

1. DBA 为每个要用的数据源预建人的账号和各智能体的只读账号（只授所需对象的 SELECT/USAGE 等读取权限）。平台仅测试连接，不自动分析 GRANT；智能体只读性由 DBA 保证。Doris 使用 `Select_priv`，不依赖驱动只读事务。
2. 开启平台认证，关闭 `AUTH_ANONYMOUS_ENABLED`；DataAgent 设置 `AUTH_ENABLED=True`。本地登录时两边用户名相同；OAuth 两边接相同身份源，平台 `sys_user.external_id` 对应 sub。找不到或已停用用户即拒绝。主门户问数也必须已有 DataAgent 的真实登录会话；widget 自报用户 ID 和认证关闭时的聊天不能访问数据库。
3. 管理员在数据源页“账号映射”选择人（平台 `sys_user.id`）或智能体（agent_id），登记数据库用户名/密码并测试连接。读接口不回传密码；编辑留空保留密码。数据源原账号只用于同步和运维，不用于主体查询。人用自己的映射浏览目录、查询、写入和结构编辑；能做什么由数据库 GRANT 决定。
4. backend 的 `AGENT_API_SERVICE_TOKEN` 与 odw-mcp-server 的 `ODW_MCP_SERVER_BACKEND_SERVICE_TOKEN` 保持一致，Java agent API 仍限私网。可信 Python 后端填写 `X-Agent-User-Id`（`local:<用户名>` / `<provider>:<sub>`）和 `X-Agent-Id`。每次访问都必须有用户；会话里的定时消息以会话所有者身份执行。不要由反向代理、浏览器或模型拼装这些身份头。
5. 服务令牌和数据库密码不能注入模型 Bash 环境。DataAgent 通过 MCP 配置的普通前门 token header 访问；MCP 添加服务令牌，DataAgent 后端填写身份头。删除智能体“数据范围”配置及旧范围头；元数据和查询分别取两个数据库账号的可见对象交集和执行权限交集。

PostgreSQL 16 支持仅视图 SELECT 的 EXPLAIN；MySQL 8.0.45 即使有视图 SELECT、SHOW VIEW，EXPLAIN 仍可能要求底表权限，代人问数严格拒绝，不做回退。交集是对象级，DBA 不应给智能体授予依赖当前用户/RLS/SECURITY DEFINER 的敏感对象。Doris EXPLAIN 和视图权限需在内网验证。

同名 namespace 位于多个数据源时，现有按 database 定位的 agent API 会拒绝歧义；需使用不同 namespace 名称。详见[设计](../docs/design/2026-10-26-dataagent-datasource-identity-design.md)和[验证报告](../docs/reports/2026-10-26-dataagent-datasource-identity-verification.md)。

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
  - 默认只覆盖应用服务 `backend`、`odw-mcp-server`。
  - 不收集 mysql/redis 基础设施与 nginx 前端；如需增减，在 `deploy/.env` 设置 `LOG_COLLECTOR_CONTAINERS`（空格分隔容器名）。
  - 收集器使用 `docker:27.5.1-cli` 镜像（离线包已包含），只读挂载 docker socket（`DOCKER_SOCKET`）。
- **一键汇总/导出**：
  ```bash
  scripts/dump-logs.sh
  # 产物：deploy/logs/services/<service>.log
  # 打包带走：tar -czf odw-logs.tgz -C deploy logs
  ```


## 接入 DataAgent

DataAgent 镜像由 [opendataagent](https://github.com/MingkeVan/opendataagent) 构建和部署（`oda-frontend`、`oda-backend`、`oda-runner`），
本仓库不编排它。两边可以在不同机器上，下文 `<DataAgent 主机>`、`<OpenDataWorks 主机>` 填对方可访问的地址。

1. 按 opendataagent 的 `deploy/README.md` 部署 DataAgent。从旧版本（DataAgent 跟随本目录部署）升级时，按其中“从 OpenDataWorks 内置部署迁移”导出旧 `dataagent` 库和 Skill 卷；本目录升级不会删除这些数据。
   迁移需在升级本目录之前读取旧容器的 Skill 挂载；完成后再升级本目录，并用 `up -d --remove-orphans` 清掉旧的 `opendataworks-dataagent-*` 容器。
2. 门户内嵌 widget：编辑 `docker/nginx/frontend.conf`，把 DataAgent 转发段中的 `<DataAgent 主机>` 改成实际地址并取消注释，重启 `frontend`。未接入 DataAgent 时保持注释，门户其他功能不受影响。
3. 管理员在 DataAgent 新增普通 HTTP MCP：URL 为 `http://<OpenDataWorks 主机>:8801/mcp/`，header 填 `X-ODW-MCP-Token: <前门 token>`。工具名统一为 `odw_*`。
4. 用 DataAgent 已有的 Skill 导入功能导入并启用本仓库 `odw-skills/` 下所需目录；新建智能体并绑定 MCP 与 Skills。
   在门户「配置管理 → 智能助手」把「智能问数助手」选为这个智能体；未选择时，门户问数页会提示先配置，不会自动挑选。
5. 在 OpenDataWorks 为带来源前缀的人（例如 `local:alice`）和智能体 ID 配置数据库账号映射。前门 token 校验通过后，MCP 将 DataAgent 后端填写的 `X-Agent-User-Id`、`X-Agent-Id` 和服务令牌转发给 backend-agent-api；缺映射时拒绝访问。
6. DataAgent 的 Alembic `20261027_000031` 把旧的平台 MCP / 内置智能体转为普通可编辑记录；管理员更新其中的 MCP 地址、token 头和提示词中的工具名。

前门 token 是 DataAgent 访问本平台的凭证；两边跨机器时 `odw-mcp-server` 的 8801 端口需对 DataAgent 主机可达，且应走内网或 HTTPS。
