# DataStudio MySQL 后续修复与复验

日期：2026-10-11。接续 [首次真实验证](2026-10-11-datastudio-mysql-smoke.md) 的三个待办，保持既有视觉布局。

## 修复

1. **默认值重复变更**：字段同步使用显式 update wrapper 清空 `default_value`，避免 MyBatis 的非空更新策略忽略 null。保留 SQL 空字符串默认值与无默认值的区别。字段编辑 payload 同时保留原有 NULL，仅修改注释不会将其转换为空串；比较函数维持空白输入的既有比较规则，未修改字段不提交。
2. **MySQL 主键编辑**：已同步物理表的已有字段主键开关禁用，并在提交前校验。新字段、平台草稿保留既有编辑能力；类型缺失的旧缓存从连接配置解析引擎类型。Doris 限制保持。
3. **目标链接被缓存覆盖**：初始化暂停标签反写 URL，恢复缓存并加载连接后再解析传入路由。嵌套同步保留抑制状态，异常通过 finally 恢复；初始化后普通标签切换仍更新 URL。

## 环境

- Podman 既有 `data-portal-mysql`，MySQL 8.0.43，127.0.0.1:3306，schema `opendataworks`。
- 正式 Java 后端 8080/api，Java 8，重新打包的 1.5.0 jar；临时匿名管理员模式，关闭 Flyway、运行态同步、Doris 审计同步及新鲜度定时检查。未执行迁移。
- 正式 Vite 前端 3017，Node 20.19.0，每次 frontend 命令前成功 nvm use。用户 4174 演示服务保持运行。
- 专用 MYSQL 连接 `MySQL 后续验证 20261011`，第一轮 id=4、表 id=1719；补充修复后第二轮 id=5、表 id=1720。两轮同名临时物理表 `zz_studio_followup_20261011`，4 字段、2 行数据，均未设为默认连接、未启用自动同步。
- SQL/HTTP 交叉核对使用项目 `.venv-py313` / PyMySQL。真实 JDBC、HTTP、浏览器链路，无 mock 数据。

## 真实验证结果

| 场景 | 操作与核对 | 结果 |
| --- | --- | --- |
| 默认值移除 | 物理列移除非空默认值，平台另模拟旧空串记录，同步后直接检查 data_field | 两个字段均持久化为 NULL；本次修正 2 个字段 |
| 重复同步 | 修正后连续同步两次 | updatedFields 均为 0 |
| SQL 空字符串 | 保留 DEFAULT 空字符串；再为无默认值列设置 SQL 空字符串默认值 | 空字符串原样保留；新增默认值计 1 次，下一次计 0 |
| 普通注释编辑 | 页面仅修改 removed_default 注释，直接核对物理列与平台字段 | 注释一致，默认值均保持 NULL；首次及重复同步计数 [0, 0] |
| 主键控件 | 编辑已有物理列；页面新增一行草稿后检查权限，再取消 | 已有主键开关禁用，可为空开关可用，新增字段主键开关可用 |
| 目标路由 | 浏览器已有 doris_database_users、business_domain 两个缓存标签，打开临时表 URL | 目标表激活，URL 未被旧标签覆盖，旧标签保留 |
| SQL 缓存 | 旧标签输入 SELECT 42 AS cache_probe，再完整导航到临时表 URL，回到旧标签检查 | 自定义 SQL 保留；验证后恢复原 SQL |
| 普通切换 | 初始化后切换旧标签与目标标签 | URL 正常随活动标签改变 |
| 查询可用 | 最终代码通过页面执行临时表 SELECT | 返回真实 2 行，主键与数据未被编辑限制改变 |

## 回归验证

- 前端：`npm --prefix frontend test -- src/views/datastudio/__tests__`，26 文件、230 项通过。覆盖异步连接加载中的缓存恢复、SQL 保留、初始化后切换、路由失败释放、物理字段/新字段/草稿主键控件、NULL payload 与未修改字段不提交。
- 后端：`mvn -pl backend -am -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DorisMetadataSyncServiceTest,DataTableServiceTest,MysqlTableEngineHandlerTest test`，25 项通过。
- 前端 build、后端 package、`git diff --check` 均通过。前端仍有既有大 chunk / Sass 提示。
- 日志：`/tmp/odw-studio-followup-module-tests.log`、`/tmp/odw-studio-followup-backend-tests.log`、`/tmp/odw-studio-followup-frontend-build.log`、`/tmp/odw-studio-followup-backend-package.log`、`/tmp/odw-studio-followup-backend.log`。
- 截图：`~/.codex/visualizations/2026/10/09/01a11e91-87d8-7fe0-a559-d09d9cf7e9c9/datastudio-followup-2026-10-11-proof.jpg`，保留工作区标签、真实查询结果及字段主键禁用态。

## 相关设计与计划

- [数据源类型设计](../design/2026-06-22-data-table-source-type-design.md)、[执行计划](../plans/2026-06-22-data-table-source-type-plan.md)。
- [工作区设计](../design/2026-10-09-datastudio-editor-workspace-design.md)、[执行计划](../plans/2026-10-09-datastudio-editor-workspace-plan.md)。

本轮未调用真实模型生成元数据，未执行 Doris 专属操作或真实 DolphinScheduler 工作流血缘，因此不据此报告声称这些链路端到端通过。SQL 空字符串默认值的验证针对同步；字段输入框的 SQL 字面量表达与空状态展示未在本轮重设计。

## 清理

两轮临时物理表、连接、平台表、字段、版本、统计、新鲜度配置/结果、任务关联、查询/同步历史及访问统计均已清理，直接 SQL 复查各类专用资源剩余为 0。原连接 id=1 仍为 active。已恢复原缓存 SQL、移除测试对象标签并关闭临时浏览器标签；本轮 8080 后端和 3017 前端已停止，端口核对仅用户既有 4174 继续监听，MySQL 容器保持运行。
