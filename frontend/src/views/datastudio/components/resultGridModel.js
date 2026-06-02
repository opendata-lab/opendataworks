export const RESULT_GRID_ROW_KEY = '__odwResultGridRowKey'

export const MIN_COLUMN_WIDTH = 120
const MAX_COLUMN_WIDTH = 360
const HEADER_PADDING_WIDTH = 48
const CELL_PADDING_WIDTH = 24
const AVERAGE_CHARACTER_WIDTH = 9
const WIDTH_SAMPLE_ROW_LIMIT = 50

const clampColumnWidth = (width) => Math.max(MIN_COLUMN_WIDTH, Math.min(MAX_COLUMN_WIDTH, Math.round(width)))

const measureTextWidth = (value) => String(value ?? '').length * AVERAGE_CHARACTER_WIDTH

const estimateColumnWidth = (key, rows) => {
  let widest = measureTextWidth(key) + HEADER_PADDING_WIDTH
  if (Array.isArray(rows)) {
    const sample = rows.slice(0, WIDTH_SAMPLE_ROW_LIMIT)
    for (const row of sample) {
      if (!row || typeof row !== 'object') continue
      const cellWidth = measureTextWidth(row[key]) + CELL_PADDING_WIDTH
      if (cellWidth > widest) widest = cellWidth
    }
  }
  return clampColumnWidth(widest)
}

export const buildResultGridColumns = (columns = [], rows = []) => {
  if (!Array.isArray(columns)) return []
  return columns.map((column) => {
    const key = String(column ?? '')
    return {
      key,
      dataKey: key,
      title: key,
      width: estimateColumnWidth(key, rows),
      minWidth: MIN_COLUMN_WIDTH,
      resizable: true
    }
  })
}

// Stretch columns to fill the available width when the natural content widths
// leave empty space (e.g. only two short columns in a wide grid). Extra space
// is distributed proportionally so the table never leaves an awkward gap.
export const distributeColumnWidths = (columns = [], availableWidth = 0) => {
  if (!Array.isArray(columns) || !columns.length) return Array.isArray(columns) ? columns : []
  const total = columns.reduce((sum, column) => sum + (Number(column.width) || 0), 0)
  if (!(availableWidth > 0) || total <= 0 || total >= availableWidth) return columns
  const extra = availableWidth - total
  let allocated = 0
  return columns.map((column, index) => {
    const base = Number(column.width) || 0
    const add = index === columns.length - 1
      ? extra - allocated
      : Math.floor((base / total) * extra)
    allocated += add
    return { ...column, width: base + add }
  })
}

const hasOwn = (value, key) => Object.prototype.hasOwnProperty.call(value, key)

export const buildResultGridRows = (rows = [], rowKeyPrefix = 'result') => {
  if (!Array.isArray(rows)) return []
  return rows.map((row, index) => {
    const source = row && typeof row === 'object' ? row : { value: row }
    const existingKey = hasOwn(source, RESULT_GRID_ROW_KEY) ? source[RESULT_GRID_ROW_KEY] : null
    return {
      ...source,
      [RESULT_GRID_ROW_KEY]: existingKey || `${rowKeyPrefix}::${index}`
    }
  })
}

export const ensureResultGridRows = (rows = [], rowKeyPrefix = 'result') => {
  if (!Array.isArray(rows)) return []
  const hasKeys = rows.every((row) => row && typeof row === 'object' && hasOwn(row, RESULT_GRID_ROW_KEY))
  return hasKeys ? rows : buildResultGridRows(rows, rowKeyPrefix)
}
