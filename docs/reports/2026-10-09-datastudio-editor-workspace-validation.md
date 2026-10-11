# Data Studio 编辑器工作台验证记录

日期：2026-10-09。关联[设计](../design/2026-10-09-datastudio-editor-workspace-design.md)与[实施计划](../plans/2026-10-09-datastudio-editor-workspace-plan.md)。

## 实现范围

已将用户确认的第十四版原型落地到正式 Vue 3 / Element Plus / Sass 前端源码，保留现有组件与 composable 边界。统一三栏布局、字号、边框与标签内容连接方式，并保留目录两种视图、排序、连接/Schema 切换、查询及对象详情各页。导航“Agent问答”改为“智能问数”。Java 后端、DataAgent、API、数据库和部署契约未改。

补充连接与 Schema 绿色激活图标；状态依据连接成功及目录加载缓存，停用连接不显示激活。血缘对象名、关系分组、说明和空态为10px。数据新鲜度成为第五个常驻详情标签。

对象身份与查询执行目标分离；目标跟随历史、存为任务、新建查询、补全与标签恢复。兼容原有 version1 快照；解释使用查询副本，不改写编辑器 SQL。演示执行计划仅存在于 demo mock 层。

## 自动验证

所有前端命令先成功执行仓库 `.nvmrc` 的 `nvm use`，使用 Node20.19.0。

| 检查 | 结果 |
| --- | --- |
| Data Studio 全部测试及 Layout 测试 | 23个文件，186项通过，5.58s |
| 正式生产构建 | 通过，22.73s |
| 独立演示构建 | 通过，22.72s |
| 触及文件 ESLint | 0错误，25项既有警告，包括 console、未使用异常变量和 finally 返回 |
| `git diff --check` | 通过 |

构建仍提示产物分块大小警告。测试覆盖排序保留分组/选中、激活状态、查询连接独立与异步选择竞态、显式空 Schema、执行/解释/取消、查询历史、目标持久化和新查询继承。

日志：`/tmp/odw-studio-implementation-test.log`、`/tmp/odw-studio-implementation-build.log`、`/tmp/odw-studio-implementation-demo-build.log`、`/tmp/odw-studio-implementation-lint.log`。

## 浏览器验证

通过当前正式源码的演示构建，在 `http://127.0.0.1:4174` 驱动实际前端界面。

- 原型与正式实现的9组测量一致：三栏、查询工具栏、SQL正文、对象标题、树对象节点、工作区及结果页签栏的尺寸、字体和配色。1440px时三栏宽约314.59/705.01/400.40px；树10px、工具/正文11px、SQL/标题12px。
- 紧凑节点24px、完整节点36px；切换和排序保留3个展开节点及选中表。连接与 Schema 图标计算颜色为 `rgb(47,145,103)`。
- 执行演示查询返回3行，解释返回4行明确标注演示的计划，原 SQL 不变；连接与 Schema 下拉可打开并选择。
- 标签加号距最后标签4px，增加标签至溢出才显示左右箭头，滚动与关闭可恢复。详情显隐保留当前页。
- 概览、字段、DDL、血缘、新鲜度、引擎配置、访问统计、变更记录均检查；血缘对象名字号10px，新鲜度标签直接可见。
- 1280/1100/840px没有页面横向溢出；窄详情栏可以滚动打开新鲜度。临时 viewport 已重置，临时查询标签已清理，用户原有标签与 SQL 保留。

截图、计算样式对照 JSON 与交互式截图集保存在 `/Users/guoruping/.codex/visualizations/2026/10/09/01a11e91-87d8-7fe0-a559-d09d9cf7e9c9`。主要截图为 `datastudio-implemented-lineage.jpg` 与 `datastudio-implemented-freshness.jpg`；截图集为 `datastudio-implementation-gallery.html`。

## 验证边界

本地未发现 Java API 8080 与 MySQL3316服务监听，因此未运行真实数据库 SQL、真实执行计划或元数据编辑提交联调。演示只有一个连接/Schema，跨连接目标行为由回归测试验证。上述浏览器结果属于实际前端加演示数据的验证，不代表真实后端端到端验证。

## 2026-10-10 分隔条调整

按用户反馈移除工作区标签外框。水平分隔条取消整条灰色背景，悬浮仅显示2px线；两侧分隔条默认1px线，悬浮2px线。6px/5px拖拽命中范围保留。

