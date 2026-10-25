<template>
  <div :class="rootClass">
    <div v-if="hasTableTab && state" class="panel-shell">

      <section class="meta-panel">
        <header class="table-header">
          <div class="object-identity">
            <el-icon class="object-icon"><Grid /></el-icon>
            <div class="object-identity-text">
              <div class="table-name" :title="fullTableName">{{ state.table.tableName }}</div>
              <div class="object-location" :title="`${getSourceName(activeTabItem.sourceId)} / ${state.table.dbName}`">{{ getSourceName(activeTabItem.sourceId) }} / {{ state.table.dbName }}</div>
            </div>
          </div>
          <p class="table-comment" :title="state.table.tableComment || ''">{{ state.table.tableComment || '暂无表描述' }}</p>
          <div class="object-footer">
            <div class="table-meta-row">
              <el-tag v-if="state.table.sourceType" size="small" effect="plain">{{ state.table.sourceType }}</el-tag>
              <el-tag v-if="state.table.layer" size="small" effect="plain">{{ state.table.layer }}</el-tag>
              <el-tooltip content="表描述与字段描述的填写比例" placement="top"><span class="completeness-tag">完善度 {{ completeness }}%</span></el-tooltip>
            </div>
            <div class="table-actions">
              <template v-if="isEditing">
                <el-button data-test="action-cancel" size="small" :disabled="isSaving" @click="cancelCurrentEdit">取消</el-button>
                <el-button data-test="action-save" type="primary" size="small" :loading="isSaving" :disabled="isDemoMode" @click="saveCurrentEdit">{{ state.metaTab === 'columns' ? '保存修改' : '保存' }}</el-button>
              </template>
              <template v-else>
                <el-button data-test="action-generate" size="small" :loading="metadataGenerating" :disabled="isDemoMode || isPlatformMetadataMissing(state.table)" @click="generateMetadata(activeTabId)"><svg v-if="!metadataGenerating" class="metadata-action-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="m9 5 2.3 5.7L17 13l-5.7 2.3L9 21l-2.3-5.7L1 13l5.7-2.3L9 5Zm10-3 .9 2.1L22 5l-2.1.9L19 8l-.9-2.1L16 5l2.1-.9L19 2Z" /></svg>{{ metadataGenerating ? '生成中' : '智能元数据' }}</el-button>
                <el-tooltip v-if="editDisabledReason" :content="editDisabledReason" placement="top"><span><el-button data-test="action-edit" type="primary" size="small" disabled><svg class="metadata-action-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="m15 5 4 4M4 20l4-1L20 7a2.83 2.83 0 0 0-4-4L4 15v5ZM12 20h8" /></svg>编辑</el-button></span></el-tooltip>
                <el-button v-else-if="canEditCurrentTab" data-test="action-edit" type="primary" size="small" :disabled="isDemoMode" @click="startCurrentEdit"><svg class="metadata-action-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="m15 5 4 4M4 20l4-1L20 7a2.83 2.83 0 0 0-4-4L4 15v5ZM12 20h8" /></svg>编辑</el-button>
                <el-dropdown trigger="click">
                  <el-button size="small" text aria-label="更多对象操作" title="更多对象操作"><el-icon><MoreFilled /></el-icon></el-button>
                  <template #dropdown><el-dropdown-menu><el-dropdown-item data-test="action-delete" :disabled="isDemoMode || !!deleteDisabledReason" :title="deleteDisabledReason" @click="handleDeleteTable"><span class="delete-object-action">删除表</span></el-dropdown-item></el-dropdown-menu></template>
                </el-dropdown>
              </template>
            </div>
          </div>
        </header>

        <el-alert
          v-if="isPlatformMetadataMissing(state.table)"
          type="error"
          show-icon
          :closable="false"
          class="metadata-missing-alert"
        >
          <template #title>
            <div class="metadata-missing-title">
              <span>当前表在 Doris 中存在，平台中不存在。</span>
              <el-button
                class="metadata-sync-action"
                type="primary"
                link
                :loading="state.metadataSyncing"
                :disabled="isDemoMode"
                @click="syncMissingTableMetadata(activeTabId)"
              >
                立即同步
              </el-button>
            </div>
          </template>
        </el-alert>

        <div class="inspector-tabs-shell">
          <el-dropdown trigger="click" class="inspector-more" @command="state.metaTab = $event">
            <el-button size="small" text :class="{ 'is-selected': moreMetaItems.some(item => item.value === state.metaTab) }" aria-label="更多对象信息">{{ moreMetaItems.find(item => item.value === state.metaTab)?.label || '更多' }}<el-icon><ArrowDown /></el-icon></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="item in moreMetaItems" :key="item.value" :command="item.value"><span class="inspector-menu-item"><el-icon><Check v-if="state.metaTab === item.value" /></el-icon>{{ item.label }}</span></el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-tabs v-model="state.metaTab" type="card" class="meta-tabs detail-tabs">
          <el-tab-pane name="basic" label="概览">
            <DataStudioRightPanelBasic />
          </el-tab-pane>

          <el-tab-pane v-if="isDorisTable(state.table)" name="doris" label="Doris" lazy>
            <DataStudioRightPanelDoris />
          </el-tab-pane>

          <el-tab-pane name="columns" label="字段">
            <DataStudioRightPanelColumns />
          </el-tab-pane>

          <el-tab-pane name="ddl" label="DDL">
            <div v-loading="state.ddlLoading" class="meta-section meta-section-fill">
              <section class="section-block section-fill">
                <div class="section-header">
                  <div class="section-title">建表语句</div>
                  <div class="section-actions">
                    <el-button size="small" :disabled="!state.ddl" @click="copyDdl(activeTabId)">复制</el-button>
                  </div>
                </div>
                <div class="code-shell">
                  <el-scrollbar class="ddl-scroll">
                    <div v-if="state.ddl" class="ddl-content"><div v-for="(line, index) in state.ddl.split('\n')" :key="index" class="ddl-line"><span class="ddl-line-number" aria-hidden="true">{{ index + 1 }}</span><code>{{ line || ' ' }}</code></div></div>
                    <div v-else class="ddl-placeholder">{{ capabilityReason(state.table.typeDefinition, 'ddlExport') || state.ddlError || '加载中或暂无 DDL' }}</div>
                  </el-scrollbar>
                </div>
              </section>
            </div>
          </el-tab-pane>

          <el-tab-pane name="lineage" label="血缘" lazy>
      <DataStudioRightPanelLineage
        class="lineage-pane"
        :current-table="state.table"
        :upstream-tables="state.lineage.upstreamTables"
        :downstream-tables="state.lineage.downstreamTables"
        :write-tasks="state.tasks.writeTasks"
        :read-tasks="state.tasks.readTasks"
        :edges="state.lineage.edges || []"
        @open-table="openTableTab"
        @open-task="openTask"
        @create-task="(type) => goCreateRelatedTask(activeTabId, type)"
        @go-lineage="goLineage(activeTabId)"
      />
          </el-tab-pane>

          <el-tab-pane name="access" label="访问">
            <DataStudioRightPanelAccess />
          </el-tab-pane>

          <el-tab-pane name="freshness" label="数据新鲜度" lazy>
            <DataStudioRightPanelFreshness />
          </el-tab-pane>

          <el-tab-pane name="versions" label="变更" lazy>
            <div class="meta-section meta-section-fill">
              <!-- 不再重复 tab 名「变更记录」作为区块标题 -->
              <section class="section-block section-fill">
                <el-scrollbar class="meta-scroll">
                  <TableVersionHistoryPanel
                    :table-id="state.table?.id"
                    :active="state.metaTab === 'versions'"
                  />
                </el-scrollbar>
              </section>
            </div>
          </el-tab-pane>
        </el-tabs>
        </div>
      </section>



    </div>

    <div v-else class="right-empty">
      <el-empty :description="emptyDescription" :image-size="110" />
    </div>

    <SmartMetadataDialog />
  </div>
