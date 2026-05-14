# Metadata Sync, DataStudio, and Workflow Guidance Implementation Plan

> Design: [2026-05-14-metadata-sync-datastudio-workflow-guidance-design.md](../design/2026-05-14-metadata-sync-datastudio-workflow-guidance-design.md)

**Goal:** 让元数据结构同步只落库真实差异，DataStudio 能识别并一键同步 Doris 有但平台无的表，并在任务绑定工作流后引导发布到 Dolphin 和上线。
**Tech Stack:** Java 8 + Spring Boot 2.7, MyBatis-Plus, Vue 3 + Vite + Element Plus.

## Task Checklist

- [x] Task 1: Refactor metadata structure sync to skip unchanged existing tables.
- [x] Task 2: Add separate metadata statistics sync path and scheduled task.
- [x] Task 3: Add backend table-name metadata sync API for missing-platform tables.
- [x] Task 4: Mark and render missing-platform Doris tables in DataStudio.
- [x] Task 5: Add DataStudio one-click table sync and corrected operation guards.
- [x] Task 6: Add task-save workflow publish guidance and post-deploy online prompt.
- [x] Task 7: Run focused backend/frontend verification and record gaps.

## Task 1: Structure Sync Only Applies Real Differences

**Files:**
- `backend/src/main/java/com/onedata/portal/service/DorisMetadataSyncService.java`
- `backend/src/test/java/com/onedata/portal/service/DorisMetadataSyncServiceTest.java`

**Steps:**
1. Update `syncExistingTable` so existing tables are updated only when structural metadata changes.
2. Remove row count, storage size, and Doris update time updates from existing-table structure sync.
3. Do not set `syncTime` unless table metadata or fields changed.
4. Change `syncTableFieldsIncremental` to return whether it created, updated, or deleted fields.
5. If only fields changed, update the table `syncTime` without incrementing `updatedTables` unless table-level fields changed.
6. Update `auditDatabase` so it does not call `syncTableStatistics`.
7. Add tests:
   - unchanged existing table does not call `dataTableMapper.updateById`
   - table comment change updates table and increments `updatedTables`
   - field type/comment/order change updates field counts
   - audit does not write table statistics

**Acceptance Criteria:**
- A full structure sync can still scan all Doris tables, but unchanged existing tables produce no `data_table` update.
- Existing table statistics no longer drive structure sync changes.

## Task 2: Separate Statistics Sync Scheduled Task

**Files:**
- `backend/src/main/java/com/onedata/portal/service/DorisMetadataSyncService.java`
- `backend/src/main/java/com/onedata/portal/scheduled/DataSourceMetadataStatisticsSyncTask.java`
- `backend/src/main/resources/application.yml`
- `backend/src/test/java/com/onedata/portal/service/DorisMetadataSyncServiceTest.java`

**Steps:**
1. Extract public statistics sync methods:
   - `syncAllStatistics(Long clusterId)`
   - `syncDatabaseStatistics(Long clusterId, String database)`
   - `syncTableStatisticsOnly(Long clusterId, String database, String tableName)`
2. These methods update only `rowCount`, `storageSize`, `dorisUpdateTime`, `syncTime`, and `table_statistics_history`.
3. Implement `DataSourceMetadataStatisticsSyncTask` with `@Scheduled(fixedDelayString = "${metadata.statistics-sync.fixed-delay-ms:600000}")`.
4. The task scans `DorisCluster` rows where `autoSync=1` and `status='active'`.
5. Record history with `triggerType="auto"`, `scopeType="all"`, `scopeTarget="statistics"`.
6. Add tests for statistics-only sync updating changed stats and skipping unchanged stats.

**Acceptance Criteria:**
- Structure sync and statistics sync can be reasoned about independently.
- No database migration is required for the new scheduled task.

## Task 3: Backend API to Sync a Missing Table by Name

**Files:**
- `backend/src/main/java/com/onedata/portal/controller/DataTableController.java`
- `backend/src/main/java/com/onedata/portal/service/DorisMetadataSyncService.java`
- `backend/src/test/java/com/onedata/portal/service/DorisMetadataSyncServiceTest.java`

**Steps:**
1. Add `POST /v1/tables/sync-metadata/database/{database}/table/{tableName}`.
2. Require `clusterId`; validate the cluster exists.
3. Call `dorisMetadataSyncService.syncTable(clusterId, database, tableName)`.
4. After sync, query `data_table` by `clusterId + dbName + tableName` and add `tableId` to the response.
5. Record sync history with `triggerType="manual"`, `scopeType="table"`, `scopeTarget="{database}.{tableName}"`.
6. Keep the existing ID-based `POST /v1/tables/{id}/sync-metadata` API unchanged.

**Acceptance Criteria:**
- A Doris table without a platform ID can be synchronized without syncing the whole database.
- The response gives the frontend enough data to reload and unlock the current tab.

## Task 4: Mark Missing-Platform Doris Tables in DataStudio

**Files:**
- `frontend/src/views/datastudio/DataStudioNew.vue`
- `frontend/src/views/datastudio/components/DataStudioRightPanel.vue`

