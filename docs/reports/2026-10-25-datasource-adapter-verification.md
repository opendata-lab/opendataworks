# 数据源适配器阶段一、二验证

日期：2026-10-25。工作区：`/Users/guoruping/project/bigdata/opendataworks-datasource-adapter`，分支 `feat/datasource-adapter`。

设计：[数据源适配器架构](../design/2026-10-22-datasource-adapter-design.md)；计划：[实施计划](../plans/2026-10-22-datasource-adapter-plan.md)；后续：[TODO](../TODO.md)。未 commit、未 push。

## 实施范围

- 在 backend 原 Maven 模块内增加 `service/datasource/{model,spi,core,driver,adapters}`。Spring 自动发现字符串类型，拒绝重复和未知类型；规范化连接坐标、catalog/schema、表、字段及可空统计；可选 DDL reader 与既有编辑 handler 的声明在启动时校验。
- Doris/MySQL/PostgreSQL 的 URL、会话初始化、目录/表/列读取、标识符引用、默认 SELECT 和完整 DDL 读取通过适配器分派。`DorisConnectionService` 保留兼容门面、原用户映射、连接关闭与执行治理；同步差异、版本和历史仍由原服务编排。适配器过滤系统 namespace，未知统计继续保持 NULL。
- 新增 `/api/v1/datasource-types`；旧数据源配置列表/详情附只读 `typeDefinition`，不返回密码。新增 `/api/v1/doris-clusters/{id}/default-query`；目录表结果附后端生成的 `defaultQuery`。旧 API 路径不变，无 Flyway 迁移。
- 数据源管理和 Data Studio 消费描述：类型、端口、连接字段/必填、namespace 名称、编辑器方言、默认 SQL、DDL/结构编辑/分区/备份/新鲜度/主键限制。保留 Element Plus 与 Sass 界面；缺失描述或不支持能力有禁用原因。连接测试返回 false 时不再误激活目录。恢复默认 SQL 不覆盖用户已保存的 SQL。
- 不实施阶段三、四；未修改 `backend-agent-api`、`AgentJdbcExecutor`、`BackendAgentMetadataService`、`BackendAgentQueryService` 或 `dataagent/`。既有物理 handler 和 DataAgent 行为不作扩展。

## 驱动决定与文档

先修改设计驱动章节、计划阶段一第 8/9 项、阶段二第 6 项、发布/回退和 TODO，再实施代码。

内置驱动为主：保留现有 pom 的 MySQL/PG 驱动，Doris 继续使用内置 MySQL 协议驱动。升级无需驱动目录、driverId 绑定或配置迁移，平台 MySQL/Flyway 启动布局不变。

本轮只实现 driverClass 声明、JDBC Driver 类检查、AVAILABLE / DRIVER_NOT_INSTALLED / INVALID_DRIVER_CLASS 状态及执行前检查。完整可选外置目录、manifest、独立 ClassLoader、Java/依赖/校验和检查、版本绑定和选择器记入 TODO；本轮未实现挂载 JAR、上传、下载或在线切换。

## 环境与命令

- Java 8 `1.8.0_271`，Spring Boot 2.7.18；未新增运行依赖或 Maven 子模块。
- 全量后端测试使用既有 Podman MySQL `127.0.0.1:3306/opendataworks`，沿用仓库测试配置；没有重启既有数据库/服务。
- HTTP 冒烟使用独立 schema/账号 `odw_adapter_smoke`，在上述 MySQL 上运行现有全部 Flyway 迁移；独立 Java 进程监听 `127.0.0.1:18085`。为查询历史提供真实用户上下文，本地实例开启认证模块的 anonymous 模式，用户 ID 1；没有更改仓库认证配置。
- PostgreSQL 16 使用本次新建 Podman 容器 `odw-pg-adapter`，`127.0.0.1:55440`，数据库 `odw_smoke`。启动后在容器内创建测试库并配置既有集成测试所用的测试账号口令。
- 前端 `.nvmrc`：Node 20.19.0；复用既有 node_modules 符号链接，未 install。浏览器检查使用 Chrome + Playwright CLI、前端本地开发/生产预览 `127.0.0.1:3006`，代理到独立后端。Vite 临时配置将缓存放在本 worktree 的 `frontend/.vite/adapter`。
- 不涉及 Python、Redis、模型凭据或真实模型调用。

```sh
podman run -d --name odw-pg-adapter -e POSTGRES_PASSWORD=postgres \
  -p 55440:5432 docker.io/library/postgres:16-alpine

ODW_POSTGRES_TEST_PORT=55440 mvn -s .adapter-settings.xml -o -q -pl backend -am \
  -Dtest=DatasourceAdapterContractTest,PostgresqlDatasourceIntegrationTest,DorisClusterServiceTest,DorisConnectionServiceTest,DorisConnectionServicePartitionsTest,DataTableServiceTest,TableFreshnessServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

ODW_POSTGRES_TEST_PORT=55440 mvn -s .adapter-settings.xml -o -q -pl backend -am test

export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && nvm use
npm --prefix frontend test -- --cache=false
npm --prefix frontend run build
```

