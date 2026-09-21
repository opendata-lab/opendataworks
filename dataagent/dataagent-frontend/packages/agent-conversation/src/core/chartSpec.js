const CHART_TYPES = new Set(['table', 'bar', 'line', 'area', 'scatter', 'combo', 'radar', 'funnel', 'gauge', 'pie'])
const ECHART_TYPES = new Set(['bar', 'line', 'area', 'scatter', 'combo', 'radar', 'funnel', 'gauge', 'pie'])
// 笛卡尔轴类图表（共用 x 轴 + series 数组）
const AXIS_CHART_TYPES = new Set(['bar', 'line', 'area', 'combo'])
// 必须且只能一个 series 的类型
const SINGLE_SERIES_TYPES = new Set(['pie', 'funnel', 'gauge'])
// series.type 允许的 ECharts 系列类型
const SERIES_TYPES = new Set(['bar', 'line', 'pie', 'scatter'])
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

// area 在 ECharts 中由 line + areaStyle 实现，没有独立的 'area' 系列类型。
const seriesEchartsType = (fallbackType) => (fallbackType === 'area' ? 'line' : fallbackType)

const normalizeSeries = (value, fallbackType) => {
  const defaultType = seriesEchartsType(fallbackType)
  return Array.isArray(value)
    ? value
      .filter(isPlainObject)
      .map((item) => {
        const type = textOrEmpty(item.type || defaultType).toLowerCase()
        const axis = textOrEmpty(item.axis).toLowerCase() === 'right' ? 'right' : 'left'
        return {
          name: textOrEmpty(item.name || item.field || '指标'),
          field: textOrEmpty(item.field),
          type: SERIES_TYPES.has(type) ? type : (SERIES_TYPES.has(defaultType) ? defaultType : 'line'),
          axis
        }
      })
      .filter((item) => item.field)
    : []
}

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
    errors.push('chart_type 必须为 table、bar、line、area、scatter、combo、radar、funnel、gauge 或 pie')
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
    // gauge 取单一 KPI，不要求 x_field；其余图表必须有 x_field。
    if (spec.chart_type !== 'gauge' && !spec.x_field) {
      errors.push(`${spec.chart_type} 类型必须提供 x_field`)
    }
    if (!spec.series.length) {
      errors.push(`${spec.chart_type} 类型必须提供 series`)
    }
    if (SINGLE_SERIES_TYPES.has(spec.chart_type) && spec.series.length !== 1) {
      errors.push(`${spec.chart_type} 类型必须且只能提供一个 series`)
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
  const hasTitle = Boolean(spec.title)
  return {
    backgroundColor: 'transparent',
    color: spec.colors.length ? spec.colors : DEFAULT_CHART_COLORS,
    title: hasTitle
      ? { text: spec.title, left: 'center', top: 8, textStyle: { fontSize: 13, fontWeight: 600, color: '#162131' } }
      : undefined,
    tooltip: {
      trigger: 'item',
      valueFormatter: spec.unit ? (value) => `${value}${spec.unit}` : undefined
    },
    legend: { bottom: 0, textStyle: { color: '#607185' } },
    series: [
      {
        type: 'pie',
        // The centered title (top) and the bottom legend each need a clear band.
        // Unlike axis/funnel charts (which reserve top room via grid/top), a pie
        // only has center+radius, so when a title is present we nudge the pie
        // down so its slices and outside labels clear the title in short
        // fixed-height containers (e.g. the embedded widget canvas). Shorter
        // leader lines keep outside labels from poking back up into the title.
        radius: hasTitle ? (spec.donut ? ['40%', '62%'] : '60%') : (spec.donut ? ['44%', '70%'] : '68%'),
        center: hasTitle ? ['50%', '55%'] : ['50%', '52%'],
        avoidLabelOverlap: true,
        // alignTo:'edge' pins outside labels to the container edge so a wide
        // slice's label can't overflow and get truncated in the narrow widget
        // panel; names stay on the chart (unlike inside-percentage labels).
        label: { color: '#425466', alignTo: 'edge', edgeDistance: 6, minMargin: 4 },
        labelLine: { length: 12, length2: 8 },
        itemStyle: { borderColor: '#ffffff', borderWidth: 2 },
        data: spec.dataset.map((row) => ({
          name: String(row[spec.x_field] ?? ''),
          value: toNumeric(row[primarySeries.field] ?? 0)
        }))
      }
    ]
  }
}

