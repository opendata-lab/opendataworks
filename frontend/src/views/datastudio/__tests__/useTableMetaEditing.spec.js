import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive, ref } from 'vue'

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn(() => Promise.resolve()) },
}))
vi.mock('@/api/domain', () => ({ businessDomainApi: {}, dataDomainApi: {} }))
vi.mock('@/api/table', () => ({ tableApi: { update: vi.fn(), getById: vi.fn(), createField: vi.fn(), updateField: vi.fn(), deleteField: vi.fn(), getFields: vi.fn() } }))
vi.mock('@/demo/runtime', () => ({ isDemoMode: false, showDemoReadonlyMessage: vi.fn() }))

import { ElMessage, ElMessageBox } from 'element-plus'
import { tableApi } from '@/api/table'
import { useTableMetaEditing } from '../composables/useTableMetaEditing'

function setup(table = { tableModel: 'UNIQUE' }, fields = [{ id: 1, fieldName: 'a', fieldType: 'int' }]) {
  const tabStates = reactive({
    t1: { table, fields, fieldsDraft: [], fieldsRemoved: [], fieldsEditing: false },
  })
  const api = useTableMetaEditing({
    clusterId: ref(null),
    tabStates,
    openTabs: ref([]),
    activeTab: ref('t1'),
    selectedTableKey: ref(''),
    tableRefs: ref({}),
    tableStore: reactive({}),
    getTableKey: () => 't1',
    isDorisTable: () => false,
    isAggregateTable: (t) => String(t?.tableModel || '').toUpperCase() === 'AGGREGATE',
    warnPlatformMetadataMissing: () => false,
  })
  return { tabStates, api }
}

describe('useTableMetaEditing (field draft ops)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getFieldRows returns committed fields, or the draft while editing', () => {
    const { tabStates, api } = setup()
    expect(api.getFieldRows('t1')).toBe(tabStates.t1.fields)
    tabStates.t1.fieldsEditing = true
    expect(api.getFieldRows('t1')).toBe(tabStates.t1.fieldsDraft)
    expect(api.getFieldRows('missing')).toEqual([])
  })

  it('startFieldsEdit clones fields into a draft and resets removed', () => {
    const { tabStates, api } = setup()
    api.startFieldsEdit('t1')
    expect(tabStates.t1.fieldsEditing).toBe(true)
    expect(tabStates.t1.fieldsDraft).toEqual(tabStates.t1.fields)
    expect(tabStates.t1.fieldsDraft).not.toBe(tabStates.t1.fields) // deep clone
    expect(tabStates.t1.fieldsRemoved).toEqual([])
  })

  it('addField prepends a blank row; removeField drops it and records persisted id', () => {
    const { tabStates, api } = setup()
    api.startFieldsEdit('t1')
    api.addField('t1')
    expect(tabStates.t1.fieldsDraft[0]).toMatchObject({ id: null, fieldName: '', fieldType: '' })
    const persisted = tabStates.t1.fieldsDraft.find((r) => r.id === 1)
    api.removeField('t1', persisted)
    expect(tabStates.t1.fieldsDraft.includes(persisted)).toBe(false)
    expect(tabStates.t1.fieldsRemoved).toContain(1)
  })

  it('aggregate tables block add/remove with a warning', () => {
    const { tabStates, api } = setup({ tableModel: 'AGGREGATE' })
    api.startFieldsEdit('t1')
    const before = tabStates.t1.fieldsDraft.length
    api.addField('t1')
    expect(tabStates.t1.fieldsDraft.length).toBe(before)
    expect(ElMessage.warning).toHaveBeenCalled()
  })

  it('cancelFieldsEdit clears editing state and drafts', () => {
    const { tabStates, api } = setup()
    api.startFieldsEdit('t1')
    api.addField('t1')
    api.cancelFieldsEdit('t1')
    expect(tabStates.t1.fieldsEditing).toBe(false)
    expect(tabStates.t1.fieldsDraft).toEqual([])
    expect(tabStates.t1.fieldsRemoved).toEqual([])
  })
})

describe('useTableMetaEditing (engine tab save)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('从引擎配置页保存且缺少分层时提示去概览补齐，不提交', async () => {
    const { tabStates, api } = setup({ id: 9, tableModel: 'DUPLICATE' })
    Object.assign(tabStates.t1, { metaTab: 'doris', metaEditing: true, metaForm: { layer: '', bucketNum: 8, replicaNum: 3 } })
    await api.saveMetaEdit('t1')
    expect(ElMessage.warning).toHaveBeenCalledWith('请先在概览中选择数据分层')
    expect(tabStates.t1.metaEditing).toBe(true)
  })
})


