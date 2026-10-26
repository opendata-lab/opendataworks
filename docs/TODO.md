# 待办清单

记录已确认需要做、但当前不做的事项，按需要逐项完善。

使用约定：

- 新的待办统一追加到这里，不另建零散的 TODO 文件，也不只写在代码注释或会话记录里。
- 每条写清楚：现状、要做什么、来源或关联文档。来源写明发现的日期。
- 开始做某一条时，如果属于中大型变更，先按 AGENTS.md 的流程写 `design/` 和 `plans/`，并在这里附上链接。
- 做完后把这一条移到文末的"已完成"，保留结果链接，不直接删除。

## 权限与数据源

### 主体数据库账号映射（原数据库账号映射与用户授权管理）

- **状态**（2026-10-26 最终讨论）：**方案中，已决定、待一次性交付**。见 [设计](design/2026-10-26-dataagent-datasource-identity-design.md) 和 [计划](plans/2026-10-26-dataagent-datasource-identity-plan.md)；本次只改文档。
- **现状**（2026-10-23 核实，2026-10-26 复核）：V3 库级账号缺管理入口，platform_users 无写入链路，旧权限接口不完整；主门户问数可能仍是匿名身份，目录未统一按实际用户账号列出。
- **已决定 / 要做**：平台不建授权表，只登记人（`sys_user`）和各智能体在每个数据源上的数据库账号，多主体可共用账号；DBA 建账号并 GRANT，智能体账号只读，缺映射直接拒绝。人工查询、写入、结构编辑、目录都用人的账号，数据源原账号只用于同步和运维；删除旧三表、`UserMappingService` 和 `/v1/permissions`，不迁移。DataAgent 调平台沿用现有服务令牌，由其后端在请求头填写带来源前缀的用户标识（`local:<用户名>` 或 `<provider>:<sub>`，平台据此找 `sys_user`）和智能体 ID；拆分后的 MCP 用同一契约。

### DataAgent 数据范围改为数据库层隔离

- **状态**（2026-10-26 最终讨论）：**方案中，已决定、待一次性交付**，与上项合并。见 [设计](design/2026-10-26-dataagent-datasource-identity-design.md) 和 [计划](plans/2026-10-26-dataagent-datasource-identity-plan.md)。
- **现状**（2026-10-23 codex 五轮评审，2026-10-26 复核）：SQL 范围检查可被 MySQL 可执行注释、嵌套子查询、PG 三段名绕过；只读守卫尚未发现绕过。未签名 X-Agent-Data-Scope 和共享服务令牌不证明真实主体，平台库还有 Spring 凭据旁路。
- **已决定 / 要做**：代人问数先用人的账号 EXPLAIN，再用智能体账号只读执行；给模型的元数据取两个账号可见对象的交集；独立任务只用智能体账号；普通问数必须有登录用户。删除 SQL 表引用范围检查、`X-Agent-Data-Scope`、智能体数据范围配置和平台库旁路，只读检查保留。两个账号都能读即可跨库 JOIN；撤权下一次请求生效。
- **核实边界**（2026-10-26）：PostgreSQL、MySQL 的 EXPLAIN 会检查引用对象权限；Doris 的 EXPLAIN 需要 `Select_priv`，视图只看视图本身权限，需在内网实测确认。交集是对象级的：行级安全、按当前用户变化的视图或函数、`SECURITY DEFINER` 函数不保证行级交集，DBA 不应让智能体账号访问这类对象。

### PostgreSQL 数据源的后续支持

来源：2026-10-23 codex review（[PostgreSQL 数据源设计](design/2026-10-22-postgresql-datasource-design.md)）。本轮修复之外、暂不处理的部分：

