# 数据源权限中心组件拆分验收

日期：2026-11-02。范围：现有 PR #491 的前端维护项，依据[设计第12节](../design/2026-10-26-dataagent-datasource-identity-design.md#12-权限中心前端组件拆分2026-11-02)及[计划第8节](../plans/2026-10-26-dataagent-datasource-identity-plan.md#8-权限中心组件拆分2026-11-02)。

## 结果与模块边界

原 `DatasourcePermissionCenter.vue` 3086行，拆分后603行，只保留数据源上下文、概览／账号目录加载、页签协调和各视图的显式参数。保留 `modelValue`、`cluster`、`clusters` 及原有两个事件；概览与账号目录没有复制到多个状态源。

| 位置（frontend/src/views/settings 下） | 职责 |
| --- | --- |
| components/datasource-permissions/PermissionMembersPanel.vue | 成员列表、筛选和操作入口 |
| components/datasource-permissions/RoleMatrixPanel.vue | 角色列表、资源切换、权限矩阵和草稿保存入口，634行 |
| components/datasource-permissions/PermissionOperationsPanel.vue、PermissionOperationDetail.vue | 授权记录列表、操作结果及详情跳转 |
| components/datasource-permissions/Member*.vue | 授权／新建成员、分配角色、绑定账号及原生权限查看 |
| components/datasource-permissions/ManagementConnectionDialog.vue、Role*.vue | 管理连接、角色编辑、细分动作与执行预览 |
| composables/permissions/usePermissionScope.js | 唯一数据源上下文、共享标签与请求生命周期 |
| composables/permissions/usePermissionMatrix.js、usePermissionRoleActions.js | 资源盘点／草稿与角色创建／预览／执行 |
| composables/permissions/usePermissionMembers.js、usePermissionManagement.js | 成员操作及管理凭据 |
| composables/permissions/usePermissionOperations.js、permissionLabels.js | 记录加载、结果详情及模块常量／纯标签函数 |

共12个功能视图、6个状态composable和1个常量模块；每个视图只接收本功能所需的view-model，API调用保留在对应composable。未增加全局store、事件总线或父组件旧方法代理。原scoped样式随视图迁移，共用规则保留模块内Sass片段；没有调整页面布局与授权术语。

关闭、切源或卸载会清理弹窗及密码草稿，并失效旧请求。盘点、记录、成员回读分别保留序号；角色切换也使旧预览失效，包括切换后返回原角色。已提交的服务器操作不取消、不自动重试；新建用户请求已完成时账号可能已经创建，切源后不会继续把旧表单角色分配到其他数据源。

## 自动验证

所有前端命令先从 `.nvmrc` 成功切换 Node 20.19.0。

- 拆分前权限中心原10项单测通过。
- 拆分后权限中心18项通过，保留原断言，新增实际DOM成员分配／账号绑定／搜索、矩阵预览，以及关闭重开、切源、连续成员回读、删除确认和新建用户中途切源等边界；操作结果跳转授权记录也以真实点击验证。
- 连同数据源管理、账号管理、服务Token及API契约，共5个文件41项通过。
- 本轮全部Vue／JS文件定向ESLint通过，无错误或告警；Prettier检查通过。
- Vite生产构建通过。保留依赖原有Sass legacy-js-api弃用提示与大体积依赖分包提示，未更改依赖或构建策略。

主要命令：

```sh
export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && nvm use
npm --prefix frontend run test -- src/views/settings/__tests__/DatasourcePermissionCenter.spec.js src/views/settings/__tests__/DataSourceManagement.spec.js src/views/settings/__tests__/UserManagement.spec.js src/views/settings/__tests__/ServiceAccountTokens.spec.js src/api/__tests__/usersAndPermissions.spec.js
npm --prefix frontend run build
cd frontend
node_modules/.bin/eslint src/views/settings/components/DatasourcePermissionCenter.vue src/views/settings/components/datasource-permissions src/views/settings/composables/permissions src/views/settings/__tests__/DatasourcePermissionCenter.spec.js
node_modules/.bin/prettier --check src/views/settings/components/DatasourcePermissionCenter.vue src/views/settings/components/datasource-permissions src/views/settings/composables/permissions src/views/settings/__tests__/DatasourcePermissionCenter.spec.js
```

## 浏览器核对与覆盖限制

管理员本机浏览器使用既有MySQL演示源：成员优先页、两条既有绑定、角色空状态、授权记录空状态、账号绑定与管理连接表单正常；绑定表单仍为空密码保留原密码。成员页拆分前后截图比较，布局、间距与操作位置一致。未在浏览器提交凭据、创建角色或更改真实授权；填充矩阵与预览／部分失败使用组件测试覆盖。

点击既有服务账号“实际权限”时，后端返回“实际权限读取失败，请核对账号连接和数据库权限”；新弹窗正常显示错误，没有伪造成功。后端 `DatasourcePermissionService.inspect` 的SQL异常被统一转换为该提示，目前不能仅凭提示确定是账号连接还是管理连接回读权限。该真实源回读未验收通过，需后续核对既有配置和原生错误，已记[统一TODO](../TODO.md)。本轮未改后端或数据库连接配置。

本轮是前端拆分，没有重新运行Java、MCP、真实GRANT／REVOKE及模型对话，也没有重跑Vue全量；既有跨用户SQL草稿和并发测试超时待办继续保留，不以历史验收替代本轮覆盖。

## 交付与回退

更新同主题设计／计划，将组件拆分待办移入已完成，推送现有PR #491。回退只需还原本轮前端与文档提交，无数据库迁移、服务配置或真实授权回退。

后续修复（2026-11-02）：用户要求该问题修通后上线。已确认采集账号读取mysql.role_edges被1142拒绝，改为成员连接自查并通过Java21项、真实MySQL17项及管理员浏览器验收；该项已从待办关闭。详见[实际权限回读修复报告](2026-11-02-native-permission-inspection-verification.md)。