it('保存对象元数据锁定对象连接，确认期间切换查询连接不影响保存目标', async () => {
  let confirm
  ElMessageBox.confirm.mockReturnValueOnce(new Promise(resolve => { confirm = resolve }))
  tableApi.update.mockResolvedValue({ id: 9, tableName: 't', sourceId: 'object-source', layer: 'ODS' })
  tableApi.getById.mockResolvedValue({ id: 9, tableName: 't', sourceId: 'object-source', dbName: 'db', layer: 'ODS' })
  const clusterId = ref('query-source')
  const tabStates = reactive({ t1: { table: { id: 9, sourceId: 'object-source', dbName: 'db' }, metaForm: { tableName: 't', layer: 'ODS' } } })
  const api = useTableMetaEditing({ clusterId, tabStates, openTabs: ref([]), activeTab: ref('t1'), selectedTableKey: ref(''), tableRefs: ref({}), tableStore: {}, getTableKey: () => 't1', isDorisTable: () => true, warnPlatformMetadataMissing: () => false })
  const saving = api.saveMetaEdit('t1')
  clusterId.value = 'other-query-source'
  confirm()
  await saving
  expect(tableApi.update).toHaveBeenCalledWith(9, expect.anything(), 'object-source')
})

it('字段新增、更新、删除始终使用对象的 clusterId，而非查询连接', async () => {
  tableApi.getFields.mockResolvedValue([])
  const { tabStates, api } = setup({ id: 9, clusterId: 'object-source', tableModel: 'UNIQUE' })
  const original = tabStates.t1.fields[0]
  tabStates.t1.fieldsDraft = [{ ...original, fieldComment: 'updated' }, { fieldName: 'b', fieldType: 'int' }]
  tabStates.t1.fieldsRemoved = [2]
  await api.saveFieldsEdit('t1')
  expect(tableApi.createField).toHaveBeenCalledWith(9, expect.anything(), 'object-source')
  expect(tableApi.updateField).toHaveBeenCalledWith(9, 1, expect.anything(), 'object-source')
  expect(tableApi.deleteField).toHaveBeenCalledWith(9, 2, 'object-source')
})


it('保存返回部分实体时重新读取完整对象，保留数据库、物理同步状态与数据规模', async () => {
  const stored = { id: 9, clusterId: 3, dbName: 'db', tableName: 't', layer: 'ODS', isSynced: 1, rowCount: 3, storageSize: 16384, tableComment: 'updated' }
  tableApi.update.mockResolvedValue({ id: 9, tableComment: 'updated', dbName: null, clusterId: null, isSynced: null, rowCount: null, storageSize: null })
  tableApi.getById.mockResolvedValue(stored)
  const { tabStates, api } = setup({ ...stored, tableComment: 'old' })
  Object.assign(tabStates.t1, { metaEditing: true, metaForm: { tableName: 't', layer: 'ODS', tableComment: 'updated' } })
  await api.saveMetaEdit('t1')
  expect(tableApi.getById).toHaveBeenCalledWith(9)
  expect(tabStates.t1.table).toMatchObject(stored)
  expect(tabStates.t1.metaEditing).toBe(false)
})

describe('MySQL primary key save guard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('rejects programmatic primary changes on existing physical columns before confirmation/API calls', async () => {
    const { tabStates, api } = setup({ id: 9, clusterId: 3, sourceType: 'MYSQL', isSynced: 1 })
    api.startFieldsEdit('t1')
    tabStates.t1.fieldsDraft[0].isPrimary = 1
    await api.saveFieldsEdit('t1')
    expect(ElMessage.warning).toHaveBeenCalledWith('MySQL 暂不支持在线修改已有字段的主键')
    expect(ElMessageBox.confirm).not.toHaveBeenCalled()
    expect(tableApi.updateField).not.toHaveBeenCalled()
    expect(tabStates.t1.fieldsEditing).toBe(true)
  })

  it('allows a primary key on a new physical column and edits to a platform draft', async () => {
    tableApi.getFields.mockResolvedValue([])
    const { tabStates, api } = setup({ id: 9, clusterId: 3, sourceType: 'MYSQL', isSynced: 1 })
    api.startFieldsEdit('t1')
    tabStates.t1.fieldsDraft.push({ fieldName: 'b', fieldType: 'int', isPrimary: 1 })
    await api.saveFieldsEdit('t1')
    expect(tableApi.createField).toHaveBeenCalledWith(9, expect.objectContaining({ isPrimary: 1 }), 3)
    const draft = setup({ id: 10, clusterId: 3, sourceType: 'MYSQL', isSynced: 0 })
    draft.api.startFieldsEdit('t1')
    draft.tabStates.t1.fieldsDraft[0].isPrimary = 1
    await draft.api.saveFieldsEdit('t1')
    expect(tableApi.updateField).toHaveBeenCalledWith(10, 1, expect.objectContaining({ isPrimary: 1 }), 3)
  })
})

it('comment-only field edits preserve null defaults and leave untouched fields unsubmitted', async () => {
  vi.clearAllMocks()
  tableApi.getFields.mockResolvedValue([])
  const { tabStates, api } = setup({ id: 9, clusterId: 3, sourceType: 'MYSQL', isSynced: 1 }, [
    { id: 1, fieldName: 'a', fieldType: 'int', fieldComment: 'old', defaultValue: null },
    { id: 2, fieldName: 'b', fieldType: 'int', fieldComment: 'untouched', defaultValue: null },
  ])
  api.startFieldsEdit('t1')
  tabStates.t1.fieldsDraft[0].fieldComment = 'new'
  await api.saveFieldsEdit('t1')
  expect(tableApi.updateField).toHaveBeenCalledTimes(1)
  expect(tableApi.updateField).toHaveBeenCalledWith(9, 1, expect.objectContaining({ fieldComment: 'new', defaultValue: null }), 3)
})