本机直接运行 `mvn -o -q -pl backend -am test` 在测试前因旧 `nexus-aliyun` 镜像与本地缓存仓库 ID 不匹配而失败。实际测试使用本 worktree 的临时 `.adapter-settings.xml`（完成后删除），将 `*` mirror 映射到 ID 为 `central` 的 `https://repo.maven.apache.org/maven2`；仍为 offline，不修改用户 Maven 配置或 pom。可用配置如下：

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors><mirror><id>central</id><mirrorOf>*</mirrorOf>
    <url>https://repo.maven.apache.org/maven2</url>
  </mirror></mirrors>
</settings>
```

## 测试结果

| 范围 | Tests run | 通过 | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| backend 清理后全量 | 598 | 589 | 7 | 0 | 2 |
| odw-auth（同一 reactor） | 31 | 31 | 0 | 0 | 0 |
| backend-agent-api（同一 reactor） | 3 | 3 | 0 | 0 | 0 |
| reactor 合计 | 632 | 623 | 7 | 0 | 2 |
| 阶段一、二原定向测试，7 个类 | 42 | 42 | 0 | 0 | 0 |
| 清理后的描述契约定向测试 | 8 | 8 | 0 | 0 | 0 |
| 前端上一轮全量，58 个文件 | 417 | 417 | 0 | 0 | 0 |

定向测试是全量的子集，不重复计入 reactor 合计。前端最终生产构建成功；只有 Sass legacy API 弃用和已有的大 chunk 提示。`git diff --check` 通过。

初轮新增 `DatasourceAdapterContractTest` 6 项（本轮清理后为 8 项，详见下文），覆盖三个内置描述/URL/引用/能力、规范化元数据及未知统计、重复/未知类型、驱动缺失/错误类、初始化失败关闭连接，以及 TEST_DB 的类型 API → 保存配置 → 连接/元数据分派。TEST_DB 仅增加测试适配器私有实现，无需修改枚举、通用服务或生产前端。前端注入 TEST_DB 描述后能生成选项、端口、规则并提交，未知方言默认 SQL 完全由后端提供。

`PostgresqlDatasourceIntegrationTest` 真实数据库 **8/8** 通过：database/schema 上下文、映射凭据、注释/主键/精度/数组/默认值、未知统计、分区父表、INCLUDE 主键、受限账号、预览与双引号转义、同名表跨 schema 隔离、失败连接。既有 Agent 测试方法随该类执行，但未改 Agent 实现，也不代表阶段四已实施。

全量中的凭据拒绝不回退（`DorisConnectionServiceUserContextTest` 5 项）、同步编排（`DorisMetadataSyncServiceTest` 12 项）、查询（`DataQueryServiceTest` 6 项）及新鲜度限制（`TableFreshnessServiceTest` 12 项）均通过。

7 个失败全部是既有 `WorkflowRuntimeSyncRealIntegrationTest`，均在 `bootstrapOrSkip:2131` 因 `http://127.0.0.1:12345/dolphinscheduler` 不可达失败，尚未进入业务场景；没有新增失败：

1. `forwardReverseSyncAndDiffShouldWork`
2. `importPreviewCommitAndExportShouldWorkWithRealDolphinExport`
3. `publishPreviewShouldOnlyShowChangedTasksForExistingRuntimeWorkflow`
4. `relationMismatchShouldRequireDecisionAndConvergeAfterManualSelection`
5. `reversePreviewShouldFailForUnsupportedNodeType`
6. `reversePreviewShouldPassForUpdateHeadWithoutInput`
7. `runtimeEvolutionShouldRefreshTasksLineageAndDataResult`

2 项跳过为 `RealDolphinSchedulerIntegrationTest.testConnectivity`、`testListProcessInstances`。

## 真实 HTTP 与持久化

PostgreSQL 实际 HTTP 的 **16 项检查通过**：三种内置描述/驱动可用；保存配置不回传密码；连接成功；编辑保留未传的 database/password；schema 列表；表目录附描述与带双引号的默认 SQL；默认 SQL 接口一致；元数据同步 SUCCESS；字段精度/主键持久化；真实数据预览；DDL 提前拒绝并带原因；DDL 拒绝后基本详情仍可读取；analyze；execute 限行及 hasMore；当前用户查询历史；同步历史 SUCCESS。

另用真实 MySQL 数据验证：MYSQL 保存配置、连接、namespace、表/列和主键读取、实际 SQL 执行均通过。未以 mock store 代替上述链路。

首轮关闭整个认证模块时，查询历史已落库但历史 API 列表为空；检查确认是既有 `executed_by` 为 NULL、列表使用等号筛选造成。改用本地 anonymous 用户上下文后，同一历史链路通过；未修改认证或查询历史实现，该问题已记 TODO。

## 浏览器覆盖与限制

Chrome 实际打开数据源管理页：MySQL/PostgreSQL 标签与连接信息正确；新增表单由描述生成；三种选项可用；默认 Doris 端口 9030；切换 PostgreSQL 后端口 5432，连接数据库成为必填并显示 schema 提示。截图位于本地 `output/playwright/datasource-adapter-postgresql-form.png`。