const buildTitleOption = (spec) => (spec.title
  ? {
      text: spec.title,
      subtext: spec.description || '',
      left: 'left',
      top: 6,
      textStyle: { fontSize: 13, fontWeight: 600, color: '#162131' },
      subtextStyle: { color: '#607185', fontSize: 12 }
    }
  : undefined)

// Bar-family axes must start at 0 so bar length encodes value honestly;
// line/scatter pass scale:true to zoom into the data range.
const valueAxisOption = (name, scale = true) => ({
  type: 'value',
  name: name || '',
  scale,
  axisLabel: { color: '#607185', fontSize: 11 },
  splitLine: { lineStyle: { color: '#eef3f8' } }
})

// 柱状 / 折线 / 面积 / 组合双轴：共用类目 x 轴 + series 数组。
const buildAxisOption = (spec) => {
  const isCombo = spec.chart_type === 'combo'
  const isArea = spec.chart_type === 'area'
  const horizontal = spec.chart_type === 'bar' && spec.orientation === 'horizontal'
  // Only a plain categorical bar force-shows every label (interval 0) and rotates
  // when crowded — that is the case that previously dropped labels in the narrow
  // widget. combo is usually a time-series dual-axis trend (e.g. 30 days / monthly),
  // so it keeps ECharts auto thinning like line/area; forcing interval:0 there
  // would cram every x label and overlap in a 360px container.
  const isCategoricalBar = spec.chart_type === 'bar'
  const categoryAxis = {
    type: 'category',
    data: spec.dataset.map((row) => row[spec.x_field]),
    axisLabel: {
      color: '#607185',
      fontSize: 11,
      interval: isCategoricalBar ? 0 : 'auto',
      rotate: isCategoricalBar && !horizontal && spec.dataset.length > 6 ? 30 : 0
    },
    axisLine: { lineStyle: { color: '#d7e4ef' } }
  }
  // Bar/area/combo encode value by length, so their value axis starts at 0;
  // line keeps scale:true to zoom into the trend range.
  const startAtZero = spec.chart_type === 'bar' || isArea || isCombo
  const valueAxis = valueAxisOption(spec.unit, !startAtZero)
  const yAxis = isCombo
    ? [valueAxisOption(spec.series[0] ? spec.series[0].name : spec.unit, false), valueAxisOption('', false)]
    : (horizontal ? categoryAxis : valueAxis)

  return {
    backgroundColor: 'transparent',
    color: spec.colors.length ? spec.colors : DEFAULT_CHART_COLORS,
    title: buildTitleOption(spec),
    tooltip: {
      trigger: 'axis',
      transitionDuration: 0,
      axisPointer: { type: 'line', animation: false },
      valueFormatter: spec.unit ? (value) => `${value}${spec.unit}` : undefined
    },
    legend: { top: 8, right: 0, textStyle: { color: '#607185' } },
    grid: { left: 24, right: 16, top: spec.title ? 56 : 28, bottom: 40, containLabel: true },
    xAxis: horizontal ? valueAxis : categoryAxis,
    yAxis,
    series: spec.series.map((series) => {
      const seriesType = isCombo ? series.type : (isArea ? 'line' : series.type)
      const isLineLike = seriesType === 'line'
      return {
        type: seriesType,
        name: series.name,
        yAxisIndex: isCombo ? (series.axis === 'right' ? 1 : 0) : undefined,
        smooth: spec.chart_type === 'line' || isArea,
        stack: spec.stack && !isCombo ? 'total' : undefined,
        areaStyle: isArea || (isLineLike && spec.area && !isCombo) ? {} : undefined,
        lineStyle: isLineLike ? { width: 3 } : undefined,
        symbolSize: isLineLike ? 8 : undefined,
        barMaxWidth: seriesType === 'bar' ? 34 : undefined,
        itemStyle: seriesType === 'bar' ? { borderRadius: horizontal ? [0, 8, 8, 0] : [8, 8, 0, 0] } : undefined,
        data: spec.dataset.map((row) => toNumeric(row[series.field]))
      }
    })
  }
}

