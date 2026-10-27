import { dorisClusterApi } from '@/api/doris'
import { reactive } from 'vue'
import { tableApi } from '@/api/table'
import { lineageApi } from '@/api/lineage'

// Tab 工作区生命周期（P2-2 F14，完成 F8 残留）：Tab 状态创建、打开表页签、
// 详情加载、恢复补水、资源释放与关闭/新建等操作从 DataStudioNew.vue 抽离。
// 共享响应式状态（openTabs/activeTab/tabStates 等）所有权仍归组件，按引用传入。
export function useStudioTabs({
  clusterId,
  openTabs,
  activeTab,
  tabStates,
  lineageCache,
  selectedTableKey,
  // useCatalogTree
  findCachedTableById,
  getDatasourceById,
  getTableKey,
  focusTableInSidebar,
  loadSchemas,
  // useSqlCompletion
  getSchemaOptions,
  // useQueryExecution
  buildDefaultSql,
  syncAutoSelectSqlIfSchemaMismatch,
  clearQueryTimer,
  stopNowTickerIfIdle,
  // useResultChart
  disposeChart,
  // useResizablePanes
  leftPaneRefs,
  leftPaneHeights,
  // useTableMetaEditing
  loadMetaDataDomainOptions,
}) {
  const createTabState = (table) => {
    return reactive({
      table: { ...table },
      query: {
        sql: buildDefaultSql(table),
        limit: 200,
        hasSelection: false,
        selectionText: ''
      },
      queryLoading: false,
      queryStopping: false,
      queryCancelable: false,
      queryAbortController: null,
      queryRunId: 0,
      queryTiming: {
        startedAt: 0,
        elapsedMs: 0
      },
      queryResult: {
        resultSets: [],
        columns: [],
        rows: [],
        hasMore: false,
        durationMs: 0,
        executedAt: '',
        cancelled: false,
        statementInfos: [],
        message: '',
        errorMessage: ''
      },
      resultTab: 'result-0',
      resultViewTabs: ['table'],
      page: {
        current: 1,
        size: 20
      },
      charts: [
        {
          type: 'bar',
          xAxis: '',
          yAxis: []
        }
      ],
      metaTab: 'basic',
      metaEditing: false,
      metaSaving: false,
      metaForm: {
        tableName: table.tableName || '',
        tableComment: table.tableComment || '',
        layer: table.layer || '',
        businessDomain: table.businessDomain || '',
        dataDomain: table.dataDomain || '',
        owner: table.owner || '',
        bucketNum: table.bucketNum ?? '',
        replicaNum: table.replicaNum ?? ''
      },
      metaOriginal: {},
      metaDataDomainOptions: [],
      // 分区列表缓存（null=未加载过，数组=已加载结果）
      partitionList: null,
      metadataSyncing: false,
      // 智能元数据生成结果（内存态，供字段表「智能描述」列消费）
      metaSuggestion: null,
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
      lineage: { upstreamTables: [], downstreamTables: [] },
      tasks: { writeTasks: [], readTasks: [] },
      dataLoading: false,
      dataLoaded: false
    })
  }

  const openTableTab = async (table, dbFallback = '', sourceFallback = '') => {
    if (!table) return
    let payload = table
    const tableId = payload?.id
    const hasSource = !!(payload?.sourceId || payload?.clusterId || sourceFallback)
    const hasDb = !!(payload?.dbName || payload?.databaseName || payload?.database || dbFallback)
    if (tableId && (!hasSource || !hasDb)) {
      const cached = findCachedTableById(tableId)
      if (cached) {
        payload = { ...cached, ...payload, id: tableId, dbName: cached.dbName, sourceId: cached.sourceId }
      } else {
        try {
          const tableInfo = await tableApi.getById(tableId)
          if (tableInfo) {
            const resolvedSourceId = String(
              payload?.sourceId || payload?.clusterId || tableInfo.clusterId || sourceFallback || ''
            )
            const resolvedDb =
              tableInfo.dbName ||
              payload?.dbName ||
              payload?.databaseName ||
              payload?.database ||
              dbFallback ||
              ''
            payload = {
              ...tableInfo,
              ...payload,
              id: tableId,
              dbName: resolvedDb,
              sourceId: resolvedSourceId
            }
          }
        } catch (error) {
          console.error('加载血缘表信息失败', error)
        }
      }
    }

    const sourceId = String(payload.sourceId || payload.clusterId || sourceFallback || '')
    if (sourceId) {
      clusterId.value = sourceId
    }
    const resolvedSourceType = String(payload.sourceType || getDatasourceById(sourceId)?.sourceType || '').toUpperCase()
    const resolvedDb = payload.dbName || payload.databaseName || payload.database || dbFallback || ''
    payload = { ...payload, dbName: resolvedDb, sourceId, sourceType: resolvedSourceType,
      typeDefinition: payload.typeDefinition || getDatasourceById(sourceId)?.typeDefinition }
    if (!payload.defaultQuery && sourceId && resolvedDb && payload.tableName) {
      payload.defaultQuery = await dorisClusterApi.defaultQuery(sourceId, resolvedDb, payload.tableName)
    }
    const key = getTableKey(payload, resolvedDb, sourceId)
    if (!key) return

    selectedTableKey.value = key

    const existing = openTabs.value.find((item) => String(item.id) === String(key))
    if (existing) {
      activeTab.value = String(existing.id)
      const state = tabStates[String(existing.id)]
      if (state) {
        state.table = { ...state.table, ...payload }
        syncAutoSelectSqlIfSchemaMismatch(state)
      }
      if (existing.kind !== 'query') {
        existing.sourceId = sourceId || existing.sourceId
        existing.sourceType = resolvedSourceType || existing.sourceType
        existing.dbName = resolvedDb || existing.dbName
        existing.tableName = payload.tableName || existing.tableName
      }
      await focusTableInSidebar(payload, key, resolvedDb, sourceId)
      return
    }

    const existingById = tableId
      ? openTabs.value.find((item) => {
          if (!item || item.kind === 'query') return false
          const state = tabStates[String(item.id)]
          return state?.table?.id && String(state.table.id) === String(tableId)
        })
      : null
    if (existingById) {
      activeTab.value = String(existingById.id)
      const state = tabStates[String(existingById.id)]
      if (state) {
        state.table = { ...state.table, ...payload }
        syncAutoSelectSqlIfSchemaMismatch(state)
      }
      existingById.sourceId = sourceId || existingById.sourceId
      existingById.sourceType = resolvedSourceType || existingById.sourceType
      existingById.dbName = resolvedDb || existingById.dbName
      existingById.tableName = payload.tableName || existingById.tableName
      selectedTableKey.value = key
      await focusTableInSidebar(payload, key, resolvedDb, sourceId)
      return
    }

    const tabItem = {
      id: key,
      kind: 'table',
      tableName: payload.tableName,
      dbName: resolvedDb,
      sourceId,
      sourceType: resolvedSourceType
    }
    tabStates[key] = createTabState({ ...payload, dbName: resolvedDb, sourceId })
    openTabs.value.push(tabItem)
    activeTab.value = key

    await focusTableInSidebar(payload, key, resolvedDb, sourceId)
    await loadTabData(key)
  }

  const loadTabData = async (tabId) => {
    const state = tabStates[tabId]
    if (!state?.table) return
    if (state.dataLoading || state.dataLoaded) return

    state.table.typeDefinition = getDatasourceById(state.table.sourceId || clusterId.value)?.typeDefinition || state.table.typeDefinition
    state.dataLoading = true
    if (!state.table.defaultQuery && state.table.sourceId && state.table.dbName && state.table.tableName) {
      try { state.table.defaultQuery = await dorisClusterApi.defaultQuery(state.table.sourceId, state.table.dbName, state.table.tableName) }
      catch (error) { state.defaultQueryError = error.message || '默认 SQL 加载失败' }
    }
    if (!state.table.id && state.table.dbName && state.table.tableName) {
      try {
        const sourceId = state.table.sourceId || clusterId.value
        const options = await tableApi.searchOptions({
          keyword: state.table.tableName,
          limit: 20,
          dbName: state.table.dbName,
          clusterId: sourceId || undefined
        })
        const match = (options || []).find((item) => item.tableName === state.table.tableName)
        if (match?.id) {
          state.table.id = match.id
          state.table.tableComment = state.table.tableComment || match.tableComment
          state.table.layer = state.table.layer || match.layer
          state.table.metadataMissing = false
          state.table.metadataStatus = 'synced'
        }
      } catch (error) {
        console.error('解析表元数据失败', error)
      }
    }
    if (!state.table.id) {
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
      state.metaOriginal = { ...state.metaForm }
      state.metaDataDomainOptions = []
      if (state.metaForm.businessDomain) {
        await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
      }
      state.fields = []
      state.fieldsEditing = false
      state.fieldsDraft = []
      state.fieldsRemoved = []
      state.lineage = {
        upstreamTables: [],
        downstreamTables: [],
        edges: []
      }
      state.tasks = {
        writeTasks: [],
        readTasks: []
      }
      state.accessLoading = false
      state.accessStats = null
      state.accessError = ''
      if (state.query.sql === '') {
        state.query.sql = buildDefaultSql(state.table)
      }
      state.dataLoaded = true
      state.dataLoading = false
      return
    }
    try {
      const [tableInfo, fieldList, lineageData, tasksData, lineageGraphData] = await Promise.all([
        tableApi.getById(state.table.id),
        tableApi.getFields(state.table.id),
        tableApi.getLineage(state.table.id),
        tableApi.getTasks(state.table.id),
        lineageApi.getLineageGraph({ tableId: state.table.id, depth: 1 }).catch(() => null)
      ])
      state.table = { ...state.table, ...tableInfo }
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
      state.fields = Array.isArray(fieldList) ? fieldList : []
      state.fieldsEditing = false
      state.fieldsDraft = []
      state.fieldsRemoved = []
      state.lineage = {
        upstreamTables: lineageData?.upstreamTables || [],
        downstreamTables: lineageData?.downstreamTables || [],
        edges: lineageGraphData?.edges || []
      }
      lineageCache[state.table.id] = state.lineage
      state.tasks = {
        writeTasks: Array.isArray(tasksData?.writeTasks) ? tasksData.writeTasks : [],
        readTasks: Array.isArray(tasksData?.readTasks) ? tasksData.readTasks : []
      }
      state.accessLoading = false
      state.accessStats = null
      state.accessError = ''
      if (state.query.sql === '') {
        state.query.sql = buildDefaultSql(state.table)
      }
      state.dataLoaded = true
    } catch (error) {
      console.error('加载表详情失败', error)
      state.dataLoaded = false
    } finally {
      state.dataLoading = false
    }
  }

  const hydrateRestoredTableTabs = () => {
    const tableTabIds = openTabs.value
      .filter((tab) => tab?.kind === 'table')
      .map((tab) => String(tab.id || ''))
      .filter(Boolean)
    if (!tableTabIds.length) return
    void Promise.allSettled(tableTabIds.map((tabId) => loadTabData(tabId)))
  }

  const disposeTabResources = (tabId) => {
    const id = String(tabId || '')
    if (!id) return
    clearQueryTimer(id)
    disposeChart(id)
    if (leftPaneRefs.value?.[id]) {
      delete leftPaneRefs.value[id]
    }
    if (leftPaneHeights[id] !== undefined) {
      delete leftPaneHeights[id]
    }
    delete tabStates[id]
    stopNowTickerIfIdle()
  }

  const handleTabRemove = (name) => {
    const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(name))
    if (idx === -1) return
    const removed = openTabs.value.splice(idx, 1)[0]
    if (removed) {
      disposeTabResources(removed.id)
    }
    if (openTabs.value.length) {
      activeTab.value = String(openTabs.value[Math.max(idx - 1, 0)].id)
    } else {
      activeTab.value = ''
    }
  }

  const handleCloseLeft = (tabKey) => {
    const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(tabKey))
    if (idx <= 0) return
    const removed = openTabs.value.splice(0, idx)
    removed.forEach((tab) => disposeTabResources(tab.id))
    const stillActive = openTabs.value.some((tab) => String(tab.id) === String(activeTab.value))
    if (!stillActive) {
      activeTab.value = String(tabKey)
    }
  }

  const handleCloseRight = (tabKey) => {
    const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(tabKey))
    if (idx === -1 || idx >= openTabs.value.length - 1) return
    const removed = openTabs.value.splice(idx + 1)
    removed.forEach((tab) => disposeTabResources(tab.id))
    const stillActive = openTabs.value.some((tab) => String(tab.id) === String(activeTab.value))
    if (!stillActive) {
      activeTab.value = String(tabKey)
    }
  }

  const handleCloseAll = () => {
    const removed = openTabs.value.splice(0)
    removed.forEach((tab) => disposeTabResources(tab.id))
    activeTab.value = ''
  }

  const resolveQuerySourceId = () => {
    const current = openTabs.value.find((tab) => String(tab.id) === String(activeTab.value))
    return String(tabStates[current?.id]?.query?.sourceId ?? current?.sourceId ?? '')
  }

  const resolveQueryDatabase = (sourceId) => {
    const sid = String(sourceId || '')
    if (!sid) return ''
    const current = openTabs.value.find((tab) => String(tab.id) === String(activeTab.value))
    return String(tabStates[current?.id]?.query?.dbName ?? current?.dbName ?? '')
  }

  const getTabInsertIndex = () => {
    const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(activeTab.value))
    return idx === -1 ? openTabs.value.length : idx + 1
  }

  const handleTabAdd = async () => {
    const sourceId = resolveQuerySourceId()
    let dbName = resolveQueryDatabase(sourceId)
    if (sourceId) {
      await loadSchemas(sourceId)
      if (dbName && !getSchemaOptions(sourceId).includes(dbName)) {
        dbName = ''
      }
    }

    const queryId = `query:${Date.now()}`
    const tabItem = {
      id: queryId,
      kind: 'query',
      tableName: '无标题 - 查询',
      dbName,
      sourceId
    }
    tabStates[queryId] = createTabState({ tableName: '', dbName, sourceId })
    tabStates[queryId].query.sql = ''
    openTabs.value.splice(getTabInsertIndex(), 0, tabItem)
    activeTab.value = queryId
  }

  const getTabItemById = (tabId) => {
    return openTabs.value.find((tab) => String(tab.id) === String(tabId)) || null
  }

  return {
    createTabState,
    openTableTab,
    loadTabData,
    hydrateRestoredTableTabs,
    disposeTabResources,
    handleTabRemove,
    handleCloseLeft,
    handleCloseRight,
    handleCloseAll,
    resolveQuerySourceId,
    resolveQueryDatabase,
    getTabInsertIndex,
    handleTabAdd,
    getTabItemById,
  }
}
