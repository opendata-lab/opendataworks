# 数据开发助手(合并入平台助手)设计

- 日期:2026-06-12
- 主题:data-dev-assistant
- 影响范围:dataagent-backend(权限模式模型 / 任务执行 / API)、portal-mcp(新增写工具)、backend-agent-api 与 backend(agent 专用工作流写接口)、dataagent 技能包(新增 skill)、dataagent-frontend(会话权限模式与确认卡片)、数据库 schema(`dataagent` 会话库)
- 配套计划:`docs/plans/2026-06-12-data-dev-assistant-plan.md`

## 1. 现状与问题

当前 OpenDataWorks 平台助手(`agent_opendataworks`)是一个只读的智能问数助手:

- 能力面只读:通过 `dataagent/portal-mcp` 暴露的 6 个只读 MCP 工具(`portal_search_tables` / `portal_get_lineage` / `portal_resolve_datasource` / `portal_export_metadata` / `portal_get_table_ddl` / `portal_query_readonly`,见 `dataagent/portal-mcp/portal_mcp/app.py`)和 `opendataworks-platform-tools` 技能脚本,只能查元数据、血缘和执行只读 SELECT。
- 数据开发链路全靠人工:生成 SQL → 创建任务(`POST /v1/tasks`)→ 组装工作流(`POST/PUT /v1/workflows`)→ 发布(`POST /v1/workflows/{id}/publish`,operation=deploy)→ 上线(operation=online)→ 配置调度,每一步都需要用户在 Web UI 手工完成。
- agent 专用通道(`backend-agent-api` 模块,`/v1/ai/metadata/*`、`/v1/ai/query/read`)没有任何写接口;工作流/任务 Web API 走会话 JWT 鉴权(`odw_session`),不适合智能体机器调用。
- 权限模式设计不合理:`da_agent_profile.permission_mode` 挂在智能体配置级,取值 `inherit|default|bypassPermissions` 含自造的 `inherit`,与 Claude Agent SDK 的权限模式不对齐;且同一智能体的所有会话被迫共享同一模式,无法表达"这次会话只读规划、下次会话允许执行"的真实使用差异。
- 没有"暂停等用户确认再继续"的机制:`suspended` 目前是取消后的终态(`dataagent-backend/api/routes.py` 中 `TERMINAL_TASK_STATUSES = {"finished", "error", "suspended"}`),无法支撑发布/上线这类高危操作的对话内确认。

## 2. 目标与范围

### In scope

- 平台助手 `agent_opendataworks` 合并获得数据开发能力:生成 SQL、润色 SQL、创建任务、组装工作流、发布(deploy)、上线/下线(online/offline)、调度配置。
- 权限模式改为**会话(topic)级**,取值与 Claude Agent SDK 对齐:`default` / `acceptEdits` / `plan` / `bypassPermissions`;删除 `da_agent_profile.permission_mode`。
- 新增对话内权限确认流:`default` / `acceptEdits` 模式下,高危工具调用通过 SDK `can_use_tool` 回调暂停执行,等待用户在聊天界面允许/拒绝。
- `backend-agent-api` 新增 agent 专用工作流/任务写接口;`portal-mcp` 新增对应 MCP 写工具。
- 新增技能包 `dataagent/.claude/skills/opendataworks-data-dev`,承载数据开发方法论与工具调用契约。
- widget 前端(`dataagent/dataagent-frontend`)支持会话权限模式选择与权限确认卡片。
- 全链路审计:写操作落现有 `workflow_publish_record` 等审计表,operator 标记 agent 来源。

### Out of scope

- 不新建独立"数据开发智能体"(已决策合并进平台助手)。
- 不改造 DolphinScheduler 本身;发布/上线仍依赖工作流已绑定 `dolphin_config_id` 的现有约束。
- 不做用户/角色级权限映射(普通用户/开发者/管理员差异化),列为后续演进。
- 不支持会话中途切换权限模式(与"topic 不支持中途换 agent"的既有约束一致)。
- DataX / Dinky 等非 SQL 任务类型的开发流,本期只保证 SQL 任务(`dolphinNodeType=SQL`)路径完整,其余类型接口可用但不写入技能 playbook。

