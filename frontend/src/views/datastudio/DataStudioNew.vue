<template>
  <div :class="['data-studio', { 'is-resizing': isResizing }]">
    <div ref="studioLayoutRef" class="studio-layout">
      <!-- Left: Database Tree -->
      <aside class="studio-sidebar" :style="sidebarPaneStyle">
        <div class="sidebar-controls">
          <div class="catalog-search-row">
            <el-input v-model="searchKeyword" placeholder="搜索表名或注释" aria-label="搜索表名或注释" clearable size="small" class="search-input">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-button size="small" text :loading="dbLoading" aria-label="刷新全部连接" title="刷新全部连接" @click="refreshCatalog"><el-icon><Refresh /></el-icon></el-button>
            <el-button size="small" text :disabled="isDemoMode" aria-label="新建表" title="新建表" @click="handleCreateTable"><el-icon><Plus /></el-icon></el-button>
            <el-dropdown trigger="click" @command="handleCatalogSort">
              <el-button size="small" text aria-label="目录排序选项" :title="`排序：${catalogSortOptions.find(item => item.value === sortField)?.label}，${sortOrder === 'asc' ? '升序' : '降序'}`"><el-icon><Sort /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu class="catalog-sort-menu">
                  <el-dropdown-item disabled><span class="catalog-menu-caption">排序依据</span></el-dropdown-item>
                  <el-dropdown-item v-for="item in catalogSortOptions" :key="item.value" :command="item.value" :aria-checked="sortField === item.value">
                    <span class="catalog-sort-option"><el-icon class="catalog-view-check"><Check v-if="sortField === item.value" /></el-icon>{{ item.label }}</span>
                  </el-dropdown-item>
                  <el-dropdown-item divided disabled><span class="catalog-menu-caption">排列方向</span></el-dropdown-item>
                  <el-dropdown-item command="asc" :aria-checked="sortOrder === 'asc'"><span class="catalog-sort-option"><el-icon class="catalog-view-check"><Check v-if="sortOrder === 'asc'" /></el-icon>升序</span></el-dropdown-item>
                  <el-dropdown-item command="desc" :aria-checked="sortOrder === 'desc'"><span class="catalog-sort-option"><el-icon class="catalog-view-check"><Check v-if="sortOrder === 'desc'" /></el-icon>降序</span></el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-dropdown trigger="click" @command="catalogDisplayMode = $event">
              <el-button size="small" text aria-label="目录视图选项" title="目录视图选项"><el-icon><Operation /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu class="catalog-view-menu">
                  <el-dropdown-item command="compact" :aria-checked="catalogDisplayMode === 'compact'" role="menuitemradio">
                    <div class="catalog-view-option"><el-icon class="catalog-view-check"><Check v-if="catalogDisplayMode === 'compact'" /></el-icon><div><span>紧凑视图</span><small>仅显示对象名称</small></div></div>
                  </el-dropdown-item>
                  <el-dropdown-item command="detailed" :aria-checked="catalogDisplayMode === 'detailed'" role="menuitemradio">
                    <div class="catalog-view-option"><el-icon class="catalog-view-check"><Check v-if="catalogDisplayMode === 'detailed'" /></el-icon><div><span>完整视图</span><small>显示注释和统计信息</small></div></div>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>

        </div>

        <div v-loading="dbLoading" class="db-tree">
          <el-scrollbar class="db-tree-scroll">
            <el-tree
              ref="catalogTreeRef"
              :data="catalogRoots"
              node-key="nodeKey"
              :props="catalogTreeProps"
              lazy
              :indent="14"
              highlight-current
              :expand-on-click-node="true"
              :current-node-key="selectedTableKey"
              :filter-node-method="filterCatalogNode"
              :load="loadCatalogNode"
              class="catalog-tree"
              @node-click="handleCatalogNodeClick"
            >
              <template #default="{ data }">
                <DataStudioCatalogNode :data="data" :display-mode="catalogDisplayMode" />
            </template>
            </el-tree>
          </el-scrollbar>
        </div>
      </aside>

      <div class="sidebar-resizer" title="拖动调整宽度" @mousedown="startResize"></div>

      <!-- Right: Workspace -->
      <section class="studio-workspace">
        <div class="workspace-body">
          <el-tooltip v-if="openTabs.length" :content="detailsVisible ? '隐藏对象详情' : '显示对象详情'" placement="bottom" :show-after="400" :trigger="['hover', 'focus']" :trigger-keys="[]">
            <span class="workspace-details-control">
              <el-button
                class="workspace-details-toggle"
                size="small"
                text
                :aria-expanded="detailsVisible"
                :aria-label="detailsVisible ? '隐藏对象详情' : '显示对象详情'"
                aria-controls="studio-object-details"
                @click="toggleDetails"
              >
                <svg class="panel-toggle-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <rect x="3" y="3" width="18" height="18" rx="2" />
                  <path d="M15 3v18" />
                  <path :d="detailsVisible ? 'm8 9 3 3-3 3' : 'm10 9-3 3 3 3'" />
                </svg>
              </el-button>
            </span>
          </el-tooltip>
          <PersistentTabs
            v-if="openTabs.length"
            v-model="activeTab"
            :tabs="openTabs"
            type="card"
            closable
            addable
            lazy
            class="workspace-tabs"
            style="height: 100%;"
            @tab-remove="handleTabRemove"
            @tab-add="handleTabAdd"
            @close-left="handleCloseLeft"
            @close-right="handleCloseRight"
            @close-all="handleCloseAll"
          >
            <template #label="{ tab }">
              <div class="tab-label" :title="`${tab.tableName} · ${getTabSubtitle(tab)}`">
                <el-icon class="tab-icon"><Document v-if="tab.kind === 'query'" /><Grid v-else /></el-icon>
                <span class="tab-title">{{ tab.tableName }}</span>
              </div>
            </template>

            <template #default="{ tab }">
              <div class="tab-grid">
                <div
                  :ref="(el) => setLeftPaneRef(tab.id, el)"
                  class="tab-left"
                  :style="getLeftPaneStyle(tab.id)"
                >
                  <DataStudioQueryPanel :tab="tab" />

                  <div class="left-resizer" title="拖动调整高度" @mousedown="startLeftResize(tab.id, $event)"></div>

                  <DataStudioResultPanel :tab="tab" />
                </div>

              </div>
            </template>
          </PersistentTabs>

          <div v-else class="empty-state">
            <el-icon class="empty-workspace-icon"><Document /></el-icon>
            <h2>开始探索数据</h2>
            <p>展开左侧数据源，选择一张表查看数据与结构</p>
            <el-button type="primary" size="small" @click="handleTabAdd"><el-icon><Plus /></el-icon>新建 SQL 查询</el-button>
            <span class="empty-workspace-hint">查询结果在下方显示 · 对象详情在右侧查看</span>
          </div>
	        </div>
	      </section>

      <div v-show="detailsVisible" class="workspace-resizer" title="拖动调整宽度" @mousedown="startRightResize"></div>

      <!-- Right: Meta/Lineage -->
      <aside v-show="detailsVisible" id="studio-object-details" class="studio-right" :style="rightPaneStyle">
        <header class="pane-heading"><span>对象详情</span></header>
        <DataStudioRightPanel visual-variant="clean-slate" />
      </aside>
    </div>

    <CreateTableDrawer v-if="createDrawerVisible" v-model="createDrawerVisible" @created="handleCreateSuccess" />
    <TaskEditDrawer ref="taskDrawerRef" @success="handleTaskSuccess" />

  </div>
