import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createApp, reactive, ref } from 'vue'

vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn(() => Promise.resolve()) } }))
vi.mock('@/api/query', () => ({ dataQueryApi: { analyze: vi.fn(), execute: vi.fn(), history: vi.fn(), stop: vi.fn() } }))

import { dataQueryApi } from '@/api/query'

import { buildDefaultSql, useQueryExecution } from '../composables/useQueryExecution'

// 在真实组件实例内运行 composable，使 onBeforeUnmount/watch 合法
function withSetup(composable) {
  let result
  const app = createApp({
    setup() {
      result = composable()
      return () => null
    },
  })
  app.mount(document.createElement('div'))
  return { result, unmount: () => app.unmount() }
}

function setup(overrides = {}) {
  const tabStates = reactive({
    t1: {
      table: { tableName: 'orders', dbName: 'db', sourceId: '1', defaultQuery: 'SELECT generated_by_backend' },
      query: { sql: '', selectionText: 'x', hasSelection: true, limit: 200 },
      queryTiming: { startedAt: 0, elapsedMs: 0 },
      page: { current: 1 },
      queryResult: { resultSets: [{ columns: ['a'], rows: [{ a: 1 }] }] },
    },
  })
  const { result, unmount } = withSetup(() =>
    useQueryExecution({
      clusterId: ref(null),
      activeSource: ref(''),
      activeSchema: reactive({}),
      schemaStore: reactive({}),
      tabStates,
      openTabs: ref([{ id: 't1', kind: 'table', sourceId: '1', dbName: 'db' }]),
      activeTab: ref('t1'),
      loadSchemas: vi.fn(),
      loadTables: vi.fn(),
      syncRouteWithTab: vi.fn(),
      disposeChart: vi.fn(),
      applyDefaultChartSelection: vi.fn(),
      syncResultPaneLayout: vi.fn(),
      ...overrides,
    })
  )
  return { tabStates, api: result, unmount }
}

beforeEach(() => {
    vi.clearAllMocks()
    dataQueryApi.analyze.mockResolvedValue({ riskItems: [], confirmChallenges: [] })
    dataQueryApi.execute.mockResolvedValue({ columns: ['value'], rows: [{ value: 1 }] })
    dataQueryApi.history.mockResolvedValue({ records: [], total: 0 })
    dataQueryApi.stop.mockResolvedValue({})
  })
  it('parseResultTabIndex parses result-N or returns null', () => {
    const { api, unmount } = setup()
    expect(api.parseResultTabIndex('result-2')).toBe(2)
    expect(api.parseResultTabIndex('result-0')).toBe(0)
    expect(api.parseResultTabIndex('info')).toBeNull()
    expect(api.parseResultTabIndex(null)).toBeNull()
    unmount()
  })

describe('useQueryExecution (pure helpers + state)', () => {

  it('uses backend-generated SQL for any adapter and never guesses a dialect', () => {
    const sql = 'SELECT TOP 200 * FROM [sales].[orders];'
    expect(buildDefaultSql({ sourceType: 'TEST_DB', defaultQuery: sql })).toBe(sql)
    expect(buildDefaultSql({ sourceType: 'POSTGRESQL', dbName: 'public', tableName: 'orders' })).toBe('')
  })

  it('isResultSetType treats default/RESULT_SET as a result set, UPDATE_COUNT as not', () => {
    const { api, unmount } = setup()
    expect(api.isResultSetType({})).toBe(true)
    expect(api.isResultSetType({ resultType: 'RESULT_SET' })).toBe(true)
    expect(api.isResultSetType({ resultType: 'UPDATE_COUNT' })).toBe(false)
    unmount()
  })

  it('getResultSetCountText reports row counts / affected rows', () => {
    const { api, unmount } = setup()
    expect(api.getResultSetCountText({ rows: [1, 2, 3] })).toBe('3 行')
    expect(api.getResultSetCountText({ resultType: 'UPDATE_COUNT', affectedRows: 5 })).toBe('影响 5 行')
    expect(api.getResultSetCountText({ resultType: 'UPDATE_COUNT' })).toBe('影响行数未知')
    unmount()
  })

  it('getResultSetByIndex returns normalized columns/rows or empty defaults', () => {
    const { api, unmount } = setup()
    expect(api.getResultSetByIndex('t1', 0)).toMatchObject({ columns: ['a'], rows: [{ a: 1 }] })
    expect(api.getResultSetByIndex('t1', 9)).toMatchObject({ columns: [], rows: [] })
    expect(api.getResultSetByIndex('missing', 0)).toMatchObject({ columns: [], rows: [] })
    unmount()
  })

  it('resetQuery rebuilds default sql and clears selection', () => {
    const { tabStates, api, unmount } = setup()
    api.resetQuery('t1')
    expect(typeof tabStates.t1.query.sql).toBe('string')
    expect(tabStates.t1.query.sql.length).toBeGreaterThan(0)
    expect(tabStates.t1.query.selectionText).toBe('')
    expect(tabStates.t1.query.hasSelection).toBe(false)
    unmount()
  })
})


