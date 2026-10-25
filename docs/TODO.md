# 待办清单

记录已确认需要做、但当前不做的事项，按需要逐项完善。

使用约定：

- 新的待办统一追加到这里，不另建零散的 TODO 文件，也不只写在代码注释或会话记录里。
- 每条写清楚：现状、要做什么、来源或关联文档。来源写明发现的日期。
- 开始做某一条时，如果属于中大型变更，先按 AGENTS.md 的流程写 `design/` 和 `plans/`，并在这里附上链接。
- 做完后把这一条移到文末的"已完成"，保留结果链接，不直接删除。

## 权限与数据源

### 数据库账号映射与用户授权管理

- **现状**（2026-10-23 核实）：数据模型在 V3 迁移里都有，但功能没做完，实际部署基本没有启用：
  - 库级账号 `doris_database_users`（每个库一对只读/读写账号）：只有读取方（`UserMappingService`、DataAgent 数据源解析），**没有新增或修改的接口，也没有页面**，只能手写 SQL；表名带 Doris，但结构是通用的。
  - 用户级授权 `user_database_permissions`：有后端接口 `/v1/permissions/*`（`PermissionManagementController`），**没有前端页面**；外键依赖的 `platform_users` 在代码中没有任何写入；`expires_at` 字段没有生效，取凭据时不检查过期。
  - 元数据枚举（表、字段、对象列表）固定用数据源账号，不走用户映射。
- **要做**：库级账号的管理接口和页面（含密码脱敏）；用户授权页面；`platform_users` 的来源和同步方式；过期时间生效；元数据枚举按用户权限过滤；三种数据源（Doris/MySQL/PostgreSQL）统一行为。
- **关联**：PostgreSQL 修复中已把"登记了映射账号但用户未授权时拒绝，不再退回数据源账号"改为三种类型统一规则；完整的权限功能需单独写设计文档。

### DataAgent 数据范围改为数据库层隔离

- **现状**（2026-10-23，codex 五轮评审）：DataAgent 的"数据范围"靠执行前解析 SQL 文本拦截越权引用，只能作为辅助拦截。已确认仍可绕过的写法：
  - MySQL 可执行注释藏在可解析的语句里，例如 `SELECT * /*! FROM private.orders */`：JSqlParser 当作普通注释，MySQL 会执行；
  - 表达式里的子查询，例如 `SELECT ((SELECT secret FROM private.orders LIMIT 1) = 'x') IS TRUE`，以及 JOIN ON、ORDER BY、GROUP BY 中的子查询：`TablesNamesFinder` 提取不到；
  - PostgreSQL 三段名 `ads_user.private.orders`：校验的是第一段（database），实际访问的 schema 是 `private`。
  前两项在 MySQL/Doris 上原本就存在，不是 PostgreSQL 引入的。
- **结论**：不再继续修补 SQL 解析。可靠隔离要在数据库层做：按数据范围给每个库或 schema 配只读账号，DataAgent 用对应账号连接，未授权的库从权限上就读不到。
- **要做**：并入下面"数据库账号映射与用户授权管理"一起设计实施。在此之前，数据范围只能防误用，不能当作安全边界；智能体连接所用账号的权限应尽量收窄。

### PostgreSQL 数据源的后续支持

来源：2026-10-23 codex review（[PostgreSQL 数据源设计](design/2026-10-22-postgresql-datasource-design.md)）。本轮修复之外、暂不处理的部分：

- 数据查询页的语句切分仍按 MySQL 词法处理：`$tag$...$tag$` 美元引号会被错切，`#` 运算符会被当成注释；风险确认里的对象名规范化会丢掉引号，并按空白截断。
- 血缘解析（`SqlTableMatcherService`）会去掉双引号、空格并统一转小写，大小写敏感的标识符可能漏匹配或误匹配；正则回退只支持反引号。
- 完整可恢复的 DDL 导出、结构编辑（需要 PostgreSQL 的 `TableEngineHandler`）、schema 快照备份。
- 新鲜度检查的各个模式（COLUMN / METADATA / PARTITION）和分区详情。
- domain 类型上的 `NOT NULL` 约束（目前只看 `attnotnull`）。
- DolphinScheduler 中 PostgreSQL 数据源的调度接入。
- DataAgent 经真实模型、MCP、Java agent API 到 PostgreSQL 的 NL2SQL 全链路冒烟。
- DataAgent 数据范围校验对 MySQL/Doris 未加引号的标识符按原样比较：开启 `lower_case_table_names=1/2` 的实例上，大小写不同但实际合法的引用会被误拒（不会扩大授权）。需要按实例配置决定比较方式。
- 数据范围生效时，无法被 JSqlParser 4.6 完整解析的 SQL 直接拒绝；只有 MySQL/Doris 的几种固定元数据语句例外（`SHOW [FULL] TABLES [LIKE '...']`、`SHOW [FULL] COLUMNS|FIELDS FROM|IN [db.]tbl`、`DESC|DESCRIBE [db.]tbl`）。实测被拒绝的常用写法：PostgreSQL `TABLESAMPLE`；MySQL `GROUP BY ... WITH ROLLUP`、`JSON_TABLE(...)`、`MEMBER OF(...)`；Doris `LATERAL VIEW explode(...)`、`TABLET(...)`、`TABLESAMPLE n PERCENT`。如果这些写法常用，需要换用能完整识别表引用的解析方案。（2026-10-23，codex 第四轮评审）
- 数据查询页对 PostgreSQL 实际执行 analyze/execute 的 HTTP 冒烟（含风险确认、取消、超时）和浏览器手工验证。
- DataAgent 只读查询接口只接受 database 名，没有数据源 ID：多个数据源存在同名 schema（如 `public`）且智能体未配置数据范围时，仍会报"命中多个 cluster_id"。需要在接口、odw-cli 和 MCP 中增加可选的数据源 ID。

