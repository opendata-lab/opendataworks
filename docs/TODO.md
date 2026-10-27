# 待办清单

记录已确认需要做、但当前不做的事项，按需要逐项完善。

使用约定：

- 新的待办统一追加到这里，不另建零散的 TODO 文件，也不只写在代码注释或会话记录里。
- 每条写清楚：现状、要做什么、来源或关联文档。来源写明发现的日期。
- 开始做某一条时，如果属于中大型变更，先按 AGENTS.md 的流程写 `design/` 和 `plans/`，并在这里附上链接。
- 做完后把这一条移到文末的"已完成"，保留结果链接，不直接删除。

## 权限与数据源

### 主体映射的验证与使用限制

- **MySQL 仅视图权限**（来源：2026-10-27 真实 MySQL 8.0.45 HTTP 权限矩阵）：人的账号只有视图 SELECT / SHOW VIEW 而无底表 SELECT 时，EXPLAIN 拒绝，代人问数也拒绝；PostgreSQL 16 同场景通过。本轮严格使用 EXPLAIN，不增加回退或自动扩权。若业务必须只授权视图，需要另行评估方案；[设计](design/2026-10-26-dataagent-datasource-identity-design.md#3-代人问数的-explain-检查)、[报告](reports/2026-10-26-dataagent-datasource-identity-verification.md)。
- **Doris 内网验证**（来源：2026-10-26 决策，2026-10-27 环境延续）：本机没有 Doris；需验证 EXPLAIN 对无权对象拒绝、仅视图授权、Select_priv 只读隔离，以及 `information_schema.columns` 只返回账号有权限的字段（元数据交集依赖这一点）。保持 applyReadOnly 空实现，不增加 LIMIT 0 或引擎禁用分支。真实第三方 OAuth 提供方与浏览器完整操作也未接入本机验证；目前覆盖带来源前缀的 Java HTTP 用户解析和本地真实登录问数。见上述报告。

### PostgreSQL 数据源的后续支持

来源：2026-10-23 codex review（[PostgreSQL 数据源设计](design/2026-10-22-postgresql-datasource-design.md)）。本轮修复之外、暂不处理的部分：

- 数据查询页的语句切分仍按 MySQL 词法处理：`$tag$...$tag$` 美元引号会被错切，`#` 运算符会被当成注释；风险确认里的对象名规范化会丢掉引号，并按空白截断。
- 血缘解析（`SqlTableMatcherService`）会去掉双引号、空格并统一转小写，大小写敏感的标识符可能漏匹配或误匹配；正则回退只支持反引号。
- 完整可恢复的 DDL 导出、结构编辑（需要 PostgreSQL 的 `TableEngineHandler`）、schema 快照备份。
- 新鲜度检查的各个模式（COLUMN / METADATA / PARTITION）和分区详情。
- domain 类型上的 `NOT NULL` 约束（目前只看 `attnotnull`）。
- DolphinScheduler 中 PostgreSQL 数据源的调度接入。
- 真实模型、MCP、Java agent API 到 PostgreSQL 的 NL2SQL 已在 2026-10-27 验证；见文末主体映射结果。Doris 和生产沙箱容器拓扑仍待内网回归。
- 旧范围解析已在 2026-10-27 删除；实时目录/元数据对象匹配仍须遵循引擎大小写语义（特别是 MySQL/Doris `lower_case_table_names=1/2`），需在相应实例验证。
- 旧表引用范围解析误拒 `TABLESAMPLE`、`WITH ROLLUP`、`JSON_TABLE` 等语法（2026-10-23 codex 第四轮评审）；范围解析已删除，不再规划替代的范围解析器。只读守卫仍保留，其语法支持单独验证，不能保证删除范围解析就支持全部语法。
- 数据查询页对 PostgreSQL 实际执行 analyze/execute 的 HTTP 冒烟（含风险确认、取消、超时）和浏览器手工验证。
- DataAgent 旧接口只接受 database 名，多源同名 schema（如 `public`）可能定位冲突；当前定稿仍保留该接口并拒绝歧义，本轮不扩展；后续需单独设计数据源 ID 契约，不能重新依赖已删除的范围配置消歧。

### 数据源适配器架构（剩余事项）

- **现状**（2026-10-26）：阶段一至三、Java 阶段四、能力目录和额外连接字段已实施，已完成部分移至文末。见 [设计](design/2026-10-22-datasource-adapter-design.md)、[计划](plans/2026-10-22-datasource-adapter-plan.md)、[验证报告](reports/2026-10-25-datasource-adapter-verification.md)。
- **驱动已决定、加载待实施**（2026-10-25 决策；2026-10-26 确认本轮不纳入）：内置为主，保留 Doris/MySQL/PostgreSQL 驱动；外置目录仅可选新增 JDBC 数据库或覆盖内置版本。当前只检查驱动类可用性；后续需 manifest、独立 ClassLoader、Java/依赖/校验和检查、driverId 持久绑定与选择器，并验证双版本隔离不影响平台连接。升级仍无需目录或旧源版本绑定迁移。
- **Python 与模型全链路**：2026-10-27 已删除未使用的 Python 直连 resolver/tool，模型账号已可用；本地 Pi / MCP / Java / MySQL、PostgreSQL 多轮问数、事件与消息持久化已通过。生产子容器跳转仍未验证，见[主体映射报告](reports/2026-10-26-dataagent-datasource-identity-verification.md)。
- **验证补齐**（2026-10-25 发现，2026-10-26 延续）：PG/MySQL Java agent HTTP 及结构编辑能力门禁已验证；完整 Data Studio 字段/DDL/查询结果浏览器闭环仍未重跑。本机无真实 Doris 实例，需补同步、预览、DDL、分区、新鲜度及物理编辑回归；按上述身份设计保持 `DorisAdapter.applyReadOnly` 空实现，智能体只读靠账号的 `Select_priv`；补测 Doris EXPLAIN 对无权对象报错、视图权限不依赖底表（2026-10-26 决定）。DolphinScheduler 不可达的既有集成用例需在环境恢复后复跑。原因与结果见上述报告。


## 测试与工程卫生

暂无。

## 已完成

### 主体数据库账号映射（原数据库账号映射与用户授权管理）

- **完成**（2026-10-27）：V54 主体/数据源映射、管理员管理页、人的账号访问 Data Studio 目录/查询/写入/结构编辑，数据源账号仅作同步/运维；旧三表、旧服务与权限接口直接删除。支持 `local:<用户名>` 和 `<provider>:<sub>`，缺映射或停用用户拒绝。沿用现有密码存储，读接口不回传密码。
- **结果**：[设计](design/2026-10-26-dataagent-datasource-identity-design.md)、[计划](plans/2026-10-26-dataagent-datasource-identity-plan.md)、[验证报告](reports/2026-10-26-dataagent-datasource-identity-verification.md)。

### DataAgent 数据范围改为数据库层隔离

- **完成**（2026-10-27）：人 EXPLAIN → 智能体只读执行，元数据对象/列交集；定时消息以会话所有者身份执行，没有独立模式（2026-10-27 删除 `X-Agent-Independent`）。服务令牌加用户/智能体标识由可信后端填写；CLI 通道固定身份，服务令牌与数据库密码不进入模型环境。删除 SQL 表引用授权检查、旧范围头/配置、平台库凭据旁路、Python 未使用直连代码；Alembic 20261027_000030 清理旧范围快照。
- **结果**：MySQL/PostgreSQL 真实 HTTP 权限矩阵及本地真实模型多轮问数通过；历史增加人/智能体/执行/检查账号，只有管理员和本人可看。匿名数据库访问直接拒绝，原“完全关闭认证时查询历史为空”不再通过 guest 历史解决。MySQL 仅视图和 Doris/生产拓扑等限制保留在上方。
- **链接**：[设计](design/2026-10-26-dataagent-datasource-identity-design.md)、[计划](plans/2026-10-26-dataagent-datasource-identity-plan.md)、[验证报告](reports/2026-10-26-dataagent-datasource-identity-verification.md)。


### 数据源适配器阶段一至三、Java 阶段四与连接字段扩展

- **完成**（2026-10-26）：阶段一、二由 cfa12bdd 提交；本轮续作统一连接获取、字符串编辑 handler、能力目录与前后端中性能力名、PG 参考结构 SPI、适配器对象缓存。Java agent 只读与范围守卫、用户映射拒绝保持。
- **连接字段扩展完成**：原 2026-10-25 复核确认的固定五字段限制已解除；V53 可空 connection_options、声明白名单/值校验、密码脱敏与保留、动态表单、TEST_DB 真实持久化到连接测试均实施。三个内置源不新增选项，旧配置无需改写。
- **结果**：[2026-10-26 验证章节](reports/2026-10-25-datasource-adapter-verification.md#2026-10-26阶段三java-阶段四与连接字段扩展)；[设计](design/2026-10-22-datasource-adapter-design.md)、[计划](plans/2026-10-22-datasource-adapter-plan.md)。外置加载、Python 与环境验证缺口保留在上方剩余事项，未删除。


### DataAgent 运行时、插件和前端待办已迁出

2026-10-27：上游 DataAgent 专属待办已迁往 opendataagent docs/TODO.md；含定时消息、运行时升级、ToolOutputRenderer 既有 10 项失败。见[迁移验证报告](reports/2026-10-27-dataagent-opendataworks-decoupling-verification.md)。

### website 中的 DataAgent 内部文档已移出

2026-10-27：智能查询架构、会话 API 与 Widget SDK 文档移到 opendataagent `docs/handbook/`（API 按现有路由重写）；本站 `architecture/dataagent.md` 改为「智能查询接入」，删除 `api/intelligent-query.md` 与 `guide/widget.md`，其余页面标明 DataAgent 单独部署。VitePress 构建通过。见[迁移验证报告](reports/2026-10-27-dataagent-opendataworks-decoupling-verification.md)。

### Go 版 opendataagent、根 skills 与 Gitee 发布同步已删除

2026-10-27：`opendataagent/`（Go 版）、只服务它的根 `skills/`（含旧版 `odw-cli` 拷贝）、其构建 workflow、Gitee 发布渲染脚本及测试一并删除。原 `test_gitee_sync_workflow_patches_release_body_after_upload` 在 main `828eb2f8` 上已因 workflow 不含 Gitee 步骤而失败，随脚本删除。见[迁移验证报告](reports/2026-10-27-dataagent-opendataworks-decoupling-verification.md)。
