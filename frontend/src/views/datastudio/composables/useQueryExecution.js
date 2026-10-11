import { markRaw, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { dataQueryApi } from '@/api/query'
import { buildCsvContent } from '../csvExport'
import { buildResultGridRows } from '../components/resultGridModel'
import { abbreviateSql } from '../tableFormat'
import { buildExplainSql, splitSqlStatements } from '../sqlStatements'

const INFO_TAB_NAME = 'info'
const RESULT_TYPE_RESULT_SET = 'RESULT_SET'
const RESULT_TYPE_UPDATE_COUNT = 'UPDATE_COUNT'

const EMPTY_RESULT_SET = Object.freeze({
  index: 1,
  statementIndex: 1,
  status: 'SUCCESS',
  resultType: RESULT_TYPE_RESULT_SET,
  affectedRows: null,
  message: '',
  sqlSnippet: '',
  durationMs: 0,
  columns: [],
  rows: [],
  hasMore: false,
  previewRowCount: 0
})

const buildRunningStatementInfos = (sqlText) => {
  const statements = splitSqlStatements(sqlText)
  return statements.map((statement, idx) => ({
    statementIndex: idx + 1,
    status: idx === 0 ? 'RUNNING' : 'PENDING',
    durationMs: 0,
    sqlSnippet: abbreviateSql(statement),
    resultInfo: idx === 0 ? '正在执行' : '等待执行'
  }))
}

const buildStatementInfosFromResultSets = (resultSets) => {
  const sets = Array.isArray(resultSets) ? resultSets : []
  return sets.map((set, idx) => {
    const status = String(set?.status || (set?.resultType === 'ERROR' ? 'ERROR' : 'SUCCESS')).toUpperCase()
    let resultInfo = set?.message || ''
    if (!resultInfo) {
      if (String(set?.resultType || '') === RESULT_TYPE_UPDATE_COUNT) {
        const affected = set?.affectedRows
        resultInfo = affected === null || affected === undefined ? '语句执行成功' : `影响 ${affected} 行`
      } else {
        const rows = Array.isArray(set?.rows) ? set.rows.length : 0
        resultInfo = `返回 ${rows} 行`
      }
    }
    return {
      statementIndex: Number(set?.statementIndex || idx + 1),
      status,
      durationMs: Number(set?.durationMs || 0),
      sqlSnippet: set?.sqlSnippet || '',
      resultInfo
    }
  })
}

const getResultRowKeyPrefix = (tabId, resultIndex) => `${String(tabId)}::${Number(resultIndex)}`

const normalizeResultSetForDisplay = (resultSet, tabId, resultIndex) => {
  const rows = Array.isArray(resultSet?.rows) ? resultSet.rows : []
  const columns = Array.isArray(resultSet?.columns) ? resultSet.columns : []
  return markRaw({
    ...resultSet,
    columns,
    rows: markRaw(buildResultGridRows(rows, getResultRowKeyPrefix(tabId, resultIndex))),
    hasMore: !!resultSet?.hasMore,
    previewRowCount: Number.isFinite(Number(resultSet?.previewRowCount))
      ? Number(resultSet.previewRowCount)
      : rows.length
  })
}

const normalizeResultSetsForDisplay = (resultSets, tabId) => {
  const sets = Array.isArray(resultSets) ? resultSets : []
  return markRaw(sets.map((set, idx) => normalizeResultSetForDisplay(set, tabId, idx)))
}

export function buildDefaultSql(table) {
  if (!table?.dbName || !table?.tableName) return ''
  return `SELECT *\nFROM \`${table.dbName}\`.\`${table.tableName}\`\nLIMIT 200;`
}

const parseAutoSelectSql = (sql) => {
  const text = String(sql || '').trim()
  if (!text) return null
  const match = text.match(/^select\s+\*\s+from\s+`([^`]+)`\.`([^`]+)`\s+limit\s+(\d+)\s*;?$/i)
  if (!match) return null
  return { schema: match[1], table: match[2], limit: Number(match[3]) }
}

export function useQueryExecution({
  clusterId,
  activeSource,
  activeSchema,
  schemaStore,
  tabStates,
  openTabs,
  activeTab,
  loadSchemas,
  loadTables,
  syncRouteWithTab,
  disposeChart,
  applyDefaultChartSelection,
  syncResultPaneLayout,
}) {
  const historyData = ref([])
  const historyPager = reactive({ pageNum: 1, pageSize: 15, total: 0 })
  const historyLoading = ref(false)
  const queryTimerHandles = new Map()
  const nowTick = ref(Date.now())
  let nowTickHandle = null

  const startNowTicker = () => {
    if (nowTickHandle) return
    nowTickHandle = setInterval(() => {
      nowTick.value = Date.now()
    }, 200)
  }

  const stopNowTickerIfIdle = () => {
    if (!nowTickHandle) return
    const hasCancelable = Object.values(tabStates).some((state) => !!state?.queryCancelable)
    if (hasCancelable) return
    clearInterval(nowTickHandle)
    nowTickHandle = null
  }

  const clearQueryTimer = (tabId) => {
    const handle = queryTimerHandles.get(tabId)
    if (!handle) return
    clearInterval(handle)
    queryTimerHandles.delete(tabId)
  }

  const startQueryTimer = (tabId) => {
    clearQueryTimer(tabId)
    const state = tabStates[tabId]
    if (!state) return
    state.queryTiming.startedAt = Date.now()
    state.queryTiming.elapsedMs = 0
    startNowTicker()
    const handle = setInterval(() => {
      const current = tabStates[tabId]
      if (!current?.queryCancelable) {
        clearQueryTimer(tabId)
        stopNowTickerIfIdle()
        return
      }
      current.queryTiming.elapsedMs = Date.now() - current.queryTiming.startedAt
    }, 200)
    queryTimerHandles.set(tabId, handle)
  }

  const getStatementStatusTagType = (status) => {
    const value = String(status || '').toUpperCase()
    if (value === 'SUCCESS') return 'success'
    if (value === 'RUNNING') return 'info'
    if (value === 'BLOCKED' || value === 'ERROR') return 'danger'
    if (value === 'SKIPPED') return 'warning'
    return 'info'
  }

  const isResultSetType = (resultSet) => String(resultSet?.resultType || RESULT_TYPE_RESULT_SET) === RESULT_TYPE_RESULT_SET

  const getResultSetCountText = (resultSet) => {
    const type = String(resultSet?.resultType || RESULT_TYPE_RESULT_SET)
    if (type === RESULT_TYPE_UPDATE_COUNT) {
      const affected = resultSet?.affectedRows
      return affected === null || affected === undefined ? '影响行数未知' : `影响 ${affected} 行`
    }
    return `${(resultSet?.rows || []).length} 行`
  }

  const getResultSetAlertType = (resultSet) => {
    const status = String(resultSet?.status || '').toUpperCase()
    if (status === 'ERROR' || status === 'BLOCKED') return 'error'
    if (status === 'SKIPPED') return 'warning'
    return 'success'
  }

  const getDisplayResultSets = (tabId) => {
    const state = tabStates[tabId]
    const sets = Array.isArray(state?.queryResult?.resultSets) ? state.queryResult.resultSets : []
    return sets.length ? sets : [EMPTY_RESULT_SET]
  }

  const getTabItemById = (tabId) => {
    return openTabs.value.find((tab) => String(tab.id) === String(tabId)) || null
  }

  const handleSqlSelectionChange = (tabId, payload) => {
    const state = tabStates[String(tabId || '')]
    if (!state) return
    state.query.selectionText = payload?.text ?? ''
    state.query.hasSelection = !!payload?.hasSelection
  }

  const handleQuerySourceSelect = async (tabId, value) => {
    const state = tabStates[tabId]
    const tab = getTabItemById(tabId)
    if (!state || !tab) return
    if (tab.kind !== 'query') {
      const sourceId = value ? String(value) : ''
      state.query.sourceId = sourceId
      state.query.dbName = ''
      if (sourceId && await loadSchemas(sourceId) && state.query.sourceId === sourceId) {
        state.query.dbName = activeSchema[sourceId] || schemaStore[sourceId]?.[0] || ''
        if (state.query.dbName) await loadTables(sourceId, state.query.dbName)
      }
      return
    }

    const sourceId = value ? String(value) : ''
    state.table.sourceId = sourceId
    tab.sourceId = sourceId

    state.table.dbName = ''
    state.table.tableName = ''
    state.table.id = undefined
    tab.dbName = ''

    if (String(activeTab.value) === String(tabId)) {
      clusterId.value = sourceId || null
      activeSource.value = sourceId
    }

    if (!sourceId) {
      if (String(activeTab.value) === String(tabId)) {
        syncRouteWithTab(tab, tabId)
      }
      return
    }

    const ok = await loadSchemas(sourceId)
    if (!ok) return

    const nextDb = activeSchema[sourceId] || schemaStore[sourceId]?.[0] || ''
    if (nextDb) {
      state.table.dbName = nextDb
      tab.dbName = nextDb
      activeSchema[sourceId] = nextDb
      await loadTables(sourceId, nextDb)
    }

    if (String(activeTab.value) === String(tabId)) {
      syncRouteWithTab(tab, tabId)
    }
  }

  const handleQueryDatabaseSelect = async (tabId, value) => {
    const state = tabStates[tabId]
    const tab = getTabItemById(tabId)
    if (!state || !tab) return
    if (tab.kind !== 'query') {
      state.query.dbName = value ? String(value) : ''
      const sourceId = state.query.sourceId ?? state.table.sourceId
      if (sourceId && state.query.dbName) await loadTables(sourceId, state.query.dbName)
      return
    }

    const dbName = value ? String(value) : ''
    state.table.dbName = dbName
    tab.dbName = dbName

    state.table.tableName = ''
    state.table.id = undefined

    const sourceId = String(state.table.sourceId || tab.sourceId || '')
    if (sourceId && dbName) {
      activeSchema[sourceId] = dbName
      await loadTables(sourceId, dbName)
    }

    if (String(activeTab.value) === String(tabId)) {
      clusterId.value = sourceId || null
      activeSource.value = sourceId
      syncRouteWithTab(tab, tabId)
    }
  }

  const syncAutoSelectSqlIfSchemaMismatch = (state) => {
    if (!state?.table?.dbName || !state?.table?.tableName) return
    const nextDefault = buildDefaultSql(state.table)
    if (!String(state.query?.sql || '').trim()) {
      state.query.sql = nextDefault
      return
    }
    const parsed = parseAutoSelectSql(state.query.sql)
    if (!parsed) return
    if (parsed.table === state.table.tableName && parsed.schema !== state.table.dbName) {
      state.query.sql = nextDefault
    }
  }

  const executeQuery = async (tabId, { explain = false } = {}) => {
    const state = tabStates[tabId]
    if (!state || state.queryStopping) return
    const runId = Number(state.queryRunId || 0) + 1
    state.queryRunId = runId
    const selectedSql = String(state?.query?.selectionText || '')
    const sqlText = selectedSql.trim() ? selectedSql : String(state?.query?.sql || '')
    const sqlToRun = explain ? buildExplainSql(sqlText) : sqlText
    const executionDatabase = state.query.dbName ?? state.table?.dbName
    if (!sqlToRun.trim()) {
      state.queryResult.errorMessage = '请输入 SQL'
      state.queryResult.message = ''
      state.resultTab = INFO_TAB_NAME
      return
    }
    if (!executionDatabase) {
      state.queryResult.errorMessage = '请先选择数据库'
      state.queryResult.message = ''
      state.resultTab = INFO_TAB_NAME
      return
    }
    const sourceId = state.query.sourceId ?? state.table?.sourceId ?? clusterId.value
    if (!sourceId) {
      state.queryResult.errorMessage = '请选择数据源'
      state.queryResult.message = ''
      state.resultTab = INFO_TAB_NAME
      return
    }

    let analyzeRes = null
    try {
      analyzeRes = await dataQueryApi.analyze({
        clientQueryId: String(tabId),
        clusterId: sourceId || undefined,
        database: executionDatabase || undefined,
        sql: sqlToRun
      })
    } catch (error) {
      const message = error?.response?.data?.message || error?.message || 'SQL 分析失败'
      state.queryResult = {
        resultSets: [],
        columns: [],
        rows: [],
        hasMore: false,
        durationMs: 0,
        executedAt: '',
        cancelled: false,
        statementInfos: [
          {
            statementIndex: 1,
            status: 'ERROR',
            durationMs: 0,
            sqlSnippet: abbreviateSql(sqlToRun),
            resultInfo: message
          }
        ],
        message: '',
        errorMessage: message
      }
      state.resultTab = INFO_TAB_NAME
      return
    }

    const blockedRiskItem = Array.isArray(analyzeRes?.riskItems)
      ? analyzeRes.riskItems.find((item) => item?.blocked)
      : null
    const blockedStatementIndex = Number(blockedRiskItem?.statementIndex || 0) || null
    const confirmChallenges = Array.isArray(analyzeRes?.confirmChallenges)
      ? [...analyzeRes.confirmChallenges]
        .filter((item) => {
          const idx = Number(item?.statementIndex || 0)
          return !blockedStatementIndex || (idx > 0 && idx < blockedStatementIndex)
        })
        .sort((a, b) => Number(a?.statementIndex || 0) - Number(b?.statementIndex || 0))
      : []
    const confirmations = []
    for (const challenge of confirmChallenges) {
      const expected = String(challenge?.targetObject || '').trim()
      try {
        const { value } = await ElMessageBox.prompt(
          `语句 #${challenge.statementIndex} 为高风险操作，请输入对象名确认执行：${expected}`,
          '高风险 SQL 强确认',
          {
            type: 'warning',
            confirmButtonText: '确认执行',
            cancelButtonText: '取消',
            inputValue: '',
            inputPlaceholder: expected,
            inputValidator: (input) => {
              if (String(input || '').trim() !== expected) {
                return `请输入对象名：${expected}`
              }
              return true
            }
          }
        )
        confirmations.push({
          statementIndex: Number(challenge?.statementIndex || 0),
          targetObject: expected,
          inputText: String(value || '').trim(),
          confirmToken: challenge?.confirmToken || ''
        })
      } catch (error) {
        if (error === 'cancel' || error === 'close') {
          break
        }
        const message = error?.response?.data?.message || error?.message || '强确认失败'
        state.queryResult = {
          resultSets: [],
          columns: [],
          rows: [],
          hasMore: false,
          durationMs: 0,
          executedAt: '',
          cancelled: false,
          statementInfos: [
            {
              statementIndex: Number(challenge?.statementIndex || 1),
              status: 'ERROR',
              durationMs: 0,
              sqlSnippet: challenge?.targetObject || abbreviateSql(sqlToRun),
              resultInfo: message
            }
          ],
          message: '',
          errorMessage: message
        }
        state.resultTab = INFO_TAB_NAME
        return
      }
    }

    if (state.queryAbortController) {
      try {
        state.queryAbortController.abort()
      } catch {
        // ignored
      }
    }
    state.queryAbortController = new AbortController()
    state.queryLoading = true
    state.queryStopping = false
    state.queryCancelable = true
    startNowTicker()
    state.queryResult.errorMessage = ''
    state.queryResult.message = ''
    state.queryResult.cancelled = false
    state.queryResult.durationMs = 0
    state.queryResult.executedAt = ''
    state.queryResult.statementInfos = buildRunningStatementInfos(sqlToRun)
    state.resultTab = INFO_TAB_NAME
    startQueryTimer(tabId)
    disposeChart(tabId)
    try {
      const res = await dataQueryApi.execute({
        clientQueryId: String(tabId),
        clusterId: sourceId || undefined,
        database: executionDatabase || undefined,
        sql: sqlToRun,
        limit: state.query.limit,
        confirmations
      }, { signal: state.queryAbortController?.signal })
      if (state.queryRunId !== runId) return

      const resultSets = Array.isArray(res.resultSets) ? res.resultSets : []
      const fallbackResultSet = {
        index: 1,
        statementIndex: 1,
        status: 'SUCCESS',
        resultType: RESULT_TYPE_RESULT_SET,
        affectedRows: null,
        message: res.message || '',
        sqlSnippet: abbreviateSql(sqlToRun),
        durationMs: Number(res.durationMs || 0),
        columns: res.columns || [],
        rows: res.rows || [],
        hasMore: !!res.hasMore,
        previewRowCount: (res.rows || []).length
      }
      const normalizedSets = normalizeResultSetsForDisplay(resultSets.length ? resultSets : [fallbackResultSet], tabId)
      const statementInfos = buildStatementInfosFromResultSets(normalizedSets)
      const hasFailure = normalizedSets.some((item) => {
        const status = String(item?.status || '').toUpperCase()
        return status === 'BLOCKED' || status === 'ERROR' || status === 'SKIPPED'
      })

      state.queryResult = {
        resultSets: normalizedSets,
        columns: normalizedSets[0]?.columns || [],
        rows: normalizedSets[0]?.rows || [],
        hasMore: res.hasMore,
        durationMs: res.durationMs,
        executedAt: res.executedAt,
        cancelled: !!res.cancelled,
        statementInfos,
        message: res.message || '',
        errorMessage: ''
      }
      state.queryCancelable = false
      state.queryAbortController = null
      stopNowTickerIfIdle()
      state.page.current = 1
      state.resultTab = !res.cancelled && !hasFailure ? 'result-0' : INFO_TAB_NAME
      state.charts = normalizedSets.map(() => ({
        type: 'bar',
        xAxis: '',
        yAxis: []
      }))
      state.resultViewTabs = normalizedSets.map((_, idx) => state.resultViewTabs?.[idx] || 'table')
      applyDefaultChartSelection(tabId)
      await nextTick()
      syncResultPaneLayout(tabId)
      fetchHistory()
    } catch (error) {
      if (state.queryRunId !== runId) return
      const isCanceled =
        String(error?.code || '') === 'ERR_CANCELED' ||
        String(error?.name || '') === 'CanceledError' ||
        /canceled/i.test(String(error?.message || ''))
      if (isCanceled) {
        return
      }
      const message = error?.response?.data?.message || error?.message || '查询失败'
      const hasResponse = !!error?.response
      const maybeStillRunning = !hasResponse
      if (!maybeStillRunning) {
        state.queryCancelable = false
      }
      if (!state.queryCancelable) {
        state.queryAbortController = null
        stopNowTickerIfIdle()
      }
      state.queryResult = {
        resultSets: [],
        columns: [],
        rows: [],
        hasMore: false,
        durationMs: 0,
        executedAt: '',
        cancelled: false,
        statementInfos: maybeStillRunning
          ? (Array.isArray(state.queryResult?.statementInfos) ? state.queryResult.statementInfos : buildRunningStatementInfos(sqlToRun))
          : [
            {
              statementIndex: 1,
              status: 'ERROR',
              durationMs: 0,
              sqlSnippet: abbreviateSql(sqlToRun),
              resultInfo: message
            }
          ],
        message: maybeStillRunning ? '查询请求超时/网络异常，可能仍在执行，可点击“停止”' : '',
        errorMessage: message
      }
      state.resultTab = INFO_TAB_NAME
      state.charts = [
        {
          type: 'bar',
          xAxis: '',
          yAxis: []
        }
      ]
      state.resultViewTabs = ['table']
    } finally {
      if (state.queryRunId !== runId) return
      state.queryLoading = false
      if (!state.queryCancelable) {
        clearQueryTimer(tabId)
      }
    }
  }

  const stopQuery = async (tabId) => {
    const state = tabStates[tabId]
    if (!state?.queryCancelable || state.queryStopping) return
    const runId = state.queryRunId
    const startedAt = Number(state.queryTiming?.startedAt || 0)
    state.queryStopping = true
    try {
      state.queryAbortController?.abort()
    } catch {
      // ignored
    }
    state.queryAbortController = null
    try {
      await dataQueryApi.stop({ clientQueryId: String(tabId) })
      if (state.queryRunId !== runId) return
      state.queryResult.durationMs = startedAt > 0 ? Math.max(0, Date.now() - startedAt) : 0
      state.queryResult.executedAt = dayjs().format('YYYY-MM-DD HH:mm:ss')
      state.queryCancelable = false
      state.queryLoading = false
      state.queryStopping = false
      clearQueryTimer(tabId)
      stopNowTickerIfIdle()
      state.queryResult.cancelled = true
      state.queryResult.message = '查询已停止'
      state.queryResult.errorMessage = ''
      const existingInfos = Array.isArray(state.queryResult.statementInfos) ? state.queryResult.statementInfos : []
      state.queryResult.statementInfos = existingInfos.map((item, idx) => {
        const status = String(item?.status || '').toUpperCase()
        if (status === 'SUCCESS' || status === 'ERROR' || status === 'BLOCKED') return item
        return {
          statementIndex: Number(item?.statementIndex || idx + 1),
          status: 'SKIPPED',
          durationMs: Number(item?.durationMs || 0),
          sqlSnippet: item?.sqlSnippet || '',
          resultInfo: '查询已停止'
        }
      })
      state.resultTab = INFO_TAB_NAME
    } catch (error) {
      if (state.queryRunId !== runId) return
      state.queryStopping = false
      const message = error?.response?.data?.message || error?.message || '停止失败'
      state.queryResult.errorMessage = message
      state.queryResult.message = ''
      state.resultTab = INFO_TAB_NAME
    }
  }

  const getLiveDurationMs = (tabId) => {
    const state = tabStates[String(tabId || '')]
    if (!state) return 0
    if (state.queryCancelable) {
      const startedAt = Number(state.queryTiming?.startedAt || 0)
      if (!Number.isFinite(startedAt) || startedAt <= 0) return 0
      return Math.max(0, nowTick.value - startedAt)
    }
    return Number(state.queryResult?.durationMs || 0)
  }

  const resetQuery = (tabId) => {
    const state = tabStates[tabId]
    if (!state) return
    state.query.sql = buildDefaultSql(state.table)
    state.query.selectionText = ''
    state.query.hasSelection = false
  }

  const exportResult = (tabId, resultIndex = 0) => {
    const state = tabStates[tabId]
    if (!state?.queryResult) return
    const idx = Number(resultIndex)
    const set = Array.isArray(state.queryResult.resultSets) ? state.queryResult.resultSets[idx] : null
    const columns = set?.columns || state.queryResult.columns || []
    const rows = set?.rows || state.queryResult.rows || []
    if (!rows.length || !columns.length) return

    const blob = new Blob([buildCsvContent(columns, rows)], { type: 'text/csv;charset=utf-8;' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = `export_${Date.now()}.csv`
    link.click()
  }

  const fetchHistory = async () => {
    historyLoading.value = true
    try {
      const res = await dataQueryApi.history({
        pageNum: historyPager.pageNum,
        pageSize: historyPager.pageSize
      })
      historyData.value = res.records || []
      historyPager.total = res.total || 0
    } catch (error) {
      console.error('加载历史查询失败', error)
    } finally {
      historyLoading.value = false
    }
  }

  const applyHistory = (row, tabId) => {
    const state = tabStates[tabId]
    if (!state || !row) return
    state.query.sql = row.sqlText || ''
    state.query.selectionText = ''
    state.query.hasSelection = false
    const tab = getTabItemById(tabId)
    if (tab?.kind !== 'query') {
      if (row.clusterId) {
        state.query.sourceId = String(row.clusterId)
        loadSchemas(row.clusterId)
      }
      if (row.databaseName) state.query.dbName = row.databaseName
      return
    }
    if (row.clusterId) {
      const sourceId = String(row.clusterId)
      clusterId.value = row.clusterId
      activeSource.value = sourceId
      loadSchemas(row.clusterId)
      if (tab?.kind === 'query') {
        tab.sourceId = sourceId
        state.table.sourceId = sourceId
        if (String(activeTab.value) === String(tabId)) {
          syncRouteWithTab(tab, tabId)
        }
      }
    }
    if (row.databaseName) {
      state.table.dbName = row.databaseName
      tab.dbName = row.databaseName
    }
    if (String(activeTab.value) === String(tabId)) {
      syncRouteWithTab(tab, tabId)
    }
  }

  const parseResultTabIndex = (value) => {
    const match = String(value || '').match(/^result-(\d+)$/)
    return match ? Number(match[1]) : null
  }

  const getResultSetByIndex = (tabId, resultIndex = 0) => {
    const state = tabStates[tabId]
    const sets = Array.isArray(state?.queryResult?.resultSets) ? state.queryResult.resultSets : []
    const set = sets[resultIndex] || EMPTY_RESULT_SET
    return {
      columns: Array.isArray(set?.columns) ? set.columns : [],
      rows: Array.isArray(set?.rows) ? set.rows : [],
      hasMore: !!set?.hasMore
    }
  }

  watch(
    () => [historyPager.pageNum, historyPager.pageSize],
    () => {
      fetchHistory()
    }
  )

  onBeforeUnmount(() => {
    queryTimerHandles.forEach((handle) => clearInterval(handle))
    queryTimerHandles.clear()
    if (nowTickHandle) {
      clearInterval(nowTickHandle)
      nowTickHandle = null
    }
  })

  return {
    historyData,
    historyPager,
    historyLoading,
    INFO_TAB_NAME,
    buildDefaultSql,
    clearQueryTimer,
    stopNowTickerIfIdle,
    getStatementStatusTagType,
    isResultSetType,
    getResultSetCountText,
    getResultSetAlertType,
    getDisplayResultSets,
    handleSqlSelectionChange,
    handleQuerySourceSelect,
    handleQueryDatabaseSelect,
    syncAutoSelectSqlIfSchemaMismatch,
    executeQuery,
    stopQuery,
    getLiveDurationMs,
    resetQuery,
    exportResult,
    fetchHistory,
    applyHistory,
    parseResultTabIndex,
    getResultRowKeyPrefix,
    getResultSetByIndex,
  }
}
