# Backend 终态架构收敛执行计划：四子模块边界、内置 MCP 与核心链路熵减

- 日期：2026-11-03
- 状态：已实施；Java / Vue / Python、真实 MySQL / Dolphin / HTTP MCP 验证通过。外部引擎及独立 DataAgent 完整问答覆盖边界见[实施报告](../reports/2026-11-03-backend-consolidation-validation.md)。
- 关联设计：`docs/design/2026-11-03-backend-mcp-auth-consolidation-design.md`
- 协同计划（职责不重叠）：`docs/plans/2026-11-03-backend-legacy-redundancy-cleanup-plan.md`
- 受影响栈：
  - 后端：`backend/{core,api,mcp-server,app}/` 四子模块（保留 `com.onedata.portal.*` 包名体系），`backend/pom.xml` 为唯一父工程及 Reactor
  - 消解与移除：根 `pom.xml`、顶层 `odw-auth/`、顶层 `backend-agent-api/`（含 7 个 `/api/v1/ai/**` Controller、`Agent*Service`/`BackendAgent*Service`/`Agent*DTO` 平行层）、Python `odw-mcp-server/`、旧 `backend/src/`
  - 部署与基础设施：`deploy/`、`scripts/`、`.github/workflows/docker-build.yml`、`backend/Dockerfile`
  - 外部协同：DataAgent（`opendataagent` 独立仓库，MCP URL 切换至 `/api/mcp/`，动态读取 23 个工具的顶层扁平 `inputSchema`）

---

## 1. 执行任务拆解

### Task 0：锁定 Java 17 / Spring Boot 3.5 / 官方 MCP SDK 的兼容版本组合与别名路由实验

**目标**：在迁移业务代码前，用最小隔离工程验证依赖版本与无状态传输链路。

1. 以 Java 17、Spring Boot `3.5.x`、官方 MCP Java SDK `0.18.5`（`mcp-bom` 统一管理 `mcp-core`、`mcp-json-jackson2`、`mcp-spring-webmvc`；注意 `mcp-core` 自身依赖 `reactor-core`，保留该正常传递依赖）、`mybatis-plus-spring-boot3-starter` 及 JSON Schema 校验器为基准，锁定无冲突的具体补丁版本。
2. 在最小验证工程中实测：
   - `WebMvcStatelessServerTransport` 在 context-path `/api` 下同时支持 `/mcp/` 与 `/mcp` 两个 POST/GET/DELETE 路径的具体装配方式（双路由绑定或 RequestWrapper 路径归一化，确保 POST 不依赖重定向）；
   - 无 `Mcp-Session-Id` 响应、`Accept: application/json, text/event-stream` 协商，以及 `McpTransportContextExtractor` 到同步工具 handler 的上下文传递。
3. 将锁定的精确版本号写入 `backend/pom.xml` 的 `<dependencyManagement>`。

---

### Task 1：搭建 `backend` 四子模块骨架（`core` + `api` + `mcp-server` + `app`）与协议边界守卫（回应 R4）

**目标**：建立正交的四子模块边界，不做无业务价值的全仓包名机械重命名。

1. 将 `backend/pom.xml` 改造为 `packaging=pom` 的父工程及 Reactor，声明 `<modules>` 为 `core`、`api`、`mcp-server`、`app`；仅 `app` 启用 `spring-boot-maven-plugin` 的 `repackage`。
2. 建立严格的子模块职责与依赖方向：
   - `core`：协议无关领域内核（严禁依赖 `api`、`mcp-server`、`app`；**严禁引入 `jakarta.servlet.*`、Spring MVC Filter/Controller、REST `Result<T>` 或 `io.modelcontextprotocol.*`**）；
   - `api`：门户 REST Controller、`Result<T>` 响应信封与 `GlobalExceptionHandler`（仅依赖 `core` 与 `spring-boot-starter-web`）；
   - `mcp-server`：官方 MCP SDK 无状态传输、23 个工具 Schema 与同步 Handler（仅依赖 `core` 与 MCP SDK，严禁依赖 `api`）；
   - `app`：`DataPortalApplication`、`HttpAuthenticationFilter`、`AuthenticationRoutePolicy`、Origin/CORS/私网边界配置、`application.yml` 与 Flyway 迁移（依赖 `core`、`api`、`mcp-server`）。
