import { dorisClusterApi } from '@/api/doris'

// Data Studio SQL 编辑器补全数据源（P2-2 F6）。
// 从 DataStudioNew.vue 逐字抽出，行为保持不变：基于共享的 schema/table/column 缓存
// 提供库、表、字段与远端搜索；按 tab 装配补全上下文。
// 共享目录缓存与加载器（schemaStore/tableStore/columnStore/loadTables/activateDatasource）
// 仍由组件持有并注入，保持与目录树同一份状态（后续 F7 抽取目录树时再统一归位）。
export function useSqlCompletion({
  tabStates,
  schemaStore,
  tableStore,
  columnStore,
  loadTables,
  activateDatasource,
}) {
  const getSchemaOptions = (sourceId) => {
    const sid = String(sourceId || '')
    if (!sid) return []
    return schemaStore[sid] || []
  }

  const getCompletionTablesBySchema = (sourceId) => {
    const sourceKey = String(sourceId || '')
    if (!sourceKey) return {}
    return tableStore[sourceKey] || {}
  }

  const getColumnCacheKey = (sourceId, schema, tableName) =>
    `${String(sourceId || '')}::${String(schema || '')}::${String(tableName || '')}`

  const loadCompletionTables = async (sourceId, schema) => {
    const sourceKey = String(sourceId || '')
    const schemaName = String(schema || '')
    if (!sourceKey || !schemaName) return []
    await loadTables(sourceKey, schemaName)
    return tableStore[sourceKey]?.[schemaName] || []
  }

  const loadCompletionColumns = async (sourceId, schema, tableName) => {
    const sourceKey = String(sourceId || '')
    const schemaName = String(schema || '')
    const objectName = String(tableName || '')
    if (!sourceKey || !schemaName || !objectName) return []
    const cacheKey = getColumnCacheKey(sourceKey, schemaName, objectName)
    if (Array.isArray(columnStore[cacheKey])) {
      return columnStore[cacheKey]
    }
    try {
      const activated = await activateDatasource(sourceKey)
      if (!activated) return []
      const columns = await dorisClusterApi.getColumns(sourceKey, schemaName, objectName)
      columnStore[cacheKey] = Array.isArray(columns) ? columns : []
      return columnStore[cacheKey]
    } catch (error) {
      console.error('加载 SQL 补全字段失败', error)
      columnStore[cacheKey] = []
      return []
    }
  }

  const searchCompletionTables = async (sourceId, keyword) => {
    const sourceKey = String(sourceId || '')
    const normalizedKeyword = String(keyword || '').trim()
    if (!sourceKey || normalizedKeyword.length < 2) return []
    try {
      const activated = await activateDatasource(sourceKey)
      if (!activated) return []
      const objects = await dorisClusterApi.searchSchemaObjects(sourceKey, {
        keyword: normalizedKeyword,
        limit: 50
      })
      return Array.isArray(objects) ? objects : []
    } catch (error) {
      console.error('搜索 SQL 补全表失败', error)
      return []
    }
  }

  const getSqlCompletionContext = (tabId) => {
    const state = tabStates[String(tabId || '')]
    if (!state) return null
    const sourceId = String(state.query?.sourceId ?? state.table?.sourceId ?? '')
    if (!sourceId) return null
    const currentSchema = String(state.query?.dbName ?? state.table?.dbName ?? '')
    return {
      sourceId,
      currentSchema,
      schemas: getSchemaOptions(sourceId),
      tablesBySchema: getCompletionTablesBySchema(sourceId),
      loadTables: (schema) => loadCompletionTables(sourceId, schema),
      loadColumns: ({ schema, table }) => loadCompletionColumns(sourceId, schema, table),
      searchTables: (keyword) => searchCompletionTables(sourceId, keyword)
    }
  }

  return {
    getSchemaOptions,
    getCompletionTablesBySchema,
    getColumnCacheKey,
    loadCompletionTables,
    loadCompletionColumns,
    searchCompletionTables,
    getSqlCompletionContext,
  }
}
