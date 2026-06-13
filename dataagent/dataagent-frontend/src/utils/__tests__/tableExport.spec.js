import {
  buildCsvContent,
  buildMarkdownTable,
  buildTsvContent,
  exportFilename,
  formatCsvValue
} from '../tableExport'

describe('tableExport', () => {
  const columns = ['name', 'value']
  const rows = [
    { name: 'a,b', value: 1 },
    { name: 'quote"x', value: null },
    { name: 'line\nbreak', value: 0 }
  ]

  it('escapes csv values with commas, quotes and newlines', () => {
    expect(formatCsvValue('a,b')).toBe('"a,b"')
    expect(formatCsvValue('say "hi"')).toBe('"say ""hi"""')
    expect(formatCsvValue('x\ny')).toBe('"x\ny"')
    expect(formatCsvValue('plain')).toBe('plain')
    expect(formatCsvValue(null)).toBe('')
    expect(formatCsvValue(undefined)).toBe('')
  })

  it('builds csv with utf-8 bom and crlf rows', () => {
    const csv = buildCsvContent(columns, rows)
    expect(csv.charCodeAt(0)).toBe(0xfeff)
    const lines = csv.slice(1).split('\r\n')
    expect(lines[0]).toBe('name,value')
    expect(lines[1]).toBe('"a,b",1')
    expect(lines[2]).toBe('"quote""x",')
    expect(lines[3]).toBe('"line\nbreak",0')
  })

  it('builds tsv with control characters flattened', () => {
    const tsv = buildTsvContent(columns, [{ name: 'a\tb', value: 'x\ny' }])
    expect(tsv.split('\n')).toEqual(['name\tvalue', 'a b\tx y'])
  })

  it('builds markdown table with escaped pipes', () => {
    const md = buildMarkdownTable(columns, [{ name: 'a|b', value: 2 }])
    expect(md.split('\n')).toEqual([
      '| name | value |',
      '| --- | --- |',
      '| a\\|b | 2 |'
    ])
  })

  it('builds timestamped sanitized filenames', () => {
    const now = new Date(2026, 5, 13, 8, 9, 10)
    expect(exportFilename('趋势 图/表', 'csv', now)).toBe('趋势_图_表_20260613080910.csv')
    expect(exportFilename('', 'csv', now)).toBe('export_20260613080910.csv')
  })
})
