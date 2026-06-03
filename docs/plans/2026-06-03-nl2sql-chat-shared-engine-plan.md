# NL2SQL Chat Shared Engine Plan

Paired design: `docs/design/2026-06-03-nl2sql-chat-shared-engine-design.md`.

Stack: DataAgent frontend (`dataagent/dataagent-frontend`). No backend or
deployment changes.

## Status

- Phase 1: done (`chatMessage.js` + tests; both components consume it).
- Phase 2: done (`useNl2SqlChat.js` + tests; `WidgetChat.vue` migrated; 167 tests green).
- Phase 3: pending — implementation is mechanical, but its verification needs the
  local NL2SQL smoke flow, which is unavailable in the current environment.

## Phase 1 — Pure helper module (low risk)

Extract verbatim-duplicated, stateless logic; no lifecycle change.

Touched files:
- add `src/views/intelligence/chatMessage.js`:
  `buildV2StateFromStoredBlocks`, `hydrateMessageFromApi`, `extractErrorText`,
  `renderMarkdown` (escape-then-parse), `normalizeTopic`, topic compare helper.
- add `src/views/intelligence/__tests__/chatMessage.spec.js`.
- `WidgetChat.vue` + `NL2SqlChatV2.vue`: import the helpers, delete the local
  copies.

Verify:
- `npx vitest run src/views/intelligence/__tests__/chatMessage.spec.js`
- `npx vitest run src/widget src/views/intelligence` stays green.

## Phase 2 — Engine composable, widget migration

Touched files:
- add `src/views/intelligence/useNl2SqlChat.js` implementing the contract in the
  design (state, `isBusy`, `loadConfig/loadTopics/selectTopic/newConversation/
  deleteConversation/send/detach/cancel`), with options for `api`, `agentId`,
  `messagePageSize`, `reloadTopicsAfterRun`, `track`, `onEvent`, `runMock`.
- `WidgetChat.vue`: replace its local state/actions with the composable; keep
  template, geometry, mock runner (passed as `runMock`), tracking (`track`),
  outbound + imperative watchers, demo fallback.

Verify:
- `npx vitest run src/widget` (14 widget tests) green; add a composable unit test
  for detach-on-switch / detach-on-new while busy.

## Phase 3 — chat v2 migration

Touched files:
- `NL2SqlChatV2.vue`: replace local config/topic/message/send/stream/cancel logic
  with the composable; keep route sync, agent selector, audit facets (source
  mode, user/status/sort filters), feedback, copy, scroll. Set
  `reloadTopicsAfterRun: true` and `messagePageSize: 500`.
- update `NL2SqlChatV2.spec.js` for converged behaviors (leave-while-running
  detaches; stop uses `cancel`; escaped markdown).

Verify:
- `npx vitest run src/views/intelligence` green.
- Local intelligent-query smoke (AGENTS.md): MySQL `127.0.0.1:3316`, Redis
  `127.0.0.1:6379`, dataagent-backend, one real NL2SQL run; confirm task
  creation, event stream, terminal status, message persistence, and the
  leave-while-running + cancel paths through both surfaces.

## Rollout & backout

- Land phases as separate commits so each is independently revertable.
- Phases 1–2 are inert for chat v2 until Phase 3; the widget is fully covered by
  its 49 tests at each step.
- Backout = revert the phase commit(s); no schema/state migration involved.

## Risks

- Chat v2 is the primary portal UI; Phase 3 carries the real regression risk and
  must not ship without the local smoke flow. If the smoke environment is
  unavailable, hold Phase 3 and report the missing full-flow coverage.
- Converged cancel/markdown behaviors are intentional changes to chat v2; flag in
  the Phase 3 change summary.