</template>

<script setup>
import { defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, provide, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Search,
  Plus,
  Refresh,
  Operation, Sort, Document, Grid, Check,
} from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'
import { taskApi } from '@/api/task'
import PersistentTabs from '@/components/PersistentTabs.vue'
import TaskEditDrawer from '@/components/TaskEditDrawer.vue'
import DataStudioResultPanel from '@/views/datastudio/components/DataStudioResultPanel.vue'
import DataStudioQueryPanel from '@/views/datastudio/components/DataStudioQueryPanel.vue'
import DataStudioCatalogNode from '@/views/datastudio/components/DataStudioCatalogNode.vue'
import { isDemoMode } from '@/demo/runtime'
import {
  formatDuration,
  formatDateTime,
  isAggregateTable,
  isReplicaWarning,
} from './tableFormat'
import { useTabPersistence } from './composables/useTabPersistence'
import { useResizablePanes } from './composables/useResizablePanes'
import { useSqlCompletion } from './composables/useSqlCompletion'
import { useTabRouting } from './composables/useTabRouting'
import { useCatalogTree } from './composables/useCatalogTree'
import { useQueryExecution } from './composables/useQueryExecution'
import { useResultChart } from './composables/useResultChart'
import { useTableMetaEditing } from './composables/useTableMetaEditing'
import { useStudioTabs } from './composables/useStudioTabs'
import { useTableActions } from './composables/useTableActions'
import { useMetadataGeneration } from './composables/useMetadataGeneration'

const CreateTableDrawer = defineAsyncComponent({
  loader: () => import('@/views/datastudio/CreateTableDrawer.vue'),
  suspensible: false
})

const DataStudioRightPanel = defineAsyncComponent({
  loader: () => import('@/views/datastudio/components/DataStudioRightPanel.vue'),
  suspensible: false
})

const clusterId = ref(null)
const catalogDisplayMode = ref('compact')
const catalogSortOptions = [
  { value: 'tableName', label: '名称' },
  { value: 'createdAt', label: '创建时间' },
  { value: 'rowCount', label: '行数' },
  { value: 'storageSize', label: '数据量' },
  { value: 'dorisUpdateTime', label: '更新时间' },
]
const handleCatalogSort = (command) => {
  if (command === 'asc' || command === 'desc') sortOrder.value = command
  else sortField.value = command
}
const detailsVisible = ref(true)
const toggleDetails = async () => {
  detailsVisible.value = !detailsVisible.value
  await nextTick()
  if (activeTab.value) syncResultPaneLayout(activeTab.value)
}