</template>

<script setup>
import { capabilityReason } from '@/utils/datasourceTypes'

import { getObjectSourceId } from '../objectSource'
import { computed, inject } from 'vue'
import { Grid, MoreFilled, ArrowDown, Check } from '@element-plus/icons-vue'
import DataStudioRightPanelLineage from './DataStudioRightPanelLineage.vue'
import TableVersionHistoryPanel from './TableVersionHistoryPanel.vue'
import DataStudioRightPanelBasic from './DataStudioRightPanelBasic.vue'
import DataStudioRightPanelDoris from './DataStudioRightPanelDoris.vue'
import DataStudioRightPanelColumns from './DataStudioRightPanelColumns.vue'
import DataStudioRightPanelAccess from './DataStudioRightPanelAccess.vue'
import DataStudioRightPanelFreshness from './DataStudioRightPanelFreshness.vue'
import SmartMetadataDialog from './SmartMetadataDialog.vue'
import { isDemoMode } from '@/demo/runtime'
import { computeMetadataCompleteness } from '../metadataGeneration'

const props = defineProps({
  visualVariant: {
    type: String,
    default: 'control-deck',
    validator: (value) => ['control-deck', 'paper-blueprint', 'signal-cards', 'minimal-blue', 'navicat-blue', 'tech-grid', 'clean-slate', 'data-console'].includes(value)
  }
})

