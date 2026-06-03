# 运维与部署指南

整合 `docs/deployment/*.md`、`DOCKER_BUILD.md`、`RESTART_GUIDE.md` 等文件，给出统一的部署与回滚流程。

## 部署方式对比

| 方式 | 适用场景 | 入口 |
| --- | --- | --- |
| Docker Compose | PoC、本地/测试环境一键启动；或源码方式部署生产容器 | `deploy/docker-compose.dev.yml` / `deploy/docker-compose.prod.yml` |
| 离线包 | 无外网、需要提前拉取镜像 | `scripts/create-offline-package.sh` + `scripts/load-package-and-start.sh` |
| 裸机/systemd | 生产环境分层部署、需要自定义安全策略 | `docs/handbook/operations-guide.md` 本文 + `scripts/*.sh` |

## Docker Compose

```bash
# 本地 / 测试环境
docker compose -f deploy/docker-compose.dev.yml up -d

# 源码方式生产部署
bash scripts/start.sh
```

- MySQL 卷：`mysql-data`
- 后端日志卷：`backend-logs`
- Compose 服务：`mysql`、`redis`、`backend`、`frontend`、`dataagent-frontend`、`dataagent-backend`、`portal-mcp`
- 根 `deploy/` 仍然承载主门户与现有智能问数主链，不包含 `opendataagent`
- **数据库自动初始化**：MySQL 容器首次启动时，会自动执行 `deploy/database/mysql/` 目录下的初始化脚本，创建数据库和用户。`opendataworks` 用户供后端使用，`dataagent` 用户默认供 DataAgent 使用。无需手动创建数据库。表结构由后端服务的 Flyway 自动创建。
- 环境变量重点：
  - `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE=opendataworks`, `MYSQL_USER=opendataworks`
  - `SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/opendataworks`
  - `DATAAGENT_MYSQL_USER=dataagent`, `DATAAGENT_SESSION_MYSQL_DATABASE=dataagent`
  - `DATAAGENT_RUNTIME_UID/GID` 控制 DataAgent 容器的运行身份，默认 `1000:1000`
  - DolphinScheduler 配置请在系统管理界面进行
- 若升级时保留已有 `mysql-data` volume，初始化脚本不会补跑；切换到独立 `dataagent` 用户前请先手动补建该用户。
- 若 DataAgent 挂载的 skills 目录存在宿主机权限不匹配，优先调整 `DATAAGENT_RUNTIME_UID/GID` 对齐目录拥有者，或直接修正挂载目录权限。
- 需要扩展端口（如前端 80 → 8081）时，直接修改 `ports`。

### Opendataagent

`opendataagent` 是独立部署单元，不跟随根 `deploy/` 自动启动：

```bash
cd opendataagent/deploy
cp .env.example .env
bash ../scripts/start.sh
```

- Web 默认端口：`18080`
- Server 默认端口：`18900`
- MySQL 默认端口：`13306`
- 共享平台 skill 源码来自根目录 `skills/`，由 `OPENDATAAGENT_SHARED_SKILLS_PATH` 控制挂载路径
- OpenDataWorks 平台 skill 通过 `odw-cli` 调 Java agent API，不依赖 `portal-mcp`
- `opendataagent` 启动脚本会固定使用 Compose project `opendataagent`，避免与根 OpenDataWorks 部署因目录名同为 `deploy` 而互相识别为 orphan container。
- 如需手工执行 Compose，请使用 `docker compose --project-name opendataagent --env-file .env up -d --build`。
- 如果两套部署曾经都用默认 project `deploy` 启动，请在维护窗口分别停掉旧容器后重启；不要只对其中一套部署执行 `--remove-orphans`。

## 离线部署

1. 执行 `scripts/create-offline-package.sh`，生成 `opendataworks-deployment-*.tar.gz`（可指定 `--platform` 或镜像标签）。
2. 目标机器解压后包含：`deploy/docker-compose*.yml`、`deploy/.env.example`、`deploy/dataagent-runtime/`、`scripts/` 控制脚本、`deploy/docker-images/*.tar`。
3. 在解压目录内执行 `scripts/load-package-and-start.sh --package .`，加载镜像并启动。

## 裸机部署 (systemd)

### 后端

1. 将 `backend/target/*.jar` 拷贝至 `/opt/opendataworks/backend/opendataworks-backend.jar`。
2. 创建 systemd 服务 `/etc/systemd/system/opendataworks-backend.service`：

```ini
[Unit]
Description=OpenDataWorks Backend
After=network.target

[Service]
User=opendataworks
WorkingDirectory=/opt/opendataworks/backend
ExecStart=/usr/bin/java -jar opendataworks-backend.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

3. `sudo systemctl daemon-reload && sudo systemctl enable --now opendataworks-backend`。

### 前端

1. `npm run build` 产物复制到 `/opt/opendataworks/frontend/dist`。
2. 使用 Nginx 反向代理：

```nginx
server {
    listen 80;
    server_name opendataworks.example.com;

    location / {
        root /opt/opendataworks/frontend/dist;
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
    }
}
```

## 配置清单

| 组件 | 文件 | 说明 |
| --- | --- | --- |
| Backend | `application.yml` | DB、Dolphin/Dinky、日志、CORS |
| Frontend | `frontend/nginx.conf` | 反向代理 `/api/` 至 `backend:8080/api/`，代理 `/dataagent/` 至 `dataagent-frontend:80`，并代理 `/api/v1/dataagent/`、`/api/v1/nl2sql-admin/`、`/api/v1/nl2sql/` 至 `dataagent-backend:8900` |
| DataAgent Frontend | `dataagent/dataagent-frontend` | 智能问数独立前端、管理页与 Widget bundle |
| DataAgent Backend | `dataagent/dataagent-backend` | 智能问数 API、Skills 管理、NL2SQL 会话服务 |
| Opendataagent | `opendataagent/deploy/.env.example` | 独立 agent 平台的端口、数据库和管理员令牌 |
| Compose | `deploy/docker-compose.prod.yml` | 镜像/tag/端口/卷，主前端统一承载智能问数入口 |

## 滚动/重启

- Docker：`docker compose restart backend` / `logs -f backend`。
- systemd：`sudo systemctl restart opendataworks-backend`。
- 数据库迁移由 Flyway 自动执行；如需重置数据，请根据实际环境手工清理/初始化数据库。

## 镜像构建与大小控制

- 构建脚本：`scripts/build/build-multiarch.sh`，支持多架构 `linux/amd64,linux/arm64`。
- 根部署产物：`opendataworks-backend`, `opendataworks-frontend`, `opendataworks-dataagent-frontend`, `opendataworks-dataagent-backend`, `opendataworks-portal-mcp`。
- `opendataagent` 镜像与 release 由 `opendataagent/scripts/*` 单独构建。
- 构建前确保 `frontend/dist`、`backend/target` 已存在，否则脚本会自动触发构建。

## 运维 checklist

1. **启动前**：确认 `.env`、`application.yml`、数据库账号、Dolphin API 可连通。
2. **启动中**：观察 Compose/systemd 日志；若 Backend 启动 >60s，优先检查 MySQL 连接。
3. **启动后**：
   - `curl http://<host>:8080/api/v1/health`
   - `mysql -u opendataworks -popendataworks123 -h <db> opendataworks -e "SHOW TABLES"`
   - 前端页面是否可打开/登录
4. **巡检**：定期查看 `inspection_issue`、`task_execution_log`，配合 [testing-guide.md](testing-guide.md) 的脚本回归关键流程。
