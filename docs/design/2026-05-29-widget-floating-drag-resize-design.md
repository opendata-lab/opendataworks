# Widget Floating Panel Drag & Resize Design

## Current State

The embeddable intelligent-query widget under `frontend/src/widget/` renders, in floating mode, a fixed-size panel:

- Panel size is hard-coded in `styles.js` (`WIDGET_STYLES`): `.odw-panel { width: min(480px, calc(100vw - 24px)); height: min(680px, calc(100vh - 48px)) }`. On large monitors it stays a constant 480×680 box.
- Position is anchored purely by CSS classes on the `.odw-widget` root (`position: fixed`): `.odw-widget.is-bottom-right { right: 24px; bottom: 24px }` (or `is-bottom-left`).
- The panel `header` (`.odw-panel__header`, floating mode only) holds the title plus history/new/close buttons.
- There is **no drag or resize logic anywhere** in the widget.

Inline mode (`displayMode: 'inline'`) is sized by `applyHostSizing()` in `entry.js` to fill its container and is unrelated to this change.

## Problem

On large screens the floating panel feels too narrow and too short, and users cannot move it out of the way or make it bigger. We need to:

1. Make the default floating size scale responsively with the viewport (with sensible caps).
2. Allow the user to drag the panel by its header.
3. Allow the user to resize the panel via a bottom-right handle.
4. Remember the dragged position and resized dimensions across page reloads.

Constraints:

- Changes must stay inside `frontend/src/widget/`. No backend, no main frontend app, no DataAgent.
- No breaking changes to the embed contract: `data-*` attributes, `installWidget()` API, and the event system must remain unchanged so existing third-party integrations need no code changes.
- Inline mode behavior must remain unchanged (no handle, not draggable, still fills its container).

## Scope

In scope (floating mode only): responsive default sizing, header drag-to-move, bottom-right resize handle, localStorage persistence of geometry.

Out of scope: inline mode layout, backend, any embed-API/data-attribute additions, and a settings UI for resetting geometry (a `resetGeometry()` helper is provided but not surfaced).

## Solution

### 1. Responsive default size (`styles.js`)

Replace the fixed `.odw-panel` dimensions with viewport-relative `clamp()`:

- `width: clamp(440px, 30vw, 720px)`
- `height: min(860px, calc(100vh - 48px))`

The history-open rule grows wider than the default while staying inside the viewport:

- `.odw-widget.is-history-open:not(.is-inline) .odw-panel { width: min(clamp(720px, 52vw, 1080px), calc(100vw - 24px)) }`

The `@media (max-width: 520px)` small-screen full-width rule and the inline overrides are kept as-is. Per the approved decision, this responsive enlargement applies to all embeds directly.

### 2. Drag to move (header as handle)

In `OpenDataWorksWidget.vue`, add `ref` to the root (`.odw-widget`) and panel (`.odw-panel`), and bind `@pointerdown` on the header. Drag logic lives in a new composable `useWidgetGeometry.js`:

- On `pointerdown`, ignore the gesture if the target is inside a `button` (so history/new/close still work).
- Record the starting pointer `clientX/Y` and the panel `getBoundingClientRect()`.
- On `pointermove`, compute `left/top = start + delta`, clamped to keep the panel inside the viewport. Apply via reactive inline style on the root element, and add an `is-dragged` class that sets `right/bottom: auto` to override the `is-bottom-right`/`is-bottom-left` anchor.
- Use `setPointerCapture` and end on `pointerup`. While dragging, add an `is-interacting` class to temporarily disable the `.odw-panel` width transition.

### 3. Resize handle (bottom-right)

- Add `<div class="odw-resize-handle">` inside `.odw-panel` (floating only), wired to `startResize` with `@pointerdown.stop`.
- Record the starting pointer and panel width/height; on `pointermove` compute new `width/height`, clamped to `[min, max]` where min is 360×420 and max is `window.innerWidth/Height - 24`. Apply via reactive inline style on the panel element.
- Styles: `.odw-panel { position: relative }`; `.odw-resize-handle` absolutely positioned bottom-right with `cursor: nwse-resize` and a subtle grip; `.odw-widget.is-inline .odw-resize-handle { display: none }`. The header gets `cursor: move; user-select: none`, with its buttons reset to `cursor: pointer`.

### 4. Persistence (localStorage)

- Key: `odw:widget:geom:<websiteId || 'default'>`, storing `{ left, top, width, height }` as JSON.
- Write on drag/resize end. On mount (floating mode only) read, clamp to the current viewport, and apply.
- All reads/writes are wrapped in try/catch, matching the existing robust pattern in `config.js` (`resolveVisitorId`), so private-mode/storage-disabled browsers degrade gracefully.

## Interfaces

No public interface changes. The embed contract is unchanged:

- `data-*` attributes, `installWidget()` signature, and emitted events (`open`, `close`, `history:open`, `history:close`, `conversation:new`, plus bubbled chat events) all stay the same.
- One new localStorage key (`odw:widget:geom:*`) is introduced; it is additive and isolated per `websiteId`.

The new internal module `useWidgetGeometry.js` exposes a composable consumed only by `OpenDataWorksWidget.vue` (e.g. drag/resize pointer handlers, reactive `rootStyle`/`panelStyle`, interaction flags, and `resetGeometry()`).

## Tradeoffs

- After a manual resize, the panel width is user-controlled; the history toggle still switches the in-panel sidebar column (`.query-workbench` grid) but no longer auto-widens the outer panel, because inline style outranks the history-open CSS rule. This is acceptable.
- A single Pointer Events implementation covers both mouse and touch, avoiding duplicate mouse/touch branches (aligns with the "one verified primary path" working rule).
- The responsive default-size change is visible to all existing third-party embeds. This was explicitly chosen over a conservative/opt-in rollout; it is a visual change only and not an API break.