const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanel requires dataStudioCtx')
}

const {
  getSourceName,
  clusterId,
  openTabs,
  activeTab,
  tabStates,
  isDorisTable,
  structureEditBlockedReason,
  isPlatformMetadataMissing,
  syncMissingTableMetadata,
  copyDdl,
  goLineage,
  goCreateRelatedTask,
  openTask,
  openTableTab,
  startMetaEdit,
  cancelMetaEdit,
  saveMetaEdit,
  startFieldsEdit,
  cancelFieldsEdit,
  saveFieldsEdit,
  handleDeleteTable,
  generateMetadata,
  metadataGenerating
} = ctx

const activeTabId = computed(() => String(activeTab.value || ''))

const activeTabItem = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return (openTabs.value || []).find((item) => String(item?.id) === id) || null
})

const rootClass = computed(() => [
  'right-root',
  `variant-${props.visualVariant}`
])

const emptyDescription = computed(() => {
  if (activeTabItem.value?.kind === 'query') return '没有可用的对象信息'
  return '选择表后在此查看表信息、列信息、DDL 与数据血缘'
})

const hasTableTab = computed(() => {
  return !!activeTabItem.value && activeTabItem.value.kind !== 'query'
})

const state = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return tabStates[id] || null
})

const moreMetaItems = computed(() => [
  ...(isDorisTable(state.value?.table) ? [{ value: 'doris', label: '引擎配置' }] : []),
  { value: 'access', label: '访问统计' },
  { value: 'versions', label: '变更记录' }
])

const fullTableName = computed(() => {
  const table = state.value?.table
  if (!table) return ''
  return table.dbName ? `${table.dbName}.${table.tableName}` : String(table.tableName || '')
})

const completeness = computed(() =>
  computeMetadataCompleteness({
    tableComment: state.value?.table?.tableComment,
    fields: state.value?.fields || [],
  })
)

// 头部操作区随 tab 联动：概览、字段与引擎配置（分桶/副本，与概览共用同一元数据编辑链路）可编辑
const canEditCurrentTab = computed(() => {
  const tab = String(state.value?.metaTab || '')
  if (tab === 'doris') return isDorisTable(state.value?.table)
  return ['basic', 'columns'].includes(tab)
})

const isEditing = computed(() =>
  state.value?.metaTab === 'columns' ? !!state.value?.fieldsEditing : !!state.value?.metaEditing
)

const isSaving = computed(() =>
  state.value?.metaTab === 'columns' ? !!state.value?.fieldSubmitting : !!state.value?.metaSaving
)

// 与原先各内容块内按钮一致的禁用原因；无原因则可操作
const editDisabledReason = computed(() => {
  if (!canEditCurrentTab.value) return ''
  if (isPlatformMetadataMissing(state.value?.table)) return '请先同步到平台元数据后再操作'
  if (isDorisTable(state.value?.table) && !getObjectSourceId(state.value?.table, clusterId.value)) return '请选择 Doris 集群后再操作'
  if (state.value?.metaTab === 'columns') return structureEditBlockedReason(state.value?.table)
  return ''
})

