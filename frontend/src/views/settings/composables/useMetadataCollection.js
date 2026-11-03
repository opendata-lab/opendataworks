import { computed, reactive, ref, watch } from 'vue'
import { dorisClusterApi } from '@/api/doris'
import { tableApi } from '@/api/table'

// 预览只走平台账号接口；请求序号防止切换数据源后旧结果覆盖当前范围。
export function useMetadataCollection() {
  const source = ref(null)
  const database = ref('')
  const tableName = ref('')
  const scopeType = ref('all')
  const databases = ref([])
  const tables = ref([])
  const scopeLoading = ref(false)
  const scopeError = ref('')
  const running = ref(false)
  const saving = ref(false)
  const result = ref(null)
  const config = reactive({ autoSync: 0, syncCron: '' })
  let scopeRequest = 0
  const unavailable = computed(() => {
    if (!source.value) return '请选择数据源'
    if (source.value.status !== 'active') return '数据源已停用，请在管理中心启用'
    if (scopeError.value) return scopeError.value
    if (scopeLoading.value) return '正在读取采集账号可见范围'
    if (!databases.value.length) return '采集账号没有可见数据库，请检查数据源账号权限'
    if (scopeType.value !== 'all' && !databases.value.includes(database.value)) return '采集账号无法访问所选数据库，请检查数据源账号权限'
    if (scopeType.value === 'table' && !tables.value.some(t => t.tableName === tableName.value)) return '采集账号无法访问所选表，请检查数据源账号权限'
    return ''
  })
  watch([scopeType, tableName], () => { result.value = null }, { flush: 'sync' })
  async function refreshScope() {
    const seq = ++scopeRequest
    const current = source.value
    databases.value = []
    tables.value = []
    scopeError.value = ''
    result.value = null
    if (!current || current.status !== 'active') { scopeLoading.value = false; return }
    scopeLoading.value = true
    try {
      const preview = await dorisClusterApi.metadataScope(current.id)
      if (seq !== scopeRequest) return
      databases.value = preview.databases || []
      if (database.value && databases.value.includes(database.value)) {
        const detail = await dorisClusterApi.metadataScope(current.id, database.value)
        if (seq !== scopeRequest) return
        tables.value = detail.tables || []
      }
    } catch (error) {
      if (seq === scopeRequest) scopeError.value = `无法读取采集账号可见范围：${error.message || '连接失败'}`
    } finally {
      if (seq === scopeRequest) scopeLoading.value = false
    }
  }
  async function selectSource(current, target = {}) {
    source.value = current || null
    database.value = target.database || ''
    tableName.value = target.tableName || ''
    scopeType.value = tableName.value ? 'table' : database.value ? 'database' : 'all'
    config.autoSync = current?.autoSync || 0
    config.syncCron = current?.syncCron || ''
    await refreshScope()
  }
  async function selectDatabase(value) {
    database.value = value || ''
    tableName.value = ''
    await refreshScope()
  }
  async function saveConfig() {
    if (!source.value || saving.value) return
    saving.value = true
    try {
      const updated = await dorisClusterApi.saveMetadataSyncConfig(source.value.id, { ...config })
      Object.assign(source.value, updated)
      return updated
    } finally { saving.value = false }
  }
  async function run() {
    if (unavailable.value || running.value || saving.value) return null
    const current = source.value
    const scope = scopeType.value
    const db = database.value
    const table = tableName.value
    running.value = true
    result.value = null
    try {
      const response = scope === 'table'
        ? await tableApi.syncTableMetadataByName(db, table, current.id)
        : scope === 'database'
          ? await tableApi.syncDatabaseMetadata(db, current.id)
          : await tableApi.syncMetadata(current.id)
      const status = String(response?.status || '').toUpperCase()
      const message = response?.errors?.join('；') || ''
      result.value = { ...response, status: ['SUCCESS', 'PARTIAL', 'FAILED'].includes(status) ? status : 'UNKNOWN', message }
      // 单表采集必须同时验证刷新后的平台目录，不能只相信 HTTP 或同步返回。
      if (status === 'SUCCESS' && scope === 'table') {
        try {
          const catalog = await tableApi.listByDatabase(db, undefined, undefined, current.id)
          const found = response.tableId && catalog.some(t => String(t.id) === String(response.tableId) && t.tableName === table)
          if (!found) result.value = { ...result.value, status: 'UNVERIFIED', message: '后端报告采集成功，但刷新后的平台目录未找到目标表。请查看采集历史并刷新确认。' }
        } catch (error) {
          result.value = { ...result.value, status: 'UNVERIFIED', message: `后端报告采集成功，但平台目录刷新失败：${error.message || '请稍后刷新确认'}` }
        }
      }
    } catch (error) {
      result.value = { status: 'UNKNOWN', message: `请求未取得确定结果：${error.message || '连接中断'}。请查看采集历史确认，避免重复执行。` }
    } finally { running.value = false }
    return result.value
  }
  return { source, database, tableName, scopeType, databases, tables, scopeLoading, scopeError, unavailable, running, saving, result, config, selectSource, selectDatabase, refreshScope, saveConfig, run }
}