## 3. 总体架构

```
widget(dataagent-frontend)
  会话创建时选择 permission_mode + 渲染 permission_request 确认卡片
    │
    ▼
dataagent-backend(FastAPI)
  topic.permission_mode → ClaudeAgentOptions.permission_mode
  can_use_tool 回调:高危工具 → permission_request 事件 + 等待决策(Redis)
    │
    ▼
claude_agent_sdk(agent_opendataworks profile)
  技能:opendataworks-platform-tools + opendataworks-business-knowledge
        + 新增 opendataworks-data-dev
    │
    ▼
portal-mcp(FastMCP,HTTP)
  现有 6 个只读工具 + 新增 task/workflow 写工具
    │
    ▼
backend-agent-api(/v1/ai/*,服务 token + 私网限制 + data scope)
  新增 /v1/ai/task/*、/v1/ai/workflow/* 写接口
    │
    ▼
backend 现有服务(不重写业务逻辑)
  DataTaskService / WorkflowService / WorkflowPublishService / WorkflowScheduleService
    │
    ▼
DolphinScheduler(发布与调度执行)
```

设计原则:

- 复用既有三段式通道(skill → portal-mcp → backend-agent-api → backend service),写能力是对该通道的纵向扩展,不新开旁路。
- 业务规则(版本快照、发布预览、DolphinScheduler 同步)全部留在 backend 现有服务里,agent 侧只做编排与确认。
- 技能特定行为只进 skill 包,共享 runtime(`core/agent_runtime.py` 等)保持 skill 无关,符合仓库模块规约。

## 4. 权限模式设计(核心变更)

### 4.1 取值与语义

权限模式与 Claude Agent SDK 对齐,四个取值,语义在数据开发场景下的映射:

| 模式 | SDK 语义 | 数据开发语义 |
| --- | --- | --- |
| `plan` | 只读规划 | 只挂载只读工具;所有写 MCP 工具不进入 allowed_tools,调用即拒绝。助手只能生成/润色 SQL、给出任务与工作流的建议方案 |
| `default` | 默认,未授权工具需确认 | 只读工具放行;**所有写工具**(含创建草稿)经 `can_use_tool` 对话内确认 |
| `acceptEdits` | 自动接受编辑 | 草稿类写操作(创建/更新任务、创建/更新工作流、调度配置 upsert)自动放行;**高危操作**(publish deploy/online/offline、schedule online)仍需对话内确认 |
| `bypassPermissions` | 全部放行 | 写工具全部自动放行;发布前 preview 与审计仍强制执行(见 4.4 与 10 节) |

高危工具清单(单一来源,定义为 `dataagent-backend` 配置常量,portal-mcp 工具描述与 widget 文案从语义上保持一致):

- `portal_publish_workflow`(operation 任意取值均视为高危)
- `portal_workflow_schedule_online`

### 4.2 存储迁移:profile 级 → topic 级

- `da_agent_topic` 新增列 `permission_mode VARCHAR(32) NOT NULL DEFAULT 'default'`;任务沿用现有 snapshot 机制,`agent_snapshot_json` 之外在 `da_agent_task` 不新增列——任务执行时从所属 topic 读取(topic 在会话生命周期内模式不变,无需逐任务快照)。
- `da_agent_profile.permission_mode` 删除:
  - alembic 迁移:`da_agent_topic` 加列;`da_agent_profile` 删列(downgrade 恢复,默认 `inherit`)。
  - `core/agent_profile_service.py`:移除 `_validate_permission_mode`、`PERMISSION_MODES`、内置 profile 快照中的 `permission_mode` 字段及 upsert SQL 列。
  - admin API(`api/admin_routes.py`)与 widget 智能体设置页:移除 permission_mode 配置项;`permission_modes` 枚举元数据接口同步移除。