- 数据查询页的语句切分仍按 MySQL 词法处理：`$tag$...$tag$` 美元引号会被错切，`#` 运算符会被当成注释；风险确认里的对象名规范化会丢掉引号，并按空白截断。
- 血缘解析（`SqlTableMatcherService`）会去掉双引号、空格并统一转小写，大小写敏感的标识符可能漏匹配或误匹配；正则回退只支持反引号。
- 完整可恢复的 DDL 导出、结构编辑（需要 PostgreSQL 的 `TableEngineHandler`）、schema 快照备份。
- 新鲜度检查的各个模式（COLUMN / METADATA / PARTITION）和分区详情。
- domain 类型上的 `NOT NULL` 约束（目前只看 `attnotnull`）。
- DolphinScheduler 中 PostgreSQL 数据源的调度接入。
- DataAgent 经真实模型、MCP、Java agent API 到 PostgreSQL 的 NL2SQL 全链路冒烟。
- DataAgent 旧数据范围校验在 MySQL/Doris `lower_case_table_names=1/2` 上可能大小写误拒（2026-10-23）；随上述主体映射方案删除该授权检查，不再单独扩展解析器。实时目录/元数据对象匹配仍须遵循引擎大小写语义。
- 旧表引用范围解析误拒 `TABLESAMPLE`、`WITH ROLLUP`、`JSON_TABLE` 等语法（2026-10-23 codex 第四轮评审）；随上述方案删除范围解析，不再规划替代的范围解析器。只读守卫仍保留，其语法支持单独验证，不能保证删除范围解析就支持全部语法。
- 数据查询页对 PostgreSQL 实际执行 analyze/execute 的 HTTP 冒烟（含风险确认、取消、超时）和浏览器手工验证。
- DataAgent 旧接口只接受 database 名，多源同名 schema（如 `public`）可能定位冲突；上述方案接口、odw-cli、MCP 应显式传数据源 ID，再按主体/数据源映射取账号，不再依赖待删除的数据范围配置消歧。

### 数据源适配器架构（剩余事项）

- **现状**（2026-10-26）：阶段一至三、Java 阶段四、能力目录和额外连接字段已实施，已完成部分移至文末。见 [设计](design/2026-10-22-datasource-adapter-design.md)、[计划](plans/2026-10-22-datasource-adapter-plan.md)、[验证报告](reports/2026-10-25-datasource-adapter-verification.md)。
- **驱动已决定、加载待实施**（2026-10-25 决策；2026-10-26 确认本轮不纳入）：内置为主，保留 Doris/MySQL/PostgreSQL 驱动；外置目录仅可选新增 JDBC 数据库或覆盖内置版本。当前只检查驱动类可用性；后续需 manifest、独立 ClassLoader、Java/依赖/校验和检查、driverId 持久绑定与选择器，并验证双版本隔离不影响平台连接。升级仍无需目录或旧源版本绑定迁移。
- **Python 与模型全链路**（来源：2026-10-26 范围与环境）：本轮统一 Java agent API，不改 Python resolver/tool；Python 直连路径见“测试与工程卫生”的既有单列项。真实模型账号欠费（403 Current user is in debt），恢复后按 DataAgent 规则补跑模型/MCP/任务、事件与消息持久化全流程；Java HTTP 验证不能替代此覆盖。
- **验证补齐**（2026-10-25 发现，2026-10-26 延续）：PG/MySQL Java agent HTTP 及结构编辑能力门禁已验证；完整 Data Studio 字段/DDL/查询结果浏览器闭环仍未重跑。本机无真实 Doris 实例，需补同步、预览、DDL、分区、新鲜度及物理编辑回归；按上述身份设计保持 `DorisAdapter.applyReadOnly` 空实现，智能体只读靠账号的 `Select_priv`；补测 Doris EXPLAIN 对无权对象报错、视图权限不依赖底表（2026-10-26 决定）。DolphinScheduler 不可达的既有集成用例需在环境恢复后复跑。原因与结果见上述报告。

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

### 数据源适配器阶段一至三、Java 阶段四与连接字段扩展

- **完成**（2026-10-26）：阶段一、二由 cfa12bdd 提交；本轮续作统一连接获取、字符串编辑 handler、能力目录与前后端中性能力名、PG 参考结构 SPI、适配器对象缓存。Java agent 只读与范围守卫、用户映射拒绝保持。
- **连接字段扩展完成**：原 2026-10-25 复核确认的固定五字段限制已解除；V53 可空 connection_options、声明白名单/值校验、密码脱敏与保留、动态表单、TEST_DB 真实持久化到连接测试均实施。三个内置源不新增选项，旧配置无需改写。
- **结果**：[2026-10-26 验证章节](reports/2026-10-25-datasource-adapter-verification.md#2026-10-26阶段三java-阶段四与连接字段扩展)；[设计](design/2026-10-22-datasource-adapter-design.md)、[计划](plans/2026-10-22-datasource-adapter-plan.md)。外置加载、Python 与环境验证缺口保留在上方剩余事项，未删除。
