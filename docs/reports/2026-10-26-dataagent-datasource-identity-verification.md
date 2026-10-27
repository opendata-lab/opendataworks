# DataAgent 主体数据库账号映射验证

实施/验证：2026-10-27。分支 `feat/datasource-adapter`，基线 `8767838f`；续作中断工作区，未提交、未推送。

[设计](../design/2026-10-26-dataagent-datasource-identity-design.md) · [计划](../plans/2026-10-26-dataagent-datasource-identity-plan.md) · [部署](../../deploy/README.md#数据源主体账号映射v54--alembic-20261026_000029) · [TODO](../TODO.md)

## 改动

整个工作区差异（含中断前半成品）：**124 个文件，新增 18、删除 21、修改 85**。

- 新建主体/数据源账号映射及仅管理员可用的 CRUD、主体列表和保存连接校验；密码沿用现有存储，JSON 只写，不回传。USER 用平台用户 ID；AGENT 用智能体 ID，多主体可共用数据库账号。
- Data Studio 目录、查询、写入、物理编辑使用人的映射；同步和运维显式使用原数据源账号。目录以数据库实时可见对象为准，平台同步元数据只补充展示。人工查询历史也记录 JDBC 实际连接账号。
- agent API 保留共享服务令牌，新增三种身份头；普通查询先用人的只读连接 EXPLAIN，再用智能体只读连接执行原 SQL；独立任务只用智能体映射。元数据按对象和列取交集，只在本次请求内复用枚举结果。
- Python 从已验证的 topic 登录身份、智能体快照和后台调度来源填写身份。公开任务输入不能自报 schedule ID 取得独立模式。主门户也消费 DataAgent 自己的已验证会话；widget 自报用户不获得数据库身份。
- CLI 经运行期间的本地 HTTP 工具通道调用，固定本次身份，忽略模型请求头；可信服务端补服务令牌。MCP 由运行器填写身份。显式清空模型子环境里的服务令牌/数据库密码，避免 SDK 合并父环境时重新继承。
- 删除旧三表配套 entity/mapper、UserMappingService、旧权限服务/接口及测试、范围过滤器/SQL 表引用授权检查、范围头/配置、平台库凭据旁路、未使用的 Python 直连 resolver/tool/metadata loader。历史迁移保留；旧说明标记为历史，当前部署、知识资产和 CLI 文档已更新。
- 前端增加数据源“账号映射”，删除智能体“数据范围”，保留可见范围；缺映射/无权限返回明确原因。未重写 Element Plus/Sass 页面。

主要文件组：

| 范围 | 主要文件 |
| --- | --- |
| backend | `DatasourceSubjectAccount{Service,Controller,Mapper}`、实体、`DorisConnectionService`、`DorisMetadataSyncService`、`DataQueryService`、`DataQueryHistory` |
| backend-agent-api / 实现 | `AgentIdentityContext`、`AgentApiAuthInterceptor`、`AgentDatasourceAccessService`、`AgentJdbcExecutor`、`BackendAgent{Query,Metadata}Service`，及删除范围检查的任务/工作流入口 |
| Python | `core/agent_identity.py`、`tool_http_channel.py`、`task_coordinator.py`、`task_executor.py`、`agent_runtime.py`、`readonly_query_proxy.py`、profile/draft、routes/schemas、portal-mcp、odw-cli |
| 界面 | `DatasourceAccountMappings.vue`、`DataSourceManagement.vue`、`useCatalogTree.js`、两端 API、`AgentDetailView.vue`、`AgentStudio.vue`、demo 和回归测试 |
| 文档/迁移 | Flyway V54、Alembic 20261027_000030，设计/计划/部署/TODO、旧 RBAC 说明、技能知识资产与历史方案替代标记 |

## 接口与迁移

- 映射管理：`/api/v1/doris-clusters/{sourceId}/accounts`，GET/POST，DELETE `/{id}`；`/users` 返回平台用户的 ID/用户名。非管理员拒绝。
- 身份头：`X-Agent-Service-Token` + `X-Agent-User-Id`（`local:<用户名>` / `<provider>:<sub>`）+ `X-Agent-Id` + `X-Agent-Independent`（true/false）。普通请求缺用户标识或服务令牌拒绝，不自动转独立模式。
- **Flyway `V54__datasource_subject_accounts.sql`**：删除旧三表；创建唯一 `(source_id, subject_type, subject_id)` 映射；`sys_user.external_id` 唯一；历史增加 agent/check/execution 字段，原人/source/namespace 列继续使用。
- **Alembic `20261027_000030`**（前版 main 的 `20261026_000029`，合并 main 时改号）：删除 profile 范围列，清理 draft/published/topic/task 快照的旧范围键，保留登录身份。
- 实际从零执行全部 54 个 Flyway 迁移；另在 V53 的临时 schema 中插入数据源和旧三表关联数据，升级 V54 后源数据保留、旧三表删除、新映射为空。
- Alembic 从零升级通过；另在旧 head 插入 profile、草稿、发布内容、topic/task 范围快照后升级，新范围列/键删除，`auth_user_id=local:alice` 保留。
- 上线前需备份两库并检查非空 external_id 重复，开启认证、由 DBA 预建并登记人和智能体账号。本地用户名两边一致；OAuth 使用相同身份源。回退是停服务、恢复两库备份及旧程序，不支持仅降级镜像。

## 环境与命令

临时 Podman：MySQL 8.0.45 `odw-identity-mysql`，`127.0.0.1:13370`；PostgreSQL 16 `odw-identity-pg`，`127.0.0.1:55460`，database `odw_smoke`。平台、测试、升级副本仅在临时 MySQL 的 `odw_identity`、`odw_tests`、`odw_upgrade*`、`dataagent_identity`、`dataagent_upgrade*` 中运行。既有 `opendataworks` 库未修改。

Java HTTP 18088、DataAgent 18900、portal-mcp 18801；复用现有 Redis `127.0.0.1:6379` 的隔离 DB 13，不停止或清空其他 Redis DB。Python 用本 worktree 新建的 `.venv-py313`（Python 3.13），已验证 fastapi/uvicorn/alembic/pymysql/anyio/claude_agent_sdk 可导入。主/DA 前端 Node 20.19.0；Pi 从自身 `.nvmrc` 使用 22.19.0 并构建。

临时 Maven settings 的 mirror ID 为 `central`，URL 为 `https://repo.maven.apache.org/maven2`，mirrorOf 为 `*`，与已有离线缓存对齐。先做定向，再执行完整 reactor：

```sh
ODW_POSTGRES_TEST_PORT=55460 mvn -s /tmp/odw-identity-settings.xml -o -q -pl backend -am test \
  '-Dspring.datasource.url=jdbc:mysql://127.0.0.1:13370/odw_tests?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' \
  -Dspring.datasource.username=root -Dspring.datasource.password=identity-root
```

系统属性显式覆盖测试 profile 的默认 3306 库，避免触碰既有平台库。离线 clean 插件未缓存，已手工删除本 worktree backend/backend-agent-api 的编译目录及旧测试报告，再完整编译验证。打包检查已删除服务、权限控制器、PlatformUser 和范围类未留在 jar 中。

Python 定向 pytest 覆盖身份、profile/draft、runtime、只读代理、CLI、routes/auth/widget、task/coordinator、sandbox、内置技能内容和 portal-mcp；数据库环境固定为上述临时库。前端均先成功执行 nvm use，再分别执行 `npm --prefix <前端目录> test`、`run build`；复用已有依赖，未重新 install 前端依赖；DataAgent 前端/Pi 测试时的临时依赖符号链接已删除，主前端原有符号链接保持。

## 测试结果

最终全量：backend **588 项，579 通过、7 失败、0 错误、2 跳过**；odw-auth **31/31**、backend-agent-api **3/3**。reactor 合计 **622 项，613 通过、7 失败、0 错误、2 跳过**，Maven 退出码 1。七个失败逐一比对仍为下列既有 DolphinScheduler 用例，无新增失败；跳过仍为 RealDolphinSchedulerIntegrationTest 的连接/实例列表两个环境用例。

- PostgreSQL 真实集成：**8/8**，包括 agent 只读、URL/namespace、映射凭据、受限账号、元数据、预览和参考结构相关回归。
- Python / portal-mcp：**479 通过，0 失败**（定向模块；非全 Python 仓库测试）。覆盖身份固定、伪造独立来源拒绝、SDK 父环境密码清空及上下文恢复。
- 主前端：**59 文件 / 419 测试通过**；构建通过。
- DataAgent 前端：**66 文件通过、1 文件失败；743 通过 / 10 失败 / 合计 753**；构建通过。失败文件为本轮未修改的 `ToolOutputRenderer.spec.js`，在 `git archive HEAD` 的临时副本中单独重跑同样 **10 失败 / 12 通过**，确认是既有失败，已登记 TODO。两端构建仅有现有包体积/依赖告警。

DolphinScheduler 七个失败均为 `WorkflowRuntimeSyncRealIntegrationTest` 在 bootstrapOrSkip 连接 `http://localhost:12345/dolphinscheduler` 失败，未进入业务场景；与上一份报告逐名一致：

1. `forwardReverseSyncAndDiffShouldWork`
2. `importPreviewCommitAndExportShouldWorkWithRealDolphinExport`
3. `publishPreviewShouldOnlyShowChangedTasksForExistingRuntimeWorkflow`
4. `relationMismatchShouldRequireDecisionAndConvergeAfterManualSelection`
5. `reversePreviewShouldFailForUnsupportedNodeType`
6. `reversePreviewShouldPassForUpdateHeadWithoutInput`
7. `runtimeEvolutionShouldRefreshTasksLineageAndDataResult`

DataAgent 前端已有失败涉及 shell/read/generic trace 的折叠摘要、技能标签、SQL 面板/结果表和导出链接；实现与测试未在本轮修改，保留给单独修复。

## 真实 HTTP 权限矩阵

两个真实数据库的映射管理、认证、目录和 agent API 均经 HTTP；数据库 GRANT/REVOKE 由临时库 DBA 执行。P、A 分别是人的映射和智能体映射；MySQL 使用 mx/my database，PostgreSQL 使用 px/py schema。

| 场景 | MySQL 8.0.45 | PostgreSQL 16 |
| --- | --- | --- |
| P/A 都有权，原 SQL 双步执行 | 通过 | 通过 |
| P 无权/REVOKE，下一请求 | 人 EXPLAIN 拒绝 | 人 EXPLAIN 拒绝 |
| A 无权/REVOKE，下一请求 | A 执行拒绝 | A 执行拒绝 |
| 删除任一映射，下一请求 | 拒绝 | 拒绝 |
| 双方有权跨 namespace JOIN | 通过；撤销任一方则拒绝 | 通过；撤销任一方则拒绝 |
| 仅视图 SELECT（MySQL 另有 SHOW VIEW），无底表 SELECT | **EXPLAIN 拒绝** | 通过 |
| 视图账号直接查底表 | 拒绝 | 拒绝 |
| 目录与模型元数据交集，撤权后 | 隐藏无权对象 | 隐藏无权对象 |
| Studio 只读账号写入 / GRANT INSERT 后 | 拒绝 / 成功 | 拒绝 / 成功 |
| 物理结构编辑 | 无 ALTER 拒绝，GRANT ALTER 后成功 | 保持能力不支持，明确拒绝 |
| 本地/OAuth 前缀、独立模式 | 通过 | 通过 |
| 缺用户 ID / 未知用户 / 无服务令牌 / 写 SQL | 拒绝 | 拒绝 |
| 参考结构读取 | 双账号检查后通过 | 双账号检查后通过 |

**60 项 HTTP 断言通过**（46 项主矩阵、11 项结构编辑/参考结构/身份/历史与模型持久化补充、3 项实际账号与用户停用实时检查）。另验证非管理员不能管理映射、密码不回传、人的历史有 agent/check/execution 字段、其他用户看不到其历史；平台库不能再隐式用 Spring 凭据访问。DataAgent 无登录且自报用户 ID 的直接查询被拒绝；认证关闭时拒绝代理查询的回归由 pytest 覆盖。

MySQL 仅视图的原验收假设已被实测推翻；更新设计/计划/部署和 TODO，仍严格执行 EXPLAIN，不加 LIMIT 0、自动扩权或放宽。故上述“拒绝”是已核实的使用限制，不能宣称该引擎已支持只授视图的代人问数。

## 真实模型与清理

- 本机当前配置的 deepseek provider 探测 HTTP **200**，不再沿用上一轮“欠费未覆盖”的结论；真实使用模型 **deepseek-v4-pro**。
- 开启 DataAgent 自身本地认证，以 `local:alice` 登录；平台同名用户已登记映射。`agent_opendataworks` 有 MCP 与技能，并登记对应源账号。
- 实际 HTTP 建 topic、提交 `execution_mode=auto` 的任务：第一轮问 MySQL mx.x，第二轮同会话问 PostgreSQL px.x，均 running → finished，模型实际调用 MCP 只读查询并返回库中两行数据。主路径由 Pi 执行，SDK 记录 `engine_kind=pi_agent_core`。
- `/sdk-events` 记录、`/sdk-events/stream` 到 run.completed、最终 assistant 消息及查询历史已核对；系统提示两条入库，三个任务有 prompt hash。取消请求 HTTP 200，终态 suspended（既有 user_cancel 语义）。
- 真实 odw-cli 另经固定身份的本地通道 → Java → MySQL 返回两行；CLI 子环境没有服务令牌。
- 此为本地主进程 Pi 拓扑，未覆盖生产 child-container 跳转；未进行浏览器完整点击/渲染或真实外部 OAuth 提供方跳转。OAuth 前缀解析和账号权限已用真实 Java HTTP 覆盖；不能等同真实第三方 SSO 集成。
- 无本机 Doris：EXPLAIN 拒权、视图语义与 Select_priv 隔离需在内网补测；不增加引擎禁用或回退。

临时环境已清理：删除两个身份验证容器及对应匿名数据卷（通过 Podman 创建事件确认归属），临时库/账号随卷删除；停止自建 Java、DataAgent、portal-mcp 进程；通过认证 HTTP 删除问数 topic，并删除其工作区、HEAD 基线副本和自建依赖链接。既有 MySQL/Redis/其他容器保持运行，未清空其他 Redis DB；Python 本地虚拟环境保留用于复核。`git diff --check`、文档链接和最终文件计数检查通过。

## 2026-10-27 复核修改

- 删除独立模式（`X-Agent-Independent`）：唯一使用者是会话定时消息，改为以会话所有者身份执行，与手动发消息同一路径。每次访问都必须有用户标识和智能体 ID。
- 代人问数缺少人的账号时直接拒绝，不再以"没有检查账号"为条件跳过 EXPLAIN。
- 元数据可见性改为每个账号查询一次 `information_schema.columns`，替代逐表查询字段；已在 PostgreSQL 16、MySQL 8.0 实测该视图只返回账号有权限的字段（含列级授权）。Doris 需内网确认。
- 以上修改后的测试结果见本节末尾的复测记录。