// 散点图：x、y 均为数值轴，数据点为 [x, y]。
const buildScatterOption = (spec) => ({
  backgroundColor: 'transparent',
  color: spec.colors.length ? spec.colors : DEFAULT_CHART_COLORS,
  title: buildTitleOption(spec),
  tooltip: {
    trigger: 'item',
    valueFormatter: spec.unit ? (value) => `${value}${spec.unit}` : undefined
  },
  legend: { top: 8, right: 0, textStyle: { color: '#607185' } },
  grid: { left: 24, right: 16, top: spec.title ? 56 : 28, bottom: 40, containLabel: true },
  xAxis: { ...valueAxisOption(spec.x_field), name: spec.x_field },
  yAxis: valueAxisOption(spec.unit),
  series: spec.series.map((series) => ({
    type: 'scatter',
    name: series.name,
    symbolSize: 12,
    data: spec.dataset.map((row) => [toNumeric(row[spec.x_field]), toNumeric(row[series.field])])
  }))
})

// 雷达图：每行作为一个指标轴，每个 series 为一圈。
const buildRadarOption = (spec) => {
  const indicators = spec.dataset.map((row) => {
    const max = spec.series.reduce((acc, series) => {
      const value = toNumeric(row[series.field])
      return typeof value === 'number' && value > acc ? value : acc
    }, 0)
    return { name: String(row[spec.x_field] ?? ''), max: max > 0 ? max : undefined }
  })
  return {
    backgroundColor: 'transparent',
    color: spec.colors.length ? spec.colors : DEFAULT_CHART_COLORS,
    title: buildTitleOption(spec),
    tooltip: { trigger: 'item' },
    legend: { top: 8, right: 0, textStyle: { color: '#607185' } },
    radar: {
      indicator: indicators,
      radius: '62%',
      center: ['50%', '55%'],
      axisName: { color: '#607185' },
      splitLine: { lineStyle: { color: '#e3ebf3' } },
      splitArea: { areaStyle: { color: ['rgba(15,140,123,0.03)', 'rgba(15,140,123,0.06)'] } }
    },
    series: [
      {
        type: 'radar',
        data: spec.series.map((series) => ({
          name: series.name,
          value: spec.dataset.map((row) => toNumeric(row[series.field])),
          areaStyle: { opacity: 0.1 }
        }))
      }
    ]
  }
}

// 漏斗图：阶段 + 单数值。
const buildFunnelOption = (spec) => {
  const primarySeries = spec.series[0]
  return {
    backgroundColor: 'transparent',
    color: spec.colors.length ? spec.colors : DEFAULT_CHART_COLORS,
    title: buildTitleOption(spec),
    tooltip: {
      trigger: 'item',
      valueFormatter: spec.unit ? (value) => `${value}${spec.unit}` : undefined
    },
    legend: { bottom: 0, textStyle: { color: '#607185' } },
    series: [
      {
        type: 'funnel',
        left: '10%',
        right: '10%',
        top: spec.title ? 56 : 24,
        bottom: 32,
        minSize: '20%',
        sort: 'descending',
        gap: 2,
        label: { color: '#425466' },
        labelLine: { lineStyle: { color: '#c5d2df' } },
        itemStyle: { borderColor: '#ffffff', borderWidth: 1 },
        data: spec.dataset.map((row) => ({
          name: String(row[spec.x_field] ?? ''),
          value: toNumeric(row[primarySeries.field] ?? 0)
        }))
      }
    ]
  }
}

const niceCeil = (value) => {
  const num = typeof value === 'number' && Number.isFinite(value) ? value : 0
  if (num <= 0) return 100
  const magnitude = 10 ** Math.floor(Math.log10(num))
  return Math.ceil(num / magnitude) * magnitude
}