3. 编写架构边界守卫测试（ArchUnit 或字节码/import 扫描单测），断言 `core` 零 Servlet 依赖、零 MCP SDK 依赖、零 `Result<T>` 耦合，且 `mcp-server` 与 `api` 互不依赖。

---

### Task 2：重构统一身份上下文、账号认证服务与 HTTP 入口过滤链（回应 R3、R4、N2、N3）

**目标**：消除双套身份上下文，修复外部身份来源解析，将 HTTP 过滤与 Origin/私网策略归口到 `app` 装配层。

1. **统一身份与支持嵌套恢复的 `PrincipalContextHolder`（位于 `core`）**：
   - 定义不可变 `CurrentPrincipal`（`subjectId`、`accountType [USER/SERVICE]`、`authenticationMethod [SESSION/SERVICE_TOKEN]`、`username`、`role`、`sessionVersion`、`delegatedUserKey`）。
   - 实现 `PrincipalContextHolder.runWith(principal, action)`：进入时保存 `previousPrincipal` 与 `previousScope`，在 `finally` 中精确恢复外层上下文（仅最外层退出时清空 ThreadLocal）；请求级可见列缓存键强制包含 `(clusterId, subjectId, accountType, Objects.toString(delegatedUserKey, ""))`，绝不跨主体或跨委托身份串用。
   - 删除旧 `UserContext`、`UserContextHolder`、`AgentIdentityContext`、`ServiceAccountAuthenticator`。
2. **保持账号与认证领域服务职责清晰（位于 `core`）**：
   - 保留 `SysUserService`（登录认证、失败锁定、`verifySession`、个人改密）、`PlatformUserManagementService`（管理员对 `USER`/`SERVICE` 账号的 CRUD、启停、软删除、重置密码）与 `ServiceAccountTokenService`（服务 Token 签发、吊销、SHA-256 摘要认证），三者共享 `SysUserMapper` 与 `PlatformUserOperationMapper`（详见协同清理计划 Task 1）。
   - 迁移 `@RequireAuth`、`@RequireRole` 与 `AuthorizationAspect`（角色校验前强制校验 `accountType == USER`）。
3. **修复外部用户身份 `<authSource>:<externalId>` 精确匹配（回应 N2）**：
   - 在解析 `delegatedUserKey` 时，对 `local` 来源查询 `auth_source = 'local' AND username = #{subject} AND deleted = 0`；对非 `local` 来源**严格按 `auth_source = #{source} AND external_id = #{subject} AND deleted = 0` 精确查询**（替换原 `auth_source != 'local'` 模糊条件），并校验 `enabled = true` 且 `accountType == USER`。
4. **在 `app` 装配层实现 `HttpAuthenticationFilter` 与统一入口安全策略（回应 R4、N3）**：
   - 路由分类：
     - `PUBLIC`：仅限 `/auth/login`、`/auth/logout`、`/v1/health/**`、`/actuator/health`、`/error`（移除 `/v1/ai/**`，将 `/auth/me` 与 `/auth/password` 移入 `USER` 路由；彻底删除 `OdwAuthAutoConfiguration`、`odw.auth.enabled` 及 `odw.auth.anonymous.*` 匿名管理员回退开关）；
     - `USER`：`/auth/me`、`/auth/password`、`/v1/**`（仅接受 `odw_session` Cookie 或会话 Bearer JWT，显式 `Authorization` 优先且无效时不回退 Cookie，拒绝 `odw_sa_*`）；
     - `SERVICE`：`/mcp`、`/mcp/`（仅接受 `Bearer odw_sa_*`，提取可选 `X-Agent-User-Id` 为 `delegatedUserKey`）。
   - Origin / CORS / 私网边界统一：
     - 门户 CORS 由 `odw.http.allowed-origins` 显式配置允许来源，废除 `allowedOriginPatterns("*")` 任意回显；
     - `SERVICE` 路由（`/mcp`、`/mcp/`）不开启浏览器凭据 CORS；若请求携带 `Origin` 头则校验其属于 `allowed-origins`（否则 403）；开启 `require-private-network` 时校验客户端地址为私网/回环地址（仅当连接对端属于 `trusted-proxies` 时从右向左剥离受信代理取首个非受信地址，禁止取最左值；非法链拒绝，非受信对端忽略转发头）。

