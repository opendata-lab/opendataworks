# Widget 用户行为埋点设计

## 现状

Widget chat 的会话与消息已落库，身份通过 HTTP 头（`X-ODW-Client`、`X-ODW-Website-Id`、`X-ODW-User-Id`/`X-ODW-Visitor-Id`）传递并存储在 `da_agent_topic` 的 `source/website_id/external_user_id/visitor_id` 列。

但 UI 行为（打开/关闭悬浮窗、新建会话、发消息）没有被记录，无法统计 widget 的实际使用情况。

## 问题

- 无法统计 widget 打开率、会话创建率、消息发送量
- 无法区分用户主动输入与建议词点击、外部调用触发等消息来源

## 目标范围

采集以下事件（best-effort，不影响聊天体验）：

| 事件 | 触发时机 |
|------|----------|
| `widget_open` | 点击悬浮按钮展开浮窗；inline 模式 mount 时 |
| `widget_close` | 点击关闭浮窗 |
| `history_open` | 打开历史会话侧边栏 |
| `history_close` | 关闭历史会话侧边栏 |
| `conversation_new` | 点击新建会话 |
| `message_send` | 用户发送消息（**只存元数据**，不存问题原文） |

`message_send` payload：`{ input_source, length, provider_id, model, topic_id }`，`input_source` 区分 `typed`/`suggestion`/`outbound`。

## 数据模型

新表 `da_agent_widget_event`（dataagent schema）：

```sql
id               BIGINT AUTO_INCREMENT PK
event_type       VARCHAR(64)  NOT NULL
source           VARCHAR(32)  NOT NULL DEFAULT 'portal'
website_id       VARCHAR(128) NOT NULL DEFAULT ''
external_user_id VARCHAR(255) NOT NULL DEFAULT ''
visitor_id       VARCHAR(128) NOT NULL DEFAULT ''
agent_id         VARCHAR(64)  NOT NULL DEFAULT ''
topic_id         VARCHAR(64)  NULL
task_id          VARCHAR(64)  NULL
message_id       VARCHAR(64)  NULL
payload_json     JSON         NULL
client_ts        DATETIME(3)  NULL
created_at       DATETIME(3)  NOT NULL DEFAULT NOW(3)
```

索引：`(source, website_id, external_user_id, visitor_id, created_at)`、`(event_type, created_at)`、`(topic_id)`。

## 接口

`POST /api/v1/nl2sql/widget-events`

- 复用 `_request_context(http_request)` 获取 widget 身份（header 非法时已有 400/403）
- 批量上限 50 条/请求
- `event_type` 白名单校验
- `payload_json` 大小上限 4KB
- 身份只取自 header，不信任 body 里的身份字段

## 前端架构

`createWidgetTracker({ apiBaseUrl, headers })` — 独立模块 `widget/tracking.js`：

- 内存队列 + debounce 800ms 批量 flush
- `visibilitychange(hidden)` / `pagehide` 时 keepalive flush
- 全程 try/catch，失败静默
- `destroy()` 时 keepalive flush + 解绑页面事件

tracker 在 `entry.js::installWidget` 内创建，作为 `state.track` 函数挂载到共享状态，由组件调用。

## 权衡

- **身份归一**：身份全部来自 HTTP header，不允许 body 注入，与现有会话创建逻辑一致。
- **best-effort**：任何埋点失败不影响聊天链路。
- **隐私**：`message_send` 只存问题长度和来源，不存问题原文（原文已在 `da_agent_message`）。
- **批量**：debounce + keepalive，减少网络请求，页面卸载时不丢失。
