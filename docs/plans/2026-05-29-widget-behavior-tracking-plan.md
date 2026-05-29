# Widget 用户行为埋点实施计划

对应设计：`docs/design/2026-05-29-widget-behavior-tracking-design.md`

## 任务清单

### 后端

- [x] `alembic/versions/20260529_000014_add_widget_event_log.py` — 新增 `da_agent_widget_event` 表
- [x] `models/schemas.py` — 新增 `WidgetEventItem`、`WidgetEventBatchRequest`、`WidgetEventIngestResponse`
- [x] `core/topic_task_store.py` — 新增常量 `WIDGET_EVENT_TYPES`、`MAX_WIDGET_EVENTS_PER_BATCH`、`MAX_WIDGET_PAYLOAD_BYTES`；新增 `_parse_client_ts()`；新增 `record_widget_events()` 方法
- [x] `api/routes.py` — 新增 `POST /widget-events` 路由，复用 `_request_context`

### 前端

- [x] `api/nl2sql.js` — 新增 `eventApi.recordEvents(events, { keepalive })` 使用 `fetch`（不用 axios，以支持 keepalive）
- [x] `widget/tracking.js` — 新增 `createWidgetTracker`：队列 + debounce flush + page 卸载 keepalive flush
- [x] `widget/entry.js` — `installWidget` 内创建 tracker，挂到 `state.track`，`destroy` 时调用 `tracker.destroy()`
- [x] `widget/OpenDataWorksWidget.vue` — `open/close/toggleHistory/newConversation` 调用 `state.track`；inline 模式 `onMounted` 补一次 `widget_open`
- [x] `widget/WidgetChat.vue` — `send()` 内调用 `state.track('message_send', ...)`；`inputSource` ref 追踪 typed/suggestion/outbound 来源

### 文档

- [x] `docs/design/2026-05-29-widget-behavior-tracking-design.md`
- [x] `docs/plans/2026-05-29-widget-behavior-tracking-plan.md`（本文件）

## 验证计划

1. 后端单测：`pytest` 覆盖 `record_widget_events`（身份落库、白名单、批量上限）与 `POST /widget-events` 契约
2. 前端单测：vitest 覆盖 `tracking.js`（批量 flush、失败静默）
3. 本地冒烟：`alembic upgrade head`；带 widget header 调用 `POST /api/v1/nl2sql/widget-events`；验证表中出现对应行；缺失 header 返回 400/403

## 回滚

- 删除或空跑 migration `20260529_000014`
- 前端 `state.track` 为可选调用（`?.`），移除 tracker 创建代码不影响聊天功能
