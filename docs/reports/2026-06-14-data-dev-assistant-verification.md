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
| 4 | Chat V2 通用权限确认块（record 类型 + 双侧投影 + cases.json 四态）+ permission_gate 模式策略 + decision 端点 + waiting_permission + can_use_tool 回调 | `17c6f84`,`9550b37` | 后端 **279 passed**（含 permission_gate、投影契约、decision 端点幂等/409/404、can_use_tool allow/deny/timeout/plan-deny 编排） |
| 5 | opendataworks-data-dev 技能包 + 启用到平台助手 + alembic 数据迁移 | `c9e3dc4` | profile/skill-content/skill-admin 套件通过；技能目录/JSON 结构校验 |
| 6 | 前端 Chat V2 权限卡片 + 会话模式 pill（门户 + widget 共享引擎）+ 删除 profile 权限选择器 | `12ba944` | 前端 vitest **233 passed（27 文件）**；production build 成功；双侧投影契约 13 用例两侧一致 |

## 环境与未跑的端到端 smoke（如实说明）

本环境**无法运行设计要求的 live 全链路 smoke**，原因：

- **Docker daemon 不可用**：`docker` CLI 存在但守护进程未运行，且无 podman；无法按 `deploy/docker-compose.dev.yml` 启动本地 MySQL（`127.0.0.1:3316`)与 Redis（`127.0.0.1:6379`），三个端口均 closed。
- **无可用 DataAgent provider 凭证**：环境仅有 Claude Code 自身的 OAuth token（文件描述符形式），不可直接作为 dataagent-backend 的 provider 凭证；真实模型执行不可达。
- 因此 `claude_agent_sdk` 真实运行路径（含 `can_use_tool` 结果类 `PermissionResultAllow/Deny` 的实际契约）未在真机执行——后端套件里该 SDK 缺包的 1 个导入测试始终失败，属环境限制。

### 已验证层

- 后端 Python：单元 + 契约 + 路由（fake store）+ can_use_tool 编排（fake writer/store/wait），real-logic 覆盖。
- 双侧投影契约：后端 `test_sdk_block_projection_contract.py` 与前端 `sdkBlockProjection.contract.spec.js` 共用 `cases.json`,四态一致。
- Java：真实 `mvn` 编译通过 + 委托/凭证强制单测。
- 前端：完整 vitest + 生产构建。

### 尚未执行（需 live 环境）

- 两个 alembic 迁移（`20260613_000017` 列增删、`20260613_000018` 技能 JSON 追加）对真实 MySQL 8 的 `upgrade head` / `downgrade`。迁移用 `_has_table`/`_has_column` 守卫且为标准 DDL，但未真机执行。
- 真实模型驱动的确认流四场景：default 确认→deploy→online / plan 拒绝 / deny / timeout；门户与 widget 各一次。
- portal-mcp → backend-agent-api → backend service 的真实 HTTP 贯通（含 previewToken 在真实 publish 上的校验）。

### 复跑建议（待环境具备时）

1. `docker compose -f deploy/docker-compose.dev.yml up -d mysql redis`，初始化 `dataagent` schema/用户（库 `dataagent`/用户 `dataagent`/密码 `dataagent123`）。
2. `dataagent/dataagent-backend` 用 `.venv-py313` 安装 `requirements.txt`（含 `claude-agent-sdk`），设置本地 MySQL/Redis 环境变量，`alembic upgrade head`。
3. `da_agent_settings` 配置有效 provider 与运行 DB；`uvicorn main:app`。
4. 走真实 HTTP：`POST /topics`（permission_mode=default）→ "为某表写聚合 SQL 并建任务加入工作流发布上线" → 验证 `permission_request` 块 → `POST /tasks/{id}/permission-decision` allow → preview→deploy→online，`workflow_publish_record.operator` 含会话标识；再覆盖 plan/deny/timeout；门户与 widget 各一次。
