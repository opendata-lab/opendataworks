# 数据开发助手 — 实施与验证报告

- 日期：2026-06-14
- 主题：data-dev-assistant
- 分支：`claude/data-dev-assistant-design-87a763`
- 设计 / 计划：`docs/design/2026-06-12-data-dev-assistant-design.md`、`docs/plans/2026-06-12-data-dev-assistant-plan.md`

把"数据开发助手"能力合并进 OpenDataWorks 平台助手：生成/润色 SQL、创建任务、组装工作流、发布与上线、配置调度；权限确认下沉为 Chat V2 通用交互块；权限模式与 Claude Agent SDK 对齐并迁移到会话级。

## 各阶段状态与验证

| 阶段 | 内容 | 提交 | 验证 |
| --- | --- | --- | --- |
| 1 | 权限模式迁移到 topic 级（删 profile 字段，SDK 词表，topic 加列，执行链改造） | `1803270` | 后端 targeted + 全套件 **279 passed**；alembic 链单一 head 校验通过 |
| 2 | backend-agent-api 写接口（/v1/ai/task、/v1/ai/workflow、/v1/ai/sql/analyze）+ previewToken 二次防线 + X-Agent-Operator | `c02b2d6` | `mvn -pl backend -am compile` **BUILD SUCCESS**；新增 **9 个 Java 单测**通过（preview-token issue/verify/篡改/版本漂移/异密钥、publish 凭证强制、offline 豁免） |
| 3 | portal-mcp 14 个写工具 + operator contextvar + publish 必填 preview_token | `ab232c0` | portal-mcp **18 测试**通过 |
| 4 | Chat V2 通用权限确认块（record 类型 + 双侧投影 + cases.json 四态）+ permission_gate 模式策略 + decision 端点 + waiting_permission + can_use_tool 回调 | `17c6f84`,`9550b37` | 后端 **279 passed**（含 permission_gate、投影契约、can_use_tool allow/deny/timeout/plan-deny 编排）；复审发现 decision 端点 request_id mismatch 与 endpoint 侧 `permission_decision` 持久化覆盖不足，补充修复与复验见下文 |
| 5 | opendataworks-data-dev 技能包 + 启用到平台助手 + alembic 数据迁移 | `c9e3dc4` | profile/skill-content/skill-admin 套件通过；技能目录/JSON 结构校验 |
| 6 | 前端 Chat V2 权限卡片 + 会话模式 pill（门户 + widget 共享引擎）+ 删除 profile 权限选择器 | `12ba944` | 前端 vitest **233 passed（27 文件）**；production build 成功；双侧投影契约 13 用例两侧一致；复审发现 widget 入口缺少会话权限模式切换，补充修复与复验见下文 |

## 复审后补充修复与验证

复审 `claude/data-dev-assistant-design-87a763` 后，针对设计/计划偏差补充修复：

- backend agent API 在调度 upsert 层拒绝 `enabled` 字段，调度上线/下线必须走 `/schedule/online` 或 `/schedule/offline`，避免绕过 preview token 与高危确认。
- `permission-decision` endpoint 在写 Redis 决策键后同步追加 `permission_decision` SDK record；`get_pending_permission_request_id` 改为真正查找未被 decision 关闭的请求；同一 `request_id` 幂等重试不重复写 record，错误 `request_id` 返回 409。
- portal-mcp service 规范化 workflow/schedule payload，后端收到 `{workflow: ...}` / `{schedule: ...}`，并剥离 schedule `enabled`。
- Widget 入口补齐权限确认卡片后的会话权限模式选择器，创建 topic、deliver-message 与已有 topic 更新都会携带/保存 `permission_mode`。
- `test_task_executor.py` 兼容真实 `claude_agent_sdk` 返回的 `PermissionResultAllow/Deny` 对象，避免只在 fallback dict 环境下通过。

本次补充验证：

- `pytest dataagent/dataagent-backend/tests/test_routes_contract.py`：**6 passed**
- `pytest dataagent/dataagent-backend/tests/test_task_executor.py`：**25 passed**
- `pytest dataagent/portal-mcp/tests/test_write_tools.py`：**5 passed**
- `mvn -pl backend -am -Dtest=BackendAgentWorkflowServiceTest -DfailIfNoTests=false test`：**BUILD SUCCESS**，`BackendAgentWorkflowServiceTest` **4 passed**
- `nvm use && npm --prefix dataagent/dataagent-frontend test -- WidgetChat.spec.js`：**23 passed**（独立 worktree 临时复用主工作区 `node_modules` symlink，测试后已删除）

## Live smoke：真实链路验证

### 2026-06-14 数据库迁移与 API 落地