// 删表会删除物理表，不受当前 tab 影响
const deleteDisabledReason = computed(() =>
  editDisabledReason.value || structureEditBlockedReason(state.value?.table)
)

const startCurrentEdit = () => {
  if (state.value?.metaTab === 'columns') startFieldsEdit(activeTabId.value)
  else startMetaEdit(activeTabId.value)
}

const cancelCurrentEdit = () => {
  if (state.value?.metaTab === 'columns') cancelFieldsEdit(activeTabId.value)
  else cancelMetaEdit(activeTabId.value)
}

const saveCurrentEdit = () => {
  if (state.value?.metaTab === 'columns') saveFieldsEdit(activeTabId.value)
  else saveMetaEdit(activeTabId.value)
}

</script>

<style scoped>
.right-root {
  --bg: #f4f8ff;
  --panel: #ffffff;
  --panel-muted: #f6f9ff;
  --line: #d8e3f1;
  --line-strong: #c3d4e7;
  --text: #19314d;
  --text-sub: #5d7491;
  --text-muted: #8298b2;
  --accent: #2f6aa3;
  --accent-soft: #e9f1fb;
  --tab-bg: #eef4fc;
  --tab-active: #ffffff;
  --flow-task-bg: #f7fbff;
  --flow-table-bg: #ffffff;

  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  color: var(--text);
  font-family: inherit;
  overflow: hidden;
}

/* 统一滚动条样式 */
.right-root :deep(*::-webkit-scrollbar) {
  width: 6px;
  height: 6px;
}

.right-root :deep(*::-webkit-scrollbar-track) {
  background: transparent;
  border-radius: 3px;
}

.right-root :deep(*::-webkit-scrollbar-thumb) {
  background: var(--line);
  border-radius: 3px;
  transition: background 0.2s ease;
}

.right-root :deep(*::-webkit-scrollbar-thumb:hover) {
  background: var(--line-strong);
}

.variant-paper-blueprint {
  --bg: #f8fbff;
  --panel: #ffffff;
  --panel-muted: #f8fbff;
  --line: #d7e2f1;
  --line-strong: #bdd0e6;
  --text: #1b334f;
  --text-sub: #5a7394;
  --text-muted: #8699b1;
  --accent: #356ea8;
  --accent-soft: #ecf3fd;
  --tab-bg: #f1f6fd;
  --tab-active: #ffffff;
  --flow-task-bg: #f8fbff;
  --flow-table-bg: #ffffff;
}

.variant-signal-cards {
  --bg: #eff6ff;
  --panel: #ffffff;
  --panel-muted: #f3f8ff;
  --line: #d2dff0;
  --line-strong: #b7cae3;
  --text: #163050;
  --text-sub: #55739a;
  --text-muted: #7f97b6;
  --accent: #245f99;
  --accent-soft: #e4eefb;
  --tab-bg: #eaf2fd;
  --tab-active: #ffffff;
  --flow-task-bg: #f2f8ff;
  --flow-table-bg: #ffffff;
}

.variant-minimal-blue {
  --bg: #f9fbff;
  --panel: #ffffff;
  --panel-muted: #ffffff;
  --line: #dce6f3;
  --line-strong: #c7d7ea;
  --text: #1f3652;
  --text-sub: #637b98;
  --text-muted: #8ba0b9;
  --accent: #3c78b1;
  --accent-soft: #edf4fe;
  --tab-bg: #f3f7fd;
  --tab-active: #ffffff;
  --flow-task-bg: #ffffff;
  --flow-table-bg: #ffffff;
}

.variant-navicat-blue {
  --bg: #eef2f8;
  --panel: #ffffff;
  --panel-muted: #f5f8fc;
  --line: #cfd9e6;
  --line-strong: #bac8d9;
  --text: #24364e;
  --text-sub: #5f738c;
  --text-muted: #8697ac;
  --accent: #3f6f9e;
  --accent-soft: #e9f0f9;
  --tab-bg: #e8edf4;
  --tab-active: #ffffff;
  --flow-task-bg: #f5f8fc;
  --flow-table-bg: #ffffff;
}

