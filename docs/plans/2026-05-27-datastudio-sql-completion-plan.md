# DataStudio SQL Completion Implementation Plan

> **For agentic workers:** Implement task-by-task with tests first. Keep existing DataStudio result-grid changes intact.

**Goal:** Add Navicat-like SQL editor suggestions for schemas, tables, fields, functions, and keywords in DataStudio.

**Architecture:** Reuse the current CodeMirror 6 editor. DataStudio owns metadata loading and caching; `SqlEditor.vue` owns context-aware completion construction. Backend exposes two lightweight metadata APIs over existing Doris metadata service methods.

**Tech Stack:** Vue 3, CodeMirror 6, Vitest, Java 8, Spring Boot MVC, Mockito/MockMvc.

---

## Tasks

1. Add backend tests for `DorisClusterController` metadata endpoints.
2. Implement `schema-objects` and table-column endpoints plus frontend API wrappers.
3. Add frontend SQL completion tests around a small exported helper module.
4. Implement completion helper and wire it into `SqlEditor.vue`.
5. Wire DataStudio completion context, table/column lazy loading, and demo adapter support.
6. Run targeted verification and report any remaining full-flow gaps.

## Verification Commands

- Frontend:
  - `export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use`
  - `npm --prefix frontend run test -- src/components/__tests__/sqlCompletion.spec.js src/demo/__tests__/mockServerDatastudio.spec.js`
- Backend:
  - `cd backend && mvn -Dtest=DorisClusterControllerTest,DorisConnectionServiceTest test`

## Acceptance Criteria

- Current SQL editor no longer limits completion to prefix-only `startsWith` filtering.
- DataStudio can suggest current data source schemas and cross-schema tables.
- `schema.table.` and `alias.` can lazily suggest fields.
- Common functions and SQL keywords appear in completion options.
- Existing `tableNames` prop remains usable by non-DataStudio callers.