Data Studio 已打开并激活真实数据源目录，恢复 PostgreSQL 表页签，显示注释、行数、大小和未知时间。后续检查与生产构建重叠，构建替换 preview 的 dist 后，旧页面的动态 chunk 出现 404（含 SQL 编辑器），快照也有超时；这轮浏览器 SQL 编辑器/字段/DDL/结果操作不计为通过。完整浏览器闭环仍需在固定构建下补跑，已记 TODO；不能据组件回归和 HTTP 覆盖宣称浏览器全流程通过。

未验证或未实施：

- 无可用真实 Doris 服务，未跑真实 Doris 同步、DDL、分区、备份与新鲜度执行；已有契约和回归测试通过，真实环境回归记 TODO。
- 未做 PostgreSQL 风险确认、取消、超时的真实 HTTP 故障场景；此前 PostgreSQL 后续项继续保留。
- 外置驱动加载及双版本隔离尚未实施，不宣称支持外置驱动运行。
- 阶段三新物理 handler、阶段四 DataAgent 统一/真实模型链路不在本轮范围。

## 清理与后续

已删除本次 PostgreSQL 容器、独立 MySQL schema/账号，停止本次独立后端与前端预览/开发进程，关闭本次 Playwright 会话；不停止既有 MySQL/Redis 或其他服务。保留正常 target/dist 构建输出和本地截图。临时 `.adapter-*` 脚本、配置和日志清理；所有交付改动留在本 worktree，未提交、未推送。

TODO 更新：架构状态为实施中、阶段一/二已落地，驱动选择从待定改为已决定；新增完整可选外置加载、Doris/Dolphin 环境验证补齐、认证关闭时查询历史问题；阶段三/四继续独立实施。

## 代码风格复核清理（2026-10-25）

本轮仅整理 `service/datasource/` 的 21 个 Java 文件、`DatasourceTypeController`、`DatasourceAdapterContractTest` 和 `DatasourceAdapterFixtures`：4 空格缩进、逐条语句、显式 import、控制语句大括号、接口实现方法的 `@Override`，model 字段逐行声明。保留连接/SQL/元数据/凭据逻辑和既有 JSON 契约，未修改前端、平台 schema 或运行依赖。

`Descriptors.create` 的 10 个位置参数改为具名 builder，三个内置适配器逐项声明能力与原因，例如 PostgreSQL：

```java
Descriptors.builder("POSTGRESQL", "PostgreSQL")
        .defaultPort(5432)
        .namespaceLabel("Schema")
        .identifierQuote("\"")
        .editorDialect("postgresql")
        .driverClass("org.postgresql.Driver")
        .connectionDatabaseRequired(true)
        .capability("ddlExport", false, "PostgreSQL 暂不支持在线 DDL 导出，请使用 pg_dump --schema-only")
        // 其余能力同样逐项声明，见 PostgresqlAdapter.descriptor()。
        .build();
```

共享 builder 的默认拒绝原因是中性文案；`pg_dump --schema-only` 仅由 PostgreSQL 适配器声明，三个内置类型原有字段、默认值、能力及提示均保留。

新增 `backend/src/test/resources/datasource-descriptors.json`，由清理前版本源码独立编译后导出 UTF-8 JSON，锁定全部公开描述（含 driverStatus）；契约测试逐项比较，另验证共享拒绝原因不假定 PostgreSQL。`DatasourceAdapterContractTest` 从 6 项增至 **8 项，全部通过**。

设计第 5 节和 TODO 已记录第一阶段限制：连接 key 仍固定为 `feHost`、`fePort`、`databaseName`、`username`、`password`，其他 key 仍直接拒绝。Oracle service name、额外 JDBC 参数等需要后续连同存储、校验和前端表单一起设计，本轮未放宽白名单。

本轮复跑命令：

```sh
mvn -s .adapter-style-tmp/settings.xml -o -q -pl backend -am \
  -Dtest=DatasourceAdapterContractTest -Dsurefire.failIfNoSpecifiedTests=false test
ODW_POSTGRES_TEST_PORT=55440 mvn -s .adapter-style-tmp/settings.xml -o -q -pl backend -am test
```

临时 settings 使用与上一轮相同的 central mirror；新增临时 PostgreSQL 16 容器 `odw-pg-style-cleanup`，仅绑定 `127.0.0.1:55440`，数据库 `odw_smoke`，用于复跑既有 8 项集成测试。MySQL 沿用 `127.0.0.1:3306/opendataworks`。前端文件与本轮开始时逐字节一致，按复核要求未重跑前端；上文 417 项与构建为上一轮结果。

本轮最终全量：**backend Tests run: 598, Failures: 7, Errors: 0, Skipped: 2（589 通过）**；同一 reactor 的 odw-auth 31 项、backend-agent-api 3 项均通过，合计 632 项、623 通过、7 失败、2 跳过。与上一轮相比只增加上述 2 项描述测试；7 个失败的类、方法名和 `Dolphin 不可达` 原因与上文清单逐一一致，无新增失败。真实 PostgreSQL 集成测试 **8/8**、描述契约 **8/8** 通过。

已检查 24 个指定 Java 文件的缩进、显式 import 和大括号，`git diff --check` 通过；本轮共改动 24 个 Java 文件、1 个 JSON 基线和设计/TODO/报告 3 个文档。临时 settings、脚本与 PostgreSQL 容器在验证结束后删除；不 commit、不 push。

