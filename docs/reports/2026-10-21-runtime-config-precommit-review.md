# 运行配置提交前审查

审查日期：2026-10-21（Asia/Shanghai）。范围为本次运行预算、部署配置精简及其
测试/文档的全部未提交改动。设计与执行计划继续使用
[运行预算设计](../design/2026-10-15-dataagent-runtime-budgets-design.md)、
[运行预算计划](../plans/2026-10-15-dataagent-runtime-budgets-plan.md)及
[部署收敛设计](../design/2026-06-09-dataagent-config-consolidation-design.md)。

## 发现与修复

1. **Bash 超时/取消遗漏后台子进程。** 实测 `sleep 2 & wait`，50ms 超时约
   2051ms 后才返回。外层 shell 被终止，后台子进程仍持有输出管道。现在为 Bash
   建立独立 POSIX 进程组，超时/取消终止整组；预先取消的命令不再启动。修复后
   相同探针约 54ms 返回。新增测试确认后台子进程之后也不会写入文件；真实 Cell
   超时回归不再使用 exec 避开这个问题。
2. **Podman 部署示例与标准镜像不一致。** 标准 runner 仅复制 Docker CLI，原
   注释建议切换 podman CLI 会失败。已删除错误示例，明确 Podman 宿主应挂载其
   兼容 socket，runner 保持 Docker CLI 默认；自行带 Podman CLI 的镜像才可改该项。
3. **SDK 测试污染后续测试的 PATH。** 组合运行最初出现 3 个找不到 bash 的
   失败及 11 个找不到 Compose/xz 的跳过。既有 executor 测试使用临时 PATH 并由
   实际代码写入 os.environ，却未还原。fixture 现在在每个用例结束后恢复完整
   环境。最终组合运行全部通过且没有跳过；没有通过隐藏失败或补兼容分支绕过。

已复核全局/助手优先级、提交期总预算保留、控制面到子进程的 limits 传递、Pi
协议必填字段、镜像内 Node 默认、原生高级覆盖、外部 Spring DB 覆盖、令牌映射、
离线模板改写及已有 .env 保留。未发现剩余阻断本次提交的问题。

## 回归结果

- Python：**363 passed，0 failed，0 skipped**；5 条既有 Pydantic/FastAPI 弃用警告。
- Pi：TypeScript 编译及 **158 passed，0 failed，0 skipped**。
- 助手配置页：**11 passed，0 failed**。
- Shell 语法、源码/离线归档内两套真实 Compose 解析及 git diff 空白检查通过。

Python 使用已验证可导入运行时依赖的 `.venv-py313`（Python 3.13.4）：

```bash
dataagent/dataagent-backend/.venv-py313/bin/python -m pytest \
  dataagent/dataagent-backend/tests/test_runtime_budgets.py \
  dataagent/dataagent-backend/tests/test_pi_runtime_dispatch.py \
  dataagent/dataagent-backend/tests/test_pi_runtime_contract.py \
  dataagent/dataagent-backend/tests/test_pi_runtime_e2e.py \
  dataagent/dataagent-backend/tests/test_agent_runtime.py \
  dataagent/dataagent-backend/tests/test_task_executor.py \
  dataagent/dataagent-backend/tests/test_auth_config.py \
  dataagent/dataagent-backend/tests/test_sandbox_task_main.py \
  dataagent/dataagent-backend/tests/test_sandbox_runner_main.py \
  dataagent/dataagent-backend/tests/test_agent_profile_service.py \
  dataagent/dataagent-backend/tests/test_admin_routes.py \
  dataagent/dataagent-backend/tests/test_routes_contract.py \
  dataagent/dataagent-backend/tests/test_task_coordinator.py \
  dataagent/dataagent-backend/tests/test_agent_drafts.py \
  dataagent/dataagent-backend/tests/test_runner_dockerfile.py \
  tests/test_deploy_config_contract.py \
  tests/test_offline_package_env_policy.py \
  tests/test_deepeval_packaging_hooks.py -q
```

Pi：成功加载仓库 nvm use 后再 nvm use 22.19.0，执行
`npm --prefix dataagent/dataagent-runtime-pi test`。Vue：仓库 nvm use（20.19.0）后执行
`npm --prefix dataagent/dataagent-frontend test -- src/views/intelligence/__tests__/AgentDetailView.spec.js`。
Shell 检查：`bash -n scripts/create-offline-package.sh scripts/load-package-and-start.sh scripts/start.sh`。

## 本轮真实 HTTP smoke

- 复用 Podman MySQL `data-portal-mysql` 的实际端口 `127.0.0.1:3306`，业务 schema
  opendataworks。原 8900 服务正在运行，因此新建临时会话库
  `dataagent_review_smoke_20261021` 并运行 alembic upgrade head；从原 dataagent
  只复制 provider/MCP 配置，未改动原会话数据。
- Redis 复用 Podman `odw-local-redis`，127.0.0.1:6379，隔离使用 DB 15。
- Python 为 `.venv-py313`，Node 22.19.0；启动独立 8970 后端，显式选择
  pi_agent_core 和本地编译的 Pi。真实 provider 凭据和真实 deepseek-v4-pro 模型。
- 本次缩短 background Bash 超时至 2s、SQL 读/写预算至 1s，仅通过 smoke
  进程环境覆盖；仓库默认仍是 900s / 900s / 60s。总预算 1800s、40 轮、200 次
  工具调用保持默认。日志和持久化 engine_kind 均确认 Pi 实际执行。
- workspace 仅使用本次 `/tmp/odw-review-runtime-20261021`，未使用原会话目录。

| 场景 | 结果 |
| --- | --- |
| HTTP 创建话题、auto 提交 | accepted=true，获得 task_id |
| 真实模型直接回复 | waiting → running → finished；review-smoke-ok 持久化 |
| 模型调用 sleep 5 & wait | Bash 2s 超时报错，最终 review-timeout-ok 持久化；没有等到 5s |
| 工具已开始后 HTTP cancel | waiting → running → suspended；run.cancelled 持久化 |
| SDK 事件及 SSE | 三个任务均返回已持久化事件，流在终态结束 |

已通过 API 清理话题，停止本次 8970 后端，删除临时 runtime 目录、schema 和新增
授权；已有 8900 服务和 MySQL/Redis 容器保持运行。

## 验证边界

本轮未重启整套部署、未构建发布镜像或走真实子容器跳转。Portal MCP 8801 和主
后端 8080 当前均未运行，业务 NL2SQL 结果路径仍未验证成功；本次真实模型 smoke
覆盖实际受改动影响的 Pi 工具超时、取消、任务协调及持久化路径。静态配置页只改
文案并运行已有 Vue 测试，没有额外浏览器验证。提交不等于已经部署。
