# Intelligent Query Widget Design

## Current State

OpenDataWorks exposes the intelligent-query UI as a portal page under `frontend/src/views/intelligence`.
The page talks to DataAgent through `/api/v1/nl2sql/*` for topics, tasks, messages, SSE events, and cancellation, and reads provider/model settings from `/api/v1/nl2sql-admin/settings`.

This is suitable for the portal, but not for embedding in other systems. A website widget must avoid admin APIs, must not expose the portal's global topic list, and must isolate embedded conversations by the host project and user.

## Problem

External Vue + Spring Boot projects need to embed intelligent query with a single script tag after their frontend has loaded the current user. The desired integration style is similar to kapa's website widget:

```html
<script
  src="https://odw.example.com/widget/opendataworks-widget.bundle.js"
  data-display-mode="floating"
  data-website-id="your-project"
  data-agent-id="agent_widget"
  data-user-id="USER_123"
  data-project-name="Your Project"
  data-project-color="#4A90A4"
  data-api-base-url="https://odw.example.com"
></script>
```

The runtime API should remain unified where possible. Widget requests should use the existing `/api/v1/nl2sql/*` endpoints with additional context headers instead of duplicating topic/task routes under a separate `/widget/*` tree.

Some host systems also need to place intelligent query inside a normal application menu page. For that case the same bundle supports an inline mode:

```html
<div id="odw-intelligent-query" style="height: 100vh"></div>
<script
  src="https://odw.example.com/widget/opendataworks-widget.bundle.js"
  data-display-mode="inline"
  data-container-id="odw-intelligent-query"
  data-website-id="your-project"
  data-agent-id="agent_widget"
  data-user-id="USER_123"
  data-project-name="Your Project"
  data-project-color="#4A90A4"
  data-api-base-url="https://odw.example.com"
></script>
```

## Scope

In scope:

- Frontend widget bundle and embed API.
- DataAgent runtime context extraction, runtime config endpoint, and topic/task ownership checks.
- DataAgent schema migration for widget ownership metadata.
- Frontend build and static serving changes for the widget bundle.
- Tests and documentation for the integration path.

Out of scope:

- Main Java backend changes.
- Public anonymous widget access.
- SSO or signed identity tokens.
- Exposing Skills, model management, or portal navigation in the widget.

## Solution

### Widget Runtime

The widget is a separate frontend entrypoint under `frontend/src/widget`. It supports two integration paths:
1. **Auto-mounting**: Reads configuration from the loading script's `data-*` attributes. To prevent premature mounting, the script tag must contain either `data-website-id` or `data-agent-id` to trigger auto-installation. If these attributes are missing, the script serves strictly as a library loader.
2. **Programmatic Multi-instance API**: Exposes `window.OpenDataWorksWidget.installWidget(config)` to programmatically create and mount one or more widget instances. This enables different instances (e.g., an inline modal widget and a floating bottom-right widget) to co-exist on the same page.

The global registry manages active instances:
- Each instance receives a unique `instanceId` (e.g. `odw_1`, `odw_2`).
- Users can retrieve instances via `window.OpenDataWorksWidget.getInstance(id)` or `getInstances()`.
- Legacy single-instance control methods (e.g., `window.OpenDataWorksWidget.open()`, `sendMessage()`) proxy calls to the last-installed instance (`_lastController`) for backward compatibility.
- Script stylesheet loading is enhanced to support programmatic configurations: if no script element is associated with the configuration, the entry script resolves the CSS URL using other loaded script tags in the DOM.

Supported display modes:

- `floating` is the default. It renders a bottom-corner launcher, a compact chat panel, and an overlay history drawer.
- `inline` mounts into `data-container-id` (or the config's container), opens immediately, hides the launcher and close button, and fills the remaining viewport height from the mount point. The container acts as the anchor in the host menu page; the widget keeps a small bottom gap so the input area stays near the visible page bottom without sticking to the viewport edge. The widget recalculates this height on viewport changes so Vue host layouts that settle after script injection do not leave the composer in the middle of the page.

To ensure the widget is responsive regardless of where it is embedded (e.g., in a narrow sidebar modal versus a full-width page), the layout uses CSS Container Queries (`@container (max-width: 600px)`) rather than traditional viewport Media Queries. The container type is defined on the workbench wrapper (`container-type: inline-size`), allowing the history sidebar to collapse into a sliding menu or hide responsively based on the widget's actual width.

Both modes use the portal intelligent-query visual language: left topic history, search, new conversation, topic switching, model info, portal-style message rendering, deep-thinking blocks, tool output rendering, sending, streaming, and cancellation. The widget UI does not expose topic deletion so it stays aligned with the portal page. While an answer is running, history switching, creation, and sending are disabled; the user can stop the task first.

The widget bundle may also emit `style.css` for reused portal components. The script entry automatically links that stylesheet into the widget Shadow DOM based on the script URL, so embedding projects still only need the single script tag.

### Unified DataAgent API

The widget uses the existing `/api/v1/nl2sql/*` runtime endpoints:

- `GET /api/v1/nl2sql/topics`
- `POST /api/v1/nl2sql/topics`
- `GET /api/v1/nl2sql/topics/{topic_id}`
- `GET /api/v1/nl2sql/topics/{topic_id}/messages`
- `PUT /api/v1/nl2sql/topics/{topic_id}`
- `POST /api/v1/nl2sql/tasks/deliver-message`
- `GET /api/v1/nl2sql/tasks/{task_id}`
- `GET /api/v1/nl2sql/tasks/{task_id}/events/stream`
- `POST /api/v1/nl2sql/tasks/{task_id}/cancel`

Widget calls add these headers:

- `X-ODW-Client: widget`
- `X-ODW-Website-Id: <website_id>`
- `X-ODW-User-Id: <business_user_id>`
- `X-ODW-Visitor-Id: <generated_visitor_id>` when no user id is provided

Widget runtime payloads also include `agent_id` from the embedding script's `data-agent-id`. Widget topic creation requires this value so the selected DataAgent profile snapshot, including its `data_scope`, is stored on the topic. Widget topic listing and message delivery pass the same `agent_id` to keep history filtering and task validation aligned with the selected agent.

Portal calls send no widget headers and remain `source=portal`.

A new safe runtime endpoint, `GET /api/v1/nl2sql/runtime-config`, returns enabled provider/model metadata needed to send chat messages without exposing admin settings or credentials.

### Isolation Model

DataAgent stores ownership metadata on `da_agent_topic`:

- `source`: `portal` or `widget`
- `website_id`
- `external_user_id`
- `visitor_id`

Portal requests can only access portal topics. Widget requests can only access topics whose `website_id` matches and whose identity matches either `external_user_id` or, when no user id is provided, `visitor_id`.

Direct user id headers provide internal product-level isolation, not cryptographic identity proof. This is acceptable for v1 because the target integrations are trusted internal systems.

### Allowed Sites

DataAgent reads `WIDGET_ALLOWED_SITES_JSON`, for example:

```json
[
  {
    "website_id": "your-project",
    "allowed_origins": ["https://app.example.com"],
    "project_name": "Your Project",
    "project_color": "#4A90A4"
  }
]
```

Widget requests with an unknown `website_id` or disallowed `Origin` receive 403. Empty configuration allows no widget sites by default.

## Tradeoffs

- Unified routes reduce API duplication but require careful ownership checks on every topic/task path.
- Direct user id integration is easy for Vue + Spring Boot hosts, but it is not a strong security mechanism.
- A dedicated widget entry avoids coupling external embeds to the full portal page and keeps widget styling isolated through Shadow DOM.
