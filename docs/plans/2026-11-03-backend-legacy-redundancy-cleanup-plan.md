# 后端与门户历史冗余深度清理执行计划：持久化收口、Dolphin 目标显式传递与废弃接口下线

- 日期：2026-11-03
- 状态：已按熵减评审（R8、N1）修订，待执行
- 关联设计：`docs/design/2026-11-03-backend-legacy-redundancy-cleanup-design.md`
- 前置/协同计划：`docs/plans/2026-11-03-backend-mcp-auth-consolidation-plan.md`（认证过滤链、白名单收窄、匿名回退移除、数据源主体访问与只读 SQL 守卫归属主收敛计划；本计划专注平台表 Mapper 收口、Dolphin 双 `ThreadLocal` 消除与 `RestClient` 切换、以及废弃接口/死代码清理）
- 受影响栈：
  - 后端（`backend`）：MyBatis-Plus Mapper 层、Dolphin 服务与客户端、废弃 REST Controller 方法与死 Service
  - 前端（`frontend`）：`src/api/{doris,execution,task,settings,table}.js` 及死组件 `src/views/settings/components/DatasourceAccountMappings.vue`

---

## 1. 执行任务拆解

### Task 1：平台自身 MySQL 表统一收口至 MyBatis-Plus Mapper（消除 Service 层裸 `JdbcTemplate`）

**目标**：移除 `PlatformUserManagementService`、`ServiceAccountTokenService`、`DatasourceSubjectAccountService`、`DatasourcePermissionService` 中的裸 `JdbcTemplate` SQL，统一为强类型 Entity + Mapper，并补齐 DuckDB SQL 契约单测。

1. **扩展 `SysUserMapper`**：
   - 将 `PlatformUserManagementService` 与 `ServiceAccountTokenService` 中针对 `sys_user` 的裸 SQL 下沉为 `SysUserMapper` 的 `@Select` / `@Update` 方法（`countAllByUsername`、`countActiveAdmins`、`lockActiveAdminIds`、`lockUserById`、`incrementSessionVersion`、`updatePasswordAndInvalidateSession`、`softDeleteAndInvalidateSession`）。
2. **新增 `PlatformUserOperation` 实体与 `PlatformUserOperationMapper`**：
   - 替代 `PlatformUserManagementService.audit` 与 `ServiceAccountTokenService.audit` 中重复手写的 `INSERT INTO platform_user_operation` 裸 SQL。
3. **新增 `ServiceAccountToken` 实体、`ServiceAccountTokenDto` 与 `ServiceAccountTokenMapper`**：
   - 将 `ServiceAccountTokenService` 中的 `list`、`insert`、`selectByTokenHash`、`revoke`、`authenticate` 及 `touchLastUsedAt` 全部收口至 `ServiceAccountTokenMapper`，移除 `JdbcTemplate` 与弱类型 `Map<String, Object>`。
4. **收口权限中心 5 张表至 MyBatis Mapper 并删除 `PermissionJdbc`**：
   - 为 `datasource_access_role`、`datasource_access_grant`、`datasource_access_member`、`datasource_access_member_role`、`datasource_access_audit_log` 建立对应的 Entity / Mapper（含成员不确定状态计数子查询），移除 `DatasourcePermissionService` 中的裸 `JdbcTemplate`；
   - 外部数据源连接上的原生权限回读统一调用数据源连接层提供的标准 JDBC 查询方法，删除 `PermissionJdbc.java`。
5. **编写 DuckDB 内存 SQL 契约单测**：
   - 通过反射读取新增 Mapper 方法上的 `@Select`/`@Update` 注解 SQL，在内存 DuckDB（`jdbc:duckdb:`）中验证语法与结果契约。

---

### Task 2：消除 Dolphin 双层 `ThreadLocal<DolphinConfig>` 并切换至同步 `RestClient`（回应 N1、R8）

**目标**：在主收敛计划完成 Spring Boot 3.5 升级后，消除 `DolphinSchedulerService` 与 `DolphinOpenApiClient` 的两份线程上下文与重复默认回退，并用同步 `RestClient` 替代阻塞式 `WebClient`。

