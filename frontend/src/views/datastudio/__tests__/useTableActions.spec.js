import { definition } from '@/utils/__tests__/datasourceFixtures'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive, ref } from 'vue'

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn(() => Promise.resolve()), prompt: vi.fn(() => Promise.resolve({ value: '' })) },
}))
vi.mock('@/api/table', () => ({
  tableApi: {
    getById: vi.fn(),
    getTableDdl: vi.fn(),
    getTableDdlByName: vi.fn(),
    getAccessStats: vi.fn(),
    softDelete: vi.fn(),
    delete: vi.fn(),
    syncTableMetadataByName: vi.fn(),
  },
}))
vi.mock('@/demo/runtime', () => ({ isDemoMode: false, showDemoReadonlyMessage: vi.fn() }))
vi.mock('@/utils/clipboard', () => ({ copyText: vi.fn(() => Promise.resolve()) }))

import { ElMessage, ElMessageBox } from 'element-plus'
import { tableApi } from '@/api/table'
import { copyText } from '@/utils/clipboard'
import { useTableActions } from '../composables/useTableActions'

function setup(overrides = {}) {
  const tabStates = reactive({})
  const openTabs = ref([])
  const activeTab = ref('')
  const router = { push: vi.fn() }
  const taskDrawerRef = ref({ open: vi.fn() })
  const deps = {
    clusterId: ref('c1'),
    openTabs,
    activeTab,
    tabStates,
    createDrawerVisible: ref(false),
    taskDrawerRef,
    router,
    hasEngineProperties: vi.fn(() => true),
    isPlatformMetadataMissing: vi.fn(() => false),
    warnPlatformMetadataMissing: vi.fn(() => false),
    tableStore: reactive({}),
    loadClusters: vi.fn(() => Promise.resolve()),
    loadTables: vi.fn(() => Promise.resolve()),
    openTableTab: vi.fn(() => Promise.resolve()),
    loadTabData: vi.fn(() => Promise.resolve()),
    handleTabRemove: vi.fn(),
    getTabItemById: vi.fn(() => null),
    ...overrides,
  }
  return { deps, api: useTableActions(deps), tabStates, openTabs, activeTab, router, taskDrawerRef }
}

