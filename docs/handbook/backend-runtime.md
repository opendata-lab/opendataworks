# Backend 本地运行与验证

后端使用 Java 17、Spring Boot 3.5.7、MCP Java SDK 0.18.5、NetworkNT JSON Schema 2.0.0。`backend/pom.xml` 是唯一 Maven Reactor，依次构建 `core`、`api`、`mcp-server`、`app`；只有 `app` 生成可运行 jar。需要 MySQL 8，工作流操作另外需要 DolphinScheduler。

## 源码运行

```bash
# JAVA_HOME 指向 JDK 17；Maven 3.8+ 在 PATH 中
mvn -B -ntp -f backend/pom.xml -DskipTests package

# 与 deploy/.env.example 使用相同的数据库环境变量
export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/opendataworks?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
export SPRING_DATASOURCE_USERNAME=opendataworks
export SPRING_DATASOURCE_PASSWORD=opendataworks123
# 权限管理凭据使用持久的 32 字节 Base64 密钥；重启时继续使用同一个值
export ODW_CREDENTIAL_KEY='<持久保存的32字节随机密钥Base64>'
export ODW_HTTP_ALLOWED_ORIGINS='http://localhost:3000,http://127.0.0.1:3000'
java -jar backend/app/target/opendataworks-app-1.6.0.jar --server.port=8082
```

另开终端，在仓库根目录启动前端：

```bash
export NVM_DIR="$HOME/.nvm"
. "$NVM_DIR/nvm.sh"
nvm use
npm --prefix frontend ci
VITE_BACKEND_PROXY_TARGET=http://localhost:8082 npm --prefix frontend run dev -- --host 127.0.0.1 --port 3000
```

访问 `http://127.0.0.1:3000/`，本地初始账号 `admin / admin123`。后端健康检查为 `http://127.0.0.1:8082/api/v1/health`。Flyway 启动时校验并迁移平台库，不需要独立认证或 Python MCP 服务。

## 内置 MCP 与访问身份

MCP Streamable HTTP 地址为 `http://127.0.0.1:8082/api/mcp/`，无尾斜杠的 `/api/mcp` 也直接接受 POST，无重定向和会话状态；GET / DELETE 返回 405。请求使用 `Accept: application/json, text/event-stream` 和 `Authorization: Bearer <odw_sa_服务账号Token>`。管理员在账号管理中创建 SERVICE 并签发 Token；同一 Token 不允许访问门户 USER API。23 个工具的 `arguments` 顶层扁平，固定参数使用 camelCase，未知参数拒绝；无必填参数的工具可省略 `arguments`。表／数据源／血缘导出超过5000行明确失败，需缩小范围。预览不产生凭据，发布差异确认需要显式 `confirmDiff: true`。

在数据源权限页配置一次授权管理连接，为 USER / SERVICE 分配角色；首次分配自动创建并核验独立数据库账号，凭据使用 ODW_CREDENTIAL_KEY 加密，后续角色变更复用账号。已有 DBA 账号映射保持原样。MCP 只按服务账号 Token 授权：目录、只读查询与参考 DDL 都用该服务账号自己的映射；调用方上报的 `X-Agent-Id`、`X-Agent-User-Id` 只写入查询历史（`reported_agent_id`、`reported_user_id`），不参与权限判断（[设计](../design/2026-11-09-mcp-service-token-mode-design.md)）。需要不同数据范围时，为每个智能体建独立服务账号。物理 DDL 始终使用当前主体自己的映射，不借用采集账号。SERVICE 可被明确授予原生写权限。管理凭据仅用于权限配置，不能补足缺失的主体映射。

独立 DataAgent 需要在其仓库启动并把 MCP URL 更新为上述后端地址，同时刷新工具 Schema；智能问数还需要其模型及助手配置。本仓库不启动独立 DataAgent。门户 Nginx 拒绝 `/api/mcp` 及其子路径，外部 MCP 使用受限后端入口。MCP 默认仅允许私网客户端，受信代理必须显式列出，解析转发链时从右向左剥离受信地址。

## 验证

```bash
mvn -B -ntp -f backend/pom.xml \
  -Dtest='*Test,!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test

export NVM_DIR="$HOME/.nvm"
. "$NVM_DIR/nvm.sh"
nvm use
npm --prefix frontend test
npm --prefix frontend run build

python3.12 -m venv .venv-validation
.venv-validation/bin/pip install -r odw-skills/requirements.txt -r backend/app/src/test/scripts/requirements.txt pytest PyYAML
.venv-validation/bin/python -m pytest tests odw-skills/tests -q
```

Python 打包测试需要 `xz`。真实数据库及 Dolphin 检查只在自有可丢弃的环境执行，脚本会创建测试角色、账号、数据库和调度定义：

```bash
# DS_TOKEN 只放私有环境配置，IT_MYSQL_HOST 从 Dolphin worker 所在网络可达
export DS_BASE_URL=http://127.0.0.1:12345/dolphinscheduler
export DS_PROJECT_NAME=it_consolidation
export IT_MYSQL_HOST=host.docker.internal
backend/scripts/run-integration-test.sh

.venv-validation/bin/python backend/app/src/test/scripts/service_accounts_smoke.py \
  --credentials /private/credentials.json --backend-env /private/backend-env.json \
  --state /private/fixtures.json --keep
.venv-validation/bin/python backend/app/src/test/scripts/backend_mcp_smoke.py \
  --credentials /private/credentials.json --backend-env /private/backend-env.json \
  --state /private/fixtures.json
# 试用结束后可用 service_accounts_smoke.py 的同组参数加 --cleanup 清理
```

`credentials.json` 字段为 `portal_url`（含 `/api`）、`admin_username`、`admin_password`、`mysql_host`、`mysql_port`、`mysql_user`、`mysql_password`、`source_host`、`source_port`；`backend-env.json` 含运行中后端的 `SPRING_DATASOURCE_URL`。文件与 fixture state 含秘密，必须存仓库外并限制为仅本人可读。Dolphin 3.2.2 standalone 镜像默认缺少 MySQL 驱动，本轮真实 SQL 验证将 Connector/J 8.0.33 放到镜像 `libs/standalone-server/` 后重启，再生成 Token。

本轮验证结果及已知外部覆盖边界见[实施报告](../reports/2026-11-03-backend-consolidation-validation.md)。

V58 为自动创建的数据库登录增加 managed 标记。新托管映射必须使用本版本后端及原有 ODW_CREDENTIAL_KEY；降级前须处理托管账号与加密映射，不能直接让旧版本读取这些密码。自动创建的真实 MySQL／PostgreSQL 验证见[本轮报告](../reports/2026-11-04-managed-database-accounts-validation.md)。
