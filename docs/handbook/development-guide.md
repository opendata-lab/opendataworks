# 开发指南

合并原 `START.md`、`QUICK_START_GUIDE.md` 以及 backend/README 中的关键步骤，覆盖本地开发的准备、启动、调试流程。

## 环境要求

| 组件 | 版本建议 |
| --- | --- |
| Java | 8 或 11 (Gradle/IntelliJ) |
| Node.js | 20.19.0+（按 `.nvmrc`） |
| MySQL | 8.0+ |
| Docker / Podman (可选) | Docker 24+ 或兼容 Podman |

## 第一步：准备数据库

使用本地 MySQL 创建数据库和用户（Flyway 首次启动会自动建表/迁移）：

```bash
mysql -u root -p <<'SQL'
CREATE DATABASE opendataworks DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'opendataworks'@'%' IDENTIFIED BY 'opendataworks123';
GRANT ALL PRIVILEGES ON opendataworks.* TO 'opendataworks'@'%';
FLUSH PRIVILEGES;
SQL
```

> 如果已经通过 `deploy/docker-compose.dev.yml` 或等价的 Podman compose 启动了 MySQL，可复用该容器的账号/密码，或在 `deploy/.env` 中调整后重启。

## 第二步：启动后端 (Spring Boot)

```bash
# 在仓库根目录执行
mvn -f pom.xml -pl backend -am spring-boot:run
# 或使用 IntelliJ 运行 DataPortalApplication
```

- 默认端口 `8080`，上下文路径 `/api`。
- 配置文件 `application.yml` 中的 `spring.datasource.url` 已指向 `opendataworks`。
- 开启调试日志：`mvn -f pom.xml -pl backend -am spring-boot:run -Dspring-boot.run.arguments="--logging.level.com.onedata.portal=DEBUG"`。

## 第三步：启动 DataAgent

DataAgent 的 `config.py` 使用相对路径 `.env`，因此本地启动时必须先进入 `dataagent/dataagent-backend` 目录。如果从仓库根目录或其他目录直接运行 `uvicorn`，可能读不到本地 `.env`，并回退到容器默认路径 `/dataagent_runtime`；该路径在 macOS 本地根文件系统上不可写，会导致会话创建后报 `Errno 30: Read-only file system`。

本地 `.env` 至少需要使用可写的运行时目录，并指向仓库内的 Skills：

```dotenv
DATAAGENT_RUNTIME_ROOT=/tmp/dataagent_runtime
DATAAGENT_HOST_ROOT=/tmp/dataagent_runtime
SKILLS_ROOT_DIR=<仓库绝对路径>/dataagent/.claude/skills
```

使用项目虚拟环境启动：

```bash
# 先在仓库根目录执行
cd dataagent/dataagent-backend

./.venv-py313/bin/python -c "import fastapi, uvicorn, alembic, pymysql, anyio, claude_agent_sdk"
./.venv-py313/bin/alembic upgrade head
./.venv-py313/bin/uvicorn main:app --host 127.0.0.1 --port 8900
```

启动后不要只检查 `/api/v1/nl2sql/health`。至少在真实前端中选择一个带 Skills 的助手并提交一次最小会话，确认 topic 创建、任务执行、事件流与最终消息持久化都成功。健康检查为绿不代表会话工作区一定可写。

## 第四步：启动前端 (Vue3)

```bash
cd frontend

export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm use || nvm install

npm install
npm run dev
```

- 开发服务器默认运行在 `http://localhost:3000`。
- 默认代理已指向 `http://localhost:8080`、`http://localhost:8900`；如需修改后端或 DataAgent 地址，请编辑 `frontend/vite.config.js` 中的 `server.proxy`。
- 生产构建：`npm run build` → 静态文件输出到 `frontend/dist`。

## 联调确认清单

1. 打开 `http://localhost:3000`；当前默认开启匿名访问，本地开发默认无需登录。
2. 表管理 → 查看示例表 `ods_user`，确认字段 & Doris 配置渲染正常。
3. 任务管理 → 执行 `sample_batch_user_daily`，在 DolphinScheduler 中新增临时工作流。
4. 进入血缘视图，确认 DAG 渲染、节点点击信息正常。
5. 检查 `task_execution_log`、`inspection_issue` 是否写入记录。

## 常见问题

| 问题 | 排查方式 |
| --- | --- |
| “Access denied for user 'opendataworks'” | 确认数据库用户/密码已创建；`mysql -uopendataworks -popendataworks123 opendataworks -e "SHOW TABLES;"` |
| 调度接口 500 | 检查后端日志；确认系统管理 -> Dolphin 配置（URL/token/Project）正确且 OpenAPI 可访问 |
| 前端跨域 | 检查 `frontend/vite.config.js` 中 `server.proxy` 是否指向正确的 backend / dataagent 地址 |
| DataAgent 会话报 `/dataagent_runtime` 只读 | 确认从 `dataagent/dataagent-backend` 目录启动，并检查 `.env` 中 `DATAAGENT_RUNTIME_ROOT` / `DATAAGENT_HOST_ROOT` 指向本地可写目录 |
| 示例数据缺失 | 确认 Flyway 迁移已完成；如需演示数据可根据业务手工插入 |
| Doris 集群不可用 | 在 `doris_cluster` 表中配置 FE 地址，并在 Portal 中标记 `is_default=1` |

## 推荐工作流

1. **代码变更**：使用 feature 分支；需要的情况下更新数据库脚本并编写对应变更说明。
2. **单元/集成测试**：`mvn -f pom.xml -pl backend -am test`（支持 `-Dtest=TaskExecutionWorkflowTest` 等定点执行）。
3. **前端校验**：`npm run test` (如有) + 手动验证关键流程。
4. **文档同步**：凡是增加接口、表字段、配置项，都在本手册中更新相应章节。
