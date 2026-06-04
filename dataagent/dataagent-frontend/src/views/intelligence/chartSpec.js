const CHART_TYPES = new Set(['table', 'bar', 'line', 'pie'])
const ECHART_TYPES = new Set(['bar', 'line', 'pie'])
const SERIES_TYPES = new Set(['bar', 'line', 'pie'])
const DEFAULT_CHART_COLORS = ['#0f8c7b', '#f59e0b', '#3b82f6', '#ef4444', '#8b5cf6', '#14b8a6', '#f97316']

const isPlainObject = (value) => value && typeof value === 'object' && !Array.isArray(value)

const textOrEmpty = (value) => (value == null ? '' : String(value).trim())

export const parseMaybeJson = (value) => {
  if (typeof value !== 'string') return null
  const raw = value.trim()
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch (_error) {
    const firstBrace = raw.indexOf('{')
    const lastBrace = raw.lastIndexOf('}')
    if (firstBrace >= 0 && lastBrace > firstBrace) {
      try {
        return JSON.parse(raw.slice(firstBrace, lastBrace + 1))
      } catch (_innerError) {
        return null
      }
    }
    return null
  }
}

export const extractTextParts = (value) => {
  if (typeof value === 'string') return value
  if (Array.isArray(value)) {
    return value.map((item) => {
      if (typeof item === 'string') return item
      if (isPlainObject(item)) {
        if (typeof item.text === 'string') return item.text
        if (typeof item.content === 'string') return item.content
      }
      return ''
    }).filter(Boolean).join('\n')
  }
  if (isPlainObject(value)) {
    if (typeof value.text === 'string') return value.text
    if (typeof value.content === 'string') return value.content
    if (typeof value.stdout === 'string') return value.stdout
    if (typeof value.result === 'string') return value.result
  }
  return ''
}

// Deeply locate a chart_spec inside a tool output, which can arrive as a raw
// object, an array of tool-result content blocks, or JSON embedded in stdout
// text. Both the in-box renderer and the conclusion-area promotion logic rely
// on this single source of truth so detection and rendering never diverge.
export const extractChartSpec = (value) => {
  const direct = parseChartSpec(value)
  if (direct) return direct

  if (Array.isArray(value)) {
    for (const item of value) {
      const itemChart = parseChartSpec(item)
      if (itemChart) return itemChart
      const itemTextChart = parseChartSpec(extractTextParts(item))
      if (itemTextChart) return itemTextChart
    }
  }

  return parseChartSpec(extractTextParts(value))
}

const normalizeDataset = (value) => (
  Array.isArray(value)
    ? value.filter(isPlainObject).map((row) => ({ ...row }))
    : []
)

const normalizeColumns = (value) => (
  Array.isArray(value)
    ? value.map((item) => String(item || '').trim()).filter(Boolean)
    : []
)

const normalizeSeries = (value, fallbackType) => (
  Array.isArray(value)
    ? value
      .filter(isPlainObject)
      .map((item) => {
        const type = textOrEmpty(item.type || fallbackType).toLowerCase()
        return {
          name: textOrEmpty(item.name || item.field || '指标'),
          field: textOrEmpty(item.field),
          type: SERIES_TYPES.has(type) ? type : fallbackType
        }
      })
      .filter((item) => item.field)
    : []
)

export const parseChartSpec = (value) => {
  if (typeof value === 'string') {
    return parseChartSpec(parseMaybeJson(value))
  }
  if (!isPlainObject(value)) return null
  if (value.kind !== 'chart_spec' && !value.chart_type) return null

  const chartType = textOrEmpty(value.chart_type).toLowerCase()
  const version = Number(value.version)
  const dataset = normalizeDataset(value.dataset)
  const normalized = {
    kind: 'chart_spec',
    version: Number.isInteger(version) && version > 0 ? version : 1,
    chart_type: chartType,
    title: textOrEmpty(value.title),
    description: textOrEmpty(value.description),
    x_field: textOrEmpty(value.x_field),
    dataset,
    columns: normalizeColumns(value.columns),
    series: normalizeSeries(value.series, chartType),
    unit: textOrEmpty(value.unit),
    colors: Array.isArray(value.colors) ? value.colors.map((item) => String(item || '').trim()).filter(Boolean) : [],
    stack: value.stack === true,
    area: value.area === true,
    donut: value.donut === true,
    orientation: textOrEmpty(value.orientation).toLowerCase() === 'horizontal' ? 'horizontal' : 'vertical',
    error: value.error == null ? null : textOrEmpty(value.error)
  }
  return normalized
}

