import { ref, reactive, computed } from 'vue'
import { DEFAULT_ACTIONS, SUB_DML_KEYS, READONLY_ACTION_KEYS } from './permissionLabels'
import { permissionsApi } from '@/api/permissions'

export function usePermissionMatrix({
  captureRequest,
  overview,
  isPostgresEngine,
  activeSourceId,
}) {
  const selectedRoleId = ref(null)

  const roleSearchKeyword = ref('')

  const currentResourceType = ref('SCHEMA')

  const selectedDatabase = ref('')

  const resourceSearch = ref('')
  const onlyGranted = ref(false)

  const capabilityNoteVisible = ref(false)

  const resourcesState = reactive({
    loading: false,
    error: '',
    databases: [],
    objects: [],
  })

  const roleDraftMap = ref({})

  const refineDialogVisible = ref(false)

  const refineTarget = ref(null)

  let resourceLoadSeq = 0

  const filteredRoles = computed(() => {
    const kw = String(roleSearchKeyword.value ?? '')
      .trim()
      .toLowerCase()
    if (!kw) return overview.roles
    return overview.roles.filter((r) =>
      [r.name, r.nativeName].filter(Boolean).join(' ').toLowerCase().includes(kw)
    )
  })

  const selectedRole = computed(
    () => overview.roles.find((r) => String(r.id) === String(selectedRoleId.value)) || null
  )

  const availableResourceTypes = computed(() => {
    const rts = overview.capabilities?.resourceTypes
    return Array.isArray(rts) && rts.length ? rts : ['SCHEMA', 'TABLE', 'VIEW']
  })

  const normalizedCapabilityActions = computed(() => {
    const raw = overview.capabilities?.actions
    const list = Array.isArray(raw) && raw.length ? raw : DEFAULT_ACTIONS
    return list.map((a) => ({
      ...a,
      shortLabel:
        a.label === '查询数据'
          ? '查询'
          : a.label === '写入数据'
            ? '写入'
            : a.label === '查看视图定义'
              ? '查看定义'
              : a.label || a.key,
    }))
  })

  const matrixActionColumns = computed(() => {
    const hasCompositeWrite = normalizedCapabilityActions.value.some((a) => a.key === 'WRITE')
    return normalizedCapabilityActions.value.filter(
      (col) =>
        !(hasCompositeWrite && SUB_DML_KEYS.has(col.key)) &&
        (!col.resourceTypes ||
          col.resourceTypes.includes(currentResourceType.value) ||
          Boolean(col.note || col.reason))
    )
  })

  const isActionSupported = (col, resourceType = currentResourceType.value) => {
    if (col.supported === false) return false
    if (Array.isArray(col.resourceTypes) && col.resourceTypes.length > 0) {
      return col.resourceTypes.includes(resourceType)
    }
    return true
  }

  const getUnsupportedBadge = (col) => {
    const note = String(col.note || col.reason || '')
    if (note.includes('所有权') || col.mode === 'OWNER') return '所有者权限'
    if (note.includes('可见') || col.mode === 'VISIBLE') return '随资源可见'
    return '—'
  }

  const isSelectedRoleReadonlyLocked = computed(
    () =>
      String(selectedRole.value?.template || '').toUpperCase() === 'READONLY'
  )

  const isActionDisabledForRole = (col) => {
    if (!isSelectedRoleReadonlyLocked.value) return false
    return !READONLY_ACTION_KEYS.has(col.key)
  }

  const getCellTooltip = (col) => {
    if (isActionDisabledForRole(col)) {
      return '只读模板角色仅允许查询与查看视图定义'
    }
    const parts = []
    if (col.nativeActions?.length) parts.push(`原生权限: ${col.nativeActions.join(', ')}`)
    if (col.note) parts.push(col.note)
    return parts.join(' · ')
  }

  const currentScopeNote = computed(() => {
    const notes = []
    if (overview.capabilities?.note) {
      notes.push(overview.capabilities.note)
    }
    for (const a of matrixActionColumns.value) {
      if ((!a.resourceTypes || a.resourceTypes.includes(currentResourceType.value)) && a.note) {
        notes.push(`${a.label || a.key}：${a.note}`)
      }
    }
    if (notes.length) return [...new Set(notes)].join('；')
    if (currentResourceType.value === 'SCHEMA') {
      return isPostgresEngine.value
        ? 'PostgreSQL Schema 权限与子表读写权限独立控制，不会自动授予未来新建对象。'
        : '数据库级授权覆盖该库下当前与后续新增对象。'
    }
    return ''
  })

  const makeGrantKey = (resourceType, database, tableName = '') =>
    `${resourceType}|${database || ''}|${resourceType === 'SCHEMA' ? '' : tableName || ''}`

  const currentResourceRows = computed(() => {
    const rt = currentResourceType.value
    const kw = String(resourceSearch.value ?? '')
      .trim()
      .toLowerCase()
    const db = selectedDatabase.value
    let rows = []

    if (rt === 'SCHEMA') {
      const schemaObjs = (resourcesState.objects || []).filter((o) => o.resourceType === 'SCHEMA')
      const dbs = resourcesState.databases?.length
        ? resourcesState.databases
        : schemaObjs.map((o) => o.database).filter(Boolean)
      const filteredDbs = db ? dbs.filter((d) => d === db) : dbs
      rows = filteredDbs.map((d) => ({
        rowKey: makeGrantKey('SCHEMA', d, ''),
        resourceType: 'SCHEMA',
        database: d,
        tableName: '',
        displayName: d,
        subLabel: isPostgresEngine.value ? `SCHEMA "${d}"` : `${d}.*`,
      }))
    } else {
      const objs = (resourcesState.objects || []).filter(
        (o) => o.resourceType === rt && (!db || o.database === db)
      )
      rows = objs.map((o) => ({
        rowKey: makeGrantKey(rt, o.database, o.tableName),
        resourceType: rt,
        database: o.database,
        tableName: o.tableName,
        displayName: o.tableName,
        subLabel: o.comment
          ? `${o.database}.${o.tableName} · ${o.comment}`
          : `${o.database}.${o.tableName}`,
      }))
    }

    if (onlyGranted.value) {
      rows = grantRows.value.filter((row) => row.resourceType === rt)
    }

    if (!kw) return rows
    return rows.filter((r) => `${r.displayName} ${r.subLabel}`.toLowerCase().includes(kw))
  })

  const buildGrantsMapFromRole = (role) => {
    const map = {}
    for (const g of role?.grants || []) {
      const key = makeGrantKey(g.resourceType, g.database, g.tableName)
      map[key] = [...new Set(g.actions || [])]
    }
    return map
  }

  const baseGrantMap = computed(() => buildGrantsMapFromRole(selectedRole.value))
  const grantRows = computed(() => {
    const keys = new Set([...Object.keys(baseGrantMap.value), ...Object.keys(roleDraftMap.value)])
    return [...keys].filter((key) =>
      baseGrantMap.value[key]?.length || roleDraftMap.value[key]?.length
    ).sort().map((key) => {
      const [resourceType, database, tableName] = key.split('|')
      // Compare WRITE as INSERT/UPDATE/DELETE so refining it counts only the real sub-action change.
      const expandWrite = (list) =>
        [...new Set(list.flatMap((a) => (a === 'WRITE' ? [...SUB_DML_KEYS] : [a])))]
      const before = expandWrite(baseGrantMap.value[key] || [])
      const after = expandWrite(roleDraftMap.value[key] || [])
      return {
        rowKey: key, resourceType, database, tableName,
        displayName: tableName || database,
        subLabel: tableName ? `${database}.${tableName}` : `${database}.*`,
        granted: before.length > 0,
        changed: [...new Set([...before, ...after])].some((a) => before.includes(a) !== after.includes(a)),
        added: after.filter((a) => !before.includes(a)).length,
        removed: before.filter((a) => !after.includes(a)).length,
      }
    })
  })
  const grantCounts = (resourceType, database) => {
    const rows = grantRows.value.filter((row) =>
      (!resourceType || row.resourceType === resourceType) && (!database || row.database === database))
    return { granted: rows.filter((r) => r.granted).length, changed: rows.filter((r) => r.changed).length }
  }
  const databaseOptions = computed(() => [...new Set([
    ...resourcesState.databases, ...grantRows.value.map((row) => row.database),
  ])].sort())
  const draftChangeCounts = computed(() => grantRows.value.reduce((counts, row) => ({
    added: counts.added + row.added, removed: counts.removed + row.removed,
  }), { added: 0, removed: 0 }))

  function syncDraftFromSelectedRole() {
    roleDraftMap.value = buildGrantsMapFromRole(selectedRole.value)
  }

  const getRowDraftActions = (row) => roleDraftMap.value[row.rowKey] || []

  const getRowBaseActions = (row) => {
    const baseMap = buildGrantsMapFromRole(selectedRole.value)
    return baseMap[row.rowKey] || []
  }

  const isActionChecked = (row, col) => {
    const acts = getRowDraftActions(row)
    if (acts.includes(col.key)) return true
    if (Array.isArray(col.nativeActions) && col.nativeActions.length > 0) {
      return col.nativeActions.every((na) => acts.includes(na))
    }
    return false
  }

  const isActionIndeterminate = (row, col) => {
    const acts = getRowDraftActions(row)
    if (acts.includes(col.key)) return false
    if (Array.isArray(col.nativeActions) && col.nativeActions.length > 1) {
      const count = col.nativeActions.filter((na) => acts.includes(na)).length
      return count > 0 && count < col.nativeActions.length
    }
    return false
  }

  const isCellChanged = (row, col) => {
    const draftActs = getRowDraftActions(row)
    const baseActs = getRowBaseActions(row)
    const keysToCompare = [col.key, ...(col.nativeActions || [])]
    return keysToCompare.some((k) => draftActs.includes(k) !== baseActs.includes(k))
  }

  function toggleMatrixAction(row, col, checked) {
    if (!isActionSupported(col, row.resourceType) || isActionDisabledForRole(col)) return
    const current = new Set(getRowDraftActions(row))
    const subActions = col.nativeActions || []
    if (checked) {
      subActions.forEach((na) => current.delete(na))
      current.add(col.key)
    } else {
      current.delete(col.key)
      subActions.forEach((na) => current.delete(na))
    }
    roleDraftMap.value = {
      ...roleDraftMap.value,
      [row.rowKey]: [...current],
    }
  }

  const getRefinableColumns = (row) =>
    matrixActionColumns.value.filter(
      (col) =>
        isActionSupported(col, row.resourceType) &&
        Array.isArray(col.nativeActions) &&
        col.nativeActions.length > 1
    )

  const hasRefinableActions = (row) => getRefinableColumns(row).length > 0

  const isNativeSubActionChecked = (row, col, nativeAct) => {
    const acts = getRowDraftActions(row)
    return acts.includes(col.key) || acts.includes(nativeAct)
  }

  function toggleNativeSubAction(row, col, nativeAct, checked) {
    if (isActionDisabledForRole(col)) return
    const current = new Set(getRowDraftActions(row))
    const subActions = col.nativeActions || []
    if (current.has(col.key)) {
      current.delete(col.key)
      subActions.forEach((na) => current.add(na))
    }
    if (checked) {
      current.add(nativeAct)
    } else {
      current.delete(nativeAct)
    }
    if (subActions.length > 0 && subActions.every((na) => current.has(na))) {
      subActions.forEach((na) => current.delete(na))
      current.add(col.key)
    }
    roleDraftMap.value = {
      ...roleDraftMap.value,
      [row.rowKey]: [...current],
    }
  }

  function openRefineDialog(row) {
    refineTarget.value = row
    refineDialogVisible.value = true
  }

  function batchSelectTemplate() {
    const template = selectedRole.value?.template
    const keys = template === 'ADMIN' ? null
      : template === 'READWRITE' ? ['QUERY', 'WRITE'] : ['QUERY', 'SHOW_VIEW']
    for (const row of currentResourceRows.value) {
      for (const col of matrixActionColumns.value) {
        if (!keys || keys.includes(col.key)) toggleMatrixAction(row, col, true)
      }
    }
  }

  function resetRoleDraft() {
    syncDraftFromSelectedRole()
  }

  const serializeGrantMap = (map) => {
    const entries = Object.entries(map || {})
      .map(([k, acts]) => [k, [...new Set(acts || [])].sort().join(',')])
      .filter(([, v]) => v.length > 0)
      .sort(([a], [b]) => a.localeCompare(b))
    return JSON.stringify(entries)
  }

  const hasDraftChanges = computed(() => {
    if (!selectedRole.value) return false
    if (String(selectedRole.value.status || '').toUpperCase() === 'DRAFT') return true
    const baseMap = buildGrantsMapFromRole(selectedRole.value)
    return serializeGrantMap(roleDraftMap.value) !== serializeGrantMap(baseMap)
  })

  const buildDraftGrantsPayload = () => {
    const list = []
    for (const [key, actions] of Object.entries(roleDraftMap.value)) {
      const cleanActions = [...new Set(actions || [])].filter(Boolean)
      if (!cleanActions.length) continue
      const [resourceType, database, tableName] = key.split('|')
      list.push({
        resourceType,
        database,
        tableName: resourceType === 'SCHEMA' ? '' : tableName || '',
        actions: cleanActions,
      })
    }
    return list
  }

  async function loadResources(
    database = selectedDatabase.value,
    targetSourceId = activeSourceId.value
  ) {
    const request = captureRequest()
    if (!targetSourceId) return
    const seq = ++resourceLoadSeq
    resourcesState.loading = true
    resourcesState.error = ''
    try {
      const res = await permissionsApi.listResources(targetSourceId, database ? { database } : {})
      if (!request.isCurrent()) return
      if (seq !== resourceLoadSeq || String(request.sourceId) !== String(targetSourceId)) {
        return
      }
      const dbs = Array.isArray(res?.databases) ? res.databases : []
      resourcesState.databases = dbs
      resourcesState.objects = Array.isArray(res?.objects) ? res.objects : []
      if (database) selectedDatabase.value = database
      else if (dbs.length > 0) {
        const preferred = dbs.includes(selectedDatabase.value) ? selectedDatabase.value : dbs[0]
        selectedDatabase.value = preferred
        await loadResources(preferred, targetSourceId)
        if (!request.isCurrent()) return
      }
    } catch (error) {
      if (!request.isCurrent()) return
      if (seq !== resourceLoadSeq || String(request.sourceId) !== String(targetSourceId)) {
        return
      }
      resourcesState.error = error?.message || '盘点数据源库表资源失败'
    } finally {
      if (request.isCurrent()) {
        if (seq === resourceLoadSeq && String(request.sourceId) === String(targetSourceId)) {
          resourcesState.loading = false
        }
      }
    }
  }

  function selectRole(roleId) {
    selectedRoleId.value = roleId
    syncDraftFromSelectedRole()
    // A role without saved grants has nothing to show under "only granted".
    if (!selectedRole.value?.grants?.length) onlyGranted.value = false
    const firstGrant = selectedRole.value?.grants?.[0]
    if (firstGrant) currentResourceType.value = firstGrant.resourceType
    loadResources(firstGrant?.database || selectedDatabase.value, activeSourceId.value)
  }

  function switchResourceType(rt) {
    currentResourceType.value = rt
    resourceSearch.value = ''
  }

  function handleDatabaseChange(db) {
    selectedDatabase.value = db
    loadResources(db, activeSourceId.value)
  }

  function reset() {
    resourceLoadSeq += 1
    selectedRoleId.value = null
    roleSearchKeyword.value = ''
    currentResourceType.value = 'SCHEMA'
    selectedDatabase.value = ''
    resourceSearch.value = ''
    onlyGranted.value = false
    capabilityNoteVisible.value = false
    roleDraftMap.value = {}
    refineDialogVisible.value = false
    refineTarget.value = null
    Object.assign(resourcesState, { loading: false, error: '', databases: [], objects: [] })
  }

  return {
    reset,
    selectedRoleId,
    roleSearchKeyword,
    currentResourceType,
    selectedDatabase,
    resourceSearch,
    capabilityNoteVisible,
    resourcesState,
    roleDraftMap,
    refineDialogVisible,
    refineTarget,
    filteredRoles,
    selectedRole,
    availableResourceTypes,
    normalizedCapabilityActions,
    matrixActionColumns,
    isActionSupported,
    getUnsupportedBadge,
    isSelectedRoleReadonlyLocked,
    isActionDisabledForRole,
    getCellTooltip,
    currentScopeNote,
    makeGrantKey,
    currentResourceRows,
    buildGrantsMapFromRole,
    syncDraftFromSelectedRole,
    getRowDraftActions,
    getRowBaseActions,
    isActionChecked,
    isActionIndeterminate,
    isCellChanged,
    toggleMatrixAction,
    getRefinableColumns,
    hasRefinableActions,
    isNativeSubActionChecked,
    toggleNativeSubAction,
    openRefineDialog,
    batchSelectTemplate,
    onlyGranted,
    grantCounts,
    databaseOptions,
    draftChangeCounts,
    resetRoleDraft,
    serializeGrantMap,
    hasDraftChanges,
    buildDraftGrantsPayload,
    loadResources,
    selectRole,
    switchResourceType,
    handleDatabaseChange,
  }
}
