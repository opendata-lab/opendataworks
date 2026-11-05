import dayjs from 'dayjs'

export const ROLE_TEMPLATES = [
  {
    value: 'READONLY',
    label: '只读',
    actions: ['QUERY', 'SHOW_VIEW'],
    desc: '查询数据；按资源适用范围查看视图定义',
  },
  {
    value: 'READWRITE',
    label: '读写',
    actions: ['QUERY', 'WRITE', 'SHOW_VIEW'],
    desc: '查询及新增、更新、删除数据，不包含 DDL',
  },
  {
    value: 'ADMIN',
    label: '数据管理员',
    actions: ['QUERY', 'WRITE', 'CREATE', 'ALTER', 'DROP', 'SHOW_VIEW'],
    desc: '数据维护及底层可授予的对象管理，不包含 Grant 或超级用户',
  },
  { value: 'CUSTOM', label: '自定义', actions: [], desc: '空白角色，创建后自由选择资源与动作' },
]

export const DEFAULT_ACTIONS = [
  {
    key: 'QUERY',
    label: '查询数据',
    shortLabel: '查询',
    resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'],
    nativeActions: ['SELECT'],
  },
  {
    key: 'WRITE',
    label: '写入数据',
    shortLabel: '写入',
    resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'],
    nativeActions: ['INSERT', 'UPDATE', 'DELETE'],
  },
  {
    key: 'CREATE',
    label: '创建对象',
    shortLabel: '创建对象',
    resourceTypes: ['SCHEMA'],
    nativeActions: ['CREATE'],
  },
  {
    key: 'ALTER',
    label: '修改结构',
    shortLabel: '修改结构',
    resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'],
    nativeActions: ['ALTER'],
  },
  {
    key: 'DROP',
    label: '删除对象',
    shortLabel: '删除对象',
    resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'],
    nativeActions: ['DROP'],
  },
  {
    key: 'SHOW_VIEW',
    label: '查看视图定义',
    shortLabel: '查看定义',
    resourceTypes: ['SCHEMA', 'VIEW'],
    nativeActions: ['SHOW VIEW'],
  },
]

export const READONLY_ACTION_KEYS = new Set([
  'QUERY',
  'SHOW_VIEW',
  'DEFINITION',
  'ACCESS',
  'SELECT',
  'Select_priv',
  'USAGE',
  'CONNECT',
  'SHOW VIEW',
  'Show_view_priv',
])

export const formatDateTime = (val) => {
  if (!val) return '-'
  return dayjs(val).format('YYYY-MM-DD HH:mm:ss')
}

export const getTemplateLabel = (tpl) => {
  const item = ROLE_TEMPLATES.find((t) => t.value === String(tpl).toUpperCase())
  return item ? item.label : tpl
}

export const SUB_DML_KEYS = new Set(['INSERT', 'UPDATE', 'DELETE'])

export const getOperationStateMeta = (state) => {
  const s = String(state || '').toUpperCase()
  if (s === 'SUCCESS' || s === 'SUCCEEDED') return { label: '已生效', tagType: 'success' }
  if (s === 'PARTIAL') return { label: '部分成功', tagType: 'warning' }
  if (s === 'FAILED') return { label: '失败', tagType: 'danger' }
  if (s === 'UNKNOWN') return { label: '待核对', tagType: 'warning' }
  return { label: s || '未知', tagType: 'info' }
}

export const getOperationDialogTitle = (op) => {
  const s = String(op?.state || '').toUpperCase()
  if (s === 'SUCCESS') return '授权已生效'
  if (s === 'PARTIAL') return '部分成功，请核对操作结果'
  if (s === 'FAILED') return '授权执行失败'
  return '授权执行状态待核对'
}
