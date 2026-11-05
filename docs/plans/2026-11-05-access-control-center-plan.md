# 账号与数据源访问控制中心执行计划

- 日期：2026-11-05
- 状态：实施中（PR 1 按人查看与回收、PR 2 服务账号一步完成已完成；PR 3 矩阵定位／可读预览／模板编辑已实现并通过定向验证，待评审合并）
- 关联设计：[访问控制中心设计](../design/2026-11-05-access-control-center-design.md)
- 来源：[统一待办 · 权限中心交互体验](../TODO.md#权限中心交互体验2026-11-05-ux走查)、[快速修复报告](../reports/2026-11-05-permission-ux-quick-fixes.md)
- 受影响栈：前端 `frontend/src/views/settings/**`、`frontend/src/api/{users,permissions}.js`；后端 `backend/api` 的 `PlatformUserManagementController`、`DatasourcePermissionController`、`DorisClusterController`，`backend/core` 的 `PlatformUserManagementService`、`DatasourcePermissionService`。无 Flyway 迁移、无部署变更、不涉及 DataAgent。

## 交付方式

一份设计，拆成 4 个相互独立的 PR，按顺序交付；每个 PR 都是完整可用的功能，不加功能开关或过渡分支。每个 PR 合并前：最小相关测试通过、PR CI 在当前 head 通过、按全局规则做一次只读评审（Gemini 或 Codex），由 Claude 核实并修复。

## PR 1：按人查看与回收权限

1. 后端：`GET /v1/users` 增加 `activeTokenCount`、`lastTokenUsedAt`（`service_account_token.last_used_at` 已存在）与轻量 `accessSummary`，一次批量聚合平台表，不连远端库。
2. 后端：新增 `GET /v1/users/{id}/datasource-access`，返回设计 §5.1 的单人跨源视图（各源可选角色、成员状态、账号、最近失败操作）。
3. 前端：`UserManagement.vue` 拆分「普通用户 / 服务账号」两个页签与表头；新增「数据权限」列；新增 `SubjectAccessDrawer.vue`，按源保存（复用 `PUT /members`）、移除（复用 `DELETE /members`）与「一键回收全部」，逐源显示结果与重试。
4. 前端：停用／删除确认框列出该账号的数据源权限，默认勾选「同时回收」，逐源回收全部成功后才调用停用／删除；任一失败则停止并展示失败源。
5. 后端文案：更新 `PlatformUserManagementService.delete` 的注释与审计说明（回收由前端在删除前完成，删除本身仍不改原生权限）。
6. 验证：后端新增接口的服务层单测（聚合字段、跨源视图、非管理员拒绝）；前端 `UserManagement` 与 `SubjectAccessDrawer` 组件测试（分页签表头、逐源保存部分失败、删除前回收失败不删除）；本地浏览器走一遍入职与离职流程。

## PR 2：服务账号一步完成

1. 新建服务账号后沿用已有行为直接打开 Token 窗口（首个 Token 预填名称，一次点击即签发），不在新建弹窗里再做一套签发表单；签发后展示明文 Token、MCP 端点与 `Authorization: Bearer` 示例及「配置数据权限」（打开 PR 1 的抽屉）。新建普通用户成功后直接打开该用户的数据权限抽屉。
2. `ServiceAccountTokens.vue` 签发成功区域加入 MCP 接入示例与「配置数据权限」入口。
3. `MemberAuthorizationDialog.vue` 服务账号模式支持「新建服务账号」，创建后回到该源授权表单。
4. 验证：组件测试覆盖源内新建服务账号后分配失败时选中已建账号重试（不重复建号）、首个 Token 预填与 MCP 示例；浏览器走一遍两条服务账号流程（只在本地演示源，结束后清理）。

## PR 3：角色矩阵定位、可读预览与模板编辑

实施与验证：[PR 3 报告](../reports/2026-11-05-access-control-matrix-preview.md)；本地 PostgreSQL 16 服务层预览 → 执行通过，未重启现有服务，完整 HTTP／浏览器闭环待补。

1. 后端：`PATCH /roles/{id}` 支持修改 `template`；改为 `READONLY` 时校验已保存授权只含只读动作，否则 400。`preview` 响应增加结构化 `changes`。
2. 前端：`RoleMatrixPanel.vue` 资源类型按钮与数据库下拉显示已授权／待变更计数，「仅看已授权/变更」跨库展示（由 `roleDraftMap` 与 `grants` 合成行，不额外加载其他库表目录）；「改名」改为「编辑角色」；批量按钮改为「按模板勾选」；保存栏显示新增／撤销数。
3. 前端：`RolePreviewDialog.vue` 主体展示新增／撤销／调整表格，SQL 折叠。
4. 验证：后端模板切换与 `changes` 计算单测（含只读回退被拒）；前端矩阵计数、跨库过滤与预览渲染测试；PostgreSQL 16 本地容器核对一次预览 → 执行。

## PR 4：权限诊断

1. 后端：`inspect` 未绑账号或连接失败时返回 200 与 `connectionError`，同时返回 `expectedRoles`、`lastFailedOperation`。这是错误契约变更，同步更新设计 §8 与前端错误处理。
2. 前端：`MemberGrantInspectDialog.vue` 升级为诊断弹窗（告警横幅、搜索、差异过滤、期望 vs 回读对照）；`PermissionMembersPanel.vue` 状态筛选拆为正常／状态异常／执行中／待配置／已停用，角色标签跳转角色、异常标签打开诊断；`PermissionOperationsPanel.vue` 增加关键字、成员／角色与状态筛选。
3. 验证：后端 `inspect` 连接失败与草稿差异单测；前端诊断对照与筛选测试；本地 MySQL 演示源上核对一个真实成员。

## 回滚

每个 PR 独立，可单独 revert；无数据迁移，回滚只恢复代码。PR 4 回滚时前端与后端 `inspect` 契约需一起回退（同一 PR 内）。

## 完成后

每个 PR 合并后在 `docs/TODO.md` 「权限中心交互体验」中把对应条目移入「已完成」并链接 PR／报告；4 个 PR 全部完成后更新本计划状态。
