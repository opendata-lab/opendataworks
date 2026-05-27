export const RESULT_GRID_ROW_KEY = '__odwResultGridRowKey'

const MIN_COLUMN_WIDTH = 120
const MAX_COLUMN_WIDTH = 360
const HEADER_PADDING_WIDTH = 48
const AVERAGE_CHARACTER_WIDTH = 9

const clampColumnWidth = (width) => Math.max(MIN_COLUMN_WIDTH, Math.min(MAX_COLUMN_WIDTH, width))

const estimateColumnWidth = (column) => {
  const textLength = String(column ?? '').length
  return clampColumnWidth(textLength * AVERAGE_CHARACTER_WIDTH + HEADER_PADDING_WIDTH)
}

export const buildResultGridColumns = (columns = []) => {
  if (!Array.isArray(columns)) return []
  return columns.map((column) => {
    const key = String(column ?? '')
    return {
      key,
      dataKey: key,
      title: key,
      width: estimateColumnWidth(key)
    }
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