## 改动文件

以下清单不包含临时脚本、日志与构建输出。

- `backend/src/main/java/com/onedata/portal/controller/DatasourceTypeController.java`
- `backend/src/main/java/com/onedata/portal/controller/DorisClusterController.java`
- `backend/src/main/java/com/onedata/portal/entity/DorisCluster.java`
- `backend/src/main/java/com/onedata/portal/service/DataTableService.java`
- `backend/src/main/java/com/onedata/portal/service/DorisClusterService.java`
- `backend/src/main/java/com/onedata/portal/service/DorisConnectionService.java`
- `backend/src/main/java/com/onedata/portal/service/DorisMetadataSyncService.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/adapters/Descriptors.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/adapters/JdbcMetadataReader.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/adapters/QuotedLimitDialect.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/adapters/doris/DorisAdapter.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/adapters/mysql/MysqlAdapter.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/adapters/mysql/MysqlProtocolAdapter.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/adapters/postgresql/PostgresqlAdapter.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/core/DatasourceAdapterRegistry.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/core/DatasourceCapabilityValidator.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/driver/JdbcDriverRegistry.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/model/ColumnMetadata.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/model/ConnectionConfig.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/model/DatasourceDescriptor.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/model/DatasourceTypeDefinition.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/model/JdbcConnectionSpec.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/model/Namespace.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/model/TableMetadata.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/spi/DatasourceAdapter.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/spi/DatasourceDdlReader.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/spi/DatasourceMetadataReader.java`
- `backend/src/main/java/com/onedata/portal/service/datasource/spi/DatasourceSqlDialect.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/TableFreshnessService.java`
- `backend/src/test/java/com/onedata/portal/service/DataTableServiceTest.java`
- `backend/src/test/java/com/onedata/portal/service/DatasourceAdapterContractTest.java`
- `backend/src/test/java/com/onedata/portal/service/DatasourceAdapterFixtures.java`
- `backend/src/test/java/com/onedata/portal/service/DorisClusterServiceTest.java`
- `backend/src/test/java/com/onedata/portal/service/DorisConnectionServicePartitionsTest.java`
- `backend/src/test/java/com/onedata/portal/service/DorisConnectionServiceTest.java`
- `backend/src/test/java/com/onedata/portal/service/PostgresqlDatasourceIntegrationTest.java`
- `backend/src/test/java/com/onedata/portal/service/freshness/TableFreshnessServiceTest.java`
- `backend/src/test/resources/datasource-descriptors.json`
- `docs/TODO.md`
- `docs/design/2026-10-22-datasource-adapter-design.md`
- `docs/plans/2026-10-22-datasource-adapter-plan.md`
- `docs/reports/2026-10-25-datasource-adapter-verification.md`
- `frontend/src/api/doris.js`
- `frontend/src/components/SqlEditor.vue`
- `frontend/src/utils/__tests__/datasourceFixtures.js`
- `frontend/src/utils/datasourceTypes.js`
- `frontend/src/views/datastudio/DataStudioNew.vue`
- `frontend/src/views/datastudio/__tests__/DataStudioNew.smoke.spec.js`
- `frontend/src/views/datastudio/__tests__/fieldEdit.spec.js`
- `frontend/src/views/datastudio/__tests__/primaryKeyControls.spec.js`
- `frontend/src/views/datastudio/__tests__/useCatalogTree.spec.js`
- `frontend/src/views/datastudio/__tests__/useQueryExecution.spec.js`
- `frontend/src/views/datastudio/__tests__/useStudioTabs.spec.js`
- `frontend/src/views/datastudio/__tests__/useTableActions.spec.js`
- `frontend/src/views/datastudio/__tests__/useTableMetaEditing.spec.js`
- `frontend/src/views/datastudio/components/DataStudioCatalogNode.vue`
- `frontend/src/views/datastudio/components/DataStudioQueryPanel.vue`
- `frontend/src/views/datastudio/components/DataStudioRightPanel.vue`
- `frontend/src/views/datastudio/components/DataStudioRightPanelColumns.vue`
- `frontend/src/views/datastudio/components/DataStudioRightPanelFreshness.vue`
- `frontend/src/views/datastudio/composables/useCatalogTree.js`
- `frontend/src/views/datastudio/composables/useQueryExecution.js`
- `frontend/src/views/datastudio/composables/useStudioTabs.js`
- `frontend/src/views/datastudio/composables/useTableActions.js`
- `frontend/src/views/datastudio/composables/useTableMetaEditing.js`
- `frontend/src/views/datastudio/fieldEdit.js`
- `frontend/src/views/settings/DataSourceManagement.vue`
- `frontend/src/views/settings/__tests__/DataSourceManagement.spec.js`
- `frontend/src/views/settings/components/SchemaBackupManager.vue`


## 2026-10-26：阶段三、Java 阶段四与连接字段扩展

本节承接 `cfa12bdd` 的阶段一、二，不改 `dataagent/` Python，不实现外置驱动加载；改动保留在 feat/datasource-adapter 工作区，未 commit、未 push。

