# 后端与门户历史冗余深度清理设计：持久化收口、Dolphin 目标显式传递与废弃接口下线

- 日期：2026-11-03
- 状态：已按熵减评审（R8、N1）修订，待实施
- 关联计划：`docs/plans/2026-11-03-backend-legacy-redundancy-cleanup-plan.md`
- 协同架构设计（边界严格分离）：`docs/design/2026-11-03-backend-mcp-auth-consolidation-design.md`
- 关联评审：`docs/reports/2026-11-03-backend-consolidation-entropy-review.md`
- 受影响栈：`backend`（平台 MySQL 表 Mapper 层、Dolphin 客户端与调用链、废弃 Controller/Service 清理）、`frontend`（`src/api/*.js` 死方法与未引用死组件 `DatasourceAccountMappings.vue` 清理）

---

## 1. 背景与文档职责边界（回应 R8）

为避免与 [Backend 终态架构收敛设计](2026-11-03-backend-mcp-auth-consolidation-design.md) 产生重复归属或验收冲突，两份设计按**唯一职责归属**严格划分边界：

- **归属 [Backend 终态架构收敛设计](2026-11-03-backend-mcp-auth-consolidation-design.md) 的事项（本文档不再重复定义）**：
  - Spring Boot 3.5 / JDK 17 / 官方 MCP SDK 升级与四子模块（`core`/`api`/`mcp-server`/`app`）划分；
  - 统一身份上下文（`CurrentPrincipal` / `PrincipalContextHolder`）、外部用户 `<authSource>:<externalId>` 精确解析（N2）、`HttpAuthenticationFilter`、白名单收窄、匿名回退开关移除、Origin/Host/私网入口策略（N3）；
  - 数据源主体访问（`DatasourceSubjectAccessService`）、只读 SQL 守卫与流式字节预算（`DataQueryService`）、物理 DDL 主体身份守恒（R1）、统一发布预检与预览令牌删除（R6、F1/F2）及 23 个 MCP 工具迁移。
- **唯一归属本文档的 4 项历史技术债清理**：
  1. **平台自身 MySQL 表持久化收口**：消除 `PlatformUserManagementService`、`ServiceAccountTokenService`、`DatasourceSubjectAccountService`、`DatasourcePermissionService` 中同表混用 `MyBatis-Plus Mapper` 与裸 `JdbcTemplate` 的双轨状态，删除 `PermissionJdbc`；
  2. **Dolphin 调用链去隐式状态（回应 N1）与 HTTP 客户端升级**：消除 `DolphinSchedulerService` 与 `DolphinOpenApiClient` 双层 `ThreadLocal<DolphinConfig>` 与两处重复默认配置回退，改为入口一次解析、显式传递；并在 Spring Boot 3.5 基线上用同步 `RestClient` 替换阻塞 `.block()` 的 `WebClient`，移除 `spring-boot-starter-webflux`；
  3. **下线已被新版权限中心替代的旧账号映射入口**：删除 `DatasourceSubjectAccountController`（`/v1/doris-clusters/{sourceId}/accounts/**`）及前端死组件 `DatasourceAccountMappings.vue`（注：遵循熵减评审原则，**不再合并 `DolphinDatasourceController`、`DolphinTaskGroupController`、`DatasourceTypeController` 等正常工作的单方法 Controller**）；
  4. **下线前后端确认零调用的废弃 REST 接口与死 Service**：删除旧单任务执行监控（8 个死接口 + 474 行 `TaskExecutionService`）、单任务触发工作流遗留接口、Dolphin 单实例旧读写接口、`SqlTableMatcherController` 未使用的 `match`/`bind` 接口，以及 `DataTableController` 5 个未使用接口与 `DataExportService`。

---

## 2. 现状问题与目标态方案

### 2.1 平台自身 MySQL 表统一收口至 MyBatis-Plus Mapper

#### 现状问题
1. `PlatformUserManagementService` 与 `ServiceAccountTokenService` 同时注入 `SysUserMapper` 与 `JdbcTemplate`，对同一张 `sys_user` 表既用 Mapper 又手写裸 SQL，且两处重复手写了相同的 `INSERT INTO platform_user_operation` 审计 SQL。
2. `ServiceAccountTokenService` 用 `JdbcTemplate.queryForList` / `queryForMap` 操作 `service_account_token`，在 SQL 中拼接 `DATE_FORMAT` 并返回弱类型 `Map<String, Object>`。
3. `DatasourceSubjectAccountService` 与 `DatasourcePermissionService` 用 300+ 行裸 `JdbcTemplate` SQL 操作平台 MySQL 的 `datasource_access_role`、`datasource_access_grant`、`datasource_access_member`、`datasource_access_member_role`、`datasource_access_audit_log` 五张表。
4. `PermissionJdbc` 重复实现了 JDBC `PreparedStatement` 参数绑定与 `ResultSetMetaData` 转 `List<Map<String, Object>>`。

