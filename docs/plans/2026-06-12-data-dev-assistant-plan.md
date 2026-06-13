# 数据开发助手(合并入平台助手)实施计划

- 日期:2026-06-12
- 主题:data-dev-assistant
- 配套设计:`docs/design/2026-06-12-data-dev-assistant-design.md`
- 涉及栈:dataagent-backend(Python/FastAPI/Alembic)、portal-mcp(Python/FastMCP)、backend-agent-api + backend(Java/Spring Boot)、dataagent 技能包、dataagent-frontend(widget)、deploy

## 阶段拆分与依赖

```
阶段1 权限模式迁移 ──┐
阶段2 写 API(Java) ──┼─→ 阶段3 portal-mcp 工具 ─→ 阶段4 确认流 ─→ 阶段5 技能包+profile ─→ 阶段6 widget ─→ 阶段7 部署接线与全链路验证
```

阶段 1 与阶段 2 可并行;阶段 3 起串行。每阶段独立可合入、可回退。

## 阶段 1:权限模式迁移(dataagent-backend)

任务:

1. alembic 迁移:`da_agent_topic` 新增 `permission_mode VARCHAR(32) NOT NULL DEFAULT 'default'`;`da_agent_profile` 删除 `permission_mode` 列;downgrade 对称恢复(profile 列默认 `inherit`)。存量 topic 回填 `default`。
2. `models/schemas.py`:topic 创建/详情/列表模型增加 `permission_mode`;`deliver-message` 请求模型增加可选 `permission_mode`(仅隐式建 topic 生效);admin profile 模型移除 `permission_mode` 与 `permission_modes` 枚举。
3. `api/routes.py`:`POST /topics`、`POST /tasks/deliver-message` 接收并校验模式取值(`default|acceptEdits|plan|bypassPermissions`)。
4. `core/topic_task_store.py`:topic 读写链路携带 `permission_mode`;`TaskExecutionInput` 组装时带上 topic 模式。
5. `core/agent_runtime.py`:`_resolve_sdk_permission_mode` 改为校验 SDK 合法值,`inherit`/未知值归一化 `default`;新增高危/写工具清单常量;`_build_allowed_tools` 按模式裁剪(`plan` 不挂写工具)。
6. `core/task_executor.py`:`permission_mode` 来源改为 TaskExecutionInput(不再读 agent_snapshot)。
7. `core/agent_profile_service.py` / `api/admin_routes.py`:删除 permission_mode 字段、校验、upsert 列与枚举接口。

触达文件:`alembic/versions/`(新迁移)、`models/schemas.py`、`api/routes.py`、`api/admin_routes.py`、`core/topic_task_store.py`、`core/agent_runtime.py`、`core/task_executor.py`、`core/agent_profile_service.py`。

验证:

- 更新并通过 `tests/test_agent_profile_service.py`、`tests/test_task_executor.py`(断言 `ClaudeAgentOptions.permission_mode` 来自 topic)、`tests/test_routes_contract.py`、`tests/test_admin_routes.py`。
- `alembic upgrade head` + `downgrade -1` 在本地 `dataagent` 库往返成功。

回退:revert 代码 + `alembic downgrade`;存量数据无损(删列在 downgrade 恢复为默认值)。

## 阶段 2:backend-agent-api 写接口(Java)

任务:

1. `backend-agent-api` 新增 `AgentTaskController`、`AgentWorkflowController`、`AgentSqlController` 与请求/响应 DTO(对齐设计 6 节接口表)。
2. `backend` 新增对应 `BackendAgentTaskService` / `BackendAgentWorkflowService`(与现有 `BackendAgentMetadataService` 同模式),委托 `DataTaskService` / `WorkflowService` / `WorkflowPublishService` / `WorkflowScheduleService` / `DataQueryService`。
3. previewToken 机制:preview 响应生成一次性 token(含 workflowId + 当前版本号 + 过期时间,HMAC 签名或服务端缓存);publish(deploy/online)与 schedule/online 校验 token 有效且版本未变。
4. `X-Agent-Operator` header 解析,写入 operator/owner 审计字段;data scope 校验任务/工作流涉及的 database。