1. **消除双层 `ThreadLocal<DolphinConfig>`，改为入口解析一次、显式传参（回应 N1）**：
   - 删除 `DolphinSchedulerService.scopedConfig` 与 `DolphinOpenApiClient.scopedConfig` 两个 `ThreadLocal<DolphinConfig>`，删除 `withConfig` 隐式绑定方法；
   - 在 `DolphinSchedulerService` 的业务入口调用唯一解析方法 `DolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId)`（非空 ID 校验目标启用状态，null 仅在入口选择当前有效默认配置），得到操作期间只读的配置快照；
   - 将 `DolphinConfig` 显式作为参数传递给 `DolphinOpenApiClient` 的所有请求方法，`DolphinOpenApiClient` 内部不再自行回退查询默认配置。
2. **用 Spring Boot 3.5 原生同步 `RestClient` 替换 `WebClient`（回应 R8）**：
   - 将 `DolphinOpenApiClient` 中的 `WebClient` 缓存与 `.retrieve().bodyToMono(...).block()` 全部替换为基于 `JdkClientHttpRequestFactory` 的同步 `RestClient` 与 `.retrieve().body(...)`；
   - 将 `GlobalExceptionHandler` 中的 `WebClientResponseException` 替换为 `RestClientResponseException`，删除 `WebClientConfig.java`；
   - 从 `pom.xml` 移除 `spring-boot-starter-webflux`（注：验证时确认 `spring-boot-starter-webflux` 与 `reactor-netty` 已移除；`mcp-server` 因官方 `mcp-core` SDK 传递引入 `reactor-core` 属正常依赖，不做排除）。
3. **重命名通用 JDBC 配置类**：
   - 将多引擎共用的 `DorisJdbcProperties` 重命名为 `DatasourceJdbcProperties`。

---

### Task 3：精准下线废弃 REST 接口、死 Service 与前端死代码

**目标**：删除已被新功能完全取代或前后端零调用的废弃代码（保持正常工作的单方法 Controller 不变）。

1. **下线旧版数据源账号映射入口与前端死组件**：
   - 删除后端 `DatasourceSubjectAccountController`（`/v1/doris-clusters/{sourceId}/accounts/**`）、`DatasourcePermissionService.bindLegacyAccount` / `removeLegacyAccount`、`DatasourceSubjectAccountService` 的 `list`/`save`/`delete`；
   - 删除前端死组件 `frontend/src/views/settings/components/DatasourceAccountMappings.vue` 及 `frontend/src/api/doris.js` 中的 `accounts`、`accountUsers`、`saveAccount`、`removeAccount`。
2. **下线旧版单任务执行监控接口与 `TaskExecutionService`**：
   - 从 `TaskExecutionController` 删除 `/history`、`/{id}`、`/recent`、`/{id}/sync`、`/statistics`、`/failed`、`/running`、`POST /v1/executions` 及 `CreateExecutionLogRequest`（保留工作流实例监控的 2 个接口）；
   - 删除 `TaskExecutionService.java`（474 行）；
   - 从 `frontend/src/api/execution.js` 删除对应的 8 个死函数。
3. **下线单任务触发工作流接口与 `@Deprecated` 方法**：
   - 从 `DataTaskController` 删除 `POST /v1/tasks/{id}/execute-workflow` 与 `GET /v1/tasks/{id}/execution-status`；
   - 从 `DataTaskService` 删除 `@Deprecated executeTask`、`@Deprecated execute`、`executeWorkflow`、`getLatestExecutionStatus`；
   - 从 `frontend/src/api/task.js` 删除 `execute`、`executeWorkflow`、`getExecutionStatus`。
