import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref, reactive, nextTick } from 'vue'
import { useTabPersistence } from '../composables/useTabPersistence'

const KEY = 'odw:test:tabs'

function setup(initialTabs = []) {
  const openTabs = ref(initialTabs)
  const activeTab = ref('')
  const tabStates = reactive({})
  const queryTabCounter = ref(1)
  const createTabState = (table) => ({ table: { ...table }, query: { sql: '', limit: 200 } })
  const api = useTabPersistence({
    openTabs,
    activeTab,
    tabStates,
    queryTabCounter,
    createTabState,
    storageKey: KEY,
  })
  return { openTabs, activeTab, tabStates, queryTabCounter, api }
}

describe('useTabPersistence', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.useRealTimers()
  })

  it('flush persists a non-empty snapshot to localStorage', () => {
    const { activeTab, tabStates, api } = setup([{ id: 't1', kind: 'table', tableName: 'orders', dbName: 'db', sourceId: '1' }])
    tabStates['t1'] = { table: { dbName: 'db', sourceId: '1', id: 9 }, query: { sql: 'select 1', limit: 50 } }
    activeTab.value = 't1'
    api.flushPersistTabs()
    const raw = JSON.parse(localStorage.getItem(KEY))
    expect(raw.version).toBe(1)
    expect(raw.activeTab).toBe('t1')
    expect(raw.tabs[0]).toMatchObject({ id: 't1', kind: 'table', sql: 'select 1', limit: 50 })
  })

  it('flush removes the key when there are no tabs', () => {
    localStorage.setItem(KEY, 'stale')
    const { api } = setup([])
    api.flushPersistTabs()
    expect(localStorage.getItem(KEY)).toBeNull()
  })

  it('restore rebuilds tabStates/openTabs/activeTab and query counter', () => {
    localStorage.setItem(
      KEY,
      JSON.stringify({
        version: 1,
        activeTab: 'q3',
        tabs: [
          { id: 'q3', kind: 'query', tableName: 'query3', dbName: 'db', sourceId: '1', sql: 'select 2', limit: 10 },
        ],
      })
    )
    const { openTabs, activeTab, tabStates, queryTabCounter, api } = setup([])
    expect(api.restoreTabsFromStorage()).toBe(true)
    expect(openTabs.value).toHaveLength(1)
    expect(activeTab.value).toBe('q3')
    expect(tabStates['q3'].query.sql).toBe('select 2')
    expect(tabStates['q3'].query.limit).toBe(10)
    expect(queryTabCounter.value).toBe(4) // max index 3 + 1
  })

  it('restore returns false on missing or version-mismatched payload', () => {
    const { api } = setup([])
    expect(api.restoreTabsFromStorage()).toBe(false)
    localStorage.setItem(KEY, JSON.stringify({ version: 99, tabs: [] }))
    expect(api.restoreTabsFromStorage()).toBe(false)
    localStorage.setItem(KEY, 'not-json')
    expect(api.restoreTabsFromStorage()).toBe(false)
  })

  it('schedules a debounced persist when the snapshot changes (not during restore)', async () => {
    vi.useFakeTimers()
    const { openTabs, tabStates } = (() => {
      const s = setup([])
      return s
    })()
    openTabs.value = [{ id: 't9', kind: 'table', tableName: 'x', dbName: 'db', sourceId: '1' }]
    tabStates['t9'] = { table: { dbName: 'db', sourceId: '1' }, query: { sql: '', limit: 200 } }
    await nextTick()
    vi.advanceTimersByTime(300)
    expect(localStorage.getItem(KEY)).not.toBeNull()
  })
})


it('persists independent query target and explicitly empty schema without changing object identity', () => {
  localStorage.clear()
  const original = setup([{ id: 't1', kind: 'table', tableName: 'orders', dbName: 'db', sourceId: '1' }])
  original.tabStates.t1 = { table: { id: 9, dbName: 'db', sourceId: '1' }, query: { sql: 'SELECT 1', sourceId: '2', dbName: '', limit: 200 } }
  original.api.flushPersistTabs()
  const restored = setup()
  expect(restored.api.restoreTabsFromStorage()).toBe(true)
  expect(restored.tabStates.t1.table).toMatchObject({ id: 9, dbName: 'db', sourceId: '1' })
  expect(restored.tabStates.t1.query).toMatchObject({ sql: 'SELECT 1', sourceId: '2', dbName: '' })
})