### 实施与取舍

1. **连接主路径**：`DatasourceConnectionFactory` 统一注册表配置校验、内置驱动检查、适配器规格、JDBC 打开和会话初始化，失败关闭连接。`DorisConnectionService` 保留用户凭据映射；`TableEngineJdbcExecutor` 通过该门面取连接，Java `AgentJdbcExecutor` 用已授权的 resolution 凭据调用工厂。URL 构造仅留在适配器，继续使用 JdbcUrlParts；PG URL 固定 readOnlyMode=always。agent 所有引擎调用 setReadOnly(true)，PG setSchema、maxRows(limit + 1)、timeout、SQL 只读守卫与数据范围检查保留。登记映射账号但用户未授权时继续直接拒绝。
2. **参考结构**：PG 列参考 DDL 迁入 PostgreSQL 适配器的 `DatasourceReferenceDdlReader`；MySQL/Doris 复用 SHOW CREATE TABLE。参考结构与可恢复导出分开，PG ddlExport 仍为 false，pg_dump 提示不变。描述新增 agentEngine，替换 Java metadata 服务中的引擎映射与默认端口分支。
3. **编辑类型**：handler 与 registry 使用统一 normalize 字符串，删除 util/DatasourceType 枚举；启动校验未知/重复 handler 与结构编辑能力。现有 Doris/MySQL 物理 SQL 和主键限制保留，TEST_DB + handler 契约证明无需中央枚举。Doris 专用表设计器仍使用自己的物理模型与创建 DDL，普通表结构编辑通过通用 DataTableService 分派。
4. **能力目录**：后端 `DatasourceCapabilities` 定义 18 项能力的名称、含义与默认拒绝原因；不可变描述补全缺省能力，注册表启动拒绝未知项。dorisProperties 同步改为 engineProperties，前端辅助函数改为 hasEngineProperties。备份使用 backup，元数据物理属性使用 engineProperties；审计使用明确的 auditAccess，Tablet 巡检使用 tabletInspection。DolphinScheduler 的 DataX 默认 MYSQL 保留：它属于 Dolphin 自己的数据源协议，不是平台数据源分派。
5. **连接选项**：`V53__add_datasource_connection_options.sql` 新增 doris_cluster.connection_options 可空 TEXT（JSON 对象，JacksonTypeHandler）。REST 旧接口增加可选 connectionOptions，旧记录 NULL 等同空对象。只有描述声明的额外 key 可保存/取连接，校验控件类型、必填、格式与长度，拒绝 URL/驱动/只读等保留键以及与顶层配置冲突的键；值不会被通用工厂拼成 JDBC 参数。额外密码控件只写、响应脱敏，更新缺省/留空保留秘密值。前端按描述生成字段，将额外值放入 connectionOptions。三个内置源不新增字段，真实 mapper 的 TEST_DB 测试覆盖描述→保存→重载→连接规格与会话初始化。
6. **对象复用**：三个生产适配器在构造时创建描述、方言和元数据读取器，后续返回同一实例。

### 环境、命令与测试结果

- Java 1.8.0_271 / Spring Boot 2.7.18；临时 Maven settings 使用 id=central、mirrorOf=*，匹配离线缓存，不修改全局 settings 或 pom。
- MySQL：复用 Podman data-portal-mysql（127.0.0.1:3306），创建独立用户与 schema `odw_adapter_20261026`、`odw_adapter_upgrade_src`、`odw_adapter_upgrade_copy`、`odw_adapter_business`。测试显式覆盖 spring.datasource URL/用户名/密码；未修改既有 opendataworks 数据，也未在其上运行 V53。
- PostgreSQL：一次性 Podman `odw-pg-adapter-final`，postgres:16-alpine，127.0.0.1:55443，数据库 odw_smoke；由 ODW_POSTGRES_TEST_PORT=55443 开启真实集成测试。
- 定向：DatasourceAdapterContractTest **11/11**，DatasourceConnectionOptionsIntegrationTest **1/1**，PostgresqlDatasourceIntegrationTest **8/8**，合计 **20 通过、0 失败、0 错误、0 跳过**。扩展选项测试真实落库但 JDBC TEST_DB 用测试驱动；PG 集成使用真实驱动/服务器，包含只读多语句写拒绝、schema、maxRows 和参考结构。
最终全量中重点类全部通过：

| 测试类 | 通过数 |
| --- | ---: |
| BackendAgentQueryServiceTest | 31 |
| BackendAgentMetadataServiceTest | 13 |
| DatasourceAdapterContractTest | 11 |
| DatasourceConnectionOptionsIntegrationTest | 1 |
| PostgresqlDatasourceIntegrationTest | 8 |
| TableEngineJdbcExecutorUrlTest | 1 |
| MysqlTableEngineHandlerTest | 5 |
| DorisTableEngineHandlerTest | 7 |
| UserMappingServiceTest | 6 |
| DorisConnectionServiceUserContextTest | 5 |
| 合计 | 88 |