#### 目标态方案
1. **扩展 `SysUserMapper`**：将用户名全局唯一性计数（含软删除行）、活跃管理员计数、`FOR UPDATE` 行锁、`session_version + 1` 原子递增、重置密码并递增会话版本、软删除并递增会话版本全部定义为 `SysUserMapper` 的 `@Select` / `@Update` 方法。
2. **新增 `PlatformUserOperation` 实体与 `PlatformUserOperationMapper`**：作为 `platform_user_operation` 审计写入的唯一入口。
3. **新增 `ServiceAccountToken` 实体与 `ServiceAccountTokenMapper`**：封装 Token 列表、创建、吊销、按 `token_hash` 关联 `sys_user` 认证及 `last_used_at` 更新，移除 `JdbcTemplate` 与弱类型 `Map`。
4. **新增权限中心 Mapper（`DatasourceAccessPermissionMapper`）**：将 `datasource_access_*` 五张平台 MySQL 表的读写与成员不确定状态计数子查询收口至 MyBatis Mapper；外部数据源原生权限回读改用数据源连接层的标准 JDBC 方法，删除 `PermissionJdbc.java`。
5. **DuckDB SQL 契约单测**：通过反射提取上述 Mapper 上的生产 `@Select`/`@Update` SQL，在内存 DuckDB（`jdbc:duckdb:`）中验证契约。

---

### 2.2 Dolphin 执行目标显式传递（回应 N1）与 `RestClient` 替换 WebFlux（回应 R8）

#### 现状问题
1. **双层 `ThreadLocal<DolphinConfig>` 与重复默认回退（评审 N1）**：
   - `DolphinSchedulerService.java:63`（`scopedConfig`）与 `DolphinOpenApiClient.java:49`（`scopedConfig`）各自维护了一份 `ThreadLocal<DolphinConfig>`；
   - `DolphinSchedulerService.withConfig` 必须同时绑定两处 ThreadLocal；当上下文为空时，`DolphinSchedulerService.getConfig()` 与 `DolphinOpenApiClient.getWebClient()` 又各自回退去查 `dolphinConfigService.getActiveConfig()`。
2. **为阻塞调用引入整套 WebFlux**：
   - 全仓仅 `WebClientConfig` 与 `DolphinOpenApiClient` 使用了 `WebClient`，且所有调用均通过 `.block()` 同步阻塞执行。

#### 目标态方案
1. **消除双层 `ThreadLocal<DolphinConfig>`，改为入口解析一次、显式传参（回应 N1）**：
   - 删除 `DolphinSchedulerService.scopedConfig` 与 `DolphinOpenApiClient.scopedConfig` 两个 `ThreadLocal`，删除 `withConfig` 隐式上下文绑定；
   - `DolphinConfigService.resolveActiveOrTargetConfig(Long dolphinConfigId)` 作为唯一解析入口：传入非空 `dolphinConfigId` 时校验并返回该启用配置；传入 `null` 时在业务入口唯一解析一次当前有效默认配置；
   - `DolphinSchedulerService` 将解析出的只读配置快照（同一次业务操作期间不修改 `DolphinConfig`）显式作为首参传递给 `DolphinOpenApiClient` 的各个方法（如 `get(DolphinConfig config, String path, ...)`、`postForm(DolphinConfig config, ...)`），底层客户端不再自行回退查询默认配置，彻底消除隐式线程状态。
2. **在 Spring Boot 3.5 基线上切换至同步 `RestClient`（回应 R8）**：
   - 本任务在主收敛计划完成 Spring Boot 3.5 升级后执行；
   - 使用 `spring-boot-starter-web` 内置的同步 `RestClient`（底层基于 JDK `HttpClient`）替换 `WebClient`，将 `.retrieve().bodyToMono(...).block()` 替换为同步 `.retrieve().body(...)`；
   - `GlobalExceptionHandler` 中将 `WebClientResponseException` 替换为 `RestClientResponseException`，删除 `WebClientConfig.java`；
   - 从 `pom.xml` 移除不再使用的 `spring-boot-starter-webflux`（及 Netty 传递依赖；保留 `mcp-server` 中官方 `mcp-core` SDK 正常依赖的 `reactor-core`）。

---