---

### Task 3：收敛数据源主体访问、查询流式守卫、物理 DDL 身份与统一发布预检（回应 R1、R3、R6、R7）

**目标**：消解 `BackendAgent*Service` 平行层，保留 `DatasourceConnectionFactory`，严格守恒人工/主体 DDL 权限，删除预览签名/nonce 机制并复用统一发布预检。

1. **保留 `DatasourceConnectionFactory`，收敛 `DatasourceSubjectAccessService`（回应 R1、R3）**：
   - 合并原 `DatasourceSubjectAccountService` 与 `AgentDatasourceAccessService` 的主体连接选择与库/表/列可见性交集逻辑为 `DatasourceSubjectAccessService`，底层继续调用 `DatasourceConnectionFactory`；
   - 将原 `AgentDatasourceResolution` 精简为不含任何密码与内部连接字段的纯元数据 DTO `DatasourceResolutionDto`（删除全部 10 个 `@JsonIgnore` 字段）；
   - **彻底废除 `DorisConnectionService` 中的 `ThreadLocal<Boolean> systemAccess`**，显式拆分为：
     - `openPrincipalConnection(clusterId, db, readOnly)` / `executeAsPrincipal(clusterId, db, sql)`：**供表设计器物理建表（`TableCreateService`）、表结构/注释编辑与软删恢复（`DataTableService` → `TableEngineHandler`）、Data Studio 实时目录/预览/DDL、Web 与 MCP 查询使用**（USER 用自身映射；SERVICE 无论是否携带 `delegatedUserKey` 均用自身映射执行 DDL，用户头只增加只读交集约束，不选择写账号；无映射或原生无权限直接拒绝）；
     - `openSystemConnection(clusterId, db)` / `executeAsSystem(clusterId, db, sql)`：**仅限元数据同步/稽核、数据源连接测试与采集范围预览、表容量统计采集、Schema 备份恢复、数据新鲜度/质量巡检、访问审计同步显式调用**。
2. **服务账号写映射与门户同步**：移除权限中心 SERVICE 只能分配只读角色及原生只读账号的硬编码门禁；同步 MemberRolesDialog/MemberAuthorizationDialog 的角色选择提示，保留 READONLY 模板与只读工具连接约束。实际 DDL 按主体映射的数据库权限决定允许或拒绝。
3. **物理表 DDL 归位至 `TableEngineHandler`（回应 R1）**：
   - 将原 `DorisConnectionService` 中残留的 Doris `ALTER TABLE` / `DROP TABLE` / 列定义拼接方法迁入 `DorisTableEngineHandler`；
   - `DorisTableEngineHandler` 与 `MysqlTableEngineHandler` 的建表、改表、删表方法统一调用 `datasourceSubjectAccessService.executeAsPrincipal(clusterId, database, sql)`，删除 `TableEngineJdbcExecutor`，并编写单测锁定“只读或无映射用户执行 DDL 被拒绝且不触发系统连接”。
3. **统一 `DataQueryService`、完整行预算与执行审计（回应 R3、R7、F4）**：
   - 将 `BackendAgentQueryService` 与 `AgentJdbcExecutor` 并入 `DataQueryService`，共用只读守卫、JDBC 读取器与查询历史写入；保留 Web 多语句与受限单语句各自入口。
   - MCP 只读查询传入 128KiB 行预算；在读取阶段对完整行计数，超限行不加入结果，停止读取并标记截断；超大首行返回空 rows 与说明。超大 LOB 按流计数并及时停止，不先完整加载。
   - 不截断或追加标记改变单元格原值，不对 Web 套用 MCP 字段限制。按唯一不可变结果写入行数、标记与预览，历史表示数据库执行结果，不承诺客户端交付成功。
4. **删除预览令牌并复用发布领域检查（回应 R6、F1、F2）**：
   - 删除 `AgentPreviewTokenSupport`、相关令牌 DTO/字段与旧测试，不新增 `WorkflowPublishPreviewTokenService`、随机密钥、nonce 表或清理任务；JWT 密钥仍按既有规则不足则拒绝启动。
   - `odw_preview_publish` 调用 `previewPublish`，只返回当前差异与预检信息；`odw_publish_workflow` 调用 `publish`，直接传入显式 `confirmDiff`，缺省 false，不自动确认。
   - 部署仍重新检查当前差异、`canPublish` 与差异确认；调度上线调用 `onlineSchedule` 检查当前状态；Web/MCP 复用领域规则，不新增审批或一次性执行要求。
