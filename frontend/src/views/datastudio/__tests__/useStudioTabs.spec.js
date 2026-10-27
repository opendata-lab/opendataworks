import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive, ref } from 'vue'

vi.mock('@/api/doris', () => ({ dorisClusterApi: { defaultQuery: vi.fn().mockResolvedValue('SELECT generated_by_backend') } }))

vi.mock('@/api/table', () => ({
  tableApi: {
    getById: vi.fn(),
    getFields: vi.fn(),
    getLineage: vi.fn(),
    getTasks: vi.fn(),
    searchOptions: vi.fn(),
  },
}))
vi.mock('@/api/lineage', () => ({
  lineageApi: { getLineageGraph: vi.fn(() => Promise.resolve(null)) },
}))

import { tableApi } from '@/api/table'
import { useStudioTabs } from '../composables/useStudioTabs'

function setup(overrides = {}) {
  const openTabs = ref([])
  const activeTab = ref('')
  const tabStates = reactive({})
  const deps = {
    clusterId: ref(null),
    openTabs,
    activeTab,
    tabStates,
    lineageCache: reactive({}),
    selectedTableKey: ref(''),
    findCachedTableById: vi.fn(() => null),
    getDatasourceById: vi.fn(() => null),
    getTableKey: vi.fn((payload, db, sourceId) => `${sourceId}::${db}::${payload.tableName}`),
    focusTableInSidebar: vi.fn(() => Promise.resolve()),
    loadSchemas: vi.fn(() => Promise.resolve()),
    getSchemaOptions: vi.fn(() => []),
    buildDefaultSql: vi.fn((table) => (table?.tableName ? `SELECT * FROM ${table.tableName}` : '')),
    syncAutoSelectSqlIfSchemaMismatch: vi.fn(),
    clearQueryTimer: vi.fn(),
    stopNowTickerIfIdle: vi.fn(),
    disposeChart: vi.fn(),
    leftPaneRefs: ref({}),
    leftPaneHeights: reactive({}),
    loadMetaDataDomainOptions: vi.fn(() => Promise.resolve()),
    ...overrides,
  }
  return { deps, api: useStudioTabs(deps), openTabs, activeTab, tabStates }
}

const pushTab = (openTabs, tabStates, api, id, kind = 'query', extra = {}) => {
  openTabs.value.push({ id, kind, tableName: id, ...extra })
  tabStates[id] = api.createTabState({ tableName: id })
}

