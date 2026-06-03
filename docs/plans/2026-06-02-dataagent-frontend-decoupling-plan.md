# DataAgent Frontend Decoupling Plan

## Goal

Split the intelligent-query frontend into `dataagent/dataagent-frontend` and make the main portal consume it through a remote widget JavaScript bundle.

## Steps

1. Add focused portal tests proving that `Layout.vue` and `/intelligent-query` load `VITE_DATAAGENT_WIDGET_JS_URL` and call `window.OpenDataWorksWidget.installWidget`.
2. Create `dataagent/dataagent-frontend` with independent package metadata, Vite app config, Vite widget config, Dockerfile, and Nginx config.
3. Copy DataAgent-owned UI modules from `frontend/` into `dataagent/dataagent-frontend/src`, including widget, chat, API clients, and DataAgent admin views.
4. Update main portal routes and layout:
   - route `/intelligent-query` to a small inline widget container page
   - keep `/nl2sql` redirecting to `/intelligent-query`
   - redirect old Skill/agent deep links to `/intelligent-query`
   - mount the floating widget from the remote script in `Layout.vue`
5. Remove DataAgent-owned source files from `frontend/` once portal imports no longer reference them.
6. Update deployment:
   - add `opendataworks-dataagent-frontend` image configuration
   - add `dataagent-frontend` service to dev and prod Compose
   - proxy `/dataagent/` from main frontend Nginx to `dataagent-frontend`
   - update build scripts, offline packaging, image loading, `.env.example`, and deployment docs
7. Verify:
   - DataAgent frontend tests and builds
   - portal tests and build
   - Compose config rendering
   - local smoke when backend, Redis, MySQL, and provider credentials are available

## Acceptance Criteria

- `frontend/` no longer imports `@/widget`, `@/api/nl2sql`, `@/api/dataagent`, or `@/views/intelligence`.
- `dataagent/dataagent-frontend run build` and `run build:widget` produce the DataAgent app and widget bundle.
- Main portal `/intelligent-query` embeds only the widget inline chat page.
- The floating widget still appears from the main portal layout.
- Root deployment serves the default widget URL at `/dataagent/widget/opendataworks-widget.bundle.js`.