.variant-tech-grid {
  --bg: #f2f5fa;
  --panel: #ffffff;
  --panel-muted: #f8fafd;
  --line: #dae1eb;
  --line-strong: #c5d0df;
  --text: #1a2f47;
  --text-sub: #5a6e87;
  --text-muted: #7d91ab;
  --accent: #2563b8;
  --accent-soft: #e6eef8;
  --tab-bg: #eff3f9;
  --tab-active: #ffffff;
  --flow-task-bg: #f8fafd;
  --flow-table-bg: #ffffff;
}

.variant-clean-slate {
  --bg: #fafbfd;
  --panel: #ffffff;
  --panel-muted: #ffffff;
  --line: #e4e8f0;
  --line-strong: #d0d7e3;
  --text: #212d3f;
  --text-sub: #657186;
  --text-muted: #8a99b0;
  --accent: #4178c1;
  --accent-soft: #eff5fd;
  --tab-bg: #f5f8fc;
  --tab-active: #ffffff;
  --flow-task-bg: #ffffff;
  --flow-table-bg: #fafbfd;
}

.metadata-missing-alert :deep(.metadata-sync-action.el-button--primary.is-link) {
  color: #2563eb;
}

.metadata-missing-alert :deep(.metadata-sync-action.el-button--primary.is-link:hover),
.metadata-missing-alert :deep(.metadata-sync-action.el-button--primary.is-link:focus) {
  color: #1d4ed8;
}

.variant-data-console {
  --bg: #f0f4f9;
  --panel: #ffffff;
  --panel-muted: #f6f9fc;
  --line: #d4dce8;
  --line-strong: #bfcbdb;
  --text: #1d3047;
  --text-sub: #5b6f89;
  --text-muted: #7e93ac;
  --accent: #2d66a4;
  --accent-soft: #e8f0f9;
  --tab-bg: #ebeef4;
  --tab-active: #ffffff;
  --flow-task-bg: #f6f9fc;
  --flow-table-bg: #ffffff;
}

