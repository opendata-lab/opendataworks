# Widget Floating Panel Drag & Resize Plan

Paired design: `docs/design/2026-05-29-widget-floating-drag-resize-design.md`

Affected stack: frontend widget only (`frontend/src/widget/`). No backend, DataAgent, or infra changes.

## Tasks

### 1. Responsive default size — `frontend/src/widget/styles.js`
- `.odw-panel`: change to `width: clamp(440px, 30vw, 720px)`, `height: min(860px, calc(100vh - 48px))`; add `position: relative`.
- `.odw-widget.is-history-open:not(.is-inline) .odw-panel`: `width: min(clamp(720px, 52vw, 1080px), calc(100vw - 24px))`.
- Keep `@media (max-width: 520px)` full-width rule and all inline overrides unchanged.
- Add styles: `.odw-resize-handle` (absolute bottom-right, `cursor: nwse-resize`, subtle grip), `.odw-widget.is-inline .odw-resize-handle { display: none }`, `.odw-panel__header { cursor: move; user-select: none }` with buttons reset to `cursor: pointer`, `.odw-widget.is-dragged { right: auto; bottom: auto }`, and `.odw-widget.is-interacting .odw-panel { transition: none }`.

### 2. Geometry composable — `frontend/src/widget/useWidgetGeometry.js` (new)
- Export `useWidgetGeometry({ rootEl, panelEl, config })` returning: `startDrag(e)`, `startResize(e)`, reactive `rootStyle`, `panelStyle`, `isDragged`, `isInteracting`, and `resetGeometry()`.
- Pointer Events only (`pointerdown`/`pointermove`/`pointerup` + `setPointerCapture`).
- `startDrag`: bail if `e.target.closest('button')`; track pointer + rect; clamp `left/top` to viewport.
- `startResize`: track pointer + size; clamp to min 360×420, max `innerWidth/Height - 24`.
- Persistence helpers: key `odw:widget:geom:<websiteId||'default'>`; `loadGeometry()` / `saveGeometry()` wrapped in try/catch; apply on mount (floating only) with viewport clamp.

### 3. Wire into component — `frontend/src/widget/OpenDataWorksWidget.vue`
- Add `ref="rootEl"` to `.odw-widget`, `ref="panelEl"` to `.odw-panel`.
- Bind `:style`/class for `is-dragged` + `is-interacting` on root; `:style="panelStyle"` on panel.
- `@pointerdown="startDrag"` on `.odw-panel__header`.
- Add resize handle `<div class="odw-resize-handle" @pointerdown.stop="startResize" v-if="!isInline" />` inside `.odw-panel`.
- Call the composable in `setup`, restore geometry in `onMounted`, clean up listeners in `onBeforeUnmount`.

### 4. Test — `frontend/src/widget/__tests__/useWidgetGeometry.spec.js` (new)
- Unit-test clamp logic (position kept in-viewport, size within min/max) and localStorage load/save round-trip incl. the try/catch fallback when storage throws.

## Verification
- `cd frontend && npx vite build` — compiles clean (repo runs node v22; `nvm use` first if available).
- `npx vitest run src/widget` — existing `WidgetChat.spec.js` / `entry.spec.js` still pass; new geometry spec passes.
- Manual smoke (floating demo, `agent-id=demo`): large-screen default visibly larger and viewport-scaled; header drag moves panel and persists, clamped at edges; bottom-right handle resizes within min/max; reload restores position + size; inline mode shows no handle, not draggable, still fills container.

## Rollout
- Pure frontend; ships with the widget bundle. No migrations, no config, no coordinated backend deploy. Responsive enlargement applies to all embeds on next bundle load (decision: direct rollout, no opt-in).

## Backout
- Revert the four files (`styles.js`, `OpenDataWorksWidget.vue`, delete `useWidgetGeometry.js` and its spec). No persisted server state; the only residue is the `odw:widget:geom:*` localStorage key, which is harmless if left and ignored once the code is gone.