const route = useRoute()
const router = useRouter()
const createDrawerVisible = ref(false)

const lineageCache = reactive({})
const suppressRouteSync = ref(false)

const openTabs = ref([])
const activeTab = ref('')
const tabStates = reactive({})
const queryTabCounter = ref(1)
const tableObserver = ref(null)

// 目录树加载与缓存（P2-2 F7）：schema/table/column 缓存继续共享给 SQL 补全与路由同步。
const {
  dbLoading,
  dataSources,
  activeSource,
  schemaStore,
  schemaLoading,
  schemaCountLoading,
  activeSchema,
  tableLoading,
  tableStore,
  columnStore,
  catalogTreeRef,
  catalogTreeProps,
  catalogRoots,
  searchKeyword,
  sortField,
  sortOrder,
  selectedTableKey,
  tableRefs,
  loadClusters,
  getDatasourceById,
  activateDatasource,
  loadSchemas,
  loadTables,
  setTableRef,
  getTableKey,
  findCachedTableById,
  isDatasourceIconInactive,
  isCatalogNodeActive,
  getDatasourceIconUrl,
  isViewTable,
  getTableRowCount,
  getTableStorageSize,
  getTableCount,
  getTableCountByType,
  getProgressWidth,
  filterCatalogNode,
  loadCatalogNode,
  handleCatalogNodeClick,
  refreshCatalog,
  refreshDatasourceNode,
  refreshSchemaNode,
  focusTableInSidebar,
  focusActiveTableInSidebar,
} = useCatalogTree({
  clusterId,
  tabStates,
  openTabs,
  activeTab,
  tableObserver,
  handleQuerySourceSelect: (...args) => handleQuerySourceSelect(...args),
  handleQueryDatabaseSelect: (...args) => handleQueryDatabaseSelect(...args),
  openTableTab: (...args) => openTableTab(...args),
})

// 三栏布局尺寸与拖拽（P2-2 F5）：syncResultPaneLayout 为前向引用，惰性传入
const {
  studioLayoutRef,
  sidebarPaneStyle,
  rightPaneStyle,
  normalizePaneRatios,
  isResizing,
  leftPaneHeights,
  leftPaneRefs,
  setLeftPaneRef,
  getLeftPaneStyle,
  startResize,
  startRightResize,
  startLeftResize,
} = useResizablePanes({
  activeTab,
  syncResultPaneLayout: (tabId) => syncResultPaneLayout(tabId),
})

// SQL 补全数据源（P2-2 F6）：共享目录缓存直接注入，loadTables/activateDatasource 惰性前向引用
// 仅取组件实际使用的入口；其余补全函数仅在 getSqlCompletionContext 内部使用
const {
  getSchemaOptions,
  getSqlCompletionContext,
} = useSqlCompletion({
  tabStates,
  schemaStore,
  tableStore,
  columnStore,
  loadTables: (...args) => loadTables(...args),
  activateDatasource: (...args) => activateDatasource(...args),
})

// 标签页 URL 路由同步（P2-2 F8）：catalog 加载器与 openTableTab 惰性前向引用
const {
  syncRouteWithTab,
  clearRouteTabQuery,
  clearCreateQuery,
  syncFromRoute,
} = useTabRouting({
  suppressRouteSync,
  tabStates,
  openTabs,
  activeTab,
  activeSource,
  activeSchema,
  tableStore,
  loadSchemas: (...args) => loadSchemas(...args),
  loadTables: (...args) => loadTables(...args),
  openTableTab: (...args) => openTableTab(...args),
})

// 查询执行与历史（P2-2 F9）：执行、停止、结果集标准化、导出和历史分页从主组件抽离
const {
  historyData,
  historyPager,
  historyLoading,
  buildDefaultSql,
  clearQueryTimer,
  stopNowTickerIfIdle,
  getStatementStatusTagType,
  isResultSetType,
  getResultSetCountText,
  getResultSetAlertType,
  getDisplayResultSets,
  handleSqlSelectionChange,
  handleQuerySourceSelect,
  handleQueryDatabaseSelect,
  syncAutoSelectSqlIfSchemaMismatch,
  executeQuery,
  stopQuery,
  getLiveDurationMs,
  resetQuery,
  exportResult,
  fetchHistory,
  applyHistory,
  parseResultTabIndex,
  getResultRowKeyPrefix,
  getResultSetByIndex,
} = useQueryExecution({
  clusterId,
  activeSource,
  activeSchema,
  schemaStore,
  tabStates,
  openTabs,
  activeTab,
  loadSchemas: (...args) => loadSchemas(...args),
  loadTables: (...args) => loadTables(...args),
  syncRouteWithTab: (...args) => syncRouteWithTab(...args),
  disposeChart: (...args) => disposeChart(...args),
  applyDefaultChartSelection: (...args) => applyDefaultChartSelection(...args),
  syncResultPaneLayout: (...args) => syncResultPaneLayout(...args),
})

