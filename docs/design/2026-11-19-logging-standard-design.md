# 日志统一标准与 OpenDataWorks 实现设计

日期：2026-11-19
状态：待评审
关联计划：[2026-11-19-logging-standard-plan.md](../plans/2026-11-19-logging-standard-plan.md)
相关：OntoFoundry、DataAgent 各自仓库中的同名设计，统一标准一节逐字相同。

## 1. 现状

- 后端 Spring Boot 3.5.7，业务用 SLF4J（Lombok `@Slf4j`），Logback 默认文本格式输出到控制台；`com.onedata.portal` 默认 `debug`。
- 生产 Compose 给所有服务配了 Docker `json-file`（20m×5），另有 `log-collector` 容器跟随后端的 `docker logs` 追加到 `deploy/logs/opendataworks-backend.log`。追加文件不轮转、无限增长；断连期间的日志用 `--tail 0` 重连后丢失。
- 两份 Compose 都挂载了 `backend-logs:/app/logs`，但应用从未往该目录写日志。
- 没有请求关联 ID。

## 2. 目标

运维打开 `deploy/logs/opendataworks-backend/` 就能看到后端日志，按天归档、自动清理；与 OntoFoundry、DataAgent 格式相同，能用同一个请求 ID 串起跨服务的一次操作。去掉 log-collector。

## 2.1 统一标准（OntoFoundry、OpenDataWorks、DataAgent 三仓库相同）

三个仓库各自实现，下面的约定逐字相同。改动约定时三处同步修改。

#### 目录

部署目录下有一个 `logs/`，每个服务一个子目录，由 Compose 绑定挂载到容器内 `/var/log/<服务名>`：

```text
<部署目录>/logs/
  <服务名>/
    app.log                    当前日志
    app.log.2026-11-18.gz      已轮转的历史日志，按 UTC 日期命名
  dataagent-backend/
    app.log
    runtime.log                仅 DataAgent：Pi 运行时与沙箱的诊断
```

- 目录名与日志里的 `service` 字段相同，三个项目不重名：`of-backend`、`dataagent-backend`、`opendataworks-backend`。
- 日志位置由环境变量给出：OntoFoundry `ONTOFOUNDRY_LOG_DIR`、DataAgent `DATAAGENT_LOG_DIR`、OpenDataWorks 用 Spring Boot 标准的 `LOGGING_FILE_NAME`。未设置时（本地源码运行）只输出到控制台，不写文件。
- 宿主机上的日志文件属主是容器内运行服务的用户：OntoFoundry、OpenDataWorks 为 root，DataAgent 为运行时用户（默认 uid 1000，入口脚本负责建目录并授权）。

#### 轮转与保留

- 应用自己写文件、自己轮转：每天 UTC 零点切分，历史文件 gzip 压缩，保留 30 天，更早的自动删除。Java（Logback）的滚动策略要求文件名带序号，历史文件名为 `app.log.2026-11-18.0.gz`。
- 每个日志文件只能有一个进程写入。服务以单进程运行；改为多进程前必须先改为每进程一个文件。
- 同一行同时写控制台（stdout），`docker logs` 仍可用于实时查看。Docker 自身的日志用 `json-file` 驱动并限额（`max-size: 10m`、`max-file: 3`），只作临时缓冲，不作历史。
- 数据库、Redis、Nginx 等第三方服务不写 `logs/`，只用上面的 Docker 限额。Nginx 的访问记录已由后端的请求日志覆盖。

#### 格式

UTF-8，每个事件一行 JSON，不换行、不带颜色，异常堆栈作为字符串字段。

| 字段 | 说明 |
| --- | --- |
| `timestamp` | UTC，RFC 3339，毫秒，例如 `2026-11-19T08:03:12.345Z` |
| `level` | `DEBUG`、`INFO`、`WARN`、`ERROR` |
| `service` | 服务名，与目录名相同 |
| `logger` | 模块或类名 |
| `message` | 一句话说明 |
| `request_id` | HTTP 请求关联 ID，非请求上下文省略 |
| `task_id`、`run_id`、`topic_id`、`session_id` | 已有业务 ID，适用时附带，不新造 |
| `source` | 仅 DataAgent `runtime.log`：`runtime` 或 `sandbox` |
| `error_code`、`stack_trace` | 出错时附带 |

请求完成时记一条：`method`、`path`（不含查询串）、`status_code`、`duration_ms`。

