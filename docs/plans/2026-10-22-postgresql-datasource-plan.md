# PostgreSQL 数据源实施计划

设计：[PostgreSQL 数据源管理](../design/2026-10-22-postgresql-datasource-design.md)

1. 扩展 `DorisCluster`、`DatasourceType`、`DorisClusterService`，增加可空数据库字段与 V52 迁移，添加 PostgreSQL JDBC 依赖。
2. 在 PostgreSQL catalog 工具中实现 schema、表、列和统计 SQL；连接服务按引擎选择连接及查询，实现 schema 上下文和双引号预览，隔离 Doris 专属调用。
3. 更新数据源管理类型选项、连接数据库输入、列表及端口切换，保留编辑密码行为。
4. 增加 Java 保存/连接/真实数据库契约测试和 Vue 表单回归；运行定向 Maven 测试、nvm use 后的前端测试和构建。
5. 使用独立 PostgreSQL smoke 容器/数据库及本地平台 MySQL，通过实际 HTTP 验证创建、编辑、测试连接、同步与历史持久化；清理测试数据、容器及启动的服务。记录实际覆盖和未覆盖项。
6. 补齐数据查询页默认 SELECT 的 PostgreSQL 引号和类型识别；回归表详情加载及默认 SQL，再经真实 HTTP 验证查询页 analyze/execute、表详情和字段接口。

## 发布与回退

发布后端时由 Flyway 添加列，再发布前端。回退代码前先停用 PostgreSQL 数据源及自动同步，保留可空新增列，避免回退时删除数据。既有 Doris/MySQL 默认连接和配置保持兼容。

## 验证结果

- 已完成步骤 1–5。后端 27 项定向测试全部通过（0 failures / 0 errors / 0 skipped），其中 5 项连接真实 PostgreSQL 16；前端 5 项回归全部通过。
- `nvm use` 后前端生产构建通过；定向 ESLint 无错误，存在原文件的 18 项属性顺序/console 警告；`git diff --check` 通过。
- 独立后端实例的真实 HTTP 创建、编辑/密码保留、连接检测、schema/字段查询、同步、表预览、同步历史、重复同步及同步后比对全部通过。平台 MySQL 使用独立 `odw_pgsql_smoke` schema，未改已有业务 schema。
- 详细命令、环境、覆盖与限制见[验证报告](../reports/2026-10-22-postgresql-datasource-verification.md)。

## 后续状态

- 步骤 6 的前端补充回归：3 个测试文件、30 项测试通过，前端再次构建通过；查询页 analyze/execute 的真实 HTTP 验证尚未执行。
- 用户要求先出适配器架构方案，已停止后续实现并清理临时测试实例。架构计划独立见[数据源适配器实施计划](2026-10-22-datasource-adapter-plan.md)。