此前在真机 MySQL 兼容库 + Redis 上完成数据库迁移与 API 落地验证：

- `20260613_000017` 权限模式迁移：`upgrade` 后 `da_agent_topic.permission_mode` 存在、默认 `default`、NOT NULL；`da_agent_profile.permission_mode` 已删除；`downgrade` 后列状态对称恢复，再 `upgrade head` 复原。
- `20260613_000018` 技能启用迁移：`agent_opendataworks` 的 `skill_folders_json` 为 `["opendataworks-business-knowledge","opendataworks-platform-tools","opendataworks-data-dev"]`；down/up 往返通过。
- 真库 API：`POST /topics`、`PUT /topics/{id}`、非法 mode 归一化、空更新 400、未知 task 的 `permission-decision` 404 均通过。

### 2026-06-15 全链路 smoke

本轮使用用户已启动的本地 MySQL/Redis，补齐 Java backend、portal-mcp、DataAgent、真实 provider 与权限确认链路：

- 环境：
  - MySQL：`127.0.0.1:3306`，业务库 `opendataworks`，会话库 `dataagent`，用户 `dataagent/dataagent123`。
  - Redis：`127.0.0.1:6379`。
  - Python：`<workspace>/opendataworks/dataagent/dataagent-backend/.venv-py313`。
  - Java backend：`127.0.0.1:8080/api`，`AGENT_API_SERVICE_TOKEN=odw-agent-service-token`。
  - portal-mcp：`127.0.0.1:8801/mcp/`，frontdoor token `odw-portal-mcp-token`。
  - DataAgent：`127.0.0.1:8900`，`DATAAGENT_HOST_ROOT=.dataagent_runtime`，provider `anthropic_compatible` / `deepseek-v4-pro`。
- 迁移与启动：
  - `alembic upgrade head` 执行到 `20260613_000018`。
  - backend Flyway 当前版本 `45`，启动成功。
  - DataAgent health OK，task coordinator 连接 Redis 成功；`POST /topics` 成功。
- backend-agent-api 鉴权：
  - `GET /api/v1/ai/workflow/list?limit=1` 正确 token 返回 200。
  - 错误 token / 缺失 token 均返回 401。复审中发现 `/v1/ai/workflow/**` 原未被拦截，已改为统一保护 `/v1/ai/**`。
- portal-mcp 真实工具调用：
  - MCP `list_tools` 返回 20 个工具，包含 `portal_list_workflows`、`portal_publish_workflow`、`portal_upsert_schedule`。
  - `portal_list_workflows {limit:1}` 经 portal-mcp → backend-agent-api → backend service → MySQL 返回 workflow 数据，`isError=false`。
- 真实模型任务流：
  - `POST /tasks/deliver-message`，prompt `你好，请直接回复 smoke-ok。`。
  - task 状态 `waiting -> running -> finished`；`/sdk-events` 返回 11 条记录，包含 terminal `done(success)`；`/tasks/{id}/message` 返回 assistant content `smoke-ok`；topic 已删除清理。
- 权限确认链路：
  - 初次真实 smoke 暴露：确认型 MCP 写工具如果直接出现在 `allowed_tools`，SDK 会把它视为已授权工具并绕过 `can_use_tool`；该次创建了 smoke workflow `id=12`，随后已通过 `DELETE /api/v1/workflows/12?cascadeDeleteTasks=true` 清理，列表 total 从 5 回到 4。
  - 修复后策略：`default`/`acceptEdits` 中需要确认的 MCP 写工具不直接挂入 `allowed_tools`，由 `can_use_tool` 动态确认后放行。
  - 复验同一写工具 prompt：task 进入 `waiting_permission`，`sdk-events` 含 1 条 `permission_request`；`POST /tasks/{task_id}/permission-decision` deny 返回 200；随后 task `finished`，`sdk-events` 含 1 条 `permission_decision`、1 条错误 `tool_result`、1 条 `done`；最终 assistant message 为"操作已被取消..."；workflow 列表确认 smoke workflow 未创建；topic 已删除。

## 仍未执行 / 残余风险

- 未执行真实 deploy/online/schedule-online 到 DolphinScheduler；本地 `dolphin_config` 指向的 DolphinScheduler 需要有效认证/运行环境。preview token、schedule online/offline 与 publish 凭证强制目前由 Java 单测和 HTTP 边界覆盖，真实调度器执行仍需部署环境补测。
- 未覆盖 widget 浏览器端点击确认的 Playwright smoke；后端/SDK/Redis/消息投影链路已通过真实 HTTP 验证，widget 交互由 `WidgetChat.spec.js` 覆盖。