### 2.3 下线废弃的重叠账号映射入口与前后端死代码

遵循熵减原则，**不合并正常工作的单方法 Controller**，仅精准删除已被新功能完全取代或前后端调用次数为 0 的废弃代码：

| 清理项 | 后端删除内容 | 前端删除内容 | 依据与影响 |
|---|---|---|---|
| 1. 旧版数据源账号映射入口 | 删除 `DatasourceSubjectAccountController`（`/v1/doris-clusters/{sourceId}/accounts/**`）、`DatasourcePermissionService.bindLegacyAccount` / `removeLegacyAccount`、`DatasourceSubjectAccountService` 的 `list`/`save`/`delete` | 删除未引用死组件 `frontend/src/views/settings/components/DatasourceAccountMappings.vue` 及 `frontend/src/api/doris.js` 中的 `accounts`、`accountUsers`、`saveAccount`、`removeAccount` | 账号绑定已统一由 `DatasourcePermissionController`（`/v1/doris-clusters/{sourceId}/permissions/members/{type}/{subject}/account`）承载 |
| 2. 旧版单任务级执行监控 | 从 `TaskExecutionController` 删除 `/history`、`/{id}`、`/recent`、`/{id}/sync`、`/statistics`、`/failed`、`/running`、`POST /v1/executions` 8 个方法；**删除整套 474 行的 `TaskExecutionService.java`**（保留 `TaskExecutionLogMapper.selectDashboardStatistics` 供 `DashboardController` 使用） | 从 `frontend/src/api/execution.js` 删除对应的 8 个未使用函数（仅保留 `getWorkflowExecutionInstances` 与 `getWorkflowExecutionTasks`） | 执行监控已全面升级为 `WorkflowExecutionMonitorService` 工作流实例级监控，上述 8 个接口前端零调用 |
| 3. 旧版单任务触发工作流与状态接口 | 从 `DataTaskController` 删除 `POST /v1/tasks/{id}/execute-workflow` 与 `GET /v1/tasks/{id}/execution-status`；从 `DataTaskService` 删除 `@Deprecated executeTask`、`@Deprecated execute`、`executeWorkflow`、`getLatestExecutionStatus` | 从 `frontend/src/api/task.js` 删除 `execute`、`executeWorkflow`、`getExecutionStatus` | 工作流执行已统一走 `POST /v1/workflows/{id}/execute`，前端零调用 |
| 4. Dolphin 单实例时代读写接口 | 从 `DolphinConfigController` 删除 `GET /v1/settings/dolphin` 与 `PUT /v1/settings/dolphin`；从 `DolphinConfigService` 删除 `getConfig()` 与 `updateConfig(DolphinConfig)` | 从 `frontend/src/api/settings.js` 删除 `getDolphinConfig` 与 `updateDolphinConfig`（保留 `testDolphinConnection` 与多环境 `/configs/**`） | Dolphin 已升级为多环境配置管理，单实例读写接口前端零调用 |
| 5. `SqlTableMatcher` 未使用接口 | 从 `SqlTableMatcherController` 删除 `POST /v1/sql-table-matcher/match` 与 `POST /v1/sql-table-matcher/tasks/{taskId}/bind`；从 `SqlTableMatcherService` 删除 `match`、`bindTaskRelations` 及 `SqlTableMatchResponse.java` | 无（前端仅封装了 `/analyze`） | 全仓仅使用 `/v1/sql-table-matcher/analyze` |
| 6. `DataTable` 5 个未使用接口与表数据导出 | 从 `DataTableController` 删除 `GET /v1/tables/all`、`POST /v1/tables`、`GET /v1/tables/statistics/database/{database}`、`GET /v1/tables/{id}/statistics/history/last7days`、`last30days`、`GET /v1/tables/{id}/export`；从 `DataTableQueryService` 删除对应方法，并**彻底删除 `DataExportService.java` 与 `TableExport.java`**（主收敛设计同步不再保留 `DataExportService`） | 从 `frontend/src/api/table.js` 删除 `listAll`、`create`、`getDatabaseStatistics`、`getLast7DaysHistory`、`getLast30DaysHistory` | 分别已被 `/v1/tables/options`、`POST /v1/table-designer`、带 `limit` 的 `/statistics/history` 替代；`/export` 从未被前端接入 |
| 7. JDBC 配置命名规范化 | 将被 Doris / MySQL / PostgreSQL 所有连接器共用的 `DorisJdbcProperties` 重命名为通用的 `DatasourceJdbcProperties` | 无 | 消除“MySQL/PG 连接器注入 `DorisJdbcProperties`”的命名错位 |
