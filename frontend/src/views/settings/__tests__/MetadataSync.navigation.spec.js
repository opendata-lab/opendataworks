import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'

vi.mock('@/api/doris', () => ({ dorisClusterApi: { list: vi.fn(), metadataScope: vi.fn(), getSyncHistory: vi.fn(), getSyncHistoryDetail: vi.fn(), saveMetadataSyncConfig: vi.fn() } }))
vi.mock('@/api/table', () => ({ tableApi: { listPendingDeletion: vi.fn() } }))
import { dorisClusterApi } from '@/api/doris'
import MetadataSync from '../MetadataSync.vue'

const sources = [
  { id: 1, clusterName: '本地 MySQL', sourceType: 'MYSQL', username: 'collector', feHost: 'localhost', fePort: 3306, status: 'active', autoSync: 0, syncCron: '' },
  { id: 2, clusterName: '分析仓库', sourceType: 'DORIS', username: 'warehouse', feHost: 'doris', fePort: 9030, status: 'inactive', autoSync: 1, syncCron: '0 0 2 * * ?' }
]
const mounted = []
async function setup(url = '/metadata-sync') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/metadata-sync', component: MetadataSync }, { path: '/settings', component: { template: '<div />' } }] })
  await router.push(url)
  const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [router, ElementPlus] } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
const click = async (wrapper, selector, text) => {
  await wrapper.findAll(selector).find(node => node.text() === text).trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  dorisClusterApi.list.mockImplementation(async () => sources.map(source => ({ ...source })))
  dorisClusterApi.metadataScope.mockImplementation(async (_id, database) => database ? { tables: [{ tableName: 'orders' }] } : { databases: ['retail'] })
  dorisClusterApi.getSyncHistory.mockResolvedValue({ records: [], total: 0 })
})
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()) })

describe('元数据采集列表与详情交互', () => {
  it('从列表搜索采集账号，进入配置并返回列表，不自动采集', async () => {
    const { wrapper, router } = await setup()
    expect(wrapper.find('.collection-source-table').exists()).toBe(true)
    expect(dorisClusterApi.metadataScope).not.toHaveBeenCalled()
    await wrapper.find('input[aria-label="搜索采集数据源"]').setValue('collector')
    await flushPromises()
    expect(wrapper.findAll('.collection-source-table .el-table__body tbody tr')).toHaveLength(1)
    await click(wrapper, 'button', '配置采集')
    expect(router.currentRoute.value.query).toEqual({ sourceId: '1', tab: 'basic' })
    expect(wrapper.find('.el-tabs__item.is-active').text()).toBe('基本信息')
    expect(wrapper.find('.source-summary').text()).toContain('collector')
    await click(wrapper, 'button', '返回列表')
    expect(router.currentRoute.value.query).toEqual({})
    expect(wrapper.find('.collection-source-table').exists()).toBe(true)
  })

  it('库表深链进入数据范围；切换页签保留范围及未保存的周期配置', async () => {
    const { wrapper, router } = await setup('/metadata-sync?sourceId=1&database=retail&tableName=orders')
    expect(wrapper.find('.el-tabs__item.is-active').text()).toBe('数据范围')
    expect(wrapper.find('#pane-scope').text()).toContain('orders')
    await click(wrapper, '.el-tabs__item', '基本信息')
    await wrapper.find('#pane-basic input[type="radio"][value="1"]').setValue(true)
    await wrapper.find('#pane-basic input[placeholder="0 0 2 * * ?"]').setValue('0 0 3 * * ?')
    await click(wrapper, '.el-tabs__item', '数据范围')
    await click(wrapper, '.el-tabs__item', '基本信息')
    expect(wrapper.find('#pane-basic input[placeholder="0 0 2 * * ?"]').element.value).toBe('0 0 3 * * ?')
    expect(router.currentRoute.value.query.tableName).toBe('orders')
    expect(dorisClusterApi.metadataScope).toHaveBeenCalledTimes(2)
    expect(dorisClusterApi.saveMetadataSyncConfig).not.toHaveBeenCalled()
  })

  it('运行记录深链展示真实历史并打开明细', async () => {
    dorisClusterApi.getSyncHistory.mockResolvedValue({ records: [{ id: 7, status: 'SUCCESS', scopeTarget: 'retail.orders' }], total: 1 })
    dorisClusterApi.getSyncHistoryDetail.mockResolvedValue({ id: 7, status: 'SUCCESS', scopeTarget: 'retail.orders' })
    const { wrapper } = await setup('/metadata-sync?sourceId=1&tab=history')
    expect(wrapper.find('.el-tabs__item.is-active').text()).toBe('运行记录')
    expect(dorisClusterApi.getSyncHistory).toHaveBeenCalledWith(1, { pageNum: 1, pageSize: 20 })
    expect(wrapper.find('#pane-history').text()).toContain('retail.orders')
    await click(wrapper, '#pane-history button', '详情')
    expect(dorisClusterApi.getSyncHistoryDetail).toHaveBeenCalledWith(1, 7)
  })

  it('运行记录加载失败时显示错误并可刷新重试', async () => {
    dorisClusterApi.getSyncHistory.mockRejectedValueOnce(new Error('连接失败'))
    const { wrapper } = await setup('/metadata-sync?sourceId=1&tab=history')
    expect(wrapper.find('#pane-history .el-alert').text()).toContain('加载运行记录失败：连接失败')
    expect(wrapper.find('#pane-history .el-table').exists()).toBe(false)
    await click(wrapper, 'button', '刷新记录')
    expect(wrapper.find('#pane-history .el-alert').exists()).toBe(false)
    expect(wrapper.find('#pane-history .el-table').exists()).toBe(true)
  })

  it('切换源后忽略迟到的旧源运行记录', async () => {
    let resolveOld
    dorisClusterApi.getSyncHistory.mockImplementation(id => id === 1 ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve({ records: [{ id: 9, status: 'FAILED', scopeTarget: 'warehouse.events' }], total: 1 }))
    const { wrapper, router } = await setup('/metadata-sync?sourceId=1&tab=history')
    await router.push('/metadata-sync?sourceId=2&tab=history')
    await flushPromises()
    resolveOld({ records: [{ id: 7, status: 'SUCCESS', scopeTarget: 'retail.orders' }], total: 1 })
    await flushPromises()
    expect(wrapper.find('#pane-history').text()).toContain('warehouse.events')
    expect(wrapper.find('#pane-history').text()).not.toContain('retail.orders')
  })
})
