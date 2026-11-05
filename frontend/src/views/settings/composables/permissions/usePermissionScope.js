import { ref, reactive, computed } from 'vue'
import { DEFAULT_ACTIONS, memberStatus, summarizeGrants } from './permissionLabels'

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

  // USER and SERVICE subjects, and operators, are all sys_user ids.
  const getPlatformUserName = (id) => {
    if (id === null || id === undefined || id === '') return ''
    const u = platformUsers.value.find((item) => String(item.id) === String(id))
    return u ? u.nickname || u.username : String(id)
  }

  const getMemberDisplayName = (member) => {
    if (!member) return ''
    return member.name || getPlatformUserName(member.subjectId)
  }

  const getMemberStatus = memberStatus


  const summarizeRoleGrants = (role) => summarizeGrants(role, overview.capabilities?.actions)


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
    getPlatformUserName,
    getMemberStatus,
    summarizeRoleGrants,
    actionLabel,
    getResourceTypeLabel,
  }
}