用户反馈偶发松开后仍跟随鼠标，未现场复现原问题。三个拖拽入口限定左键；mousemove检查左键状态以恢复丢失mouseup的情况，窗口blur结束拖拽。新增12项回归覆盖未按下/右键、正常mouseup、失焦与丢失mouseup后无按键移动，全部通过。Node20.19.0最终演示构建通过（34.08s），diff检查通过。

浏览器确认标签高度34px、标签内容间隙0、三个分隔条背景透明；右栏按住拖动40px后松开，点击其他位置时尺寸未再改变；水平拖动35px后编辑器高度240→275px且拖拽状态结束。当前4174预览已更新。日志：`/tmp/odw-studio-resizer-tests.log`、`/tmp/odw-studio-resizer-final-build.log`。截图：可视化目录中的 `datastudio-resizers-refined.png` 与 `datastudio-resizers-hover.png`。

## 2026-10-10 功能接通与修复

依据[功能接线核查](2026-10-10-datastudio-function-audit.md)执行，设计见设计文档“功能接通（2026-10-10）”。视觉未改。仅修改前端；Java、DataAgent、数据库与部署契约未改。

### 已修

| 项目 | 修复 | 回归 |
| --- | --- | --- |
| 新鲜度演示入口 | 演示环境禁用配置/编辑/保存/删除/立即检查，处理方法同样拦截；页内标明“演示样例”，历史仍可看。mock 删除立即检查样例（写请求统一被拒），历史第一条即概览最新结果，每条满足检查时间−数据时间=数据年龄 | `freshnessPanelDemo.spec.js`（2项）；`mockServerDatastudio.spec.js` 一致性与 POST/PUT/DELETE 拒绝 |
| 新鲜度切表竞态 | 请求序号＋目标表校验，迟到响应丢弃；切表先清空，失败就地显示“新鲜度加载失败”和重试；字段与历史同样丢弃非当前表响应 | 延迟 Promise 复现“A 迟到覆盖 B”“B 失败残留 A”，修复前失败、修复后通过 |
| 分区切表竞态 | 加载中/错误按 tab+表记录，B 不再被 A 的组件级 loading 挡住；A 的迟到结果或错误只落在 A | 修复前 B 未发请求（失败），修复后通过 |
| 引擎配置编辑入口 | 引擎配置页头部直接“编辑”，复用概览的元数据编辑/保存/取消链路与禁用原因；编辑态说明 ALTER 与分区生效范围；分层缺失提示到概览 | 头部入口、未选集群禁用原因、分层提示 |
| 分桶/副本空值被改写（联调新发现） | 未同步的分桶数/副本数为 null 时，`el-input-number :min=1` 把表单值改成 1，任意元数据保存都会发出分桶/副本 ALTER（真实页面实测 bucketNum `null → 1`）。现仅在值已知（分桶另需分桶字段）时给输入框，否则只读说明原因；实测修复后保持为空 | 无分桶字段、分桶数未同步、副本数未同步三种情况 |
| 存为任务 | 传结构化 `queryTarget`（连接 id/名称/Schema，取执行目标）；抽屉显示查询目标及“无映射、不按名称自动匹配”，该入口数据源必填且不预选；描述补充连接名 | `useTableActions.spec.js`；`taskEditDrawerQueryTarget.spec.js`（同名同库候选也不被选中） |

另修正一个基准已存在的失败：`mockServerDatastudio.spec.js` 仍断言旧连接名“README 演示集群”，与设计中的演示改名不符（该文件不在交接时的24个文件内）。

### 待修 / 阻塞

- **门户连接 → Dolphin 调度数据源映射**：代码中不存在任何持久化映射，Dolphin 数据源选项只有 id/名称/类型/库名/备注，无法证明对应关系。按要求未按名称猜测。已在设计中给出显式绑定表方案（`(cluster_id, dolphin_config_id) → datasource_name`），属于契约变更，需用户确认后实施；在此之前只能手动选择。
- 本地 Dolphin（127.0.0.1:12345）未运行，`/v1/dolphin/datasources` 返回空列表，任务保存到调度未联调。
- 本地“Doris”连接实际指向 MySQL 3306：`SHOW PARTITIONS` 报语法错误（页面就地显示，符合预期），真实 Doris 的分区、分桶/副本 ALTER、EXPLAIN 计划未验证。

### 实际测试环境