// 仪表盘：取首行单一 KPI。
const buildGaugeOption = (spec) => {
  const primarySeries = spec.series[0]
  const firstRow = spec.dataset[0] || {}
  const value = toNumeric(firstRow[primarySeries.field] ?? 0)
  const numericValue = typeof value === 'number' ? value : 0
  const name = spec.x_field ? String(firstRow[spec.x_field] ?? primarySeries.name) : primarySeries.name
  const max = spec.unit === '%' ? 100 : niceCeil(numericValue * 1.2)
  return {
    backgroundColor: 'transparent',
    color: spec.colors.length ? spec.colors : DEFAULT_CHART_COLORS,
    title: buildTitleOption(spec),
    series: [
      {
        type: 'gauge',
        min: 0,
        max,
        center: ['50%', '58%'],
        radius: '78%',
        progress: { show: true, width: 14 },
        axisLine: { lineStyle: { width: 14 } },
        axisLabel: { color: '#607185', distance: 18 },
        pointer: { width: 5 },
        detail: {
          valueAnimation: true,
          fontSize: 22,
          color: '#162131',
          formatter: spec.unit ? `{value}${spec.unit}` : '{value}'
        },
        title: { color: '#607185', offsetCenter: [0, '72%'] },
        data: [{ value: numericValue, name }]
      }
    ]
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
    option: buildEchartsOption(spec),
    errorText: ''
  }
}

const ECHART_OPTION_BUILDERS = {
  pie: buildPieOption,
  scatter: buildScatterOption,
  radar: buildRadarOption,
  funnel: buildFunnelOption,
  gauge: buildGaugeOption
}

const buildEchartsOption = (spec) => {
  const builder = ECHART_OPTION_BUILDERS[spec.chart_type]
  return builder ? builder(spec) : buildAxisOption(spec)
}

export const buildChartOption = (specInput) => {
  const model = buildChartRenderModel(specInput)
  return model.kind === 'echarts' ? model.option : null
}

// Wrapped forms a model may use to embed a chart_spec inside answer prose:
// a ```chart / ```json fence, or an <chart_spec> tag. The raw-object form
// ({ "kind": "chart_spec", ... } written inline without any wrapper) is handled
// separately via brace scanning below.
const CHART_SPEC_FENCE_PATTERN = /```([^\n`]*)\n?([\s\S]*?)```/g

// The contract-JSON marker, stricter than a bare `chart_spec` mention so a
// fence that merely talks about the contract (e.g. a build_chart_spec.py
// command template) is never claimed.
const CHART_SPEC_KIND_MARK = /"kind"\s*[:：]\s*"chart_spec"/

// Chart-intended fences are claimed even when their body fails to parse, so a
// bypassing model's made-up chart block is dropped instead of leaking as a code
// block. Intent requires an explicit signal — a chart* info string, an embedded
// <chart_spec> tag, or the contract kind marker; anything less keeps the fence
// untouched (false negatives are preferred over eating legit code blocks).
const fenceLooksChartIntended = (info, body) => {
  const text = `${info}\n${body}`
  return (
    /^chart/i.test(String(info || '').trim()) ||
    text.includes('<chart_spec') ||
    CHART_SPEC_KIND_MARK.test(text)
  )
}

// A hand-written <chart_spec> pair is claimed whether or not its body parses:
// a model bypassing build_chart_spec.py often writes malformed or non-contract
// JSON, and leaving the raw tag in place would leak it to the user as literal
// text. Attributes on the opening tag and whitespace in the close are tolerated.
const CHART_SPEC_TAG_PATTERN = /<chart_spec\b[^>]*>\s*([\s\S]*?)<\/chart_spec\s*>/gi

// A lone opening/closing tag with no matching pair is dropped as a bare token,
// never claiming the prose around it.
const CHART_SPEC_ORPHAN_TAG_PATTERN = /<\/?chart_spec\b[^>]*>/gi

// A model sometimes hallucinates a chart as a markdown image/link pointing at a
// fake `chart_spec://` URL (full-width colon included), which marked turns into a
// broken <img>. Charts are never images (the spec contract forbids static image
// URLs), so we always neutralize this form: recover an embedded spec from the URL
// when one is actually there, otherwise drop the artifact entirely so no broken
// image leaks to the user. `!?` covers both `![alt](...)` and `[text](...)`.
const CHART_SPEC_PSEUDO_URL_PATTERN = /!?\[[^\]]*\]\(\s*(chart_spec[:：][^)]*?)\s*\)/gi

