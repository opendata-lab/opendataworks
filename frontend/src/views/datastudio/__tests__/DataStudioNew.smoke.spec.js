import { definition } from '@/utils/__tests__/datasourceFixtures'
// 运行时挂载冒烟（P2-2 F4+ 的安全网）。
// shallowMount 会真正执行 DataStudioNew 的 script setup / composables / computed /
// onMounted 与模板渲染（子组件被 stub）。若 composable 抽取破坏了引用或响应式，
// 挂载/渲染会抛错，本测试即失败 —— 这是 build/lint 之外能在本环境拿到的运行时验证。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'

// 所有被访问的方法都返回一个稳定的空响应，避免 onMounted 内的接口调用抛错。
// 用 vi.hoisted 让工厂可在被提升的 vi.mock 中安全引用。
const { apiStub, routeState, replaceRoute, listClusters, getTable } = vi.hoisted(() => {
  const routeState = { query: {}, params: {}, path: '/datastudio', fullPath: '/datastudio' }
  const replaceRoute = vi.fn()
  const listClusters = vi.fn(() => Promise.resolve([]))
  const getTable = vi.fn(() => Promise.resolve({}))
  const apiStub = (overrides = {}) =>
    new Proxy(
      {},
      {
        get: (_, prop) => overrides[prop] || (() =>
          Promise.resolve({ data: [], records: [], total: 0, code: 0, success: true })),
      }
    )
  return { apiStub, routeState, replaceRoute, listClusters, getTable }
})

vi.mock('@/api/table', () => ({ tableApi: apiStub({ getById: getTable }) }))
vi.mock('@/api/lineage', () => ({ lineageApi: apiStub() }))
vi.mock('@/api/doris', () => ({ dorisClusterApi: apiStub({ list: listClusters }) }))
vi.mock('@/api/query', () => ({ dataQueryApi: apiStub() }))
vi.mock('@/api/domain', () => ({ businessDomainApi: apiStub(), dataDomainApi: apiStub() }))

vi.mock('@/utils/loadEcharts', () => ({ loadEcharts: vi.fn(() => Promise.resolve({})) }))
vi.mock('@/demo/runtime', () => ({ isDemoMode: false, showDemoReadonlyMessage: vi.fn() }))

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => ({ push: vi.fn(), replace: replaceRoute, currentRoute: { value: { query: {} } } }),
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal()
  const msg = Object.assign(vi.fn(), {
    success: vi.fn(),
    warning: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    closeAll: vi.fn(),
  })
  return {
    ...actual,
    ElMessage: msg,
    ElMessageBox: {
      ...actual.ElMessageBox,
      confirm: vi.fn(() => Promise.resolve()),
      alert: vi.fn(() => Promise.resolve()),
    },
  }
})

import DataStudioNew from '../DataStudioNew.vue'

// jsdom 不带 IntersectionObserver/ResizeObserver；组件 onMounted 会用到，stub 掉以免
// 异步路径产生无关的未处理错误污染冒烟信号
class NoopObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return []
  }
}

