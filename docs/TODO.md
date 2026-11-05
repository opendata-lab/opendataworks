# 待办清单

记录已确认需要做、但当前不做的事项，按需要逐项完善。

使用约定：

- 新的待办统一追加到这里，不另建零散的 TODO 文件，也不只写在代码注释或会话记录里。
- 每条写清楚：现状、要做什么、来源或关联文档。来源写明发现的日期。
- 开始做某一条时，如果属于中大型变更，先按 AGENTS.md 的流程写 `design/` 和 `plans/`，并在这里附上链接。
- 做完后把这一条移到文末的"已完成"，保留结果链接，不直接删除。

## 权限与数据源




### 权限中心交互体验（2026-11-05 UX走查）

来源：[权限交互快速修复报告](reports/2026-11-05-permission-ux-quick-fixes.md)。快速修复已完成，以下为延后项；已确认按[访问控制中心设计](design/2026-11-05-access-control-center-design.md)与[执行计划](plans/2026-11-05-access-control-center-plan.md)分 4 个 PR 实施（PR 1 按人查看与回收、PR 2 服务账号一步完成、PR 3 矩阵定位／可读预览／模板编辑、PR 4 权限诊断）。

- **数据源列表操作列**：数据源列表操作列平铺6个按钮，「权限配置」不突出，列表也不显示成员／角色数量（设计 §4.2 的「数据权限」列未在 PR 1–4 实施）。
- **诊断逐行对照（2026-11-05 PR 4 评审）**：PR 4 已完成诊断弹窗（阻断横幅、期望权限与原生回读并列、成员状态细分、授权记录筛选）。逐行判断一致／缺失／外部授权需要后端返回结构化原生授权（库、对象、原生权限，保留标识符大小写与引号），并按与下发相同的展开规则（PostgreSQL 库级授权展开到当前表、WRITE 展开为 INSERT/UPDATE/DELETE）比对；当前回读是文本行，前端解析不可靠，故未做。关联[设计 §8](design/2026-11-05-access-control-center-design.md)。

### 权限中心剩余验证与边界

- **PR 3 完整 HTTP／浏览器验证**（2026-11-05）：矩阵、模板编辑与预览已有定向测试，隔离 PostgreSQL 16 服务层预览 → 执行与原生回读通过（平台持久层使用 mock）；按任务约束未重启 8082／3000，未运行完整 HTTP／浏览器闭环。需用新代码部署后补核；见[PR 3 报告](reports/2026-11-05-access-control-matrix-preview.md)。