- 存量数据兼容:
  - 存量 topic 回填 `permission_mode='default'`(与现有 `inherit→default` 的实际解析结果一致,行为不变)。
  - 存量 task 的 `agent_snapshot_json` 中残留的 `permission_mode` 字段被忽略(执行链不再读取);`_resolve_sdk_permission_mode` 改为校验 SDK 合法值并把历史值 `inherit` 归一化为 `default`。

### 4.3 API 变更

- `POST /api/v1/nl2sql/topics`:请求体新增可选 `permission_mode`(缺省 `default`);响应体回带。
- `POST /api/v1/nl2sql/tasks/deliver-message`:新增可选 `permission_mode`,仅在隐式建 topic 时生效;topic 已存在时忽略并以 topic 值为准(模式会话内不可变)。
- topic 详情 / 列表响应增加 `permission_mode` 字段(`models/schemas.py`)。

### 4.4 执行链变更

- `core/task_executor.py`:`permission_mode` 来源从 `agent_snapshot` 改为 `TaskExecutionInput` 携带的 topic 值;继续传给 `ClaudeAgentOptions.permission_mode`。
- `core/agent_runtime.py`:
  - `_resolve_sdk_permission_mode` 改为只接受 SDK 合法值(`default|acceptEdits|plan|bypassPermissions`),`inherit` 与未知值归一化为 `default`。
  - `_build_allowed_tools` 增加按 permission_mode 的工具裁剪:`plan` 模式不挂载写 MCP 工具(写工具名单来自 4.1 的配置常量);其余模式全量挂载,确认职责交给 `can_use_tool`。
- 防御纵深:即使模型在 `plan` 模式尝试调用写工具,SDK 层因不在 allowed_tools 而拒绝;`bypassPermissions` 下 API 层仍要求 preview 凭证(见 6 节)。

## 5. 对话内权限确认流(新机制)

### 5.1 流程

```
模型调用高危工具(如 portal_publish_workflow)
  → can_use_tool 回调命中高危清单且当前模式要求确认
  → 生成 request_id,落一条 permission_request chunk 事件
     (含工具名、参数摘要、若有则附带最近一次 preview 结果引用)
  → task 状态置为 waiting_permission(新增非终态)
  → 回调 await Redis 决策键,期间任务心跳照常续租
用户在 widget 确认卡片点击 允许 / 拒绝
  → POST /api/v1/nl2sql/tasks/{task_id}/permission-decision
     {request_id, decision: allow|deny, note?}
  → 写 Redis key da:task:permission:{task_id}:{request_id} = allow|deny(带 TTL)
  → 回调读到决策:allow → 返回允许,工具继续执行;deny → 返回拒绝,
     模型收到拒绝结果后继续对话(说明原因/调整方案)
  → task 状态恢复 running,落 permission_decision chunk 事件(审计)
```

### 5.2 关键决策与约束

- **决策传递用 Redis 键**,复用现有取消标志模式(`da:task:cancel:{task_id}`),进程内执行与 sandbox runner(`_should_use_sandbox_runner`,`core/task_executor.py`)两条路径都可达,无需为 runner 增加反向 HTTP 通道。
- **`waiting_permission` 是非终态**,与现有终态 `suspended`(取消)严格区分;`TERMINAL_TASK_STATUSES` 不变。topic 的 `current_task_status` 同步该状态,供前端轮询/流式展示。
- **超时**:等待确认有独立超时(默认 600s,可配置 `task_permission_wait_seconds`),超时视为 deny 并继续执行(模型收到"用户未确认"的拒绝结果);等待期间豁免 idle/progress 超时判定,但**不豁免**总运行超时——总超时上限相应评估(等待确认时间计入总时长是可接受的,因为确认型会话本身是交互式的)。
- **decision 端点幂等**:重复提交同一 `request_id` 返回首次决策结果;`request_id` 不匹配当前等待中的请求时返回 409。
- **事件契约**:`permission_request` / `permission_decision` 两类 chunk 事件结构写入 `dataagent/contracts`(与现有 sdk-block-projection 契约同级),widget 与 eval 工具按契约消费。

