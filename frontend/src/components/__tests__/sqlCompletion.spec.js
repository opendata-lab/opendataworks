import { describe, expect, it, vi } from 'vitest'
import { createSqlCompletionSource } from '../sqlCompletion'

const completionContext = (overrides = {}) => ({
  sourceId: '1',
  currentSchema: 'dw',
  schemas: ['dw', 'ods'],
  tablesBySchema: {
    dw: [
      { tableName: 'fact_orders', tableComment: '订单明细' },
      { tableName: 'dim_store', tableComment: '门店维表' }
    ],
    ods: [
      { tableName: 'ods_order_detail', tableComment: '原始订单' }
    ]
  },
  loadTables: vi.fn(async (schema) => completionContext().tablesBySchema[schema] || []),
  loadColumns: vi.fn(async ({ schema, table }) => {
    if (schema === 'dw' && table === 'fact_orders') {
      return [
        { columnName: 'order_id', dataType: 'BIGINT', columnComment: '订单 ID' },
        { columnName: 'pay_amount', dataType: 'DECIMAL(18,2)', columnComment: '支付金额' }
      ]
    }
    return []
  }),
  searchTables: vi.fn(async () => [
    { schemaName: 'ods', tableName: 'ods_order_detail', tableComment: '原始订单' }
  ]),
  ...overrides
})

const editorContext = (doc, explicit = false) => ({
  pos: doc.length,
  explicit,
  state: {
    doc: {
      toString: () => doc,
      sliceString: (from, to) => doc.slice(from, to)
    }
  }
})

describe('sql completion source', () => {
  it('returns keyword and function candidates without prefix-only filtering', async () => {
    const source = createSqlCompletionSource({
      getCompletionContext: () => completionContext(),
      getTableNames: () => []
    })

    const result = await source(editorContext('ect', true))

    expect(result.options.some((item) => item.label === 'SELECT')).toBe(true)
    expect(result.options.some((item) => item.label === 'COUNT')).toBe(true)
  })

  it('loads tables for a schema-qualified completion', async () => {
    const ctx = completionContext()
    const source = createSqlCompletionSource({
      getCompletionContext: () => ctx,
      getTableNames: () => []
    })

    const result = await source(editorContext('SELECT * FROM ods.', true))

    expect(ctx.loadTables).toHaveBeenCalledWith('ods')
    expect(result.options).toEqual(expect.arrayContaining([
      expect.objectContaining({
        label: 'ods_order_detail',
        detail: 'ods'
      })
    ]))
  })

  it('loads columns for an alias-qualified completion', async () => {
    const ctx = completionContext()
    const source = createSqlCompletionSource({
      getCompletionContext: () => ctx,
      getTableNames: () => []
    })

    const result = await source(editorContext('SELECT * FROM dw.fact_orders o WHERE o.', true))

    expect(ctx.loadColumns).toHaveBeenCalledWith({ schema: 'dw', table: 'fact_orders' })
    expect(result.options).toEqual(expect.arrayContaining([
      expect.objectContaining({
        label: 'order_id',
        type: 'property',
        detail: 'BIGINT'
      })
    ]))
  })

  it('keeps legacy tableNames completion for existing callers', async () => {
    const source = createSqlCompletionSource({
      getCompletionContext: () => null,
      getTableNames: () => ['legacy_table']
    })

    const result = await source(editorContext('legacy', true))

    expect(result.options).toEqual(expect.arrayContaining([
      expect.objectContaining({
        label: 'legacy_table',
        type: 'variable'
      })
    ]))
  })
})
