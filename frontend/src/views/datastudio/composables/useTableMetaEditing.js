import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { businessDomainApi, dataDomainApi } from '@/api/domain'
import { tableApi } from '@/api/table'
import { getObjectSourceId } from '../objectSource'
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'
import { buildFieldPayload, isFieldChanged, isOnlyCommentChanged, isMysqlPrimaryKeyLocked } from '../fieldEdit'

export function useTableMetaEditing({
  clusterId,
  tabStates,
  openTabs,
  activeTab,
  selectedTableKey,
  tableRefs,
  tableStore,
  getTableKey,
  isDorisTable,
  getTableSourceType,
  isAggregateTable,
  warnPlatformMetadataMissing,
}) {
  const businessDomainOptions = ref([])

  const loadBusinessDomains = async () => {
    try {
      const options = await businessDomainApi.list()
      businessDomainOptions.value = Array.isArray(options) ? options : []
    } catch (error) {
      businessDomainOptions.value = []
      console.error('加载业务域失败', error)
    }
  }

  const loadMetaDataDomainOptions = async (tabId, businessDomain) => {
    const state = tabStates[tabId]
    if (!state) return
    if (!businessDomain) {
      state.metaDataDomainOptions = []
      return
    }
    try {
      const options = await dataDomainApi.list({ businessDomain })
      state.metaDataDomainOptions = Array.isArray(options) ? options : []
    } catch (error) {
      state.metaDataDomainOptions = []
      console.error('加载数据域失败', error)
    }
  }

  const getMetaDataDomainOptions = (tabId) => {
    const state = tabStates[tabId]
    if (!state?.metaDataDomainOptions) return []
    return state.metaDataDomainOptions
  }

  const handleMetaBusinessDomainChange = async (tabId) => {
    const state = tabStates[tabId]
    if (!state) return
    state.metaForm.dataDomain = ''
    await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
  }

  const ensureClusterSelected = (table) => {
    if (isDorisTable(table) && !getObjectSourceId(table, clusterId.value)) {
      ElMessage.warning('请选择 Doris 集群')
      return false
    }
    return true
  }

  const getFieldRows = (tabId) => {
    const state = tabStates[tabId]
    if (!state) return []
    return state.fieldsEditing ? state.fieldsDraft : state.fields
  }

  const updateTableCache = (updated) => {
    if (!updated?.dbName) return
    const sourceId = getObjectSourceId(updated, clusterId.value)
    if (!sourceId) return
    const sourceKey = String(sourceId)
    const list = tableStore[sourceKey]?.[updated.dbName] || []
    const idx = list.findIndex((item) => String(item.id) === String(updated.id))
    if (idx === -1) return
    const next = [...list]
    next[idx] = { ...next[idx], ...updated }
    tableStore[sourceKey][updated.dbName] = next
  }

  const refreshFields = async (tabId) => {
    const state = tabStates[tabId]
    if (!state?.table?.id) return
    try {
      const fieldList = await tableApi.getFields(state.table.id)
      state.fields = Array.isArray(fieldList) ? fieldList : []
    } catch (error) {
      console.error('刷新字段失败', error)
    }
  }

  const syncTabKey = (oldKey, updatedTable) => {
    const newKey = getTableKey(updatedTable, updatedTable?.dbName || '', getObjectSourceId(updatedTable, clusterId.value))
    if (!newKey || newKey === oldKey) return oldKey
    const oldIndex = openTabs.value.findIndex((tab) => String(tab.id) === String(oldKey))
    const existingIndex = openTabs.value.findIndex((tab) => String(tab.id) === String(newKey))
    if (existingIndex !== -1 && existingIndex !== oldIndex) {
      if (oldIndex !== -1) {
        openTabs.value.splice(oldIndex, 1)
      }
      delete tabStates[oldKey]
      activeTab.value = String(newKey)
      selectedTableKey.value = String(newKey)
      return newKey
    }
    if (oldIndex !== -1) {
      openTabs.value[oldIndex].id = newKey
    }
    tabStates[newKey] = tabStates[oldKey]
    if (oldKey !== newKey) {
      delete tabStates[oldKey]
      delete tableRefs.value[oldKey]
    }
    if (String(activeTab.value) === String(oldKey)) {
      activeTab.value = String(newKey)
    }
    selectedTableKey.value = String(newKey)
    return newKey
  }

  const startMetaEdit = async (tabId) => {
    if (isDemoMode) {
      showDemoReadonlyMessage('编辑表信息')
      return
    }
    const state = tabStates[tabId]
    if (!state) return
    if (warnPlatformMetadataMissing(state.table)) return
    if (!ensureClusterSelected(state.table)) return
    if (!businessDomainOptions.value.length) {
      await loadBusinessDomains()
    }
    await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
    state.metaEditing = true
    state.metaForm = { ...state.metaForm }
  }

  const cancelMetaEdit = async (tabId) => {
    const state = tabStates[tabId]
    if (!state) return
    state.metaEditing = false
    state.metaForm = { ...state.metaOriginal }
    await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
  }

  const saveMetaEdit = async (tabId) => {
    if (isDemoMode) {
      showDemoReadonlyMessage('保存表信息')
      return
    }
    const state = tabStates[tabId]
    if (warnPlatformMetadataMissing(state?.table)) return
    if (!state?.table?.id) return
    if (!ensureClusterSelected(state.table)) return
    if (!state.metaForm.layer) {
      // 引擎配置页没有分层字段，提示去概览补齐，避免看不到的必填项卡住保存
      ElMessage.warning(state.metaTab === 'doris' ? '请先在概览中选择数据分层' : '请选择数据分层')
      return
    }
    const sourceId = getObjectSourceId(state.table, clusterId.value)
    const tableId = state.table.id
    try {
      await ElMessageBox.confirm('确认保存对象信息的修改吗？', '提示', {
        type: 'warning',
        confirmButtonText: '确认',
        cancelButtonText: '取消'
      })
    } catch (error) {
      return
    }
    if (state.table.id !== tableId) return
    state.metaSaving = true
    try {
      const payload = {
        tableName: state.metaForm.tableName,
        tableComment: state.metaForm.tableComment,
        layer: state.metaForm.layer,
        businessDomain: state.metaForm.businessDomain,
        dataDomain: state.metaForm.dataDomain,
        owner: state.metaForm.owner,
        bucketNum: state.metaForm.bucketNum,
        replicaNum: state.metaForm.replicaNum
      }
      await tableApi.update(tableId, payload, sourceId)
      const updated = await tableApi.getById(tableId)
      state.table = { ...state.table, ...updated }
      state.metaForm = {
        tableName: state.table.tableName || '',
        tableComment: state.table.tableComment || '',
        layer: state.table.layer || '',
        businessDomain: state.table.businessDomain || '',
        dataDomain: state.table.dataDomain || '',
        owner: state.table.owner || '',
        bucketNum: state.table.bucketNum ?? '',
        replicaNum: state.table.replicaNum ?? ''
      }
      state.metaDataDomainOptions = []
      if (state.metaForm.businessDomain) {
        await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
      }
      state.metaOriginal = { ...state.metaForm }
      state.metaEditing = false
      updateTableCache(state.table)
      const newKey = syncTabKey(tabId, state.table)
      const tab = openTabs.value.find((item) => String(item.id) === String(newKey))
      if (tab) {
        tab.tableName = state.table.tableName
        tab.dbName = state.table.dbName
      }
      ElMessage.success('表信息已更新')
    } catch (error) {
      ElMessage.error('更新失败')
    } finally {
      state.metaSaving = false
    }
  }

  const startFieldsEdit = (tabId) => {
    if (isDemoMode) {
      showDemoReadonlyMessage('编辑字段')
      return
    }
    const state = tabStates[tabId]
    if (!state) return
    if (warnPlatformMetadataMissing(state.table)) return
    if (!ensureClusterSelected(state.table)) return
    state.fieldsEditing = true
    state.fieldsDraft = state.fields.map((item) => ({ ...item }))
    state.fieldsRemoved = []
  }

  const cancelFieldsEdit = (tabId) => {
    const state = tabStates[tabId]
    if (!state) return
    state.fieldsEditing = false
    state.fieldsDraft = []
    state.fieldsRemoved = []
  }

  const addField = (tabId, afterRow = null) => {
    const state = tabStates[tabId]
    if (!state) return
    if (isAggregateTable(state.table)) {
      ElMessage.warning('AGGREGATE 表仅支持修改注释，无法新增字段')
      return
    }
    const newRow = {
      id: null,
      fieldName: '',
      fieldType: '',
      fieldOrder: 0,
      isNullable: 1,
      isPrimary: 0,
      defaultValue: '',
      fieldComment: ''
    }
    if (!afterRow) {
      state.fieldsDraft.unshift(newRow)
      return
    }
    const index = state.fieldsDraft.indexOf(afterRow)
    if (index === -1) {
      state.fieldsDraft.unshift(newRow)
      return
    }
    state.fieldsDraft.splice(index + 1, 0, newRow)
  }

  const removeField = (tabId, row) => {
    const state = tabStates[tabId]
    if (!state) return
    if (isAggregateTable(state.table)) {
      ElMessage.warning('AGGREGATE 表仅支持修改注释，无法删除字段')
      return
    }
    if (row?.id) {
      state.fieldsRemoved = [...new Set([...(state.fieldsRemoved || []), row.id])]
    }
    state.fieldsDraft = state.fieldsDraft.filter((item) => item !== row)
  }

  const saveFieldsEdit = async (tabId) => {
    if (isDemoMode) {
      showDemoReadonlyMessage('保存字段')
      return
    }
    const state = tabStates[tabId]
    if (warnPlatformMetadataMissing(state?.table)) return
    if (!state?.table?.id) return
    if (!ensureClusterSelected(state.table)) return
    const draft = state.fieldsDraft || []
    const removedIds = [...new Set(state.fieldsRemoved || [])]
    for (const row of draft) {
      const payload = buildFieldPayload(row)
      if (!payload.fieldName || !payload.fieldType) {
        ElMessage.warning('请填写字段名和类型')
        return
      }
    }
    const originalMap = new Map(state.fields.map((item) => [item.id, item]))
    const createList = draft.filter((row) => !row.id)
    const updateList = draft.filter((row) => row.id && isFieldChanged(row, originalMap.get(row.id)))
    if (isAggregateTable(state.table)) {
      const invalidUpdates = updateList.filter(
        (row) => !isOnlyCommentChanged(row, originalMap.get(row.id))
      )
      if (createList.length || removedIds.length || invalidUpdates.length) {
        ElMessage.warning('AGGREGATE 表仅支持修改字段注释')
        return
      }
    }
    if (state.table?.typeDefinition && !state.table.typeDefinition.capabilities?.newPrimaryKeyEdit?.supported) {
      const primaryChanged = updateList.some((row) => {
        const original = originalMap.get(row.id)
        return Number(row.isPrimary ?? 0) !== Number(original?.isPrimary ?? 0)
      })
      const primaryAdded = createList.some((row) => Number(row.isPrimary ?? 0) === 1)
      if (primaryChanged || primaryAdded) {
        ElMessage.warning('Doris 不支持在线修改主键列')
        return
      }
    }
    const mysqlPrimaryChanged = updateList.some((row) =>
      isMysqlPrimaryKeyLocked(state.table, row, getTableSourceType?.(state.table)) &&
      Number(row.isPrimary ?? 0) !== Number(originalMap.get(row.id)?.isPrimary ?? 0)
    )
    if (mysqlPrimaryChanged) {
      ElMessage.warning('MySQL 暂不支持在线修改已有字段的主键')
      return
    }
    if (!createList.length && !updateList.length && !removedIds.length) {
      ElMessage.info('暂无字段变更')
      return
    }
    const sourceId = getObjectSourceId(state.table, clusterId.value)
    const tableId = state.table.id
    try {
      await ElMessageBox.confirm(
        `确认保存字段变更（新增 ${createList.length}、修改 ${updateList.length}、删除 ${removedIds.length}）吗？`,
        '提示',
        {
          type: 'warning',
          confirmButtonText: '确认',
          cancelButtonText: '取消'
        }
      )
    } catch (error) {
      return
    }
    if (state.table.id !== tableId) return
    state.fieldSubmitting = true
    try {
      for (const row of createList) {
        await tableApi.createField(tableId, buildFieldPayload(row), sourceId)
      }
      for (const row of updateList) {
        await tableApi.updateField(tableId, row.id, buildFieldPayload(row), sourceId)
      }
      for (const id of removedIds) {
        await tableApi.deleteField(tableId, id, sourceId)
      }
      await refreshFields(tabId)
      state.fieldsEditing = false
      state.fieldsDraft = []
      state.fieldsRemoved = []
      ElMessage.success('字段已保存')
    } catch (error) {
      ElMessage.error('字段保存失败')
    } finally {
      state.fieldSubmitting = false
    }
  }

  return {
    businessDomainOptions,
    loadBusinessDomains,
    loadMetaDataDomainOptions,
    getMetaDataDomainOptions,
    handleMetaBusinessDomainChange,
    getFieldRows,
    startMetaEdit,
    cancelMetaEdit,
    saveMetaEdit,
    startFieldsEdit,
    cancelFieldsEdit,
    saveFieldsEdit,
    addField,
    removeField,
  }
}
