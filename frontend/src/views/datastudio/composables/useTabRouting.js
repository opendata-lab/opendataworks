import { useRoute, useRouter } from 'vue-router'
import { tableApi } from '@/api/table'

// Data Studio 标签页与 URL 路由同步（P2-2 F8）。
// 从 DataStudioNew.vue 逐字抽出，行为保持不变：把当前标签写入 query，清理 query，
// 以及从 query 还原并打开目标表标签。共享标签/目录状态与加载器注入，保持同一引用。
export function useTabRouting({
  suppressRouteSync,
  tabStates,
  openTabs,
  activeTab,
  activeSource,
  activeSchema,
  tableStore,
  loadSchemas,
  loadTables,
  openTableTab,
}) {
  const route = useRoute()
  const router = useRouter()

  const syncRouteWithTab = (tab, tabId) => {
    if (suppressRouteSync.value) return
    if (!tab) return
    const kind = tab.kind === 'query' ? 'query' : 'table'
    const id = String(tabId ?? tab.id ?? '')

    const nextQuery = { ...route.query }
    if (id) nextQuery.tab = id
    if (tab.sourceId) nextQuery.clusterId = String(tab.sourceId)
    if (tab.dbName) nextQuery.database = String(tab.dbName)

    if (kind === 'table') {
      const tableId = tabStates[id]?.table?.id
      if (tableId) nextQuery.tableId = String(tableId)
      else delete nextQuery.tableId
      if (tab.tableName) nextQuery.tableName = String(tab.tableName)
    } else {
      delete nextQuery.tableId
      delete nextQuery.tableName
    }

    router.replace({ path: route.path, query: nextQuery })
  }

  const clearRouteTabQuery = () => {
    if (suppressRouteSync.value) return
    const nextQuery = { ...route.query }
    delete nextQuery.tab
    delete nextQuery.tableId
    delete nextQuery.tableName
    router.replace({ path: route.path, query: nextQuery })
  }

  const clearCreateQuery = () => {
    if (!route.query.create) return
    const nextQuery = { ...route.query }
    delete nextQuery.create
    router.replace({ path: route.path, query: nextQuery })
  }

  const syncFromRoute = async () => {
    const { clusterId: routeClusterId, database, tableId, tableName } = route.query
    if (!routeClusterId || !database || (!tableId && !tableName)) return
    const currentTab = openTabs.value.find((item) => String(item.id) === String(activeTab.value))
    if (currentTab) {
      const sameSource = String(currentTab.sourceId || '') === String(routeClusterId)
      const sameDb = String(currentTab.dbName || '') === String(database)
      const sameName = !tableName || String(currentTab.tableName || '') === String(tableName)
      const currentId = tabStates[String(currentTab.id)]?.table?.id
      const sameId = !tableId || (currentId && String(currentId) === String(tableId))
      if (sameSource && sameDb && sameName && sameId) return
    }
    activeSource.value = String(routeClusterId)
    activeSchema[String(routeClusterId)] = database
    await loadSchemas(routeClusterId, true)
    await loadTables(routeClusterId, database, true)
    const list = tableStore[String(routeClusterId)]?.[database] || []
    let target = null
    if (tableId) {
      target = list.find((item) => String(item.id) === String(tableId))
    }
    if (!target && tableName) {
      target = list.find((item) => item.tableName === tableName)
    }
    if (!target && tableId) {
      try {
        const tableInfo = await tableApi.getById(tableId)
        if (tableInfo) {
          target = { ...tableInfo, sourceId: String(routeClusterId), dbName: database }
        }
      } catch (error) {
        console.error('路由表加载失败', error)
      }
    }
    if (!target) return
    const previousSuppression = suppressRouteSync.value
    suppressRouteSync.value = true
    try {
      await openTableTab(target, database, routeClusterId)
    } finally {
      suppressRouteSync.value = previousSuppression
    }
  }

  return { syncRouteWithTab, clearRouteTabQuery, clearCreateQuery, syncFromRoute }
}
