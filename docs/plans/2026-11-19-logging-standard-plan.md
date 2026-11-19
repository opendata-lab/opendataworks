# 日志统一标准计划（OpenDataWorks）

日期：2026-11-19
设计：[2026-11-19-logging-standard-design.md](../design/2026-11-19-logging-standard-design.md)
涉及：后端（`backend/app`）、部署。OntoFoundry、DataAgent 在各自仓库按同一标准实现。

## 任务

1. `backend/app/src/main/java/com/onedata/portal/config/logging/`：`StandardJsonLogFormatter`、`RequestLoggingFilter`。
2. `backend/app/src/main/resources/application.yml`：结构化格式、滚动策略、默认级别。
3. `deploy/docker-compose.prod.yml`、`deploy/docker-compose.dev.yml`：去掉 log-collector 与 `backend-logs`，挂载 `logs/opendataworks-backend`，Docker 日志限额。
4. `scripts/build/build-images.sh`、`scripts/dump-logs.sh`、`scripts/start.sh`、`deploy/.env.example`：去掉收集器相关内容。
5. `deploy/README.md`、`docs/handbook/architecture.md`、`docs/handbook/operations-guide.md`。

## 验证

- 新增单元测试：`StandardJsonLogFormatterTest`（标准字段、UTC、字段白名单、堆栈在行内且脱敏、截断）、`RequestLoggingFilterTest`（请求 ID 透传、非法值替换、MDC 清理、完成日志、跳过健康检查）。
- 在 `maven:3.9-eclipse-temurin-17` 容器中离线运行（本机无 JDK）：新增测试通过；全量 `mvn test` 中 13 个集成测试因容器内没有 MySQL（`Communications link failure`）无法启动上下文，与本改动无关，其余通过。
- 借一个会启动 Spring 上下文的集成测试确认：控制台输出为标准 JSON、UTC；设置 `LOGGING_FILE_NAME` 后 `app.log` 写入且全部为 JSON 行。
- `docker compose config` 校验两份 Compose（以 `.env.example` 作 `.env`）。

未验证：按天滚动与 30 天清理没有在本仓库实际跨日运行，依赖 Logback `SizeAndTimeBasedRollingPolicy` 的既有行为。

## 上线与回退

- 发布新后端镜像，按 README 重建容器，并删除旧 `opendataworks-log-collector` 容器。
- 回退：去掉 `LOGGING_FILE_NAME` 即只输出控制台；恢复旧 Compose 即恢复 collector。