describe('useStudioTabs', () => {
  beforeEach(() => vi.clearAllMocks())

  it('createTabState builds the full per-tab state shape with default SQL', () => {
    const { api } = setup()
    const state = api.createTabState({ tableName: 't_user', tableComment: 'c' })
    expect(state.query.sql).toBe('SELECT * FROM t_user')
    expect(state.query.limit).toBe(200)
    expect(state.resultTab).toBe('result-0')
    expect(state.charts).toEqual([{ type: 'bar', xAxis: '', yAxis: [] }])
    expect(state.metaTab).toBe('basic')
    expect(state.metaForm.tableComment).toBe('c')
    expect(state.dataLoaded).toBe(false)
  })

  it('handleTabAdd inserts a query tab after the active tab and activates it', async () => {
    const { api, openTabs, activeTab, tabStates } = setup()
    pushTab(openTabs, tabStates, api, 'a')
    pushTab(openTabs, tabStates, api, 'b')
    activeTab.value = 'a'
    await api.handleTabAdd()
    expect(openTabs.value).toHaveLength(3)
    expect(openTabs.value[1].kind).toBe('query')
    expect(activeTab.value).toBe(openTabs.value[1].id)
    expect(tabStates[openTabs.value[1].id].query.sql).toBe('')
  })

  it('handleTabRemove disposes resources and activates the previous tab', () => {
    const { deps, api, openTabs, activeTab, tabStates } = setup()
    pushTab(openTabs, tabStates, api, 'a')
    pushTab(openTabs, tabStates, api, 'b')
    pushTab(openTabs, tabStates, api, 'c')
    activeTab.value = 'b'
    api.handleTabRemove('b')
    expect(openTabs.value.map((t) => t.id)).toEqual(['a', 'c'])
    expect(tabStates.b).toBeUndefined()
    expect(deps.clearQueryTimer).toHaveBeenCalledWith('b')
    expect(deps.disposeChart).toHaveBeenCalledWith('b')
    expect(activeTab.value).toBe('a')
  })

  it('handleTabRemove of the last tab clears activeTab', () => {
    const { api, openTabs, activeTab, tabStates } = setup()
    pushTab(openTabs, tabStates, api, 'only')
    activeTab.value = 'only'
    api.handleTabRemove('only')
    expect(openTabs.value).toHaveLength(0)
    expect(activeTab.value).toBe('')
  })

  it('handleCloseLeft/Right keep the anchor tab active when the active one is closed', () => {
    const { api, openTabs, activeTab, tabStates } = setup()
    ;['a', 'b', 'c', 'd'].forEach((id) => pushTab(openTabs, tabStates, api, id))
    activeTab.value = 'a'
    api.handleCloseLeft('c')
    expect(openTabs.value.map((t) => t.id)).toEqual(['c', 'd'])
    expect(activeTab.value).toBe('c')
    expect(tabStates.a).toBeUndefined()

    activeTab.value = 'd'
    api.handleCloseRight('c')
    expect(openTabs.value.map((t) => t.id)).toEqual(['c'])
    expect(activeTab.value).toBe('c')
  })

  it('handleCloseAll empties tabs, states, and activeTab', () => {
    const { api, openTabs, activeTab, tabStates } = setup()
    ;['a', 'b'].forEach((id) => pushTab(openTabs, tabStates, api, id))
    activeTab.value = 'b'
    api.handleCloseAll()
    expect(openTabs.value).toHaveLength(0)
    expect(activeTab.value).toBe('')
    expect(Object.keys(tabStates)).toHaveLength(0)
  })

  it('openTableTab activates an existing tab with the same key instead of duplicating', async () => {
    const { deps, api, openTabs, activeTab, tabStates } = setup()
    const table = { tableName: 't1', dbName: 'db', sourceId: 's1' }
    openTabs.value.push({ id: 's1::db::t1', kind: 'table', tableName: 't1', dbName: 'db', sourceId: 's1' })
    tabStates['s1::db::t1'] = api.createTabState(table)
    await api.openTableTab(table)
    expect(openTabs.value).toHaveLength(1)
    expect(activeTab.value).toBe('s1::db::t1')
    expect(deps.syncAutoSelectSqlIfSchemaMismatch).toHaveBeenCalled()
    expect(deps.focusTableInSidebar).toHaveBeenCalled()
  })

  it('openTableTab creates a new table tab and loads its data', async () => {
    const { api, openTabs, activeTab, tabStates } = setup()
    tableApi.getById.mockResolvedValue({ id: 9, tableName: 't2', dbName: 'db' })
    tableApi.getFields.mockResolvedValue([])
    tableApi.getLineage.mockResolvedValue({ upstreamTables: [], downstreamTables: [] })
    tableApi.getTasks.mockResolvedValue({ writeTasks: [], readTasks: [] })
    await api.openTableTab({ id: 9, tableName: 't2', dbName: 'db', sourceId: 's1' })
    expect(openTabs.value).toHaveLength(1)
    expect(openTabs.value[0].kind).toBe('table')
    const key = openTabs.value[0].id
    expect(activeTab.value).toBe(key)
    expect(tabStates[key].dataLoaded).toBe(true)
  })

  it('loadTabData is a no-op while loading or already loaded', async () => {
    const { api, openTabs, tabStates } = setup()
    pushTab(openTabs, tabStates, api, 'x', 'table')
    tabStates.x.table = { id: 1, tableName: 'x' }
    tabStates.x.dataLoaded = true
    await api.loadTabData('x')
    expect(tableApi.getById).not.toHaveBeenCalled()
  })

  it('loadTabData marks not-loaded on API failure', async () => {
    const { api, openTabs, tabStates } = setup()
    pushTab(openTabs, tabStates, api, 'x', 'table')
    tabStates.x.table = { id: 1, tableName: 'x', dbName: 'db' }
    tableApi.getById.mockRejectedValue(new Error('boom'))
    tableApi.getFields.mockRejectedValue(new Error('boom'))
    tableApi.getLineage.mockRejectedValue(new Error('boom'))
    tableApi.getTasks.mockRejectedValue(new Error('boom'))
    await api.loadTabData('x')
    expect(tabStates.x.dataLoaded).toBe(false)
    expect(tabStates.x.dataLoading).toBe(false)
  })

  it('resolveQuerySourceId/Database read from the active tab', () => {
    const { api, openTabs, activeTab, tabStates } = setup()
    pushTab(openTabs, tabStates, api, 'q1', 'query', { sourceId: 's9', dbName: 'db9' })
    activeTab.value = 'q1'
    expect(api.resolveQuerySourceId()).toBe('s9')
    expect(api.resolveQueryDatabase('s9')).toBe('db9')
    expect(api.resolveQueryDatabase('')).toBe('')
  })

  it('getTabInsertIndex appends when no active tab matches', () => {
    const { api, openTabs, activeTab, tabStates } = setup()
    pushTab(openTabs, tabStates, api, 'a')
    activeTab.value = 'missing'
    expect(api.getTabInsertIndex()).toBe(1)
    activeTab.value = 'a'
    expect(api.getTabInsertIndex()).toBe(1)
  })

  it('hydrateRestoredTableTabs loads data for restored table tabs only', async () => {
    const { api, openTabs, tabStates } = setup()
    pushTab(openTabs, tabStates, api, 'q', 'query')
    pushTab(openTabs, tabStates, api, 't', 'table')
    tabStates.t.table = { tableName: 't' }
    tableApi.searchOptions.mockResolvedValue([])
    api.hydrateRestoredTableTabs()
    await Promise.resolve()
    expect(tabStates.t.dataLoading || tabStates.t.dataLoaded).toBe(true)
  })
})


it('new queries inherit the execution target independently from the inspected object', async () => {
  const { api, openTabs, activeTab, tabStates } = setup({ getSchemaOptions: () => ['reports'] })
  pushTab(openTabs, tabStates, api, 'table1', 'table', { sourceId: '1', dbName: 'object_db' })
  Object.assign(tabStates.table1.query, { sourceId: '2', dbName: 'reports' })
  activeTab.value = 'table1'
  await api.handleTabAdd()
  expect(openTabs.value[1]).toMatchObject({ kind: 'query', sourceId: '2', dbName: 'reports' })
  expect(openTabs.value[0]).toMatchObject({ kind: 'table', sourceId: '1', dbName: 'object_db' })
})
