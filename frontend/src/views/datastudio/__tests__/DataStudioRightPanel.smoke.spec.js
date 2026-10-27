// DataStudioRightPanel 运行时挂载冒烟（P2-2 F17 的安全网）。
// DataStudioNew.smoke 把 RightPanel stub 掉了，所以这里单独挂载:
// 用假 dataStudioCtx provide + shallowMount 真正执行 script setup / composables /
// computed 与模板渲染。若 F17 抽取破坏了引用或响应式,挂载/渲染会抛错。
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { nextTick, reactive, ref } from 'vue'

const { apiStub, listPartitionsMock } = vi.hoisted(() => {
  const listPartitionsMock = vi.fn(() => Promise.resolve([]))
  // 默认返回通用空响应；listPartitions 走可控 mock，便于断言分区列表渲染
  const apiStub = () =>
    new Proxy(
      { listPartitions: listPartitionsMock },
      {
        get: (target, prop) =>
          prop in target
            ? target[prop]
            : () => Promise.resolve({ data: [], records: [], total: 0, code: 0, success: true }),
      }
    )
  return { apiStub, listPartitionsMock }
})

vi.mock('@/api/table', () => ({ tableApi: apiStub() }))
vi.mock('@/utils/loadEcharts', () => ({ loadEcharts: vi.fn(() => Promise.resolve({})) }))
vi.mock('@/demo/runtime', () => ({ isDemoMode: false, showDemoReadonlyMessage: vi.fn() }))

import DataStudioRightPanel from '../components/DataStudioRightPanel.vue'
import DataStudioRightPanelBasic from '../components/DataStudioRightPanelBasic.vue'
import DataStudioRightPanelColumns from '../components/DataStudioRightPanelColumns.vue'
import DataStudioRightPanelAccess from '../components/DataStudioRightPanelAccess.vue'
import DataStudioRightPanelDoris from '../components/DataStudioRightPanelDoris.vue'
import SmartMetadataDialog from '../components/SmartMetadataDialog.vue'

const buildCtx = () => {
  const activeTab = ref('t1')
  const tabStates = reactive({
    t1: {
      table: { id: 1, tableName: 'demo_t', dbName: 'db', sourceId: 's1', sourceType: 'DORIS' },
      metaTab: 'basic',
      metaEditing: false,
      metaSaving: false,
      metaForm: {},
      metaDataDomainOptions: [],
      metaSuggestion: null,
      metadataSyncing: false,
      fieldSubmitting: false,
      fieldsEditing: false,
      fieldsDraft: [],
      fieldsRemoved: [],
      fields: [],
      ddl: '',
      ddlLoading: false,
      accessLoading: false,
      accessStats: null,
      accessError: '',
      lineage: { upstreamTables: [], downstreamTables: [], edges: [] },
      tasks: { writeTasks: [], readTasks: [] },
    },
  })
  const fn = () => vi.fn()
  return {
    getSourceName: () => '测试连接',
    clusterId: ref('s1'),
    openTabs: ref([{ id: 't1', kind: 'table', tableName: 'demo_t' }]),
    activeTab,
    tabStates,
    layerOptions: [],
    businessDomainOptions: ref([]),
    getMetaDataDomainOptions: () => [],
    handleMetaBusinessDomainChange: fn(),
    hasEngineProperties: () => true,
    structureEditBlockedReason: () => '',
    isPlatformMetadataMissing: () => false,
    isAggregateTable: () => false,
    isReplicaWarning: () => false,
    getFieldRows: () => [],
    startMetaEdit: fn(),
    cancelMetaEdit: fn(),
    saveMetaEdit: fn(),
    handleDeleteTable: fn(),
    syncMissingTableMetadata: fn(),
    startFieldsEdit: fn(),
    cancelFieldsEdit: fn(),
    saveFieldsEdit: fn(),
    addField: fn(),
    removeField: fn(),
    copyDdl: fn(),
    loadAccessStats: fn(),
    formatDuration: (v) => String(v ?? '-'),
    formatDateTime: (v) => String(v ?? '-'),
    goLineage: fn(),
    goCreateRelatedTask: fn(),
    openTask: fn(),
    openTableTab: fn(),
    generateMetadata: fn(),
    adoptMetadata: fn(),
    metadataGenerating: ref(false),
    metadataAdopting: ref(false),
    metadataDialogVisible: ref(false),
    metadataResult: ref(null),
  }
}