5. **消解其余 `BackendAgent*Service` 平行包装类**：
   - 将表检索（`inspectTables`）、参考 DDL（`inspectTableDdl`）、元数据导出（`exportMetadata`）与元数据完善（`completeMetadata`）并入 `DataTableQueryService` / `DataTableService`；权限过滤血缘查询（`inspectLineage`）并入 `LineageService`；
   - 将任务部分更新保留 `workflowId` 绑定逻辑内聚进 `DataTaskService`；
   - 删除全部 6 个 `Agent*Service` 接口、6 个 `BackendAgent*Service` 实现类及跨模块中转 DTO。

---

### Task 4：清理 `/api/v1/ai/**` 并装配 `backend/api`

**目标**：`api` 模块仅暴露门户与认证 REST 接口，彻底移除内部过渡 `/api/v1/ai/**`。

1. 将门户业务 Controller、原 `AuthController`、`Result<T>` 与 `GlobalExceptionHandler` 迁入 `backend/api`（删除原 `odw-auth` 的 `ApiResponse<T>` 与 `AuthExceptionHandler`）。
2. **彻底删除全部 7 个 `Agent*Controller`（`/v1/ai/**`）、`AgentApiAuthInterceptor`、`AgentApiConfiguration`、`AgentApiProperties`**。
3. 运行门户 Controller 与认证测试，验证：
   - `/auth/me` 与 `/auth/password` 未登录时直接由 `HttpAuthenticationFilter` 拦截返回 401；
   - 人工建表/改表走主体映射账号；
   - `SERVICE` Token 访问 `/api/v1/**` 门户接口被 401 拒绝。

---

### Task 5：构建内置 MCP 协议适配层（`backend/mcp-server`），保持 23 个工具全部可用（回应 R2、R5、R7）

**目标**：基于官方 MCP Java SDK 暴露 `/api/mcp/`（及 `/api/mcp`），采用单一权威扁平 Schema，保持全部 23 个工具对已认证服务账号可用，并检查工具结果体积。

1. 在 `backend/mcp-server` 注册 `WebMvcStatelessServerTransport` 与 Stateless `McpServer`，配置 `McpTransportContextExtractor` 提取 `CurrentPrincipal`。
2. 在 `McpToolSchemaRegistry` 定义 23 个 `odw_*` 工具的顶层扁平、完全内联（零 `$ref`/`$defs`）JSON Schema：
   - 全部工具顶层及结构化嵌套对象均设置 `"additionalProperties": false`，移除 `{"params": {...}}` 外层包裹；
   - 全部固定参数采用现有 Java/Web 请求的 camelCase（如 dbName、workflowId、inputTableIds、confirmDiff）；显式枚举 task/workflow/schedule 与 columns 的嵌套字段，沿用其同名契约。工具名、SQL 和用户 Map 键不改写；不新增纯改名适配层。
   - 显式枚举 `task`、`workflow`、`schedule` 复合对象的允许属性（`schedule` 排除 `enabled`）；
   - `odw_update_task` 中 `inputTableIds` 允许 `[]`（清空输入血缘），`outputTableIds` 声明 `"minItems": 1`（禁止清空输出血缘）。
3. **单一 Schema 校验器 + 直接调用 `core` 领域服务**：
   - 使用标准 JSON Schema 校验器对入参 `arguments` 执行唯一权威校验，剥离可选 `description` 后按 camelCase 同名字段绑定现有请求对象；仅提取 workflowId/taskId 等方法参数、解包 task/workflow/schedule。删除纯改名映射，不新增 snake_case 别名或兼容 DTO，不改门户全局 ObjectMapper；默认值由适配方法一次赋值，不依赖 Schema 校验器注入默认；
   - **对已认证的 `SERVICE` 主体开放全部 23 个工具的执行**（不设硬编码拦截 17 个工具的 403 门禁），数据库操作遵循主体原生权限，发布/上线复用领域预检；不将这些规则当作尚未实现的平台资源 RBAC。
