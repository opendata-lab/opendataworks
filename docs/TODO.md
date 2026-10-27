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

## DataAgent 运行时与插件

### 定时消息（会话定时任务）功能完善

- **现状**（2026-10-27 核实）：只有后端。`/message-schedule` 接口（增删改查、执行日志）和协调器里的定时循环都在，数据存于 `da_agent_message_schedule`；前端 `dataagent-frontend/src/api/nl2sql.js` 封装了 `scheduleApi`，但没有任何页面或菜单调用，用户无入口。来源：2026-03-23 提交 `8bb5d774`。
- **身份**：定时消息以所属会话的所有者（`da_agent_topic.auth_user_id`）身份执行，和手动发消息走同一条"人 EXPLAIN → 智能体执行"路径；[身份设计](design/2026-10-26-dataagent-datasource-identity-design.md)。
- **要做**：决定是否正式提供定时任务。若提供，需要单独的定时任务菜单（列表、创建/编辑 cron、启停、执行记录），并按中大型变更先写设计和计划；若不提供，删除后端接口、定时循环、两张表和前端封装。

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
- **进展（2026-10-25）**：用户已确认迁移方向，设计见 [pi-coding-agent 迁移设计](design/2026-10-25-pi-coding-agent-migration-design.md)，计划见 [实施计划](plans/2026-10-25-pi-coding-agent-migration-plan.md)，第 0 步是试验。
- **暂缓（2026-10-26）**：先完成问题卡片与计划卡片。之后与 [服务化架构](design/2026-10-25-dataagent-service-architecture-design.md) 的重构一起做，两份设计改的是同一批模块：技能 ↔ `getSkills`，隔离 ↔ 工具 `operations`，凭据与 MCP ↔ 宿主侧 MCP 扩展，会话状态 ↔ 会话条目存 MySQL。
  - 开工前先定：SA-02（是否只保留 Pi 引擎；保留 Claude Code 引擎的话，凭据、隔离、会话都要做两套）；SA-01（隔离方式，需要生产宿主机的 `uname -r`，以及容器是否允许用户命名空间）。
  - codex 评审结论记在迁移设计第 8 节，重点：MCP 扩展会另外读全局 `mcp.json`；工具白名单要列全；会话条目恢复不了执行中的工具调用；新增的帧和对话卡片是跨层变更。
  - 建议顺序：试验 → 换内核 + `run.json` + 按本轮列技能 → MCP 和凭据移到编排侧 → 会话条目存库、删除 `home/` → 用 `operations` 接新隔离、删除文本规则；技能钉版本可以随时单独做。
  - **验收目标（2026-10-26 用户确认）：DataAgent 重启不影响用户会话。** 重启前等待中的问题卡片和计划卡片，重启后仍可回答，回答后接着原来的运行继续，不要求用户重发消息；重启前已完成的对话完整保留工具上下文。下面的 PQ-06 并入这次升级，不单独在现有运行时上做。

### 等待用户时释放运行资源（卡片设计 PQ-06）

- **现状**（2026-10-26）：问题卡片和计划卡片等待期间，Pi Cell 进程和执行槽位一直占着，只暂停总超时和空闲超时。`park` 的计划会一直等待；服务重启后，等待中的运行按现有设计结束为 `suspended`，用户要重新发送消息。
- **要做**：等待时释放进程；用户回答后从事件日志和会话条目恢复，接着执行原来的工具调用。接续不了的，把回答作为新一轮的第一条消息自动发出。需要设计：待处理交互的记录方式、同一批里还没执行的工具、扩展状态（例如计划是否已批准）、避免重复写入。
- **关联**：[卡片设计](design/2026-10-25-dataagent-plan-and-question-cards-design.md) PQ-06；并入上面的运行时迁移与架构升级（迁移设计 MG-07、MG-D4，服务化架构 4.5），作为其验收目标“DataAgent 重启不影响用户会话”的一部分。

### MCP 工具名命名空间

- **现状**：Pi 直接使用 MCP 返回的工具名注册，没有按服务加命名空间，不同 MCP 的同名工具会冲突。插件设计里先用"冲突即拒绝"兜底。
- **要做**：设计按服务加命名空间的方案，同时兼顾 Claude 引擎和已有的技能文档。

### 系统提示落库的后续

- 管理端查看页面（接口 `GET /api/v1/dataagent/tasks/{task_id}/system-prompt` 已有）。
- `da_system_prompt` 中不再被任务引用的记录的定时清理（按"无引用且超过 N 天"）。

### 生产运行拓扑回归

- **现状**（2026-10-27）：真实模型账号已恢复，本地主进程 Pi 问数、MCP 和系统提示落库通过；生产 child-container 沙箱跳转尚未跑。后续在部署环境补测同一身份头、CLI 通道、撤权及任务持久化，不能用本地进程冒烟代替容器跳转。见[主体映射报告](reports/2026-10-26-dataagent-datasource-identity-verification.md)。

## 测试与工程卫生

- `da_agent_draft` 表的排序规则是 `utf8mb4_0900_ai_ci`，与其他 `da_*` 表的 `utf8mb4_unicode_ci` 不一致，以后与其他表做字符串关联时会报错。
- `entity-extraction` 内置 skill 被 `.gitignore` 的 `/dataagent/.claude/skills/*/` 规则忽略，没有被 git 跟踪，`test_every_builtin_skill_is_fully_tracked_by_git` 失败。
- `test_pi_runtime_e2e::test_real_cell_completes_the_protocol_round_trip` 假设环境里没有模型凭据；本机 `dataagent-backend/.env` 有凭据时请求会真实发出，导致失败。测试应显式清空凭据相关的环境变量。
- Pi 测试 `cell.init Bash timeout 2s reaches the real command` 单独运行稳定通过，跑全量时偶发失败，是时序问题。
- `skills/bin/odw-cli` 是一份旧版拷贝，与 `dataagent/.claude/skills/opendataworks-platform-tools/bin/odw-cli` 已经分叉，需要确认是否还在使用，决定删除或合并。
- `tools/dataagent-evals/pi-kernel-spike` 仍使用 Pi `0.85.1`，作为早期验证脚本不再维护，可以考虑归档。
- **DataAgent 前端已有失败**（来源：2026-10-27 本轮验证）：ToolOutputRenderer 的 10 项断言与当前折叠/结果展示不一致，已在未修改 HEAD 副本复现相同 10 项失败；后续单独对齐组件与测试。其余 743 项通过；见[主体映射报告](reports/2026-10-26-dataagent-datasource-identity-verification.md)。

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