触达文件:`backend-agent-api/src/main/java/com/onedata/portal/agentapi/controller/`(新 controller)、`backend/src/main/java/com/onedata/portal/agentapi/service/`(新 service)、相关 DTO 包。

验证:

- 新增单测:controller 参数校验、previewToken 过期/版本漂移拒绝、data scope 越权拒绝、鉴权拦截(无 token 401)。
- `mvn -pl backend-agent-api,backend test`(或仓库等效最小命令)通过。

回退:新增类整体 revert,无 schema 变更。

## 阶段 3:portal-mcp 写工具

任务:

1. `portal_mcp/backend_client.py` 增加 task/workflow/sql-analyze 后端调用方法。
2. `portal_mcp/app.py` 按 `@mcp.tool` 模式注册设计 7 节的 14 个工具,Pydantic 入参模型;`portal_publish_workflow` 的 `previewToken` 必填;高危工具 description 标注需用户确认。
3. 透传 `X-Agent-Operator`(从 MCP 请求上下文/header 获取,沿用 data scope 的 contextvar 模式 `scope_context.py`)。

触达文件:`dataagent/portal-mcp/portal_mcp/app.py`、`backend_client.py`、`config.py`、`tests/`。

验证:portal-mcp 现有测试风格下新增工具契约测试(mock backend client),`pytest dataagent/portal-mcp/tests` 通过。

回退:revert,工具未注册即不可见。

## 阶段 4:can_use_tool 对话内确认流

任务:

1. `core/task_executor.py`:构造 `can_use_tool` 回调传入 `ClaudeAgentOptions`;命中高危清单且模式要求确认时:生成 `request_id` → 落 `permission_request` chunk → task 状态置 `waiting_permission` → 轮询 Redis 决策键(间隔 1s,总等待 `task_permission_wait_seconds` 默认 600s)→ 超时/deny 返回拒绝,allow 放行 → 状态恢复 `running`,落 `permission_decision` chunk。
2. `core/topic_task_store.py`:`waiting_permission` 状态读写(非终态,`TERMINAL_TASK_STATUSES` 不变);topic `current_task_status` 同步。
3. `api/routes.py`:新增 `POST /tasks/{task_id}/permission-decision`(request_id 幂等、不匹配 409),写 Redis `da:task:permission:{task_id}:{request_id}`(TTL ≈ 等待超时 + 60s)。
4. sandbox 路径:`sandbox_runner_main.py` / `sandbox_task_main.py` 中执行 SDK 的进程同样注册回调并可访问 Redis(确认 runner 容器具备 Redis 连接配置;若 runner 无 Redis,则该阶段开始前先补通配置)。
5. 超时模型:等待确认期间豁免 idle/progress 判定;`config.py` 新增 `task_permission_wait_seconds`。
6. 事件契约:`dataagent/contracts/` 新增 permission 事件结构说明(与 sdk-block-projection 同级)。

触达文件:`core/task_executor.py`、`core/topic_task_store.py`、`core/task_coordinator.py`(状态/租约交互确认)、`api/routes.py`、`config.py`、`sandbox_runner_main.py`、`sandbox_task_main.py`、`dataagent/contracts/`。

验证:

- 单测:回调在四种模式 × 高危/草稿/只读工具矩阵下的放行/确认/拒绝行为;decision 端点幂等与 409;超时 deny。
- 契约测试:`tests/test_routes_contract.py` 增加 `waiting_permission` 状态与 decision 端点用例。
- 租约回归:等待确认 60s+ 场景下任务租约不被回收(`tests` 中模拟心跳)。

回退:`can_use_tool` 不注册即回到无确认行为;新端点/状态向后兼容。

## 阶段 5:技能包 + profile 更新

任务:

