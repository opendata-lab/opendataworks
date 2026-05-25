# 数据中台权限管理系统实施计划

> Design: [数据中台权限管理系统设计](../design/2025-12-12-data-platform-rbac-design.md)

**Goal:** 将现有的数据平台改造为支持用户权限管理的系统，通过规范化Doris用户方案实现细粒度的数据访问控制。
**Tech Stack:** Java 8, Spring Boot 2.7, MyBatis-Plus, MySQL, Vue 3, Element Plus

## Architecture Summary

使用 AOP 切面结合 ThreadLocal 统一处理用户身份，无侵入式地在 DorisConnectionService 中获取当前用户并映射到对应的 Doris 账号凭据，由 Doris 原生权限控制数据访问范围。

---

## Task 1: 建立用户权限基础架构

**Files:**
- `backend/src/main/resources/db/migration/V3__add_rbac_tables.sql`
- `backend/src/main/java/com/opendataworks/backend/model/PlatformUser.java`
- `backend/src/main/java/com/opendataworks/backend/model/DorisDbUser.java`
- `backend/src/main/java/com/opendataworks/backend/model/UserDatabasePermission.java`
- `backend/src/main/java/com/opendataworks/backend/mapper/PlatformUserMapper.java`
- `backend/src/main/java/com/opendataworks/backend/mapper/DorisDbUserMapper.java`
- `backend/src/main/java/com/opendataworks/backend/mapper/UserDatabasePermissionMapper.java`

**Steps:**
1. 编写 Flyway 迁移脚本，创建 `platform_users`, `doris_database_users`, `user_database_permissions` 三个数据表。
2. 实现对应的 MyBatis-Plus 实体类 (Entity) 和 Mapper 接口。
3. 编写用户映射和权限定义的基础服务接口。

**Expected Result:**
- 数据库结构更新成功。
- 基础持久化层（Entity / Mapper）正常工作，能对相关表进行增删改查。

---

## Task 2: 实现权限服务核心功能

**Files:**
- `backend/src/main/java/com/opendataworks/backend/service/UserMappingService.java`
- `backend/src/main/java/com/opendataworks/backend/service/PermissionManagementService.java`
- `backend/src/main/java/com/opendataworks/backend/controller/PermissionManagementController.java`
- `backend/src/test/java/com/opendataworks/backend/service/UserMappingServiceTest.java`
- `backend/src/test/java/com/opendataworks/backend/service/PermissionManagementServiceTest.java`

**Steps:**
1. 实现 `UserMappingService`，用于根据用户 ID 和数据库名获取 Doris 用户凭据 (readonly / readwrite)。
2. 实现 `PermissionManagementService`，用于管理和分配平台用户到数据库权限映射。
3. 实现管理员权限分配与查询的 REST API 控制器。
4. 编写 `UserMappingServiceTest` 和 `PermissionManagementServiceTest` 进行单元测试与属性测试（如验证用户权限正确映射到 Doris 用户凭据）。

**Expected Result:**
- 能够正确通过 API 为平台用户分配和撤销数据库只读/读写权限。
- 核心功能单元测试及属性测试通过。

---

## Task 3: 实现统一的用户上下文管理

**Files:**
- `backend/src/main/java/com/opendataworks/backend/context/UserContext.java`
- `backend/src/main/java/com/opendataworks/backend/context/UserContextHolder.java`
- `backend/src/main/java/com/opendataworks/backend/annotation/RequireAuth.java`
- `backend/src/main/java/com/opendataworks/backend/aspect/AuthenticationAspect.java`
- `backend/src/main/java/com/opendataworks/backend/service/DorisConnectionService.java`
- `backend/src/test/java/com/opendataworks/backend/context/UserContextHolderTest.java`