- 最终全量：**backend Tests run: 602, Failures: 7, Errors: 0, Skipped: 2（593 通过）**。odw-auth **31/31**、backend-agent-api **3/3**，reactor 合计 **636 项、627 通过、7 失败、0 错误、2 跳过**。
- 与前轮 7 个失败逐项比对完全一致，无新增失败。均为 WorkflowRuntimeSyncRealIntegrationTest 的 bootstrapOrSkip:2131 在等待 DolphinScheduler `http://localhost:12345/dolphinscheduler` 后失败（端口检查 HTTP=000）：
  - forwardReverseSyncAndDiffShouldWork
  - importPreviewCommitAndExportShouldWorkWithRealDolphinExport
  - publishPreviewShouldOnlyShowChangedTasksForExistingRuntimeWorkflow
  - relationMismatchShouldRequireDecisionAndConvergeAfterManualSelection
  - reversePreviewShouldFailForUnsupportedNodeType
  - reversePreviewShouldPassForUpdateHeadWithoutInput
  - runtimeEvolutionShouldRefreshTasksLineageAndDataResult
- 2 个跳过仍为 RealDolphinSchedulerIntegrationTest.testConnectivity、testListProcessInstances。没有跳过本轮 PG 或连接选项测试。Maven 全量因上述既有 7 项返回退出码 1。
- 过程中的测试修正：动态表单变量改名遗漏已修复后重跑前端全量；额外选项更新测试改为模拟真实 HTTP 的独立请求对象，避免原地修改 MyBatis 会话缓存中的实体。修正后的完整 Java 全量如上，不将中途失败计入最终通过数。

全量命令（测试密码仅供临时独立用户，报告省略）：

```sh
ODW_POSTGRES_TEST_PORT=55443 \
IT_MYSQL_DATABASE=odw_adapter_20261026 \
IT_MYSQL_USERNAME=odw_adapter_20261026 IT_MYSQL_PASSWORD=<临时测试密码> \
mvn -s <临时 central mirror settings> -o -q -pl backend -am test \
  '-Dspring.datasource.url=jdbc:mysql://127.0.0.1:3306/odw_adapter_20261026?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' \
  -Dspring.datasource.username=odw_adapter_20261026 \
  -Dspring.datasource.password=<临时测试密码>
```

前端每次先 `export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && nvm use`（Node 20.19.0）：

- `npm --prefix frontend test -- --cache=false`：**59 文件、419 测试全部通过**；包含自动扫描前端能力引用并与后端基线 JSON 对照、额外字段保存/恢复测试。禁用 Vitest 缓存，未 install，保留 node_modules 符号链接。
- `npm --prefix frontend run build`：通过；沿用现有 Sass 弃用与 chunk 大小提示，无构建错误。

### Flyway 验证

- **从零**：空 schema odw_adapter_20261026 使用全部迁移，成功应用 **53 个迁移，最终 V53**。
- **已有数据副本升级**：独立 schema odw_adapter_upgrade_src 先迁移到 V52 并插入 upgrade-sentinel；使用 mysqldump 复制其结构、Flyway 历史及数据到 odw_adapter_upgrade_copy，再运行全部迁移，成功升级 V53。确认旧 password 哨兵值保持、新 connection_options 为 NULL。复制源和目标均为本轮新建 schema，没有复制或修改既有 opendataworks。
- 回退无需删除可空列；回退前后端应用并保留 V53，暂停旧版本不认识的新类型配置。能力重命名要求前后端协调发布；内置驱动与旧源字段保持。

### 真实 Java HTTP

隔离 Java backend 绑定 127.0.0.1:18086，平台库 odw_adapter_upgrade_copy；仅本地开启匿名 admin 上下文，agent 使用临时 service token 和 X-Agent-Data-Scope。MySQL 业务库 odw_adapter_business，PG schema adapter_http_sales。**12/12 项通过**：

| 引擎 | 测试连接、单表同步 | agent 只读查询（limit=1、has_more=true） | DELETE 拒绝 | outside_scope.secret 越界拒绝 | 修改表注释入口 |
| --- | --- | --- | --- | --- | --- |
| PostgreSQL | 通过 | 通过，真实 PG 行 | HTTP 400 | HTTP 400 | UNSUPPORTED_CAPABILITY（旧 API HTTP 200、业务 code=500），数据库注释未改变 |
| MySQL | 通过 | 通过，真实 MySQL 行 | HTTP 400 | HTTP 400 | 允许，数据库 TABLE_COMMENT=adapter-http-edited |

入口分别为 `/api/v1/ai/query/read` 与 `/api/v1/tables/{id}/comment`。MySQL 使用单独业务库，以覆盖注册数据源分派；平台元数据库有 clusterId=null 的独立 scope 语义，测试不绕过该限制。

### 文档、缺口与清理

- 先更新既有 design/plan 的阶段三、Java 阶段四、V53、接口/安全约束、能力目录、发布与回退，再修改代码。TODO 将已实施的架构与连接字段扩展移入已完成，保留外置加载、Python resolver、Doris/浏览器和环境验证缺口。
- 本轮没有真实模型 NL2SQL 全链路：模型账号欠费（既有 403 Current user is in debt），未改 Python；Java agent HTTP 通过不能代替真实模型/MCP/任务与事件持久化验证。
- 未新增生产数据库适配器，TEST_DB 是扩展契约测试。没有真实 Doris 实例，保留其真实物理操作回归；本轮未重跑完整 Data Studio 浏览器闭环。
- 清理：本轮 Java HTTP 服务已停止；临时 PG 容器 odw-pg-adapter-final 已删除；四个独立 MySQL schema 与专用用户已删除；执行脚本/settings/日志工作目录已清理。保留 Maven surefire 报告与本验证文档，其他原有容器、服务及既有 opendataworks 库保持。

