# 后台查看 Widget 会话 — 执行计划

- 日期：2026-06-02
- 关联设计：`docs/design/2026-06-02-widget-session-admin-view-design.md`
- 受影响栈：`dataagent/dataagent-backend`、`frontend`
- 状态：任务 1-4（store/schema/路由/前端 API）已合并并保留；任务 5-6（独立页 + `widget-sessions` Tab）已被 `2026-06-02-chat-v2-session-filter-plan.md` 取代。

## 任务与触达文件

1. store 层 — `dataagent/dataagent-backend/core/topic_task_store.py`
   - 新增 `admin_list_topics(...)`：`source='widget'` 默认 + `website_id`/`external_user_id`/`visitor_id`/`agent_id`/`keyword`/`start`/`end` 过滤 + `page`/`page_size` 分页，返回 `{items,total,page,page_size}`。
   - 消息查看复用既有 `get_topic(context=None)` 与 `list_topic_messages_page(context=None)`，不新增方法。
2. schema — `dataagent/dataagent-backend/models/schemas.py`
   - 新增 `AdminWidgetTopicSummary`（继承 `TopicSummary` 增加隔离维度字段）与 `AdminWidgetTopicPage`。
3. 路由 — `dataagent/dataagent-backend/api/admin_routes.py`
   - 在 `settings_router` 新增 `GET /widget-topics` 与 `GET /widget-topics/{topic_id}/messages`，经 `get_topic_task_store()` 调 store。
4. 前端 API — `frontend/src/api/dataagent.js`
   - 新增 `listWidgetTopics(params)`、`getWidgetTopicMessages(topicId, params)`。
5. 前端页面 — `frontend/src/views/settings/WidgetConversations.vue`（新增，只读筛选表格 + 消息抽屉）。
6. 前端 Tab — `frontend/src/views/intelligence/IntelligentQueryView.vue`
   - `validTabs` 加 `widget-sessions`，新增菜单项与 `<WidgetConversations>` 分支及 import。

## 验证

- 后端：`pytest tests/test_topic_task_store.py tests/test_admin_routes.py`
  - 新增 store 单测断言 admin 查询固定 `source='widget'`、过滤/分页参数顺序正确、不复用隔离谓词。
  - 新增 route 契约测试断言端点、参数透传、404、消息读取 `context=None`。
- 前端：`nvm use` 后 `npx vitest run .../IntelligentQueryView.spec.js` + `npx vite build`。
- 本地端到端 smoke（环境可用时）：用 widget 请求头创建会话 → admin 端点可见 → portal `GET /topics` 仍不可见（隔离未破坏）。

## 回退

- 纯新增（store 方法 / schema / 端点 / 前端页面与 Tab）。回退即移除新增项与 Tab 注册，无数据迁移。