### 数据源适配器架构

- **现状**（2026-10-25）：架构实施中，阶段一、二已落地（验证覆盖见报告），阶段三、四未实施，见 [数据源适配器设计](design/2026-10-22-datasource-adapter-design.md) 和 [计划](plans/2026-10-22-datasource-adapter-plan.md)。
- **已决定**（2026-10-25）：内置驱动为主，保留 Doris/MySQL/PostgreSQL pom 依赖，升级无需目录或版本绑定迁移；外置目录仅可选补充新 JDBC 数据库或显式覆盖内置版本。
- **后续**（来源：2026-10-25 阶段一、二范围取舍）：当前只检查驱动类可用性；需实现可选 manifest、独立 ClassLoader、Java/依赖/校验和检查、driverId 持久绑定及选择器，以双版本隔离验证平台连接不受影响。阶段三物理编辑扩展、阶段四 DataAgent 统一仍独立验收。见上述设计、计划及 [验证报告](reports/2026-10-25-datasource-adapter-verification.md)。

- **连接字段扩展**（来源：2026-10-25 代码风格复核）：第一阶段仅接受 `feHost`/`fePort`/`databaseName`/`username`/`password`，其他 key 会被注册表拒绝。后续支持适配器声明额外连接字段（如 Oracle service name、额外 JDBC 参数），须将配置存储、参数校验与前端表单一起设计，不只放宽 key 检查。见 [设计](design/2026-10-22-datasource-adapter-design.md) 和 [验证报告](reports/2026-10-25-datasource-adapter-verification.md)。

- **验证补齐**（来源：2026-10-25 阶段一、二验证）：真实 PostgreSQL 与 MySQL HTTP 已覆盖；数据源表单浏览器检查通过，Data Studio 字段/DDL/SQL 结果的完整浏览器闭环仍需补跑；本机没有可用 Doris 实例，需补跑 Doris 真实同步、预览、DDL、分区和新鲜度回归；DolphinScheduler 不可达的既有全量集成用例需在环境恢复后复跑。见 [验证报告](reports/2026-10-25-datasource-adapter-verification.md)。

### 完全关闭认证时的查询历史

- **现状**（来源：2026-10-25 适配器 HTTP 冒烟）：设置 `odw.auth.enabled=false` 时没有用户上下文，查询历史 `executed_by` 为 NULL；历史接口使用 `executed_by = NULL` 筛选，记录已落库但列表为空。使用认证模块的本地匿名上下文后，同一执行与历史链路正常。
- **要做**：单独统一关闭认证时的历史归属和查询语义，并补回归；本轮未修改认证和查询历史实现。见 [验证报告](reports/2026-10-25-datasource-adapter-verification.md)。

## DataAgent 运行时与插件

### 插件管理与导入（暂缓，后续专门实施）