// 结果图表（P2-2 F10）：ECharts 实例、DOM ref、默认选列、渲染和清理从主组件抽离
const {
  getNumericColumns,
  applyDefaultChartSelection,
  canRenderChart,
  setChartRef,
  syncResultPaneLayout,
  disposeChart,
} = useResultChart({
  activeTab,
  tabStates,
  parseResultTabIndex,
  getResultSetByIndex,
})

	const taskDrawerRef = ref(null)

const layerOptions = [
  { label: 'ODS - 原始数据层', value: 'ODS' },
  { label: 'DWD - 明细数据层', value: 'DWD' },
  { label: 'DIM - 维度数据层', value: 'DIM' },
  { label: 'DWS - 汇总数据层', value: 'DWS' },
  { label: 'ADS - 应用数据层', value: 'ADS' }
]

const getSourceName = (sourceId) => {
  if (!sourceId) return ''
  const source = dataSources.value.find((item) => String(item.id) === String(sourceId))
  return source?.clusterName || source?.name || ''
}

const getTabSubtitle = (tab) => {
  if (!tab) return ''
  const sourceName = getSourceName(tab.sourceId)
  const dbName = tab.dbName || ''
  if (sourceName && dbName) {
    return `${sourceName} / ${dbName}`
  }
  return sourceName || dbName || ''
}

const hasText = (value) => value !== null && value !== undefined && String(value).trim() !== ''
const hasPositiveNumber = (value) => {
  const num = Number(value)
  return Number.isFinite(num) && num > 0
}

const getTableSourceType = (table) => {
  if (!table) return ''
  const explicitType = String(table.sourceType || table.datasourceType || table.dataSourceType || '')
    .trim()
    .toUpperCase()
  if (explicitType) return explicitType
  const sourceId = table.sourceId || table.clusterId || table.datasourceId
  if (!sourceId) return ''
  return String(getDatasourceById(sourceId)?.sourceType || '')
    .trim()
    .toUpperCase()
}

const isDorisTable = (table) => {
  if (!table) return false
  const sourceType = getTableSourceType(table)
  if (sourceType === 'MYSQL') return false
  if (sourceType === 'DORIS') return true
  if (table.isSynced === 1) return true
  return (
    hasText(table.tableModel) ||
    hasPositiveNumber(table.bucketNum) ||
    hasPositiveNumber(table.replicaNum) ||
    hasText(table.distributionColumn) ||
    hasText(table.keyColumns) ||
    hasText(table.partitionColumn)
  )
}

const MISSING_PLATFORM_METADATA_MESSAGE = '该表未同步到平台，需同步后才能操作'

const isPlatformMetadataMissing = (table) => {
  if (!table) return false
  if (table.metadataMissing === true || table.metadataStatus === 'missing') return true
  return isDorisTable(table) && !table.id && hasText(table.dbName) && hasText(table.tableName)
}

const warnPlatformMetadataMissing = (table) => {
  if (!isPlatformMetadataMissing(table)) return false
  ElMessage.warning(MISSING_PLATFORM_METADATA_MESSAGE)
  return true
}

// 表元数据与字段编辑（P2-2 F10b）：保存、取消回滚、字段草稿和业务域加载从主组件抽离
const {
  businessDomainOptions,
  loadBusinessDomains,
  loadMetaDataDomainOptions,
  getMetaDataDomainOptions,
  handleMetaBusinessDomainChange,
  getFieldRows,
  startMetaEdit,
  cancelMetaEdit,
  saveMetaEdit,
  startFieldsEdit,
  cancelFieldsEdit,
  saveFieldsEdit,
  addField,
  removeField,
} = useTableMetaEditing({
  clusterId,
  tabStates,
  openTabs,
  activeTab,
  selectedTableKey,
  tableRefs,
  tableStore,
  getTableKey,
  isDorisTable,
  isAggregateTable,
  warnPlatformMetadataMissing,
})

const getUpstreamCount = (tableId) => {
  if (!tableId) return 0
  return lineageCache[tableId]?.upstreamTables?.length || 0
}

const getDownstreamCount = (tableId) => {
  if (!tableId) return 0
  return lineageCache[tableId]?.downstreamTables?.length || 0
}

const loadLineageForTable = async (tableId) => {
  if (!tableId || lineageCache[tableId]) return
  try {
    const lineageData = await tableApi.getLineage(tableId)
    lineageCache[tableId] = lineageData || { upstreamTables: [], downstreamTables: [] }
  } catch (error) {
    console.error('加载数据血缘失败', error)
  }
}

const observeExistingTableRefs = () => {
  if (!tableObserver.value) return
  Object.values(tableRefs.value).forEach((el) => {
    const tableId = el?.dataset?.tableId
    if (tableId) {
      tableObserver.value.observe(el)
    }
  })
}

