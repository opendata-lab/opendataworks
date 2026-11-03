# 元数据采集账号一致性与局部功能整理实施计划

> Design: [元数据采集账号一致性与局部功能整理设计](../design/2026-10-29-portal-information-architecture-design.md)

**Date:** 2026-10-29
**Goal:** 修复采集身份错位，按用户确认的“数据开发 / 数据治理 / 智能问数 / 管理中心”整理菜单与现有页面挂载；缺失功能不实现。
**Tech Stack:** Vue 3 / Vue Router 4 / Element Plus / Sass；Java 8 / Spring Boot 2.7 / MyBatis-Plus；现有数据库适配器与主体映射。
**Status:** 已实施。用户于 2026-10-29 要求开始，按下列任务完成；实际验证、命令与边界见[报告](../reports/2026-10-29-metadata-account-scope-verification.md)。

## Architecture Summary

- Data Studio 的用户实时目录保持；系统采集固定使用源配置账号。
- 顶部保留数据开发/数据治理/智能问数/管理中心四项，Layout 统一提供开发/治理/管理的 Element Plus 左菜单；智能问数保持独立嵌入页，不加空菜单；地图只作为治理菜单中的分组，不新建地图容器。右侧复用既有 SQL/工作流/DataX/域/血缘/质量/问数/配置页面。
- 后端仅在现有数据源控制器增加范围预览、采集配置两个接口，继续复用同步和历史服务。
- 唯一新增业务路由是 `/metadata-sync`，挂当前 MetadataSync；其他主要路径保留。无数据库迁移、新采集实体/引擎、目录权限服务或详情系统；不改工作流、调度、血缘画布业务、建模与 DataAgent。
- 按下列依赖顺序完成并一次交付，任务顺序不是分批上线方案。

## Task 1: 增加同账号采集范围与隔离的配置更新

**Files:**
- `backend/src/main/java/com/onedata/portal/controller/DorisClusterController.java`
- `backend/src/main/java/com/onedata/portal/service/DorisClusterService.java`
- `backend/src/main/java/com/onedata/portal/service/DorisConnectionService.java`（复用既有系统身份方法；仅必要时补薄封装）
- `backend/src/main/java/com/onedata/portal/dto/MetadataSyncConfigRequest.java`（新增，仅 autoSync/syncCron）
- `backend/src/main/java/com/onedata/portal/controller/DataTableController.java`
- `frontend/src/api/doris.js`

**Steps:**
1. 在已有控制器增加 admin 保护的 `GET /{id}/metadata-scope`，通过 withSystemAccount 调用当前目录读取；有 database 时列该库表，无值时列库，返回设计规定的响应。
2. 明确校验源 ID、源存在/启用状态；异常原样表达为失败，不把无法读取改成成功空范围。不改原 `/databases`、`/tables` 用户身份接口。
3. 增加 `PUT /{id}/metadata-sync-config`，白名单请求字段，按列更新 autoSync/syncCron；复用 Cron 校验、启用时初始化 lastSyncTime 的当前行为。数据库结构不变。
4. 数据源连接保存保留现有采集配置；禁止连接表单的旧值覆盖它。采集保存不能整实体覆盖连接、密码、options、默认标志等。
5. 范围预览、采集配置、现有比对/同步/历史端点均校验 admin；只覆盖本次系统采集链路，不扩写全平台权限框架。

**Expected Result:**
- 预览与执行明确是同一平台连接账号；用户目录与查询不受影响。
- 两个分区保存互不覆盖；已有定时任务仍能读取相同字段工作。

## Task 2: 复用现有采集组件并搬迁控件

**Files:**
- `frontend/src/views/settings/MetadataSync.vue`
- `frontend/src/views/settings/DataSourceManagement.vue`
- `frontend/src/api/doris.js`
- `frontend/src/api/table.js`（复用；仅有必要时补参数传递）

**Steps:**
1. MetadataSync 增加明确的数据源选择、只读账号/引擎信息以及全源/单库/单表的本次范围；选项调用新的系统身份预览接口。
2. 深链目标不可见时保留库表名并说明不可见或不存在，禁用本次执行。切换源、修改连接或手动刷新时重新校验，不回退默认源或用户账号。
3. 从 DataSourceManagement 搬入 autoSync/Cron、现有比对/同步、历史列表及详情弹窗、待删除表管理；保留原动作行为，不增加新的详情路由。
4. 数据源页面留下连接/账号映射、默认/状态/测试连接及备份；原“元数据/同步历史”操作改为去采集页的定位链接，创建后提供链接而不自动运行。
5. 自动采集范围准确显示为全源，手动库表仅对本次生效。配置保存与开始采集分开，沿用同步请求与历史，不新增后台作业/停止按钮。
6. 所有原有采集相关操作迁入数据治理 → 数据地图 → 元数据采集唯一界面；组件文件继续使用原 MetadataSync.vue 路径，减少无关搬迁。避免单个内容组件外再套多层卡片，兄弟分区保持当前控制台样式。

