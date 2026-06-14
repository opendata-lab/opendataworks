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

## Live smoke：已在真机运行的部分

本轮在容器内启动了 docker daemon(Docker Hub CDN 被网络策略 403,无法拉镜像),改用 apt 安装 **MariaDB 10.11(MySQL 兼容)+ Redis**,完成了此前最关键的未测层——**数据库迁移与 DB 落地的 API 贯通**:

- **两个 alembic 迁移在真机 MySQL 兼容库上执行通过**:
  - `20260613_000017`(权限模式迁移):`upgrade` 后 `da_agent_topic.permission_mode` 存在、默认 `'default'`、NOT NULL;`da_agent_profile.permission_mode` 已删除(列数 0)。`downgrade` 回到 000016 时:topic 列移除(0)、profile 列恢复(1);再 `upgrade head` 复原——**完整 down/up 往返通过**。
  - `20260613_000018`(技能启用):`agent_opendataworks` 内置 profile 的 `skill_folders_json` 变为 `["opendataworks-business-knowledge","opendataworks-platform-tools","opendataworks-data-dev"]`;down/up 往返通过。
  - 注:一个**既有**(非本次)迁移 `20260601_000014` 在 MariaDB 严格性下产出 `DEFAULT 'NULL'` 报 1067,与本改动无关;smoke 中 stamp 跳过该 comment-only 迁移后,本次两个迁移正常执行。
- **DB 落地的 API 贯通**(dataagent-backend 连真机 MySQL+Redis,health OK,协调器启动):
  - `POST /topics`(permission_mode=plan)→ 落库并返回 `plan`;无 mode → `default`。
  - `PUT /topics/{id}` 切到 `bypassPermissions` → 生效;切 `junk` → 归一化 `default`;`GET` 反映最新值。
  - `PUT /topics/{id}` 空 body → 400;`POST /tasks/{id}/permission-decision`(不存在任务)→ 404。
  - 验证了 Stage 1 的 store SQL(create/update/get_topic + permission_mode)、归一化与 decision 端点路由,均在真实 MySQL 上。
- 环境:MariaDB `127.0.0.1:3306`(库 `dataagent`/用户 `dataagent`)、Redis `127.0.0.1:6379`、Python venv 安装 `requirements` 子集、未配置 provider(无真实模型)。

## 仍未执行（需 provider/模型 或 部署后端）

- **无可用 DataAgent provider 凭证**:环境仅有 Claude Code 自身的 OAuth token,不可作为 dataagent provider;`claude_agent_sdk` 真实运行(含 `can_use_tool` 结果类 `PermissionResultAllow/Deny` 的实际契约、task 进入 `waiting_permission` 的 409 路径)未真机跑。
- 真实模型驱动的确认流四场景:default 确认→deploy→online / plan 拒绝 / deny / timeout;门户与 widget 各一次。
- portal-mcp → backend-agent-api → backend service 的真实 HTTP 贯通(需部署 Java 后端 + DolphinScheduler;previewToken 在真实 publish 上的校验)。

### 复跑建议（待环境具备时）

1. `docker compose -f deploy/docker-compose.dev.yml up -d mysql redis`，初始化 `dataagent` schema/用户（库 `dataagent`/用户 `dataagent`/密码 `dataagent123`）。
2. `dataagent/dataagent-backend` 用 `.venv-py313` 安装 `requirements.txt`（含 `claude-agent-sdk`），设置本地 MySQL/Redis 环境变量，`alembic upgrade head`。
3. `da_agent_settings` 配置有效 provider 与运行 DB；`uvicorn main:app`。
4. 走真实 HTTP：`POST /topics`（permission_mode=default）→ "为某表写聚合 SQL 并建任务加入工作流发布上线" → 验证 `permission_request` 块 → `POST /tasks/{id}/permission-decision` allow → preview→deploy→online，`workflow_publish_record.operator` 含会话标识；再覆盖 plan/deny/timeout；门户与 widget 各一次。
