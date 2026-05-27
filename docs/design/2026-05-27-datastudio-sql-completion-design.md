# DataStudio SQL Completion Design

**Date:** 2026-05-27
**Goal:** Make the DataStudio SQL editor suggest schemas, tables, columns, common functions, and SQL keywords while typing, with cross-schema object completion in the selected data source.
**Tech Stack:** Vue 3 + Vite + CodeMirror 6 frontend, Java 8 + Spring Boot 2.7 backend, Doris/MySQL-compatible JDBC metadata APIs.

## Current State

- `frontend/src/components/SqlEditor.vue` already uses CodeMirror 6 with `@codemirror/autocomplete` and `@codemirror/lang-sql`.
- Current completion builds a flat list from `tableNames` and a small hard-coded keyword list, then filters candidates with `startsWith`, so matching is prefix-only.
- `DataStudioNew.vue` already loads data sources, schemas, and per-schema tables into `schemaStore` and `tableStore`.
- Backend already has `DorisConnectionService.getSchemaObjects` for schema/table metadata and `getColumnsInTable` for table columns, but column metadata is not exposed through a DataStudio API.

## Design

- Keep the existing CodeMirror editor and extend its completion source.
- Completion matching should be delegated to CodeMirror's built-in autocomplete filtering and ranking; the editor should not pre-filter candidates with `startsWith`.
- DataStudio provides a completion context to `SqlEditor`:
  - selected `sourceId` and `dbName`
  - current schema list
  - loaded tables by schema
  - async loaders for schema table lists, table columns, and cross-schema table search
- Completion behavior:
  - top-level input suggests keywords, common SQL/Doris functions, schemas, current-schema tables, and limited cross-schema table search results
  - `schema.` suggests tables/views in that schema
  - `schema.table.` suggests columns for that table
  - `alias.` suggests columns for the table bound by `FROM`/`JOIN` aliases in the current statement
- Use lazy loading for fields. Do not prefetch every table's columns when a data source opens.
- Cache column metadata by `sourceId::schema::table` in DataStudio state.

## Interfaces

- Add backend API: `GET /v1/doris-clusters/{id}/schema-objects`
  - Query params: `keyword`, `limit`, `includeSoftDeleted`
  - Returns lightweight objects with `schemaName`, `tableName`, `tableType`, `tableComment`.
- Add backend API: `GET /v1/doris-clusters/{id}/databases/{database}/tables/{table}/columns`
  - Returns the list from `DorisConnectionService.getColumnsInTable`.
- Add frontend API methods in `frontend/src/api/doris.js`:
  - `searchSchemaObjects(id, params)`
  - `getColumns(id, database, tableName)`
- Extend `SqlEditor.vue` props without breaking existing callers:
  - keep `tableNames`
  - add optional `completionContext`

## Risks and Tradeoffs

- Alias extraction will be lightweight SQL text parsing, not a full SQL AST. It should support common `FROM schema.table alias` and `JOIN table AS alias` patterns and fail closed when ambiguous.
- Cross-schema search must be limited to avoid large completion payloads.
- Dynamic UDF discovery is out of scope for this iteration; first version uses a curated common function list.
- The backend endpoints expose metadata already visible in DataStudio's catalog and require existing auth.

## Verification

- Frontend unit tests cover fuzzy matching delegation, schema/table/column/alias/function completions, and `tableNames` compatibility.
- Backend tests cover the new controller endpoints and Doris metadata delegation.
- Demo adapter tests cover the new DataStudio completion endpoints.
- Run targeted frontend and backend tests, then run a frontend build if time permits.
