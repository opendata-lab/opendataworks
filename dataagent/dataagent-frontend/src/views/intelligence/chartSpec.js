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

const CHART_SPEC_BLOCK_PATTERNS = [
  /```chart\s*([\s\S]*?)```/gi,
  /<chart_spec>\s*([\s\S]*?)<\/chart_spec>/gi
]

const CHART_SPEC_CONDITIONAL_BLOCK_PATTERNS = [
  /```json\s*([\s\S]*?)```/gi
]

const findJsonObjectEnd = (source, start) => {
  let depth = 0
  let inString = false
  let escaped = false

  for (let index = start; index < source.length; index += 1) {
    const char = source[index]
    if (inString) {
      if (escaped) {
        escaped = false
      } else if (char === '\\') {
        escaped = true
      } else if (char === '"') {
        inString = false
      }
      continue
    }

    if (char === '"') {
      inString = true
    } else if (char === '{') {
      depth += 1
    } else if (char === '}') {
      depth -= 1
      if (depth === 0) return index + 1
    }
  }

  return -1
}

const findRawChartSpecJsonMatches = (source) => {
  const matches = []
  const text = String(source || '')
  for (let index = 0; index < text.length; index += 1) {
    if (text[index] !== '{') continue
    const end = findJsonObjectEnd(text, index)
    if (end < 0) break
    const raw = text.slice(index, end)
    const parsed = parseChartSpec(raw)
    if (parsed) {
      matches.push({ start: index, end, spec: parsed })
      index = end - 1
    }
  }
  return matches
}

const stripConditionalChartSpecBlocks = (source) => {
  let output = String(source || '')
  for (const pattern of CHART_SPEC_CONDITIONAL_BLOCK_PATTERNS) {
    pattern.lastIndex = 0
    output = output.replace(pattern, (match, content) => (
      parseChartSpec(content) ? '' : match
    ))
  }
  return output
}

export const extractChartSpecsFromText = (text) => {
  const source = String(text || '')
  const specs = []
  for (const pattern of CHART_SPEC_BLOCK_PATTERNS) {
    pattern.lastIndex = 0
    const matches = source.matchAll(pattern)
    for (const match of matches) {
      const parsed = parseChartSpec(match[1])
      if (parsed) specs.push(parsed)
    }
  }
  for (const pattern of CHART_SPEC_CONDITIONAL_BLOCK_PATTERNS) {
    pattern.lastIndex = 0
    const matches = source.matchAll(pattern)
    for (const match of matches) {
      const parsed = parseChartSpec(match[1])
      if (parsed) specs.push(parsed)
    }
  }
  let rawSource = source
  for (const pattern of CHART_SPEC_BLOCK_PATTERNS) {
    pattern.lastIndex = 0
    rawSource = rawSource.replace(pattern, '')
  }
  rawSource = stripConditionalChartSpecBlocks(rawSource)
  for (const match of findRawChartSpecJsonMatches(rawSource)) {
    specs.push(match.spec)
  }
  return specs
}

export const stripChartSpecsFromText = (text) => {
  let output = String(text || '')
  for (const pattern of CHART_SPEC_BLOCK_PATTERNS) {
    pattern.lastIndex = 0
    output = output.replace(pattern, '')
  }
  output = stripConditionalChartSpecBlocks(output)
  const rawMatches = findRawChartSpecJsonMatches(output)
  for (let index = rawMatches.length - 1; index >= 0; index -= 1) {
    const match = rawMatches[index]
    output = `${output.slice(0, match.start)}${output.slice(match.end)}`
  }
  return output
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}