**Steps:**
1. In `loadTables`, when a Doris table has no matching platform metadata row, add `metadataMissing: true`, `metadataStatus: 'missing'`, and `id: undefined`.
2. When a matching metadata row exists, add `metadataMissing: false`, `metadataStatus: 'synced'`.
3. Add helper `isPlatformMetadataMissing(table)`.
4. In the left tree table node, render a red warning icon with tooltip for missing metadata.
5. Provide the helper through `dataStudioCtx` for the right panel.

**Acceptance Criteria:**
- Users can see the missing-platform status before clicking the table.
- Existing synced tables keep the current visual behavior.

## Task 5: One-Click Table Sync and Correct Operation Guards

**Files:**
- `frontend/src/api/table.js`
- `frontend/src/views/datastudio/DataStudioNew.vue`
- `frontend/src/views/datastudio/components/DataStudioRightPanel.vue`

**Steps:**
1. Add `tableApi.syncTableMetadataByName(database, tableName, clusterId)`.
2. Add `syncMissingTableMetadata(tabId)` in `DataStudioNew.vue`.
3. Show a right-panel `el-alert` when `isPlatformMetadataMissing(state.table)` is true:
   - text: `当前表在 Doris 中存在，平台中不存在。`
   - action: link button `立即同步`
4. On click, confirm with `ElMessageBox.confirm` before syncing.
5. After success:
   - force `loadTables(sourceId, dbName, true)`
   - find the synced table in `tableStore`
   - merge it into `state.table`
   - set `state.dataLoaded=false`
   - call `loadTabData(tabId)`
6. Update `handleDeleteTable`, `startMetaEdit`, `startFieldsEdit`, `saveFieldsEdit`, `goCreateRelatedTask`, and `goLineage` to show the missing-platform message when the table lacks platform metadata.
7. Disable edit/delete/field buttons in `DataStudioRightPanel` for missing-platform tables and show tooltip `请先同步到平台元数据后再操作`.

**Acceptance Criteria:**
- Missing-platform tables can be synchronized from the current tab without leaving DataStudio.
- Delete no longer says “请先选择要删除的表” for a selected Doris-only table.

## Task 6: Workflow Guidance after Task Save and Deploy

**Files:**
- `frontend/src/views/tasks/TaskEditDrawer.vue`
- `frontend/src/views/workflows/WorkflowDetail.vue`
- `frontend/src/views/workflows/WorkflowList.vue`

**Steps:**
1. In `TaskEditDrawer.handleSave`, capture the saved task response from create/update.
2. Resolve the workflow ID from the saved task or form payload.
3. If a workflow ID exists, after success show a confirm dialog:
   - title: `工作流有变化`
   - message: `请跳转到任务调度页面，将工作流发布到 Dolphin。`
   - confirm: `去发布`
   - cancel: `稍后处理`
4. On confirm, route to `/workflows/{workflowId}?publishHint=1`.
5. In `WorkflowDetail`, when `publishHint=1`, show a non-blocking warning or confirm that tells the user to use the existing publish button, then remove the query marker with `router.replace`.
6. In both `WorkflowDetail.handleDeploy` and `WorkflowList.handleDeploy`, after a successful non-approval deploy, call a shared local helper that asks whether to immediately上线.
7. If user confirms, call existing `workflowApi.publish(id, { operation: 'online', versionId, requireApproval: false, operator: 'portal-ui' })` and refresh the workflow data.
8. Do not auto-online when deploy returns `pending_approval`.

**Acceptance Criteria:**
- Saving a task into a workflow visibly tells users the workflow needs publishing.
- Publish success gives users a direct chance to immediately上线.
- Existing manual deploy and online buttons continue to work.

## Task 7: Verification

**Backend commands:**
- Run the narrow Maven test set for metadata sync:
  - `./mvnw -pl backend -Dtest=DorisMetadataSyncServiceTest test`
- If controller tests are added, include the exact controller test class in the same command.

**Frontend commands:**
- Always load nvm first:
  - `export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use`
- Run the smallest available frontend test/build:
  - `npm --prefix frontend run build`

**Smoke checks when local services are available:**
- DataStudio: open a Doris table that exists in Doris but not `data_table`, verify warning icon, right-panel sync prompt, successful sync, refreshed tab, and enabled delete/edit controls.
- Workflow: create a task with `workflowId`, verify jump prompt, publish the workflow, verify post-deploy online prompt.

**Reporting:**
- Update this checklist as tasks complete.
- If smoke cannot run because Doris or Dolphin is unavailable, state exactly which external runtime was missing.

### Run Notes - 2026-05-14

- Backend verification passed with Maven fallback because `./mvnw` is absent in this checkout:
  - `mvn -o -pl backend -am -Dtest=DorisMetadataSyncServiceTest,DataTableControllerTest -DfailIfNoTests=false test`
- Frontend verification passed after loading `.nvmrc` Node:
  - `export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use && npm --prefix frontend run build`
- Local smoke was not run because required external runtimes were not listening:
  - Doris FE/query checks failed on `127.0.0.1:8030` and `127.0.0.1:9030`
  - DolphinScheduler check failed on `127.0.0.1:12345`
