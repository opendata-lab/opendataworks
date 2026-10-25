# PostgreSQL 数据源验证（2026-10-22）

设计：[PostgreSQL 数据源](../design/2026-10-22-postgresql-datasource-design.md)；计划：[实施计划](../plans/2026-10-22-postgresql-datasource-plan.md)；使用说明：[PostgreSQL 数据源](../handbook/features/postgresql-datasource.md)。

## 环境

- Java 8（本机 Maven 使用 1.8.0_271），Spring Boot 2.7.18，pgJDBC 42.7.14。
- PostgreSQL 16：使用 Podman 新建独立 `odw-pgsql-smoke` 容器，监听 `127.0.0.1:55439`，database `odw_smoke`。未复用其他正在运行的 PostgreSQL 容器。
- 平台 MySQL：现有 `data-portal-mysql`，`127.0.0.1:3306`；创建独立 schema/账号 `odw_pgsql_smoke`，全量 Flyway V1–V52 成功。
- HTTP 后端：本次编译产物及运行依赖启动独立 JVM，绑定 `127.0.0.1:18080`，测试实例开启匿名访问，仅使用测试 schema。原有 8080 服务未重启。
- 前端：通过仓库 `.nvmrc` 的 `nvm use`，Node 20.19.0。
- 不涉及 Python DataAgent、Redis、模型凭据或真实模型调用。

## 命令与结果

测试 PostgreSQL 在代码之外启动：

```sh
podman run -d --name odw-pgsql-smoke \
  -e POSTGRES_PASSWORD=odw_smoke_password -e POSTGRES_DB=odw_smoke \
  -p 127.0.0.1:55439:5432 docker.io/library/postgres:16-alpine
```

后端定向测试：

```sh
ODW_POSTGRES_TEST_PORT=55439 mvn -s /tmp/odw-postgresql-maven-settings.xml \
  -pl backend -am \
  -Dtest=DorisClusterServiceTest,DorisConnectionServiceTest,DorisConnectionServicePartitionsTest,DorisMetadataSyncServiceTest,DorisClusterControllerTest,PostgresqlDatasourceIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

本机原有 Maven 配置包含不可用的旧 HTTP 仓库；上述临时 settings 仅使用 `https://repo.maven.apache.org/maven2` 作为 `external:*` mirror，未修改用户或仓库 Maven 配置。正常仓库配置下可省略 `-s` 参数。

结果：**Tests run: 27, Failures: 0, Errors: 0, Skipped: 0**。真实 PostgreSQL 测试 5 项，覆盖：

- 使用配置 database、schema 查询上下文。（首轮结论；第二轮起凭据改为走统一用户映射，见下文"第二轮修复与验证"。）
- 非系统 schema、表/视图、注释、主键、精度/长度/数组类型、默认值、NULL 行数和未知时间。
- 包含双引号的字段预览和取值统计；含中文及 `%`/`_` 的注释按字面搜索。
- 列级授权账号只能读取获授权的表和字段。
- 不存在的 database 返回连接失败；DDL 限制明确返回提示，元数据同步不执行 MySQL DDL 查询。

前端命令（均先成功执行 `nvm use`）：

```sh
npm --prefix frontend test -- src/views/settings/__tests__/DataSourceManagement.spec.js
npm --prefix frontend run build
# 在 frontend/ 内：
./node_modules/.bin/eslint src/views/settings/DataSourceManagement.vue src/views/settings/__tests__/DataSourceManagement.spec.js
```

结果：**5 passed / 0 failed**；生产构建成功（已有大 chunk 提示）；ESLint **0 errors / 18 warnings**，警告为原页面已有属性顺序/console 用法。

## 实际 HTTP 与持久化

通过独立实例的 `/api/v1` 实际 HTTP 接口验证：

