# 部署配置精简验证

日期：2026-10-16（Asia/Shanghai）。本轮延续
[配置收敛设计](../design/2026-06-09-dataagent-config-consolidation-design.md)和
[实施计划](../plans/2026-06-09-dataagent-config-consolidation-plan.md)，保留前一轮运行预算修改。

## 结果与配置规模

| 项目 | 修改前 | 修改后 |
| --- | ---: | ---: |
| deploy/.env.example 常规赋值 | 68 | 12 |
| 本地 deploy/.env 赋值 | 14 | 7 |
| dev 两个 DataAgent 服务合计注入字段（含本地 env_file） | 93 | 39 |
| prod 两个 DataAgent 服务合计注入字段（含本地 env_file） | 94 | 39 |

DataAgent 共享 DB/API/MCP 接线用一个 YAML anchor；任务、调度、池、Node 路径等
默认值不再在 Compose 重复声明。主静态前端不注入部署环境。已删除无消费者的
DATAAGENT_LLM_JSON_FILE/settings.json 打包逻辑、废弃审批等待配置及启动脚本的
无调用写配置函数；启动与运维文档改为 MYSQL_* 推导 Spring 连接。

本地实际 .env 仅清理三项与 MYSQL_* 推导完全一致的 Spring 字段，以及四项
没有消费者的 dev/Doris 网段字段。改前改后两个真实 Compose 的展开结果比较确认：
凭据、关键连接、镜像、端口、挂载、依赖、用户身份和网络均一致。未重启已有服务。
本地 .env 备份保存在 `/tmp/odw-deploy-env-before-20261016`，权限 0600；包含凭据的
临时 Compose 全量快照在比对结束后已删除，日志没有输出这些实际值。

## 验证命令与覆盖

Python 使用 `.venv-py313`，Compose 使用现有 Docker Compose v2.37.0（经 Podman
调用或独立 docker-compose 客户端），配置解析不启动容器。

```bash
dataagent/dataagent-backend/.venv-py313/bin/python -m pytest \
  tests/test_deploy_config_contract.py \
  tests/test_offline_package_env_policy.py \
  tests/test_deepeval_packaging_hooks.py \
  dataagent/dataagent-backend/tests/test_auth_config.py \
  dataagent/dataagent-backend/tests/test_runtime_budgets.py \
  dataagent/dataagent-backend/tests/test_sandbox_runner_main.py \
  dataagent/dataagent-backend/tests/test_runner_dockerfile.py -q
```

157 passed，0 failed，0 skipped；1 条既有 Pydantic class Config 弃用警告。
补充自定义宿主目录/skills 挂载及离线归档内 Compose 解析后，重跑前两个测试文件，
17 passed，0 failed，0 skipped。

新 Compose 契约测试对 dev/prod 均覆盖：

- 最小模板直接解析，应用 Settings 恢复默认 Pi、200 次工具预算、8 并发等值。
- 自定义主库/Agent 凭据隔离、业务库名、服务令牌和 MCP header 同步。
- 已有外部 Spring 数据库三项覆盖仍优先于 MYSQL_* 推导。
- 原生 TASK_*/SCHEDULE_*、沙箱池与引擎切换、child 镜像、UID/GID、宿主挂载、
  MCP 挂载路径和 keep-alive 覆盖继续有效。
- MySQL 健康检查读取容器密码（包括含空格的值），不是固定密码字面量。

离线测试执行实际打包脚本及 tar/xz 归档，只把镜像 pull/save 替换为既有 fake CLI：
模板、镜像标签、skills 路径、container 模式都正确；归档不含实际 .env 或旧
settings.json。提取归档内 dev/prod Compose，复制归档模板为临时 .env 后再次用
真实 Compose CLI 解析通过。加载脚本保留已有 .env 的回归也通过。

```bash
bash -n scripts/start.sh scripts/create-offline-package.sh scripts/load-package-and-start.sh
podman compose --env-file deploy/.env.example -f deploy/docker-compose.dev.yml config --format json
podman compose --env-file deploy/.env.example -f deploy/docker-compose.prod.yml config --format json
podman exec data-portal-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin ping -h localhost -uroot'
git diff --check
```

Shell 语法、两套 Compose 解析、真实本地 MySQL probe（mysqld is alive）、相对文档
链接及 diff 空白检查通过。Compose 全量输出在内存中解析，仅打印字段名/计数和
相等判断。

## 覆盖边界

本轮没有构建镜像或重启整套部署，没有重新执行模型问数/浏览器流程。原有 8900
服务及 MySQL/Redis 容器保持运行。配置实际注入与应用默认通过 Compose/Pydantic
契约验证；镜像 ENV 默认也由 Dockerfile 回归覆盖。前一轮真实 Pi HTTP smoke 的
结果和 Portal MCP 不可达的限制，见
[运行预算验证报告](2026-10-15-dataagent-runtime-budgets-validation.md)。

已有非默认旧 DATAAGENT_TASK_* / DATAAGENT_SCHEDULE_* 等前缀需要按部署文档迁移，
不新增运行时兼容链。根 deploy/.env 的本地清理不改变独立 opendataagent 或 DataAgent
Python 本地开发 .env。
