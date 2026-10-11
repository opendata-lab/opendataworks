// DataStudioRightPanelFreshness 挂载与 API 接线测试。
// shallowMount 真正执行 script setup / computed / watch，验证：
// 1) 挂载即按当前表 id 拉取契约与历史；2) 切换表触发重新拉取；
// 3) 未配置时不抛错。DOM 细节交给运行时，这里锁住数据接线。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { reactive, ref, nextTick } from 'vue'

const { getFreshness, freshnessHistory, checkFreshness, saveFreshness, deleteFreshness, getFields } = vi.hoisted(() => ({
  getFreshness: vi.fn(),
  freshnessHistory: vi.fn(),
  checkFreshness: vi.fn(),
  saveFreshness: vi.fn(),
  deleteFreshness: vi.fn(),
  getFields: vi.fn(),
}))

vi.mock('@/api/table', () => ({
  tableApi: { getFreshness, freshnessHistory, checkFreshness, saveFreshness, deleteFreshness, getFields },
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal()
  const msg = Object.assign(vi.fn(), { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() })
  return { ...actual, ElMessage: msg, ElMessageBox: { confirm: vi.fn(() => Promise.resolve()) } }
})

import DataStudioRightPanelFreshness from '../components/DataStudioRightPanelFreshness.vue'
import { ElMessageBox } from 'element-plus'

const buildCtx = (activeTab, tabStates) => ({
  activeTab,
  tabStates,
  formatDateTime: (v) => String(v ?? '-'),
})

const unconfigured = { tableId: 1, configured: false, config: null, effective: null, latestResult: null }
const configured = {
  tableId: 1,
  configured: true,
  config: { mode: 'column', loadedAtField: 'etl_time', warnAfterCount: 2, warnAfterPeriod: 'hour' },
  effective: {
    mode: 'column',
    loadedAtField: 'etl_time',
    warnAfter: { count: 2, period: 'hour' },
    errorAfter: { count: 4, period: 'hour' },
    fieldSources: { mode: 'table', warnAfter: 'rule_default' },
  },
  latestResult: { status: 'pass', maxLoadedAt: '2026-08-06 02:00:00', snapshottedAt: '2026-08-06 03:00:00', ageSeconds: 3600, mode: 'column' },
}

const mountPanel = (tabStates, activeTab) =>
  shallowMount(DataStudioRightPanelFreshness, {
    global: { provide: { dataStudioCtx: buildCtx(activeTab, tabStates) } },
  })

describe('DataStudioRightPanelFreshness', () => {
  beforeEach(() => {
    getFreshness.mockReset().mockResolvedValue(unconfigured)
    freshnessHistory.mockReset().mockResolvedValue([])
    checkFreshness.mockReset().mockResolvedValue({})
  })

  it('挂载即按当前表拉取契约（历史改为按钮点开时才拉）', async () => {
    const activeTab = ref('t1')
    const tabStates = reactive({ t1: { table: { id: 1 } } })
    const wrapper = mountPanel(tabStates, activeTab)
    await flushPromises()

    expect(wrapper.exists()).toBe(true)
    expect(getFreshness).toHaveBeenCalledWith(1)
    // 历史不在挂载时拉取（收进「历史」按钮，点开才拉）
    expect(freshnessHistory).not.toHaveBeenCalled()
  })

  it('切换表触发重新拉取', async () => {
    const activeTab = ref('t1')
    const tabStates = reactive({ t1: { table: { id: 1 } }, t2: { table: { id: 2 } } })
    mountPanel(tabStates, activeTab)
    await flushPromises()
    expect(getFreshness).toHaveBeenLastCalledWith(1)

    activeTab.value = 't2'
    await nextTick()
    await flushPromises()
    expect(getFreshness).toHaveBeenLastCalledWith(2)
  })

  it('配置态挂载不抛错', async () => {
    getFreshness.mockResolvedValue(configured)
    freshnessHistory.mockResolvedValue([configured.latestResult])
    const activeTab = ref('t1')
    const tabStates = reactive({ t1: { table: { id: 1 } } })
    const wrapper = mountPanel(tabStates, activeTab)
    await flushPromises()
    expect(wrapper.exists()).toBe(true)
  })

  it('无当前表时不拉取', async () => {
    const activeTab = ref('t1')
    const tabStates = reactive({ t1: {} }) // 无 table
    mountPanel(tabStates, activeTab)
    await flushPromises()
    expect(getFreshness).not.toHaveBeenCalled()
  })
})

// 快速切表的异步竞态：迟到响应与失败都不能把上一张表的结果留在当前表上。
describe('DataStudioRightPanelFreshness 切表竞态', () => {
  const deferred = () => {
    let resolve
    const promise = new Promise((res) => { resolve = res })
    return { promise, resolve }
  }
  const withField = (tableId, field) => ({
    ...configured,
    tableId,
    config: { ...configured.config, loadedAtField: field },
    effective: { ...configured.effective, loadedAtField: field },
  })
  const passthrough = { template: '<div><slot name="label" /><slot /></div>' }
  const mountRendered = (tabStates, activeTab) =>
    shallowMount(DataStudioRightPanelFreshness, {
      global: {
        provide: { dataStudioCtx: buildCtx(activeTab, tabStates) },
        stubs: {
          ElScrollbar: passthrough,
          ElButton: { props: ['disabled', 'loading'], emits: ['click'], template: '<el-button-stub :disabled="disabled" @click="$emit(\'click\')"><slot /></el-button-stub>' },
          ElDialog: { props: ['modelValue'], template: '<el-dialog-stub :modelvalue="String(modelValue)"><slot /></el-dialog-stub>' },
          ElDescriptions: passthrough,
          ElDescriptionsItem: passthrough,
          ElTag: passthrough,
          ElEmpty: { props: ['description'], template: '<div class="empty">{{ description }}<slot /></div>' },
        },
        directives: { loading: {} },
        config: { warnHandler: () => {} },
      },
    })

  beforeEach(() => {
    getFreshness.mockReset()
  })

  it('A 表迟到的响应不覆盖已切换到的 B 表', async () => {
    const slowA = deferred()
    getFreshness.mockImplementation((id) => (id === 1 ? slowA.promise : Promise.resolve(withField(2, 'b_time'))))
    const activeTab = ref('t1')
    const tabStates = reactive({ t1: { table: { id: 1 } }, t2: { table: { id: 2 } } })
    const wrapper = mountRendered(tabStates, activeTab)
    await flushPromises()

    activeTab.value = 't2'
    await flushPromises()
    expect(wrapper.text()).toContain('b_time')

    slowA.resolve(withField(1, 'a_time'))
    await flushPromises()
    expect(wrapper.text()).toContain('b_time')
    expect(wrapper.text()).not.toContain('a_time')
  })

  it('B 表加载失败时不保留 A 表的旧结果', async () => {
    getFreshness.mockImplementation((id) =>
      id === 1 ? Promise.resolve(withField(1, 'a_time')) : Promise.reject(new Error('boom'))
    )
    const activeTab = ref('t1')
    const tabStates = reactive({ t1: { table: { id: 1 } }, t2: { table: { id: 2 } } })
    const wrapper = mountRendered(tabStates, activeTab)
    await flushPromises()
    expect(wrapper.text()).toContain('a_time')

    activeTab.value = 't2'
    await flushPromises()
    expect(wrapper.text()).not.toContain('a_time')
    expect(wrapper.text()).toContain('新鲜度加载失败')
  })

  const button = (wrapper, label) => wrapper.findAll('el-button-stub').find((node) => node.text() === label)
  const editingMount = () => {
    const activeTab = ref('t1')
    const tabStates = reactive({
      t1: { table: { id: 1, tableName: 'table_a' }, fields: [{ fieldName: 'a_time' }] },
      t2: { table: { id: 2, tableName: 'table_b' }, fields: [{ fieldName: 'b_time' }] },
    })
    getFreshness.mockImplementation((id) => Promise.resolve(withField(id, id === 1 ? 'a_time' : 'b_time')))
    return { wrapper: mountRendered(tabStates, activeTab), activeTab }
  }

  it('删除确认期间切表，不得删除新表或旧表', async () => {
    const confirm = deferred()
    ElMessageBox.confirm.mockReturnValueOnce(confirm.promise)
    deleteFreshness.mockReset().mockResolvedValue({})
    const { wrapper, activeTab } = editingMount()
    await flushPromises()
    await button(wrapper, '编辑').trigger('click')
    await button(wrapper, '删除').trigger('click')
    activeTab.value = 't2'
    await flushPromises()
    confirm.resolve()
    await flushPromises()
    expect(deleteFreshness).not.toHaveBeenCalled()
    expect(ElMessageBox.confirm).toHaveBeenLastCalledWith(expect.stringContaining('table_a'), '提示', expect.anything())
    expect(wrapper.text()).toContain('b_time')
    wrapper.unmount()
  })

  it('A 保存的迟到结果不能退出 B 的编辑态', async () => {
    const saved = deferred()
    saveFreshness.mockReset().mockReturnValueOnce(saved.promise)
    const { wrapper, activeTab } = editingMount()
    await flushPromises()
    await button(wrapper, '编辑').trigger('click')
    await button(wrapper, '保存').trigger('click')
    expect(saveFreshness).toHaveBeenCalledWith(1, expect.objectContaining({ loadedAtField: 'a_time' }))
    activeTab.value = 't2'
    await flushPromises()
    await button(wrapper, '编辑').trigger('click')
    saved.resolve({})
    await flushPromises()
    expect(button(wrapper, '保存')).toBeDefined()
    expect(getFreshness.mock.calls.map(([id]) => id)).toEqual([1, 2])
    wrapper.unmount()
  })

  it('切表关闭旧历史，迟到的历史结果不显示在新表窗口', async () => {
    const a = deferred(), b = deferred()
    freshnessHistory.mockReset().mockImplementation((id) => id === 1 ? a.promise : b.promise)
    const { wrapper, activeTab } = editingMount()
    await flushPromises()
    await button(wrapper, '历史').trigger('click')
    activeTab.value = 't2'
    await flushPromises()
    expect(wrapper.find('el-dialog-stub').attributes('modelvalue')).toBe('false')
    await button(wrapper, '历史').trigger('click')
    a.resolve([{ status: 'pass', mode: 'metadata' }])
    await flushPromises()
    expect(wrapper.find('el-dialog-stub el-table-stub').attributes('data')).not.toContain('metadata')
    b.resolve([])
    await flushPromises()
    wrapper.unmount()
  })

  it('A 检查完成不会重新加载 B 的配置或退出 B 的编辑态', async () => {
    const checked = deferred()
    checkFreshness.mockReset().mockReturnValueOnce(checked.promise)
    const { wrapper, activeTab } = editingMount()
    await flushPromises()
    await button(wrapper, '立即检查').trigger('click')
    activeTab.value = 't2'
    await flushPromises()
    await button(wrapper, '编辑').trigger('click')
    checked.resolve({})
    await flushPromises()
    expect(getFreshness.mock.calls.map(([id]) => id)).toEqual([1, 2])
    expect(button(wrapper, '保存')).toBeDefined()
    wrapper.unmount()
  })
  it('同一张表的保存、立即检查、删除仍走真实接口并刷新', async () => {
    saveFreshness.mockReset().mockResolvedValue({})
    checkFreshness.mockReset().mockResolvedValue({})
    deleteFreshness.mockReset().mockResolvedValue({})
    const { wrapper } = editingMount()
    await flushPromises()
    await button(wrapper, '编辑').trigger('click')
    await button(wrapper, '保存').trigger('click')
    await flushPromises()
    expect(saveFreshness).toHaveBeenCalledWith(1, expect.objectContaining({ loadedAtField: 'a_time' }))
    await button(wrapper, '立即检查').trigger('click')
    await flushPromises()
    expect(checkFreshness).toHaveBeenCalledWith(1)
    await button(wrapper, '编辑').trigger('click')
    await button(wrapper, '删除').trigger('click')
    await flushPromises()
    expect(deleteFreshness).toHaveBeenCalledWith(1)
    expect(getFreshness.mock.calls.map(([id]) => id)).toEqual([1, 1, 1, 1])
    wrapper.unmount()
  })

  it('检查请求完成前卸载组件，不刷新配置', async () => {
    const checked = deferred()
    checkFreshness.mockReset().mockReturnValueOnce(checked.promise)
    const { wrapper } = editingMount()
    await flushPromises()
    await button(wrapper, '立即检查').trigger('click')
    wrapper.unmount()
    checked.resolve({})
    await flushPromises()
    expect(getFreshness).toHaveBeenCalledTimes(1)
  })

})