4. **下线 Dolphin 单实例读写接口、`SqlTableMatcher` 未使用接口及 `DataTable` 5 个死接口**：
   - 从 `DolphinConfigController` 删除 `GET /v1/settings/dolphin` 与 `PUT /v1/settings/dolphin`；从 `DolphinConfigService` 删除 `getConfig()` 与 `updateConfig(DolphinConfig)`；从 `frontend/src/api/settings.js` 删除 `getDolphinConfig` 与 `updateDolphinConfig`；
   - 从 `SqlTableMatcherController` 删除 `/match` 与 `/tasks/{taskId}/bind`；从 `SqlTableMatcherService` 删除 `match`、`bindTaskRelations` 及 `SqlTableMatchResponse.java`；
   - 从 `DataTableController` 删除 `GET /v1/tables/all`、`POST /v1/tables`、`GET /v1/tables/statistics/database/{database}`、`GET /v1/tables/{id}/statistics/history/last7days`、`last30days`、`GET /v1/tables/{id}/export`；从 `DataTableQueryService` 删除对应方法，彻底删除 `DataExportService.java` 与 `TableExport.java`；从 `frontend/src/api/table.js` 删除对应死函数。

---

## 2. 触碰文件清单（Touched Files）

| 类别 | 操作 | 路径 |
|---|---|---|
| 平台持久化 Mapper 收口 | 修改／新增／删除 | `SysUserMapper.java`、`ServiceAccountToken.java`、`ServiceAccountTokenMapper.java`、`PlatformUserOperation.java`、`PlatformUserOperationMapper.java`、`DatasourceAccessPermissionMapper.java`、`PlatformUserManagementService.java`、`ServiceAccountTokenService.java`、`DatasourcePermissionService.java`；删除 `PermissionJdbc.java` |
| Dolphin 显式目标与 `RestClient` | 修改／删除 | `DolphinConfigService.java`、`DolphinSchedulerService.java`、`DolphinOpenApiClient.java`、`GlobalExceptionHandler.java`、`DorisJdbcProperties.java` → `DatasourceJdbcProperties.java`、`pom.xml`；删除 `WebClientConfig.java` |
| 废弃接口与死 Service 清理 | 删除／修改 | 删除 `DatasourceSubjectAccountController.java`、`TaskExecutionService.java`、`DataExportService.java`、`TableExport.java`、`SqlTableMatchResponse.java`；修改 `TaskExecutionController.java`、`DataTaskController.java`、`DataTaskService.java`、`DolphinConfigController.java`、`SqlTableMatcherController.java`、`SqlTableMatcherService.java`、`DataTableController.java`、`DataTableQueryService.java` |
| 前端死组件与死 API 清理 | 删除／修改 | 删除 `frontend/src/views/settings/components/DatasourceAccountMappings.vue`；修改 `frontend/src/api/{doris,execution,task,settings,table}.js` |

---

## 3. 验证方案（Verification）

后端命令统一从 `backend/pom.xml` Reactor 运行，按主计划实际模块归属选择 `-pl` 与 `-am`。Mapper SQL 契约验证不替代 MySQL 行锁、事务与并发验证。

1. **后端持久化与客户端单测**：
   - 运行 `SysUserMapper`、`ServiceAccountTokenMapper`、`PlatformUserOperationMapper`、`DatasourceAccessPermissionMapper` 的内存 DuckDB（`jdbc:duckdb:`）SQL 契约单测；
   - 运行 `DolphinSchedulerService` 与 `DolphinOpenApiClient` 单元测试，验证多 Dolphin 环境切换通过显式 `DolphinConfig` 参数传递且无 `ThreadLocal` 残留；
   - 运行 `mvn dependency:tree`，确认 `spring-boot-starter-webflux` 与 `reactor-netty` 已移除。
2. **前端构建与单测验证**：
   - 执行 `nvm use` 后运行 `npm --prefix frontend run test:unit` 与 `npm --prefix frontend run build`，确认零编译与引用错误。

---

3. **本地外部链路回归**：按主计划统一环境前检与临时资源规则，验证权限中心成员/角色变更与回读、Token 签发/吊销、Dolphin 多环境选择及工作流实例监控；数据库持久化与实际业务结果均须核对。缺少环境时明确登记，不以单测替代。

---

## 4. 上线与回滚（Rollout & Backout）

1. **上线**：随主收敛计划在同一交付周期内按“Task 1（Mapper 收口）→ Task 3（死代码清理）→ 主计划 Boot 3 升级 → Task 2（Dolphin 显式目标与 `RestClient`）”顺序推进并验收。
2. **回滚**：若出现异常，整体回退至上一版前后端构建产物与配置。