- **现状**（2026-10-23）：只有设计和计划，没有代码改动。见 [插件管理设计](design/2026-10-22-dataagent-plugin-management-design.md) 和 [计划](plans/2026-10-22-dataagent-plugin-management-plan.md)。已拍板：skill folder 加 `<plugin_id>__` 前缀；升级立即生效；第一阶段拒绝引用 `${CLAUDE_PLUGIN_ROOT}` 的 MCP；认证关闭的部署照常允许导入。
- **评审结论**：codex 两轮评审都是"需要修改后定稿"。第二轮复核第一轮的 14 项：6 项已解决、8 项部分解决；新发现 6 项 Critical，都和"原地替换 skill 目录"这条路有关：
  1. 替换目录的各步之间存在无法可靠恢复的中断窗口（需要按 folder 记录交换意图并做幂等恢复）；
  2. `SKILLS_ROOT_DIR` 与插件仓库不在同一挂载点时 `rename` 会失败，现有部署是单独 bind mount 的；
  3. 沙箱暖容器挂载的是具体 skill 目录，复用签名不含内容版本，升级后同一 topic 可能继续用旧 skill；
  4. 普通 MCP 创建、settings 全量保存、agent 草稿保存和发布都可能与插件安装并发写入；
  5. 其他副本启动时的全量 reindex 仍可能删除正在交换的 skill 的版本历史；
  6. 工具名冲突检查没有覆盖"已发布 agent + 立即升级"，且 MCP 能力接口返回的工具清单不完整。
  另有 6 项 Warning：命名锁的连接生命周期、settings 持久化的事务边界、启用状态取交集时内置 skill 的自动补入、Skill 调用名改写缺少可执行语法、stdio 确认令牌未绑定 env 值、"只停用后回退"不安全。
- **下次实施前先决定**：是否放弃"原地替换目录"，改为"每次安装或升级写入新的版本目录，运行时把稳定的 skill 标识解析到当前版本"。后一种方式能一并避开上面 1、2、3、5 项，代价是运行时要增加一层通用的 skill 路径解析。确定后再修订设计、重新评审。
- **可以先单独做的通用修正**：快照里的 skill 与当前启用状态取交集（计划第 0 项），注意沙箱会自动补入平台工具 skill，以及空配置与全局 fallback 的区别。

### 运行时迁到 `pi-coding-agent` SDK

- **现状**：`dataagent-runtime-pi` 只用了 `pi-agent-core`。commands、子代理、workflow、hooks 都依赖 `pi-coding-agent` 的扩展系统。
- **要做**：单独写设计文档。需要重写 `kernel/cell.ts`，把工作区边界、结果折叠、上下文压缩、工具结果注册改写成扩展事件；关闭 SDK 默认的编码工具、会话文件、项目信任和 `~/.pi` 配置；子代理示例会启动独立的 `pi` 进程，需要重新接入边界、取消和事件记录；hooks 需要另做安全评审。

### MCP 工具名命名空间

- **现状**：Pi 直接使用 MCP 返回的工具名注册，没有按服务加命名空间，不同 MCP 的同名工具会冲突。插件设计里先用"冲突即拒绝"兜底。
- **要做**：设计按服务加命名空间的方案，同时兼顾 Claude 引擎和已有的技能文档。

### 系统提示落库的后续

- 管理端查看页面（接口 `GET /api/v1/dataagent/tasks/{task_id}/system-prompt` 已有）。
- `da_system_prompt` 中不再被任务引用的记录的定时清理（按"无引用且超过 N 天"）。

### 真实模型冒烟

- 2026-10-22：本机配置的模型账号欠费（403 `Current user is in debt`），Pi 1.4.0 升级和系统提示落库都只完成了离线测试和真实库验证。账号恢复后补跑：最小对话、多轮对话、真实 NL2SQL，并确认系统提示被落库。

## 测试与工程卫生

- `da_agent_draft` 表的排序规则是 `utf8mb4_0900_ai_ci`，与其他 `da_*` 表的 `utf8mb4_unicode_ci` 不一致，以后与其他表做字符串关联时会报错。
- `entity-extraction` 内置 skill 被 `.gitignore` 的 `/dataagent/.claude/skills/*/` 规则忽略，没有被 git 跟踪，`test_every_builtin_skill_is_fully_tracked_by_git` 失败。
- `test_pi_runtime_e2e::test_real_cell_completes_the_protocol_round_trip` 假设环境里没有模型凭据；本机 `dataagent-backend/.env` 有凭据时请求会真实发出，导致失败。测试应显式清空凭据相关的环境变量。
- Pi 测试 `cell.init Bash timeout 2s reaches the real command` 单独运行稳定通过，跑全量时偶发失败，是时序问题。
- `skills/bin/odw-cli` 是一份旧版拷贝，与 `dataagent/.claude/skills/opendataworks-platform-tools/bin/odw-cli` 已经分叉，需要确认是否还在使用，决定删除或合并。
- `tools/dataagent-evals/pi-kernel-spike` 仍使用 Pi `0.85.1`，作为早期验证脚本不再维护，可以考虑归档。
- DataAgent 后端的 `core/tool_runtime.py`、`core/datasource_resolver.py`（Python 直连查询路径）只有测试在用，引擎只认 `mysql`/`doris`；确认后删除，或者与 Java agent API 对齐。

## 已完成

（暂无）
