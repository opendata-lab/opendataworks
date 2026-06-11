# DataAgent Frontend Decoupling Design

## Current State

OpenDataWorks currently builds the main portal and the intelligent-query UI from `frontend/`.
The portal route `/intelligent-query` imports `frontend/src/views/intelligence/IntelligentQueryView.vue`, and `frontend/src/views/Layout.vue` imports `frontend/src/widget/entry.js` directly to mount the floating assistant.

DataAgent backend is already independent under `dataagent/dataagent-backend`, but its frontend surfaces are still compiled into the portal:

- chat and task streaming UI under `frontend/src/views/intelligence/`
- embeddable widget under `frontend/src/widget/`
- DataAgent API clients under `frontend/src/api/nl2sql.js` and `frontend/src/api/dataagent.js`
- model, Skill, agent, and widget settings pages under `frontend/src/views/settings/`

## Problem

The portal should no longer own DataAgent frontend code. It should embed DataAgent through a remote JavaScript bundle so DataAgent can be released as its own frontend product and the portal only acts as a host.

The split must preserve two portal entrypoints:

- a global bottom-right floating assistant
- a dedicated `/intelligent-query` menu page that embeds the ask-data chat page inline

## Scope

In scope:

- add `dataagent/dataagent-frontend` as an independent Vite frontend
- move DataAgent widget, chat, API client, and admin UI code into the DataAgent frontend
- make the portal consume the DataAgent widget through `VITE_DATAAGENT_WIDGET_JS_URL`
- update Docker, Compose, offline packaging, and deployment docs

Out of scope:

- changing DataAgent backend API routes or schemas
- replacing the widget contract
- embedding the full DataAgent admin workbench in the portal menu
- introducing iframe-based hosting

## Solution

### DataAgent Frontend

`dataagent/dataagent-frontend` becomes the owner of DataAgent UI code. It builds:

- a standalone web app for chat, agents, Skills, models, and widget access configuration
- a widget bundle served as `opendataworks-widget.bundle.js`
- a widget stylesheet served as `style.css`

The widget keeps its current public global API:

- `window.OpenDataWorksWidget.installWidget(config)`
- `open`, `close`, `sendMessage`, `cancel`, `destroy`, and existing conversation controls

### Portal Embedding

The portal uses only a remote script URL configured by:

```text
VITE_DATAAGENT_WIDGET_JS_URL=/dataagent/widget/opendataworks-widget.bundle.js
```

`Layout.vue` injects that script and mounts a floating widget. The `/intelligent-query` route renders a local container and mounts the same remote widget with `displayMode: "inline"`.

No shared portal-side widget loader module is introduced. The two host components contain their own small script-injection logic and coordinate through a single browser-global promise to avoid loading the same script twice.

### Deployment

The root deployment gains a `dataagent-frontend` service. The main portal Nginx proxies `/dataagent/` to that service, so the default widget URL remains same-origin for production and offline deployments.

DataAgent frontend Nginx proxies existing DataAgent API routes to `dataagent-backend` and preserves streaming proxy settings for `/api/v1/nl2sql/`.

## Tradeoffs

- Script embedding keeps the portal lightweight and avoids iframe styling and routing isolation issues, but it means each host component owns some script lifecycle code.
- Keeping DataAgent admin UI in the independent app avoids mixing settings ownership back into the portal, but existing portal deep links to DataAgent management must redirect or collapse to the inline chat entry.
- A separate DataAgent frontend image adds deployment surface, but it makes DataAgent release boundaries explicit.
