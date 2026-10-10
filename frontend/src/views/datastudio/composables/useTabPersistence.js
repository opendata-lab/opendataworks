import { computed, ref, watch } from 'vue'

// Data Studio 工作区 Tab 状态的本地持久化（P2-2 F4）。
// 从 DataStudioNew.vue 逐字抽出，行为保持不变：
// - 监听快照变化，去抖（250ms）写入 localStorage；
// - 恢复时按版本校验，重建 tabStates / openTabs / activeTab / queryTabCounter。
// 通过参数注入共享响应式状态与 createTabState，保持与组件同一引用，响应式不变。
export function useTabPersistence({
  openTabs,
  activeTab,
  tabStates,
  queryTabCounter,
  createTabState,
  storageKey,
}) {
  const isRestoringTabs = ref(false)
  let persistTabsTimer = null

  const tabsPersistSnapshot = computed(() => {
    const tabs = (Array.isArray(openTabs.value) ? openTabs.value : []).map((tab) => {
      const id = String(tab?.id ?? '')
      const state = id ? tabStates[id] : null
      return {
        id,
        kind: tab?.kind === 'query' ? 'query' : 'table',
        tableName: tab?.tableName || '',
        dbName: tab?.dbName || state?.table?.dbName || '',
        sourceId: tab?.sourceId || state?.table?.sourceId || '',
        sourceType: state?.table?.sourceType || '',
        tableId: state?.table?.id || null,
        sql: state?.query?.sql ?? '',
        querySourceId: state?.query?.sourceId,
        queryDatabase: state?.query?.dbName,
        limit: Number(state?.query?.limit ?? 200)
      }
    })
    return {
      version: 1,
      activeTab: String(activeTab.value || ''),
      tabs
    }
  })

  const persistTabsNow = (snapshot) => {
    try {
      const tabs = snapshot?.tabs || []
      if (!Array.isArray(tabs) || tabs.length === 0) {
        localStorage.removeItem(storageKey)
        return
      }
      localStorage.setItem(storageKey, JSON.stringify(snapshot))
    } catch (error) {
      console.warn('保存工作区 Tab 状态失败', error)
    }
  }

  const schedulePersistTabs = (snapshot) => {
    if (persistTabsTimer) {
      clearTimeout(persistTabsTimer)
    }
    persistTabsTimer = setTimeout(() => {
      persistTabsTimer = null
      persistTabsNow(snapshot)
    }, 250)
  }

  const flushPersistTabs = () => {
    if (persistTabsTimer) {
      clearTimeout(persistTabsTimer)
      persistTabsTimer = null
    }
    persistTabsNow(tabsPersistSnapshot.value)
  }

  const restoreTabsFromStorage = () => {
    let parsed = null
    try {
      const raw = localStorage.getItem(storageKey)
      if (!raw) return false
      parsed = JSON.parse(raw)
    } catch (error) {
      console.warn('读取工作区 Tab 状态失败', error)
      return false
    }

    if (!parsed || parsed.version !== 1 || !Array.isArray(parsed.tabs)) return false

    isRestoringTabs.value = true
    try {
      const nextTabs = []
      const existingKeys = Object.keys(tabStates)
      existingKeys.forEach((key) => delete tabStates[key])

      parsed.tabs.forEach((item) => {
        const id = String(item?.id ?? '')
        if (!id) return
        const kind = item?.kind === 'query' ? 'query' : 'table'
        const tabItem = {
          id,
          kind,
          tableName: String(item?.tableName ?? ''),
          dbName: String(item?.dbName ?? ''),
          sourceId: String(item?.sourceId ?? ''),
          sourceType: String(item?.sourceType ?? '')
        }

        const tablePayload =
          kind === 'query'
            ? { tableName: '', dbName: tabItem.dbName, sourceId: tabItem.sourceId, sourceType: tabItem.sourceType }
            : { id: item?.tableId || undefined, tableName: tabItem.tableName, dbName: tabItem.dbName, sourceId: tabItem.sourceId, sourceType: tabItem.sourceType }

        tabStates[id] = createTabState(tablePayload)
        if (typeof item?.sql === 'string') {
          tabStates[id].query.sql = item.sql
        }
        if (typeof item?.querySourceId === 'string') {
          tabStates[id].query.sourceId = item.querySourceId
        }
        if (typeof item?.queryDatabase === 'string') {
          tabStates[id].query.dbName = item.queryDatabase
        }
        if (Number.isFinite(Number(item?.limit))) {
          tabStates[id].query.limit = Number(item.limit)
        }

        nextTabs.push(tabItem)
      })

      openTabs.value = nextTabs

      const active = String(parsed?.activeTab ?? '')
      const activeExists = active && nextTabs.some((tab) => String(tab.id) === active)
      activeTab.value = activeExists ? active : (nextTabs[0] ? String(nextTabs[0].id) : '')

      const maxQueryIndex = nextTabs
        .filter((tab) => tab.kind === 'query')
        .map((tab) => {
          const match = String(tab.tableName || '').match(/(\d+)$/)
          return match ? Number(match[1]) : 0
        })
        .reduce((max, val) => (Number.isFinite(val) ? Math.max(max, val) : max), 0)
      queryTabCounter.value = maxQueryIndex ? maxQueryIndex + 1 : 1

      return true
    } finally {
      isRestoringTabs.value = false
    }
  }

  watch(
    tabsPersistSnapshot,
    (snapshot) => {
      if (isRestoringTabs.value) return
      schedulePersistTabs(snapshot)
    },
    { deep: true }
  )

  return { isRestoringTabs, tabsPersistSnapshot, flushPersistTabs, restoreTabsFromStorage }
}