1. 新建 `dataagent/.claude/skills/opendataworks-data-dev/`(SKILL.md、reference/10-task-fields.md、20-workflow-lifecycle.md、30-tool-recipes.md、assets/task-template.json、dev-policies.json),内容按设计 8 节 playbook;工具引用一律用 MCP 工具名,脚本(如需)用 `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/<name>.py"` 完整形式。
2. alembic 数据迁移:`agent_opendataworks` 内置 profile 的 `skill_folders_json` 追加 `opendataworks-data-dev`,system prompt 增补数据开发职责段(仅当 profile 仍为内置默认内容时更新,避免覆盖管理员修改)。
3. `core/agent_runtime.py` 内置技能目录清单(builtin skill folders)登记新 skill。

验证:技能文档自检(命名/目录/调用契约符合仓库规约);`reindex_documents_from_disk` 路径单测覆盖新目录;启动后 admin 技能列表可见。

回退:迁移 downgrade 移除 skill 引用;技能目录删除即不可被挂载。

## 阶段 6:widget 前端(dataagent-frontend)

任务:

1. 新建会话面板:权限模式选择(默认 `default`,四模式通俗文案),随 `POST /topics` 提交;会话头部模式徽标。
2. `permission_request` 事件 → 确认卡片(操作/参数摘要/preview 摘要/允许/拒绝/备注);决策调用 decision 端点;`waiting_permission` 状态输入框置灰提示。
3. 智能体设置页移除 permission_mode 配置项。

验证:`nvm use` 后 widget 构建通过 + 既有前端测试;事件渲染按 `dataagent/contracts` 契约写组件测试。

回退:UI revert;后端缺省 `default` 模式行为与现状等价(高危工具确认,但本阶段前写工具尚未对模型开放使用场景)。

## 阶段 7:部署接线与全链路验证

任务:

1. 确认 `deploy/docker-compose.prod.yml` / `docker-compose.dev.yml`:portal-mcp 已有 `PORTAL_MCP_BACKEND_BASE_URL` / `PORTAL_MCP_BACKEND_SERVICE_TOKEN`,新写接口无需新增 env;sandbox runner 容器补充 Redis 连接 env(若阶段 4 确认缺失)。
2. nginx/反代无路由变化确认(decision 端点在既有 `/api/v1/nl2sql` 前缀下)。

全链路 smoke(按 AGENTS.md 智能问数验证规约):

- 环境:本地 Docker MySQL `127.0.0.1:3316`(schema `opendataworks` + `dataagent`)、Redis `127.0.0.1:6379`、`.venv-py313`、`alembic upgrade head`、真实 provider 凭证。
- 场景 A(default 模式主路径):创建 topic(`permission_mode=default`)→ 请求"为 XX 表写一个聚合 SQL 并创建任务加入工作流发布上线" → 验证:写工具触发 `permission_request` 事件 → decision 端点 allow → 任务/工作流创建成功 → preview → deploy → online,`workflow_publish_record` operator 含会话标识。
- 场景 B(plan 模式):同样请求 → 助手只产出 SQL 与方案,写工具不可用。
- 场景 C(拒绝路径):permission_request 后 deny → 任务恢复 running,助手解释并停止发布。
- 场景 D(超时路径):不操作确认卡片 → 超时 deny → 任务正常收尾。
- 既有回归:`你好,请直接回复 smoke-ok。` 与 `最近 30 天工作流发布次数趋势` 两个标准 prompt 不受影响。
- 清理:删除 smoke 会话与 smoke 创建的任务/工作流(先 offline 再删),停止本地服务。
- 验证记录:按规约报告 MySQL/Redis/解释器/凭证/场景通过情况,写入 `docs/reports/`。

## 总回退策略

- 各阶段独立 revert;DB 迁移均有 downgrade。
- 最大风险点(阶段 4 确认流)有特性开关语义:不注册 `can_use_tool` + 高危清单置空即可退化为"写工具直通"(仅限故障应急,需同时将内置 profile 暂时移除写工具)。
- 写能力整体下线:portal-mcp 侧注销写工具即可,无需回滚 Java 与 schema。