const setupTableObserver = () => {
  if (tableObserver.value) {
    tableObserver.value.disconnect()
  }
  tableObserver.value = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return
        const tableId = Number(entry.target.dataset.tableId)
        if (Number.isFinite(tableId)) {
          loadLineageForTable(tableId)
        }
        tableObserver.value?.unobserve(entry.target)
      })
    },
    {
      root: null,
      rootMargin: '100px',
      threshold: 0.1
    }
  )
  observeExistingTableRefs()
}

// Tab 工作区生命周期（P2-2 F14，完成 F8 残留）：Tab 状态创建、打开/加载/关闭
// 从主组件抽离；共享状态所有权仍在组件，openTableTab 前向引用由此闭环
const {
  createTabState,
  openTableTab,
  loadTabData,
  hydrateRestoredTableTabs,
  handleTabRemove,
  handleCloseLeft,
  handleCloseRight,
  handleCloseAll,
  handleTabAdd,
  getTabItemById,
} = useStudioTabs({
  clusterId,
  openTabs,
  activeTab,
  tabStates,
  lineageCache,
  selectedTableKey,
  findCachedTableById,
  getDatasourceById,
  getTableKey,
  focusTableInSidebar,
  loadSchemas,
  getSchemaOptions,
  buildDefaultSql,
  syncAutoSelectSqlIfSchemaMismatch,
  clearQueryTimer,
  stopNowTickerIfIdle,
  disposeChart,
  leftPaneRefs,
  leftPaneHeights,
  loadMetaDataDomainOptions,
})

// 表操作与详情加载（P2-2 F15）：建表/删表/元数据同步、DDL/访问统计、任务与血缘跳转
const {
  saveAsTask,
  handleCreateTable,
  handleDeleteTable,
  syncMissingTableMetadata,
  handleCreateSuccess,
  loadDdl,
  loadAccessStats,
  copyDdl,
  openTask,
  goCreateRelatedTask,
  handleTaskSuccess,
  goLineage,
} = useTableActions({
  clusterId,
  openTabs,
  activeTab,
  tabStates,
  createDrawerVisible,
  taskDrawerRef,
  router,
  isDorisTable,
  isPlatformMetadataMissing,
  warnPlatformMetadataMissing,
  tableStore,
  loadClusters,
  loadTables,
  openTableTab,
  loadTabData,
  handleTabRemove,
  getTabItemById,
})

// 智能元数据（复用智能问数任务链路）：生成字段/表描述建议，复核后采纳写回
const {
  metadataGenerating,
  metadataAdopting,
  metadataDialogVisible,
  metadataResult,
  generateMetadata,
  adoptMetadata,
} = useMetadataGeneration({
  clusterId,
  tabStates,
  taskApi,
  loadDdl,
  layerOptions,
  warnPlatformMetadataMissing,
})

watch(
  () => [activeTab.value, tabStates[activeTab.value]?.metaTab],
  ([tabId, metaTab]) => {
    if (!tabId) return
    const state = tabStates[tabId]
    if (!state) return
    if (metaTab === 'ddl') {
      if (state.ddlLoading || state.ddl) return
      loadDdl(tabId)
      return
    }
    if (metaTab === 'access') {
      if (state.accessLoading || state.accessStats) return
      loadAccessStats(tabId)
    }
  }
)

const loadActiveQueryContext = async () => {
  const query = tabStates[activeTab.value]?.query
  const sourceId = query?.sourceId
  if (!sourceId || !getDatasourceById(sourceId)) return
  if (!schemaStore[sourceId] && !await loadSchemas(sourceId)) return
  if (query.dbName) await loadTables(sourceId, query.dbName)
}

watch(
  () => activeTab.value,
  (value) => {
    if (!value) {
      if (!openTabs.value.length) {
        clearRouteTabQuery()
      }
      return
    }
    const tab = openTabs.value.find((item) => String(item.id) === String(value))
    if (!tab) return
    if (tab.sourceId) {
      clusterId.value = tab.sourceId
    }
    if (tab.kind === 'table') {
      selectedTableKey.value = String(tab.id)
      const tabId = String(tab.id)
      const state = tabStates[tabId]
      if (state && !state.dataLoaded && !state.dataLoading) {
        loadTabData(tabId)
      }
    } else {
      selectedTableKey.value = ''
    }
    syncRouteWithTab(tab, value)
    void loadActiveQueryContext()
  }
)

watch(
  () => [route.query.clusterId, route.query.database, route.query.tableId, route.query.tableName],
  async () => {
    if (suppressRouteSync.value) return
    await syncFromRoute()
  }
)