describe('query execution context', () => {
  it('switches connection/schema without changing the inspected object or SQL', async () => {
    const loadTables = vi.fn()
    const syncRouteWithTab = vi.fn()
    const { api, tabStates, unmount } = setup({
      loadSchemas: vi.fn().mockResolvedValue(true),
      schemaStore: { '2': ['analytics'] },
      loadTables, syncRouteWithTab,
    })
    const original = { ...tabStates.t1.table }
    tabStates.t1.query.sql = 'select 42'
    await api.handleQuerySourceSelect('t1', '2')
    expect(tabStates.t1.query).toMatchObject({ sourceId: '2', dbName: 'analytics', sql: 'select 42' })
    await api.handleQueryDatabaseSelect('t1', 'reports')
    expect(loadTables).toHaveBeenLastCalledWith('2', 'reports')
    expect(tabStates.t1.table).toEqual(original)
    expect(syncRouteWithTab).not.toHaveBeenCalled()
    unmount()
  })

  it('ignores a late schema response from a previous connection selection', async () => {
    let resolveOld
    const oldRequest = new Promise(resolve => { resolveOld = resolve })
    const { api, tabStates, unmount } = setup({
      loadSchemas: vi.fn(source => source === '2' ? oldRequest : Promise.resolve(true)),
      schemaStore: { '2': ['old'], '3': ['current'] },
    })
    const first = api.handleQuerySourceSelect('t1', '2')
    await api.handleQuerySourceSelect('t1', '3')
    resolveOld(true)
    await first
    expect(tabStates.t1.query).toMatchObject({ sourceId: '3', dbName: 'current' })
    unmount()
  })

  it('uses the chosen target for analyze and execution, explaining only the selection', async () => {
    const { api, tabStates, unmount } = setup()
    Object.assign(tabStates.t1.query, { sourceId: '2', dbName: 'reports', sql: 'SELECT 1; SELECT 2;', selectionText: 'SELECT 2;' })
    await api.executeQuery('t1', { explain: true })
    const payload = { clusterId: '2', database: 'reports', sql: 'EXPLAIN SELECT 2' }
    expect(dataQueryApi.analyze).toHaveBeenCalledWith(expect.objectContaining(payload))
    expect(dataQueryApi.execute).toHaveBeenCalledWith(expect.objectContaining(payload), expect.any(Object))
    expect(tabStates.t1.query.sql).toBe('SELECT 1; SELECT 2;')
    expect(tabStates.t1.queryResult.rows).toHaveLength(1)
    expect(tabStates.t1.resultTab).toBe('result-0')
    expect(tabStates.t1.table).toMatchObject({ sourceId: '1', dbName: 'db' })
    unmount()
  })

  it('does not fall back to the object database when execution schema was cleared', async () => {
    const { api, tabStates, unmount } = setup()
    Object.assign(tabStates.t1.query, { sourceId: '2', dbName: '', sql: 'SELECT 1', selectionText: '' })
    const calls = dataQueryApi.analyze.mock.calls.length
    await api.executeQuery('t1')
    expect(dataQueryApi.analyze.mock.calls).toHaveLength(calls)
    expect(tabStates.t1.queryResult.errorMessage).toBe('请先选择数据库')
    unmount()
  })

  it('applying history changes query target and clears stale SQL selection, preserving object identity', () => {
    const { api, tabStates, unmount } = setup()
    api.applyHistory({ clusterId: 2, databaseName: 'reports', sqlText: 'SELECT 5' }, 't1')
    expect(tabStates.t1.table).toMatchObject({ sourceId: '1', dbName: 'db', tableName: 'orders' })
    expect(tabStates.t1.query).toMatchObject({ sourceId: '2', dbName: 'reports', sql: 'SELECT 5', selectionText: '', hasSelection: false })
    unmount()
  })
})


it('stops an in-flight execution through the existing cancellation API', async () => {
  dataQueryApi.execute.mockImplementationOnce((_payload, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(Object.assign(new Error('canceled'), { code: 'ERR_CANCELED' })))
  }))
  const { api, tabStates, unmount } = setup()
  Object.assign(tabStates.t1.query, { sql: 'SELECT 1', selectionText: '' })
  tabStates.t1.queryResult.durationMs = 55
  tabStates.t1.queryResult.executedAt = 'old-execution'
  const execution = api.executeQuery('t1')
  await Promise.resolve()
  expect(tabStates.t1.queryCancelable).toBe(true)
  expect(tabStates.t1.queryResult.executedAt).toBe('')
  expect(tabStates.t1.queryResult.durationMs).toBe(0)
  tabStates.t1.queryTiming.startedAt = Date.now() - 3200
  await api.stopQuery('t1')
  await execution
  expect(dataQueryApi.stop).toHaveBeenCalledWith({ clientQueryId: 't1' })
  expect(tabStates.t1.queryCancelable).toBe(false)
  expect(tabStates.t1.queryResult.cancelled).toBe(true)
  expect(tabStates.t1.queryResult.durationMs).toBeGreaterThanOrEqual(3200)
  expect(tabStates.t1.queryResult.executedAt).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)
  expect(tabStates.t1.queryResult.message).toBe('查询已停止')
  unmount()
})


it('迟到的停止响应不会覆盖下一次执行的状态', async () => {
  let finishStop
  dataQueryApi.stop.mockReturnValueOnce(new Promise(resolve => { finishStop = resolve }))
  const { api, tabStates, unmount } = setup()
  Object.assign(tabStates.t1, { queryCancelable: true, queryRunId: 1 })
  const stop = api.stopQuery('t1')
  tabStates.t1.queryRunId = 2
  tabStates.t1.queryStopping = false
  tabStates.t1.queryLoading = true
  finishStop(true)
  await stop
  expect(tabStates.t1.queryResult.cancelled).not.toBe(true)
  expect(tabStates.t1.queryLoading).toBe(true)
  unmount()
})
