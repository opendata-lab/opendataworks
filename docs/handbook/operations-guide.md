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
- Compose 服务：`mysql`、`redis`、`backend`、`frontend`、`odw-mcp-server`、`log-collector`
- DataAgent 不在本 Compose 中，由 [opendataagent](https://github.com/opendata-lab/opendataagent) 单独部署（可在另一台机器上）；接入步骤见 `deploy/README.md` 的“接入 DataAgent”
- **数据库自动初始化**：MySQL 容器首次启动时，会自动执行 `deploy/database/mysql/` 目录下的初始化脚本，创建 `opendataworks` 数据库和用户。表结构由后端服务的 Flyway 自动创建。
- 环境变量重点：
  - `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE=opendataworks`, `MYSQL_USER=opendataworks`
  - Spring 连接由 MYSQL_* 自动推导；连接外部库时才填三项 SPRING_DATASOURCE_* 覆盖
  - `AGENT_API_SERVICE_TOKEN`、`ODW_MCP_SERVER_TOKEN`
  - DolphinScheduler 配置请在系统管理界面进行
- 需要扩展端口（如前端 80 → 8081）时，直接修改 `ports`。

## 离线部署

1. 执行 `scripts/create-offline-package.sh`，生成 `opendataworks-deployment-*.tar.xz`（可指定 `--platform` 或镜像标签；压缩级别可用 `OPENDATAWORKS_XZ_LEVEL` 覆盖，默认 6）。全部镜像会去重保存为单个 `deploy/docker-images/all-images.tar`，再用 `xz -T0` 压缩整包。
2. 目标机器需安装 `xz`/`xz-utils`，解压（`tar -xJf opendataworks-deployment-*.tar.xz`）后包含：`deploy/docker-compose*.yml`、`deploy/.env.example`、`scripts/` 控制脚本、`deploy/docker-images/all-images.tar`。
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
| Frontend | `deploy/docker/nginx/frontend.conf` | 宿主机挂载的门户 nginx：反向代理 `/api/` 至 `backend:8080/api/`；DataAgent 的 `/dataagent/widget/`、`/api/v1/dataagent/`、`/api/v1/nl2sql-admin/`、`/api/v1/nl2sql/` 转发以注释示例给出，接入 DataAgent 时填实际地址启用 |
| Compose | `deploy/docker-compose.prod.yml` | 镜像/tag/端口/卷 |

## 滚动/重启

- Docker：`docker compose restart backend` / `logs -f backend`。
- systemd：`sudo systemctl restart opendataworks-backend`。
- 数据库迁移由 Flyway 自动执行；如需重置数据，请根据实际环境手工清理/初始化数据库。

## 镜像构建与大小控制

- 构建脚本：`scripts/build/build-multiarch.sh`，支持多架构 `linux/amd64,linux/arm64`。
- 根部署产物：`opendataworks-backend`, `opendataworks-frontend`, `opendataworks-odw-mcp-server`。DataAgent 镜像（`oda-*`）由 opendataagent 仓库构建。
- 构建前确保 `frontend/dist`、`backend/target` 已存在，否则脚本会自动触发构建。

## 运维 checklist

1. **启动前**：确认 `.env`、`application.yml`、数据库账号、Dolphin API 可连通。
2. **启动中**：观察 Compose/systemd 日志；若 Backend 启动 >60s，优先检查 MySQL 连接。
3. **启动后**：
   - `curl http://<host>:8080/api/v1/health`
   - `mysql -u opendataworks -popendataworks123 -h <db> opendataworks -e "SHOW TABLES"`
   - 前端页面是否可打开/登录
4. **巡检**：定期查看 `inspection_issue`、`task_execution_log`，配合 [testing-guide.md](testing-guide.md) 的脚本回归关键流程。
