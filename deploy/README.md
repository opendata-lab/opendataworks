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

MCP调用方使用账号管理生成的服务Token（`Authorization: Bearer`）；不再使用共享前门Token或自定义Token头。ODW MCP Server keep-alive
默认 600 秒（镜像入口维护），不要在 Compose 重复写默认。版本升级使用 `compose pull`
后重建对应服务。

## Deployment Topology

本目录只部署 OpenDataWorks 门户：MySQL、Redis、backend、frontend
和日志收集 sidecar。DataAgent（智能问数）由独立仓库
[opendataagent](https://github.com/opendata-lab/opendataagent) 单独部署，可以在另一台机器上；
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
   - ODW MCP Server Health: `http://localhost:8080/api/v1/health`
   - ODW MCP Server Streamable HTTP: `http://localhost:8080/api/mcp/`

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

<a id="users-and-data-permissions"></a>

## 用户与数据源权限（V55／V56）

管理中心新增「账号管理」，数据源原「账号映射」由「权限配置」的成员／角色／授权记录替代。一个成员绑定一个独立数据库账号，持有多个源内角色；模板不等于共享登录账号。DBA预建登录账号，平台即时下发原生ROLE权限，不提供SQL权限改写网关。平台管理员也必须有自己的数据源账号才能查询。

升级前备份平台库及远端授权，发布匹配的backend／frontend，由Flyway执行V55和V56。V55补用户会话版本和角色等表，已有账号映射保留；V56保存绑定时验证的原生身份，重新绑定已有成员后可在其密码过期时仍通过管理连接撤权。授权能力要求MySQL8／PostgreSQL16+；Doris须在部署环境完成真实权限回归。

使用专用「授权管理连接」前，在部署的`.env`配置`ODW_CREDENTIAL_KEY`（`openssl rand -base64 32`生成）。Compose开发／生产配置均传给backend；独立Java启动时同样设置该环境变量。它是AES-256-GCM密钥，不得提交版本库或发到前端；备份时与平台库分开保管。不要直接替换密钥：旧管理连接将不能解密，需明确重录或设计密钥轮换后再替换。未配置密钥时拒绝保存专用管理连接，不保存明文。

管理账号需要盘点资源、创建／删除ROLE、给ROLE授予并撤销选定对象权限、管理成员关系及回读权限的实际原生能力。MySQL回读涉及mysql.user／db／tables_priv／role_edges／default_roles等；PG涉及角色、成员和ACL目录。最简单的本机验证可用DBA账号，生产应由DBA限制可管理资源与网络范围。不要把管理连接绑定给普通用户或智能体；智能体账号只能只读且无管理／转授权能力。

原生库与平台记录无分布式事务。非SUCCESS必须查授权记录和远端实际状态，重新预览后明确恢复；回滚时先停止权限写入，再按操作步骤核对原生授权与平台备份，不能仅换旧镜像，也不能自动重授已撤权限。停用／删除平台用户不会删除数据库账号；DBA直连访问需单独管理。

完整操作、引擎差异和已知限制见[使用说明](../docs/handbook/features/users-and-data-permissions.md)与[验收报告](../docs/reports/2026-11-02-users-and-permissions-verification.md)。服务账号Token及生命周期管理见下方V57；可信签名用户身份仍延期，当前浏览器用户头不能作为生产可信委托，详见TODO。

<a id="数据源主体账号映射v54--alembic-20261026_000029"></a>

## 数据源主体账号接入（V54历史基础）

升级前备份平台库和 DataAgent 会话库（DataAgent 侧为 Alembic `20261026_000029`），检查并人工清理 `sys_user.external_id` 的非空重复值（V54 加唯一约束），停服务后执行两库迁移并整套发布。旧三表及旧权限接口直接删除，不迁移旧授权；没有映射即拒绝，没有默认账号兜底。回滚必须停服务并同时恢复两库备份和旧程序，不能仅回滚镜像。

1. DBA 为每个要用的数据源预建人的账号和各智能体的只读账号。V55起可用角色中心管理托管授权并核验Agent只读能力；账号已有直接授权仍由DBA核对。Doris 使用 `Select_priv`，不依赖驱动只读事务。
2. 开启平台认证，关闭 `AUTH_ANONYMOUS_ENABLED`；DataAgent 设置 `AUTH_ENABLED=True`。本地登录时两边用户名相同；OAuth 两边接相同身份源，平台 `sys_user.external_id` 对应 sub。找不到或已停用用户即拒绝。门户内嵌问数时由门户上报当前用户（`/auth/me` 的 `agentUserId`），DataAgent 以该用户执行，不再要求单独登录 DataAgent；DataAgent 独立前端仍用它自己的登录。认证关闭时的匿名聊天不能访问数据库。
3. 管理员在数据源页「权限配置 → 成员」选择人（平台 `sys_user.id`）或服务账号（同为平台 `sys_user.id`，V57后不接受外部agent_id），分配角色并绑定数据库用户名／密码。读接口不回传密码，编辑留空保留密码。数据源原账号只用于采集和运维，不用于主体查询；人的目录、查询、写入和结构编辑均由其受限账号执行。
4. MCP 内置于 Java 后端 `/api/mcp/`（也支持 `/api/mcp`），外部仅使用 `Authorization: Bearer <服务账号Token>`。全部 23 个工具可用；物理 DDL 使用 SERVICE 自身数据库映射，用户头只用于只读权限交集。按部署填写 `ODW_HTTP_ALLOWED_ORIGINS` 与受信代理 IP，MCP 默认只允许私网访问。
5. 服务令牌和数据库密码不能注入模型 Bash 环境。DataAgent通过普通HTTP MCP配置的Authorization Bearer服务账号Token访问；MCP添加独立内部转发凭据，DataAgent后端可填写用户身份头。无用户头按服务账号自身权限，有用户头严格取两个数据库账号交集。删除旧数据范围配置及范围头。

PostgreSQL 16 支持仅视图 SELECT 的 EXPLAIN；MySQL 8.0.45 即使有视图 SELECT、SHOW VIEW，EXPLAIN 仍可能要求底表权限，代人问数严格拒绝，不做回退。交集是对象级，DBA 不应给智能体授予依赖当前用户/RLS/SECURITY DEFINER 的敏感对象。Doris EXPLAIN 和视图权限需在内网验证。

同名 namespace 位于多个数据源时，现有按 database 定位的 agent API 会拒绝歧义；需使用不同 namespace 名称。详见[设计](../docs/design/2026-10-26-dataagent-datasource-identity-design.md)和[验证报告](../docs/reports/2026-10-26-dataagent-datasource-identity-verification.md)。


## 账号类型与服务Token（V57）

V57增加USER／SERVICE及用途字段和Token摘要表。账号创建时选择类型，之后不可转换；普通用户可登录，服务账号无密码／网页会话或平台admin权限。服务账号可以生成多个Token（有效期1～365天，默认90），明文只返回一次，平台只存摘要；生命周期时间按UTC保存与比较，API返回带Z时间，页面按本地时区展示；停用／删除立即阻止后续Token请求。平台账号与数据库登录账号分开，后者在源内绑定。

升级前备份平台库及调用方MCP配置，发布匹配Java／MCP／Vue。既有AGENT绑定按旧ID去重迁移为平台服务账号，保留原生角色与数据库账号，不自动签发Token。管理员在账号管理找到迁移账号并生成Token，更新调用方Authorization头。旧共享前门Token及外部agent_id不能继续认证，无旧协议回退；原始ODW_MCP_SERVER_TOKEN及TOKEN_HEADER_NAME环境项停止使用。内部共享服务Token已移除，调用方 URL 切换到 `/api/mcp/` 并刷新工具 Schema 缓存；工具固定参数统一 camelCase、直接顶层 arguments，预览不签发凭据，发布 confirmDiff 缺省 false。

Token明文只存外部应用的安全配置；平台记录名称、前缀、到期／撤销／最后使用时间。轮换时先创建新Token并更新调用方，验证后撤销旧Token，不需要改角色或数据库账号。已签发Token不允许再次查看。账号停用不自动撤销数据库直连权限，DBA生命周期仍独立。

本期服务Token对agent API仅开放元数据GET与只读查询POST；现有开发写工具仍有工具定义，但会明确403，必须另行设计写权限与可信委托后启用，不能沿用可伪造operator头执行。回滚需同时恢复平台备份、旧版本和调用方旧凭据，不能只回退前端。操作见[账号手册](../docs/handbook/features/users-and-data-permissions.md)。

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
  - 默认只覆盖应用服务 `backend`。
  - 不收集 mysql/redis 基础设施与 nginx 前端；如需增减，在 `deploy/.env` 设置 `LOG_COLLECTOR_CONTAINERS`（空格分隔容器名）。
  - 收集器使用 `docker:27.5.1-cli` 镜像（离线包已包含），只读挂载 docker socket（`DOCKER_SOCKET`）。
- **一键汇总/导出**：
  ```bash
  scripts/dump-logs.sh
  # 产物：deploy/logs/services/<service>.log
  # 打包带走：tar -czf odw-logs.tgz -C deploy logs
  ```


## 接入 DataAgent

DataAgent 镜像由 [opendataagent](https://github.com/opendata-lab/opendataagent) 构建和部署（`oda-frontend`、`oda-backend`、`oda-runner`），
本仓库不编排它。两边可以在不同机器上，下文 `<DataAgent 主机>`、`<OpenDataWorks 主机>` 填对方可访问的地址。

1. 按 opendataagent 的 `deploy/README.md` 部署 DataAgent。从旧版本（DataAgent 跟随本目录部署）升级时，按其中“从 OpenDataWorks 内置部署迁移”导出旧 `dataagent` 库和 Skill 卷；本目录升级不会删除这些数据。
   迁移需在升级本目录之前读取旧容器的 Skill 挂载；完成后再升级本目录，并用 `up -d --remove-orphans` 清掉旧的 `opendataworks-dataagent-*` 容器。
2. 门户内嵌 widget：编辑 `docker/nginx/frontend.conf`，把 DataAgent 转发段中的 `<DataAgent 主机>` 改成实际地址并取消注释，重启 `frontend`。未接入 DataAgent 时保持注释，门户其他功能不受影响。
   在 DataAgent「Widget 接入」中登记站点 `opendataworks-portal`，允许来源填门户的访问地址（如 `https://portal.example.com`）。
   门户 widget 以该站点身份上报当前用户：本地登录为 `local:<用户名>`，第三方登录为 `<来源>:<external_id>`；DataAgent 按此用户选择数据库账号。
   注意：这个用户标识来自浏览器，登录门户的用户可以篡改它冒充他人，按冒充对象的数据库账号权限查数。只在可信内网使用，并保证 DataAgent 的 8900 端口不对外暴露（风险已记入 `docs/TODO.md`）。
3. 在OpenDataWorks「管理中心 → 账号管理」创建服务账号、生成Token，并在数据源权限页分配只读角色、绑定受限数据库账号。DataAgent或其他外部应用配置普通HTTP MCP，URL为`http://<OpenDataWorks 主机>:8080/api/mcp/`，header为`Authorization: Bearer <服务账号Token>`。工具名`odw_*`不变，凭据只存调用方安全配置，不放提示词或模型环境。
4. 用 DataAgent 已有的 Skill 导入功能导入并启用本仓库 `odw-skills/` 下所需目录；新建智能体并绑定 MCP 与 Skills。
   在门户「配置管理 → 智能助手」把「智能问数助手」选为这个智能体；未选择时，门户问数页会提示先配置，不会自动挑选。
5. 平台根据服务Token识别服务账号，不查询外部助手目录、不信任调用方agent_id。无用户头时按服务账号自身权限查询；代人问数时可提供`X-Agent-User-Id: local:alice`等原有标识，并要求该普通用户的源内映射，查询范围取双方交集。可信用户委托仍延期，未签名用户头不构成可信人身份认证。
6. DataAgent侧应使用普通可编辑的HTTP MCP记录（configured），管理员更新MCP地址、Authorization头和提示词中的工具名。本机旧portal种子仍标记为plugin，需由DataAgent运维备份并转为configured后再编辑；不要通过平台访问外部助手目录或修改DataAgent源码来鉴别服务账号。

服务账号Token由平台管理，可到期或单独撤销，账号停用后所有Token失效；两边跨机器时后端 8080 端口或门户 `/api/mcp/` 反向代理入口需对 DataAgent 主机可达，且应走内网或 HTTPS。
