# 离线部署包与运行时去冗余设计 (Deployment Package Slimming)

- 日期: 2026-11-04
- 主题 slug: `deployment-package-slimming`
- 影响栈: 部署配置（`deploy/`）、打包与运维脚本（`scripts/`）、部署契约测试（`tests/`）、项目文档（`README.md`、`deploy/README.md`）

## 现状

1. **Redis 历史遗留**：
   - 早期单体架构下，智能问数 DataAgent（FastAPI + asyncio + redis-py）引入了 Redis 用于短状态租约与任务队列协调。
   - 在 2026-10-27 DataAgent 拆分到独立仓库 [opendataagent](https://github.com/opendata-lab/opendataagent)（commit `1a80c8f5`）后，DataAgent 已自带独立的 Compose 及 `oda-deployment-*.tar.xz` 离线包（内含独立 Redis 与 MySQL）。
   - 主系统 OpenDataWorks 的 Java 17 / Spring Boot 3.5 后端对 Redis **零依赖**（Maven `pom.xml` 和 `application.yml` 中均无任何 Redis 依赖或配置），前端 Nginx 与 `odw-skills` 亦不连接 Redis。
   - 但在 `deploy/docker-compose.{prod,dev}.yml`、`scripts/create-offline-package.sh`、`scripts/load-images.sh`、`scripts/build/build-images.sh` 及 `scripts/dump-logs.sh` 中，`redis:7.2-alpine` 依然作为服务和镜像被拉取、打包与启动，产生了不必要的镜像体积、构建耗时、内存与端口占用（6379）。

2. **Opik 目录未排除**：
   - `deploy/opik/` 属于 DataAgent 评测时期的本地验证依赖集合（含 8 个容器编排与 clickhouse/zookeeper/minio/opik 配置）。
   - `scripts/create-offline-package.sh` 打包时盲目拷贝 `deploy/`，将 `deploy/opik/` 整体打入主离线部署包，但离线包中并未包含任何 Opik 镜像，导致包内存在无法脱机运行的死文件。

3. **历史废弃代码**：
   - `scripts/create-offline-package.sh` 仍保留对已删除目录 `database/mysql` 的冗余判断代码（真实初始化 SQL 早已统一在 `deploy/database/mysql/`）。

## 问题

- 离线包体积与传输耗时冗余：包含无用的 Redis 镜像归档和不可用的 Opik 评测编排。
- 宿主机资源浪费：默认 Compose 会无意义拉起一个空转的 Redis 容器并占用宿主机 6379 端口。
- 概念认知与契约混乱：部署文档与 README 声称拉起 Redis，但业务系统根本不使用。

## 方案

1. **彻底移除运行时 Redis 服务**：
   - 从 `deploy/docker-compose.prod.yml` 与 `deploy/docker-compose.dev.yml` 中删除 `redis` 容器配置。
   - 清理日志收集 sidecar 注释中关于 `mysql/redis 基础设施` 的描述。
   - 从 `scripts/dump-logs.sh` 中的 `SERVICES` 列表剔除 `redis`。

2. **离线包与镜像构建剔除 Redis**：
   - 从 `scripts/create-offline-package.sh` 的 `EXTRA_IMAGES` 中删除 `redis-7.2-alpine.tar|docker.io/library/redis:7.2-alpine|redis:7.2-alpine`。
   - 从 `scripts/load-images.sh` 的 `LEGACY_IMAGES`、`IMAGES` 列表以及 `grep` 过滤正则中删除 `redis`。
   - 从 `scripts/build/build-images.sh` 中删除 `REDIS_IMAGE` 变量、拉取循环与导出列表，调整步骤编号。

3. **彻底删除 Opik 与清理废弃打包逻辑**：
   - 彻底删除 `deploy/opik/` 整个目录（包括上游 compose 与配置等 16 个文件）。
   - 在 `scripts/create-offline-package.sh` 拷贝 `deploy/` 时增加 `--exclude='opik'` 与 `--exclude='./opik'`。
   - 移除 `create-offline-package.sh` 中检查根目录 `database/mysql` 的已弃用死代码。
   - 清理 `scripts/build/` 中遗留的 Python `PIP_*` 镜像构建变量（本仓库已无任何 Python 镜像）以及 `BUILD_ODW_MCP_SERVER` 废弃选项。

4. **契约测试与文档同步**：
   - 在 `tests/test_deploy_config_contract.py` 中增加断言：渲染出的 Compose 服务中坚决不包含 `redis` 服务，且无端口 6379 暴露。
   - 在 `tests/test_offline_package_env_policy.py` 中断言：主离线包打包排除 `opik` 目录，且脚本不再声明打包 `redis` 镜像。
   - 更新 `deploy/README.md`、`README.md`、`README_en.md`，同步移除关于门户启动 Redis 的陈旧描述。

## 接口与兼容性影响

- **端口**：释放宿主机 `6379` 端口，消除与用户本地既有 Redis 的端口冲突。
- **配置**：`deploy/.env.example` 原本就未包含任何 Redis 变量，无需修改环境变量契约。
- **向后兼容**：旧版包含 `redis` 镜像的离线包在解压后，新版 `load-images.sh` 仍可通过 `all-images.tar` 正常加载核心业务镜像；即使回退逐镜像加载，也不再因缺失 `redis-7.2-alpine.tar` 而中断。

## 取舍

- 保留 `deploy/opik/` 源码在仓库中（供开发期评测参考），仅在生产/离线安装包（release tar）构建时排除，避免破坏既有的本地评测验证。
