// Data Studio「存为任务」打开任务抽屉：展示执行目标、不预填调度数据源、该入口数据源必填。
// Element Plus 组件走与运行时相同的自动导入，不额外注册插件，避免表单上下文分属两份实例。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const { taskApi } = vi.hoisted(() => ({
  taskApi: {
    fetchDatasources: vi.fn(),
    getById: vi.fn(),
    getTaskLineage: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    checkTaskName: vi.fn(() => Promise.resolve(false)),
    analyzeSql: vi.fn(() => Promise.resolve({ inputRefs: [], outputRefs: [], unmatched: [], ambiguous: [] })),
  },
}))

vi.mock('@/api/task', () => ({ taskApi }))
vi.mock('@/api/workflow', () => ({ workflowApi: { list: vi.fn(() => Promise.resolve({ records: [] })) } }))
vi.mock('@/api/table', () => ({ tableApi: { options: vi.fn(() => Promise.resolve([])), getById: vi.fn() } }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {}, params: {} }), useRouter: () => ({ push: vi.fn() }) }))

import TaskEditDrawer from '../TaskEditDrawer.vue'

const mountDrawer = () =>
  mount(TaskEditDrawer, {
    global: {
      stubs: {
        ElDrawer: { template: '<div class="drawer"><slot /><slot name="footer" /></div>' },
        SqlEditor: true,
      },
      config: { warnHandler: () => {} },
    },
    attachTo: document.body,
  })

const formItem = (wrapper, label) =>
  wrapper.findAll('.el-form-item').find((item) => item.find('.el-form-item__label').text() === label)

describe('TaskEditDrawer queryTarget', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 目录里有与连接同名、与 Schema 同库的数据源，也不能被自动选中
    taskApi.fetchDatasources.mockResolvedValue([
      { id: 7, name: 'doris_prod', type: 'DORIS', dbName: 'dw' },
      { id: 8, name: '演示 Doris 连接', type: 'DORIS', dbName: 'dw' },
    ])
  })

  it('展示查询目标，数据源保持为空并标为必填', async () => {
    const wrapper = mountDrawer()
    await wrapper.vm.open(null, {
      taskSql: 'SELECT 1',
      taskName: 'q_task',
      taskDesc: 'From DataStudio',
      queryTarget: { sourceId: '1', sourceName: '演示 Doris 连接', dbName: 'dw' },
    })
    await flushPromises()

    const target = wrapper.find('[data-test="query-target"]')
    expect(target.exists()).toBe(true)
    expect(target.text()).toContain('演示 Doris 连接（ID 1） / dw')
    expect(target.text()).toContain('不会按名称自动匹配')
    expect(formItem(wrapper, '数据源').find('.el-select__placeholder').classes()).toContain('is-transparent')

    // 必填标记由表单 rules 计算，与提交时的校验同源
    expect(formItem(wrapper, '数据源').classes()).toContain('is-required')
    wrapper.unmount()
  })

  it('其他入口不显示查询目标，也不增加数据源必填', async () => {
    const wrapper = mountDrawer()
    await wrapper.vm.open(null, { taskSql: 'SELECT 1', taskName: 'q_task' })
    await flushPromises()
    expect(wrapper.find('[data-test="query-target"]').exists()).toBe(false)

    // 原有必填不变，数据源仍为可选
    expect(formItem(wrapper, '输出表').classes()).toContain('is-required')
    expect(formItem(wrapper, '数据源').classes()).not.toContain('is-required')
    wrapper.unmount()
  })
  it('无调度数据源时显示配置提示，支持重新加载', async () => {
    taskApi.fetchDatasources.mockResolvedValueOnce([])
    const wrapper = mountDrawer()
    await wrapper.vm.open(null, { taskSql: 'SELECT 1', queryTarget: { sourceId: '1' } })
    await flushPromises()
    expect(wrapper.find('[data-test="datasource-status"]').text()).toContain('暂无可用调度数据源')
    await wrapper.find('[data-test="datasource-status"] button').trigger('click')
    await flushPromises()
    expect(taskApi.fetchDatasources).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-test="datasource-status"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('加载失败清空旧目录并显示重试，不声称可以继续保存', async () => {
    const wrapper = mountDrawer()
    await wrapper.vm.open(null, { taskSql: 'SELECT 1' })
    taskApi.fetchDatasources.mockRejectedValueOnce(new Error('scheduler offline'))
    await wrapper.vm.open(null, { taskSql: 'SELECT 2' })
    await flushPromises()
    expect(wrapper.find('[data-test="datasource-status"]').text()).toContain('调度数据源加载失败')
    expect(wrapper.text()).not.toContain('可继续编辑并保存')
    wrapper.unmount()
  })

  it('先前打开抽屉的迟到目录不覆盖新抽屉及预填 SQL', async () => {
    let finishOld
    taskApi.fetchDatasources.mockReturnValueOnce(new Promise(resolve => { finishOld = resolve }))
    const wrapper = mountDrawer()
    const firstOpen = wrapper.vm.open(null, { taskSql: 'SELECT old', queryTarget: { sourceId: '1', sourceName: 'Old' } })
    await flushPromises()
    taskApi.fetchDatasources.mockResolvedValueOnce([])
    await wrapper.vm.open(null, { taskSql: 'SELECT new', queryTarget: { sourceId: '2', sourceName: 'New' } })
    finishOld([{ id: 3, name: 'stale-source' }])
    await firstOpen
    await flushPromises()
    expect(wrapper.find('[data-test="query-target"]').text()).toContain('New')
    expect(wrapper.find('[data-test="datasource-status"]').text()).toContain('暂无可用调度数据源')
    expect(wrapper.findComponent({ name: 'SqlEditor' }).attributes('modelvalue')).toBe('SELECT new')
    wrapper.unmount()
  })

  it('切换调度实例清空已选数据源和旧选项，迟到响应不覆盖新目录', async () => {
    let finishOld, finishNew
    const wrapper = mountDrawer()
    await wrapper.vm.open(null, { taskSql: 'SELECT 1' })
    const select = formItem(wrapper, '数据源').findComponent({ name: 'ElSelect' })
    select.vm.$emit('update:modelValue', 'doris_prod')
    await flushPromises()
    expect(select.props('modelValue')).toBe('doris_prod')
    taskApi.fetchDatasources.mockImplementation((params) => new Promise(resolve => {
      if (params.dolphinConfigId === 2) finishOld = resolve
      else finishNew = resolve
    }))
    await wrapper.setProps({ dolphinConfigId: 2 })
    await flushPromises()
    expect(select.props('modelValue')).toBe('')
    expect(select.props('loading')).toBe(true)
    await wrapper.setProps({ dolphinConfigId: 3 })
    await flushPromises()
    finishNew([])
    await flushPromises()
    finishOld([{ id: 9, name: 'old-env' }])
    await flushPromises()
    expect(wrapper.find('[data-test="datasource-status"]').text()).toContain('暂无可用调度数据源')
    wrapper.unmount()
  })

  it('编辑任务初始化加载目录后保留原数据源', async () => {
    taskApi.getById.mockResolvedValue({ id: 9, taskName: 'existing', taskSql: 'SELECT 1', datasourceName: 'doris_prod', dolphinNodeType: 'SQL' })
    taskApi.getTaskLineage.mockResolvedValue({ inputTableIds: [], outputTableIds: [] })
    const wrapper = mountDrawer()
    await wrapper.vm.open(9)
    await flushPromises()
    expect(formItem(wrapper, '数据源').findComponent({ name: 'ElSelect' }).props('modelValue')).toBe('doris_prod')
    wrapper.unmount()
  })

})