export const validateChartSpec = (specInput) => {
  const spec = parseChartSpec(specInput)
  if (!spec) {
    return {
      valid: false,
      spec: null,
      errors: ['无法解析 chart_spec JSON']
    }
  }

  const errors = []

  if (spec.version !== 1) {
    errors.push('仅支持 chart_spec version=1')
  }
  if (!CHART_TYPES.has(spec.chart_type)) {
    errors.push('chart_type 必须为 table、bar、line 或 pie')
  }
  if (!spec.title) {
    errors.push('title 不能为空')
  }
  if (!Array.isArray(spec.dataset)) {
    errors.push('dataset 必须为数组')
  }

  if (spec.chart_type === 'table') {
    if (!spec.columns.length) {
      errors.push('table 类型必须提供 columns')
    }
  } else if (ECHART_TYPES.has(spec.chart_type)) {
    if (!spec.x_field) {
      errors.push(`${spec.chart_type} 类型必须提供 x_field`)
    }
    if (!spec.series.length) {
      errors.push(`${spec.chart_type} 类型必须提供 series`)
    }
    if (spec.chart_type === 'pie' && spec.series.length !== 1) {
      errors.push('pie 类型必须且只能提供一个 series')
    }
  }

  return {
    valid: errors.length === 0,
    spec,
    errors
  }
}

const toNumeric = (value) => {
  if (typeof value === 'number') return value
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : value
}

const buildPieOption = (spec) => {
  const primarySeries = spec.series[0]
  return {
    backgroundColor: 'transparent',
    color: spec.colors.length ? spec.colors : DEFAULT_CHART_COLORS,
    title: spec.title
      ? { text: spec.title, left: 'center', top: 8, textStyle: { fontSize: 14, fontWeight: 600, color: '#162131' } }
      : undefined,
    tooltip: {
      trigger: 'item',
      valueFormatter: spec.unit ? (value) => `${value}${spec.unit}` : undefined
    },
    legend: { bottom: 0, textStyle: { color: '#607185' } },
    series: [
      {
        type: 'pie',
        radius: spec.donut ? ['44%', '70%'] : '68%',
        center: ['50%', '52%'],
        label: { color: '#425466' },
        itemStyle: { borderColor: '#ffffff', borderWidth: 2 },
        data: spec.dataset.map((row) => ({
          name: String(row[spec.x_field] ?? ''),
          value: toNumeric(row[primarySeries.field] ?? 0)
        }))
      }
    ]
  }
}

const buildAxisOption = (spec) => {
  const horizontal = spec.chart_type === 'bar' && spec.orientation === 'horizontal'
  const categoryAxis = {
    type: 'category',
    data: spec.dataset.map((row) => row[spec.x_field]),
    axisLabel: {
      color: '#607185',
      rotate: spec.chart_type === 'bar' && !horizontal && spec.dataset.length > 8 ? 25 : 0
    },
    axisLine: { lineStyle: { color: '#d7e4ef' } }
  }
  const valueAxis = {
    type: 'value',
    name: spec.unit || '',
    scale: true,
    axisLabel: { color: '#607185' },
    splitLine: { lineStyle: { color: '#eef3f8' } }
  }

  return {
    backgroundColor: 'transparent',
    color: spec.colors.length ? spec.colors : DEFAULT_CHART_COLORS,
    title: spec.title
      ? {
          text: spec.title,
          subtext: spec.description || '',
          left: 'left',
          top: 6,
          textStyle: { fontSize: 14, fontWeight: 600, color: '#162131' },
          subtextStyle: { color: '#607185', fontSize: 12 }
        }
      : undefined,
    tooltip: {
      trigger: 'axis',
      valueFormatter: spec.unit ? (value) => `${value}${spec.unit}` : undefined
    },
    legend: { top: 8, right: 0, textStyle: { color: '#607185' } },
    grid: { left: 24, right: 16, top: spec.title ? 68 : 32, bottom: 40, containLabel: true },
    xAxis: horizontal ? valueAxis : categoryAxis,
    yAxis: horizontal ? categoryAxis : valueAxis,
    series: spec.series.map((series) => ({
      type: series.type,
      name: series.name,
      smooth: spec.chart_type === 'line',
      stack: spec.stack ? 'total' : undefined,
      areaStyle: spec.chart_type === 'line' && spec.area ? {} : undefined,
      lineStyle: spec.chart_type === 'line' ? { width: 3 } : undefined,
      symbolSize: spec.chart_type === 'line' ? 8 : undefined,
      barMaxWidth: spec.chart_type === 'bar' ? 34 : undefined,
      itemStyle: spec.chart_type === 'bar' ? { borderRadius: horizontal ? [0, 8, 8, 0] : [8, 8, 0, 0] } : undefined,
      data: spec.dataset.map((row) => toNumeric(row[series.field]))
    }))
  }
}