describe('useTableActions', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loadDdl loads by id when present, by name otherwise, and tracks loading', async () => {
    const { api, tabStates } = setup()
    tabStates.t1 = { table: { id: 5, sourceId: 's1', typeDefinition: definition('MYSQL'), dbName: 'db', tableName: 't' }, ddl: '', ddlLoading: false }
    tableApi.getTableDdl.mockResolvedValue('CREATE TABLE t')
    await api.loadDdl('t1')
    expect(tableApi.getTableDdl).toHaveBeenCalledWith(5, 's1')
    expect(tabStates.t1.ddl).toBe('CREATE TABLE t')
    expect(tabStates.t1.ddlLoading).toBe(false)

    tabStates.t2 = { table: { sourceId: 's1', typeDefinition: definition('MYSQL'), dbName: 'db', tableName: 't2' }, ddl: '', ddlLoading: false }
    tableApi.getTableDdlByName.mockResolvedValue('CREATE TABLE t2')
    await api.loadDdl('t2')
    expect(tableApi.getTableDdlByName).toHaveBeenCalledWith('s1', 'db', 't2')
  })

  it('loadDdl surfaces API failure via ElMessage and resets loading', async () => {
    const { api, tabStates } = setup()
    tabStates.t1 = { table: { id: 5, sourceId: 's1', typeDefinition: definition('MYSQL'), dbName: 'db', tableName: 't' }, ddl: '', ddlLoading: false }
    tableApi.getTableDdl.mockRejectedValue(new Error('boom'))
    await api.loadDdl('t1')
    expect(ElMessage.error).toHaveBeenCalled()
    expect(tabStates.t1.ddlLoading).toBe(false)
  })

  it('loadAccessStats dedupes while loading and caches unless forced', async () => {
    const { api, tabStates } = setup()
    tabStates.t1 = { table: { id: 5, sourceId: 's1' }, accessLoading: false, accessStats: { visits: 1 }, accessError: '' }
    await api.loadAccessStats('t1')
    expect(tableApi.getAccessStats).not.toHaveBeenCalled()
    tableApi.getAccessStats.mockResolvedValue({ visits: 2 })
    await api.loadAccessStats('t1', true)
    expect(tableApi.getAccessStats).toHaveBeenCalledTimes(1)
    expect(tabStates.t1.accessStats).toEqual({ visits: 2 })
  })

  it('loadAccessStats records error message on failure', async () => {
    const { api, tabStates } = setup()
    tabStates.t1 = { table: { id: 5, sourceId: 's1' }, accessLoading: false, accessStats: null, accessError: '' }
    tableApi.getAccessStats.mockRejectedValue(new Error('nope'))
    await api.loadAccessStats('t1')
    expect(tabStates.t1.accessError).toBe('nope')
    expect(tabStates.t1.accessStats).toBeNull()
  })

  it('handleDeleteTable prompts with table-name confirmation then soft-deletes doris tables', async () => {
    const { deps, api, tabStates, activeTab } = setup()
    activeTab.value = 'x'
    tabStates.x = { table: { id: 7, tableName: 'demo_t', dbName: 'db', sourceId: 's1' } }
    ElMessageBox.prompt.mockResolvedValue({ value: 'demo_t' })
    await api.handleDeleteTable()
    expect(tableApi.softDelete).toHaveBeenCalledWith(7, 's1', 'demo_t')
    expect(deps.loadTables).toHaveBeenCalledWith('s1', 'db', true)
    expect(deps.handleTabRemove).toHaveBeenCalledWith('x')
    expect(ElMessage.success).toHaveBeenCalled()
  })

  it('handleDeleteTable cancel path shows no error', async () => {
    const { api, tabStates, activeTab } = setup()
    activeTab.value = 'x'
    tabStates.x = { table: { id: 7, tableName: 'demo_t' } }
    ElMessageBox.prompt.mockRejectedValue('cancel')
    await api.handleDeleteTable()
    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(tableApi.softDelete).not.toHaveBeenCalled()
  })

  it('goLineage pushes the lineage route with tableId', () => {
    const { api, tabStates, router } = setup()
    tabStates.t1 = { table: { id: 42 } }
    api.goLineage('t1')
    expect(router.push).toHaveBeenCalledWith({ path: '/lineage', query: { tableId: 42 } })
  })

  it('goLineage respects the metadata-missing guard', () => {
    const { api, tabStates, router } = setup({ warnPlatformMetadataMissing: vi.fn(() => true) })
    tabStates.t1 = { table: { id: 42 } }
    api.goLineage('t1')
    expect(router.push).not.toHaveBeenCalled()
  })

  it('saveAsTask requires SQL then opens the task drawer prefilled', () => {
    const { api, tabStates, taskDrawerRef } = setup()
    tabStates.q = { query: { sql: '  ' }, table: {} }
    api.saveAsTask('q')
    expect(ElMessage.warning).toHaveBeenCalled()
    tabStates.q = { query: { sql: 'SELECT 1' }, table: { sourceId: 's1', dbName: 'db' } }
    api.saveAsTask('q')
    expect(taskDrawerRef.value.open).toHaveBeenCalledWith(null, expect.objectContaining({ taskSql: 'SELECT 1' }))
  })

  it('copyDdl copies and reports success', async () => {
    const { api, tabStates } = setup()
    tabStates.t1 = { ddl: 'CREATE TABLE x' }
    await api.copyDdl('t1')
    expect(copyText).toHaveBeenCalledWith('CREATE TABLE x')
    expect(ElMessage.success).toHaveBeenCalled()
  })

  it('handleTaskSuccess reloads only active table tabs', async () => {
    const { deps, api, tabStates, activeTab } = setup({
      getTabItemById: vi.fn(() => ({ id: 't1', kind: 'table' })),
    })
    activeTab.value = 't1'
    tabStates.t1 = { dataLoaded: true }
    await api.handleTaskSuccess()
    expect(tabStates.t1.dataLoaded).toBe(false)
    expect(deps.loadTabData).toHaveBeenCalledWith('t1')
  })

  it('syncMissingTableMetadata syncs, refreshes tables, and reloads the tab', async () => {
    const { deps, api, tabStates, openTabs } = setup({
      isPlatformMetadataMissing: vi.fn(() => true),
    })
    openTabs.value.push({ id: 't1', kind: 'table' })
    tabStates.t1 = { table: { tableName: 'demo', dbName: 'db', sourceId: 's1' }, metadataSyncing: false, dataLoaded: true }
    tableApi.syncTableMetadataByName.mockResolvedValue({ tableId: 99 })
    await api.syncMissingTableMetadata('t1')
    expect(tableApi.syncTableMetadataByName).toHaveBeenCalledWith('db', 'demo', 's1')
    expect(deps.loadTables).toHaveBeenCalledWith('s1', 'db', true)
    expect(tabStates.t1.table.metadataStatus).toBe('synced')
    expect(deps.loadTabData).toHaveBeenCalledWith('t1')
    expect(tabStates.t1.metadataSyncing).toBe(false)
  })
})


it('saving a query task uses execution connection/schema rather than the inspected object', () => {
  const { api, tabStates, taskDrawerRef } = setup()
  tabStates.t1 = { table: { sourceId: 'object-source', dbName: 'object-db' }, query: { sql: 'SELECT 1', sourceId: 'query-source', dbName: 'query-db' } }
  api.saveAsTask('t1')
  expect(taskDrawerRef.value.open).toHaveBeenCalledWith(null, expect.objectContaining({
    taskSql: 'SELECT 1', taskDesc: 'From DataStudio\nCluster: query-source\nDatabase: query-db',
  }))
})

it('saving a query task passes the execution target for display without choosing a scheduler datasource', () => {
  const { api, tabStates, taskDrawerRef } = setup({ getSourceName: (id) => (id === 'query-source' ? '生产 Doris' : '') })
  tabStates.t1 = { table: { sourceId: 'object-source', dbName: 'object-db' }, query: { sql: 'SELECT 1', sourceId: 'query-source', dbName: 'query-db' } }
  api.saveAsTask('t1')
  const initialData = taskDrawerRef.value.open.mock.calls[0][1]
  expect(initialData.queryTarget).toEqual({ sourceId: 'query-source', sourceName: '生产 Doris', dbName: 'query-db' })
  expect(initialData.taskDesc).toBe('From DataStudio\nCluster: 生产 Doris (query-source)\nDatabase: query-db')
  // 门户连接与调度数据源无映射，不得预填
  expect(initialData).not.toHaveProperty('datasourceName')
})


it('删除对象在确认期间切换查询连接，仍使用对象的所属连接', async () => {
  let confirm
  ElMessageBox.prompt.mockReturnValueOnce(new Promise(resolve => { confirm = resolve }))
  const { deps, api, activeTab, tabStates } = setup()
  activeTab.value = 't1'
  tabStates.t1 = { table: { id: 9, clusterId: 'object-source', tableName: 't' } }
  const deleting = api.handleDeleteTable()
  deps.clusterId.value = 'another-query-source'
  confirm({ value: 't' })
  await deleting
  expect(tableApi.softDelete).toHaveBeenCalledWith(9, 'object-source', 't')
})
