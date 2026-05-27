import { describe, expect, it } from 'vitest'
import {
  RESULT_GRID_ROW_KEY,
  buildResultGridColumns,
  buildResultGridRows
} from '../components/resultGridModel'
import { buildCsvContent } from '../csvExport'

describe('resultGridModel', () => {
  it('builds no virtual table columns when the result set has no fields', () => {
    expect(buildResultGridColumns([])).toEqual([])
  })

  it('builds bounded virtual columns for normal and long field names', () => {
    const columns = buildResultGridColumns([
      'id',
      'customer_name',
      'this_is_a_very_long_column_name_that_should_not_expand_the_grid_forever'
    ])

    expect(columns).toHaveLength(3)
    expect(columns[0]).toMatchObject({
      key: 'id',
      dataKey: 'id',
      title: 'id',
      width: 120
    })
    expect(columns[1].width).toBeGreaterThan(120)
    expect(columns[2].width).toBe(360)
  })

  it('adds stable row keys even when rows contain duplicate values and nulls', () => {
    const rows = buildResultGridRows(
      [
        { id: 1, name: 'same', memo: null },
        { id: 1, name: 'same', memo: null },
        { id: null, name: '', memo: undefined }
      ],
      'tab-a::0'
    )

    expect(rows.map((row) => row[RESULT_GRID_ROW_KEY])).toEqual([
      'tab-a::0::0',
      'tab-a::0::1',
      'tab-a::0::2'
    ])
    expect(rows[0].name).toBe('same')
    expect(rows[2].memo).toBeUndefined()
  })

  it('keeps the internal row key out of CSV exports', () => {
    const rows = buildResultGridRows([{ id: 1, name: 'Alice' }], 'tab-a::0')
    const csv = buildCsvContent(['id', 'name'], rows)

    expect(csv).toBe('\uFEFFid,name\r\n1,Alice\r\n')
    expect(csv).not.toContain(RESULT_GRID_ROW_KEY)
  })
})