watch(
  () => route.query.create,
  (value) => {
    if (!value) return
    createDrawerVisible.value = true
    clearCreateQuery()
  }
)

  provide('dataStudioCtx', {
  getSourceName,
    clusterId,
    openTabs,
    activeTab,
    tabStates,
  layerOptions,
  businessDomainOptions,
  getMetaDataDomainOptions,
  handleMetaBusinessDomainChange,
  isDorisTable,
  isPlatformMetadataMissing,
  isAggregateTable,
  isReplicaWarning,
  getFieldRows,
  startMetaEdit,
  cancelMetaEdit,
  saveMetaEdit,
  handleDeleteTable,
  syncMissingTableMetadata,
  startFieldsEdit,
  cancelFieldsEdit,
  saveFieldsEdit,
  addField,
  removeField,
    copyDdl,
    loadAccessStats,
    formatDuration,
    formatDateTime,
    goLineage,
    goCreateRelatedTask,
    openTask,
    openTableTab,
    generateMetadata,
    adoptMetadata,
    metadataGenerating,
    metadataAdopting,
    metadataDialogVisible,
    metadataResult
  })

// 查询结果面板契约（P2-2 F16a）：DataStudioResultPanel 消费,键集合与该组件解构保持一致
provide('dataStudioQueryCtx', {
  tabStates,
  dataSources,
  getSourceName,
  getSchemaOptions,
  handleQuerySourceSelect,
  handleQueryDatabaseSelect,
  executeQuery,
  stopQuery,
  resetQuery,
  saveAsTask,
  getSqlCompletionContext,
  handleSqlSelectionChange,
  getLiveDurationMs,
  getStatementStatusTagType,
  getDisplayResultSets,
  isResultSetType,
  getResultSetCountText,
  getResultSetAlertType,
  exportResult,
  getResultRowKeyPrefix,
  applyHistory,
  historyData,
  historyPager,
  historyLoading,
  getNumericColumns,
  setChartRef,
  canRenderChart,
})

// 目录树节点契约（P2-2 F16c）：DataStudioCatalogNode 消费,键集合与该组件解构保持一致
provide('dataStudioCatalogCtx', {
  dbLoading,
  schemaLoading,
  schemaCountLoading,
  tableLoading,
  setTableRef,
  getProgressWidth,
  getDatasourceIconUrl,
  isDatasourceIconInactive,
  isCatalogNodeActive,
  isViewTable,
  getTableCount,
  getTableCountByType,
  getTableRowCount,
  getTableStorageSize,
  refreshDatasourceNode,
  refreshSchemaNode,
  isPlatformMetadataMissing,
  getUpstreamCount,
  getDownstreamCount,
})

// Tab 工作区持久化（P2-2 F4）：在 createTabState 之后装配，依赖共享响应式状态
const { flushPersistTabs, restoreTabsFromStorage } = useTabPersistence({
  openTabs,
  activeTab,
  tabStates,
  queryTabCounter,
  createTabState,
  storageKey: isDemoMode
    ? 'odw:datastudio:workspace-tabs:demo-v1'
    : 'odw:datastudio:workspace-tabs:v1',
})

onMounted(async () => {
  setupTableObserver()
  const restored = restoreTabsFromStorage()
  if (restored) {
    hydrateRestoredTableTabs()
  }
  loadBusinessDomains()
  await loadClusters()
  fetchHistory()
  await syncFromRoute()
  await focusActiveTableInSidebar()
  await loadActiveQueryContext()
  if (route.query.create) {
    createDrawerVisible.value = true
    clearCreateQuery()
  }
  await nextTick()
  normalizePaneRatios()
})

onBeforeUnmount(() => {
  flushPersistTabs()
  if (tableObserver.value) {
    tableObserver.value.disconnect()
  }
})
</script>

