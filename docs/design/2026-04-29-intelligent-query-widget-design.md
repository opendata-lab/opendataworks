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

The widget is a separate frontend entrypoint under `frontend/src/widget`. It mounts into a Shadow DOM container created by `entry.js` and exposes `window.OpenDataWorksWidget` with `open`, `close`, `toggle`, `destroy`, `sendMessage`, `openHistory`, `newConversation`, `selectConversation`, `deleteConversation`, and `on`.

The widget reads configuration from the current script's `data-*` attributes. For SPA hosts, the project frontend dynamically creates the script after fetching the current user from its Spring Boot backend.

Supported display modes:

- `floating` is the default. It renders a bottom-corner launcher, a compact chat panel, and an overlay history drawer.
- `inline` mounts into `data-container-id`, opens immediately, hides the launcher and close button, and fills the remaining viewport height from the mount point. The container acts as the anchor in the host menu page; the widget keeps a small bottom gap so the input area stays near the visible page bottom without sticking to the viewport edge. The widget recalculates this height on viewport changes so Vue host layouts that settle after script injection do not leave the composer in the middle of the page.

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