### 本轮改动文件

```text
backend-agent-api/src/main/java/com/onedata/portal/agentapi/dto/AgentDatasourceResolution.java
backend/src/main/java/com/onedata/portal/agentapi/service/AgentJdbcExecutor.java
backend/src/main/java/com/onedata/portal/agentapi/service/BackendAgentMetadataService.java
backend/src/main/java/com/onedata/portal/entity/DorisCluster.java
backend/src/main/java/com/onedata/portal/service/DataTableService.java
backend/src/main/java/com/onedata/portal/service/DorisClusterService.java
backend/src/main/java/com/onedata/portal/service/DorisConnectionService.java
backend/src/main/java/com/onedata/portal/service/DorisMetadataSyncService.java
backend/src/main/java/com/onedata/portal/service/SchemaBackupService.java
backend/src/main/java/com/onedata/portal/service/TableCreateService.java
backend/src/main/java/com/onedata/portal/service/audit/DorisAuditAccessSyncService.java
backend/src/main/java/com/onedata/portal/service/datasource/adapters/Descriptors.java
backend/src/main/java/com/onedata/portal/service/datasource/adapters/doris/DorisAdapter.java
backend/src/main/java/com/onedata/portal/service/datasource/adapters/mysql/MysqlAdapter.java
backend/src/main/java/com/onedata/portal/service/datasource/adapters/mysql/MysqlProtocolAdapter.java
backend/src/main/java/com/onedata/portal/service/datasource/adapters/postgresql/PostgresqlAdapter.java
backend/src/main/java/com/onedata/portal/service/datasource/core/DatasourceAdapterRegistry.java
backend/src/main/java/com/onedata/portal/service/datasource/core/DatasourceCapabilities.java
backend/src/main/java/com/onedata/portal/service/datasource/core/DatasourceConnectionFactory.java
backend/src/main/java/com/onedata/portal/service/datasource/model/ConnectionConfig.java
backend/src/main/java/com/onedata/portal/service/datasource/model/DatasourceDescriptor.java
backend/src/main/java/com/onedata/portal/service/datasource/spi/DatasourceReferenceDdlReader.java
backend/src/main/java/com/onedata/portal/service/inspection/InspectionSupport.java
backend/src/main/java/com/onedata/portal/service/table/DorisTableEngineHandler.java
backend/src/main/java/com/onedata/portal/service/table/MysqlTableEngineHandler.java
backend/src/main/java/com/onedata/portal/service/table/TableEngineHandler.java
backend/src/main/java/com/onedata/portal/service/table/TableEngineHandlerRegistry.java
backend/src/main/java/com/onedata/portal/service/table/TableEngineJdbcExecutor.java
backend/src/main/java/com/onedata/portal/util/DatasourceType.java
backend/src/main/resources/db/migration/V53__add_datasource_connection_options.sql
backend/src/test/java/com/onedata/portal/agentapi/BackendAgentMetadataServiceTest.java
backend/src/test/java/com/onedata/portal/service/DataTableMetadataSyncServiceTest.java
backend/src/test/java/com/onedata/portal/service/DataTableServiceTest.java
backend/src/test/java/com/onedata/portal/service/DatasourceAdapterContractTest.java
backend/src/test/java/com/onedata/portal/service/DatasourceConnectionOptionsIntegrationTest.java
backend/src/test/java/com/onedata/portal/service/DorisMetadataSyncServiceTest.java
backend/src/test/java/com/onedata/portal/service/PostgresqlDatasourceIntegrationTest.java
backend/src/test/java/com/onedata/portal/service/SchemaBackupServiceTest.java
backend/src/test/java/com/onedata/portal/service/inspection/TableNamingRuleHandlerTest.java
backend/src/test/java/com/onedata/portal/service/inspection/TableOwnerRuleHandlerTest.java
backend/src/test/java/com/onedata/portal/service/table/MysqlTableEngineHandlerTest.java
backend/src/test/java/com/onedata/portal/service/table/TableEngineJdbcExecutorUrlTest.java
backend/src/test/resources/datasource-descriptors.json
docs/TODO.md
docs/design/2026-10-22-datasource-adapter-design.md
docs/plans/2026-10-22-datasource-adapter-plan.md
docs/reports/2026-10-25-datasource-adapter-verification.md
frontend/src/utils/__tests__/datasourceCapabilityContract.spec.js
frontend/src/utils/__tests__/datasourceFixtures.js
frontend/src/views/datastudio/DataStudioNew.vue
frontend/src/views/datastudio/__tests__/DataStudioNew.smoke.spec.js
frontend/src/views/datastudio/__tests__/DataStudioRightPanel.smoke.spec.js
frontend/src/views/datastudio/__tests__/primaryKeyControls.spec.js
frontend/src/views/datastudio/__tests__/useTableActions.spec.js
frontend/src/views/datastudio/__tests__/useTableMetaEditing.spec.js
frontend/src/views/datastudio/components/DataStudioRightPanel.vue
frontend/src/views/datastudio/components/DataStudioRightPanelColumns.vue
frontend/src/views/datastudio/composables/useTableActions.js
frontend/src/views/datastudio/composables/useTableMetaEditing.js
frontend/src/views/settings/DataSourceManagement.vue
frontend/src/views/settings/__tests__/DataSourceManagement.spec.js
```