1. `POST /doris-clusters` 保存 `POSTGRESQL` 和 `databaseName`；响应不包含密码。
2. `POST /doris-clusters/{id}/test` 返回 true。
3. `PUT /doris-clusters/{id}` 不传密码，再次测试连接成功，数据库回填正确。
4. `GET /doris-clusters/{id}/databases` 包含 `public`；列接口正确返回主键及 `numeric(18,2)`。
5. `POST /tables/audit-metadata?clusterId={id}` 首次检测到差异。
6. `POST /tables/sync-metadata?clusterId={id}` 返回 `SUCCESS`；表列表持久化 schema、注释和一行数据的统计信息。
7. `GET /tables/{id}/preview` 返回预置的 `smoke-ok`。
8. `GET /doris-clusters/{id}/sync-history` 返回 `SUCCESS`；再次同步成功，随后比对 `hasDifferences=false`。

另直接查询独立 MySQL schema，确认源配置、表、字段及同步历史已落库。测试使用真实 PostgreSQL 数据、真实 JDBC 和真实 MySQL；没有以 mock store 代替此流程。

## 限制与清理

未执行真实浏览器手工交互；页面行为由组件测试、生产构建和实际后端 HTTP 流程分别覆盖。未测试定时触发；自动同步复用已验证的相同同步服务。PostgreSQL 完整 DDL 导出、结构编辑、Doris 快照备份和 DataAgent 专用查询路径不在此次支持范围。

已清理独立后端进程、PostgreSQL 测试容器、MySQL 测试 schema/账号。原有服务和数据库保留。

## 第二轮修复与验证（2026-10-23）

起因：codex review 提出 3 项 Critical、9 项 Warning。本轮修复范围和遗留项见[设计](../design/2026-10-22-postgresql-datasource-design.md)与 [docs/TODO.md](../TODO.md)。

修复：

- JDBC URL 注入：新增 `JdbcUrlParts` 校验数据源地址（三种类型）和 MySQL/Doris 模板路径中的库名。
- DataAgent 数据范围：识别双引号 schema；无法解析且含双引号标识符的 SQL 直接拒绝。
- 用户映射：登记了映射账号但用户未授权时拒绝，不再退回数据源账号（三种类型统一）；未登记映射时行为不变。
- 主键 `INCLUDE` 列误判、分区父表行数和大小为 0、数组/jsonb 序列化、DataAgent 取数上限、DataStudio 结构编辑入口、新鲜度不支持的模式、多个同名 schema 的数据源定位。

验证（独立 Podman 容器 PostgreSQL 16，`127.0.0.1:55439`；平台 MySQL `127.0.0.1:3306`）：

- 定向测试全部通过：`PostgresqlDatasourceIntegrationTest` 7 项（新增分区汇总、INCLUDE 主键、数组列表、取数上限、映射账号连接），`JdbcUrlPartsTest`、`UserMappingServiceTest`、`DorisConnectionServiceUserContextTest`（拒绝不回退、未登记映射用数据源账号）、`BackendAgentQueryServiceTest`（双引号 schema 授权/越权、解析失败拒绝）、`BackendAgentMetadataServiceTest`（同名 schema 按范围定位）、`TableFreshnessServiceTest`、`DataTableServiceTest`、`DataQueryServiceTest` 等。
- 后端全量：575 项，568 通过，7 项失败均为 `WorkflowRuntimeSyncRealIntegrationTest` 的"Dolphin 不可达"（本机未启动 DolphinScheduler），与本次改动无关；2 项跳过。
- 前端：`nvm use` 后运行 `src/views/datastudio`、`src/views/settings` 测试，28 个文件 240 项全部通过，含新增的 PostgreSQL 编辑禁用用例。

未验证：数据查询页实际 analyze/execute 的 HTTP 流程、浏览器手工操作、DataAgent 真实模型全链路（本机模型账号欠费）。已记入 [docs/TODO.md](../TODO.md)。

## 第三轮修复与验证（2026-10-23）

起因：codex 复审第二轮修复，结论"需要修改后合并"（2 项 Critical、4 项 Warning）。

修复：