describe('DataStudioRightPanel mount smoke', () => {
  beforeEach(() => vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} }))
  afterEach(() => vi.unstubAllGlobals())
  it('mounts with a table tab and renders the panel shell', async () => {
    const wrapper = shallowMount(DataStudioRightPanel, {
      global: {
        provide: { dataStudioCtx: buildCtx() },
        stubs: {
          DataStudioRightPanelLineage: true,
          TableVersionHistoryPanel: true,
          TableTrendDialog: true,
          ElScrollbar: { template: '<div><slot /></div>' },
        },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })
    expect(wrapper.exists()).toBe(true)
    expect(wrapper.find('.panel-shell').exists()).toBe(true)
    expect(wrapper.find('.inspector-tabs-shell').exists()).toBe(true)
    expect(wrapper.find('.panel-resizer').exists()).toBe(false)
    wrapper.unmount()
  })

  it('mounts each pane child with the ctx (P2-2 F17d)', () => {
    const panes = [
      [DataStudioRightPanelBasic, '.meta-descriptions'],
      [DataStudioRightPanelColumns, '.section-block'],
      [DataStudioRightPanelAccess, '.section-block'],
    ]
    for (const [Pane, marker] of panes) {
      const wrapper = shallowMount(Pane, {
        global: {
          provide: { dataStudioCtx: buildCtx() },
          stubs: { TableTrendDialog: true, ElScrollbar: { template: '<div><slot /></div>' } },
          config: { warnHandler: () => {} },
          directives: { loading: {} },
        },
      })
      expect(wrapper.exists()).toBe(true)
      expect(wrapper.find(marker).exists()).toBe(true)
      wrapper.unmount()
    }
  })

  it('PostgreSQL 表编辑表信息时表名和表注释只读，平台属性仍可编辑', () => {
    const ctx = buildCtx()
    ctx.tabStates.t1.metaEditing = true
    ctx.tabStates.t1.metaForm = { tableName: 'orders', tableComment: '订单', layer: 'DWD' }
    ctx.structureEditBlockedReason = () => 'PostgreSQL 暂不支持在线修改表结构，请在数据库中修改后重新同步元数据'
    const wrapper = shallowMount(DataStudioRightPanelBasic, {
      global: {
        provide: { dataStudioCtx: ctx },
        stubs: { TableTrendDialog: true, ElScrollbar: { template: '<div><slot /></div>' } },
        // el-descriptions 的 item 是插槽内容，浅挂载默认不渲染
        renderStubDefaultSlot: true,
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })
    // 表单控件按出现顺序：表名、表注释、分层……
    const controls = wrapper.findAll('.meta-input')
    expect(controls.length).toBeGreaterThan(2)
    expect(controls[0].attributes('disabled')).toBe('true')
    expect(controls[0].attributes('title')).toContain('PostgreSQL')
    expect(controls[1].attributes('disabled')).toBe('true')
    // 分层等平台属性不受影响
    expect(controls[2].attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('智能元数据：数据源不支持改注释时不勾选、不提交表/字段描述，平台属性照常', () => {
    const ctx = buildCtx()
    ctx.structureEditBlockedReason = () => 'PostgreSQL 暂不支持在线修改表结构'
    ctx.metadataDialogVisible.value = true
    ctx.metadataResult.value = {
      tabId: 't1',
      table: { hasRecommendation: true, currentComment: '', suggestedComment: '订单表' },
      attributes: [{ key: 'layer', label: '分层', hasRecommendation: true, suggestedValue: 'DWD' }],
      freshness: { hasRecommendation: false },
      fields: [{ fieldName: 'status', hasRecommendation: true, currentComment: '', suggestedComment: '状态' }],
    }
    const wrapper = shallowMount(SmartMetadataDialog, {
      global: {
        provide: { dataStudioCtx: ctx },
        // 只展开弹窗本身；表格等子组件保持浅挂载
        stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } },
        config: { warnHandler: () => {} },
      },
    })
    expect(wrapper.find('[data-test="smd-comment-blocked"]').exists()).toBe(true)
    wrapper.vm.$.setupState.onAdopt()
    expect(ctx.adoptMetadata).toHaveBeenCalledWith('t1', expect.objectContaining({
      table: null,
      fields: [],
      attributes: [{ key: 'layer', value: 'DWD' }],
    }))
    wrapper.unmount()
  })

  it('智能元数据：对应的表已关闭时提示并禁用采纳', () => {
    const ctx = buildCtx()
    ctx.structureEditBlockedReason = () => ''
    ctx.metadataDialogVisible.value = true
    ctx.metadataResult.value = {
      tabId: 'closed-tab',
      table: { hasRecommendation: true, currentComment: '', suggestedComment: '订单表' },
      attributes: [],
      freshness: { hasRecommendation: false },
      fields: [],
    }
    const wrapper = shallowMount(SmartMetadataDialog, {
      global: {
        provide: { dataStudioCtx: ctx },
        stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } },
        config: { warnHandler: () => {} },
      },
    })
    expect(wrapper.find('[data-test="smd-target-missing"]').exists()).toBe(true)
    expect(wrapper.vm.$.setupState.targetMissing).toBe(true)
    wrapper.unmount()
  })

  it('表信息默认两列，面板收窄后退回一列', async () => {
    // 归属信息按可用宽度选择列数；长时间项始终跨满当前行。
    const observers = []
    class MockResizeObserver {
      constructor(callback) {
        this.callback = callback
        observers.push(this)
      }
      observe() {}
      disconnect() {}
      emit(width) {
        this.callback([{ contentRect: { width } }])
      }
    }
    vi.stubGlobal('ResizeObserver', MockResizeObserver)

    const wrapper = shallowMount(DataStudioRightPanelBasic, {
      global: {
        provide: { dataStudioCtx: buildCtx() },
        stubs: {
          TableTrendDialog: true,
          ElScrollbar: { template: '<div><slot /></div>' },
          ElDescriptions: {
            props: ['column'],
            template: '<div class="desc" :data-column="column"><slot /></div>',
          },
          ElDescriptionsItem: {
            props: ['label', 'span'],
            template: '<div class="desc-item" :data-label="label" :data-span="span"><slot /></div>',
          },
        },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })
    const column = () => wrapper.find('.desc').attributes('data-column')
    // 长时间项跨满整行
    const commentSpan = () =>
      wrapper.findAll('.desc-item').find((n) => n.attributes('data-label') === '创建时间').attributes('data-span')

    expect(column()).toBe('2')
    expect(commentSpan()).toBe('2')

    observers[0].emit(320)
    await nextTick()
    expect(column()).toBe('1')
    expect(commentSpan()).toBe('1')

    observers[0].emit(720)
    await nextTick()
    expect(column()).toBe('2')

    // tab 隐藏时宽度为 0，不能把列数抖回一列
    observers[0].emit(0)
    await nextTick()
    expect(column()).toBe('2')

    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('访问概况展示同步关闭状态和历史覆盖信息', () => {
    const ctx = buildCtx()
    ctx.tabStates.t1.accessStats = {
      totalAccessCount: 12,
      recentAccessCount: 3,
      distinctUserCount: 1,
      trendDays: 14,
      trend: [],
      topUsers: [],
      tableAccessSyncStatus: 'DISABLED',
      tableAccessCoverageComplete: false,
      tableAccessCoverageStart: '2026-07-01 00:00:00',
      tableAccessLastSyncedAt: '2026-07-30 12:00:00',
      note: 'Doris 审计访问同步已关闭'
    }
    const wrapper = shallowMount(DataStudioRightPanelAccess, {
      global: {
        provide: { dataStudioCtx: ctx },
        stubs: {
          ElScrollbar: { template: '<div><slot /></div>' },
          ElTag: { template: '<span><slot /></span>' },
          ElAlert: { props: ['title'], template: '<div>{{ title }}</div>' },
        },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })

    expect(wrapper.text()).toContain('同步已关闭')
    expect(wrapper.text()).toContain('历史覆盖')
    expect(wrapper.text()).toContain('2026-07-01 00:00:00')
    wrapper.unmount()
  })

  it.each([
    ['READY', '已就绪'],
    ['BACKFILLING', '历史回填中'],
    ['DEGRADED', '同步异常'],
    ['DISABLED', '同步已关闭'],
    ['UNAVAILABLE', '统计不可用'],
  ])('访问概况将 %s 映射为明确状态提示', (status, label) => {
    const ctx = buildCtx()
    ctx.tabStates.t1.accessStats = {
      totalAccessCount: 0,
      trend: [],
      topUsers: [],
      tableAccessSyncStatus: status,
    }
    const wrapper = shallowMount(DataStudioRightPanelAccess, {
      global: {
        provide: { dataStudioCtx: ctx },
        stubs: {
          ElScrollbar: { template: '<div><slot /></div>' },
          ElTag: { template: '<span><slot /></span>' },
        },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })

    expect(wrapper.text()).toContain(label)
    wrapper.unmount()
  })

  it('访问概况兼容缺少新增同步字段的旧响应', () => {
    const ctx = buildCtx()
    ctx.tabStates.t1.accessStats = {
      totalAccessCount: 8,
      recentAccessCount: 2,
      distinctUserCount: 1,
      trend: [],
      topUsers: [],
    }
    const wrapper = shallowMount(DataStudioRightPanelAccess, {
      global: {
        provide: { dataStudioCtx: ctx },
        stubs: { ElScrollbar: { template: '<div><slot /></div>' } },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })

    expect(wrapper.text()).toContain('总访问次数')
    expect(wrapper.text()).toContain('8')
    wrapper.unmount()
  })

  it('头部操作区随 tab 联动', () => {
    const ctx = buildCtx()
    const mountPanel = () =>
      shallowMount(DataStudioRightPanel, {
        global: {
          provide: { dataStudioCtx: ctx },
          stubs: {
            DataStudioRightPanelLineage: true,
            SmartMetadataDialog: true,
            ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
            ElDropdownMenu: { template: '<div><slot /></div>' },
            ElScrollbar: { template: '<div><slot /></div>' },
          },
          config: { warnHandler: () => {} },
          directives: { loading: {} },
        },
      })
    const actions = (w) => ({
      generate: w.find('[data-test="action-generate"]').exists(),
      edit: w.find('[data-test="action-edit"]').exists(),
      del: w.find('[data-test="action-delete"]').exists(),
      copy: w.find('[data-test="action-copy-ddl"]').exists(),
      cancel: w.find('[data-test="action-cancel"]').exists(),
      save: w.find('[data-test="action-save"]').exists(),
    })

    // 表信息：可编辑
    let wrapper = mountPanel()
    expect(actions(wrapper)).toMatchObject({ generate: true, edit: true, del: true, copy: false })
    wrapper.unmount()

    // 变更记录：无可编辑内容
    ctx.tabStates.t1.metaTab = 'versions'
    wrapper = mountPanel()
    expect(actions(wrapper)).toMatchObject({ generate: true, edit: false, del: true })
    wrapper.unmount()

    // DDL：不可编辑；复制按钮留在 DDL 内容区，不在头部
    ctx.tabStates.t1.metaTab = 'ddl'
    wrapper = mountPanel()
    expect(actions(wrapper)).toMatchObject({ generate: true, edit: false, del: true, copy: false })
    wrapper.unmount()

    // 列信息编辑态：只剩取消 / 保存
    ctx.tabStates.t1.metaTab = 'columns'
    ctx.tabStates.t1.fieldsEditing = true
    wrapper = mountPanel()
    expect(actions(wrapper)).toMatchObject({ cancel: true, save: true, generate: false, del: false })
    wrapper.unmount()
  })

  it('PostgreSQL 表的字段编辑和删表禁用并说明原因，表信息仍可编辑', () => {
    const ctx = buildCtx()
    ctx.hasEngineProperties = () => false
    ctx.structureEditBlockedReason = () => 'PostgreSQL 暂不支持在线修改表结构，请在数据库中修改后重新同步元数据'
    const mountPanel = () =>
      shallowMount(DataStudioRightPanel, {
        global: {
          provide: { dataStudioCtx: ctx },
          stubs: {
            DataStudioRightPanelLineage: true,
            SmartMetadataDialog: true,
            ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
            ElDropdownMenu: { template: '<div><slot /></div>' },
            ElDropdownItem: { props: ['disabled', 'title'], template: '<div :data-disabled="String(!!disabled)" :title="title"><slot /></div>' },
            ElTooltip: { props: ['content'], template: '<div class="tooltip-stub" :data-content="content"><slot /></div>' },
            ElScrollbar: { template: '<div><slot /></div>' },
          },
          config: { warnHandler: () => {} },
          directives: { loading: {} },
        },
      })

    // 表信息：平台属性仍可编辑；删表禁用
    let wrapper = mountPanel()
    const pgReason = (w) => w.findAll('.tooltip-stub')
      .some((t) => String(t.attributes('data-content') || '').includes('PostgreSQL 暂不支持在线修改表结构'))
    expect(pgReason(wrapper)).toBe(false)
    const del = wrapper.find('[data-test="action-delete"]')
    expect(del.attributes('data-disabled')).toBe('true')
    expect(del.attributes('title')).toContain('PostgreSQL 暂不支持在线修改表结构')
    wrapper.unmount()

    // 列信息：编辑按钮禁用并给出原因
    ctx.tabStates.t1.metaTab = 'columns'
    wrapper = mountPanel()
    expect(pgReason(wrapper)).toBe(true)
    wrapper.unmount()
  })

  it('引擎配置页直接进入元数据编辑，保存走同一链路', async () => {
    const ctx = buildCtx()
    ctx.tabStates.t1.metaTab = 'doris'
    const ButtonStub = {
      props: ['disabled', 'loading', 'type', 'size', 'text'],
      emits: ['click'],
      template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    }
    const mountPanel = () =>
      shallowMount(DataStudioRightPanel, {
        global: {
          provide: { dataStudioCtx: ctx },
          stubs: {
            DataStudioRightPanelLineage: true,
            SmartMetadataDialog: true,
            ElButton: ButtonStub,
            ElTooltip: { props: ['content'], template: '<div :data-tip="content"><slot /></div>' },
            ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
            ElDropdownMenu: { template: '<div><slot /></div>' },
            ElScrollbar: { template: '<div><slot /></div>' },
          },
          config: { warnHandler: () => {} },
          directives: { loading: {} },
        },
      })

    let wrapper = mountPanel()
    const edit = wrapper.find('[data-test="action-edit"]')
    expect(edit.exists()).toBe(true)
    await edit.trigger('click')
    expect(ctx.startMetaEdit).toHaveBeenCalledWith('t1')
    expect(ctx.startFieldsEdit).not.toHaveBeenCalled()
    wrapper.unmount()

    ctx.tabStates.t1.metaEditing = true
    wrapper = mountPanel()
    await wrapper.find('[data-test="action-save"]').trigger('click')
    expect(ctx.saveMetaEdit).toHaveBeenCalledWith('t1')
    await wrapper.find('[data-test="action-cancel"]').trigger('click')
    expect(ctx.cancelMetaEdit).toHaveBeenCalledWith('t1')
    wrapper.unmount()

    // 未选择集群时与概览一致给出禁用原因
    ctx.tabStates.t1.metaEditing = false
    ctx.clusterId.value = ''
    ctx.tabStates.t1.table.sourceId = ''
    wrapper = mountPanel()
    expect(wrapper.find('[data-test="action-edit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-tip="请选择数据源后再操作"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('引擎配置编辑态：无分桶字段时分桶数不可改并说明原因', async () => {
    const ctx = buildCtx()
    Object.assign(ctx.tabStates.t1, { metaEditing: true, metaForm: { bucketNum: 8, replicaNum: 3 }, partitionList: [] })
    const mountPane = () =>
      shallowMount(DataStudioRightPanelDoris, {
        global: {
          provide: { dataStudioCtx: ctx },
          stubs: {
            ElScrollbar: { template: '<div><slot /></div>' },
            ElDescriptions: { template: '<div><slot /></div>' },
            ElDescriptionsItem: { props: ['label'], template: '<div :data-label="label"><slot /></div>' },
            ElInputNumber: { props: ['disabled', 'modelValue'], template: '<input :disabled="disabled" />' },
          },
          config: { warnHandler: () => {} },
          directives: { loading: {} },
        },
      })
    const bucketInput = (w) => w.find('[data-label="分桶数"] input')

    const replicaInput = (w) => w.find('[data-label="副本数"] input')

    // 无分桶字段：不渲染输入框（el-input-number 会把空值按 min 改写成 1，保存时被当成 ALTER）
    ctx.tabStates.t1.table = { ...ctx.tabStates.t1.table, bucketNum: 8, replicaNum: null }
    let wrapper = mountPane()
    expect(bucketInput(wrapper).exists()).toBe(false)
    expect(wrapper.text()).toContain('未设置分桶字段，无法修改分桶数')
    expect(replicaInput(wrapper).exists()).toBe(false)
    expect(wrapper.text()).toContain('未同步副本数，无法修改')
    expect(wrapper.text()).toContain('ALTER TABLE')
    wrapper.unmount()

    // 有分桶字段但分桶数未同步：同样只读
    ctx.tabStates.t1.table = { ...ctx.tabStates.t1.table, distributionColumn: 'id', bucketNum: null, replicaNum: 3 }
    wrapper = mountPane()
    expect(bucketInput(wrapper).exists()).toBe(false)
    expect(wrapper.text()).toContain('未同步分桶数，无法修改')
    expect(replicaInput(wrapper).exists()).toBe(true)
    wrapper.unmount()

    ctx.tabStates.t1.table = { ...ctx.tabStates.t1.table, bucketNum: 8 }
    wrapper = mountPane()
    expect(bucketInput(wrapper).exists()).toBe(true)
    expect(wrapper.text()).toContain('仅对之后新建的分区生效')
    wrapper.unmount()
  })

  it('头部展示库表名', () => {
    const ctx = buildCtx()
    const wrapper = shallowMount(DataStudioRightPanel, {
      global: {
        provide: { dataStudioCtx: ctx },
        stubs: {
          DataStudioRightPanelLineage: true,
          SmartMetadataDialog: true,
          ElScrollbar: { template: '<div><slot /></div>' },
        },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })
    expect(wrapper.find('.table-name').text()).toBe('demo_t')
    expect(wrapper.find('.table-name').attributes('title')).toBe('db.demo_t')
    expect(wrapper.find('.object-location').text()).toBe('测试连接 / db')
    wrapper.unmount()
  })

  it('Doris信息渲染后端返回的分区列表', async () => {
    listPartitionsMock.mockResolvedValueOnce([
      { partitionName: 'p20260101', range: '[20260101, 20260102)', dataSize: '1.234 GB', rowCount: 1234567, buckets: 10, replicationNum: 3, state: 'NORMAL' },
      { partitionName: 'p20260102', range: '[20260102, 20260103)', dataSize: '2.5 GB', rowCount: null, buckets: 10, replicationNum: 3, state: 'NORMAL' },
    ])
    const ctx = buildCtx()
    const wrapper = shallowMount(DataStudioRightPanelDoris, {
      global: {
        provide: { dataStudioCtx: ctx },
        stubs: { ElScrollbar: { template: '<div><slot /></div>' } },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })
    await flushPromises()

    expect(listPartitionsMock).toHaveBeenCalledWith(1, 's1', { skipErrorMessage: true })
    expect(wrapper.text()).toContain('共 2 个分区')
    wrapper.unmount()
  })

  it('分区列表请求失败时就地提示，不抛出', async () => {
    listPartitionsMock.mockRejectedValueOnce(new Error('获取分区列表失败: 不支持的表类型'))
    const ctx = buildCtx()
    const wrapper = shallowMount(DataStudioRightPanelDoris, {
      global: {
        provide: { dataStudioCtx: ctx },
        stubs: { ElScrollbar: { template: '<div><slot /></div>' } },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })
    await flushPromises()

    expect(wrapper.html()).toContain('不支持的表类型')
    wrapper.unmount()
  })

  it('分区列表结果缓存后不重复请求', async () => {
    listPartitionsMock.mockClear()
    listPartitionsMock.mockResolvedValueOnce([{ partitionName: 'p1', dataSize: '1 GB' }])
    const ctx = buildCtx()
    const mountPane = () =>
      shallowMount(DataStudioRightPanelDoris, {
        global: {
          provide: { dataStudioCtx: ctx },
          stubs: { ElScrollbar: { template: '<div><slot /></div>' } },
          config: { warnHandler: () => {} },
          directives: { loading: {} },
        },
      })

    let wrapper = mountPane()
    await flushPromises()
    expect(listPartitionsMock).toHaveBeenCalledTimes(1)
    wrapper.unmount()

    // 结果已缓存在 tab state 上，再次挂载不再发请求
    wrapper = mountPane()
    await flushPromises()
    expect(listPartitionsMock).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('共 1 个分区')
    wrapper.unmount()
  })

  it('A 表分区仍在加载时切到 B 表，B 照常加载且不被 A 的迟到结果或错误影响', async () => {
    let rejectA
    listPartitionsMock.mockReset()
    listPartitionsMock.mockImplementation((tableId) =>
      tableId === 1 && !rejectA
        ? new Promise((_, reject) => { rejectA = reject })
        : Promise.resolve([{ partitionName: 'p_b1' }, { partitionName: 'p_b2' }])
    )
    const ctx = buildCtx()
    ctx.tabStates.t2 = {
      ...ctx.tabStates.t1,
      table: { id: 2, tableName: 'demo_b', dbName: 'db', sourceId: 's1', sourceType: 'DORIS' },
    }
    const wrapper = shallowMount(DataStudioRightPanelDoris, {
      global: {
        provide: { dataStudioCtx: ctx },
        stubs: {
          ElScrollbar: { template: '<div><slot /></div>' },
          ElEmpty: { props: ['description'], template: '<div class="empty">{{ description }}</div>' },
        },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })
    await flushPromises()
    expect(listPartitionsMock).toHaveBeenCalledWith(1, 's1', { skipErrorMessage: true })

    ctx.activeTab.value = 't2'
    await flushPromises()
    expect(listPartitionsMock).toHaveBeenCalledWith(2, 's1', { skipErrorMessage: true })
    expect(wrapper.text()).toContain('共 2 个分区')

    rejectA(new Error('A 超时'))
    await flushPromises()
    expect(wrapper.text()).toContain('共 2 个分区')
    expect(wrapper.text()).not.toContain('A 超时')
    expect(ctx.tabStates.t2.partitionList).toHaveLength(2)

    // 回到 A：失败没有被缓存为空列表，自动重试
    ctx.activeTab.value = 't1'
    await flushPromises()
    expect(wrapper.text()).toContain('共 2 个分区')
    expect(listPartitionsMock.mock.calls.filter(([id]) => id === 1)).toHaveLength(2)
    wrapper.unmount()
    listPartitionsMock.mockReset()
    listPartitionsMock.mockImplementation(() => Promise.resolve([]))
  })

  it('renders the empty state for query tabs', () => {
    const ctx = buildCtx()
    ctx.openTabs.value = [{ id: 'q1', kind: 'query', tableName: '查询' }]
    ctx.activeTab.value = 'q1'
    const wrapper = shallowMount(DataStudioRightPanel, {
      global: {
        provide: { dataStudioCtx: ctx },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })
    expect(wrapper.find('.panel-shell').exists()).toBe(false)
    expect(wrapper.find('.right-empty').exists()).toBe(true)
    wrapper.unmount()
  })
  it('分区查询跟随对象所属连接，失败后重新打开会重试', async () => {
    listPartitionsMock.mockReset().mockRejectedValueOnce(new Error('network unavailable')).mockResolvedValue([{ partitionName: 'p1' }])
    const ctx = buildCtx()
    ctx.clusterId.value = 'query-source'
    ctx.tabStates.t1.table.sourceId = 'object-source'
    const mountPane = () => shallowMount(DataStudioRightPanelDoris, {
      global: {
        provide: { dataStudioCtx: ctx },
        stubs: { ElScrollbar: { template: '<div><slot /></div>' } },
        config: { warnHandler: () => {} }, directives: { loading: {} },
      },
    })
    let wrapper = mountPane()
    await flushPromises()
    expect(listPartitionsMock).toHaveBeenCalledWith(1, 'object-source', { skipErrorMessage: true })
    expect(ctx.tabStates.t1.partitionList).toBeUndefined()
    wrapper.unmount()
    wrapper = mountPane()
    await flushPromises()
    expect(listPartitionsMock).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('共 1 个分区')
    wrapper.unmount()
  })

})
