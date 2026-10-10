import { ElMessage, ElMessageBox } from 'element-plus'
import { tableApi } from '@/api/table'
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'
import { copyText } from '@/utils/clipboard'

// 表操作与详情加载（P2-2 F15）：建表/删表/元数据同步、DDL 与访问统计加载、
// 任务抽屉与血缘跳转从 DataStudioNew.vue 抽离。共享状态所有权仍归组件。
export function useTableActions({
  clusterId,
  openTabs,
  activeTab,
  tabStates,
  createDrawerVisible,
  taskDrawerRef,
  router,
  // 组件内判定助手
  isDorisTable,
  isPlatformMetadataMissing,
  warnPlatformMetadataMissing,
  // useCatalogTree
  tableStore,
  loadClusters,
  loadTables,
  // useStudioTabs
  openTableTab,
  loadTabData,
  handleTabRemove,
  getTabItemById,
}) {
  const saveAsTask = (tabId) => {
    if (isDemoMode) {
      showDemoReadonlyMessage('保存查询任务')
      return
    }
    const state = tabStates[tabId]
    if (!state?.query?.sql?.trim()) {
      ElMessage.warning('请先输入 SQL')
      return
    }
    const sourceId = state.query.sourceId ?? state.table?.sourceId ?? clusterId.value ?? ''
    const database = state.query.dbName ?? state.table?.dbName ?? ''
    taskDrawerRef.value?.open(null, {
      taskSql: state.query.sql,
      taskName: `新建查询任务_${Date.now()}`,
      taskDesc: `From DataStudio\nCluster: ${sourceId}\nDatabase: ${database}`
    })
  }

  const handleCreateTable = () => {
    if (isDemoMode) {
      showDemoReadonlyMessage('新建表')
      return
    }
    createDrawerVisible.value = true
  }

  const handleDeleteTable = async () => {
    if (isDemoMode) {
      showDemoReadonlyMessage('删除表')
      return
    }
    const active = activeTab.value
    const state = active ? tabStates[active] : null
    const table = state?.table
    if (warnPlatformMetadataMissing(table)) {
      return
    }
    if (!table?.id) {
      ElMessage.warning('请先选择要删除的表')
      return
    }
    const dorisTable = isDorisTable(table)
    if (dorisTable && !clusterId.value) {
      ElMessage.warning('请选择 Doris 集群')
      return
    }

    try {
      const rawName = String(table.tableName || '').trim()
      const expectedName = dorisTable ? (rawName.includes('.') ? rawName.split('.').pop() : rawName) : rawName
      const message = dorisTable
        ? `确定要删除表 “${table.tableName}” 吗？删除后将重命名为 deprecated_时间戳，数据不会丢失。`
        : `确定要删除表 “${table.tableName}” 吗？将仅删除平台元数据记录。`
      const { value } = await ElMessageBox.prompt(
        `${message}\n请输入表名以确认：${expectedName}`,
        '删除表确认',
        {
          type: 'warning',
          confirmButtonText: '确认删除',
          cancelButtonText: '取消',
          inputPlaceholder: expectedName,
          inputValidator: (input) => {
            if (String(input || '').trim() !== expectedName) {
              return `请输入正确表名：${expectedName}`
            }
            return true
          }
        }
      )
      const confirmTableName = String(value || '').trim()
      if (dorisTable) {
        await tableApi.softDelete(table.id, clusterId.value || null, confirmTableName)
      } else {
        await tableApi.delete(table.id, confirmTableName)
      }
      ElMessage.success('删除表成功')
      const dbName = table.dbName || table.databaseName || table.database
      if (dbName) {
        const sourceId = table.sourceId || clusterId.value
        if (sourceId) {
          await loadTables(sourceId, dbName, true)
        }
      }
      handleTabRemove(active)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error('删除表失败')
      }
    }
  }

  const syncMissingTableMetadata = async (tabId) => {
    if (isDemoMode) {
      showDemoReadonlyMessage('同步平台元数据')
      return
    }
    const state = tabStates[String(tabId || '')]
    const table = state?.table
    if (!isPlatformMetadataMissing(table)) return
    const sourceId = String(table.sourceId || table.clusterId || clusterId.value || '')
    const dbName = table.dbName || table.databaseName || table.database || ''
    const tableName = table.tableName || ''
    if (!sourceId || !dbName || !tableName) {
      ElMessage.warning('缺少数据源、数据库或表名，无法同步')
      return
    }

    try {
      await ElMessageBox.confirm(
        `确定将 “${dbName}.${tableName}” 同步到平台元数据吗？`,
        '同步平台元数据',
        {
          type: 'warning',
          confirmButtonText: '立即同步',
          cancelButtonText: '取消'
        }
      )
    } catch (error) {
      return
    }

    state.metadataSyncing = true
    try {
      const response = await tableApi.syncTableMetadataByName(dbName, tableName, sourceId)
      await loadTables(sourceId, dbName, true)
      const list = tableStore[String(sourceId)]?.[dbName] || []
      const synced = list.find((item) => {
        if (!item) return false
        if (response?.tableId && String(item.id) === String(response.tableId)) return true
        return item.tableName === tableName
      })
      const syncedTable = synced || {
        ...table,
        id: response?.tableId || table.id,
        metadataMissing: false,
        metadataStatus: 'synced'
      }
      state.table = {
        ...state.table,
        ...syncedTable,
        sourceId,
        dbName,
        metadataMissing: false,
        metadataStatus: 'synced'
      }
      const tab = openTabs.value.find((item) => String(item.id) === String(tabId))
      if (tab) {
        tab.sourceId = sourceId
        tab.dbName = dbName
        tab.tableName = tableName
      }
      state.dataLoaded = false
      await loadTabData(String(tabId))
      ElMessage.success('平台元数据已同步')
    } catch (error) {
      ElMessage.error('同步平台元数据失败')
    } finally {
      state.metadataSyncing = false
    }
  }

  const handleCreateSuccess = async (result) => {
    createDrawerVisible.value = false
    await loadClusters()
    const tableId = result?.id || result?.tableId
    if (!tableId) return
    try {
      const table = await tableApi.getById(tableId)
      const dbName = table?.dbName || table?.databaseName || table?.database || ''
      if (dbName) {
        if (table?.sourceId || clusterId.value) {
          const sourceId = table.sourceId || clusterId.value
          await loadTables(sourceId, dbName, true)
        }
      }
      await openTableTab(table, dbName, table?.sourceId || clusterId.value)
    } catch (error) {
      console.error('加载新建表失败', error)
    }
  }

  const loadDdl = async (tabId) => {
    const state = tabStates[tabId]
    if (!state?.table) return
    const sourceId = state.table.sourceId || clusterId.value
    if (!sourceId) {
      ElMessage.warning('请选择数据源')
      return
    }
    const dbName = state.table.dbName || state.table.databaseName || state.table.database || ''
    const tableName = state.table.tableName || ''
    if (!dbName || !tableName) {
      ElMessage.warning('缺少数据库或表名')
      return
    }
    state.ddlLoading = true
    try {
      const ddl = state.table.id
        ? await tableApi.getTableDdl(state.table.id, sourceId || null)
        : await tableApi.getTableDdlByName(sourceId || null, dbName, tableName)
      state.ddl = ddl || ''
    } catch (error) {
      ElMessage.error('加载 DDL 失败')
    } finally {
      state.ddlLoading = false
    }
  }

  const loadAccessStats = async (tabId, force = false) => {
    const state = tabStates[tabId]
    if (!state?.table?.id) return
    if (state.accessLoading) return
    if (!force && state.accessStats) return
    const sourceId = state.table.sourceId || clusterId.value || state.table.clusterId
    if (!sourceId) {
      state.accessError = '缺少集群信息，无法获取访问统计'
      state.accessStats = null
      return
    }

    state.accessLoading = true
    state.accessError = ''
    try {
      const stats = await tableApi.getAccessStats(state.table.id, {
        clusterId: sourceId,
        recentDays: 30,
        trendDays: 14,
        topUsers: 5
      })
      state.accessStats = stats || null
    } catch (error) {
      state.accessStats = null
      state.accessError = error?.message || '加载访问统计失败'
    } finally {
      state.accessLoading = false
    }
  }

  const copyDdl = async (tabId) => {
    const state = tabStates[tabId]
    if (!state?.ddl) return
    try {
      await copyText(state.ddl)
      ElMessage.success('已复制')
    } catch (error) {
      ElMessage.error('复制失败')
    }
  }

  const openTask = (taskId) => {
    if (!taskId) return
    if (isDemoMode) {
      showDemoReadonlyMessage('任务详情')
      return
    }
    taskDrawerRef.value?.open(taskId)
  }

  const goCreateRelatedTask = (tabId, relation) => {
    if (isDemoMode) {
      showDemoReadonlyMessage('新增关联任务')
      return
    }
    const state = tabStates[String(tabId || '')]
    if (warnPlatformMetadataMissing(state?.table)) return
    const tableId = state?.table?.id
    if (!tableId) {
      ElMessage.warning('请先选择表')
      return
    }
    taskDrawerRef.value?.open(null, { relation, tableId })
  }

  const handleTaskSuccess = async () => {
    const id = String(activeTab.value || '')
    const tab = getTabItemById(id)
    if (tab?.kind !== 'table') return
    if (tabStates[id]) {
      tabStates[id].dataLoaded = false
    }
    await loadTabData(id)
  }

  const goLineage = (tabId) => {
    const state = tabStates[tabId]
    if (warnPlatformMetadataMissing(state?.table)) return
    if (!state?.table?.id) return
    router.push({ path: '/lineage', query: { tableId: state.table.id } })
  }

  return {
    saveAsTask,
    handleCreateTable,
    handleDeleteTable,
    syncMissingTableMetadata,
    handleCreateSuccess,
    loadDdl,
    loadAccessStats,
    copyDdl,
    openTask,
    goCreateRelatedTask,
    handleTaskSuccess,
    goLineage,
  }
}
