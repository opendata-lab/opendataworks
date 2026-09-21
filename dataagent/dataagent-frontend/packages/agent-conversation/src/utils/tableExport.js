const UTF8_BOM = '\uFEFF'

const cellText = (value) => {
  if (value === null || value === undefined) return ''
  return String(value)
}

export const formatCsvValue = (value) => {
  const str = cellText(value)
  if (str.includes(',') || str.includes('"') || str.includes('\n') || str.includes('\r')) {
    return `"${str.replace(/"/g, '""')}"`
  }
  return str
}

export const buildCsvContent = (columns, rows) => {
  const header = columns.map(formatCsvValue).join(',')
  const lines = rows.map((row) => columns.map((column) => formatCsvValue(row?.[column])).join(','))
  return `${UTF8_BOM}${[header, ...lines].join('\r\n')}`
}

export const buildTsvContent = (columns, rows) => {
  const sanitize = (value) => cellText(value).replace(/[\t\r\n]+/g, ' ')
  const header = columns.map(sanitize).join('\t')
  const lines = rows.map((row) => columns.map((column) => sanitize(row?.[column])).join('\t'))
  return [header, ...lines].join('\n')
}

export const buildMarkdownTable = (columns, rows) => {
  const sanitize = (value) => cellText(value).replace(/\|/g, '\\|').replace(/[\r\n]+/g, ' ')
  const header = `| ${columns.map(sanitize).join(' | ')} |`
  const divider = `| ${columns.map(() => '---').join(' | ')} |`
  const lines = rows.map((row) => `| ${columns.map((column) => sanitize(row?.[column])).join(' | ')} |`)
  return [header, divider, ...lines].join('\n')
}

const pad2 = (value) => String(value).padStart(2, '0')

export const exportFilename = (base, ext, now = new Date()) => {
  const safeBase = String(base || 'export')
    .trim()
    .replace(/[\\/:*?"<>|\s]+/g, '_')
    .slice(0, 60) || 'export'
  const stamp = [
    now.getFullYear(),
    pad2(now.getMonth() + 1),
    pad2(now.getDate()),
    pad2(now.getHours()),
    pad2(now.getMinutes()),
    pad2(now.getSeconds())
  ].join('')
  return `${safeBase}_${stamp}.${ext}`
}

export const downloadTextFile = (filename, content, mime = 'text/plain;charset=utf-8;') => {
  if (typeof document === 'undefined') return
  const blob = new Blob([content], { type: mime })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

export const downloadCsv = (baseName, columns, rows) => {
  downloadTextFile(exportFilename(baseName, 'csv'), buildCsvContent(columns, rows), 'text/csv;charset=utf-8;')
}