#### 请求关联

HTTP 入口读取 `X-Request-ID`（1～64 位字母、数字、`-`、`_`），缺失或不合法时生成新值，并在响应头返回；调用其他服务时带上同一个值。后台任务显式带上自己的 `task_id`/`run_id`，不依赖已结束的请求上下文。

#### 级别

生产默认 `INFO`，第三方库 `WARN`，`DEBUG` 只临时开启。请求完成、任务开始与结束、超时、重试、异常各记一条；不记录逐 token 输出或完整协议帧。

#### 禁止记录

令牌、密码、API Key、Cookie、`Authorization` 头、私钥、带凭据的连接串和 URL、完整环境变量或配置对象；默认也不记录请求和响应正文、完整 SQL 及参数、用户提示词、模型回答、工具原始输出。异常消息和子进程输出写入前先脱敏并限制长度。

#### 库

- Python：标准库 `logging`，配一个约 40 行的 JSON Formatter；文件用 `TimedRotatingFileHandler(when="midnight", utc=True, backupCount=30)`，用 `namer`/`rotator` 压缩（Python 官方 Logging Cookbook 的写法）。不引入 structlog、loguru。
- Java：SLF4J + Logback（Spring Boot 自带）。Spring Boot 结构化日志接一个约 80 行的格式类，保证字段名和 UTC 时间（内置 logstash 格式跟随 JVM 时区）；滚动策略用 Spring Boot 配置项，不加依赖。
- Node（DataAgent 运行时）：stdout 只用于协议；诊断以 JSON 行写 stderr，由 Python 后端接收后写入 `runtime.log`。

#### 不在范围内

ELK、Loki、OpenTelemetry 等日志收集与检索系统；日志收集容器（OpenDataWorks 现有的 log-collector 删除）。

## 3. OpenDataWorks 的实现

### 3.1 后端

- `config/logging/StandardJsonLogFormatter`：实现 Spring Boot 的 `StructuredLogFormatter<ILoggingEvent>`，输出标准字段；时间按 UTC 格式化，不改变 JVM 时区（镜像为 `Asia/Shanghai`，Jackson 日期序列化等依赖它）。MDC 和 SLF4J 键值对中只取标准字段，消息与堆栈先脱敏、截断到 8 KB。
- `config/logging/RequestLoggingFilter`：最外层 Servlet 过滤器，处理 `X-Request-ID`（写 MDC `request_id`、响应头返回），请求结束记一条完成日志（`method`、`path`、`status_code`、`duration_ms`），跳过 `/api/v1/health`。
- `application.yml`：控制台和文件都用上面的格式类；只有设置 `LOGGING_FILE_NAME` 时写文件；滚动文件名 `${LOG_FILE}.%d{yyyy-MM-dd,UTC}.%i.gz`，`max-history: 30`，单日超过 1 GB 时当天再切分；`com.onedata.portal` 默认级别改为 `info`，由 `ODW_LOG_LEVEL` 覆盖。

### 3.2 部署

- `docker-compose.prod.yml`、`docker-compose.dev.yml`：删除 `log-collector` 服务和无用的 `backend-logs` 卷；后端设置 `LOGGING_FILE_NAME=/var/log/opendataworks-backend/app.log`、`ODW_LOG_LEVEL`，挂载 `./logs/opendataworks-backend`；全部服务 Docker 日志改为 10m×3（开发 Compose 补上限额）。
- 收集器镜像从 `scripts/build/build-images.sh`、`deploy/.env.example`、`scripts/dump-logs.sh` 的回退服务清单中去掉；`scripts/start.sh` 已预建 `deploy/logs/`。
- `deploy/README.md`、`docs/handbook/architecture.md`、`docs/handbook/operations-guide.md` 更新日志位置。升级时需手动 `docker rm -f opendataworks-log-collector`：Compose 不自动删除已从配置中移除的容器，`start.sh` 不加 `--remove-orphans`，因为默认项目名 `deploy` 可能与同机其他部署重名。

## 4. 取舍

- **自定义格式类而非内置 logstash 格式**：唯一差别是时间必须为 UTC；改 JVM 时区会影响业务日期，不可取。
- **默认级别降为 info**：生产不应常开 SQL 调试日志；排查时用 `ODW_LOG_LEVEL=debug` 临时打开。
- **去掉 log-collector**：应用直接写文件后，collector 的追加副本既不轮转也会在断连时丢行，没有保留价值。