- Node 20.19.0（`nvm use`），前端依赖复用主检出 `frontend/node_modules`（符号链接，已被忽略）。
- 自动化：`npm --prefix frontend test -- src/views/datastudio/__tests__ src/views/__tests__/Layout.spec.js src/demo src/components/__tests__`：31个文件、237项通过；交接口径（Data Studio + Layout）25个文件、207项通过。生产构建与演示构建（输出到临时目录，未覆盖4174产物）均通过。触及文件 ESLint 0 错误（35 项警告，主要为既有 catch 未使用变量）；`git diff --check` 通过。
- 真实服务：MySQL 8.0.43 容器 `data-portal-mysql`（127.0.0.1:3306，库 `opendataworks`，Flyway 已在 V51，与仓库一致，启动未迁移）；3316 未监听。Redis 6379 在线但本次路径未使用。Java 后端由本工作树 `mvn -o -pl backend -am package` 构建，以 `AUTH_ANONYMOUS_ENABLED=true`（本地匿名 admin 开关）在 8080 运行；前端 Vite 开发服务（非 demo）3017 代理到 8080。验证结束后两者均已停止。
- 真实 API 只读烟测：连接、库、表列表、表详情、字段、新鲜度（未配置）、新鲜度历史、版本、血缘、任务、访问统计均 200；`data-query/execute` 对 `opendataworks.data_table` 执行 SELECT 和 EXPLAIN 成功，`stop` 返回 true（会写入平台查询历史，属正常行为）。元数据中的 `doris_ods` 等库在该 MySQL 中不存在，查询报无权限，属于环境数据。
- 真实页面（正式模式）：目录加载真实连接/库/表；打开 `business_domain` 的引擎配置页直接出现“编辑”，进入编辑显示限制提示，取消恢复；新鲜度页按钮可用并显示真实“尚未配置”；存为任务抽屉显示“README 演示集群（ID 1） / opendataworks”，数据源为空且必填，点击保存被“请选择查询目标对应的调度数据源”“请选择至少一个输出表”阻止，未创建任务；执行表查询返回1行（84ms）。

未执行任何保存/删除/DDL，未写入新鲜度配置，未在业务表执行 DELETE/DROP，未部署、未推送、未提交。

### 未覆盖

- 新鲜度切表竞态在真实环境无法区分（两张表均未配置，且未写配置），由单元回归覆盖。
- 元数据编辑保存、字段编辑保存、建表/删表、智能元数据生成与采纳、任务保存与 Dolphin 发布均未真实提交。
- 演示页面浏览器复验未做；4174 是独立 demo 产物，本次未改动。演示只读由组件与 mock 测试覆盖。
- 任务抽屉在 jsdom 中整表提交校验不触发（表单上下文差异），单元测试锁定 rules 必填标记，提交阻断由真实浏览器验证。
- 控制台既有警告：DataAgent widget 404、TaskEditDrawer 中 `Loading/CircleClose/CircleCheck` 未解析、`el-radio label` 弃用，均非本次引入。

### 修改文件

- `frontend/src/views/datastudio/components/DataStudioRightPanelFreshness.vue`
- `frontend/src/views/datastudio/components/DataStudioRightPanelDoris.vue`
- `frontend/src/views/datastudio/components/DataStudioRightPanel.vue`
- `frontend/src/views/datastudio/composables/useTableMetaEditing.js`
- `frontend/src/views/datastudio/composables/useTableActions.js`
- `frontend/src/views/datastudio/DataStudioNew.vue`
- `frontend/src/components/TaskEditDrawer.vue`
- `frontend/src/demo/mockServer.js`
- 测试：`freshnessPanel.spec.js`、新增 `freshnessPanelDemo.spec.js`、`DataStudioRightPanel.smoke.spec.js`、`useTableMetaEditing.spec.js`、`useTableActions.spec.js`、新增 `components/__tests__/taskEditDrawerQueryTarget.spec.js`、`demo/__tests__/mockServerDatastudio.spec.js`
- 文档：本记录、设计与计划文档（“功能接通”章节）

## 2026-10-11 后续状态

Claude 修改已 review 并合入主检出，新增修复对象连接隔离、新鲜度删除/保存竞态、分区失败重试、任务数据源目录和停止耗时。详见[本轮 review 与验证](2026-10-11-datastudio-review-and-wiring.md)。原有“未修”条目为历史检查时的状态。显式映射、真实 Doris 写路径及 Dolphin 发布仍需后续验证。
