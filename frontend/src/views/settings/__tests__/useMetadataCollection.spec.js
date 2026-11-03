import { beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('@/api/doris', () => ({ dorisClusterApi: { metadataScope: vi.fn(), saveMetadataSyncConfig: vi.fn() } }))
vi.mock('@/api/table', () => ({ tableApi: { syncMetadata: vi.fn(), syncDatabaseMetadata: vi.fn(), syncTableMetadataByName: vi.fn(), listByDatabase: vi.fn() } }))
import { dorisClusterApi } from '@/api/doris'
import { tableApi } from '@/api/table'
import { useMetadataCollection } from '../composables/useMetadataCollection'
const source = { id: 1, username: 'collector', status: 'active', autoSync: 0 }
async function setupTable() {
  const state = useMetadataCollection()
  await state.selectSource({ ...source }, { database: 'dw', tableName: 'orders' })
  return state
}
beforeEach(() => {
  vi.resetAllMocks()
  dorisClusterApi.metadataScope.mockImplementation(async (id, database) => database ? { tables: [{ tableName: 'orders' }] } : { databases: ['dw'] })
})
describe('采集账号范围与结果', () => {
  it('保留个人可见但平台账号不可见的目标并禁止执行', async () => {
    dorisClusterApi.metadataScope.mockResolvedValue({ databases: ['public'] })
    const s = await setupTable()
    expect(s.database.value).toBe('dw')
    expect(s.tableName.value).toBe('orders')
    expect(s.unavailable.value).toContain('无法访问所选数据库')
    await s.run()
    expect(tableApi.syncTableMetadataByName).not.toHaveBeenCalled()
  })
  it('数据库可见但目标表不可见时同样阻止采集', async () => {
    dorisClusterApi.metadataScope.mockImplementation(async (id, db) => db ? { tables: [] } : { databases: ['dw'] })
    const s = await setupTable()
    expect(s.unavailable.value).toContain('无法访问所选表')
  })
  it('预览连接失败时不会使用其他账号或执行', async () => {
    dorisClusterApi.metadataScope.mockRejectedValue(new Error('Access denied'))
    const s = await setupTable()
    expect(s.unavailable.value).toContain('Access denied')
    await s.run()
    expect(tableApi.syncTableMetadataByName).not.toHaveBeenCalled()
  })
  it('丢弃切换数据源后的旧范围响应', async () => {
    let resolveOld
    dorisClusterApi.metadataScope.mockImplementation(id => id === 1 ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve({ databases: ['new'] }))
    const s = useMetadataCollection()
    const old = s.selectSource(source)
    await s.selectSource({ ...source, id: 2 })
    resolveOld({ databases: ['old'] })
    await old
    expect(s.databases.value).toEqual(['new'])
    expect(s.scopeLoading.value).toBe(false)
  })
  it('按指定库和表执行，成功必须在刷新目录中找到同一平台表', async () => {
    const s = await setupTable()
    tableApi.syncTableMetadataByName.mockResolvedValue({ status: 'SUCCESS', tableId: 99, syncRunId: 7 })
    tableApi.listByDatabase.mockResolvedValue([{ id: 99, tableName: 'orders' }])
    await s.run()
    expect(tableApi.syncTableMetadataByName).toHaveBeenCalledWith('dw', 'orders', 1)
    expect(s.result.value.status).toBe('SUCCESS')
  })
  it.each(['FAILED', 'PARTIAL'])('HTTP 成功但业务状态 %s 时不报成功', async status => {
    const s = await setupTable()
    tableApi.syncTableMetadataByName.mockResolvedValue({ status, errors: ['源账号看不到表'] })
    await s.run()
    expect(s.result.value.status).toBe(status)
    expect(s.result.value.message).toContain('源账号看不到表')
    expect(tableApi.listByDatabase).not.toHaveBeenCalled()
  })
  it('后端成功但目录缺失时保留运行记录并提示待核实', async () => {
    const s = await setupTable()
    tableApi.syncTableMetadataByName.mockResolvedValue({ status: 'SUCCESS', tableId: 99, syncRunId: 7 })
    tableApi.listByDatabase.mockResolvedValue([])
    await s.run()
    expect(s.result.value.status).toBe('UNVERIFIED')
    expect(s.result.value.syncRunId).toBe(7)
  })
  it('后端成功但刷新失败时不会错误标记采集失败', async () => {
    const s = await setupTable()
    tableApi.syncTableMetadataByName.mockResolvedValue({ status: 'SUCCESS', tableId: 99 })
    tableApi.listByDatabase.mockRejectedValue(new Error('网络错误'))
    await s.run()
    expect(s.result.value.status).toBe('UNVERIFIED')
    expect(s.result.value.message).toContain('刷新失败')
  })
  it('请求超时结果不明时引导查看历史且不自动重试', async () => {
    const s = await setupTable()
    tableApi.syncTableMetadataByName.mockRejectedValue(new Error('timeout'))
    await s.run()
    expect(s.result.value.status).toBe('UNKNOWN')
    expect(s.result.value.message).toContain('查看采集历史')
    expect(tableApi.syncTableMetadataByName).toHaveBeenCalledTimes(1)
  })
  it('保存采集配置只提交开关和 Cron', async () => {
    const s = await setupTable()
    dorisClusterApi.saveMetadataSyncConfig.mockResolvedValue({ ...source, autoSync: 1 })
    s.config.autoSync = 1
    s.config.syncCron = '0 0 2 * * ?'
    await s.saveConfig()
    expect(dorisClusterApi.saveMetadataSyncConfig).toHaveBeenCalledWith(1, { autoSync: 1, syncCron: '0 0 2 * * ?' })
  })
  it('停用源不可预览或采集', async () => {
    const s = useMetadataCollection()
    await s.selectSource({ ...source, status: 'inactive' })
    expect(s.unavailable.value).toContain('停用')
    expect(dorisClusterApi.metadataScope).not.toHaveBeenCalled()
  })
})
