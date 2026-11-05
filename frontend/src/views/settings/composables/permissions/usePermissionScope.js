import { ref, reactive, computed } from 'vue'
import { getTemplateLabel, DEFAULT_ACTIONS } from './permissionLabels'

export function usePermissionScope({ props }) {
  const activeSourceId = ref(props.cluster?.id ?? null)

  let requestEpoch = 0

  // A source can be closed and reopened while a previous request is still pending.
  function captureRequest() {
    const epoch = requestEpoch
    const sourceId = activeSourceId.value
    return {
      sourceId,
      isCurrent: () =>
        props.modelValue &&
        epoch === requestEpoch &&
        String(sourceId) === String(activeSourceId.value),
    }
  }

  function invalidateRequests() {
    requestEpoch += 1
  }

  function clearOverview() {
    Object.assign(overview, {
      sourceId: null,
      engine: '',
      roles: [],
      members: [],
      management: { username: '', configured: false },
      capabilities: { resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'], actions: [] },
    })
    platformUsers.value = []
    loading.value = false
    loadError.value = ''
  }

  const loading = ref(false)

  const loadError = ref('')

  const overview = reactive({
    sourceId: null,
    engine: '',
    roles: [],
    members: [],
    management: { username: '', configured: false },
    capabilities: { resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'], actions: [] },
  })

  const platformUsers = ref([])

  const activeClusterObj = computed(() => {
    if (props.clusters?.length && activeSourceId.value != null) {
      const found = props.clusters.find((c) => String(c.id) === String(activeSourceId.value))
      if (found) return found
    }
    return props.cluster
  })

  const activeClusterName = computed(() => activeClusterObj.value?.clusterName || '')

  const currentEngine = computed(() => overview.engine || activeClusterObj.value?.sourceType || '')

  const isPostgresEngine = computed(() =>
    String(currentEngine.value).toUpperCase().includes('POSTGRES')
  )

  const getRoleName = (roleId) => {
    const found = overview.roles.find((r) => String(r.id) === String(roleId))
    return found ? found.name : String(roleId)
  }

  const countRoleMembers = (roleId) => {
    if (!roleId) return 0
    return overview.members.filter((m) =>
      (m.roleIds || []).some((id) => String(id) === String(roleId))
    ).length
  }

  const getMemberDisplayName = (member) => {
    if (!member) return ''
    if (member.name) return member.name
    if (member.subjectType === 'USER') {
      const u = platformUsers.value.find((item) => String(item.id) === String(member.subjectId))
      if (u) return u.nickname || u.username
    }
    return String(member.subjectId || '')
  }

  const getMemberStatus = (member) => {
    if (!member) return { key: 'pending', label: '待配置', tagType: 'warning' }
    const upperStatus = String(member.status || '').toUpperCase()
    if (member.enabled === false || upperStatus === 'DISABLED') {
      return { key: 'disabled', label: '已停用', tagType: 'danger' }
    }
    if (upperStatus === 'APPLYING') return { key: 'pending', label: '执行中', tagType: 'warning' }
    if (upperStatus === 'ERROR') {
      return { key: 'pending', label: '状态异常', tagType: 'danger' }
    }
    const hasRoles = Array.isArray(member.roleIds) && member.roleIds.length > 0
    const hasAccount = Boolean(member.account?.configured && member.account?.username)
    if (hasRoles && hasAccount && upperStatus === 'ACTIVE') {
      return { key: 'ready', label: '正常', tagType: 'success' }
    }
    if (!hasRoles) {
      return { key: 'pending', label: '待分配角色', tagType: 'warning' }
    }
    return { key: 'pending', label: '待绑定账号', tagType: 'warning' }
  }

  const summarizeRoleGrants = (role) => {
    if (!role) return ''
    const grants = role.grants || []
    if (!grants.length) {
      return role.template
        ? `${getTemplateLabel(role.template)}模板 · 待配置资源`
        : '尚未配置资源权限'
    }
    const byDb = new Map()
    for (const g of grants) {
      const db = g.database || '*'
      if (!byDb.has(db)) byDb.set(db, new Set())
      for (const a of g.actions || []) byDb.get(db).add(a)
    }
    return [...byDb.entries()]
      .map(([db, acts]) => `${db} (${[...acts].map(actionLabel).join('、')})`)
      .join('；')
  }

  const actionLabel = (key) =>
    (overview.capabilities?.actions || DEFAULT_ACTIONS).find((action) => action.key === key)
      ?.label || key

  const getResourceTypeLabel = (rt) => {
    if (rt === 'SCHEMA')
      return overview.capabilities?.schemaLabel || (isPostgresEngine.value ? 'Schema' : '数据库')
    if (rt === 'TABLE') return '表'
    if (rt === 'VIEW') return '视图'
    return rt
  }

  return {
    captureRequest,
    invalidateRequests,
    clearOverview,
    activeSourceId,
    loading,
    loadError,
    overview,
    platformUsers,
    activeClusterObj,
    activeClusterName,
    currentEngine,
    isPostgresEngine,
    getRoleName,
    countRoleMembers,
    getMemberDisplayName,
    getMemberStatus,
    summarizeRoleGrants,
    actionLabel,
    getResourceTypeLabel,
  }
}
