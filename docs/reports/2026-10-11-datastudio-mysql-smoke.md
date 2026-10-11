# DataStudio MySQL 真实验证

日期：2026-10-11。使用正式前端、Java 后端、真实 MySQL JDBC/HTTP/UI 链路，并以直接查询 MySQL 的结果交叉核对。未使用演示 mock 数据。

## 环境与测试资源

- MySQL 8.0.43：Podman 既有容器 `data-portal-mysql`，127.0.0.1:3306，schema `opendataworks`。
- 新建专用连接：`MySQL 真实验证 20261011`，id=3，`sourceType=MYSQL`，自动同步关闭，不设为默认连接。没有修改原 id=1 的连接配置。
- 专用临时表：`zz_studio_smoke_20261011`，平台 tableId=1718；4 个字段，3 行测试数据，北京/上海/杭州三家门店，销售额合计 601.50。
- Java 8 / Spring Boot：127.0.0.1:8080/api。本地临时匿名管理员模式，关闭 Flyway、工作流运行态同步、新鲜度定时检查及 Doris 审计同步。本次未执行数据库迁移。
- 首轮使用既有已构建 jar 复现问题，修复后使用本工作区重新打包的 `backend/target/opendataworks-backend-1.5.0.jar` 复验。
- 正式 Vite 前端：127.0.0.1:3017，每次命令前成功 `nvm use`，Node 20.19.0。用户的 4174 演示服务保持运行。
- 直接核对：项目 `.venv-py313` 的 PyMySQL；测试 SQL 和 HTTP 只针对上述专用表及连接。

## 结果

| 场景 | 真实操作与核对 | 结果 |
| --- | --- | --- |
| 连接 | 新建 MYSQL 连接，`POST /doris-clusters/3/test` | true |
| 目录 | 展开连接、Schema、表分类，搜索并打开临时表；激活标识和 MYSQL 类型显示正确 | 通过 |
| 元数据导入 | 单表同步，4 个字段、3 行和 16 KB 统计与 MySQL 一致 | 通过，首次暴露同步标记问题并修复 |
| SQL 查询 | 页面执行 SELECT，3 行结果；直接 SQL 核对数量与金额合计 | 通过 |
| 返回行数限制 | HTTP 查询 limit=2，返回 2 行、hasMore=true | 通过 |
| 解释 | 页面“解释”执行真实 MySQL EXPLAIN，返回 SIMPLE/ALL 等计划信息 | 通过 |
| 停止与恢复 | 页面执行 SLEEP(30)，PROCESSLIST 确认进入 User sleep；点击停止约 9.21 秒结束，数据库中慢语句消失；再执行成功 | 通过 |
| 多结果集 | 页面执行两条 SELECT；结果 1 是三家门店，结果 2 是 rows_count=3、total=601.5 | 通过 |
| 图表 | 真实查询结果切换柱状图，选中 store_name/amount，生成有实际尺寸的 canvas | 通过 |
| CSV | 页面真实下载 CSV，读取文件校验 3 行及金额合计 601.50 | 通过 |
| 查询历史 | 页面显示本轮真实执行记录；MySQL data_query_history 有对应 SQL、执行时间与耗时 | 通过，停止的查询未新增历史记录（既有行为） |
| DDL | 页面显示真实 CREATE TABLE，InnoDB、主键及字段与 SHOW CREATE TABLE 一致 | 通过 |
| 概览编辑 | 页面保存表注释、负责人；平台 data_table 和 information_schema.TABLES 均核对成功 | 通过，另修复保存后详情空值覆盖问题 |
| 跨查询连接编辑 | 查询目标切到原 id=1，右侧继续编辑 id=3 所属对象；MySQL handler 日志、物理表注释和平台 cluster_id=3 一致 | 通过 |
| 字段页面编辑 | 页面保存 store_name 注释；SHOW FULL COLUMNS 和 data_field 的注释一致 | 通过 |
| 字段 CRUD | HTTP 新增 VARCHAR(32)，改名、扩为 VARCHAR(64)、修改默认值，再删除；每步直接核对 SHOW FULL COLUMNS | 通过 |
| 数据新鲜度 | 页面配置 MAX(loaded_at)，预警 1 天、过期 2 天；检查真实字段 | 通过 |
| 状态变化与历史 | 专用数据改为 3 天前→error/过期，再改为 1 分钟前→pass/正常；表中持久化结果与页面历史一致 | 通过，最终共 4 条检查结果 |
| 血缘 | 临时表无关联任务和上下游，页面正常展示空关系和完整血缘入口 | 空状态通过；未创建真实工作流血缘 |
| 变更记录 | API 查询实际返回 9 个版本记录 | API 持久化通过；本轮未做版本比较 UI 验证 |