### 5.3 确认卡片内容

widget 渲染 `permission_request` 事件为卡片,展示:

- 操作名称与目标(如"发布工作流 #123(deploy)");
- 参数摘要(workflow 名称、operation、版本号);
- 若助手在请求确认前已调用 `portal_preview_publish`,卡片附带 diff/告警摘要(技能 playbook 强制要求先 preview 再请求确认,见 8 节);
- 允许 / 拒绝按钮与可选备注输入。

## 6. backend-agent-api 新增写接口

新增 controller 注册在 `backend-agent-api` 模块(与 `AgentMetadataController` / `AgentQueryController` 并列),复用 `AgentApiAuthInterceptor`(`X-Agent-Service-Token` + 私网限制)与 `AgentDataScopeFilter`;实现委托 `backend` 现有服务,不复制业务逻辑。

### 6.1 任务接口(委托 `DataTaskService`)

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/ai/task` | 创建任务;入参对齐 Web 端 `TaskCreateRequest`(task + inputTableIds + outputTableIds),强制要求输入/输出表 id 以维护血缘 |
| PUT | `/v1/ai/task/{id}` | 更新任务(仅 draft 状态) |
| GET | `/v1/ai/task/{id}` | 任务详情 |
| GET | `/v1/ai/task/list` | 任务列表(关键字 / 类型 / 状态过滤,默认限制条数) |

### 6.2 工作流接口(委托 `WorkflowService` / `WorkflowPublishService` / `WorkflowScheduleService`)

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/ai/workflow` | 创建工作流(`WorkflowDefinitionRequest` 子集) |
| PUT | `/v1/ai/workflow/{id}` | 更新结构(任务绑定、依赖边) |
| GET | `/v1/ai/workflow/{id}` | 详情(含任务与最近实例) |
| GET | `/v1/ai/workflow/list` | 列表 |
| GET | `/v1/ai/workflow/{id}/publish/preview` | 发布预览;响应在现有 `WorkflowPublishPreviewResponse` 基础上附加一次性 `previewToken`(短 TTL) |
| POST | `/v1/ai/workflow/{id}/publish` | operation=deploy/online/offline;**deploy/online 必须携带有效 `previewToken`**,服务端校验 preview 之后工作流版本未变化 |
| PUT | `/v1/ai/workflow/{id}/schedule` | 调度配置 upsert |
| POST | `/v1/ai/workflow/{id}/schedule/online` | 调度上线(同样要求 previewToken) |
| POST | `/v1/ai/workflow/{id}/schedule/offline` | 调度下线 |

