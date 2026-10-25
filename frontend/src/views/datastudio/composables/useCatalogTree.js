import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { tableApi } from '@/api/table'
import { dorisClusterApi } from '@/api/doris'

const EMPTY_SCHEMA_COUNTS = Object.freeze({ tableCount: 0, viewCount: 0, totalCount: 0 })

const getDatasourceNodeKey = (sourceId) => `ds:${String(sourceId)}`
const getSchemaNodeKey = (sourceId, schemaName) => `schema:${String(sourceId)}::${schemaName}`
const getObjectGroupNodeKey = (sourceId, schemaName, objectType) =>
  `group:${String(sourceId)}::${schemaName}::${objectType}`

// Data Studio catalog tree state and lazy loading (P2-2 F7).
// This is moved from DataStudioNew.vue as a behavior-preserving cluster: the
// schema/table caches remain shared with SQL completion and route hydration.
export function useCatalogTree({
  clusterId,
  tabStates,
  openTabs,
  activeTab,
  tableObserver,
  handleQuerySourceSelect,
  handleQueryDatabaseSelect,
  openTableTab,
}) {
  const dbLoading = ref(false)
  const dataSources = ref([])
  const activeSource = ref('')
  const schemaStore = reactive({})
  const schemaLoading = reactive({})
  const schemaCountStore = reactive({})
  const schemaCountLoading = reactive({})
  const schemaCountKeyword = reactive({})
  const schemaCountRequestSeq = reactive({})
  const activeSchema = reactive({})
  const tableLoading = reactive({})
  const tableStore = reactive({})
  const columnStore = reactive({})
  const activatedSources = reactive({})
  const datasourceActivationTasks = new Map()
  let schemaCountReloadTimer = null

  const catalogTreeRef = ref(null)
  const catalogTreeProps = {
    children: 'children',
    label: 'name',
    isLeaf: 'leaf'
  }

  const catalogRoots = computed(() => {
    const list = Array.isArray(dataSources.value) ? dataSources.value : []
    return list.map((source) => ({
      nodeKey: getDatasourceNodeKey(source.id),
      type: 'datasource',
      name: source.clusterName || source.name || `DataSource ${source.id}`,
      sourceId: String(source.id),
      sourceType: source.sourceType,
      typeDefinition: source.typeDefinition,
      status: source.status,
      leaf: false
    }))
  })

  const searchKeyword = ref('')
  const sortField = ref('tableName')
  const sortOrder = ref('asc')
  const selectedTableKey = ref('')
  const tableRefs = ref({})

  const getDatasourceById = (sourceId) => {
    const id = String(sourceId || '')
    const list = Array.isArray(dataSources.value) ? dataSources.value : []
    return list.find((item) => String(item.id) === id) || null
  }

  const activateDatasource = async (sourceId) => {
    if (!sourceId) return false
    const key = String(sourceId)
    const source = getDatasourceById(key)
    if (source?.status && source.status !== 'active') {
      ElMessage.warning('数据源已停用')
      return false
    }
    if (activatedSources[key]) return true
    if (datasourceActivationTasks.has(key)) {
      return datasourceActivationTasks.get(key)
    }

    const task = (async () => {
      try {
        const connected = await dorisClusterApi.testConnection(sourceId)
        if (connected !== true) throw new Error('连接失败')
        activatedSources[key] = true
        return true
      } catch {
        activatedSources[key] = false
        ElMessage.error('数据源连接失败')
        return false
      } finally {
        datasourceActivationTasks.delete(key)
      }
    })()

    datasourceActivationTasks.set(key, task)
    return task
  }

  const toSafeCount = (value) => {
    const num = Number(value)
    if (!Number.isFinite(num) || num <= 0) return 0
    return Math.floor(num)
  }

  const normalizeSchemaCounts = (item) => {
    const tableCount = toSafeCount(item?.tableCount)
    const viewCount = toSafeCount(item?.viewCount)
    const totalFromPayload = toSafeCount(item?.totalCount)
    const totalCount = totalFromPayload || tableCount + viewCount
    return { tableCount, viewCount, totalCount }
  }

  const normalizeKeyword = (keyword) => String(keyword || '').trim()

  const getSchemaCountSnapshot = (sourceId, schemaName) => {
    const sourceKey = String(sourceId || '')
    if (!sourceKey || !schemaName) return EMPTY_SCHEMA_COUNTS
    return schemaCountStore[sourceKey]?.[schemaName] || EMPTY_SCHEMA_COUNTS
  }

  const isSchemaTablesLoaded = (sourceId, database) => {
    const sourceKey = String(sourceId || '')
    return Array.isArray(tableStore[sourceKey]?.[database])
  }

  const loadSchemaCounts = async (sourceId, keyword = searchKeyword.value, force = false) => {
    if (!sourceId) return false
    const sourceKey = String(sourceId)
    const normalizedKeyword = normalizeKeyword(keyword)
    if (!force && schemaCountStore[sourceKey] && schemaCountKeyword[sourceKey] === normalizedKeyword) {
      return true
    }

    const requestSeq = (schemaCountRequestSeq[sourceKey] || 0) + 1
    schemaCountRequestSeq[sourceKey] = requestSeq
    schemaCountLoading[sourceKey] = true
    try {
      const params = {}
      if (normalizedKeyword) {
        params.keyword = normalizedKeyword
      }
      const counts = await dorisClusterApi.getSchemaObjectCounts(sourceId, params)
      if (schemaCountRequestSeq[sourceKey] !== requestSeq) {
        return false
      }
      const normalizedStore = {}
      ;(Array.isArray(counts) ? counts : []).forEach((item) => {
        const schemaName = String(item?.schemaName || '')
        if (!schemaName) return
        normalizedStore[schemaName] = normalizeSchemaCounts(item)
      })
      schemaCountStore[sourceKey] = normalizedStore
      schemaCountKeyword[sourceKey] = normalizedKeyword
      return true
    } catch (error) {
      if (schemaCountRequestSeq[sourceKey] === requestSeq && !schemaCountStore[sourceKey]) {
        schemaCountStore[sourceKey] = {}
      }
      console.error('加载 schema 计数失败', error)
      return false
    } finally {
      if (schemaCountRequestSeq[sourceKey] === requestSeq) {
        schemaCountLoading[sourceKey] = false
      }
    }
  }

  const loadSchemas = async (sourceId, force = false) => {
    if (!sourceId) return false
    const key = String(sourceId)
    if (schemaStore[key] && !force) {
      activatedSources[key] = true
      await loadSchemaCounts(sourceId, searchKeyword.value)
      return true
    }
    schemaLoading[key] = true
    try {
      const activated = await activateDatasource(sourceId)
      if (!activated) return false
      const schemas = await dorisClusterApi.getDatabases(sourceId)
      schemaStore[key] = Array.isArray(schemas) ? schemas : []
      activatedSources[key] = true
      refreshDatasourceChildrenInTree(sourceId)
      await loadSchemaCounts(sourceId, searchKeyword.value, true)
      if (!activeSchema[key] && schemaStore[key].length) {
        activeSchema[key] = schemaStore[key][0]
      }
      return true
    } catch {
      ElMessage.error('加载数据库列表失败')
      return false
    } finally {
      schemaLoading[key] = false
    }
  }

  const loadTables = async (sourceId, database, force = false, refreshTree = true) => {
    if (!sourceId || !database) return false
    const sourceKey = String(sourceId)
    const sourceType = String(getDatasourceById(sourceKey)?.sourceType || '').toUpperCase()
    tableStore[sourceKey] = tableStore[sourceKey] || {}
    if (tableStore[sourceKey][database] && !force) return true
    const loadingKey = `${sourceKey}::${database}`
    tableLoading[loadingKey] = true
    try {
      const activated = await activateDatasource(sourceId)
      if (!activated) return false
      const [tables, metaTables] = await Promise.all([
        dorisClusterApi.getTables(sourceId, database),
        tableApi.listByDatabase(database, sortField.value, sortOrder.value, sourceId).catch(() => [])
      ])
      const metaList = Array.isArray(metaTables) ? metaTables : []
      const metaMap = new Map(metaList.map((item) => [item.tableName, item]))
      const list = (Array.isArray(tables) ? tables : []).map((item) => {
        const tableName = item.tableName || item.TABLE_NAME || ''
        const meta = metaMap.get(tableName)
        const base = {
          ...item,
          sourceId: sourceKey,
          sourceType,
          typeDefinition: getDatasourceById(sourceKey)?.typeDefinition,
          dbName: database,
          tableName,
          tableType: item.tableType || item.TABLE_TYPE || '',
          tableComment: item.tableComment || item.TABLE_COMMENT || '',
          rowCount: item.tableRows ?? item.table_rows ?? item.rowCount,
          storageSize: item.dataLength ?? item.data_length ?? item.storageSize,
          createdAt: item.createTime || item.CREATE_TIME || item.createdAt || meta?.dorisCreateTime || meta?.createdAt,
          dorisCreateTime: item.createTime || item.CREATE_TIME || meta?.dorisCreateTime || null,
          dorisUpdateTime: item.updateTime || item.UPDATE_TIME || meta?.dorisUpdateTime || null
        }
        if (!meta) {
          return {
            ...base,
            id: undefined,
            metadataMissing: true,
            metadataStatus: 'missing'
          }
        }
        return {
          ...meta,
          ...base,
          id: meta.id,
          tableComment: base.tableComment || meta.tableComment,
          metadataMissing: false,
          metadataStatus: 'synced'
        }
      })
      tableStore[sourceKey][database] = list
      if (refreshTree) {
        refreshSchemaChildrenInTree(sourceId, database)
        refreshObjectGroupChildrenInTree(sourceId, database, 'table')
        refreshObjectGroupChildrenInTree(sourceId, database, 'view')
      }
      return true
    } catch {
      ElMessage.error('加载表列表失败')
      return false
    } finally {
      tableLoading[loadingKey] = false
    }
  }

  const loadClusters = async () => {
    dbLoading.value = true
    try {
      const clusters = await dorisClusterApi.list()
      dataSources.value = Array.isArray(clusters) ? clusters : []
      if (!clusterId.value && dataSources.value.length) {
        const defaultCluster =
          dataSources.value.find((item) => item.isDefault === 1) || dataSources.value[0]
        clusterId.value = defaultCluster?.id || null
      }
      if (!activeSource.value && dataSources.value.length) {
        const defaultSource =
          dataSources.value.find((item) => item.isDefault === 1) || dataSources.value[0]
        activeSource.value = defaultSource?.id ? String(defaultSource.id) : ''
        if (activeSource.value) {
          const ok = await loadSchemas(activeSource.value)
          if (ok) {
            await nextTick()
            await ensureCatalogPathExpanded(activeSource.value, activeSchema[String(activeSource.value)])
          }
        }
      }
    } catch {
      ElMessage.error('加载数据源失败')
    } finally {
      dbLoading.value = false
    }
  }

  const handleSourceChange = async (sourceId) => {
    if (!sourceId) return
    await loadSchemas(sourceId)
  }

  const handleSchemaChange = async (sourceId, database) => {
    if (!sourceId || !database) return
    await loadTables(sourceId, database)
  }

  const getFilteredTables = (sourceId, database) => {
    const sourceKey = String(sourceId || '')
    const list = tableStore[sourceKey]?.[database] || []
    if (!searchKeyword.value) return list
    const keyword = searchKeyword.value.toLowerCase()
    return list.filter((item) => {
      return (
        item.tableName?.toLowerCase().includes(keyword) ||
        item.tableComment?.toLowerCase().includes(keyword)
      )
    })
  }

  const getDisplayedTables = (sourceId, database) => {
    const list = [...getFilteredTables(sourceId, database)]
    const field = sortField.value
    const order = sortOrder.value
    list.sort((a, b) => {
      const aVal = a[field]
      const bVal = b[field]
      if (aVal == null && bVal == null) return 0
      if (aVal == null) return order === 'asc' ? -1 : 1
      if (bVal == null) return order === 'asc' ? 1 : -1
      if (typeof aVal === 'number' && typeof bVal === 'number') {
        return order === 'asc' ? aVal - bVal : bVal - aVal
      }
      return order === 'asc'
        ? String(aVal).localeCompare(String(bVal))
        : String(bVal).localeCompare(String(aVal))
    })
    return list
  }

  const getTableCount = (sourceId, database) => {
    if (isSchemaTablesLoaded(sourceId, database)) {
      return getFilteredTables(sourceId, database).length
    }
    return getSchemaCountSnapshot(sourceId, database).totalCount
  }

  const normalizeTableType = (tableType) => {
    const normalized = String(tableType || '').trim().toUpperCase()
    return normalized || 'BASE TABLE'
  }

  const isViewTableType = (tableType) => normalizeTableType(tableType).includes('VIEW')
  const isViewTable = (table) => isViewTableType(table?.tableType)

  const getTableCountByType = (sourceId, database, objectType) => {
    if (isSchemaTablesLoaded(sourceId, database)) {
      return getFilteredTables(sourceId, database).filter((item) =>
        objectType === 'view' ? isViewTable(item) : !isViewTable(item)
      ).length
    }
    const snapshot = getSchemaCountSnapshot(sourceId, database)
    return objectType === 'view' ? snapshot.viewCount : snapshot.tableCount
  }

  const setTableRef = (key, el, tableId) => {
    if (!key || !el) return
    tableRefs.value[key] = el
    if (tableId) {
      el.dataset.tableId = String(tableId)
    }
    if (tableObserver.value) {
      tableObserver.value.observe(el)
    }
  }

  const getTableKey = (table, fallbackDb = '', fallbackSource = '') => {
    if (!table) return ''
    const sourceId = table.sourceId || table.clusterId || fallbackSource || ''
    const dbName = table.dbName || table.databaseName || table.database || fallbackDb || ''
    const tableName = table.tableName || ''
    const core = dbName && tableName ? `${dbName}.${tableName}` : tableName || dbName
    return sourceId ? `${sourceId}::${core}` : core
  }

  const findCachedTableById = (tableId) => {
    const targetId = String(tableId || '')
    if (!targetId) return null
    for (const sourceId of Object.keys(tableStore)) {
      const dbMap = tableStore[sourceId]
      if (!dbMap || typeof dbMap !== 'object') continue
      for (const dbName of Object.keys(dbMap)) {
        const list = Array.isArray(dbMap[dbName]) ? dbMap[dbName] : []
        const found = list.find((item) => item && String(item.id) === targetId)
        if (!found) continue
        return {
          ...found,
          sourceId: String(found.sourceId || found.clusterId || sourceId),
          dbName: found.dbName || dbName
        }
      }
    }
    return null
  }

  const buildSchemaNode = (sourceId, schemaName) => ({
    nodeKey: getSchemaNodeKey(sourceId, schemaName),
    type: 'schema',
    name: schemaName,
    sourceId: String(sourceId),
    schemaName,
    leaf: false
  })

  const buildObjectGroupNode = (sourceId, schemaName, objectType) => ({
    nodeKey: getObjectGroupNodeKey(sourceId, schemaName, objectType),
    type: 'object_group',
    objectType,
    name: objectType === 'view' ? '视图' : '表',
    sourceId: String(sourceId),
    schemaName,
    leaf: false
  })

  const isDatasourceIconInactive = (nodeData) => {
    if (!nodeData || nodeData.type !== 'datasource') return false
    if (nodeData.status && nodeData.status !== 'active') return true
    return !activatedSources[String(nodeData.sourceId)]
  }

  const isCatalogNodeActive = (nodeData) => {
    if (nodeData?.type === 'datasource') return !isDatasourceIconInactive(nodeData)
    if (nodeData?.type !== 'schema') return false
    const sourceId = String(nodeData.sourceId)
    const source = getDatasourceById(sourceId)
    if (source?.status && source.status !== 'active') return false
    // 已连接且成功加载对象列表的 Schema 为激活态，空库同样有效。
    return !!activatedSources[sourceId] && Array.isArray(tableStore[sourceId]?.[nodeData.schemaName])
  }

  const getDatasourceIconUrl = (sourceType) => {
    return dataSources.value.find(source => source.sourceType === sourceType)?.typeDefinition?.iconUrl || ''
  }

  const buildTableNode = (table, sourceId, schemaName) => {
    const key = getTableKey(table, schemaName, sourceId)
    return {
      nodeKey: key || `table:${String(sourceId)}::${schemaName}.${table?.tableName || ''}`,
      type: 'table',
      name: table?.tableName || '',
      sourceId: String(sourceId),
      schemaName,
      table,
      objectType: isViewTable(table) ? 'view' : 'table',
      leaf: true
    }
  }

  const parseTimeValue = (value) => {
    if (!value) return 0
    if (typeof value === 'number') return value
    const text = String(value)
    const parsed = Date.parse(text)
    if (!Number.isNaN(parsed)) return parsed
    const fallback = Date.parse(text.replace(' ', 'T'))
    return Number.isNaN(fallback) ? 0 : fallback
  }

  const getTableRowCount = (table) => {
    if (!table) return 0
    const value = table.rowCount ?? table.tableRows ?? table.table_rows
    if (value === null || value === undefined) return 0
    return Number(value) || 0
  }

  const getTableStorageSize = (table) => {
    if (!table) return 0
    const value = table.storageSize ?? table.dataLength ?? table.data_length
    if (value === null || value === undefined) return 0
    return Number(value) || 0
  }

  const getTableSortValue = (table) => {
    const field = sortField.value
    if (field === 'rowCount') return getTableRowCount(table)
    if (field === 'storageSize') return getTableStorageSize(table)
    if (field === 'dorisUpdateTime') {
      return parseTimeValue(table?.dorisUpdateTime)
    }
    if (field === 'createdAt') {
      return parseTimeValue(table?.dorisCreateTime ?? table?.createTime ?? table?.CREATE_TIME ?? table?.createdAt)
    }
    return String(table?.tableName || '').toLowerCase()
  }

  const getSortedTablesForTree = (sourceId, database, objectType = 'all') => {
    const sourceKey = String(sourceId || '')
    let list = [...(tableStore[sourceKey]?.[database] || [])]
    if (objectType === 'view') {
      list = list.filter((item) => isViewTable(item))
    } else if (objectType === 'table') {
      list = list.filter((item) => !isViewTable(item))
    }
    const order = sortOrder.value
    list.sort((a, b) => {
      const aVal = getTableSortValue(a)
      const bVal = getTableSortValue(b)
      if (aVal === bVal) return 0
      if (order === 'asc') return aVal > bVal ? 1 : -1
      return aVal < bVal ? 1 : -1
    })
    return list
  }

  const buildSchemaChildren = (sourceId, database) => ([
    buildObjectGroupNode(sourceId, database, 'table'),
    buildObjectGroupNode(sourceId, database, 'view')
  ])

  const buildTableChildren = (sourceId, database, objectType = 'all') =>
    getSortedTablesForTree(sourceId, database, objectType).map((table) => buildTableNode(table, sourceId, database))

  const refreshDatasourceChildrenInTree = (sourceId) => {
    const tree = catalogTreeRef.value
    if (!tree || !sourceId) return
    const key = getDatasourceNodeKey(sourceId)
    const node = tree.getNode(key)
    if (!node?.loaded) return
    const schemas = schemaStore[String(sourceId)] || []
    tree.updateKeyChildren(key, schemas.map((schemaName) => buildSchemaNode(sourceId, schemaName)))
    nextTick(() => tree.filter(searchKeyword.value))
  }

  const refreshSchemaChildrenInTree = (sourceId, database) => {
    const tree = catalogTreeRef.value
    if (!tree || !sourceId || !database) return
    const key = getSchemaNodeKey(sourceId, database)
    const node = tree.getNode(key)
    if (!node?.loaded) return
    tree.updateKeyChildren(key, buildSchemaChildren(sourceId, database))
    nextTick(() => tree.filter(searchKeyword.value))
  }

  const refreshObjectGroupChildrenInTree = (sourceId, database, objectType) => {
    const tree = catalogTreeRef.value
    if (!tree || !sourceId || !database || !objectType) return
    const key = getObjectGroupNodeKey(sourceId, database, objectType)
    const node = tree.getNode(key)
    if (!node?.loaded) return
    tree.updateKeyChildren(key, buildTableChildren(sourceId, database, objectType))
    nextTick(() => tree.filter(searchKeyword.value))
  }

  const reloadSchemaCountsForLoadedDatasources = async (keyword) => {
    const tree = catalogTreeRef.value
    if (!tree) return
    const loadedSources = dataSources.value
      .map((item) => String(item.id))
      .filter((sourceId) => tree.getNode(getDatasourceNodeKey(sourceId))?.loaded)
    if (!loadedSources.length) return
    await Promise.allSettled(
      loadedSources.map((sourceId) => loadSchemaCounts(sourceId, keyword, true))
    )
  }

  const filterCatalogNode = (value, data) => {
    if (!value) return true
    const keyword = String(value).toLowerCase()
    if (data?.type === 'datasource') {
      const nameMatched = String(data?.name || '').toLowerCase().includes(keyword)
      if (nameMatched) return true
      const schemas = schemaStore[String(data.sourceId)] || []
      return schemas.some((schemaName) => getTableCount(data.sourceId, schemaName) > 0)
    }
    if (data?.type === 'schema') {
      const nameMatched = String(data?.name || '').toLowerCase().includes(keyword)
      if (nameMatched) return true
      return getTableCount(data.sourceId, data.schemaName) > 0
    }
    if (data?.type === 'object_group') {
      const nameMatched = String(data?.name || '').toLowerCase().includes(keyword)
      if (nameMatched) return true
      return getTableCountByType(data.sourceId, data.schemaName, data.objectType) > 0
    }
    if (data?.type === 'table') {
      const name = String(data.table?.tableName || data.name || '').toLowerCase()
      const comment = String(data.table?.tableComment || '').toLowerCase()
      return name.includes(keyword) || comment.includes(keyword)
    }
    return String(data?.name || '').toLowerCase().includes(keyword)
  }

  const loadCatalogNode = async (node, resolve, reject) => {
    const data = node?.data
    if (!data?.type) {
      resolve([])
      return
    }

    if (data.type === 'datasource') {
      const ok = await loadSchemas(data.sourceId)
      if (!ok) {
        reject?.()
        return
      }
      const schemas = schemaStore[String(data.sourceId)] || []
      resolve(schemas.map((schemaName) => buildSchemaNode(data.sourceId, schemaName)))
      nextTick(() => catalogTreeRef.value?.filter(searchKeyword.value))
      return
    }

    if (data.type === 'schema') {
      resolve(buildSchemaChildren(data.sourceId, data.schemaName))
      nextTick(() => catalogTreeRef.value?.filter(searchKeyword.value))
      return
    }

    if (data.type === 'object_group') {
      // Keep current expand transition stable: do not rebuild schema/group nodes
      // while this group is being lazily expanded.
      const ok = await loadTables(data.sourceId, data.schemaName, false, false)
      if (!ok) {
        reject?.()
        return
      }
      resolve(buildTableChildren(data.sourceId, data.schemaName, data.objectType))
      nextTick(() => catalogTreeRef.value?.filter(searchKeyword.value))
      return
    }

    resolve([])
  }

  const isExpandIconClick = (event) => {
    const target = event?.target
    if (!target || typeof target.closest !== 'function') return false
    return Boolean(target.closest('.el-tree-node__expand-icon'))
  }

  const handleCatalogNodeClick = async (data, _node, _component, event) => {
    if (!data) return
    if (isExpandIconClick(event)) return
    const currentTab = activeTab.value
      ? openTabs.value.find((item) => String(item.id) === String(activeTab.value))
      : null

    if (currentTab?.kind === 'query') {
      if (data.type === 'datasource') {
        await handleQuerySourceSelect(currentTab.id, data.sourceId)
        return
      }
      if (data.type === 'schema') {
        await handleQuerySourceSelect(currentTab.id, data.sourceId)
        await handleQueryDatabaseSelect(currentTab.id, data.schemaName)
        return
      }
      if (data.type === 'object_group') {
        await handleQuerySourceSelect(currentTab.id, data.sourceId)
        await handleQueryDatabaseSelect(currentTab.id, data.schemaName)
        return
      }
    }
    if (data.type === 'table') {
      openTableTab(data.table, data.schemaName, data.sourceId)
    }
  }

  const expandCatalogNode = (key) => {
    return new Promise((resolve) => {
      const tree = catalogTreeRef.value
      if (!tree || !key) {
        resolve(false)
        return
      }
      const node = tree.getNode(key)
      if (!node) {
        resolve(false)
        return
      }
      if (node.expanded) {
        resolve(true)
        return
      }
      node.expand(() => resolve(true), true)
    })
  }

  const ensureCatalogPathExpanded = async (sourceId, schemaName) => {
    if (!catalogTreeRef.value || !sourceId) return
    await expandCatalogNode(getDatasourceNodeKey(sourceId))
    await nextTick()
    if (schemaName) {
      await expandCatalogNode(getSchemaNodeKey(sourceId, schemaName))
      await nextTick()
    }
  }

  const getProgressWidth = (sourceId, database, table) => {
    const sourceKey = String(sourceId || '')
    const list = tableStore[sourceKey]?.[database] || []
    if (!list.length) return '0%'
    const currentRowCount = getTableRowCount(table)
    const maxRowCount = Math.max(...list.map((item) => getTableRowCount(item)))
    if (!Number.isFinite(maxRowCount) || maxRowCount <= 0) {
      return '0%'
    }
    const percentage = Math.max(10, (currentRowCount / maxRowCount) * 100)
    return percentage.toFixed(1) + '%'
  }

  const refreshCatalog = async () => {
    if (dbLoading.value) return
    dbLoading.value = true
    try {
      const clusters = await dorisClusterApi.list()
      dataSources.value = Array.isArray(clusters) ? clusters : []
      const ids = new Set(dataSources.value.map((item) => String(item.id)))
      if (clusterId.value && !ids.has(String(clusterId.value))) {
        const fallback =
          dataSources.value.find((item) => item.isDefault === 1) || dataSources.value[0] || null
        clusterId.value = fallback?.id || null
      }
      if (activeSource.value && !ids.has(String(activeSource.value))) {
        const fallback =
          dataSources.value.find((item) => item.isDefault === 1) || dataSources.value[0] || null
        activeSource.value = fallback?.id ? String(fallback.id) : ''
      }
      await nextTick()
      const tree = catalogTreeRef.value
      if (!tree) return
      const loadedSources = dataSources.value
        .map((item) => String(item.id))
        .filter((sourceId) => tree.getNode(getDatasourceNodeKey(sourceId))?.loaded)

      for (const sourceId of loadedSources) {
        const ok = await loadSchemas(sourceId, true)
        if (!ok) continue
        const schemas = schemaStore[String(sourceId)] || []
        for (const schemaName of schemas) {
          const tableGroupLoaded = tree.getNode(getObjectGroupNodeKey(sourceId, schemaName, 'table'))?.loaded
          const viewGroupLoaded = tree.getNode(getObjectGroupNodeKey(sourceId, schemaName, 'view'))?.loaded
          if (tableGroupLoaded || viewGroupLoaded) {
            await loadTables(sourceId, schemaName, true)
          }
        }
      }
    } catch {
      ElMessage.error('刷新目录失败')
    } finally {
      dbLoading.value = false
    }
  }

  const refreshDatasourceNode = async (nodeData) => {
    const sourceId = nodeData?.sourceId
    if (!sourceId) return
    if (dbLoading.value || schemaLoading[String(sourceId)]) return
    await loadSchemas(sourceId, true)
  }

  const refreshSchemaNode = async (nodeData) => {
    const sourceId = nodeData?.sourceId
    const schemaName = nodeData?.schemaName
    if (!sourceId || !schemaName) return
    const key = `${String(sourceId)}::${schemaName}`
    if (dbLoading.value || schemaCountLoading[String(sourceId)] || tableLoading[key]) return
    await loadSchemaCounts(sourceId, searchKeyword.value, true)
    if (isSchemaTablesLoaded(sourceId, schemaName)) {
      await loadTables(sourceId, schemaName, true)
    } else {
      nextTick(() => catalogTreeRef.value?.filter(searchKeyword.value))
    }
  }

  const focusTableInSidebar = async (table, key, dbFallback = '', sourceFallback = '') => {
    if (!table) return
    const sourceId = table.sourceId || table.clusterId || sourceFallback
    const dbName = table.dbName || table.databaseName || table.database || dbFallback
    if (sourceId) {
      activeSource.value = String(sourceId)
      await loadSchemas(sourceId)
    }
    if (sourceId && dbName) {
      activeSchema[String(sourceId)] = dbName
    }
    await nextTick()
    await ensureCatalogPathExpanded(sourceId, dbName)
    if (sourceId && dbName) {
      await loadTables(sourceId, dbName)
      const objectType = isViewTable(table) ? 'view' : 'table'
      await expandCatalogNode(getObjectGroupNodeKey(sourceId, dbName, objectType))
      await nextTick()
      if (!catalogTreeRef.value?.getNode(key)) {
        const fallbackType = objectType === 'view' ? 'table' : 'view'
        await expandCatalogNode(getObjectGroupNodeKey(sourceId, dbName, fallbackType))
      }
    }
    catalogTreeRef.value?.setCurrentKey(key)
    await nextTick()
    const tableRef = tableRefs.value[key]
    if (tableRef?.scrollIntoView) {
      tableRef.scrollIntoView({ block: 'nearest' })
    }
  }

  const focusActiveTableInSidebar = async () => {
    const currentTab = openTabs.value.find((item) => String(item.id) === String(activeTab.value))
    if (!currentTab || currentTab.kind !== 'table') return
    const state = tabStates[String(currentTab.id)]
    const table = state?.table
    if (!table) return
    const sourceId = String(table.sourceId || table.clusterId || currentTab.sourceId || '')
    const dbName = table.dbName || table.databaseName || table.database || currentTab.dbName || ''
    const payload = { ...table, sourceId, dbName }
    const key = getTableKey(payload, dbName, sourceId)
    if (!key) return
    selectedTableKey.value = key
    await focusTableInSidebar(payload, key, dbName, sourceId)
  }

  watch(searchKeyword, (value) => {
    catalogTreeRef.value?.filter(value)
    if (schemaCountReloadTimer) {
      clearTimeout(schemaCountReloadTimer)
    }
    schemaCountReloadTimer = setTimeout(() => {
      schemaCountReloadTimer = null
      void (async () => {
        await reloadSchemaCountsForLoadedDatasources(value)
        catalogTreeRef.value?.filter(value)
      })()
    }, 300)
  })

  watch([sortField, sortOrder], async () => {
    Object.entries(tableStore).forEach(([sourceId, databases]) => {
      Object.keys(databases || {}).forEach((schemaName) => {
        refreshObjectGroupChildrenInTree(sourceId, schemaName, 'table')
        refreshObjectGroupChildrenInTree(sourceId, schemaName, 'view')
      })
    })
    await nextTick()
    if (selectedTableKey.value) catalogTreeRef.value?.setCurrentKey(selectedTableKey.value, false)
  })

  watch(selectedTableKey, (value) => {
    if (!value) return
    catalogTreeRef.value?.setCurrentKey(value, false)
  })

  onBeforeUnmount(() => {
    if (schemaCountReloadTimer) {
      clearTimeout(schemaCountReloadTimer)
      schemaCountReloadTimer = null
    }
  })

  return {
    dbLoading,
    dataSources,
    activeSource,
    schemaStore,
    schemaLoading,
    schemaCountLoading,
    activeSchema,
    tableLoading,
    tableStore,
    columnStore,
    catalogTreeRef,
    catalogTreeProps,
    catalogRoots,
    searchKeyword,
    sortField,
    sortOrder,
    selectedTableKey,
    tableRefs,
    loadClusters,
    getDatasourceById,
    activateDatasource,
    loadSchemas,
    loadTables,
    handleSourceChange,
    handleSchemaChange,
    getDisplayedTables,
    setTableRef,
    getTableKey,
    findCachedTableById,
    isDatasourceIconInactive,
    isCatalogNodeActive,
    getDatasourceIconUrl,
    isViewTable,
    getTableRowCount,
    getTableStorageSize,
    getTableCount,
    getTableCountByType,
    getProgressWidth,
    filterCatalogNode,
    loadCatalogNode,
    handleCatalogNodeClick,
    ensureCatalogPathExpanded,
    refreshCatalog,
    refreshDatasourceNode,
    refreshSchemaNode,
    focusTableInSidebar,
    focusActiveTableInSidebar,
  }
}