## 2026-10-26 PostgreSQL / MySQL 浏览器端到端验证

在提交 `eec99afe` 之上，用真实数据库和浏览器走完"新增数据源 → 测试连接 → 元数据同步 → Data Studio 浏览与查询 → 查询历史"，并直接调用 Java agent 只读接口。Doris 留待内网环境验证。

### 环境

- 后端：本 worktree 打包的 jar，Java 1.8.0_271，端口 18086；平台库为本机 `data-portal-mysql`（MySQL 8，127.0.0.1:3306）上新建的 `odw_e2e_platform`，Flyway 从空库迁移到 V53；`AUTH_ANONYMOUS_ENABLED=true`（本地匿名 admin），`AGENT_API_SERVICE_TOKEN` 设为测试值。
- 前端：Vite 开发服务器（Node 20.19.0），代理到 18086；浏览器为 Playwright 驱动的 Chrome。
- PostgreSQL 16：临时容器，库 `shop`，`public.customer`（`BIGSERIAL` 主键、`TEXT[]`、`JSONB`、带单引号的数据）、`sales.orders`（按季度 RANGE 分区、联合主键、`NUMERIC(12,2)`）、`sales."MixedCase"`（大小写敏感标识符）、视图 `sales.v_daily`。
- MySQL 业务库：同一 MySQL 实例上的 `odw_e2e_biz`（`product`、联合主键的 `stock`、视图），连接账号只有 `SELECT, SHOW VIEW`。

### 结果

| 环节 | PostgreSQL | MySQL |
| --- | --- | --- |
| 新增表单 | 按描述生成，端口 5432，连接数据库必填 | 端口 3306，连接数据库可选 |
| 测试连接 | 成功 | 成功 |
| 首次同步 | SUCCESS，4 表 14 字段；分区子表不单列，父表行数为分区合计 200 | SUCCESS，3 表 11 字段 |
| 字段元数据 | 中文注释、联合主键、精度、数组、jsonb、默认值、大写字段名正确 | 注释、联合主键、默认值正确 |
| 比对后再同步 | — | 发现 3 处差异，确认后更新 2 表 8 字段 |
| 目录树 | public / sales，表与视图分组正确 | 只显示授权的业务库 |
| 默认查询 | 后端生成双引号 `"sales"."orders"`，200 行 | 反引号，3 行 |
| 自定义 SQL | 跨 schema JOIN + 聚合 + `->>` + `string_agg`，结果与 psql 一致；不带 schema 前缀查询 `"MixedCase"`，`current_schema()` 为 `sales` | JOIN + 聚合结果正确 |
| EXPLAIN | 返回计划 | — |
| 高风险语句 | — | DELETE 弹出强确认；确认后因账号无权限被数据库拒绝，错误就地显示 |
| DDL 页签 | 显示适配器声明的不支持原因（pg_dump 提示） | 显示建表语句 |
| 字段编辑入口 | 按能力禁用 | — |
| 查询历史 | 两个数据源的执行、EXPLAIN、失败语句均已记录 | 同左 |
| agent 只读查询 | 成功，`has_more` 正确；多语句被拒；`set_config('transaction_read_only','off')` 被服务端拒绝 | 成功；DELETE 被拒；会话 `transaction_read_only = 1` |
| agent 参考结构 | 返回字段与注释 | 返回字段与注释 |

### 本轮发现并修复

- **新建数据源的默认类型不固定**：前端取类型描述列表第一项，而注册表顺序取决于 Spring 注入顺序，本次实测默认变成了 PostgreSQL（改造前固定为 Doris）。适配器加 `@Order`（Doris、MySQL、PostgreSQL），注册表按此排序；新增契约测试以逆序传入验证顺序。重启后接口返回 `DORIS, MYSQL, POSTGRESQL`，表单默认 Doris / 9030。
- **切换类型时空字段立即标红**：必填规则随描述重算，Element Plus 默认在规则变化时校验。表单设为 `validate-on-rule-change=false`，浏览器复测切换后无错误提示。

验证：后端适配器、连接、结构编辑与 agent 相关测试 87 项通过；前端 59 个文件 419 项通过。

### 未覆盖

- Doris（用户在内网自行验证），包括 `DorisAdapter.applyReadOnly` 的后续决定。
- 经真实模型的 DataAgent NL2SQL（模型账号欠费）。
- 数据集成（DataX / DolphinScheduler 同步任务）：DolphinScheduler 本地不可用。
- 第一次灌入 MySQL 测试数据时客户端字符集不对，导致源数据本身是双重编码；用 utf8mb4 重灌后同步结果正确，这是测试数据问题，不是平台问题。