### 6.3 SQL 分析接口(委托 `DataQueryService`)

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/ai/sql/analyze` | SQL 解析:输入/输出表识别、操作类型、风险告警;供润色与任务血缘自动推荐 |

### 6.4 鉴权与审计

- `previewToken` 是 API 层防线:即使智能体侧权限模式被配置为 `bypassPermissions`,deploy/online 也必须先 preview,杜绝"未经预览直达生产调度"。
- operator 标识:请求新增 header `X-Agent-Operator`(由 portal-mcp 透传 dataagent 会话标识,如 `agent:{topic_id}`),写入 `workflow_publish_record.operator` 与任务实体 owner/operator 字段,审计可追溯到具体会话。
- data scope:任务/工作流涉及的数据源 database 需通过 `AgentDataScopeContext.isDatabaseNameAllowed` 校验,越权数据库直接拒绝。

## 7. portal-mcp 新增 MCP 工具

在 `portal_mcp/app.py` 的 `build_mcp_server` 中按现有 `@mcp.tool` 模式注册,`backend_client.py` 增加对应后端调用:

| 工具名 | 后端接口 | 风险级 |
| --- | --- | --- |
| `portal_create_task` | POST /v1/ai/task | 草稿写 |
| `portal_update_task` | PUT /v1/ai/task/{id} | 草稿写 |
| `portal_list_tasks` | GET /v1/ai/task/list | 只读 |
| `portal_get_task` | GET /v1/ai/task/{id} | 只读 |
| `portal_create_workflow` | POST /v1/ai/workflow | 草稿写 |
| `portal_update_workflow` | PUT /v1/ai/workflow/{id} | 草稿写 |
| `portal_get_workflow` | GET /v1/ai/workflow/{id} | 只读 |
| `portal_list_workflows` | GET /v1/ai/workflow/list | 只读 |
| `portal_preview_publish` | GET /v1/ai/workflow/{id}/publish/preview | 只读 |
| `portal_publish_workflow` | POST /v1/ai/workflow/{id}/publish | **高危** |
| `portal_upsert_schedule` | PUT /v1/ai/workflow/{id}/schedule | 草稿写 |
| `portal_workflow_schedule_online` | POST .../schedule/online | **高危** |
| `portal_workflow_schedule_offline` | POST .../schedule/offline | 草稿写 |
| `portal_analyze_sql` | POST /v1/ai/sql/analyze | 只读 |

- 工具入参用 Pydantic 模型显式声明(沿用现有 `SearchTablesInput` 等风格),`portal_publish_workflow` 的 `previewToken` 为必填参数,从工具签名上强制"先 preview"。
- 高危工具的 description 明确标注需要用户确认,辅助模型自发走 preview → 确认流程;真正的强制门控在 dataagent-backend 高危清单(4.1)与 API previewToken(6.4),工具描述只是提示层。

## 8. 新技能包:`dataagent/.claude/skills/opendataworks-data-dev`

```
opendataworks-data-dev/
├── SKILL.md            # 数据开发 playbook(单一事实来源)
├── reference/
│   ├── 10-task-fields.md          # DataTask 字段契约(taskType/engine/dolphinNodeType/taskSql/...)
│   ├── 20-workflow-lifecycle.md   # draft → preview → 确认 → deploy → online 流程与状态机
│   └── 30-tool-recipes.md         # MCP 工具调用顺序、参数契约、失败恢复
└── assets/
    ├── task-template.json         # SQL 任务定义模板
    └── dev-policies.json          # 命名规范、必填校验、禁止项