- 数据范围校验按引擎的标识符语义比较：PostgreSQL 未加引号的名称折叠为小写，带引号名称原样比较（不 trim）；校验移到解析出数据源之后，以便知道引擎。
- `TableEngineJdbcExecutor`（MySQL 结构编辑路径）的 URL 拼接接入 `JdbcUrlParts`。
- `JdbcUrlParts` 接受末尾带点的 FQDN 和带 zone id 的 IPv6。
- 前端：结构编辑改为只对 Doris/MySQL 放行，类型未知的已同步表不放行；PostgreSQL 表的表名、表注释输入框只读，平台属性仍可编辑。
- 测试补充：PostgreSQL 大小写折叠与引号内空白、授权用例断言执行器确被调用、MySQL 结构编辑路径的库名注入、合法地址边界、新鲜度对 PostgreSQL 的模式拒绝、`JdbcValues` 对 MySQL/Doris 值不做改动以及嵌套数组和 jsonb。

验证：定向后端测试 14 个类共 103 项全部通过（真实 PostgreSQL 16 容器，跑完已删除）；前端 `src/views/datastudio`、`src/views/settings` 共 241 项全部通过。用户手册补充 PostgreSQL 12+ 要求和上述行为说明。

## 第四轮修复与验证（2026-10-23）

起因：codex 第三轮复审（2 项 Critical、2 项 Warning）。

- PostgreSQL 未加引号标识符只折叠 ASCII 大写字母，与 PostgreSQL 一致（`Ä` 不再被当成 `ä`）。
- 数据范围生效时，无法完整解析的 SQL 不再走词法猜测：PostgreSQL 一律拒绝，MySQL/Doris 只对 SHOW/DESC 保留词法检查（覆盖逗号连接、含 `$` 的名称等绕过）。
- 带括号 IPv6 的 `%25zone` 还原为 `%zone` 后再交给驱动，新增按 pgJDBC 实际解析结果断言的测试。
- 智能元数据采纳：数据源不支持改物理注释时，弹窗提示并不勾选、不提交表/字段描述，组合函数侧同样丢弃；平台属性和新鲜度照常采纳。

验证：定向后端测试全部通过（真实 PostgreSQL 16 容器，跑完已删除）；前端 `src/views/datastudio`、`src/views/settings` 共 243 项全部通过。

## 第五轮修复与验证（2026-10-23）

起因：codex 第四轮复审。DESC 后接查询、`SHOW ... FROM/IN 库名`、反引号、`/*! */` 可执行注释仍能绕过词法检查；保存后的 IPv6 zone 无法再次通过校验。

- 数据范围生效且 SQL 无法解析时，不再做词法猜测，只放行整句匹配的固定元数据语句（见 [docs/TODO.md](../TODO.md)），其余一律拒绝。评审给出的 10 条绕过样例全部加入回归测试。
- `JdbcUrlParts`：带括号 IPv6 同时接受 `%25zone` 和 `%zone`，规范化结果可以原样再次校验（保存后再编辑、再连接）。
- 智能元数据：生成期间对应的表被关闭时，弹窗提示并禁用采纳。

验证：`BackendAgentQueryServiceTest` 31 项、`JdbcUrlPartsTest` 5 项、`AgentQueryControllerTest` 2 项通过；前端 244 项全部通过。

## 第五轮复审结论与收尾（2026-10-23）

codex 第五轮复审又发现 3 项可绕过数据范围的写法（MySQL 可执行注释、表达式子查询、PostgreSQL 三段名）。经与用户确认，不再修补 SQL 解析，改为在数据库层按数据范围配只读账号隔离，已记入 [docs/TODO.md](../TODO.md)。

同轮的 IPv6 问题已修复：地址校验只判断、不改写，带网卡标识的 IPv6 只接受不加括号的 `fe80::1%en0`；新增按 pgJDBC 实际解析结果断言、以及反复校验结果不变的测试（`JdbcUrlPartsTest` 6 项通过）。