**Steps:**
1. 创建 `UserContext` 和 `UserContextHolder` (使用 ThreadLocal)。
2. 定义 `@RequireAuth` 注解，标识需要获取用户身份的 API 方法。
3. 编写 `AuthenticationAspect` AOP 切面，拦截带有 `@RequireAuth` 注解的方法，自动从 HTTP Header (如 `X-User-Id`, `X-Username`) 提取并设置用户上下文，并在请求结束时自动清理上下文以防止内存泄漏。
4. 改造 `DorisConnectionService.getConnection()`，使其从 `UserContextHolder` 获取当前用户，并通过 `UserMappingService` 解析得到其对应的 Doris 账号凭据建立连接。
5. 编写切面及上下文管理相关的测试，验证用户上下文在切面中正确设置与清理。

**Expected Result:**
- 带有注解的方法能自动解析出请求头中的用户信息并缓存于 ThreadLocal。
- Doris 数据库连接时自动切换为该用户有权使用的 Doris standard 凭据。

---

## Task 4: 应用用户身份切面到控制器

**Files:**
- `backend/src/main/java/com/opendataworks/backend/controller/DorisClusterController.java`
- `backend/src/main/java/com/opendataworks/backend/controller/DataTableController.java`
- `backend/src/main/java/com/opendataworks/backend/controller/DataQueryController.java`
- `backend/src/main/java/com/opendataworks/backend/controller/DataTaskController.java`

**Steps:**
1. 在 `DorisClusterController.listDatabases` 和 `listTables` 上添加 `@RequireAuth` 注解。
2. 在 `DataTableController` 统计信息、DDL 查看、数据预览等方法上添加 `@RequireAuth` 注解。
3. 在 `DataQueryController.executeQuery` 上添加 `@RequireAuth` 注解，并在查询历史查看方法中添加按当前用户 ID 过滤的逻辑。
4. 在 `DataTaskController` 任务方法上添加 `@RequireAuth` 注解，工作流列表添加基于 owner 的过滤逻辑。

**Expected Result:**
- API 接口均受到权限注解拦截并应用了对应的身份解析。
- 查询历史和工作流列表实现了个人数据隔离。

---

## Task 5: 实现权限管理前端界面

**Files:**
- `frontend/src/views/permissions/PermissionStudio.vue`
- `frontend/src/router/index.js`
- `frontend/src/api/permissions.js`

**Steps:**
1. 在前端开发用户列表和数据库权限分配界面，提供分配（readonly/readwrite）与撤回按钮。
2. 添加 Doris 数据库用户配置管理界面。
3. 在前端请求拦截器中自动处理 API 权限不足时的提示。

**Expected Result:**
- 权限管理控制台能正常调用后端 API 赋予/回收用户权限。
- 未授权页面或数据预览能够友好提示“权限不足”。

---

## Task 6: 系统集成与测试

**Files:**
- `backend/src/test/java/com/opendataworks/backend/integration/RbacIntegrationTest.java`

**Steps:**
1. 编写端到端集成测试，测试从用户登录、分配权限，到通过 AOP 切面鉴权并使用特定凭据查询 Doris 的全流程。
2. 进行 10 线程以上的并发安全及内存泄漏测试。

**Expected Result:**
- 全链条集成测试顺利通过。
- 并发环境下 ThreadLocal 读写正常，无数据混淆与泄漏。

---

## Task 7: 最终检查点

**Steps:**
1. 确保所有单元测试、集成测试在本地编译构建时（`mvn clean test`）均全部通过。

---

## Rollout / Backout

### Rollout (发布上线)
1. 部署后端代码，运行 Flyway 脚本创建新表。
2. 运行 Doris 用户创建脚本，为需要的数据库创建 `readonly` 和 `readwrite` Doris 账号。
3. 在 `doris_database_users` 表中插入每个库的标准凭据。
4. 部署前端静态资源，灰度开启带有 `X-User-Id` 的请求头路由网关。

### Backout (回滚方案)
1. 如遇稳定性问题，可回滚后端服务代码。
2. 数据库配置保留，不影响旧的免认证或全局管理员逻辑运行。
