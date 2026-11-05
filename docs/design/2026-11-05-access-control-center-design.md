# 账号与数据源访问控制中心交互重构设计

**Date:** 2026-11-05
**Goal:** 打通「平台账号管理」与「数据源权限配置」的信息孤岛，支持从人/服务账号跨数据源配置与一键回收权限、从数据源高效定位角色矩阵与可读预览、在同一视图内完成「平台期望权限 vs 原生回读 vs 失败记录」三合一诊断，并解除只读角色模板不可变更的死锁。
**Tech Stack:** Vue 3 / Vue Router 4 / Element Plus / Sass；Java 17 / Spring Boot 3.5 / MyBatis-Plus。
**Status:** 已确认（2026-11-05），待实施；按计划分 4 个 PR 交付
**Plan:** [执行计划](../plans/2026-11-05-access-control-center-plan.md)
**Origin:** 初稿由 Gemini 4 Argon 根据代码走查起草，Claude 核对代码事实并按用户决定修订（见 §8）。
**Backlog:** [统一待办](../TODO.md#权限中心交互体验2026-11-05-ux走查)
**Related:** [主体数据库身份与统一表级权限中心设计 §10-§15](2026-10-26-dataagent-datasource-identity-design.md#implementation-contract)、[权限中心交互快速修复报告](../reports/2026-11-05-permission-ux-quick-fixes.md)

---

## 1. 现状

2026-11-05 已完成局部快速修复（新建角色不再静默注入整库草稿、角色摘要显示具体库表、授权记录映射用户与服务账号名称、禁止分配空角色、从角色「管理成员」进入授权时预选该角色，见 `docs/reports/2026-11-05-permission-ux-quick-fixes.md`）。当前生产代码结构如下：

| 模块 | 关键文件 | 当前行为 |
| --- | --- | --- |
| 管理中心导航 | `frontend/src/router/navigation.js:23-29`<br>`frontend/src/views/settings/ConfigurationManagement.vue:1-32` | 左侧菜单并列「数据源」（`/settings?tab=datasource`）与「账号管理」（`/settings?tab=users`），两者无路由参数联动或相互跳转入口。 |
| 平台账号管理 | `frontend/src/views/settings/UserManagement.vue:56-248`<br>`frontend/src/views/settings/components/ServiceAccountTokens.vue:1-39`<br>`backend/api/.../PlatformUserManagementController.java:20-85` | 普通用户（`USER`）与服务账号（`SERVICE`）混用同一张静态列 `el-table`；服务账号不显示 `description`（用途）与有效 Token 数；列表无任何数据源权限列或授权入口；创建用户/签发 Token 后流程直接结束；停用（`:506-524`）或删除（`:567-595`）不提示也不联动清理各数据源已创建的托管数据库账号。 |
| 数据源列表 | `frontend/src/views/settings/DataSourceManagement.vue:18-82`<br>`backend/api/.../DorisClusterController.java:53-56` | 表格无成员/角色统计列；单行操作列（`width="430"`）平铺 `编辑 / 测试连接 / 设为默认 / 权限配置 / 元数据采集 / 删除` 6 个按钮，「权限配置」淹没在第 4 项。 |
| 单源权限抽屉与成员面板 | `frontend/src/views/settings/components/DatasourcePermissionCenter.vue:1-109`<br>`.../datasource-permissions/PermissionMembersPanel.vue:1-148`<br>`.../datasource-permissions/MemberAuthorizationDialog.vue:15-97`<br>`.../composables/permissions/usePermissionScope.js:95-114` | 抽屉每次仅加载单一 `sourceId`。成员状态筛选将 `APPLYING`、`ERROR`、`待分配角色`、`待绑定账号` 全部混入 `待配置 (pending)`；角色标签与异常状态标签不可点击；`MemberAuthorizationDialog.vue` 支持就地新建普通用户（`existing/new`），但 `service` 模式仅显示静态提示文案，不支持就地新建服务账号。 |
| 角色资源矩阵与预览 | `.../datasource-permissions/RoleMatrixPanel.vue:49-290`<br>`.../datasource-permissions/RoleEditorDialogs.vue:1-60`<br>`.../datasource-permissions/RolePreviewDialog.vue:1-49`<br>`.../composables/permissions/usePermissionMatrix.js:99-181`<br>`backend/core/.../DatasourcePermissionService.java:126-221` | 矩阵每次仅渲染单一 `currentResourceType`（`SCHEMA/TABLE/VIEW`）与单一 `selectedDatabase`；资源类型按钮与数据库下拉框无已授权/草稿变更计数，无「仅看已授权」开关；`READONLY` 模板角色在前端（`usePermissionMatrix.js:99-107`）与后端（`DatasourcePermissionService.java:170-171`）被永久禁用非只读动作，且 `PATCH /roles/{id}` 仅支持改名、不支持改模板；`RolePreviewDialog.vue:27-34` 仅展示裸 SQL，无结构化增删对比。 |
| 权限回读与操作记录 | `.../datasource-permissions/MemberGrantInspectDialog.vue:1-56`<br>`.../datasource-permissions/PermissionOperationsPanel.vue:1-68`<br>`backend/core/.../DatasourcePermissionService.java:712-750` | `inspect` 仅返回数据库原生回读结果（未绑账号或连接失败时直接抛 400 隐藏上下文），无搜索框，不对比平台角色期望权限、未激活草稿与最近失败操作；`PermissionOperationsPanel.vue` 无按成员、角色、状态的筛选控件。 |

---

## 2. 问题

1. **人（主体）与权限（数据源角色）管理割裂**：管理员在 `UserManagement.vue` 看不到任何账号的数据源角色分布，入职建号与分配数据权限分属两个页面；员工离职或转岗时，管理员必须在 `DataSourceManagement.vue` 逐个切换 N 个数据源排查并移除成员，且删除平台账号不会提示或清理底层数据库已自动创建的 `odw_m_*` 登录账号。
2. **普通用户与服务账号混表，服务账号创建链路断头**：`UserManagement.vue` 用同一套表头展示自然人与服务账号，导致服务账号必填的「用途」与关键的「有效 Token 数」缺失，却展示恒为 `-` 的邮箱和「无管理权限」；从数据源授权弹窗无法就地新建服务账号，而从账号管理新建服务账号并签发 Token 后又无配权入口与 MCP 接入说明。
3. **角色矩阵缺乏全局定位，预览缺乏可读业务语义**：当角色跨多个数据库或同时包含库级、表级、视图级授权时，管理员在 `RoleMatrixPanel.vue` 中没有任何计数角标或「仅看已授权」视图，只能逐库盲翻；预览弹窗 `RolePreviewDialog.vue` 只有原生 SQL 语句，缺乏直观的「新增/撤销/调整」资源权限清单。
4. **「为什么 X 无法访问 Y」排查链路碎片化**：当用户报错无法查表时，管理员需先后查看成员状态、点开无搜索框的「实际权限」原生回读、记住角色名切去「角色」页检查是否处于 `DRAFT` 草稿、再切去无筛选栏的「授权记录」翻找失败 SQL，无法在一处对照「平台期望 vs 数据库实况 vs 最近失败原因」。
5. **`READONLY` 模板形成不可逆死锁**：新建角色已改为从空草稿起步，但 `READONLY` 模板创建后永久锁死写入/DDL 复选框且不允许修改角色模板，想为只读角色补一张表的写权限只能删角色、解绑全部成员后重建；而 `READWRITE` / `ADMIN` 模板既不锁定动作也无法在矩阵中一键按模板勾选。

---

## 3. 范围（含不做的事）

### 本次范围
1. **主体视角跨源权限闭环**：在 `UserManagement.vue` 将「普通用户」与「服务账号」分 Tab 独立表头展示；新增跨数据源权限抽屉 `SubjectAccessDrawer.vue`，支持按人/服务账号一处查看、分配/修改全部数据源角色，以及一键回收全部数据源权限（含停用/删除账号时的联动清理确认）。
2. **入职与服务账号一步式引导**：新建普通用户或服务账号成功后，完成态直接提供「配置数据权限」，打开同一个 `SubjectAccessDrawer.vue` 勾选各数据源角色，不在新建弹窗里再做一套角色选择；新建服务账号弹窗同时签发首枚 Token，并在完成态展示明文 Token 与 MCP 接入示例；数据源内 `MemberAuthorizationDialog.vue` 补齐「新建服务账号」能力。
3. **数据源入口与角色矩阵增强**：`DataSourceManagement.vue` 新增「数据权限」列并收拢低频操作；`RoleMatrixPanel.vue` 为资源类型按钮和数据库下拉项增加已授权/待变更计数角标，新增「仅看已授权/变更」跨库过滤开关；`RolePreviewDialog.vue` 主视图改为结构化「新增/撤销/调整」资源清单，原生 SQL 作为折叠次级信息。
4. **`READONLY` 模板解锁与模板快捷勾选**：支持在「编辑角色」中修改角色模板类型（`PATCH /roles/{id}` 支持更新 `template`），并在矩阵工具栏提供「按模板勾选当前列表」。
5. **三合一权限诊断与操作记录筛选**：将 `MemberGrantInspectDialog.vue` 升级为「权限诊断与实际权限」弹窗，同屏展示顶部异常诊断横幅（含最近失败操作与未激活草稿提醒）+ 可搜索的「平台角色期望权限 vs 数据库原生回读」对照表；补齐 `PermissionMembersPanel.vue` 精确状态筛选、标签点击跳转与 `PermissionOperationsPanel.vue` 多维过滤。

### 不做的事（Out of Scope）
- **不引入跨数据源分布式事务或后台异步队列**：跨多数据源分配或回收角色时，前端按变更的数据源逐源调用现有 `PUT/DELETE /v1/doris-clusters/{sourceId}/permissions/members/{type}/{subject}`，保持每源 `GET_LOCK` 串行与诚实的单源结果反馈。
- **不新建跨数据源全局角色或用户组**：角色仍严格归属单个 `sourceId`（`datasource_access_role`），不改变底层数据模型。
- **不修改数据库迁移（零 Flyway 变更）**：完全复用现有 `sys_user`、`service_account_token`、`datasource_access_role`、`datasource_access_grant`、`datasource_access_member`、`datasource_subject_account` 与 `datasource_permission_operation` 表结构。
- **不涉及平台级功能权限（工作流/任务/建表 RBAC）或可信自然人委托签名**：保留在 `docs/TODO.md` 既有独立待办中。

---

## 4. 方案

### 4.1 信息架构与导航联动

保持管理中心（`/settings`）左侧菜单现有的「数据源」（`?tab=datasource`）与「账号管理」（`?tab=users`）两项，不增加第三套顶层菜单，通过**双向抽屉与 URL Query 深链**打通两个视角：

```text
管理中心 (/settings)
├── 账号管理 (?tab=users)  [主体视角：管人 / 管服务账号 / 跨源配权与离职回收]
│   ├── Tab 1: 普通用户 (USER)      ── 行内展示跨源角色摘要 ──┐
│   ├── Tab 2: 服务账号 (SERVICE)   ── 展示用途、有效Token、跨源角色 ─┼─► [跨源权限抽屉 SubjectAccessDrawer]
│   ├── 弹窗: 新增用户 (完成态「配置数据权限」→ 跨源抽屉)       │     ├─ 按源勾选/修改角色 (复用 PUT /members)
│   ├── 弹窗: 新增服务账号 (建号 + 首枚Token + MCP示例 → 跨源抽屉) │     ├─ 一键回收全部数据源权限 (复用 DELETE /members)
│   └── 弹窗: 管理 Token (含 MCP 配置示例 + 「配置数据权限」入口)  │     ├─ 点「诊断」──► [三合一权限诊断弹窗]
│                                                               │     └─ 点「去配角色」─► 跳转 ?tab=datasource&permSourceId=X&permTab=roles
└── 数据源 (?tab=datasource) [资源视角：管连接 / 管源内角色矩阵 / 管成员与审计]
    ├── 列表新增「数据权限」列 (N 位成员 · M 个角色，异常角标，点击直开权限抽屉)
    └── [数据源权限抽屉 DatasourcePermissionCenter] (支持 URL query 自动打开定位)
        ├── 成员 Tab: 精确状态筛选 / 角色Tag点击跳角色 / 异常Tag点击开诊断 / 授权弹窗支持就地新建服务账号
        ├── 角色 Tab: 资源类型与库下拉带计数角标 / 「仅看已授权/变更」跨库汇总 / 编辑角色可改模板 / 结构化变更预览
        └── 授权记录 Tab: 关键字 + 角色/成员 + 状态筛选
```

遵循 `AGENTS.md` 前端视觉结构规范：所有页面、抽屉与弹窗最多保留两层边框（外层容器/抽屉 + `el-table border` 自身表格框），不在表格外再套 `el-card`；同级 Tab（普通用户/服务账号，成员/角色/授权记录）统一采用「扁平工具栏 + 带边框表格」结构。

---

### 4.2 核心界面设计（ASCII 线框图）

#### 界面 1：账号管理页（`UserManagement.vue` — 普通用户 / 服务账号分表）

```text
+----------------------------------------------------------------------------------------------------+
| 管理普通用户与服务账号，可按账号跨数据源分配或一键回收访问权限                     [+ 新增普通用户] |
|                                                                                                    |
| [ 普通用户 (14) ]  [ 服务账号 · MCP (3) ]                                                          |
|----------------------------------------------------------------------------------------------------|
| [搜索用户名/姓名/邮箱      ] [全部平台角色 v] [全部状态 v] [查询]                  共 14 个普通用户 |
| +------------+----------+----------+--------------------+----------+----------+------------------+ |
| | 账号标识   | 姓名     | 平台角色 | 数据权限 (跨数据源)| 状态     | 最近登录 | 操作             | |
| +------------+----------+----------+--------------------+----------+----------+------------------+ |
| | zhangming  | 张明     | 普通用户 | [生产Doris: 销售只读| [正常]  | 11-05    | 数据权限 编辑    | |
| | (本地账号) |          |          |  财务分析]          |          | 09:30    | 重置密码 停用 删除| |
| |            |          |          | [本地PG: 读写]      |          |          |                  | |
| +------------+----------+----------+--------------------+----------+----------+------------------+ |
| | lisi       | 李四     | 普通用户 | [生产Doris: 异常 !] | [正常]  | 11-04    | 数据权限 编辑    | |
| +------------+----------+----------+--------------------+----------+----------+------------------+ |
+----------------------------------------------------------------------------------------------------+

（切换至「服务账号 · MCP」页签时表头与主操作切换为 [ + 新增服务账号 ]）：
| +------------------+--------------------+-------------------+--------------------+------+--------+ |
| | 服务账号名称/标识| 用途 (description) | 有效 Token        | 数据权限 (跨数据源)| 状态 | 操作   | |
| +------------------+--------------------+-------------------+--------------------+------+--------+ |
| | 智能问数服务     | DataAgent生产只读  | [2 个有效]        | [生产Doris: 只读]  | 正常 |数据权限| |
| | svc_8f92a1...    | 查询与报表分析     | 最近使用 10分钟前 | [本地MySQL: 只读]  |      |Token   | |
| |                  |                    |                   |                    |      |编辑 更多| |
| +------------------+--------------------+-------------------+--------------------+------+--------+ |
```

#### 界面 2：跨数据源权限抽屉（`SubjectAccessDrawer.vue`）与一步式新建服务账号弹窗

```text
+--------------------------------------------------------------------------------------------------+
| 张明 (zhangming) · 普通用户 · 跨数据源权限配置                   [一键回收全部权限] [刷新] [X]   |
|--------------------------------------------------------------------------------------------------|
| 已在 2 / 3 个数据源配置访问权限。首次勾选角色将自动创建托管数据库账号，移除将自动清理托管账号。   |
| +------------------+------------------------+----------------------------+--------+------------+ |
| | 数据源           | 分配角色 (可多选)      | 数据库登录账号             | 状态   | 操作       | |
| +------------------+------------------------+----------------------------+--------+------------+ |
| | 生产 Doris       | [x] 销售只读 (sales.*) | odw_m_1_a8f91c...          | [正常] | [保存变更] | |
| | [DORIS]          | [x] 财务分析 (fin.t1)  | [平台自动创建]             |        | [权限诊断] | |
| |                  | [ ] 数据管理员         |                            |        | [移除访问] | |
| |                  | (+ 去新建/管理源角色)  |                            |        |            | |
| +------------------+------------------------+----------------------------+--------+------------+ |
| | 本地 PostgreSQL  | [x] 公共库读写         | odw_m_2_77c01b...          | [状态  | [重试下发] | |
| | [POSTGRES]       | [ ] 报表只读           | [平台自动创建]             | 异常!] | [权限诊断] | |
| |                  |                        | 最近失败: 角色激活超时     |        | [移除访问] | |
| +------------------+------------------------+----------------------------+--------+------------+ |
| | 测试 MySQL       | (未分配角色)           | 未创建                     | [未授  | [保存变更] | |
| | [MYSQL]          | [ ] 只读  [ ] 读写     |                            | 权]    |            | |
| +------------------+------------------------+----------------------------+--------+------------+ |
+--------------------------------------------------------------------------------------------------+
```

**一步式「新增服务账号」弹窗（内建于 `UserManagement.vue`）**：
```text
+------------------------------------------------------------------------+
| 新增服务账号                                                       [X] |
|------------------------------------------------------------------------|
| 账号名称 *   [ 智能问数生产助手                                      ] |
| 用途说明     [ 供 DataAgent 访问生产库执行只读分析                   ] |
| 签发首枚Token [x] 立即生成 Token   名称 [ 默认生产Token ] 有效期 [90]天|
|------------------------------------------------------------------------|
|                                         [取消]  [创建并生成 Token]     |
+------------------------------------------------------------------------+
（提交成功后弹窗内切换为完成态：展示一次明文 Token + 复制按钮 + MCP Server URL 与
  Authorization: Bearer <token> 接入示例 + [配置数据权限] 按钮，点击打开该账号的 SubjectAccessDrawer）
```

#### 界面 3：数据源权限抽屉 — 角色矩阵与可读变更预览（`RoleMatrixPanel.vue` + `RolePreviewDialog.vue`）

```text
+--------------------------------------------------------------------------------------------------+
| 角色列表 (4) [+新建] | 销售分析员  [编辑角色]                    [管理成员 · 3] [删除角色]       |
| [搜索角色         ]  | 原生角色: odw_1_9c8a...  模板: 只读 (可切读写)  状态: ACTIVE  版本: v2    |
|----------------------|---------------------------------------------------------------------------|
| > 销售分析员   [只读]| 资源类型: [ 数据库 (0) ] [ 表 (3*) ] [ 视图 (1) ]             [权限说明]  |
|   3位成员            | 数据库: [ sales (3项*) v ] [搜索表名... ] [x] 仅看已授权/变更 [按模板勾选]|
|   公共读写     [读写]|---------------------------------------------------------------------------|
|   5位成员            | 资源名称                   | 查询 | 写入 | 创建 | 修改结构 | 删除 | 细分  |
|                      |----------------------------+------+------+------+----------+------+-------|
|                      | orders                     | [x]  | [ ]  |  —   |   [ ]    | [ ]  | 细分  |
|                      | sales.orders · 订单主表    |      |      |      |          |      |       |
|                      | customers         (待新增) | [x]* | [ ]  |  —   |   [ ]    | [ ]  | 细分  |
|                      | sales.customers · 客户表   |      |      |      |          |      |       |
|                      | raw_logs          (待撤销) | [ ]* | [ ]  |  —   |   [ ]    | [ ]  | 细分  |
|                      | analytics.raw_logs (跨库)  |      |      |      |          |      |       |
|                      |---------------------------------------------------------------------------|
|                      | 待生效变更：新增 1 项 · 撤销 1 项（共 3 项已授权） [重置] [预览并保存]    |
+--------------------------------------------------------------------------------------------------+
```
*注：勾选「仅看已授权/变更」时，表格汇总展示当前角色在 `roleDraftMap` 与已保存 `grants` 中涉及的全部库表对象（不限于单一 `selectedDatabase`），彻底消除跨库盲翻。*

**可读变更预览弹窗（`RolePreviewDialog.vue`）**：
```text
+--------------------------------------------------------------------------------+
| 确认权限变更                                                               [X] |
|--------------------------------------------------------------------------------|
| 角色：销售分析员 · 影响 3 位成员                     预览有效期至：14:35:10    |
| ! 库级授权覆盖当前及未来对象；原生账号外部角色可能扩大有效范围。               |
|                                                                                |
| 本次权限变更清单 (2 项资源变更)                                                |
| +----------+-------------------------+---------------------------------------+ |
| | 变更类型 | 资源对象                | 权限动作变化                          | |
| +----------+-------------------------+---------------------------------------+ |
| | [新增]   | 表 sales.customers      | + 查询数据 (SELECT)                   | |
| | [撤销]   | 表 analytics.raw_logs   | - 查询数据 (SELECT)                   | |
| +----------+-------------------------+---------------------------------------+ |
| > 查看待执行原生 SQL (2 条)  [点击展开]                                        |
|--------------------------------------------------------------------------------|
|                                                     [返回修改]  [确认执行]     |
+--------------------------------------------------------------------------------+
```

#### 界面 4：三合一权限诊断弹窗（`MemberGrantInspectDialog.vue`）

```text
+----------------------------------------------------------------------------------------+
| 李四 (lisi) · 生产 Doris · 权限诊断与实际权限                                      [X] |
|----------------------------------------------------------------------------------------|
| 成员状态: [状态异常]  绑定账号: odw_m_1_3f2a... (平台自动创建)  原生身份: 'odw_m_1'@'%'|
|----------------------------------------------------------------------------------------|
| [!] 诊断发现 2 项阻断或差异原因：                                                      |
|  1. 最近一次操作失败 (操作ID #108 · 11-05 10:12)：GRANT Role 失败 (SQLState=42000)     |
|     -> [查看失败步骤详情]  [重新下发成员角色]                                          |
|  2. 所属角色「财务分析」处于 [草稿（待预览激活）]，其配置的 2 项表权限尚未在数据库生效 |
|     -> [去角色页预览并激活]                                                            |
|----------------------------------------------------------------------------------------|
| [搜索库名、表名或权限动作 (如 sales.orders) ]  [全部(4)] [仅差异项(2)] [仅外部授权(1)] |
| +----------------------+-------------------------+---------------------+-------------+ |
| | 资源范围 / 对象      | 平台期望权限 (角色来源) | 数据库实际回读权限  | 诊断状态    | |
| +----------------------+-------------------------+---------------------+-------------+ |
| | 表 sales.orders      | 查询数据                | SELECT              | [一致]      | |
| |                      | 来源: 销售只读 [已生效] | 来源: odw_1_9c8a    |             | |
| +----------------------+-------------------------+---------------------+-------------+ |
| | 表 fin.ledger        | 查询数据                | 未回读到权限        | [草稿未生效]| |
| |                      | 来源: 财务分析 [草稿!]  | —                   |             | |
| +----------------------+-------------------------+---------------------+-------------+ |
| | 表 sales.refunds     | 查询数据、写入数据      | 未回读到权限        | [缺失/未生效| |
| |                      | 来源: 销售读写 [已生效] | —                   |  (下发失败)]| |
| +----------------------+-------------------------+---------------------+-------------+ |
| | 库 test_db.*         | 未配置                  | SELECT, INSERT      | [外部授权]  | |
| |                      | —                       | 来源: DBA 直接授权  |             | |
| +----------------------+-------------------------+---------------------+-------------+ |
|                                                                               [关闭]   |
+----------------------------------------------------------------------------------------+
```

---

### 4.3 关于 `READONLY` 模板锁定的决策建议

- **推荐方案：保留 `READONLY` 模板对写动作的防呆拦截，但允许在「编辑角色」弹窗中显式切换角色的 `template`（`PATCH /roles/{id}` 支持更新 `template`），同时在矩阵工具栏提供「按模板勾选当前列表」。**
- **具体规则**：
  1. `RoleMatrixPanel.vue:53-60` 的「改名」按钮改为「编辑角色」，打开 `RoleEditorDialogs.vue` 时可同时修改「角色名称」与「权限模板（`READONLY / READWRITE / ADMIN / CUSTOM`）」。
  2. 当角色模板为 `READONLY` 时，矩阵继续禁用 `WRITE / CREATE / ALTER / DROP` 复选框并提示“当前为只读模板，如需授予写权限请点「编辑角色」切换模板”，后端 `DatasourcePermissionService.preview` 继续校验 `READONLY` 不含写权限，防止管理员在维护公共只读角色时误勾写权限。
  3. 当管理员需要将某只读角色升级为可写时，在「编辑角色」中将模板改为 `READWRITE` 或 `CUSTOM` 并保存（若从可写角色切换为 `READONLY`，后端校验当前已生效 `grants` 不含非只读动作，否则提示先移除写权限再切换）。
  4. 矩阵工具栏的「批量勾选查询」升级为「按模板勾选当前列表」：`READONLY/CUSTOM` 批量勾选 `QUERY`（及视图的 `SHOW_VIEW`），`READWRITE` 批量勾选 `QUERY + WRITE`，`ADMIN` 批量勾选当前资源支持的全部动作，使「权限模板」名副其实。

---

### 4.4 五大核心任务流步骤对比（改造前 vs 改造后）

#### 任务流 1：入职新员工，使其能查询数据源 Y 中的表 X
- **改造前（10～14 步，跨 2 页面 + 1 抽屉 + 3～4 弹窗）**：在 `UserManagement.vue` 新建用户（4 步）→ 切到 `DataSourceManagement.vue` 打开数据源 Y 权限抽屉（2 步）→ 若需建角色则切角色 Tab 新建、选库表、预览并执行（5 步）→ 跳回成员页打开授权弹窗选人并确认（3 步）。
- **改造后（5 步，1 页面 + 1 弹窗 + 1 抽屉）**：
  1. 在 `账号管理 → 普通用户` 点击「新增普通用户」。
  2. 填写用户名、姓名、初始密码并创建。
  3. 在完成态点击「配置数据权限」，打开该用户的 `SubjectAccessDrawer.vue`。
  4. 在数据源 Y 一行勾选目标角色（角色旁显示 `库.表` 摘要；没有合适角色时点「去新建/管理源角色」跳到数据源 Y 的角色矩阵）。
  5. 点击该行「保存变更」，调用 `PUT /members/USER/{id}` 自动建数据库账号并绑定角色，行内展示结果。

#### 任务流 2：修改某个角色的访问权限范围（含跨库查找与只读角色加写权限）
- **改造前（7～12+ 步，盲翻 N 个库，只读角色需删了重建）**：打开权限抽屉切到「角色」→ 在无计数角标的 `SCHEMA/TABLE/VIEW` 与数据库下拉框间逐个切换寻找已授权对象 → 若为 `READONLY` 角色想加写权限，需先移除所有成员、删除角色、重建 `READWRITE` 角色、重新配置所有库表并加回成员 → 点预览看裸 SQL 后确认。
- **改造后（4～5 步，1 抽屉 + 1 预览弹窗）**：
  1. 在数据源列表点击「数据权限」列的 `N位成员 · M个角色` 直接打开权限抽屉，切到「角色」页签选中目标角色。
  2. 看资源类型按钮角标（如 `表 (3)`）与数据库下拉角标（如 `sales (3)`），或直接勾选「仅看已授权/变更」同屏列出该角色跨所有库的已授权对象（如需解除只读限制，点「编辑角色」将模板改为 `读写`）。
  3. 勾选/取消目标表权限（底部保存栏实时显示 `新增 1 项 · 撤销 1 项`）。
  4. 点「预览并保存」，在 `RolePreviewDialog.vue` 核对结构化「新增/撤销/调整」表格后点「确认执行」。

#### 任务流 3：员工离职或转岗，回收其跨所有数据源的权限
- **改造前（3N + 3 步，跨 2 页面 + N 次切换数据源 + N+1 个确认框）**：在 `UserManagement.vue` 停用/删除看不到任何数据源关联，需去 `DataSourceManagement.vue` 打开权限抽屉，在顶部下拉框逐个切换 N 个数据源、逐个搜索该员工并点击「移除」确认。
- **改造后（2～3 步，1 页面 + 1 抽屉/确认框）**：
  - **仅回收权限（转岗）**：在 `账号管理` 点击该员工行的「数据权限」打开 `SubjectAccessDrawer.vue` → 点击顶部「一键回收全部数据源权限」（或取消勾选指定源角色）→ 确认后逐源调用 `DELETE /members` 撤销角色并删除托管数据库账号（**3 步**）。
  - **离职删号/停用**：在 `账号管理` 点击「删除」或「停用」→ 确认弹窗自动列出该员工在 N 个数据源的成员身份与托管数据库账号，并默认勾选「同时回收各数据源权限」→ 点击确认（**2 步**）。前端先逐源调用 `DELETE /members`；任一源失败时不继续删除或停用账号，弹窗内标出失败源与原因，管理员处理后重试。取消勾选时保持现有行为：只停用或删除平台账号，原生权限不变。

#### 任务流 4：排查“为什么用户 X 无法访问库/表 Y”
- **改造前（8～15+ 步，跨 3 页签 + 2 弹窗，靠记忆对账）**：在成员页看状态 → 点「实际权限」在无搜索框表格里肉眼找表 Y → 记住角色名切去「角色」页看是否为 `DRAFT` 草稿并翻找表 Y → 切去无过滤的「授权记录」翻找失败 SQL。
- **改造后（2～3 步，1 个诊断弹窗内闭环）**：
  1. 在 `账号管理`（跨源抽屉）或数据源成员页点击用户 X 的「权限诊断」（或直接点击红色「状态异常」标签）打开 `MemberGrantInspectDialog.vue`。
  2. 顶部诊断横幅直接展示阻断根因（如“最近一次授权 SQL 失败 #108”或“角色『财务分析』处于草稿未激活”），并在搜索框输入 `Y`，对照表同屏显示表 Y 的「平台角色期望权限 vs 数据库实际回读 vs 诊断状态」。
  3. 点击横幅或行内的「重新下发角色」或「去角色页激活草稿」一键修复。

#### 任务流 5：为 Agent/应用创建服务账号、签发 MCP Token 并配置数据权限
- **改造前（11～13 步，跨 2 页面 + 1 抽屉 + 3 弹窗）**：在 `UserManagement.vue` 新建服务账号 → 在 `ServiceAccountTokens.vue` 签发并复制 Token → 切去 `DataSourceManagement.vue` 打开数据源抽屉 → 点「授权服务账号」选中该账号并分配角色。
- **改造后（5 步，1 页面 + 1 弹窗 + 1 抽屉）**：
  1. 在 `账号管理 → 服务账号` 点击「新增服务账号」。
  2. 填写名称、用途，保持勾选「立即生成首枚 Token」，点击「创建并生成 Token」。
  3. 在完成态复制 Token 明文与 MCP 请求头示例。
  4. 点击「配置数据权限」，在 `SubjectAccessDrawer.vue` 勾选目标数据源角色。
  5. 点击该行「保存变更」。（从数据源抽屉点「授权服务账号 → 新建服务账号」时，创建后直接回到该源的授权表单。）

---

## 5. 接口与文件变更契约

### 5.1 后端接口（优先复用现有接口，仅做最小必要扩展）

| 方法与路径 | 变更类型 | 请求 / 响应契约变更说明 |
| --- | --- | --- |
| `GET /v1/users` | **扩展响应字段** (`PlatformUserManagementController.java:27`) | 入参 `keyword/enabled/accountType` 不变。响应列表每项在原有 `SysUser` 字段基础上增加：<br>- `activeTokenCount` (`Integer`)：未撤销且未过期的 Token 数（仅 `SERVICE` 有效，`USER` 为 0）<br>- `lastTokenUsedAt` (`String`, ISO-8601 UTC)：最近一次 Token 调用时间<br>- `accessSummary` (`List<Map>`)：轻量摘要 `[{sourceId, clusterName, status, roleNames}]`，仅用于列表展示（由平台表一次批量聚合，不连远端库）。角色明细、账号与最近失败操作只由下方单人接口返回。 |
| `GET /v1/users/{id}/datasource-access` | **新增只读接口** (`PlatformUserManagementController.java`) | 管理员权限。返回指定用户/服务账号在全平台所有启用数据源上的权限视图，供 `SubjectAccessDrawer.vue` 渲染：<br>`{userId, accountType, username, nickname, sources: [{sourceId, clusterName, engine, managementConfigured, roles: [{id, name, template, status, grants}], member: {status, revision, roleIds, account: {username, configured, managed}, lastFailedOperation: {operationId, state, message, createdAt}}}]}`。 |
| `PUT /v1/doris-clusters/{sourceId}/permissions/members/{type}/{subject}`<br>`DELETE /v1/doris-clusters/{sourceId}/permissions/members/{type}/{subject}` | **完全复用** (`DatasourcePermissionController.java:28-33`) | 单源与跨源分配角色、移除成员、离职一键回收均复用这两个已有接口，保持每源 `GET_LOCK`、自动创建/清理托管数据库账号与核验逻辑不变。 |
| `GET /v1/doris-clusters` | **扩展响应字段** (`DorisClusterController.java:53`) | 管理员调用时，每项附加 `permissionSummary: {memberCount, roleCount, errorCount, managementConfigured}`（1 次平台表聚合查询），供数据源列表「数据权限」列展示。 |
| `PATCH /v1/doris-clusters/{sourceId}/permissions/roles/{id}` | **扩展支持字段** (`DatasourcePermissionController.java:20`) | 复用 `RoleRequest {name, template}`。`DatasourcePermissionService.renameRole` 支持同时或单独修改 `name` 与 `template`（`READONLY/READWRITE/ADMIN/CUSTOM`）；若目标 `template == 'READONLY'`，校验当前角色已保存 `grants` 仅含只读动作，否则返回 400 提示先移除写权限。 |
| `POST /v1/doris-clusters/{sourceId}/permissions/roles/{id}/preview` | **扩展响应字段** (`DatasourcePermissionController.java:24`) | 响应在原有 `{previewId, sql, warnings, memberCount, expiresAt}` 基础上增加结构化差异列表：<br>`changes: [{changeType: "ADD"\|"REMOVE"\|"UPDATE", resourceType, database, tableName, beforeActions: [], afterActions: [], addedActions: [], removedActions: []}]`。 |
| `GET /v1/doris-clusters/{sourceId}/permissions/members/{type}/{subject}/inspect` | **扩展响应字段与容错** (`DatasourcePermissionController.java:34`) | 当成员未绑账号或原生连接失败时不再直接抛 400 抹掉上下文，而是返回 HTTP 200 诊断对象：<br>`{memberStatus, account: {username, configured, managed}, username, nativeIdentity, connectionError, grants: [], expectedRoles: [{id, name, nativeName, status, revision, grants: []}], lastFailedOperation: {operationId, state, message, createdAt, steps}, notice}`。 |

### 5.2 前端文件清单（标记保留不变 / 保留并修改 / 新增）

| 文件路径 | 处理方式 | 变更职责说明 |
| --- | --- | --- |
| `frontend/src/router/navigation.js` | **保留不变** | 维持管理中心「数据源」与「账号管理」菜单项。 |
| `frontend/src/views/settings/ConfigurationManagement.vue` | **保留不变** | 维持 `/settings?tab=datasource\|users` 容器切换。 |
| `frontend/src/api/users.js` | **保留并修改** | 新增 `getDatasourceAccess(id)` 请求 `GET /v1/users/{id}/datasource-access`。 |
| `frontend/src/api/permissions.js` | **保留不变** | 全部 13 个现有接口方法原样复用。 |
| `frontend/src/views/settings/UserManagement.vue` | **保留并修改** | 1. 顶部 `el-tabs` 拆分「普通用户」与「服务账号」独立表头；<br>2. 新增「数据权限」列、服务账号「用途」与「有效 Token」列；<br>3. 新建成功的完成态提供「配置数据权限」（打开 `SubjectAccessDrawer.vue`），新建服务账号同时签发首枚 Token；<br>4. 停用/删除确认框列出该账号的数据源权限，默认勾选「同时回收」，逐源回收全部成功后才执行停用/删除；<br>5. 挂载 `SubjectAccessDrawer.vue` 与复用 `MemberGrantInspectDialog.vue`。 |
| `frontend/src/views/settings/components/SubjectAccessDrawer.vue` | **新增** | 主体跨数据源权限抽屉：按数据源列出角色多选框、数据库账号状态、单源保存/移除、顶部「一键回收全部权限」、点开权限诊断或跳转源角色配置。 |
| `frontend/src/views/settings/components/ServiceAccountTokens.vue` | **保留并修改** | Token 创建成功区域增加 MCP Server 端点与 `Authorization: Bearer` 配置示例，并增加「去配置数据权限」按钮（切换打开 `SubjectAccessDrawer.vue`）。 |
| `frontend/src/views/settings/DataSourceManagement.vue` | **保留并修改** | 1. 新增「数据权限」列展示 `N 位成员 · M 个角色` 与异常标识；<br>2. 操作列将 `设为默认`、`删除` 收拢进 `el-dropdown`「更多」；<br>3. 监听路由 query `permSourceId`、`permTab`、`roleId` 自动打开 `DatasourcePermissionCenter.vue` 并定位。 |
| `frontend/src/views/settings/components/DatasourcePermissionCenter.vue` | **保留并修改** | 支持接收初始 `initialTab` / `initialRoleId` prop，向子面板透传新增的计数、过滤与诊断属性。 |
| `.../datasource-permissions/PermissionMembersPanel.vue` | **保留并修改** | 状态筛选拆分为 `正常/状态异常/执行中/待配置/已停用`；角色标签点击切换至对应角色；异常标签点击打开诊断；操作列「实际权限」升级为「权限诊断」。 |
| `.../datasource-permissions/MemberAuthorizationDialog.vue` | **保留并修改** | `service` 模式增加「已有服务账号 / 新建服务账号」切换，新建并授权后自动打开 `ServiceAccountTokens.vue` 签发 Token。 |
| `.../datasource-permissions/MemberGrantInspectDialog.vue` | **保留并修改** | 升级为三合一诊断弹窗：顶部异常与草稿告警横幅 + 搜索框与差异过滤 + 「平台角色期望权限 vs 数据库实际回读」对照表。 |
| `.../datasource-permissions/RoleMatrixPanel.vue` | **保留并修改** | 资源类型按钮与数据库下拉项显示已授权/变更计数角标；增加「仅看已授权/变更」跨库过滤开关；「改名」改为「编辑角色」；批量按钮改为「按模板勾选」；底部保存栏显示具体增删项数。 |
| `.../datasource-permissions/RoleEditorDialogs.vue` | **保留并修改** | 重命名弹窗扩展为「编辑角色」，支持修改 `name` 与 `template`。 |
| `.../datasource-permissions/RolePreviewDialog.vue` | **保留并修改** | 顶部主视图展示结构化「新增/撤销/调整」资源权限对比表，原生 SQL 改为折叠展开块。 |
| `.../datasource-permissions/PermissionOperationsPanel.vue` | **保留并修改** | 顶部工具栏增加关键字搜索、角色/成员筛选、状态筛选。 |
| `.../datasource-permissions/{MemberRolesDialog,MemberBindingDialog,RoleRefineDialog,PermissionOperationDetail}.vue` | **保留不变** | 现有角色勾选、DBA 账号手动绑定、DML 细分与操作步骤详情弹窗保持不变。 |
| `.../composables/permissions/{usePermissionScope,usePermissionMatrix,usePermissionRoleActions,usePermissionMembers,usePermissionOperations}.js` | **保留并修改** | 对应实现状态细分、矩阵计数与 `onlyGranted` 跨库行合成、模板批量勾选、角色模板更新、就地新建服务账号及三合一诊断数据组装。 |
| `.../composables/permissions/{usePermissionManagement,permissionLabels}.js` | **保留不变** | 管理连接与基础常量保持不变。 |

---

## 6. 取舍

| 决策点 | 选定方案 | 放弃方案 | 原因 |
| --- | --- | --- | --- |
| 主体视角与数据源视角的关系 | **保留「账号管理」与「数据源」两个现有路由，通过 `SubjectAccessDrawer` 与 URL Query 深链双向互通** | 新建独立的顶层「访问控制中心」路由并废弃数据源内的权限抽屉 | 管理员在维护数据源连接时仍高频需要就地配置该源角色与成员；在现有两页上增强并互通，改动最小且不破坏既有肌肉记忆。 |
| 跨数据源角色保存与离职一键回收的执行方式 | **前端复用单源 `PUT/DELETE /members/{type}/{subject}` 按变更源依次调用并逐源展示结果** | 新增后端跨多数据源批量事务接口 `/v1/users/{id}/batch-permissions` | 不同数据源是异构物理数据库（MySQL/PG/Doris），原生 `GRANT/DROP USER` 无法跨源原子回滚；复用单源接口符合设计 §9.7「不增加跨源原子事务」原则，且单源失败可独立重试。 |
| 角色矩阵跨库定位方式 | **保留「资源类型按钮 + 数据库下拉 + 矩阵表」骨架，补齐计数角标与「仅看已授权/变更」跨库汇总开关** | 推翻矩阵，重做为左侧多层「库→表→视图」全量树形选择器 + 右侧穿梭框 | 全量树在包含数百张表的 Doris/PG 实例上首屏加载重、层级深；在现有矩阵上加角标和「仅看已授权/变更」即可用最小改动解决跨库盲翻。 |
| `READONLY` 模板锁定处理 | **保持 `READONLY` 在矩阵与预览时的只读校验，但在「编辑角色」中允许修改 `template`** | 方案 A：彻底删除 `READONLY` 校验，所有模板随意勾写权限；<br>方案 B：数据库新增 `readonly_locked` 列 | 方案 A 会使公共只读角色失去防误触保护；方案 B 需额外 Flyway 迁移。允许通过 `PATCH /roles/{id}` 显式修改 `template` 零表结构改动且兼顾防呆与灵活性。 |

---

## 7. 风险与应对

1. **跨多数据源一键授权或一键回收部分失败**：
   - **风险**：为用户同时分配或回收 3 个数据源权限时，前 2 个成功、第 3 个因该源管理连接不可用而失败。
   - **应对**：`SubjectAccessDrawer.vue` 与新建弹窗不将多源操作伪装成原子事务，而是逐源更新行状态；成功的源立即标为「已生效/已移除」，失败的源保留在列表中标红展示具体报错与「重试」按钮，避免重复执行已成功的源。
2. **「仅看已授权/变更」跨库模式下编辑未加载库的对象**：
   - **风险**：当角色在库 A 和库 B 均有授权，而 `resourcesState.objects` 当前只加载了库 A 的表元数据时，管理员在「仅看已授权/变更」视图下看到库 B 的已授权表。
   - **应对**：`usePermissionMatrix.js` 从 `roleDraftMap` 与 `selectedRole.grants` 直接合成跨库已授权行（`resourceType + database + tableName` 均已知），动作勾选与取消直接操作 `roleDraftMap`，无需额外请求库 B 全量表目录即可正常增删动作并提交 `preview`。
3. **诊断弹窗在成员账号未绑定或数据库不可达时的展示降级**：
   - **风险**：成员处于 `NEEDS_ACCOUNT` 或数据库密码失效时，原生 `SHOW GRANTS` 无法执行。
   - **应对**：后端 `inspect` 接口捕获连接异常并放入 `connectionError` 字段返回 HTTP 200，同时照常返回平台侧 `expectedRoles` 与 `lastFailedOperation`，前端诊断弹窗顶部展示连接失败原因，下方仍完整列出平台期望权限与最近失败 SQL 步骤。

## 8. 用户决定与评审修订（2026-11-05）

- **删除／停用账号联动回收**：采用默认勾选的联动回收（§4.4 任务流 3）。这改变了 `PlatformUserManagementService.delete` 当前「不改原生权限」的行为说明（该方法注释与审计文案需同步更新）；不勾选时行为不变。
- **只读模板**：保留只读校验，允许在「编辑角色」中修改模板（§4.3）。
- **交付方式**：一份设计，按计划拆成 4 个相互独立的 PR，每个 PR 交付完整功能，不设过渡开关。
- **评审修订**：账号列表只带轻量 `accessSummary`，明细只由单人接口返回；新建账号不再内嵌第二套角色选择，统一在完成态打开跨源抽屉；`inspect` 由 400 改为 200 + `connectionError` 属于接口契约变更，需在实施 PR 中同步前端错误处理与本节说明。
- **PR 2 实施修订**：新建服务账号沿用已有行为直接打开 Token 窗口（首个 Token 预填名称），不在新建弹窗内增加「立即生成 Token」表单，避免两处签发逻辑；新建普通用户后直接打开其数据权限抽屉，而不是先显示完成态再点击。
- **PR 4 实施说明**：`inspect` 接口落地为 HTTP 200 诊断对象，未绑账号或连接/身份失败时返回 `connectionError` 并保留 `expectedRoles` 与 `lastFailedOperation`；最近失败操作同时覆盖成员本身与其所属角色的操作。前端诊断弹窗同屏展示阻断横幅（连接异常、最近失败、草稿角色）、「平台期望权限（来自所属角色）」与「数据库实际回读」两张表并共用搜索，不再逐行计算一致/缺失/外部授权结论：原生回读目前是文本行，按文本解析无法可靠处理 PostgreSQL 库级授权展开、带空格或大小写敏感的标识符及 WRITE 子动作（评审确认），逐行对照改为待办。成员状态筛选细化为正常/状态异常/执行中/待配置/已停用，授权记录增加多维筛选。
- **数据源列表实施说明**：计数改为独立的管理员接口 `GET /v1/doris-clusters/permission-summary`（返回 `sourceId/memberCount/roleCount/errorCount`），不扩展 `GET /v1/doris-clusters`，该列表接口对非管理员也开放；成员数与权限抽屉一致，含已绑定账号但尚无成员记录的主体。点击计数直接打开权限抽屉，未实施 `permSourceId/permTab/roleId` 路由定位（没有跨页跳转入口需要它）。