describe('DataStudioNew runtime mount smoke', () => {
  beforeEach(() => {
    localStorage.clear()
    routeState.query = {}
    replaceRoute.mockClear()
    listClusters.mockReset().mockResolvedValue([])
    getTable.mockReset().mockResolvedValue({})
    vi.stubGlobal('IntersectionObserver', NoopObserver)
    vi.stubGlobal('ResizeObserver', NoopObserver)
  })

  it('mounts and renders without throwing (setup + composables + onMounted)', () => {
    const wrapper = shallowMount(DataStudioNew, {
      global: {
        stubs: {
          // 重组件统一 stub，专注验证本组件自身的 setup/模板不抛错
          PersistentTabs: true,
          TaskEditDrawer: true,
          DataStudioResultGrid: true,
          DataStudioRightPanel: true,
          SqlEditor: true,
          CreateTableDrawer: true,
        },
        // 让未注册的 Element Plus 元素/指令不致命
        config: { warnHandler: () => {} },
        directives: {
          loading: {},
        },
      },
    })

    expect(wrapper.exists()).toBe(true)
    // 根容器渲染成功
    expect(wrapper.find('.data-studio, .datastudio, [class]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('keeps synced PostgreSQL objects out of Doris-only panels and operations', () => {
    const wrapper = shallowMount(DataStudioNew, { global: {
      stubs: { PersistentTabs: true, TaskEditDrawer: true, DataStudioResultGrid: true, DataStudioRightPanel: true, SqlEditor: true, CreateTableDrawer: true },
      config: { warnHandler: () => {} }, directives: { loading: {} },
    } })
    const state = wrapper.vm.$.setupState
    expect(state.hasEngineProperties({ sourceType: 'POSTGRESQL', typeDefinition: definition('POSTGRESQL'), isSynced: 1 })).toBe(false)
    expect(state.hasEngineProperties({ sourceType: 'MYSQL', typeDefinition: definition('MYSQL'), isSynced: 1 })).toBe(false)
    expect(state.hasEngineProperties({ sourceType: 'DORIS', typeDefinition: definition('DORIS'), isSynced: 1 })).toBe(true)
    // 只有 Doris/MySQL 能改物理结构；类型未知的已同步表不放行，未同步的平台表不涉及物理结构
    expect(state.structureEditBlockedReason({ sourceType: 'POSTGRESQL', typeDefinition: definition('POSTGRESQL'), isSynced: 1 })).toContain('PostgreSQL')
    expect(state.structureEditBlockedReason({ sourceType: 'MYSQL', typeDefinition: definition('MYSQL'), isSynced: 1 })).toBe('')
    expect(state.structureEditBlockedReason({ sourceType: 'DORIS', typeDefinition: definition('DORIS'), isSynced: 1 })).toBe('')
    expect(state.structureEditBlockedReason({ isSynced: 1 })).toContain('数据源类型描述未加载')
    expect(state.structureEditBlockedReason({ isSynced: 0 })).toBe('')
    wrapper.unmount()
  })
  it('incoming table link wins over restored tabs during delayed connection loading, preserving cached SQL', async () => {
    const target = { id: 20, tableName: 'target_table', dbName: 'db', clusterId: 3, sourceType: 'MYSQL', isSynced: 1 }
    routeState.query = { clusterId: '3', database: 'db', tableId: '20', tableName: 'target_table', tab: '3::db.target_table' }
    let resolveClusters
    listClusters.mockReturnValueOnce(new Promise(resolve => { resolveClusters = resolve }))
    getTable.mockImplementation(id => Promise.resolve(Number(id) === 20 ? target : { id: 10, tableName: 'cached_table', dbName: 'old_db', clusterId: 1 }))
    localStorage.setItem('odw:datastudio:workspace-tabs:v1', JSON.stringify({
      version: 1, activeTab: '1::old_db.cached_table', tabs: [{
        id: '1::old_db.cached_table', kind: 'table', tableId: 10,
        tableName: 'cached_table', dbName: 'old_db', sourceId: '1', sql: 'SELECT 123 AS cached_sql', limit: 50,
      }],
    }))
    const wrapper = shallowMount(DataStudioNew, { global: {
      stubs: { PersistentTabs: true, TaskEditDrawer: true, DataStudioResultGrid: true, DataStudioRightPanel: true, SqlEditor: true, CreateTableDrawer: true },
      config: { warnHandler: () => {} }, directives: { loading: {} },
    } })
    await flushPromises()
    expect(replaceRoute).not.toHaveBeenCalled()
    resolveClusters([{ id: 1, sourceType: 'MYSQL' }, { id: 3, sourceType: 'MYSQL' }])
    await flushPromises()
    const state = wrapper.vm.$.setupState
    expect(state.activeTab).toBe('3::db.target_table')
    expect(state.tabStates['1::old_db.cached_table'].query.sql).toBe('SELECT 123 AS cached_sql')
    expect(state.suppressRouteSync).toBe(false)
    expect(replaceRoute.mock.calls.every(([arg]) => arg.query.tableName !== 'cached_table')).toBe(true)
    // 初始化后普通切换继续写入 URL。
    state.activeTab = '1::old_db.cached_table'
    await flushPromises()
    expect(replaceRoute).toHaveBeenLastCalledWith(expect.objectContaining({ query: expect.objectContaining({ tableName: 'cached_table' }) }))
    wrapper.unmount()
  })

})
