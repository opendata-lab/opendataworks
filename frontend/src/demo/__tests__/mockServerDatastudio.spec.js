import { describe, expect, it } from 'vitest'
import { demoAdapter } from '../mockServer'

const request = async (method, url, options = {}) => {
  const response = await demoAdapter({
    method,
    url,
    baseURL: options.baseURL ?? '',
    params: options.params || {},
    data: options.data
  })
  return response.data
}

describe('demoAdapter DataStudio endpoints', () => {
  it('covers DataStudio catalog and table detail endpoints without backend access', async () => {
    await expect(request('get', '/api/v1/doris-clusters/1')).resolves.toMatchObject({
      code: 200,
      data: expect.objectContaining({ id: 1, clusterName: 'README 演示集群' })
    })
    await expect(request('get', '/v1/tables')).resolves.toMatchObject({
      code: 200,
      data: expect.objectContaining({ records: expect.any(Array) })
    })
    await expect(request('get', '/api/v1/tables/all')).resolves.toMatchObject({
      code: 200,
      data: expect.arrayContaining([
        expect.objectContaining({ tableName: 'demo_order_detail' })
      ])
    })
  })

  it('covers DataStudio statistics, history, and metadata helper endpoints', async () => {
    await expect(request('get', '/v1/tables/1234/statistics')).resolves.toMatchObject({
      code: 200,
      data: expect.objectContaining({ tableId: 1234 })
    })
    await expect(request('get', '/v1/tables/statistics/database/opendataworks')).resolves.toMatchObject({
      code: 200,
      data: expect.objectContaining({ database: 'opendataworks' })
    })
    await expect(request('get', '/v1/tables/1234/statistics/history/last7days')).resolves.toMatchObject({
      code: 200,
      data: expect.any(Array)
    })
    await expect(request('get', '/v1/doris-clusters/1/sync-history')).resolves.toMatchObject({
      code: 200,
      data: expect.objectContaining({ records: expect.any(Array) })
    })
  })

  it('covers DataStudio SQL completion metadata endpoints', async () => {
    await expect(request('get', '/v1/doris-clusters/1/schema-objects', {
      params: { keyword: 'order', limit: 5 }
    })).resolves.toMatchObject({
      code: 200,
      data: expect.arrayContaining([
        expect.objectContaining({
          schemaName: 'opendataworks',
          tableName: 'demo_order_detail'
        })
      ])
    })

    await expect(request('get', '/v1/doris-clusters/1/databases/opendataworks/tables/demo_order_detail/columns')).resolves.toMatchObject({
      code: 200,
      data: expect.arrayContaining([
        expect.objectContaining({
          columnName: 'order_id'
        })
      ])
    })
  })

  it('covers table designer endpoints used by the create drawer', async () => {
    await expect(request('post', '/v1/table-designer/table-name', {
      data: { topic: '订单明细', layer: 'DWD' }
    })).resolves.toMatchObject({
      code: 200,
      data: expect.any(String)
    })
    await expect(request('post', '/v1/table-designer/preview', {
      data: { tableName: 'demo_new_table' }
    })).resolves.toMatchObject({
      code: 200,
      data: expect.objectContaining({ ddl: expect.any(String) })
    })
  })
})