**Expected Result:**
- 用户在执行前可以看到采集账号的真实范围，权限错位不再等到失败才暴露。
- 数据源与采集是两个功能分区，现有历史、待删除表、备份没有遗漏。

## Task 3: 按顶部四项整理导航，复用页面与元数据入口

**Files:**
- `frontend/src/views/Layout.vue`
- `frontend/src/router/navigation.js`（新增，仅模块/菜单/已有路由映射）
- `frontend/src/router/index.js`
- `frontend/src/router/routeWarmup.js`
- `frontend/src/views/settings/ConfigurationManagement.vue`
- `frontend/src/views/integration/DataIntegration.vue`
- `frontend/src/views/datastudio/components/DataStudioRightPanel.vue`
- `frontend/src/views/datastudio/composables/useTableActions.js`
- `frontend/src/components/TaskEditDrawer.vue`

**Steps:**
1. 将 Layout 顶部平铺功能菜单整理为数据开发、数据治理、智能问数、管理中心四项，保持现有首页入口。用一份 navigation 配置和 Element Plus 侧栏组织叶子；当前 route/query 派生模块、菜单选中与地图展开，不新建状态存储或多份模块壳。
2. 开发侧栏为数据开发（SQL）、数据集成（DataX）、数据建模（域管理）、任务调度（现有工作流模块）。保留 `/datastudio`、`/integration`、`/domains`、`/workflows` 与详情路径；tasks/monitor 原 Tab 和旧重定向不变，详情也高亮任务调度。不得因菜单整理拆工作流/任务/监控实现。
3. 治理侧栏为数据地图子菜单、数据质量，智能问数保留顶部独立入口且不在治理重复；地图实际只显示元数据采集和数据血缘。新增 `/metadata-sync` 挂当前 MetadataSync；保持 `/lineage`、`/inspection`、`/intelligent-query` 路径、参数和业务实现。资产目录/元数据检索尚无独立页面，本次不显示菜单、不建占位或假检索页。
4. 管理侧栏为数据源、调度引擎、MinIO 管理、智能助手，映射 `/settings?tab=datasource|dolphin|minio|agent`。ConfigurationManagement 删除自身导航，仅保留现有组件渲染；顶部进入管理中心默认数据源，显式旧 tab 参数继续可用。“调度引擎”仍只有 Dolphin 配置，不扩多引擎框架。
5. DataIntegration 移除旧数据源管理 Tab 和自身顶栏/单项侧栏，直接复用 DataX 任务表；不放管理中心替代链接。开发/治理/管理的左菜单只在 Layout 提供一次；智能问数保持原页面宽度，不套空侧栏，SQL 对象树和任务调度业务 Tab 不作为模块导航删除。
6. 数据源单源入口、SQL 与任务抽屉的采集链接统一 `/metadata-sync?sourceId=...&database=...&tableName=...`。开发中用 router.resolve 新标签打开，保留原表单，回原页手动刷新/分析；不共享 DataX 执行目标、不新增跨窗口机制。未实施的 `/data-map` 等方案地址不加兼容分支。
7. 菜单和登录/demo 路由门禁一起核对；新增系统采集仅 admin，既有对象权限不因归组而放宽。直接链接、刷新/前后退恢复正确模块与菜单；页面标题和助手提示按当前叶子选择，不能只显示顶层模块名。仅预热可用路由，不请求尚不存在的资产功能。
8. 元数据采集页检查 SUCCESS/PARTIAL/FAILED；比对含 errors 不提示结构一致，成功后刷新失败独立反馈，超时引导历史不自动重试。保留此前工作区已有提示修复，清理废弃的直接采集注入，不新增只看 HTTP 成功的分支。

**Expected Result:**
- 用户给定的顶部四项与左侧层级可直接使用，右侧都是当前真实能力；不存在双重模块菜单或缺失功能占位。
- 原详情/画布/标签/任务调度行为保持；只有采集身份、采集入口与导航挂载发生必要改动。

## Task 4: 聚焦验证与文档回写

