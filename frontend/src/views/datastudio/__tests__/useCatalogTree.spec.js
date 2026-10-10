import { describe, it, expect, vi } from 'vitest'
import { createApp, ref, nextTick } from 'vue'

vi.mock('element-plus', () => ({ ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() } }))
vi.mock('@/api/table', () => ({ tableApi: {} }))
vi.mock('@/api/doris', () => ({ dorisClusterApi: { testConnection: vi.fn().mockResolvedValue({}) } }))

import { useCatalogTree } from '../composables/useCatalogTree'

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

function setup() {
  const { result, unmount } = withSetup(() =>
    useCatalogTree({
      clusterId: ref(null),
      tabStates: {},
      openTabs: ref([]),
      activeTab: ref(''),
      tableObserver: ref(null),
      handleQuerySourceSelect: vi.fn(),
      handleQueryDatabaseSelect: vi.fn(),
      openTableTab: vi.fn(),
    })
  )
  return { api: result, unmount }
}

describe('useCatalogTree (getters / filter)', () => {
  it('getDatasourceById finds by stringified id from the owned dataSources ref', () => {
    const { api, unmount } = setup()
    api.dataSources.value = [{ id: 1, name: 'demo' }, { id: 2, name: 'prod' }]
    expect(api.getDatasourceById(1)).toMatchObject({ name: 'demo' })
    expect(api.getDatasourceById('2')).toMatchObject({ name: 'prod' })
    expect(api.getDatasourceById(99)).toBeNull()
    unmount()
  })

  it('filterCatalogNode matches table nodes by name/comment, empty value passes', () => {
    const { api, unmount } = setup()
    expect(api.filterCatalogNode('', { type: 'table', name: 'orders' })).toBe(true)
    expect(
      api.filterCatalogNode('ord', { type: 'table', table: { tableName: 'orders' } })
    ).toBe(true)
    expect(
      api.filterCatalogNode('订单', { type: 'table', table: { tableName: 'orders', tableComment: '订单表' } })
    ).toBe(true)
    expect(
      api.filterCatalogNode('xyz', { type: 'table', table: { tableName: 'orders', tableComment: '订单' } })
    ).toBe(false)
    unmount()
  })

  it('exposes the shared catalog caches as a single source of truth', () => {
    const { api, unmount } = setup()
    // schemaStore/tableStore/columnStore 由本 composable 拥有并返回（供补全/路由/查询共享同一引用）
    expect(api.schemaStore).toBeTypeOf('object')
    expect(api.tableStore).toBeTypeOf('object')
    expect(api.columnStore).toBeTypeOf('object')
    unmount()
  })
})


it('sorts loaded leaf groups while keeping parent nodes and selected object', async () => {
  const { api, unmount } = setup()
  api.tableStore['1'] = { db: [{ id: 1, tableName: 'a' }, { id: 2, tableName: 'z' }] }
  const tree = {
    getNode: vi.fn(key => ({ loaded: key.startsWith('group:') })),
    updateKeyChildren: vi.fn(), filter: vi.fn(), setCurrentKey: vi.fn(),
  }
  api.catalogTreeRef.value = tree
  api.selectedTableKey.value = '1::db.a'
  await nextTick()
  tree.updateKeyChildren.mockClear()
  api.sortOrder.value = 'desc'
  await nextTick()
  await nextTick()
  const calls = tree.updateKeyChildren.mock.calls
  expect(calls.map(([key]) => key)).toEqual(['group:1::db::table', 'group:1::db::view'])
  expect(calls[0][1].map(node => node.table.tableName)).toEqual(['z', 'a'])
  expect(tree.setCurrentKey).toHaveBeenLastCalledWith('1::db.a', false)
  unmount()
})


it('shows activation only after successful connection and Schema object loading', async () => {
  const { api, unmount } = setup()
  api.dataSources.value = [{ id: 1, status: 'active' }]
  const source = { type: 'datasource', sourceId: '1', status: 'active' }
  const schema = { type: 'schema', sourceId: '1', schemaName: 'db' }
  expect(api.isCatalogNodeActive(source)).toBe(false)
  expect(api.isCatalogNodeActive(schema)).toBe(false)
  await api.activateDatasource('1')
  expect(api.isCatalogNodeActive(source)).toBe(true)
  expect(api.isCatalogNodeActive(schema)).toBe(false)
  api.tableStore['1'] = { db: [] }
  expect(api.isCatalogNodeActive(schema)).toBe(true)
  api.dataSources.value[0].status = 'disabled'
  expect(api.isCatalogNodeActive({ ...source, status: 'disabled' })).toBe(false)
  expect(api.isCatalogNodeActive(schema)).toBe(false)
  unmount()
})