4. **MCP 工具结果体积检查（回应 R7、F4）**：
   - 查询传入 128KiB 行预算；适配层不再删行、改值或更新查询历史。
   - 对全部 `tools/call` 的最终 `CallToolResult`（含 TextContent 与 structuredContent）测量 JSON UTF-8 大小，工具结果上限 512KiB；超限返回固定简短 `isError`，说明业务可能已执行，不自动重试。错误、DDL、元数据和列表均覆盖。
   - 回归同时记录 SDK JSON-RPC 外壳后的实际响应大小，核对 DataAgent 接收上限，不将工具结果预算混称为整个 HTTP 响应上限。
5. **MCP 契约测试**：验证 23 工具正常调用、顶层扁平 Schema、未知字段/旧 params 拒绝、所有固定参数（含嵌套字段）统一 camelCase、同名对象绑定及结构解包，旧 snake_case 参数被未知字段校验拒绝、缺省/null/血缘三态；发布与调度上线不再接受 `preview_token`，发布差异未确认时仍按领域规则拒绝。

---

### Task 6：`app` 装配、清理旧目录与更新部署/CI/技能回归

1. 在 `backend/app` 完成 `DataPortalApplication`、`HttpAuthenticationFilter`、CORS/Origin 配置、`application.yml` 及 `db/migration` 装配。
2. 删除根 `pom.xml`、`odw-auth/`、`backend-agent-api/`、`odw-mcp-server/` 及旧 `backend/src/`。
3. 更新 `backend/Dockerfile`、`.github/workflows/docker-build.yml`、`scripts/`、`deploy/docker-compose.{dev,prod}.yml`、`deploy/.env.example`、文档与 `TODO.md`，移除 `odw-mcp-server` 容器、`8801` 端口与 `AGENT_API_SERVICE_TOKEN`。
4. 同步更新 `odw-skills/` 配方及回归：删除 preview token 获取/传递/过期重试流程，发布示例使用显式 `confirmDiff`（仅接受差异的含义），调度上线仅传 workflowId；将全部工具示例/脚本参数和相关文档统一为 camelCase，更新 description 与缺省契约；回归检查旧参数名没有留在可执行配方中。实施时使用模块专用 Python >=3.10 虚拟环境安装依赖并运行定向 pytest。
5. 更新主计划移除 Python MCP 的部署与用户说明；DataAgent 刷新工具 Schema 缓存，不保留 preview_token、params 或旧 snake_case 参数别名兼容。

---

## 2. 触碰文件清单（Touched Files）

| 类别 | 操作 | 路径 |
|---|---|---|
| 父工程与四子模块 POM | 修改／新增 | `backend/pom.xml`、`backend/{core,api,mcp-server,app}/pom.xml` |
| `core`（协议无关领域内核） | 重构迁入 | `backend/core/src/`：主体上下文、认证/账号/Token 领域服务、`DatasourceConnectionFactory`、`DatasourceSubjectAccessService`、`DataQueryService`、业务 Service/Entity/Mapper 及单测 |
| `api`（门户 Web REST） | 重构迁入 | `backend/api/src/`：门户与认证 Controller、`Result<T>`、`GlobalExceptionHandler` 及测试 |
| `mcp-server`（内置 MCP） | 新增 | `backend/mcp-server/src/`：官方 SDK 无状态传输、23 个顶层扁平工具 Schema、标准 JSON Schema 校验器、23 个工具 Handler、512KiB 工具结果守卫及测试 |
| `app`（启动与 HTTP 入口边界） | 迁入／新增 | `backend/app/src/`：`DataPortalApplication`、`HttpAuthenticationFilter`、`AuthenticationRoutePolicy`、CORS/Origin/私网配置、`application.yml`、`db/migration/**` |
| 删除的旧模块与目录 | 删除 | 根 `pom.xml`、`odw-auth/`、`backend-agent-api/`、`odw-mcp-server/`、旧 `backend/src/` |
| 镜像、部署、CI 与文档 | 修改 | `backend/Dockerfile`、`deploy/docker-compose.{dev,prod}.yml`、`deploy/.env.example`、`.github/workflows/docker-build.yml`、`scripts/**/*.sh`、`README.md`、`AGENTS.md`、`deploy/README.md`、`docs/TODO.md` |

---