**Files:**
- `backend/src/test/java/com/onedata/portal/controller/DorisClusterControllerTest.java`
- `backend/src/test/java/com/onedata/portal/controller/MetadataCollectionAuthorizationTest.java`（真实权限切面）
- `backend/src/test/java/com/onedata/portal/service/DorisClusterServiceTest.java`
- `backend/src/test/java/com/onedata/portal/service/DorisMetadataSyncServiceTest.java`（复跑既有状态/不可见对象用例）
- `frontend/src/views/datastudio/__tests__/useTableActions.spec.js`
- `frontend/src/views/datastudio/__tests__/DataStudioRightPanel.smoke.spec.js`
- `frontend/src/views/settings/__tests__/useMetadataCollection.spec.js`（新增，采集范围与状态逻辑）
- `frontend/src/router/__tests__/navigation.spec.js`（新增，仅分组映射/深链/缺失能力边界）
- `frontend/src/views/__tests__/Layout.navigation.spec.js`（新增，模块切换与路由高亮）
- `frontend/src/router/__tests__/routerLazyViews.spec.js`
- `frontend/src/router/__tests__/routeWarmup.spec.js`
- `frontend/src/components/__tests__/taskEditDrawerQueryTarget.spec.js`（现有入口回归；必要时补链接断言）
- `docs/handbook/features/metadata-collection.md`（实施时新增简短操作说明）
- `docs/reports/2026-10-29-metadata-account-scope-verification.md`
- `docs/TODO.md`

**Steps:**
1. 后端测试断言范围预览使用系统身份、普通用户拒绝、无对象/源不可用错误；配置更新只改采集列且保留启用触发基准。执行端点权限测试直接请求接口，不只测按钮隐藏。
2. 前端测试锁定目标不可见禁止执行、不同源不串范围、状态反馈、入口预选与新标签地址；同时验证顶部四项/地图子菜单高亮及智能问数独立路由、设置 query、详情及浏览器前后退。确认没有未实现资产菜单、双重侧栏或集成源配置入口，复跑现有目录/任务执行目标规则。
3. 用真实测试数据库完成下方双账号冒烟，断言库表可见性、实际采集结果及历史落库；不以 mock 成功代替。
4. 按设计路由表逐项核对所有现有页面可达，任务调度原三个 Tab、工作流详情、血缘参数和问数嵌入保持；1280px/窄窗检查可收起侧栏、画布宽度与键盘访问。保留备份/待删除表/历史，不增加工作流发布/版本/实例详情业务改造。
5. 更新账号含义与操作说明；精确记录命令、通过/失败/跳过数量和缺环境覆盖，完成后将本次待办移入已完成。

**Expected Result:**
- 账号错位场景有真实验证，页面整理没有扩大权限或改变自动采集语义。

## Verification

### 实施检查

已核对日期/slug/目录、相互链接、已有路径及本次范围。简单控件搬迁由浏览器验证，不为其编写镜像单测；新增采集逻辑与导航交互有聚焦测试。

### 实施命令

前端命令前必须成功执行：

```sh
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm use
```

缺版本先 `nvm install`，然后重新成功 `nvm use`。测试文件建立后运行：

```sh
npm --prefix frontend test -- src/views/settings/__tests__/useMetadataCollection.spec.js src/router/__tests__/navigation.spec.js src/views/__tests__/Layout.navigation.spec.js src/router/__tests__/routerLazyViews.spec.js src/router/__tests__/routeWarmup.spec.js src/views/datastudio/__tests__/useTableActions.spec.js src/views/datastudio/__tests__/DataStudioRightPanel.smoke.spec.js src/views/datastudio/__tests__/useCatalogTree.spec.js src/components/__tests__/taskEditDrawerQueryTarget.spec.js
npm --prefix frontend run build
```

后端确认 JDK/Maven 符合 Java 8 目标后，在仓库根目录运行：