<style scoped>
.data-studio {
  --studio-line: var(--odw-border, #e2e8f0);
  --studio-chrome: #f6f8fa;
  --studio-text: #334155;
  --studio-muted: #64748b;
  --studio-accent: var(--odw-primary, #2c5282);
  --studio-font-detail: 10px;
  --studio-font-ui: 11px;
  --studio-font-reading: 12px;
  --el-font-size-base: var(--studio-font-ui);
  --el-font-size-small: var(--studio-font-ui);
  height: calc(100vh - 68px);
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--studio-line);
  color: var(--studio-text);
  overflow: hidden;
  box-sizing: border-box;
}
.pane-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: none;
  min-height: 34px;
  gap: 8px;
  padding: 0 10px;
  border-bottom: 1px solid var(--studio-line);
  background: var(--studio-chrome);
}
.pane-heading {
  font-size: var(--studio-font-ui);
  font-weight: 500;
}
.data-studio :deep(.el-button + .el-button) {
  margin-left: 0;
}
.studio-layout {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 0;
}
.studio-sidebar {
  --catalog-background: #fff;
  --catalog-text: var(--studio-text);
  --catalog-muted: var(--studio-muted);
  --catalog-icon: #7c8999;
  --catalog-faint: #8a96a6;
  --catalog-line: var(--studio-line);
  --catalog-hover: #f5f7fa;
  --catalog-selected: #e8f0fa;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--catalog-background);
  color: var(--catalog-text);
  min-height: 0;
  overflow: hidden;
}
.sidebar-controls {
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  border-bottom: 1px solid var(--catalog-line);
}
.catalog-search-row {
  display: flex;
  align-items: center;
  gap: 2px;
}
.catalog-search-row .search-input {
  flex: 1;
  min-width: 0;
}
.catalog-search-row :deep(.el-button) {
  flex: none;
  padding: 4px;
  width: 22px;
}
.search-input {
  width: 100%;
}
.sidebar-controls :deep(.el-input__wrapper),
.sidebar-controls :deep(.el-select__wrapper) {
  font-size: 10px;
  border-radius: 3px;
}
.db-tree {
  flex: 1;
  min-height: 0;
  padding: 6px 0;
  overflow: hidden;
}
.db-tree-scroll {
  height: 100%;
}
.catalog-tree {
  width: 100%;
  background: transparent;
  --el-tree-node-content-height: 24px;
  --el-tree-text-color: var(--catalog-text);
}
:deep(.catalog-tree .el-tree-node__content) {
  height: auto;
  min-height: 24px;
  padding-right: 8px;
  font-size: 10px;
  border-left: 2px solid transparent;
}
:deep(.catalog-tree .el-tree-node__expand-icon) {
  padding: 4px;
  font-size: 10px;
}
:deep(.catalog-tree .el-tree-node__children) {
  margin-left: 4px;
  border-left: 1px solid var(--catalog-line);
}
:deep(.catalog-tree .el-tree-node__content:hover),
:deep(.catalog-tree .el-tree-node:focus > .el-tree-node__content) {
  background: var(--catalog-hover);
}
:deep(.catalog-tree .el-tree-node.is-current > .el-tree-node__content) {
  --catalog-text: #365f91;
  --catalog-icon: #3f648e;
  background: var(--catalog-selected);
  border-left-color: #3f648e;
  color: var(--catalog-text);
}
.sidebar-resizer,
.workspace-resizer {
  position: relative;
  flex: 0 0 5px;
  cursor: col-resize;
  background: transparent;
  box-sizing: border-box;
}
.sidebar-resizer::after,
.workspace-resizer::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: calc(50% - 0.5px);
  width: 1px;
  background: var(--studio-line);
  pointer-events: none;
}
.sidebar-resizer:hover::after,
.workspace-resizer:hover::after,
.data-studio.is-resizing .sidebar-resizer::after,
.data-studio.is-resizing .workspace-resizer::after {
  left: calc(50% - 1px);
  width: 2px;
  background: #7da2cf;
}
.data-studio.is-resizing {
  user-select: none;
}
.studio-workspace {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  overflow: hidden;
}
.studio-right {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
}
.studio-right > :deep(.right-root) {
  flex: 1;
  min-height: 0;
  height: auto;
}
.workspace-body {
  position: relative;
}
.workspace-details-control {
  position: absolute;
  top: 4px;
  right: 5px;
  z-index: 2;
  display: flex;
  width: 26px;
  height: 26px;
}
.workspace-details-toggle {
  width: 26px;
  height: 26px;
  padding: 5px;
  margin: 0;
  color: #718096;
  border-radius: 3px;
}
.workspace-details-toggle[aria-expanded='true'] {
  color: #526f94;
}
.workspace-details-toggle:hover {
  color: #365f91;
  background: #e8eef6;
}
.workspace-details-toggle:focus-visible {
  outline: 2px solid #7da2cf;
  outline-offset: 1px;
}
.panel-toggle-icon {
  width: 16px;
  height: 16px;
  display: block;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.workspace-body,
.workspace-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
}
:deep(.workspace-tabs) {
  --el-tabs-header-height: 34px;
  box-sizing: border-box;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
:deep(.workspace-tabs > .el-tabs__header) {
  display: flex;
  justify-content: flex-start;
  margin: 0;
  background: var(--studio-chrome);
  border-bottom: 1px solid var(--studio-line);
  padding-right: 40px;
}
:deep(.workspace-tabs > .el-tabs__header > .el-tabs__nav-wrap) {
  flex: 0 1 auto;
  width: max-content;
  min-width: 0;
  max-width: calc(100% - 32px);
}
:deep(.workspace-tabs > .el-tabs__header .el-tabs__nav) {
  border: 0;
  border-radius: 0;
}
:deep(.workspace-tabs > .el-tabs__header .el-tabs__item) {
  padding: 0 12px;
  border-left: 0;
  border-right: 1px solid var(--studio-line);
  border-top: 2px solid transparent;
  margin-bottom: -1px;
}
:deep(.workspace-tabs > .el-tabs__header .el-tabs__item.is-active) {
  background: #fff;
  border-top-color: var(--studio-accent);
}
:deep(.workspace-tabs .el-tabs__new-tab) {
  position: static;
  flex: 0 0 24px;
  width: 24px;
  height: 24px;
  padding: 0;
  margin: 0 4px;
  border: 0;
  border-radius: 3px;
  display: flex;
  align-items: center;
  justify-content: center;
}
:deep(.workspace-tabs .el-tabs__new-tab:hover) {
  color: var(--studio-accent);
  background: #e8f0fa;
}
:deep(.workspace-tabs > .el-tabs__content) {
  flex: 1;
  min-height: 0;
  padding: 0;
}
:deep(.workspace-tabs .el-tab-pane) {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.tab-label {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.tab-icon {
  color: var(--studio-muted);
  font-size: 12px;
}
.tab-title {
  font-size: var(--studio-font-ui);
  font-weight: 500;
  color: var(--studio-text);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tab-grid {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.tab-left {
  flex: 1;
  display: grid;
  grid-template-rows: var(--left-top, 240px) 6px minmax(180px, 1fr);
  min-height: 0;
}
.left-resizer {
  position: relative;
  cursor: row-resize;
  background: transparent;
}
.left-resizer::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  top: calc(50% - 1px);
  height: 2px;
  background: #7da2cf;
  opacity: 0;
  pointer-events: none;
}
.left-resizer:hover::after {
  opacity: 1;
}
.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 24px;
  text-align: center;
}
.empty-workspace-icon {
  font-size: 32px;
  color: #94a3b8;
}
.empty-state h2 {
  font-size: 16px;
  font-weight: 500;
  margin: 4px 0 0;
}
.empty-state p {
  font-size: 12px;
  color: var(--studio-muted);
  margin: 0 0 4px;
}
.empty-workspace-hint {
  font-size: 11px;
  color: var(--studio-muted);
  margin-top: 12px;
}
@media (max-width: 900px) {
  .data-studio {
    height: auto;
    min-height: calc(100vh - 68px);
    overflow: visible;
  }
  .studio-layout {
    flex-direction: column;
    flex: none;
  }
  .studio-sidebar {
    width: 100% !important;
    min-width: 0 !important;
    max-width: none !important;
    height: 240px;
  }
  .studio-workspace {
    flex: none;
    height: 650px;
  }
  .studio-right {
    width: 100% !important;
    min-width: 0 !important;
    max-width: none !important;
    height: 700px;
    border-top: 1px solid var(--studio-line);
  }
  .sidebar-resizer,
  .workspace-resizer {
    display: none;
  }
}
.catalog-view-option {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 160px;
  padding: 3px 0;
}
.catalog-view-check {
  color: var(--odw-primary, #2c5282);
  margin-top: 3px;
  width: 12px;
  flex: none;
}
.catalog-view-option span {
  font-size: 12px;
  line-height: 18px;
  color: #334155;
}
.catalog-view-option small {
  display: block;
  font-size: 11px;
  line-height: 18px;
  color: #64748b;
}
.catalog-sort-option {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 130px;
  font-size: 12px;
}
.catalog-sort-option .catalog-view-check {
  margin-top: 0;
}
.catalog-menu-caption {
  font-size: 10px;
  color: #64748b;
  letter-spacing: 0.3px;
}
:deep(.workspace-tabs > .el-tabs__header .el-tabs__active-bar) {
  display: none;
}
/* 标签底边直接接到白色工作区，避免 Element Plus 默认高度留出灰色缝隙。 */
:deep(.workspace-tabs > .el-tabs__header) {
  height: 34px;
  min-height: 34px;
  flex: 0 0 34px;
  box-sizing: border-box;
  align-items: flex-start;
  overflow: visible;
}
:deep(.workspace-tabs > .el-tabs__header .el-tabs__nav-wrap) {
  height: 34px;
  margin-bottom: 0;
}
:deep(.workspace-tabs > .el-tabs__header .el-tabs__nav) {
  height: 34px;
}
:deep(.workspace-tabs > .el-tabs__header .el-tabs__item) {
  margin: 0;
  height: 34px;
  border-bottom: 1px solid #dce2e9;
}
:deep(.workspace-tabs > .el-tabs__header .el-tabs__item.is-active) {
  border-bottom-color: #fff;
  position: relative;
  z-index: 1;
}
:deep(.workspace-tabs > .el-tabs__header .el-tabs__new-tab) {
  margin-top: 5px;
}

:deep(.catalog-tree .el-tree-node__expand-icon:not(.is-leaf)) {
  color: var(--catalog-faint);
}
.catalog-search-row :deep(.el-button) {
  color: var(--catalog-muted);
}
.catalog-search-row :deep(.el-button:hover) {
  background: #edf3fb;
  color: #3f648e;
}
.sidebar-controls :deep(.el-input__wrapper) {
  background: #fff;
  box-shadow: 0 0 0 1px var(--studio-line) inset;
}
.sidebar-controls :deep(.el-input__inner) {
  color: var(--catalog-text);
}
.sidebar-controls :deep(.el-input__inner::placeholder) {
  color: #8a96a6;
}
</style>