export const buildChartRenderModel = (specInput) => {
  const spec = parseChartSpec(specInput)
  if (!spec) {
    return {
      state: 'invalid',
      kind: 'invalid',
      spec: null,
      errorText: '无法解析 chart_spec JSON'
    }
  }

  if (spec.error) {
    return {
      state: 'error',
      kind: 'error',
      spec,
      errorText: spec.error
    }
  }

  const { valid, errors } = validateChartSpec(spec)
  if (!valid) {
    return {
      state: 'invalid',
      kind: 'invalid',
      spec,
      errorText: errors.join('；')
    }
  }

  if (!spec.dataset.length) {
    return {
      state: 'empty',
      kind: spec.chart_type,
      spec,
      errorText: ''
    }
  }

  if (spec.chart_type === 'table') {
    return {
      state: 'renderable',
      kind: 'table',
      spec,
      columns: spec.columns,
      rows: spec.dataset,
      errorText: ''
    }
  }

  return {
    state: 'renderable',
    kind: 'echarts',
    spec,
    option: spec.chart_type === 'pie' ? buildPieOption(spec) : buildAxisOption(spec),
    errorText: ''
  }
}

export const buildChartOption = (specInput) => {
  const model = buildChartRenderModel(specInput)
  return model.kind === 'echarts' ? model.option : null
}

// Wrapped forms a model may use to embed a chart_spec inside answer prose:
// a ```chart / ```json fence, or an <chart_spec> tag. The raw-object form
// ({ "kind": "chart_spec", ... } written inline without any wrapper) is handled
// separately via brace scanning below.
const CHART_SPEC_FENCE_PATTERNS = [
  /```(?:chart|json)?\s*([\s\S]*?)```/gi,
  /<chart_spec>\s*([\s\S]*?)<\/chart_spec>/gi
]

// Locate the index of the brace that closes the object opened at `start`,
// ignoring braces inside JSON strings. Returns -1 when unbalanced (e.g. a spec
// still streaming in), so partial output is left as plain text until complete.
const findMatchingBrace = (source, start) => {
  let depth = 0
  let inString = false
  let quote = ''
  for (let i = start; i < source.length; i += 1) {
    const ch = source[i]
    if (inString) {
      if (ch === '\\') {
        i += 1
      } else if (ch === quote) {
        inString = false
      }
      continue
    }
    if (ch === '"' || ch === "'") {
      inString = true
      quote = ch
    } else if (ch === '{') {
      depth += 1
    } else if (ch === '}') {
      depth -= 1
      if (depth === 0) return i
    }
  }
  return -1
}

// Collect every chart_spec occurrence in `source` as a non-overlapping range,
// covering fenced, tagged, and raw-JSON forms so detection never diverges from
// what the conclusion area renders.
const collectChartSpecRanges = (source) => {
  const ranges = []

  for (const pattern of CHART_SPEC_FENCE_PATTERNS) {
    pattern.lastIndex = 0
    let match
    while ((match = pattern.exec(source)) !== null) {
      const spec = parseChartSpec(match[1])
      if (spec) ranges.push({ start: match.index, end: match.index + match[0].length, spec })
    }
  }

  const isClaimed = (index) => ranges.some((range) => index >= range.start && index < range.end)
  const marker = 'chart_spec'
  let searchFrom = 0
  while (true) {
    const hit = source.indexOf(marker, searchFrom)
    if (hit < 0) break
    const start = source.lastIndexOf('{', hit)
    if (start >= 0 && !isClaimed(start)) {
      const end = findMatchingBrace(source, start)
      if (end > start) {
        const spec = parseChartSpec(source.slice(start, end + 1))
        if (spec) {
          ranges.push({ start, end: end + 1, spec })
          searchFrom = end + 1
          continue
        }
      }
    }
    searchFrom = hit + marker.length
  }

  const sorted = ranges.sort((a, b) => a.start - b.start || b.end - a.end)
  const resolved = []
  for (const range of sorted) {
    const last = resolved[resolved.length - 1]
    if (last && range.start < last.end) continue
    resolved.push(range)
  }
  return resolved
}

export const extractChartSpecsFromText = (text) => collectChartSpecRanges(String(text || '')).map((range) => range.spec)

// Split answer text into ordered segments so the conclusion area can render the
// surrounding prose as markdown and each embedded chart_spec as a real chart,
// instead of leaking the JSON as raw text.
export const splitChartSpecText = (text) => {
  const source = String(text || '')
  const ranges = collectChartSpecRanges(source)
  const segments = []
  const pushText = (raw) => {
    const value = String(raw || '').replace(/\n{3,}/g, '\n\n').trim()
    if (value) segments.push({ type: 'text', value })
  }

  let cursor = 0
  for (const range of ranges) {
    pushText(source.slice(cursor, range.start))
    segments.push({ type: 'chart', spec: range.spec })
    cursor = range.end
  }
  pushText(source.slice(cursor))
  return segments
}

export const stripChartSpecsFromText = (text) => splitChartSpecText(text)
  .filter((segment) => segment.type === 'text')
  .map((segment) => segment.value)
  .join('\n\n')
  .trim()