```sh
mvn -pl backend -am -Dmaven.compiler.useIncrementalCompilation=false -Dtest=MetadataCollectionAuthorizationTest,DorisClusterControllerTest,DorisClusterServiceTest,DorisConnectionServiceTest,DataTableMetadataSyncServiceTest,DorisMetadataSyncServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

新增权限端点的聚焦测试应放入上述既有测试或在报告附加实际新类名。若产生 mapper SQL 合同测试，按仓库规则反射生产 SQL 并用 Java 8 兼容 DuckDB 0.10.x 执行；不为文案/控件搬迁编写镜像测试。

### 双账号真实冒烟

1. 使用本地测试库与非敏感临时表，复用既有用户账号 U（可见 A/B）与采集账号 C（仅见 A），创建临时 MySQL 数据源并复制既有个人映射。资源准备与清理独立于测试源代码，不修改真实业务表权限。
2. 用户映射 U、平台连接 C：Data Studio 能浏览 A/B，采集预览仅有 A；B 的库表深链保留目标并拒绝执行，不自动换 U/root。
3. 在 C 已有权限的 A 中创建临时表并按表采集成功；核对平台表/字段、响应业务状态与 metadata_sync_history，确认执行使用 C。
4. 测试目标不可见、C 密码错误、源停用，以及可控的部分失败；检查 FAILED/PARTIAL 不显示成功。成功后的列表刷新失败只改变刷新反馈。
5. 普通用户直接调用范围、配置、同步端点应拒绝；U 原查询仍可用。配置保存互不覆盖，原自动 Cron/启用基准保留。
6. 从 SQL/任务抽屉新标签进入 `/metadata-sync`，原标签 SQL 与任务表单完整；采集后手动刷新/分析。按顶部四项切换，直接打开旧工作流详情、tasks/monitor Tab、血缘中心表和各设置 query，再刷新/前后退；检查模块菜单一致且无双重导航。资产目录/检索不显示，DataX、质量、问数、管理配置、历史/待删除表/备份未丢失。

本次不改工作流发布或调度链路，因此不要求为这个局部修复搭建新的多节点工作流。环境无法启动时明确缺少真实双账号全流程覆盖，并将阻塞写入 TODO。

## Rollout / Backout

前后端配套一次发布；无数据库迁移。原 autoSync/Cron、同步历史、用户映射保持原存储，不批量改账号、不自动扩权、不重跑全部采集。

回退恢复前后端旧版本；运行期间用户合法修改过的账号/Cron 是业务配置，不因版本回退自动覆盖。仅在确需恢复测试配置时使用变更前备份并核对目标。

若实施发现需要独立资产详情、全局权限改造或新的采集数据模型，先停止扩大本次范围，单独记录需求；不得将其作为解决这次账号错位的前置条件。

## 追加任务：现有数据采集与真实问答

1. 将采集内容区改为白底；完成顶部居中反馈并复验构建与实际布局。
2. 检查原源配置、个人映射与现有数据库权限；保存变更前信息，为 `dataagent.aq_chat_message` / `dataagent.alembic_version` 补只读采集权限，纠正原连接 MySQL 类型。预览后按表采集，核对平台表/字段与历史；补采已有 `xinghe_retail` 六张样例表的字段，供真实问数检索使用，不读取聊天消息业务明细。
3. 在独立 `../opendataagent` 中复用 Python 3.13 虚拟环境、已有 MySQL/Redis 和模型配置启动本地服务，不修改其已有未提交改动。按部署/认证契约检查 MCP、Skill、已发布助手；门户显式保存问数助手 ID。
4. 通过门户实际入口提一个现有非敏感业务数据的聚合问题，核对预期 SQL 结果、任务状态、真实运行时记录、事件和最终消息及浏览器渲染。保留必要配置与本地服务供使用，清理纯测试会话；记录实际结果并更新待办。
5. 追加验证发现独立 DataAgent 初始化历史请求覆盖正在运行的消息：只在其共享前端对话逻辑中使用已有运行版本与当前话题拒绝迟到响应，补竞态回归；不改 API/运行时/持久化。重建 widget 后从门户真实对话补验。

## 追加任务：参考 DataWorks 组织采集界面

1. 在既有 MetadataSync.vue 中增加无 sourceId 的列表视图，复用已加载的数据源，不新增后端聚合请求；名称/账号搜索、类型过滤，源配置保持管理中心唯一入口。
2. 有 sourceId 时展示详情头和基本信息/数据范围/运行记录三个页签。移动原周期表单与历史表格，复用原采集、差异、历史明细和待删除表逻辑；保留源/库/表深链以及运行中路由保护。
3. 给历史加载增加当前源/请求校验及明确的错误反馈，避免快速切换后显示旧源历史；失败时不表现成“没有记录”。
4. 聚焦组件交互验证列表→详情→运行记录、返回、库表深链、历史异常与切换；复跑采集 composable 和菜单测试，按 .nvmrc 执行构建，真实浏览器检查白底、页签、历史与窄屏。
5. 更新使用说明与本次报告。无数据变更，回退仅恢复该前端视图；不改变已保存采集配置。