```

SKILL.md playbook 核心规则:

1. **SQL 生成/润色**:先用 `portal_search_tables` / `portal_get_table_ddl` 核实表结构,再生成;润色前必须 `portal_analyze_sql` 识别输入/输出表与操作类型;含写操作的 SQL(INSERT/UPDATE)只允许进任务定义,不允许通过 `portal_query_readonly` 试跑。
2. **任务创建**:基于 analyze 结果自动推荐 inputTableIds/outputTableIds;任务一律以 draft 创建;命名遵循 dev-policies。
3. **工作流组装**:绑定任务后必须复述 DAG 结构请用户口头确认无误,再进入发布。
4. **发布与上线**:强制顺序 `portal_preview_publish` → 向用户展示 diff/告警 → `portal_publish_workflow(deploy, previewToken)` → 确认成功后再 `portal_publish_workflow(online, previewToken)`;preview 返回 error 级 issue 时禁止继续,转为修复建议。
5. **失败恢复**:publish 失败读取 `workflow_publish_record` 报错信息,定位是结构问题(回到草稿修改)还是引擎问题(提示用户检查 DolphinScheduler 配置),不盲目重试。

profile 更新:alembic 数据迁移将 `agent_opendataworks` 的 `skill_folders_json` 追加 `opendataworks-data-dev`,system prompt 增补数据开发职责段落(职责边界:问数为主、开发为辅,开发操作严格遵循 playbook)。

技能边界(遵守仓库模块规约):路由规则、调用顺序、恢复策略只写在本 skill;`core/agent_runtime.py` 等共享模块只新增通用的"按模式裁剪工具 + 高危清单 + can_use_tool"机制,不感知任何具体业务流程。

## 9. 前端改动(dataagent-frontend / widget)

- 会话创建:新建会话面板增加权限模式选择(默认 `default`),四个模式配通俗文案(如"逐步确认 / 草稿自动、发布确认 / 仅规划 / 全自动");传入 `POST /topics`。
- 确认卡片:消费 `permission_request` 事件渲染卡片(5.3),决策调用 `POST /tasks/{task_id}/permission-decision`;`waiting_permission` 状态下输入框置灰并提示"等待操作确认"。
- 会话头部展示当前模式徽标。
- 智能体设置页:删除 permission_mode 配置项。
- 主应用 `frontend/` 无路由级改动(入口复用现有内嵌页与浮窗,`agentId` 不变)。

## 10. 安全与审计

三层防线,任何单层被绕过仍有约束:

1. **SDK / 模式层**:`plan` 不挂载写工具;`default` / `acceptEdits` 高危工具经 `can_use_tool` 用户确认,决策事件全量落库。
2. **通道层**:portal-mcp → backend-agent-api 走服务 token + 私网限制 + data scope,写接口不对公网与浏览器暴露。
3. **API 层**:deploy/online/schedule-online 强制 `previewToken`(短 TTL、版本一致性校验);operator 透传会话标识写入 `workflow_publish_record` / 任务审计字段。

另:发布产物可回滚——复用现有 `workflow_version` 快照与 rollback 能力,助手误发布后可由用户在 UI 回滚(本期不给助手回滚工具,降低能力面)。

## 11. 权衡与备选

- **合并 vs 独立智能体**:选择合并。入口统一、用户无需切换;能力差异收敛到会话级权限模式,避免两个助手的提示词/技能重复维护。代价是平台助手 system prompt 变长、职责变宽,通过 skill 分层(问数/开发各自 playbook)控制。
- **会话级 vs profile 级权限模式**:选择会话级并对齐 SDK 取值。profile 级无法表达同一用户不同会话的意图差异,且 `inherit` 为自造值;会话级与 SDK `permission_mode` 一一对应,执行链零翻译。
- **MCP 写通道三选一**:选择 backend-agent-api 新增写 API。直调 Web API 需给 dataagent 配用户级 JWT,扩大安全面;portal-mcp 直接代理 Web API 则绕过 agent 专用鉴权层、需在 portal-mcp 重复实现权限收敛。
- **确认机制:can_use_tool vs 自定义 confirmToken 对话协议**:选择 SDK 原生 `can_use_tool`。自定义协议需要模型"自觉"走确认流程,可被提示词注入绕过;`can_use_tool` 是运行时强制拦截,模型无法跳过。
- **决策传递:Redis 键 vs runner 反向 HTTP**:选择 Redis 键,复用 cancel-flag 既有模式,进程内与 sandbox 两条执行路径统一,不新增网络拓扑。

## 12. 风险

- `can_use_tool` 与 sandbox runner 的组合是新路径,需验证回调在 runner 子进程中等待 Redis 决策时心跳/租约不中断(任务租约续期 5s 周期照常运行)。
- 等待确认期间任务占用并发槽位(`task_max_concurrency` 默认 4),长时间未确认会挤占吞吐;通过确认超时(默认 600s → deny)兜底,后续可演进为挂起释放槽位。
- 内置 profile 的 system prompt 增补与 skill 追加通过 alembic 数据迁移下发,已被管理员自定义过的 profile 需要迁移时做"仅内置且未改名"判定,避免覆盖用户修改。
