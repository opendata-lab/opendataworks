<template>
  <div class="tool-output" :class="{ failed: hasError, 'tool-output-shell': showTrace, 'tool-output-chart-direct': isDirectChart, 'tool-output-flat': isFlat }">
    <div v-if="showTrace" class="shell-trace">
      <button
        v-if="traceSummaryInteractive"
        type="button"
        class="shell-trace-summary"
        @click="togglePanel"
      >
        <svg class="tool-output-icon shell-trace-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path v-for="(d, index) in iconPaths" :key="index" :d="d" />
        </svg>
        <span class="shell-trace-summary-text">
          {{ traceSummaryText }}
        </span>
        <span class="shell-trace-summary-status" :class="`is-${traceStatusTone}`">
          {{ statusLabel }}
        </span>
        <svg class="shell-trace-chevron-icon" :class="{ open: panelOpen }" viewBox="0 0 12 12" fill="none"><path d="M3 4.5L6 7.5L9 4.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>

      <div v-else class="shell-trace-summary shell-trace-summary-static">
        <svg class="tool-output-icon shell-trace-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path v-for="(d, index) in iconPaths" :key="index" :d="d" />
        </svg>
        <span class="shell-trace-summary-text">
          {{ traceSummaryText }}
        </span>
        <span class="shell-trace-summary-status" :class="`is-${traceStatusTone}`">
          {{ statusLabel }}
        </span>
      </div>

      <div v-if="tracePanelVisible" class="tool-output-panel shell-trace-panel">
        <div class="tool-output-body-scroll">
          <pre v-if="traceCommand" class="shell-trace-command"><code>{{ traceCommandPrefix }}{{ traceCommand }}</code></pre>
          <div v-if="traceDescription && traceDescription !== traceCommand" class="shell-trace-description">
            {{ traceDescription }}
          </div>
          <template v-if="traceOutputText">
            <div v-if="showTraceMarkdown" class="tool-markdown">
              <div class="tool-markdown-body" v-html="traceMarkdownExpanded ? renderedTraceMarkdown : renderedTraceMarkdownPreview" />
              <button
                v-if="traceMarkdownCollapsible"
                type="button"
                class="tool-markdown-toggle"
                @click="traceMarkdownExpanded = !traceMarkdownExpanded"
              >
                {{ traceMarkdownExpanded ? '收起' : '展开...' }}
              </button>
            </div>
            <pre v-else class="shell-trace-output"><code>{{ traceOutputText }}</code></pre>
          </template>
          <div v-else class="shell-trace-empty">无输出</div>
        </div>
      </div>
    </div>

    <div
      v-if="showMainHeader"
      class="tool-output-head"
      :class="{ 'is-interactive': showMainToggle }"
      @click="showMainToggle ? togglePanel() : null"
    >
      <div class="tool-output-head-main">
        <svg class="tool-output-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path v-for="(d, index) in iconPaths" :key="index" :d="d" />
        </svg>
        <div class="tool-output-head-content">
          <div class="tool-output-label">{{ displayLabel }}</div>

          <div class="tool-output-meta" v-if="metaItems.length > 0">
            <span v-for="(item, index) in metaItems" :key="index">
              <template v-if="index > 0"> · </template>
              <span :class="{ 'is-failed': hasError && item === statusLabel }">{{ item }}</span>
            </span>
          </div>
        </div>
      </div>
      <div class="tool-output-head-right">
        <div v-if="scriptName" class="tool-output-chip">{{ scriptName }}</div>
        <svg v-if="showMainToggle" class="tool-output-head-chevron" :class="{ open: panelOpen }" viewBox="0 0 12 12" fill="none"><path d="M3 4.5L6 7.5L9 4.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </div>
    </div>

    <div v-if="summaryText && showMainHeader" class="tool-output-summary">{{ summaryText }}</div>

    <div v-if="errorText && showMainHeader" class="tool-output-error">{{ errorText }}</div>

    <!-- Direct chart rendering (no panel wrapper) -->
    <template v-if="isDirectChart">
      <div v-if="chartRenderState === 'invalid'" class="tool-output-error">{{ chartRenderError }}</div>
      <div v-else-if="chartRenderState === 'error' && !errorText" class="tool-output-error">{{ chartRenderError }}</div>

      <div v-if="chartRenderState === 'renderable' && chartRenderKind === 'table'" class="tool-table-wrap">
        <table class="tool-table">
          <thead>
            <tr>
              <th v-for="column in chartColumns" :key="column">{{ column }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in chartRows" :key="rowIndex">
              <td v-for="column in chartColumns" :key="column">{{ row[column] }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div
        v-else-if="chartRenderState === 'renderable' && chartOption"
        ref="chartCanvasRef"
        class="tool-chart"
      />
      <div v-else-if="chartRenderState === 'empty'" class="tool-output-empty">图表暂无可渲染数据</div>
      <div v-else-if="chartRenderState !== 'invalid' && chartRenderState !== 'error'" class="tool-output-empty">图表数据为空</div>

      <pre v-if="showChartRawText" class="tool-code tool-code-light"><code>{{ normalizedRawText }}</code></pre>
    </template>

    <div v-if="mainPanelVisible && !isDirectChart" class="tool-output-panel">
      <div class="tool-output-body-scroll">
        <template v-if="kind === 'sql_execution'">
          <pre v-if="sqlText" class="tool-code"><code>{{ sqlText }}</code></pre>

          <div v-if="columns.length && rows.length" class="tool-table-wrap">
            <table class="tool-table">
              <thead>
                <tr>
                  <th v-for="column in columns" :key="column">{{ column }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, rowIndex) in rows" :key="rowIndex">
                  <td v-for="column in columns" :key="column">{{ row[column] }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else-if="!errorText" class="tool-output-empty">无数据</div>
        </template>

        <template v-else-if="kind === 'chart_spec'">
          <div v-if="chartRenderState === 'invalid'" class="tool-output-error">{{ chartRenderError }}</div>
          <div v-else-if="chartRenderState === 'error' && !errorText" class="tool-output-error">{{ chartRenderError }}</div>

          <div v-if="chartRenderState === 'renderable' && chartRenderKind === 'table'" class="tool-table-wrap">
            <table class="tool-table">
              <thead>
                <tr>
                  <th v-for="column in chartColumns" :key="column">{{ column }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, rowIndex) in chartRows" :key="rowIndex">
                  <td v-for="column in chartColumns" :key="column">{{ row[column] }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div
            v-else-if="chartRenderState === 'renderable' && chartOption"
            ref="chartCanvasRef"
            class="tool-chart"
          />
          <div v-else-if="chartRenderState === 'empty'" class="tool-output-empty">图表暂无可渲染数据</div>
          <div v-else-if="chartRenderState !== 'invalid' && chartRenderState !== 'error'" class="tool-output-empty">图表数据为空</div>

          <pre v-if="showChartRawText" class="tool-code tool-code-light"><code>{{ normalizedRawText }}</code></pre>
        </template>

        <template v-else-if="kind === 'python_execution'">
          <pre v-if="stdoutText" class="tool-code"><code>{{ stdoutText }}</code></pre>
          <pre v-if="resultText" class="tool-code tool-code-light"><code>{{ resultText }}</code></pre>
        </template>

        <template v-else-if="showRawPayload">
          <div v-if="showRawMarkdown" class="tool-markdown">
            <div class="tool-markdown-body" v-html="rawMarkdownExpanded ? renderedRawMarkdown : renderedRawMarkdownPreview" />
            <button
              v-if="rawMarkdownCollapsible"
              type="button"
              class="tool-markdown-toggle"
              @click="rawMarkdownExpanded = !rawMarkdownExpanded"
            >
              {{ rawMarkdownExpanded ? '收起' : '展开...' }}
            </button>
          </div>
          <pre v-else class="tool-code tool-code-light"><code>{{ normalizedRawText }}</code></pre>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'
import * as echarts from 'echarts/core'
import { use } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { buildChartRenderModel, extractChartSpec, extractTextParts, parseMaybeJson } from './chartSpec'
import { describeToolAction, formatSkillBootstrapLabel } from './toolPresentation'

use([
  CanvasRenderer,
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent
])

const props = defineProps({
  tool: {
    type: Object,
    default: () => ({})
  }
})

const chartCanvasRef = ref(null)
const panelOpen = ref(false)
const panelTouched = ref(false)
const nowTick = ref(Date.now())
const traceMarkdownExpanded = ref(false)
const rawMarkdownExpanded = ref(false)

const isPlainObject = (value) => value && typeof value === 'object' && !Array.isArray(value)

const MARKDOWN_PREVIEW_LINES = 5

// Leading icons so each tool-call box is recognizable without expanding it.
const TOOL_ICON_PATHS = {
  shell: ['M4 17l6-6-6-6', 'M12 19h8'],
  read: ['M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z', 'M14 3v5h5', 'M9 13h6', 'M9 17h4'],
  list: ['M3 7a2 2 0 0 1 2-2h3.5l2 2H19a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'],
  search: ['M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z', 'M21 21l-4.5-4.5'],
  edit: ['M12 20h9', 'M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z'],
  skill: ['M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z'],
  sql: ['M12 3c4.97 0 9 1.34 9 3s-4.03 3-9 3-9-1.34-9-3 4.03-3 9-3z', 'M21 6v6c0 1.66-4.03 3-9 3s-9-1.34-9-3V6', 'M21 12v6c0 1.66-4.03 3-9 3s-9-1.34-9-3v-6'],
  chart: ['M3 21h18', 'M7 21V11', 'M12 21V5', 'M17 21v-7'],
  python: ['M8 18l-6-6 6-6', 'M16 6l6 6-6 6'],
  tool: ['M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.4-.6-.6-2.4z']
}

const escapeHtml = (text) => String(text || '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')

const TOOL_LINE_PREFIX_PATTERN = /^\s*\d+\s*(?:→|->)\s?/

const stripToolLinePrefixes = (text) => {
  const value = String(text || '')
  if (!value.trim()) return ''

  const lines = value.split('\n')
  const nonEmptyLines = lines.filter((line) => line.trim().length > 0)
  if (!nonEmptyLines.length) return value

  const prefixedCount = nonEmptyLines.filter((line) => TOOL_LINE_PREFIX_PATTERN.test(line)).length
  const shouldStrip = prefixedCount >= 2 || prefixedCount === nonEmptyLines.length
  if (!shouldStrip) return value

  return lines.map((line) => line.replace(TOOL_LINE_PREFIX_PATTERN, '')).join('\n')
}

const renderMarkdown = (text) => {
  if (!text) return ''
  try {
    return marked.parse(escapeHtml(text), { breaks: true, gfm: true })
  } catch (_error) {
    return escapeHtml(text)
  }
}

const looksLikeMarkdown = (text) => {
  const value = String(text || '').trim()
  if (!value) return false
  return /^#{1,6}\s/m.test(value)
    || /^>\s/m.test(value)
    || /^[-*+]\s/m.test(value)
    || /^\d+\.\s/m.test(value)
    || /```/.test(value)
    || /\[[^\]]+\]\([^)]+\)/.test(value)
}

const normalizeOutput = (value) => {
  const normalizedChart = extractChartSpec(value)
  if (normalizedChart) return normalizedChart

  if (isPlainObject(value) && value.kind) return value
  if (Array.isArray(value)) {
    for (const item of value) {
      if (isPlainObject(item) && item.kind) return item
      const parsed = parseMaybeJson(extractTextParts(item))
      if (isPlainObject(parsed) && parsed.kind) return parsed
    }
  }

  const parsed = parseMaybeJson(extractTextParts(value))
  if (isPlainObject(parsed) && parsed.kind) return parsed
  return null
}

const prettyPrint = (value) => {
  if (!value && value !== 0) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch (_error) {
    return String(value)
  }
}

const outputPayload = computed(() => normalizeOutput(props.tool?.output) || {})
const kind = computed(() => String(outputPayload.value.kind || 'raw'))
const toolName = computed(() => String(props.tool?.name || '').trim())
const toolAction = computed(() => describeToolAction({
  name: toolName.value,
  input: props.tool?.input
}))
const toolNameLower = computed(() => toolAction.value.name.toLowerCase())
const bootstrapSkillName = computed(() => String(props.tool?._skillBootstrapName || '').trim())
const bootstrapSkillLabel = computed(() => (
  bootstrapSkillName.value ? formatSkillBootstrapLabel(bootstrapSkillName.value) : ''
))
const scriptName = computed(() => String(outputPayload.value.script || '').trim())
const summaryText = computed(() => String(outputPayload.value.summary || outputPayload.value.description || '').trim())
const sqlText = computed(() => String(outputPayload.value.sql || '').trim())
const rows = computed(() => Array.isArray(outputPayload.value.rows) ? outputPayload.value.rows : [])
const columns = computed(() => {
  if (Array.isArray(outputPayload.value.columns) && outputPayload.value.columns.length) {
    return outputPayload.value.columns
  }
  const firstRow = rows.value[0]
  return isPlainObject(firstRow) ? Object.keys(firstRow) : []
})
const normalizeDisplayText = (value) => stripToolLinePrefixes(String(value || ''))

const stdoutText = computed(() => normalizeDisplayText(outputPayload.value.stdout || '').trim())
const resultText = computed(() => normalizeDisplayText(prettyPrint(outputPayload.value.result)).trim())
const rawText = computed(() => {
  const source = outputPayload.value.kind ? outputPayload.value : props.tool?.output
  return prettyPrint(source)
})
const normalizedRawText = computed(() => normalizeDisplayText(rawText.value).trim())
const errorText = computed(() => String(outputPayload.value.error || '').trim())
const hasError = computed(() => Boolean(errorText.value) || String(props.tool?.status || '') === 'failed')
const rowCountText = computed(() => {
  const value = Number(outputPayload.value.row_count)
  return Number.isFinite(value) && value > 0 ? `${value} 行` : ''
})
const durationText = computed(() => {
  const value = Number(outputPayload.value.duration_ms)
  return Number.isFinite(value) && value >= 0 ? `${value} ms` : ''
})

const displayLabel = computed(() => {
  if (outputPayload.value.tool_label) return String(outputPayload.value.tool_label)

  const map = {
    sql_execution: '执行查询',
    chart_spec: '生成图表',
    python_execution: '执行代码'
  }
  if (map[kind.value]) return map[kind.value]

  if (toolAction.value.kind === 'tool' && toolNameLower.value.includes('image')) {
    return '生成图片'
  }

  return toolAction.value.label
})

const iconKind = computed(() => {
  if (kind.value === 'sql_execution') return 'sql'
  if (kind.value === 'chart_spec') return 'chart'
  if (kind.value === 'python_execution') return 'python'
  if (toolAction.value.kind === 'tool' && toolNameLower.value.includes('image')) return 'chart'
  const actionKind = toolAction.value.kind
  return TOOL_ICON_PATHS[actionKind] ? actionKind : 'tool'
})
const iconPaths = computed(() => TOOL_ICON_PATHS[iconKind.value] || TOOL_ICON_PATHS.tool)

const traceKind = computed(() => (toolAction.value.isTrace ? toolAction.value.kind : ''))

const showTrace = computed(() => Boolean(traceKind.value))
const showMainHeader = computed(() => {
  if (showTrace.value) return false
  if (isDirectChart.value) return false
  return true
})

const traceOutputText = computed(() => {
  const directText = extractTextParts(props.tool?.output).trim()
  if (directText) return normalizeDisplayText(directText).trim()
  return normalizedRawText.value
})
const traceOutputLines = computed(() => String(traceOutputText.value || '').split('\n'))
const traceMarkdownSource = computed(() => String(traceOutputText.value || '').trim())
const showTraceMarkdown = computed(() => {
  if (!traceOutputText.value) return false
  if (traceKind.value === 'skill') return looksLikeMarkdown(traceMarkdownSource.value)
  const path = toolAction.value.path || toolAction.value.directory || traceCommand.value
  if (/\.(md|markdown)$/i.test(String(path || '').trim())) {
    return looksLikeMarkdown(traceMarkdownSource.value) || Boolean(traceMarkdownSource.value)
  }
  return false
})
const traceMarkdownCollapsible = computed(() => showTraceMarkdown.value && traceOutputLines.value.length > MARKDOWN_PREVIEW_LINES)
const traceMarkdownPreview = computed(() => {
  if (!traceMarkdownCollapsible.value) return traceMarkdownSource.value
  return traceOutputLines.value.slice(0, MARKDOWN_PREVIEW_LINES).join('\n')
})
const renderedTraceMarkdown = computed(() => renderMarkdown(traceMarkdownSource.value))
const renderedTraceMarkdownPreview = computed(() => renderMarkdown(traceMarkdownPreview.value))

const rawOutputLines = computed(() => String(normalizedRawText.value || '').split('\n'))
const showRawMarkdown = computed(() => showRawPayload.value && looksLikeMarkdown(normalizedRawText.value))
const rawMarkdownCollapsible = computed(() => showRawMarkdown.value && rawOutputLines.value.length > MARKDOWN_PREVIEW_LINES)
const rawMarkdownPreview = computed(() => {
  if (!rawMarkdownCollapsible.value) return normalizedRawText.value
  return rawOutputLines.value.slice(0, MARKDOWN_PREVIEW_LINES).join('\n')
})
const renderedRawMarkdown = computed(() => renderMarkdown(normalizedRawText.value))
const renderedRawMarkdownPreview = computed(() => renderMarkdown(rawMarkdownPreview.value))

const traceCommand = computed(() => toolAction.value.detail)

const traceCommandPrefix = computed(() => (traceKind.value === 'shell' ? '$ ' : ''))

const traceDescription = computed(() => {
  if (bootstrapSkillLabel.value) return bootstrapSkillLabel.value
  if (traceKind.value === 'read') return toolAction.value.description || '正在读取文件'
  if (traceKind.value === 'list') return toolAction.value.description || '正在查看目录'
  if (traceKind.value === 'search') return toolAction.value.description || '正在搜索文件'
  if (traceKind.value === 'edit') return toolAction.value.description || '正在修改文件'
  if (traceKind.value === 'skill') return toolAction.value.description || '正在准备技能上下文'
  if (traceKind.value === 'shell') return toolAction.value.description || '正在执行命令'
  return toolAction.value.description
})

const traceSummaryText = computed(() => {
  if (bootstrapSkillLabel.value) return bootstrapSkillLabel.value
  const detail = traceCommand.value || traceDescription.value

  // A shell step that produces a chart_spec is the chart-generation tool call;
  // label it as such so it stays recognizable alongside the conclusion chart.
  if (kind.value === 'chart_spec') {
    const title = String(outputPayload.value.title || '').trim()
    return title ? `生成图表：${title}` : '生成图表'
  }

  if (traceKind.value === 'read') {
    return detail ? `读取文件：${detail}` : '正在读取文件'
  }
  if (traceKind.value === 'list') {
    return detail ? `查看目录：${detail}` : '正在查看目录'
  }
  if (traceKind.value === 'search') {
    return detail ? `搜索文件：${detail}` : '正在搜索文件'
  }
  if (traceKind.value === 'edit') {
    return detail ? `修改文件：${detail}` : '正在修改文件'
  }
  if (traceKind.value === 'skill') {
    return detail ? `执行技能：${detail}` : '正在准备技能上下文'
  }
  
  const leading = detail || displayLabel.value || toolName.value
  return leading ? `执行命令：${leading}` : '正在执行命令'
})

const traceStatusTone = computed(() => {
  const status = String(props.tool?.status || 'success')
  const callComplete = Boolean(props.tool?._callComplete)
  const runtimeStarted = Boolean(props.tool?._runtimeStarted)
  if (status === 'failed') return 'failed'
  if (!callComplete) return 'running'
  if (callComplete && !runtimeStarted) return 'success'
  if (status === 'pending' || status === 'streaming') return 'running'
  return 'success'
})

const toolStartedAt = computed(() => Number(props.tool?._startedAt || 0))
const elapsedSeconds = computed(() => {
  if (!toolStartedAt.value) return 0
  return Math.max(0, Math.floor((nowTick.value - toolStartedAt.value) / 1000))
})

const statusLabel = computed(() => {
  const status = String(props.tool?.status || 'success')
  const callComplete = Boolean(props.tool?._callComplete)
  const runtimeStarted = Boolean(props.tool?._runtimeStarted)

  if (kind.value === 'chart_spec') {
    if (!callComplete) return '正在发起生成图表'
    if (callComplete && !runtimeStarted) return '已发起生成图表'
    if (status === 'pending' || status === 'streaming') return '正在生成图表'
    if (status === 'failed') return '生成图表失败'
    return '已生成图表'
  }

  if (traceKind.value === 'shell') {
    if (!callComplete) return '正在发起命令'
    if (callComplete && !runtimeStarted) return '已发起命令'
    if (status === 'pending' || status === 'streaming') return `正在运行命令（${elapsedSeconds.value}s）`
    if (status === 'failed') return '命令失败'
    return '已运行命令'
  }

  if (traceKind.value === 'read') {
    if (!callComplete) return '正在发起读取'
    if (callComplete && !runtimeStarted) return '已发起读取'
    if (status === 'pending' || status === 'streaming') return '正在读取'
    if (status === 'failed') return '读取失败'
    return '已读取'
  }

  if (traceKind.value === 'list') {
    if (!callComplete) return '正在发起查看目录'
    if (callComplete && !runtimeStarted) return '已发起查看目录'
    if (status === 'pending' || status === 'streaming') return '正在查看目录'
    if (status === 'failed') return '查看目录失败'
    return '已查看目录'
  }

  if (traceKind.value === 'search') {
    if (!callComplete) return '正在发起搜索'
    if (callComplete && !runtimeStarted) return '已发起搜索'
    if (status === 'pending' || status === 'streaming') return '正在搜索'
    if (status === 'failed') return '搜索失败'
    return '已搜索'
  }

  if (traceKind.value === 'edit') {
    if (!callComplete) return '正在发起修改'
    if (callComplete && !runtimeStarted) return '已发起修改'
    if (status === 'pending' || status === 'streaming') return '正在修改'
    if (status === 'failed') return '修改失败'
    return '已修改'
  }

  if (traceKind.value === 'skill') {
    if (!callComplete) return '正在发起技能'
    if (callComplete && !runtimeStarted) return '已发起技能'
    if (status === 'pending' || status === 'streaming') return '正在加载技能'
    if (status === 'failed') return '技能加载失败'
    return '已加载技能'
  }

  if (status === 'pending') return '等待执行'
  if (status === 'streaming') return '执行中'
  if (status === 'failed') return '执行失败'
  return '执行完成'
})

const chartRenderModel = computed(() => (kind.value === 'chart_spec' ? buildChartRenderModel(outputPayload.value) : null))
const chartRenderState = computed(() => String(chartRenderModel.value?.state || 'empty'))
const chartRenderKind = computed(() => String(chartRenderModel.value?.kind || ''))
const chartRenderError = computed(() => String(chartRenderModel.value?.errorText || '').trim())
const chartColumns = computed(() => Array.isArray(chartRenderModel.value?.columns) ? chartRenderModel.value.columns : [])
const chartRows = computed(() => Array.isArray(chartRenderModel.value?.rows) ? chartRenderModel.value.rows : [])
const chartOption = computed(() => {
  if (kind.value !== 'chart_spec') return null
  const baseOption = chartRenderModel.value?.kind === 'echarts' ? chartRenderModel.value.option : null
  if (!baseOption) return null
  
  try {
    const opt = JSON.parse(JSON.stringify(baseOption))
    
    // Clean up LLM-generated title artifacts
    if (opt.title) {
      const cleanTitle = (t) => {
        if (t.text) {
          t.text = t.text.replace(/[\(（]?，?共?返回\d+(行数据|条数据|条结果)的?[\)）]?/g, '')
        }
        if (t.subtext && (t.subtext.includes('基于') || t.subtext.includes('绘制'))) {
          delete t.subtext
        }
      }
      if (Array.isArray(opt.title)) opt.title.forEach(cleanTitle)
      else cleanTitle(opt.title)
    }
    
    // Ensure legend is visible at the bottom
    if (!opt.legend) {
      opt.legend = { show: true, bottom: 0 }
    } else {
      opt.legend.show = true
      if (opt.legend.bottom === undefined) opt.legend.bottom = 0
    }
    
    // Reduce bottom whitespace
    if (!opt.grid) opt.grid = {}
    if (opt.grid.bottom === undefined) opt.grid.bottom = 35
    
    return opt
  } catch (e) {
    return baseOption
  }
})
const showChartRawText = computed(() => {
  if (kind.value !== 'chart_spec') return false
  return ['invalid', 'error'].includes(chartRenderState.value) && Boolean(normalizedRawText.value)
})
const showRawPayload = computed(() => Boolean(normalizedRawText.value) && !showTrace.value)

const isDirectChart = computed(() => {
  if (kind.value !== 'chart_spec') return false
  if (showTrace.value) return false
  return true
})

const isFlat = computed(() => kind.value === 'sql_execution')

const tracePanelAvailable = computed(() => {
  if (!showTrace.value) return false
  if (traceOutputText.value) return true
  if (['read', 'list', 'search'].includes(traceKind.value)) return false
  return Boolean(traceCommand.value || traceDescription.value)
})

const mainPanelAvailable = computed(() => {
  if (kind.value === 'sql_execution') return Boolean(sqlText.value || (columns.value.length && rows.value.length) || !errorText.value)
  if (kind.value === 'chart_spec') return true
  if (kind.value === 'python_execution') return Boolean(stdoutText.value || resultText.value)
  return Boolean(showRawPayload.value)
})

const hasExpandablePanel = computed(() => tracePanelAvailable.value || mainPanelAvailable.value)
const traceSummaryInteractive = computed(() => showTrace.value && hasExpandablePanel.value)
const showMainToggle = computed(() => !showTrace.value && !isDirectChart.value && hasExpandablePanel.value)
const tracePanelVisible = computed(() => showTrace.value && tracePanelAvailable.value && panelOpen.value)
const mainPanelVisible = computed(() => mainPanelAvailable.value && panelOpen.value)

const metaItems = computed(() => {
  const items = []
  
  if (traceStatusTone.value !== 'success') {
    items.push(statusLabel.value)
  }
  
  if (
    toolName.value
    && toolName.value !== 'Tool'
    && toolName.value !== displayLabel.value
    && !displayLabel.value.includes(toolName.value)
  ) {
    items.push(toolName.value)
  }
  
  if (kind.value === 'sql_execution' && rowCountText.value) {
    items.push(rowCountText.value)
  }
  
  if (kind.value === 'sql_execution' && durationText.value) {
    items.push(durationText.value)
  }
  
  return items
})

const toolInstanceKey = computed(() => {
  const stableId = String(props.tool?.id || '').trim()
  if (stableId) return stableId
  return [
    toolName.value,
    kind.value,
    toolAction.value.command,
    toolAction.value.path,
    toolAction.value.directory,
    toolAction.value.pattern,
    scriptName.value,
    summaryText.value
  ].filter(Boolean).join('|')
})

let chartRefreshFrame = 0
let chartInstance = null
let statusTimer = 0

const disposeChart = () => {
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
}

const refreshChart = async () => {
  if (typeof window === 'undefined') return
  await nextTick()
  if (chartRefreshFrame) window.cancelAnimationFrame(chartRefreshFrame)
  chartRefreshFrame = window.requestAnimationFrame(() => {
    const container = chartCanvasRef.value
    if (!container || !chartOption.value) return
    try {
      if (!chartInstance) {
        chartInstance = echarts.init(container, undefined, { renderer: 'canvas' })
      }
      chartInstance.clear()
      chartInstance.setOption(chartOption.value, { notMerge: true, lazyUpdate: false })
      chartInstance.resize()
    } catch (_error) {
      // Swallow redraw failures and let the empty/error state remain visible.
    }
  })
}

watch(
  () => [chartRenderState.value, chartOption.value, props.tool?.id, mainPanelVisible.value, isDirectChart.value],
  () => {
    if (chartRenderState.value === 'renderable' && chartOption.value && (mainPanelVisible.value || isDirectChart.value)) {
      refreshChart()
      return
    }
    disposeChart()
  },
  { deep: true }
)

const shouldAutoOpenPanel = () => {
  if (!hasExpandablePanel.value) return false
  const status = String(props.tool?.status || 'success')
  const callComplete = Boolean(props.tool?._callComplete)
  if (status === 'pending' || status === 'streaming') return true
  if (showTrace.value && !callComplete) return true
  return false
}

const togglePanel = () => {
  if (!hasExpandablePanel.value) return
  panelTouched.value = true
  panelOpen.value = !panelOpen.value
}

onMounted(() => {
  panelOpen.value = isDirectChart.value ? true : shouldAutoOpenPanel()
  if (chartRenderState.value === 'renderable' && chartOption.value && (mainPanelVisible.value || isDirectChart.value)) {
    refreshChart()
  }

  if (typeof window !== 'undefined') {
    statusTimer = window.setInterval(() => {
      nowTick.value = Date.now()
    }, 1000)
  }
})

watch(
  () => [props.tool?.status, props.tool?._callComplete, props.tool?._runtimeStarted, hasExpandablePanel.value],
  () => {
    if (panelTouched.value) return
    panelOpen.value = shouldAutoOpenPanel()
  },
  { immediate: true }
)

watch(
  () => toolInstanceKey.value,
  () => {
    panelTouched.value = false
    panelOpen.value = shouldAutoOpenPanel()
    traceMarkdownExpanded.value = false
    rawMarkdownExpanded.value = false
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  if (chartRefreshFrame && typeof window !== 'undefined') {
    window.cancelAnimationFrame(chartRefreshFrame)
  }
  if (statusTimer && typeof window !== 'undefined') {
    window.clearInterval(statusTimer)
  }
  disposeChart()
})
</script>

<style scoped>
.tool-output {
  padding: 14px 16px;
  border: 1px solid #eff1f5;
  border-radius: 14px;
  background: #ffffff;
}

.tool-output-shell {
  padding: 2px 0;
  border: none;
  border-radius: 0;
  background: transparent;
}

.tool-output-chart-direct {
  padding: 0;
  border: none;
  border-radius: 0;
  background: transparent;
}

.tool-output-flat {
  padding: 0;
  border: none;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.tool-output.failed {
  border-color: rgba(190, 24, 93, 0.15);
  background: #fff8fb;
}

.tool-output-shell.failed,
.tool-output-chart-direct.failed {
  background: transparent;
}

.shell-trace + .tool-output-head,
.shell-trace + .tool-output-summary,
.shell-trace + .tool-output-error {
  margin-top: 14px;
}

.shell-trace-summary {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.shell-trace-summary-static {
  cursor: default;
}

.shell-trace-summary-text {
  flex: 1;
  min-width: 0;
  color: #6a6a6a;
  font-size: 14px;
  line-height: 1.55;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.shell-trace-summary-status {
  font-size: 12px;
  font-weight: 600;
  color: #8a8a8a;
}

.shell-trace-summary-status.is-running {
  color: #6b7280;
}

.shell-trace-summary-status.is-success {
  color: #6b7280;
}

.shell-trace-summary-status.is-failed {
  color: #9f1239;
}

.shell-trace-chevron-icon {
  width: 14px;
  height: 14px;
  color: #A0AABF;
  flex-shrink: 0;
  transition: transform 0.18s ease;
}

.shell-trace-chevron-icon.open {
  transform: rotate(180deg);
}

.tool-output-panel {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid #eff1f5;
  border-radius: 12px;
  background: #ffffff;
}

.tool-output-body-scroll {
  max-height: 360px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.shell-trace-panel {
  margin-top: 6px;
  border: 1px solid #E5EAF1;
  background: #F9FAFC;
}

.shell-trace-command,
.shell-trace-output {
  margin: 12px 0 0;
  padding: 0;
  background: transparent;
  color: #3f3f3f;
  font-size: 12px;
  line-height: 1.7;
  overflow: visible;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
}

.shell-trace-description {
  margin-top: 10px;
  color: #7b7b7b;
  font-size: 12px;
  line-height: 1.6;
}

.shell-trace-empty {
  margin-top: 12px;
  color: #9a9a9a;
  font-size: 12px;
  line-height: 1.6;
}

.tool-markdown {
  margin-top: 12px;
  padding: 14px 16px;
  border-radius: 14px;
  background: #ffffff;
  border: 1px solid #dbe3ec;
}

.tool-markdown-body {
  color: #334155;
  font-size: 13px;
  line-height: 1.7;
  word-break: break-word;
}

.tool-markdown-body :deep(h1),
.tool-markdown-body :deep(h2),
.tool-markdown-body :deep(h3),
.tool-markdown-body :deep(h4),
.tool-markdown-body :deep(h5),
.tool-markdown-body :deep(h6) {
  margin: 0 0 10px;
  color: #162131;
  font-weight: 700;
  line-height: 1.4;
}

.tool-markdown-body :deep(p),
.tool-markdown-body :deep(ul),
.tool-markdown-body :deep(ol),
.tool-markdown-body :deep(blockquote) {
  margin: 0 0 10px;
}

.tool-markdown-body :deep(ul),
.tool-markdown-body :deep(ol) {
  padding-left: 18px;
}

.tool-markdown-body :deep(code) {
  padding: 1px 5px;
  border-radius: 6px;
  background: #f4f7fb;
  color: #1f3b57;
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
}

.tool-markdown-body :deep(pre) {
  margin: 10px 0;
  padding: 12px 14px;
  border-radius: 12px;
  background: #102033;
  color: #edf5ff;
  overflow: visible;
}

.tool-markdown-body :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.tool-markdown-toggle {
  margin-top: 6px;
  padding: 0;
  border: none;
  background: transparent;
  color: #31567a;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.tool-markdown-toggle:hover {
  color: #1d3f5e;
}

.tool-output-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.tool-output-head.is-interactive {
  cursor: pointer;
  user-select: none;
}

.tool-output-head-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}

.tool-output-head-content {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.tool-output-icon {
  width: 16px;
  height: 16px;
  color: #4F81FF;
  flex-shrink: 0;
}

.tool-output.failed .tool-output-icon {
  color: #be185d;
}

.shell-trace-icon {
  width: 15px;
  height: 15px;
  color: #6b7280;
}

.shell-trace-summary-static .shell-trace-icon,
.shell-trace-summary .shell-trace-icon {
  color: #6b7280;
}

.tool-output-head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-output-head-chevron {
  width: 14px;
  height: 14px;
  color: #A0AABF;
  flex-shrink: 0;
  transition: transform 0.18s ease;
}

.tool-output-head-chevron.open {
  transform: rotate(-180deg);
}

.tool-output-status-check {
  width: 14px;
  height: 14px;
  color: #4F81FF;
  flex-shrink: 0;
}

.tool-output-label {
  font-size: 14px;
  font-weight: 700;
  color: #162131;
}

.tool-output-meta {
  margin-top: 4px;
  font-size: 12px;
  color: #607185;
}

.tool-output-chip {
  padding: 5px 10px;
  border-radius: 999px;
  background: #eef6ff;
  color: #31567a;
  font-size: 12px;
  font-weight: 600;
}

.tool-output-summary {
  margin-top: 12px;
  font-size: 13px;
  line-height: 1.65;
  color: #334155;
}

.tool-output-error {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(190, 24, 93, 0.08);
  color: #9f1239;
  font-size: 13px;
  line-height: 1.6;
}

.tool-code {
  margin: 14px 0 0;
  padding: 14px 16px;
  border-radius: 14px;
  background: #102033;
  color: #edf5ff;
  font-size: 12px;
  line-height: 1.7;
  overflow: visible;
}

.tool-code-light {
  background: #f3f7fb;
  color: #233142;
}

.tool-table-wrap {
  margin-top: 14px;
  border: 1px solid #e1e8f0;
  border-radius: 14px;
  background: #fff;
  overflow-x: auto;
  overscroll-behavior: contain;
}

.tool-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 480px;
}

.tool-table th,
.tool-table td {
  padding: 10px 12px;
  border-bottom: 1px solid #edf2f7;
  text-align: left;
  font-size: 12px;
  color: #233142;
  white-space: pre-wrap;
  word-break: break-word;
  vertical-align: top;
}

.tool-table th {
  background: #f8fbff;
  color: #607185;
  font-weight: 700;
  white-space: nowrap;
}

.tool-chart {
  display: block;
  margin-top: 8px;
  min-height: 340px;
  height: 340px;
  width: 100%;
  min-width: 0;
  border-radius: 14px;
  background: #F9FAFC;
  border: 1px solid #EEF1F5;
  padding: 8px;
}

.tool-output-empty {
  margin-top: 14px;
  color: #8da0b3;
  font-size: 13px;
}
</style>