- **成员部分授权后的角色删除关联（2026-11-04 用户要求精简）**：既有成员失败统一ERROR并刷新页面，不再预写新旧角色并集。若原生GRANT成功而后续失败，新角色可能尚未计入平台memberCount，ERROR状态本身不会阻止该角色删除。当前需先核对授权记录并恢复成员，再删除角色；若后续要自动保护，需另行确定简洁规则。本轮按用户选择不增加保护机制。关联[当前设计](design/2026-11-03-backend-mcp-auth-consolidation-design.md#pr评审后契约修正2026-11-04)与[报告](reports/2026-11-04-backend-pr-review-fixes.md)。


- **托管数据库登录的部署验收（2026-11-04）**：MySQL8／PG16真实自动建号、重试、权限核验及移除已通过；Doris只有SQL契约，需真实环境补验创建／默认角色／撤销／删除。此前Docker Hub超时已在后续评审修复中解决，正式Dockerfile构建通过；当前剩余为Doris真实环境验证。来源：[建号报告](reports/2026-11-04-managed-database-accounts-validation.md)、[评审修复报告](reports/2026-11-04-backend-pr-review-fixes.md)。


- **本轮后端收敛的外部完整链路覆盖（2026-11-03）**：真实 MySQL、Dolphin 3.2.2 与全部 23 个 HTTP MCP handler 已验证；本机当前没有可调用的独立 DataAgent 8900 服务或 Doris 实例，本轮未复跑真实模型最终消息和 PostgreSQL/Doris 权限、DDL 全流程。需在独立 DataAgent 完成 MCP URL/Schema 刷新后验证完整问答及消息持久化，在 PostgreSQL/Doris 环境复跑原生权限与 DDL；生产快照升级、生产代理拓扑也需部署环境验收。没有将这些路径计为本轮通过。来源：[实施报告](reports/2026-11-03-backend-consolidation-validation.md)，关联[主计划](plans/2026-11-03-backend-mcp-auth-consolidation-plan.md)。


- **平台用户与服务账号功能权限模型（RBAC）**（2026-11-03 用户决策登记）：在尚未设计平台级用户功能权限的前提下，23 个 MCP 工具（含工作流/任务查询、SQL 分析、表设计、草稿编辑及执行时重新预检的发布/上线）对已通过认证的服务账号保持可用，不再硬编码拦截为 403（数据源原生库/表/列权限与物理 DDL 映射账号校验仍严格生效）。后续需独立设计平台级功能权限体系（涵盖门户用户与服务账号对工作流、任务、建表、元数据修改等平台资源与 MCP 工具动作的细粒度授权与审计）。关联：[主收敛设计 §2.2](design/2026-11-03-backend-mcp-auth-consolidation-design.md#22-非目标与待办边界out-of-scope)、[历史验证报告](reports/2026-11-03-dataagent-widget-question-verification.md)、[设计11](design/2026-10-26-dataagent-datasource-identity-design.md#service-accounts)。
- **PG／Doris服务Token真实回归**（2026-11-02验证范围）：本轮服务账号Bearer到MCP与原生库的完整验收使用MySQL；PG此前原生角色／问数已验收，但新SERVICE身份与Token链路未在本轮重新建立真实PG环境，Doris仍无实例。需在对应环境复跑服务独立查询、可选人交集、视图、撤销和只读拒绝；当前不将SQL契约及MySQL结果记作这些引擎的真实验收。[报告](reports/2026-11-02-service-accounts-verification.md)。

用户管理和源内多角色中心已实现并完成本机验收，结果移至「已完成」。当前模型以[设计第10节](design/2026-10-26-dataagent-datasource-identity-design.md#implementation-contract)为准，历史单角色／共享角色账号草案不适用。

- **Doris原生验收**（2026-11-02环境缺口）：已实现统一动作和原生角色适配，但本机无真实Doris实例，仅有SQL契约测试。需在内网核验Schema／表／视图授权、Load_priv、角色继承与回读、EXPLAIN拒绝和只读Agent；[本轮报告](reports/2026-11-02-users-and-permissions-verification.md)。
- **跨源实例别名**（2026-10-30设计确认，2026-11-02保留）：同源禁止多个成员复用同一个数据库账号；同物理实例被登记为不同源仍未去重，不能承诺跨sourceId隔离，需另行设计物理实例与原生身份唯一约束。
- **能力扩展**（2026-10-30确认延期）：用户组、用户直接资源授权、字段／行级与脱敏、ABAC、审批、所有权移交、PG按创建者设置未来默认权限、Dolphin身份统一、定时漂移对账及全量直连审计。已有直接授权／外部角色可能扩大范围，不由平台自动撤销。原生按名称授权无法检测两次观测间的同名删除重建，严格代际隔离需DDL审计。见同主题设计第10节与[使用说明](handbook/features/users-and-data-permissions.md)。
- **源软删除端点唯一键**（2026-11-01真实验收发现）：doris_cluster按host/port唯一，软删除仍占用该坐标，导致同端点重建冲突。本轮未修改业务删除语义；需设计唯一约束与源恢复／重建的关联数据规则。测试源清理只对自有临时记录改坐标后软删除，见[报告](reports/2026-11-02-users-and-permissions-verification.md)。

### 主体映射的验证与使用限制

- **MySQL原生角色的转义库名限制（2026-11-01真实库发现）**：MySQL8.0.45／partial_revokes=OFF下，含`_`／`%`的转义库级ROLE授权存在官方Bug110582；不转义会越权，补当前表不能保证未来对象。生产预览明确拒绝该组合，保留表／视图级授权；不改全局安全配置、不静默展开为当前表。后续在修复版本及partial_revokes=ON隔离环境验证后扩展能力，见[设计10.5](design/2026-10-26-dataagent-datasource-identity-design.md#105-实测mysql库名通配符限制2026-11-01)。


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


## 采集账号一致性与局部功能整理

来源：2026-10-29 用户最终确认顶部“数据开发 / 数据治理 / 智能问数 / 管理中心”，左侧组织现有能力；数据地图在治理内含元数据采集/血缘，资产目录/元数据检索暂不实现；任务调度复用当前工作流/任务/监控，不大动业务。用户随后于同日要求开始实施，已交付局部调整。[设计](design/2026-10-29-portal-information-architecture-design.md)、[实施计划](plans/2026-10-29-portal-information-architecture-plan.md)。


- **明确延期的页面**：资产目录、元数据检索尚无独立页面，本次不显示空菜单、不另造实现；后续有需求再按变更大小编写设计/计划。现有 SQL 实时树不直接当作平台资产目录。
- **原业务重构已缩减**：独立表/任务/运行详情、工作流与运维业务拆分、目录权限服务和通用草稿/返回机制不属于本次。仅导航归位，不将这些作为账号修复的前置或必须完成的后续。

此前调研记录的现有能力限制保留如下，仅为后续候选，不扩入当前实施：

- **持久化采集范围与多采集器**（2026-10-29）：自动按源全量、手动库/表选择只对本次生效；若以后需要保存范围或多周期采集器，再设计范围变更与历史/调度语义。
- **字段血缘与影响报告**（2026-10-29）：当前关系模型提供表级关系与 taskId；字段级存储/解析及导出报告需独立需求和设计。
- **完整数据建模**（2026-10-29）：当前主要是业务域/数据域维护；本次只将菜单归为数据建模，页面仍是域管理，不将其当完整维度模型/指标系统。
- **门户与 Dolphin 数据源映射**（2026-10-29）：当前需显式选择调度端数据源；本次不按名称自动映射，未来统一需独立设计绑定。
- **跨调度绑定历史定位**（2026-10-29）：仅 workflowId/数字 instanceId 不能证明切换 Dolphin 后的旧实例归属；完整历史定位需独立执行目标快照设计，本次不新增运行详情。
- **完整运行日志与执行快照**（2026-10-29）：全局监控最近 50 条、平台提交历史不等于全量周期实例，暂无门户原生日志/任意节点重跑/完整运行 SQL 快照契约；本次不扩此能力。

## 测试与工程卫生

- **Data Studio浏览器草稿按用户隔离**（2026-11-02服务账号浏览器验收发现）：管理员退出后，同一浏览器登录临时普通用户，编辑器恢复了上一轮测试的连接ID、表标签与SQL草稿（结果未显示，实际查询仍由后端受限账号校验）。需检查本地持久化键和退出清理，按平台用户隔离标签、SQL、详情缓存，避免共用浏览器串出前一账号的编辑上下文；当前服务账号／Token变更不顺带改编辑器持久化。来源：[本轮报告](reports/2026-11-02-service-accounts-verification.md)。

- **primaryKeyControls单测并发超时**（2026-11-02服务账号最终复核发现）：全量`vitest run`时`primaryKeyControls.spec.js`的「synced=1 applies row-specific primary edit permissions」用时17.8秒超时失败，单独运行5项全过（单项0.7～2.3秒）。需定位挂载开销或等待逻辑，降低用例耗时或拆分，避免全量回归偶发失败；不放宽全局超时掩盖。来源：[本轮报告](reports/2026-11-02-service-accounts-verification.md)。

## 门户内嵌问数的用户身份

- **现有链路验证（2026-11-01）**：管理员及临时普通用户真实登录／目录／SQL通过；固定测试用户＋现有智能体的MCP查询、越权拒绝和真实模型对话通过，已完成订单12单／668.90与直接SQL一致。[本轮报告](reports/2026-11-01-current-identity-smoke.md)。不关闭下方可信身份待办；该项是新功能实施前的历史冒烟，新功能验收另见已完成项。

- **延期范围与本轮选择（2026-11-01历史选择，2026-11-02更新）**：服务账号Bearer Token与明确服务模式本轮已实现，可信自然人委托仍未实施。以下保留历史冒烟依据：用户要求先把当前实现跑通，允许固定测试用户或智能体身份。现有Java和DataAgent实际要求用户＋智能体两种身份，因此本轮优先复用会话用户与智能体，必要时仅在本机测试请求显式指定现有测试用户，不增加智能体独立执行或全局写死用户分支。后续实现参考[Aily自定义MCP签名身份](https://aily.feishu.cn/hc/1u7kleqg_73ahi33a)：MCP连接凭证识别调用方，后端生成并签名的每次请求上下文识别用户，校验智能体绑定、issuer/audience/有效期；门户→DataAgent也必须先验证用户来源，独立登录通过统一登录或明确账号绑定接入，不能按同名用户自动认领。凭证不交给模型，权限每次实时计算；普通用户、管理员及同一智能体跨用户不串权的真实对话验收随该项补齐。[设计9.15](design/2026-10-26-dataagent-datasource-identity-design.md#identity-local-smoke)、[计划第5节](plans/2026-10-26-dataagent-datasource-identity-plan.md#identity-local-smoke-plan)。目前尚未实现，不能将本机固定身份问答通过作为可信身份验收。

- **浏览器上报的用户可被冒充**（2026-10-27 拆分复核时决定先简单化）：门户 widget 把 `/auth/me` 的 `agentUserId` 作为 `X-DataAgent-User-Id` 发给 DataAgent，DataAgent 以此选择数据库账号。登录门户的用户可在浏览器中改成他人标识，按他人的数据库账号权限查数；能直连 DataAgent 8900 端口的人也能伪造。需要改为由门户可信地提供身份；2026-10-29 统一权限设计已选定短期签名身份断言与 DataAgent 验签，不再同时规划 nginx auth_request 后备路径。该修复已按用户2026-11-01要求延期，并非本轮本机功能验收前置；生产可信委托仍需实施[统一权限中心设计](design/2026-10-26-dataagent-datasource-identity-design.md#trusted-identity)的交付前提，尚未实施。见[拆分设计](design/2026-09-14-dataagent-opendataworks-decoupling-design.md)第 5 节。

## 已完成

### 角色矩阵定位、可读预览与模板编辑（2026-11-05，PR 3 待合并）

- **角色矩阵定位**：资源类型与数据库显示已授权／待变更资源计数，支持跨库「仅看已授权/变更」与新增／撤销动作计数；预览提供新增／撤销／调整资源清单，原生 SQL 折叠。
- **只读模板锁定**：编辑角色可显式切换模板，切回 READONLY 前校验已保存授权；矩阵保留只读保护，按模板勾选仅作用于当前列表的支持动作。

来源：2026-11-05 UX 走查；实现、验证与边界见[PR 3 报告](reports/2026-11-05-access-control-matrix-preview.md)及[执行计划](plans/2026-11-05-access-control-center-plan.md#pr-3角色矩阵定位可读预览与模板编辑)。

### PR #493 评审回归修复

2026-11-04修复矩阵参数鉴权、门户MCP代理边界、任务半边血缘、显式导出限额、空arguments、委托查询审计与owner归属，以及失败刷新／APPLYING状态、错误连接指引、创建超时、列标签和文档残留。首次评审修复验证通过；用户随后要求精简TLS验证、URL解析与角色并集逻辑，当前行为和复验结果见[报告](reports/2026-11-04-backend-pr-review-fixes.md)。

### 角色分配自动创建数据库账号

2026-11-04完成用户要求：首次角色分配为USER／SERVICE自动创建独立数据库登录、加密凭据、分配并核验角色；复用同一账号，失败可恢复，保护同名外部账号，移除仅删除核验过的托管登录。保留既有DBA映射。Java615、Vue481、Python13、真实MySQL20及PG11验收通过，本机保留演示成员。见[设计15](design/2026-10-26-dataagent-datasource-identity-design.md#15-分配角色时自动创建数据库登录账号2026-11-03)、[计划11](plans/2026-10-26-dataagent-datasource-identity-plan.md#11-自动创建数据库登录账号2026-11-03)、[报告](reports/2026-11-04-managed-database-accounts-validation.md)。


### Backend 四模块、内置 MCP 与认证收敛

2026-11-03 完成 PR #493 的代码实施：Java 17 / Boot 3.5.7 四模块、统一主体上下文和 HTTP 认证、23 个内置 MCP 工具、原生访问身份守恒、完整行查询预算、平台 Mapper 与显式 Dolphin 目标、历史接口及部署清理。Java 596 项、真实 Dolphin/MySQL 集成 7 项、Vue 480 项、Python 13 项以及真实 HTTP 验收通过；工具/Skill 同步为顶层 camelCase 参数。设计复核 R1～R8、N1～N3、F1～F5 的结果见[实施报告](reports/2026-11-03-backend-consolidation-validation.md)、[主设计](design/2026-11-03-backend-mcp-auth-consolidation-design.md)、[主计划](plans/2026-11-03-backend-mcp-auth-consolidation-plan.md)、[清理设计](design/2026-11-03-backend-legacy-redundancy-cleanup-design.md)、[清理计划](plans/2026-11-03-backend-legacy-redundancy-cleanup-plan.md)。原“代码待实施”条目迁至此处；外部环境剩余验证另列下方待办。


### 废弃智能体身份请求头残留清理

2026-11-03完成用户要求的未使用代码清理：删除X-Agent-Id常量、Controller的X-Agent-Operator绑定、MCP operator上下文及旧测试／注释；业务审计参数继续使用已认证服务账号ID，保留防伪旧头负向回归，开发写接口仍403。Java23项／打包、MCP43项及真实Java／MCP／MySQL22项通过；无账号授权或业务数据变化。来源：2026-11-02用户要求删除并提交、11-03继续，见[设计14](design/2026-10-26-dataagent-datasource-identity-design.md#14-废弃身份请求头残留清理2026-11-02)、[计划10](plans/2026-10-26-dataagent-datasource-identity-plan.md#10-清理旧身份头2026-11-02)、[报告](reports/2026-11-03-legacy-agent-header-cleanup.md)。

### 成员实际权限回读

2026-11-02修复演示源USER／SERVICE实际权限回读失败：原因是旧流程使用采集账号读取mysql.role_edges被1142拒绝，并非成员账号无法登录。现通过已验证成员连接自查，MySQL按已激活角色及真实host展开SHOW GRANTS，补失败步骤／SQLState／错误码；不提升采集权限或回退其他账号。Java定向21项与打包、真实MySQL／HTTP17项及管理员浏览器两个成员回读通过，临时账号／角色／库和绑定已清理，原配置未变。见[设计13](design/2026-10-26-dataagent-datasource-identity-design.md#13-成员原生权限回读修复2026-11-02)、[计划9](plans/2026-10-26-dataagent-datasource-identity-plan.md#9-修通实际权限回读2026-11-02)、[报告](reports/2026-11-02-native-permission-inspection-verification.md)。

### 数据源权限中心组件拆分

2026-11-02依据用户评审建议实施：主组件从3086行减至603行，拆分成员、权限矩阵、记录与弹窗，共12个功能视图；6个composable分别管理上下文与功能状态。保持成员优先布局、API及原生权限语义，补关闭／切源凭据清理、迟到响应和旧预览隔离。原10项断言保留，权限中心现18项、相关5文件41项通过，定向ESLint／Prettier及构建通过，浏览器核对布局和表单。拆分时发现的演示源实际权限回读失败已由下述后续修复完成（见本节「成员实际权限回读」）。见[设计12](design/2026-10-26-dataagent-datasource-identity-design.md#12-权限中心前端组件拆分2026-11-02)、[计划8](plans/2026-10-26-dataagent-datasource-identity-plan.md#8-权限中心组件拆分2026-11-02)、[报告](reports/2026-11-02-permission-center-component-extraction.md)及[PR #491](https://github.com/opendata-lab/opendataworks/pull/491)。

### 平台普通用户与服务账号

2026-11-02完成USER／SERVICE账号管理、V57已有绑定迁移、服务Token摘要与独立撤销／到期／启停、源内平台账号选择和Bearer主体识别。平台权限不再访问DataAgent智能体目录。服务独立查询、可选人交集、真实MySQL／MCP 27项、真实模型12／668.90、管理员／普通用户浏览器登录与查询通过；普通目录激活已改用当前用户账号。前端委托agy本轮无写入，由主会话完成。临时资源清理，保留本机正式接入Token。见[设计11](design/2026-10-26-dataagent-datasource-identity-design.md#service-accounts)、[计划7](plans/2026-10-26-dataagent-datasource-identity-plan.md#7-账号类型与服务token实施2026-11-02)、[报告](reports/2026-11-02-service-accounts-verification.md)。可信人委托、开发写授权和下方已列环境／浏览器草稿缺口不在本次完成范围。

### 平台用户管理与原生多角色权限中心

来源：2026-10-29～30用户要求；2026-11-01～02实际实施。用户管理／登录会话失效／审计，源内成员、角色、账号绑定，MySQL8／PG16／Doris方言，预览／即时执行／回读与部分失败处理，管理密文连接及旧账号入口约束已落地。一个成员账号持多个ROLE，不是一个角色一个共享账号。管理员／普通用户真实登录、原生查询与写入／撤权、双写故障恢复、MCP和真实模型完成本机验收。前端由用户指定agy Gemini Argon完成并经主任务审查。见[设计10](design/2026-10-26-dataagent-datasource-identity-design.md#implementation-contract)、[计划4](plans/2026-10-26-dataagent-datasource-identity-plan.md#unified-access-plan)、[使用说明](handbook/features/users-and-data-permissions.md)、[验收报告](reports/2026-11-02-users-and-permissions-verification.md)。可信签名身份与真实Doris环境验证继续保留上方，不把固定身份本机通过作为生产可信身份验收。

<details>
<summary>2026-10-29～11-01原型阶段与旧草案记录（历史，不是当前实施模型）</summary>

### 平台用户管理缺口

来源：2026-10-30用户反馈无处管理平台成员；代码检查仅有登录／改密与账号绑定用的用户列表，没有用户管理页面或创建用户API。[设计9.14](design/2026-10-26-dataagent-datasource-identity-design.md#platform-user-management-prototype)已补原型的用户管理、创建／编辑／停用和数据源已有／新建用户授权路径。原型已验证，但生产用户管理页面、管理员API、密码哈希／初始化口令处理、停用后的认证与会话失效、审计尚未实施；需在同主题设计／计划收敛后实现，不把原型当实际登录用户创建能力。

<a id="unified-table-access"></a>

### 统一表级权限中心

- **原型交互精简完成（2026-10-30）**：用户明确优先成员分配；统一数据源上下文，成员列表直接分配角色／绑定账号，小表单一次保存，取消多余教学区块与角色内部成员页签，原生细节按需查看。[设计9.13](design/2026-10-26-dataagent-datasource-identity-design.md#prototype-tob-distill)与[图集](design/prototypes/2026-10-30-data-access/README.md)已更新。浏览器模拟和宽窄容器检查通过，生产实现仍待收敛并执行。

- **最新原型交付（2026-10-30）**：用户确认“角色配置资源、成员勾选多个角色、账号在成员详情绑定、只读／读写／管理员作为模板”。[独立原型](design/prototypes/2026-10-30-data-access/README.md)已更新并完成浏览器模拟验证，三个Doris库的角色合并到一个成员账号；生产代码／真实授权仍未实施。当前UI依据为[设计9.12](design/2026-10-26-dataagent-datasource-identity-design.md#prototype-member-role-model)，下方单角色草案不再作为实施依据。

- **最新模型评审纠正（2026-10-30）**：用户提出同源三个Schema角色需要在同一连接合并访问，否定上一轮“角色等于登录账号／同源只分一个角色”简化。该草案与旧实施任务已在文档顶部标记失效，不能照此实施。当前建议为角色映射原生ROLE、用户一个账号持多个角色；无用户直接表授权。账号是否自动创建等尚未定稿，应用尚未实施。以下来源段保留上一轮状态供追溯，以本条和设计评审纠正为准。

来源：2026-10-29用户要求参考Metabase与数据库原生权限设计；2026-10-30先确认统一中文动作、Schema／表／视图、即时双写，随后授权实施并进一步精简为**数据源内置角色／自定义角色绑定数据库账号，用户仅加入角色**。应用尚未实施；原型已按上方最新模型更新，旧生产任务仍需在实施前完整收敛。见[设计第9节](design/2026-10-26-dataagent-datasource-identity-design.md#unified-access-design)、[计划](plans/2026-10-26-dataagent-datasource-identity-plan.md#unified-access-plan)。

- **已授权待实施闭环**：复用用户／源／目录，增加数据源角色、成员、直接绑定角色的资源动作与操作日志；新源初始化数据管理员／只读／读写，账号未配不放行；同用户同源一期一个角色，无用户单独授权。移除旧个人账号写入口与查询回退，不新增组、策略绑定、专属身份或跨源角色中心。人的写能力按原生能力提供，Agent只读。
- **即时双写**：成员变更只写平台；角色资源权限变更请求内GRANT／REVOKE并回读后保存。无后台权限job／全域待同步。部分成功／未知保留批准意图和步骤，不自动重放或重授已撤宽权限；仅当前异常角色临时保护。
- **可信用户身份延期（2026-11-01用户决定）**：先沿用现有用户＋智能体身份头跑通已实现的功能；签名身份、用户委托及按智能体签发Token另做，不作为本轮本机功能冒烟前置。固定测试用户只用于明确标记的本机验证，不写成全局默认或缺身份时的回退；本轮不宣称完成多用户可信隔离。见下方“门户内嵌问数的用户身份”。
- **真实验收**：角色共用账号、成员改配／移除、数据目录／SQL／Agent解析、MySQL8.0.45／PG16／实际Doris逐动作与双写故障恢复待测试；设计更新不等于实现或数据库授权已支持。
- **共享账号边界**（2026-10-30模型决策）：平台记录真实用户，数据库日志通常只识别角色账号；移除平台成员不能撤掉已经发给用户的共享数据库密码。当前只承诺平台内访问，不分发角色密码；个人直连隔离／逐人审计需后续独立需求。
- **明确延期**：多角色合并、用户组、用户级资源授权、个人数据库账号、字段／行级与脱敏、ABAC、审批、账号自动创建／所有权移交、PG按创建者配置未来默认权限、Dolphin身份统一、定时漂移同步和数据库全量直连审计。重复绑定同一物理账号的实例别名需要核实，不能只按sourceId宣称隔离。
- **原生名称授权限制**：删除重建若发生于两次观测间且引擎无可靠代际标识，目录快照无法识别；严格处理需DDL审计，不能承诺同名重建绝不继承旧权限。


</details>


### 采集账号一致性与局部导航整理

- **本地采集/真实问数缺口已补齐**（2026-10-29）：两个 dataagent 目标表补最小只读采集权限并成功落库，原源类型纠正为 MySQL；已选择现有助手、恢复本地 MCP/Skill/widget 接入，真实模型按城市聚合返回 12 单、668.90 元，与 SQL 一致。采集页改白底，独立 DataAgent 的初始化历史覆盖实时消息竞态已修复并补回归。结果见[追加验证](reports/2026-10-29-metadata-account-scope-verification.md#现有数据与真实问答追加验证)。
- **完成**（2026-10-29）：采集范围预览与执行统一用数据源配置账号；新增管理员采集页，集中手动范围、自动 Cron、比对、历史、待删除表。SQL 个人目录继续使用个人映射，采集深链新标签打开并校验目标可见性；单表成功还核对刷新后的平台目录，保留 FAILED/PARTIAL/超时与刷新异常的区别。
- **布局**：顶部数据开发/数据治理/智能问数/管理中心，开发/治理/管理用统一 Element Plus 左菜单，智能问数独立；数据集成移除源配置，管理中心统一维护连接。采集页按 DataWorks 的列表→详情结构组织，详情分基本信息/数据范围/运行记录，保留白底和库表深链。复用现有页面和主要路径，无数据迁移、资产目录占位或工作流重构。
- **验证**：前端定向测试、后端权限/配置/同步测试和真实 MySQL 双账号采集链路通过。见[设计](design/2026-10-29-portal-information-architecture-design.md)、[计划](plans/2026-10-29-portal-information-architecture-plan.md)、[验证报告](reports/2026-10-29-metadata-account-scope-verification.md)、[使用说明](handbook/features/metadata-collection.md)。未实现能力继续保留在上方。

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