## 本轮发现并修复

1. **MySQL 同步导入误写 `is_synced=0`**：字段保存返回成功但只更新平台 metadata，物理列注释不变。修正导入及已有表同步分支为 `is_synced=1`，按既有 MySQL handler 分发。修复后真实字段 CRUD、表注释和列注释均写入 MySQL。历史 MySQL 表通过下一次针对该表同步修复，不批量修改历史数据。
2. **保存表信息导致详情丢失**：更新 API 返回部分更新实体，未填写的数据库、连接、统计等属性为 null，前端直接合并会清空详情并影响 Tab key。前端保存成功后重新获取完整对象，再更新详情和缓存。复验保留数据库 opendataworks、原连接、isSynced=1、3 行和 16 KB，后续字段编辑正常。
3. 保存确认文字改为“对象信息”，避免 MYSQL 表操作出现“Doris 配置”。

关联设计/计划：

- [数据源类型设计](../design/2026-06-22-data-table-source-type-design.md)、[执行计划](../plans/2026-06-22-data-table-source-type-plan.md)
- [工作区设计](../design/2026-10-09-datastudio-editor-workspace-design.md)、[执行计划](../plans/2026-10-09-datastudio-editor-workspace-plan.md)

## 定向验证

- 后端：`mvn -pl backend -am -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DorisMetadataSyncServiceTest,DataTableServiceTest,MysqlTableEngineHandlerTest test`：23 项通过，无失败、错误或跳过。
- 后端：`mvn -pl backend -am -DskipTests package`：通过，复验使用新 jar。
- 前端：`nvm use` 后 `npm --prefix frontend test -- src/views/datastudio/__tests__/useTableMetaEditing.spec.js`：9 项通过，包含部分响应不得清空详情的回归用例。
- 前端：`nvm use` 后 `npm --prefix frontend run build`：通过，仍有既有大 chunk 警告。
- `git diff --check`：通过。

## 证据

- 页面汇总结果与新鲜度截图：`/Users/guoruping/.codex/visualizations/2026/10/09/01a11e91-87d8-7fe0-a559-d09d9cf7e9c9/datastudio-mysql-smoke-proof-2026-10-11.jpg`。截图裁去浏览器输出外围空白及下方空白，保留连接、SQL、结果、对象详情与新鲜度。
- 实际导出的 CSV：`/Users/guoruping/.codex/visualizations/2026/10/09/01a11e91-87d8-7fe0-a559-d09d9cf7e9c9/datastudio-mysql-export-2026-10-11.csv`。
- 本地日志：`/tmp/odw-studio-mysql-smoke-backend-fixed.log`、`/tmp/odw-studio-mysql-smoke-tests.log`、`/tmp/odw-studio-mysql-smoke-frontend-tests.log`、`/tmp/odw-studio-mysql-smoke-frontend-build.log`。

## 清理与边界

已删除本轮临时物理表、平台表、连接及关联字段（含已逻辑删除字段）、版本、统计、新鲜度配置/结果、查询历史、同步历史和测试访问统计；直接 SQL 复查上述专用资源全部剩余 0。原 id=1 连接仍 active。关闭本轮浏览器标签，停止本轮 3017 前端及 8080 后端，用户 4174 服务及既有数据库容器保持运行。

本轮没有执行 Doris 引擎专属分桶/副本/软删除，没有调用真实模型生成智能元数据，没有运行 DolphinScheduler 或真实工作流任务链路。因此这些能力不能据本报告声称端到端通过。

额外观察待后续收敛：MySQL 列默认值“空串”和“null”会在重复同步时产生无实际变化的字段变更记录；本轮证明表的物理关联状态修复幂等，不代表所有字段的同步已完全幂等。MySQL 字段页的主键切换控件也应与后端“暂不支持在线修改主键”的约束一致。首次带目标表 URL 打开时曾被恢复的旧工作区覆盖，应另补有缓存情况下的路由恢复专项验证（重构前已有同样的初始化流程）。

## 后续状态

上述三个额外观察已在后续修复并用真实 MySQL 复验，详见 [后续修复与验证](2026-10-11-datastudio-mysql-followup.md)。
