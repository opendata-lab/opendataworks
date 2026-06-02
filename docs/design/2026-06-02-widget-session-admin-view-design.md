# 后台查看 Widget 会话 — 设计

- 日期：2026-06-02
- 范围：`dataagent/dataagent-backend`、`frontend`
- 类型：中型（新增公开 API 契约 + 新前端页面，跨 dataagent/frontend 层）

## 现状

智能问数的会话存储在 `da_agent_topic`，通过 `source`(`portal`/`widget`) + `website_id` + `external_user_id`/`visitor_id` 做多租户隔离：

- 所有会话接口（`GET/POST/PUT/DELETE /api/v1/nl2sql/topics*`）都经过 `api/routes.py:_request_context()` → `core/topic_task_store.py:_topic_context_predicate()`。
- 门户/后台请求不带 widget 请求头时，context 被判定为 `source='portal'`，SQL 过滤固定为 `COALESCE(source,'portal')='portal'`。
- `api/admin_routes.py` 没有任何会话相关端点。

## 问题

管理后台**完全看不到任何 widget 来源的会话**。这不是数据丢失，而是缺少跨隔离的只读管理能力：portal 视图被 `source='portal'` 过滤掉了所有 `source='widget'` 行，且没有任何 admin 端点能跨站点/用户聚合查看。

## 范围

- 目标：后台新增**独立、只读**的「Widget 会话」审计页面与对应管理端 API，可按站点/外部用户/访客/agent/关键词/时间筛选并查看会话与消息。
- 不在本期范围：删除/清理会话、跨 portal 会话的统一管理、导出。
- 约束：不得改动或复用 per-user 隔离逻辑（`_topic_context_predicate`），不得让普通用户的 portal 会话列表看到 widget 会话。

## 方案

1. store 层新增只读 `admin_list_topics(...)`：默认 `source='widget'`，支持显式、可叠加的过滤与分页，**独立拼装 WHERE**，复用既有 SELECT 与消息统计子查询；不复用隔离谓词。消息查看复用既有 `get_topic(context=None)` 与 `list_topic_messages_page(context=None)`（`_topic_context_predicate(None)` 已返回 `"1 = 1"`）。
2. schema 新增 `AdminWidgetTopicSummary`（在 `TopicSummary` 上暴露 `source`/`website_id`/`external_user_id`/`visitor_id`）与分页包装 `AdminWidgetTopicPage`；消息复用 `TopicMessagePageResponse`。
3. 路由在 `settings_router`（prefix `/api/v1/nl2sql-admin`）新增两个只读端点：`GET /widget-topics`、`GET /widget-topics/{topic_id}/messages`。
4. 前端新增独立页 `views/settings/WidgetConversations.vue`，并在 `IntelligentQueryView.vue` 注册 `widget-sessions` Tab；API 客户端在 `api/dataagent.js` 新增 `listWidgetTopics` / `getWidgetTopicMessages`。

## 接口

- `GET /api/v1/nl2sql-admin/widget-topics?website_id&external_user_id&visitor_id&agent_id&keyword&start&end&page&page_size` → `AdminWidgetTopicPage`
- `GET /api/v1/nl2sql-admin/widget-topics/{topic_id}/messages?page&page_size&order` → `TopicMessagePageResponse`

## 取舍与风险

- admin 端点跨用户只读、不做 owner 校验，与现有 admin 端点一致，依赖网关/主后端的鉴权；本设计显式记录该假设。
- `admin_list_topics` 的 SELECT 与 `list_topics`/`get_topic` 存在与现状一致的 SQL 复制（既有代码已在两处重复），为降低风险沿用该模式，不做共享重构。
- 纯新增，不改动 portal 隔离逻辑，回退成本低。