.panel-shell {
  --right-top: 340px;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.meta-panel {
  flex: 1;
  border: none;
  border-radius: 0;
  background: var(--panel);
  overflow: hidden;
  min-height: 0;
  /* 头部与提示按内容高度，tab 区占满剩余空间 */
  display: flex;
  flex-direction: column;
}

.table-header {
  display: flex;
  flex-direction: column;
  border-bottom: 1px solid var(--line);
  background: var(--panel);
}

.table-name {
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-meta-row {
  display: flex;
  align-items: center;
  min-width: 0;
}

.completeness-tag {
  font-weight: 400;
  flex: none;
}

.table-comment {
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.table-actions {
  display: flex;
  align-items: center;
}

/* 窄面板下按钮不换行，必要时内缩 */
.table-actions :deep(.el-button) {
  padding-left: 9px;
  padding-right: 9px;
}

.metadata-missing-alert {
  border-radius: 0;
  border-width: 0 0 1px;
}

.metadata-missing-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.lineage-pane {
  min-height: 0;
  height: 100%;
}

.right-root.is-pane-resizing {
  user-select: none;
}

.meta-tabs {
  flex: 1;
  min-height: 0;
}

.table-header,
.metadata-missing-alert {
  flex: none;
}

/* tab 使用 el-tabs 原生下划线样式，这里只做布局约束：
   头部按内容高度，内容区占满剩余空间（不写死头部高度，避免样式变化后错位） */
.detail-tabs {
  display: flex;
  flex-direction: column;
}

:deep(.detail-tabs > .el-tabs__header) {
  margin: 0;
  box-sizing: border-box;
}

/* 六个 tab 在右栏宽度内排开：原生 item 默认左右各 20px，共占 240px 纯内边距，
   在最窄的 400px 右栏放不下。收到 8px 后 tab 栏约 361px，1280px 视口（右栏取下限
   400px、可用 378px）也不会出现滚动箭头，且不必再从中间查询区抢宽度。
   字号取 13px 与本面板的区块标题一致：el-tabs 默认 14px / 40px 行高在这个
   密集侧栏里偏大（面板内其余文字为 12-13px）。 */
:deep(.detail-tabs .el-tabs__item) {
  padding: 0 7px;
  font-size: var(--studio-font-reading);
  height: 34px;
  line-height: 34px;
}

:deep(.detail-tabs > .el-tabs__content) {
  flex: 1;
  min-height: 0;
  box-sizing: border-box;
}

:deep(.detail-tabs .el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.meta-tabs :deep(.meta-section) {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.meta-tabs :deep(.meta-section-fill) {
  flex: 1;
  min-height: 0;
}

/* 区块只做分组与间距，不再自带边框/底色：里面的表格、描述列表、代码框都
   已经有自己的边框，再套一层卡片就是「面板边框 + 卡片边框 + 内容边框」三层套娃 */
.meta-tabs :deep(.section-block) {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.meta-tabs :deep(.section-fill) {
  flex: 1;
}

.meta-tabs :deep(.section-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.meta-tabs :deep(.section-actions) {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.meta-tabs :deep(.meta-scroll) {
  flex: 1;
  min-height: 0;
  max-height: 100%;
  overflow: auto;
}

.meta-tabs :deep(.meta-scroll .el-scrollbar__view) {
  padding-right: 4px;
  box-sizing: border-box;
}

.code-shell {
  flex: 1;
  min-height: 0;
  border: 1px solid var(--line);
}

.ddl-scroll {
  height: 100%;
  font-family: 'JetBrains Mono', 'IBM Plex Mono', 'Fira Mono', Menlo, Consolas, monospace;
}

.ddl-content {
  margin: 0;
  line-height: 1.6;
  white-space: pre;
}

.ddl-placeholder {
  padding: 10px 12px;
  font-size: var(--studio-font-reading);
  color: var(--text-muted);
}

.lineage-panel {
  border: none;
  border-radius: 0;
  background: var(--panel);
  padding: 10px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
  min-height: 0;
  max-height: none;
  overflow-y: auto;
  overflow-x: hidden;
  flex: 1;
}

.right-empty {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 0;
  background: var(--panel);
}
.right-root {
  font-size: var(--studio-font-ui);
}
.table-actions {
  justify-content: flex-start;
  flex-wrap: wrap;
}
.table-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.meta-tabs :deep(.el-descriptions__cell),
.meta-tabs :deep(.el-table) {
  font-size: var(--studio-font-ui);
}
.object-identity {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  min-width: 0;
}
.object-icon {
  color: var(--accent);
  font-size: 14px;
  margin-top: 2px;
  flex: none;
}
.object-identity-text {
  min-width: 0;
  flex: 1;
}
.table-name {
  font-size: var(--studio-font-reading);
  font-weight: 500;
  line-height: 18px;
}
.object-location {
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-comment {
  margin: 0;
  font-size: var(--studio-font-ui);
  line-height: 18px;
  white-space: normal;
  overflow-wrap: anywhere;
}
.object-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
.table-meta-row {
  gap: 5px;
}
.table-meta-row :deep(.el-tag) {
  font-size: var(--studio-font-detail);
  height: 18px;
  padding: 0 4px;
  border: none;
  border-radius: 3px;
  background: #f1f5f9;
  color: #526174;
}
.completeness-tag {
  font-size: var(--studio-font-detail);
  color: var(--text-sub);
  white-space: nowrap;
}
.table-actions {
  margin-left: auto;
  gap: 4px;
}
.table-actions :deep(.el-button) {
  font-size: var(--studio-font-ui);
  padding: 4px 6px;
}
.table-actions :deep(.el-dropdown .el-button) {
  padding: 4px;
}
.delete-object-action {
  color: #dc2626;
}

.table-header {
  padding: 12px 14px;
  gap: 8px;
}
.object-location {
  font-size: var(--studio-font-detail);
  color: #8a96a6;
  line-height: 16px;
}
.table-comment {
  color: #627186;
}
.table-actions :deep(.el-button) {
  height: 26px;
}
.table-actions :deep(.el-icon) {
  margin-right: 3px;
}
.table-actions :deep(.el-dropdown .el-icon) {
  margin-right: 0;
}
.inspector-tabs-shell {
  position: relative;
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.inspector-more {
  position: absolute;
  right: 7px;
  top: 4px;
  z-index: 2;
}
.inspector-more :deep(.el-button) {
  color: #718096;
  height: 26px;
  font-size: var(--studio-font-ui);
  padding: 4px 6px;
}
.inspector-more :deep(.el-icon) {
  font-size: var(--studio-font-detail);
  margin-left: 4px;
}
.inspector-more :deep(.is-selected) {
  color: #3f6fa7;
  background: #edf3fb;
}
.inspector-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--studio-font-reading);
}
.inspector-menu-item .el-icon {
  width: 12px;
}
:deep(.detail-tabs > .el-tabs__header) {
  padding: 0 100px 0 0;
  background: #f5f7fa;
  border-bottom: 1px solid #dce2e9;
}
:deep(.detail-tabs > .el-tabs__header .el-tabs__nav) {
  border: 0;
  border-radius: 0;
}
:deep(.detail-tabs > .el-tabs__header .el-tabs__item) {
  padding: 0 10px;
  font-size: var(--studio-font-ui);
  font-weight: 400;
  height: 34px;
  border-left: 0;
  border-right: 1px solid #dce2e9;
  border-top: 2px solid transparent;
  border-bottom: 1px solid #dce2e9;
}
:deep(.detail-tabs > .el-tabs__header .el-tabs__item.is-active) {
  font-weight: 500;
  background: #fff;
  color: #3f6fa7;
  border-top-color: #517aa8;
  border-bottom-color: #fff;
}
:deep(.detail-tabs #tab-doris),
:deep(.detail-tabs #tab-access),
:deep(.detail-tabs #tab-versions) {
  display: none;
}
:deep(.detail-tabs > .el-tabs__content) {
  padding: 14px;
}
.meta-tabs :deep(.section-title) {
  font-size: var(--studio-font-ui);
  font-weight: 500;
  color: #56677b;
}
.meta-tabs :deep(.el-table th.el-table__cell) {
  background: #f5f7fa;
  color: #68778c;
  font-size: var(--studio-font-ui);
}
.meta-tabs :deep(.el-table td.el-table__cell) {
  font-size: var(--studio-font-ui);
  padding: 5px 0;
}
.meta-tabs :deep(.el-descriptions__label.is-bordered-label) {
  background: #f7f9fb;
  color: #7a8798;
  font-size: var(--studio-font-ui);
  font-weight: 400;
}
.meta-tabs :deep(.el-descriptions__content.is-bordered-content) {
  font-size: var(--studio-font-ui);
}
.meta-tabs :deep(.metric-card) {
  border: 0;
  border-radius: 0;
  background: transparent;
  padding: 8px 0;
}
.code-shell {
  border-radius: 0;
  background: #fafbfc;
}
.ddl-content {
  font-size: var(--studio-font-ui);
  color: #526174;
}
.right-empty :deep(.el-empty__description p) {
  font-size: var(--studio-font-reading);
  color: #8a96a6;
}

:deep(.detail-tabs) {
  --el-tabs-header-height: 34px;
}
:deep(.detail-tabs > .el-tabs__header) {
  height: 34px;
  min-height: 34px;
  flex: 0 0 34px;
  align-items: flex-start;
}
:deep(.detail-tabs > .el-tabs__header .el-tabs__nav-wrap),
:deep(.detail-tabs > .el-tabs__header .el-tabs__nav) {
  height: 34px;
  margin: 0;
}
:deep(.detail-tabs > .el-tabs__header .el-tabs__item) {
  margin: 0;
}
:deep(.detail-tabs > .el-tabs__header .el-tabs__item.is-active) {
  position: relative;
  z-index: 1;
}
.ddl-content {
  padding: 8px 0;
  min-width: max-content;
}
.ddl-line {
  display: flex;
  line-height: 22px;
}
.ddl-line-number {
  width: 30px;
  flex: none;
  text-align: right;
  padding-right: 8px;
  color: #a1adba;
  user-select: none;
  background: #fafbfc;
  margin-right: 10px;
}
.ddl-line code {
  font: inherit;
  color: #41536a;
  padding-right: 10px;
}

.metadata-action-icon {
  width: 14px;
  height: 14px;
  flex: none;
  margin-right: 4px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.table-actions :deep(.el-button > span) {
  display: inline-flex;
  align-items: center;
}
</style>
