# Chat V2 会话筛选与 Widget 会话融合 — 设计

- 日期：2026-06-02
- 范围：`frontend`（`dataagent/dataagent-backend` 仅复用现有端点，不改动）
- 类型：中型（前端跨视图能力整合，复用既有 API 契约，不新增后端接口）
- 关联：取代 `2026-06-02-widget-session-admin-view-design.md` 中的「独立页 + Tab」前端部分

## 现状

后台「看不到 widget 会话」的根因已在上一阶段定位并修复：

- 会话隔离来自 `core/topic_task_store.py:_topic_context_predicate()`，portal context 固定过滤 `COALESCE(source,'portal')='portal'`，结构性排除 `source='widget'` 行。
- 上一阶段已新增**只读、跨隔离**的管理端能力并合并：
  - store：`admin_list_topics(...)`
  - 路由：`GET /api/v1/nl2sql-admin/widget-topics`、`GET /api/v1/nl2sql-admin/widget-topics/{topic_id}/messages`
  - schema：`AdminWidgetTopicSummary` / `AdminWidgetTopicPage`
  - 前端 API：`dataagentApi.listWidgetTopics()` / `dataagentApi.getWidgetTopicMessages()`
  - demo：`mockServer.js` 4 条 widget 演示话题与对应端点
- 前端落地为**独立页** `views/settings/WidgetConversations.vue` + `IntelligentQueryView.vue` 的 `widget-sessions` Tab。

Chat V2（`views/intelligence/NL2SqlChatV2.vue`）的会话侧边栏当前：

- 顶部 agent 选择器（`agentSelectValue`）+「新建」按钮；
- 客户端搜索框（`searchKeyword` → `filteredTopics` computed）；
- 列表通过 `loadTopics()` 调 `topicApi.listTopics({page,page_size,agent_id?})`，选中通过 `selectTopic()` 调 `topicApi.getTopicMessages()`；
- 无来源切换、无状态筛选、无排序、无只读模式。

## 问题

独立的「Widget 会话」页与 Chat V2 是两套割裂的会话浏览体验：管理员要在两个入口间切换，且独立页只是只读表格，无法复用 Chat V2 已有的消息渲染（思考块、SQL、图表）。用户希望**在 Chat V2 内**用来源选择器统一查看门户与 widget 会话，并补齐状态筛选与排序。

## 范围

- 目标：
  - 删除独立页 `WidgetConversations.vue` 与 `widget-sessions` Tab；
  - 在 Chat V2 侧边栏加入「来源选择器（门户 / Widget，一次一种）+ 筛选/排序弹窗」；
  - Widget 来源为只读视图（隐藏新建、禁用输入区），复用 Chat V2 现有消息渲染。
- 不在本期范围：widget 会话的多站点二级筛选（website_id 下拉）、删除/导出会话、portal 与 widget 混合列表。
- 约束：不改动后端隔离逻辑与既有端点；门户会话行为（发送、流式、新建）保持不变。

## 方案

1. **清理独立页**：删除 `WidgetConversations.vue`，并从 `IntelligentQueryView.vue` 移除 `widget-sessions`（`validTabs`、菜单项、`ChatLineSquare` import、组件 import 与分支）。
2. **来源选择器**：Chat V2 侧边栏新增 `sourceMode`（`'portal' | 'widget'`），以两个小 Tab 呈现，一次只看一种来源；`widget` 模式下隐藏「新建」。
3. **筛选/排序弹窗**：用 `el-popover` 承载
   - 用户筛选 `filterUser`（仅 widget 模式）：把每条 widget 会话的 `external_user_id`（登录用户）/ `visitor_id`（匿名访客）折叠为统一 user key（`ext:<id>` / `vis:<id>`）。
     - 选项来源：新增管理端 facet 接口 `GET /v1/nl2sql-admin/widget-users`，按 `kind+user_id` 去重并带会话数 `topic_count`，支持 `keyword` 服务端搜索与 `limit`，下拉用 Element Plus `remote` 远程搜索消费全量用户集，而非仅当前已加载页；
     - 结果过滤：选中后**下沉到服务端**，按 `external_user_id`/`visitor_id` 重新请求 `/widget-topics`，保证跨分页精确，而非仅过滤当前页；
     - 后端：`TopicTaskStore.admin_list_widget_users` 在 `da_agent_topic` 上按用户分组聚合，复用管理端只读、跨用户隔离绕过语义，不重用 `_topic_context_predicate`；
   - 状态筛选 `filterStatus`：全部 / 进行中(`running`) / 失败(`error`) / 已取消(`suspended`) / 完成(`finished`)，映射到 topic 的 `current_task_status`；
   - 排序 `sortOrder`：最近更新(`updated_desc`) / 最近创建(`created_desc`) / 标题(`title_asc`)。
4. **数据加载分流**：`loadSessionList()` 按 `sourceMode` 分流——
   - portal：沿用 `topicApi.listTopics()`；
   - widget：`dataagentApi.listWidgetTopics()`（只读 admin 端点）。
   状态/排序在客户端基于已加载列表统一处理（与现有 `filteredTopics` 客户端搜索一致），避免给两套后端引入不一致的排序契约。
5. **消息加载分流**：`selectSession()` 按 `sourceMode` 选择 `topicApi.getTopicMessages()` 或 `dataagentApi.getWidgetTopicMessages()`，复用同一 `hydrateHistoryMessage()` 渲染。
6. **只读处理**：`widget` 模式下输入区（textarea + 发送）禁用并显示只读提示，发送/新建路径短路。

## 接口（复用，无新增）

- portal 列表：`GET /api/v1/nl2sql/topics`（`topicApi.listTopics`）
- portal 消息：`GET /api/v1/nl2sql/topics/{id}/messages`（`topicApi.getTopicMessages`）
- widget 列表：`GET /api/v1/nl2sql-admin/widget-topics`（`dataagentApi.listWidgetTopics`）
- widget 消息：`GET /api/v1/nl2sql-admin/widget-topics/{id}/messages`（`dataagentApi.getWidgetTopicMessages`）

## 取舍与风险

- **状态/排序放客户端**：当前两端都是单页拉取（`page_size` 50/500），客户端过滤/排序最简单且行为一致；代价是超大会话集分页时筛选只作用于已拉取页。本期会话量级下可接受，未来需要服务端筛选时再下沉。
- **来源选择器一次一种**：避免在同一列表里混合两种隔离语义和两套消息端点，降低只读/可写状态切换的复杂度；代价是无法跨来源对比。
- **删除独立页为破坏性变更**：需同步清理路由 Tab 与测试 stub；后端 API 与 demo 数据保留，回退仅需恢复前端文件。
- **只读保证靠前端**：widget 消息端点本就只读（无发送端点），前端禁用输入仅为体验防呆，不构成安全边界。
