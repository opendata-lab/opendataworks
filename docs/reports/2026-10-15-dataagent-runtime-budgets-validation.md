# DataAgent 运行预算验证

日期：2026-10-15（Asia/Shanghai）。
设计：[运行预算与默认配置](../design/2026-10-15-dataagent-runtime-budgets-design.md)。
计划：[实施计划](../plans/2026-10-15-dataagent-runtime-budgets-plan.md)。

## 结果

- Python 331 passed，0 failed，0 skipped，5 条既有 Pydantic/FastAPI 弃用警告。
- Pi TypeScript 编译及 Node 测试 155 passed，0 failed，0 skipped。
- 助手配置 Vue 测试 11 passed，0 failed。
- dev/prod Compose 均可解析；使用 `.env.example` 时 backend 和 sandbox runner
  都解析为 `pi_agent_core`。
- `git diff --check` 通过。

覆盖模式选择、默认继承、助手轮数覆盖、环境/显式设置优先级、非法值与超时链、
已提交任务总预算、沙箱分发序列化、子进程 stdio init、真实 Bash 超时、工具调用
恰好达到/超出预算、真实构建 Pi 子进程、现有助手草稿/发布及路由契约。

## 测试命令

以下在仓库根目录执行；Pi 依赖要求 Node 22.19.0，因此先成功执行仓库 `nvm use`，
再切到已安装的 22.19.0。Vue 使用仓库 20.19.0。

```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use && nvm use 22.19.0
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
  dataagent/dataagent-backend/tests/test_agent_drafts.py -q
npm --prefix dataagent/dataagent-runtime-pi test
```

```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use
npm --prefix dataagent/dataagent-frontend test -- src/views/intelligence/__tests__/AgentDetailView.spec.js
podman compose --env-file deploy/.env.example -f deploy/docker-compose.dev.yml config --format json
podman compose --env-file deploy/.env.example -f deploy/docker-compose.prod.yml config --format json
git diff --check
```

Compose 输出由脚本在内存中解析，只打印引擎字段，没有输出完整环境或凭据。

## 本地真实 HTTP smoke

- Python：`dataagent/dataagent-backend/.venv-py313`，3.13.4；启动前已验证
  fastapi、uvicorn、alembic、pymysql、anyio、claude_agent_sdk 可导入。
- MySQL：已有 Podman `data-portal-mysql`，实际发布 `127.0.0.1:3306`，并非 dev
  模板 3316。业务 schema `opendataworks`。原会话库 `dataagent` 存在且有正在运行
  的本地服务，因此建立临时 schema `dataagent_budget_smoke_20261015` 做隔离，
  使用标准 dataagent 用户，先运行 `alembic upgrade head`，复制现有模型/MCP
  配置用于真实执行，不修改原库和已有助手配置。
- Redis：复用已运行的 Podman `odw-local-redis`，`127.0.0.1:6379`，本次 DB 15。
  无需 bootstrap 或重启现有容器。
- 后端：单独启动 `uvicorn main:app --host 127.0.0.1 --port 8970`，未重启已有
  8900 服务；显式 `DATAAGENT_RUNTIME_KIND=pi_agent_core`，运行本地已编译 Pi，
  无子容器跳转。宿主根设置为仓库，现有 `.env` 的 runtime root 实际解析为
  `/private/tmp/dataagent_runtime`。
- 模型：复制已有配置，使用真实 provider 凭据与 `deepseek / deepseek-v4-pro`，
  非 mock；持久化记录 `engine_kind=pi_agent_core`。
- 请求全部通过真实 HTTP，包含 `execution_mode=auto`。Pi 日志有效预算为
  `max_turns=40, max_tool_calls=200, total_timeout_seconds=1800,
  idle_timeout_seconds=300, bash_timeout_seconds=900`。

| 场景 | 结果 |
| --- | --- |
| 创建话题、提交任务 | `accepted=true`，返回 task_id |
| 真实模型直接回复 smoke-ok | waiting → running → finished，最终消息 smoke-ok 已持久化 |
| 真实模型调用 Bash printf | waiting → running → finished；tool.started/tool.completed，最终回复 budget-smoke-ok |
| SDK 事件查询和 SSE 读取 | 返回持久化事件；普通回复 9 条、Bash 16 条；SSE 可读取并终止 |
| 运行中取消 | POST cancel 成功，waiting → running → suspended，run.cancelled 已持久化 |
| 业务 NL2SQL：最近 30 天工作流发布次数趋势 | 已接受并开始执行；Portal MCP `127.0.0.1:8801` 不可达，日志 fetch failed；主动取消，未验证业务 SQL 结果 |

## 清理与覆盖边界

已通过 API 删除四个 smoke 话题，停止本次 8970 后端，删除本次工作区、临时
schema 及对应新增授权。已有 MySQL/Redis 容器、原 `dataagent` schema、8900
服务和原助手显式轮数均保留。

本次没有成功完成业务 NL2SQL，也没有执行真实子容器跳转或浏览器交互；沙箱传参
由契约/跨进程测试覆盖，Vue 文案由现有页面测试覆盖。Nginx 模板和 Compose 已
修改，但未部署真实代理容器。不得将这些层描述成完整端到端已验证。