// Try to pull a real chart_spec JSON out of a `chart_spec://...` URL body; the
// model may cram the JSON into the URL, but usually it is just a placeholder.
const recoverSpecFromPseudoUrl = (url) => {
  let body = String(url || '').replace(/^chart_spec[:：]/i, '').replace(/^\/*/, '')
  try {
    body = decodeURIComponent(body)
  } catch (_error) {
    // keep the raw body when it is not valid percent-encoding
  }
  return parseChartSpec(body)
}

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

  // Neutralize pseudo `chart_spec://` image/link artifacts first and claim their
  // span, so the brace scanner below never re-parses the URL body and the text
  // splitter can drop (spec === null) or render (recovered spec) the range.
  CHART_SPEC_PSEUDO_URL_PATTERN.lastIndex = 0
  let pseudoMatch
  while ((pseudoMatch = CHART_SPEC_PSEUDO_URL_PATTERN.exec(source)) !== null) {
    ranges.push({
      start: pseudoMatch.index,
      end: pseudoMatch.index + pseudoMatch[0].length,
      spec: recoverSpecFromPseudoUrl(pseudoMatch[1])
    })
  }

  // Tag pairs always claim their span: a parse failure (empty body, malformed or
  // non-contract JSON) yields spec === null so the splitter drops the whole range
  // instead of leaking the literal tag + JSON into the rendered answer.
  CHART_SPEC_TAG_PATTERN.lastIndex = 0
  let tagMatch
  while ((tagMatch = CHART_SPEC_TAG_PATTERN.exec(source)) !== null) {
    ranges.push({
      start: tagMatch.index,
      end: tagMatch.index + tagMatch[0].length,
      spec: parseChartSpec(tagMatch[1])
    })
  }

  CHART_SPEC_FENCE_PATTERN.lastIndex = 0
  let fenceMatch
  while ((fenceMatch = CHART_SPEC_FENCE_PATTERN.exec(source)) !== null) {
    const infoText = fenceMatch[1] || ''
    const bodyText = fenceMatch[2] || ''
    // The info fallback keeps single-line fences (```{...}```) parseable, where
    // the whole body lands in the info capture.
    const spec = parseChartSpec(bodyText) || parseChartSpec(infoText)
    if (spec || fenceLooksChartIntended(infoText, bodyText)) {
      ranges.push({ start: fenceMatch.index, end: fenceMatch.index + fenceMatch[0].length, spec })
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

  // Orphan open/close tags left over after pair/JSON claiming (an unclosed tag
  // ahead of streaming JSON, or a stray </chart_spec>) are dropped as bare
  // tokens so they never show as literal text.
  CHART_SPEC_ORPHAN_TAG_PATTERN.lastIndex = 0
  let orphanMatch
  while ((orphanMatch = CHART_SPEC_ORPHAN_TAG_PATTERN.exec(source)) !== null) {
    if (isClaimed(orphanMatch.index)) continue
    ranges.push({ start: orphanMatch.index, end: orphanMatch.index + orphanMatch[0].length, spec: null })
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

export const extractChartSpecsFromText = (text) => collectChartSpecRanges(String(text || ''))
  .map((range) => range.spec)
  .filter(Boolean)

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
    // A null spec is an unrecoverable artifact (e.g. a broken `chart_spec://`
    // image): drop its text without emitting a chart so nothing renders.
    if (range.spec) segments.push({ type: 'chart', spec: range.spec })
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