## 3. 验证方案（Verification）

1. **架构边界与安全回归单测**：
   - 确认 Maven 使用 JDK 17，按 `backend/pom.xml` Reactor 编译/打包与定向测试，核对依赖树、组件/Mapper 扫描、空库与旧库快照 Flyway 迁移；不以 repair/baseline 绕过升级问题。
   - 账号回归保留登录、锁定、改密、会话版本失效、USER/SERVICE 隔离及 Token 过期/吊销/账号停用；覆盖 Cookie/Bearer 优先级、无效 Bearer 不回退 Cookie、请求上下文并发隔离与嵌套恢复。
   - 覆盖同源门户登录及 OPTIONS/Origin 策略、非受信连接伪造转发头、受信代理后的伪造最左 X-Forwarded-For，确保实际非私网客户端不能伪装私网。
   - 运行协议边界单测：确认 `core` 无 `jakarta.servlet.*`、无 `io.modelcontextprotocol.*`、无 `Result<T>` 依赖；
   - **人工与 Agent DDL 权限回归测试（验证 R1）**：验证只读映射或无映射的 USER/SERVICE 执行 DDL 被拒且不触发系统连接；另用无写权限 SERVICE 携带有写权限 USER 的头，确认仍使用 SERVICE 映射并被拒，不打开该 USER 写连接；
   - **外部身份解析测试（验证 N2）**：验证不同非 `local` 身份源存在相同 `externalId` 时，按 `<authSource>:<externalId>` 精确命中对应 `auth_source`；
   - **发布预检测试（验证 R6、F1、F2）**：预览不返回凭证，未先调用预览也必须执行相同领域预检；当前不可发布、差异需确认但 `confirmDiff` 未给/为 false 时拒绝；调度上线状态检查仍生效，不新增 nonce 或随机密钥依赖；
   - **MCP 响应体积探针测试（验证 R7）**：构造含 250,000 个双引号的行数据及 600KB 超大首行，验证完整行限额、原始单元格不被改写、结果与审计一致，以及所有工具结果超限均返回简短错误；检查大 LOB 读取与真实驱动预缓冲。记录 CallToolResult 及 SDK 外壳后的实际大小，不以两个小样例证明不会 OOM。
2. **本地全链路端到端冒烟验证**：
   - 复用仓库 `dev-testing-standards`：先检查 MySQL、Dolphin 与 Token 有效性；仅创建带本轮标识的临时资源，setup/cleanup 可重入，容器操作放在测试代码外。记录实际命令、通过/失败/跳过数量与环境缺口。
   - 复用既有至少 5～6 任务、含串行/并行/多上游的真实工作流回归，检查发布后的表数据；覆盖任务增删、SQL/依赖表变更、调度 cron/时区/起止时间及适用的任务组变更，核对任务绑定、血缘、版本/发布记录和业务结果。保留既有版本比较与回滚不覆盖历史的回归，不仅断言工具 HTTP 成功。
   - 启动 `backend/app` 可执行 jar，验证 `/api/mcp/` 与 `/api/mcp` 别名下 23 个工具（含只读问数、`odw_list_workflows`、`odw_analyze_sql`、`odw_preview_create_table`、`odw_preview_publish` 等）均可由服务账号 Token 正常调用；
   - 联动本地 DataAgent，通过门户智能问数验证查询数据表与查询工作流数量均正常返回；门户回归登录、当前用户、目录、Data Studio 与账号/Token 管理。
   - 真实源验证 SERVICE 自身写权限：只读 SERVICE + 有写权限 USER 头仍被拒绝，合法 SERVICE 写入与审计归属正确；发布预览与正式发布走相同当前状态检查。检查已有库升级后的持久化字段与 Token 可用性；PG/Doris 缺少环境时保留现有待办并报告未覆盖。

---

## 4. 上线与回滚（Rollout & Backout）

1. **上线**：完成 Task 0～6 与协同清理计划后，部署新版 `backend` 镜像与移除 `odw-mcp-server` 后的 Compose 配置，将 DataAgent MCP URL 切换至 `/api/mcp/` 并刷新工具缓存。
2. **回滚**：恢复上一版 `backend` 与 `odw-mcp-server` 镜像及 Compose 配置，将 DataAgent MCP URL 切回 `:8801/mcp/`。
