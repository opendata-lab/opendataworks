# DataAgent MCP 与模型供应商注册表实施计划

配套设计：[`../design/2026-09-11-dataagent-mcp-provider-registry-design.md`](../design/2026-09-11-dataagent-mcp-provider-registry-design.md)

## Tasks

1. 新增 Alembic 迁移，创建 `da_mcp_server` 与 `da_model_provider`，把
   `da_agent_settings.default` 的 provider JSON/旧列幂等迁移到 provider registry；增加迁移
   结构与数据保留回归测试。
2. 在 DataAgent store 中实现 MCP/provider 的列表、读取、创建、更新、删除与批量导入所需事务，
   并提供 portal plugin 的一次性 `insert-if-absent` bootstrap。
3. 增加 MCP Pydantic schema、FastAPI 路由和服务层校验；锁定 configured/plugin 分组、两种导入
   格式、plugin 只读、404/400 语义。
4. 将 agent capability 与 runtime MCP 解析改为从 `da_mcp_server` 读取；portal 数据范围头在运行
   时叠加，配置本身不再从环境变量 fallback。
5. 扩展 Pi MCP init contract 和 client，使数据库中的 http/sse/stdio 配置都能到达实际 transport；
   增加序列化和 transport 选择测试。
6. 增加 provider CRUD schema/路由和服务，将 provider 列表、模型检测、默认选择与
   `resolve_runtime_provider_selection` 切到 `da_model_provider`；支持自定义 provider id，并保持
   `getSettings`/`updateSettings` 的当前选择契约。
7. 同步 DataAgent 前端：MCP 更新使用 PATCH；plugin 开关不可操作；provider 保存以后端 registry
   返回为准；按参考层级拆开 Skill 总数和已启用数，并检查评测结果页、智能体页现有交互。
8. 执行定向测试、全量后端 pytest、Pi runtime test、指定前端 vitest/build；对每条修复做 mutation
   证伪并记录失败测试。
9. 执行本地 MySQL/Redis + DataAgent + frontend smoke：通过浏览器真实创建、启停、更新和删除
   MCP，通过真实 API 创建、更新、回读和删除 provider，并用一次真实 Pi 请求确认运行 context
   使用数据库 MCP。模型、Skill、评测与智能体的未触及交互继续由现有 view 回归覆盖。

## Verification matrix

| 修复 | 正向证据 | 证伪 mutation |
| --- | --- | --- |
| MCP API 与持久化 | 路由契约测试 + MySQL API smoke 后查表 | 临时移除路由或 store 写入，断言 404/查表失败 |
| plugin 只读 | PATCH/DELETE plugin 返回 400 | 临时去掉 source guard，保护测试应变红 |
| MCP runtime 读库 | runtime/任务 context 断言使用 DB 行且忽略冲突 env | 临时恢复 env 拼装，DB 优先级测试应变红 |
| Pi stdio/http/sse | TypeScript transport 与 Python init payload 测试 | 临时删除 command/args/env 转发或 stdio 分支，对应测试应变红 |
| provider 多行 CRUD | API/store 测试覆盖两个 provider 与自定义 id | 临时固定四 provider 或回读旧 JSON，列表/自定义测试应变红 |
| 当前 provider/model | 更新/删除后 runtime selection 测试 | 临时绕过 registry，选择与删除回退测试应变红 |
| 旧配置迁移 | 迁移测试断言 JSON 与旧列均完整保留 | 临时去掉 legacy backfill，迁移测试应变红 |
| 前端契约与层级 | views vitest + build + 浏览器真实数据检查 | 临时恢复 PUT/plugin 可切换/合并统计，对应 view/API 测试应变红 |

mutation 操作只在工作区临时应用，运行目标测试取得预期失败后立即恢复，再跑正向测试；不提交故意
失败的代码。

## Commands

```bash
cd dataagent/dataagent-backend
.venv-py313/bin/python -m pytest tests/ -q

cd dataagent/dataagent-runtime-pi
npm test

export NVM_DIR="$HOME/.nvm"
. "$NVM_DIR/nvm.sh"
nvm use
cd dataagent/dataagent-frontend
npx vitest run src/views/
npx vite build
```

本地全链路按仓库 `AGENTS.md` 使用 MySQL `127.0.0.1:3316/dataagent`、Redis
`127.0.0.1:6379`、`.venv-py313` 和 `DATAAGENT_RUNTIME_KIND=pi_agent_core`。如果 provider
凭据、portal-mcp 或容器运行时不可用，报告中逐项注明通过的真实层级和未覆盖的 full-flow 路径，
不把单测描述为全链路验证。

## 本次验证结果

- MySQL：`127.0.0.1:3306`，业务库 `opendataworks`，session/registry 库 `dataagent`；迁移由续做前
  已执行的 `alembic upgrade head` 提供。
- Redis：`127.0.0.1:6379`，复用本机已运行实例；Python：
  `dataagent/dataagent-backend/.venv-py313/bin/python`。
- 后端：`pytest tests/ -q`，699 passed；Pi runtime：`npm test`，147 passed。
- 前端：`npx vitest run src/views/ src/api/`，331 passed；`npx vite build` 成功。
- 浏览器：后端 `127.0.0.1:8900` + Vite `127.0.0.1:5199`，`/settings/mcp` 通过真实接口看到
  `portal`，并完成 configured stdio MCP 的创建、禁用、启用、编辑、删除；API 与 MySQL 回读一致。
- provider：真实接口完成自定义 provider 创建、编辑、脱敏回读和删除。
- Pi：使用真实 `anthropic_compatible/deepseek-v4-pro` 和 `agent_opendataworks`；任务
  `waiting -> running -> finished`，`da_agent_sdk_record.engine_kind=pi_agent_core`，持久化
  `portal_list_workflows` 的 `tool.started/tool.completed`，最终 assistant 消息为 `4`；smoke topic 已清理。

## Rollout and backout

- 发布前先备份 `da_agent_settings`，执行 `alembic upgrade head`，确认两张 registry 表与迁移行数。
- 启动应用后确认 portal plugin bootstrap 只插入一次，后续重启不覆盖数据库修改。
- 确认当前 provider/model、agent capability 和至少一个真实 Pi task 后再放量。
- 回退应用时保留新增表和旧设置行；必要时回退前导出新增 provider/MCP。Alembic downgrade 仅在确定
  不再需要新数据时执行。
