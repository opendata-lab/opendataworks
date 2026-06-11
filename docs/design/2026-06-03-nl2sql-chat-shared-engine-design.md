# NL2SQL Chat Shared Engine Design

## Current State

DataAgent ships two NL2SQL chat surfaces inside `dataagent/dataagent-frontend`:

- `src/views/intelligence/NL2SqlChatV2.vue` (~1840 lines) — the portal / admin chat
  page, with routing deep-links, agent selector, session audit facets (widget
  source mode, user filter, status filter), message feedback, and copy.
- `src/widget/WidgetChat.vue` (~960 lines) — the embeddable floating/inline
  widget, with cross-origin API config, demo/mock mode, behavior tracking, and an
  imperative outbound-message API.

They already share the stream/render primitives:

- `src/views/intelligence/v2StreamParser.js` (`createChatState`, `processV2Record`, `blockToToolProp`)
- `src/views/intelligence/chartSpec.js` (`stripChartSpecsFromText`)
- `src/views/intelligence/topicStatus.js` (`topicStatusKind`)
- `src/views/intelligence/ToolOutputRenderer.vue`

But the **conversation engine** — providers/models config, topic list CRUD,
active-conversation message hydration, and the `send → deliver task → stream SDK
events → reconcile → detach/cancel` lifecycle — is duplicated and has already
drifted between the two components.

## Problem

The duplicated engine is a maintenance hazard. Recent work to allow "new
conversation / switch topic while a run is in progress" had to be applied twice
and the two copies still diverge in subtle, partly accidental ways:

- abort detection: chat v2 keys on `error.name === 'AbortError'`; the widget
  historically read `abortController.signal.aborted` after the fact.
- busy model: chat v2 uses one `isStreaming` flag; the widget uses
  `isSubmitting + activeTaskId`.
- leave-while-running: chat v2 detaches (local abort, backend keeps running) on
  topic switch; the widget used to block via a `guardIdle` gate.
- cancel semantics: the widget stop button calls backend `cancelTask` and marks
  the topic `suspended`; chat v2's stop only detaches.
- post-run topic refresh: chat v2 calls `loadTopics()`; the widget writes
  `setTopicTaskStatus` directly and intentionally does not reload.
- markdown safety: the widget escapes HTML before `marked.parse`; chat v2 does
  **not** (a latent XSS divergence on assistant content).
- `buildV2StateFromStoredBlocks` and the error-text extractor are near-verbatim
  copies in both files.

Every future change to the chat lifecycle currently risks fixing one surface and
regressing the other.

## Scope

In scope:

- a single shared "conversation engine": one stateless helper module for pure
  functions, and one Vue composable for the stateful lifecycle.
- migrate `WidgetChat.vue` and `NL2SqlChatV2.vue` to consume the shared engine
  for config, topic list, active-conversation messages, and the send/stream/
  detach/cancel lifecycle.
- converge the accidental divergences listed above onto one behavior.

Out of scope:

- the rendered templates and styles of either component (they stay separate for
  genuine UI reasons: Element Plus + routing + audit facets vs. shadow-DOM embed
  + geometry/drag + mock mode).
- chat v2 audit-only features (widget source mode, user facet, feedback, copy) —
  these stay in the component.
- widget-only integration concerns (cross-origin headers, tracking, demo/mock,
  outbound-message API) — these are injected into the engine via options, not
  moved wholesale.
- backend routes/schemas and the widget public global API contract.

## Solution

### Layering

1. `src/views/intelligence/chatMessage.js` — pure, stateless helpers shared
   verbatim today:
   - `buildV2StateFromStoredBlocks(item)`
   - `hydrateMessageFromApi(item)` (normalizes user/assistant persisted messages
     into the local message shape, including `_v2state`)
   - `extractErrorText(error)`
   - `renderMarkdown(text)` — **always HTML-escapes before `marked.parse`** (the
     safe widget behavior becomes the single behavior).
   - `normalizeTopic(topic)`, topic sort/compare helper.

2. `src/views/intelligence/useNl2SqlChat.js` — the stateful composable. Owns the
   conversation engine state and actions; UI components own templates, routing,
   and their unique features.

### Composable contract

```
const chat = useNl2SqlChat({
  api,                 // a createNl2SqlApiClient instance (caller builds it so
                       //   the widget can pass baseURL + cross-origin headers)
  agentId,             // ref/getter; '' when unscoped
  messagePageSize = 200,
  reloadTopicsAfterRun = false,  // chat v2: true; widget: false
  track,               // optional analytics callback (widget)
  onEvent,             // optional ({ name, payload }) sink (widget emits)
  runMock,             // optional async mock runner (widget demo mode)
})
```

Returns:

- state refs: `topics`, `activeTopicId`, `messages`, `providers`,
  `selectedProvider`, `selectedModel`, `inputText`, `searchKeyword`,
  `errorText`, `thinkingExpanded`
- status: `isBusy` (single source of truth), `activeTaskId`
- actions: `loadConfig()`, `loadTopics(opts)`, `selectTopic(id)`,
  `newConversation()`, `deleteConversation(id)`, `send()`, `detach()`,
  `cancel()` (backend cancel + suspended), `toggleThinking(key)`
- computed helpers: `availableModels`, `filteredTopics` (base keyword filter;
  components layer their own status/user/sort facets on top)

### Converged behaviors

- **Busy**: `isBusy` is the only gate. Components bind disabled state and the
  send/stop toggle to it.
- **Abort detection**: always `error?.name === 'AbortError'`.
- **Leave-while-running**: `selectTopic`, `newConversation`, and
  `deleteConversation` call `detach()` when busy — never block. `detach()` aborts
  the local stream, leaves the backend task running, recoverable from history.
- **Cancel**: `cancel()` is the explicit stop action — aborts locally **and**
  calls backend `cancelTask`, marking the topic `suspended`. Both components use
  the same `cancel()`; the difference is purely which buttons each renders.
- **Post-run topic status**: the engine always writes `setTopicTaskStatus` for
  the owning topic; `reloadTopicsAfterRun` optionally triggers a list refresh for
  chat v2.
- **Markdown**: escaped-then-parsed everywhere (closes the chat v2 XSS gap).

### What stays in each component

- `NL2SqlChatV2.vue`: route sync (`replaceRouteTopic`), agent selector, source
  mode + user/status/sort facets, feedback, copy, scroll management, Element Plus
  markup. It feeds route/agent changes into the engine and layers facet filtering
  over `topics`.
- `WidgetChat.vue`: shadow-DOM markup + geometry, demo/mock runner, tracking,
  outbound-message + imperative controls (wired through composable options and
  watchers), floating/inline display.

## Tradeoffs

- A composable with options/callbacks is more indirection than two flat
  components, but it removes the dominant duplication and a class of "fixed once,
  regressed twice" bugs.
- Converging cancel semantics and markdown escaping changes current chat v2
  behavior (stop now suspends the backend task; assistant HTML is escaped). These
  are intentional corrections, called out for review.
- Staged migration (widget first, then chat v2) keeps the higher-risk portal view
  on the proven widget-tested engine path.

## Verification

- Unit tests for `chatMessage.js` pure helpers.
- Existing `WidgetChat.spec.js` (14) and `NL2SqlChatV2.spec.js` must stay green
  after migration; extend them for the converged leave-while-running behavior.
- Local intelligent-query smoke per AGENTS.md after chat v2 migration, since the
  send/stream lifecycle is touched.
