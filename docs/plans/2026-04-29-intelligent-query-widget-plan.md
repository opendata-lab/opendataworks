# Intelligent Query Widget Implementation Plan

## Goal

Add an embeddable intelligent-query widget that uses unified DataAgent runtime APIs and isolates conversations by trusted host project and user.

## Implementation Steps

1. Add DataAgent tests for widget context extraction, runtime config, topic isolation, task ownership, SSE ownership, and cancel ownership.
2. Implement DataAgent request context parsing, allowed-site validation, runtime config response, schema migration, and ownership-aware store queries.
3. Add frontend tests for widget script config parsing, API default headers, widget controls, and send/cancel behavior.
4. Implement `frontend/src/widget` entry/component/client modules and a Vite widget build.
5. Add widget conversation history in both display modes:
   - `floating`: compact portal-style panel with overlay history drawer.
   - `inline`: mount into `data-container-id`, fill the remaining viewport height from the mount point, and show a portal-style left history sidebar on desktop.
   - Support history list, search, new conversation, switch conversation, portal-style message rendering, and running-task guards.
6. Update `frontend/Dockerfile`, `frontend/nginx.conf`, and package scripts so the widget bundle is built and served at `/widget/opendataworks-widget.bundle.js`.
7. Run focused backend and frontend tests, then run frontend portal and widget builds. Run local smoke only when DataAgent dependencies and provider credentials are available.

## Files

Expected primary changes:

- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/core/topic_task_store.py`
- `dataagent/dataagent-backend/models/schemas.py`
- `dataagent/dataagent-backend/config.py`
- `dataagent/dataagent-backend/alembic/versions/*_add_widget_topic_context.py`
- `frontend/src/api/nl2sql.js`
- `frontend/src/widget/*`
- `frontend/vite.widget.config.js`
- `frontend/package.json`
- `frontend/Dockerfile`
- `frontend/nginx.conf`
- `deploy/.env.example`
- `deploy/docker-compose.dev.yml`
- `deploy/docker-compose.prod.yml`

## Verification

- `alembic upgrade head` in `dataagent/dataagent-backend` when local MySQL is available.
- Focused pytest for DataAgent widget routes and ownership.
- `nvm use && npm --prefix frontend test -- widget nl2sql`
- `nvm use && npm --prefix frontend run build`
- `nvm use && npm --prefix frontend run build:widget`
- Browser smoke for a floating host page, an inline host menu page, and an inline no-explicit-height host page, covering bottom-aligned input, history list, topic switch, new topic, no delete UI, streaming reply, and widget isolation headers.

## Backout

Revert the widget migration and code changes before production migration, or deploy a rollback commit that disables widget site configuration and removes the widget static bundle.
