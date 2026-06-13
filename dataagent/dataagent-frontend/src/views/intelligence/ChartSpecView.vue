<template>
  <div class="chart-spec-view">
    <div v-if="toolbarVisible" class="chart-spec-toolbar">
      <button
        v-if="canToggleType"
        type="button"
        class="chart-spec-btn"
        data-action="toggle-type"
        @click="toggleChartType"
      >{{ effectiveChartType === 'bar' ? '折线' : '柱状' }}</button>
      <button
        v-if="canToggleView"
        type="button"
        class="chart-spec-btn"
        data-action="toggle-view"
        @click="viewMode = viewMode === 'data' ? 'chart' : 'data'"
      >{{ viewMode === 'data' ? '查看图表' : '查看数据' }}</button>
      <button
        v-if="canExportImage"
        type="button"
        class="chart-spec-btn"
        data-action="download-png"
        @click="downloadPng"
      >下载图片</button>
      <button
        v-if="canCopyImage"
        type="button"
        class="chart-spec-btn"
        data-action="copy-image"
        @click="copyImage"
      >{{ imageCopied ? '已复制' : '复制图片' }}</button>
      <button
        v-if="datasetRows.length"
        type="button"
        class="chart-spec-btn"
        data-action="export-csv"
        @click="exportCsv"
      >导出CSV</button>
    </div>

    <ResultDataTable
      v-if="showDataTable"
      :columns="datasetColumns"
      :rows="datasetRows"
      :title="specTitle"
      class="chart-spec-data-table"
    />

    <div v-else-if="renderState === 'renderable' && option" ref="chartCanvasRef" class="chart-spec-canvas" />
    <div v-else-if="renderState === 'empty'" class="chart-spec-empty">图表暂无可渲染数据</div>
    <div v-else class="chart-spec-empty">{{ renderError || '图表无法渲染' }}</div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
import { buildChartRenderModel } from './chartSpec'
import { downloadCsv, exportFilename } from '@/utils/tableExport'
import ResultDataTable from './components/ResultDataTable.vue'

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
  spec: {
    type: [Object, String],
    default: null
  }
})

const chartCanvasRef = ref(null)
const viewMode = ref('chart')
const typeOverride = ref('')
const imageCopied = ref(false)
let imageCopiedTimer = 0

const baseRenderModel = computed(() => buildChartRenderModel(props.spec))
const baseSpec = computed(() => baseRenderModel.value?.spec || null)
const baseChartType = computed(() => String(baseSpec.value?.chart_type || ''))

const effectiveChartType = computed(() => typeOverride.value || baseChartType.value)

// 柱/折切换通过覆盖 chart_type 与 series.type 重建渲染模型，原 spec 不变。
const renderModel = computed(() => {
  if (!typeOverride.value || !baseSpec.value) return baseRenderModel.value
  const overridden = {
    ...baseSpec.value,
    chart_type: typeOverride.value,
    series: (baseSpec.value.series || []).map((series) => ({ ...series, type: typeOverride.value }))
  }
  return buildChartRenderModel(overridden)
})

const renderState = computed(() => String(renderModel.value?.state || 'empty'))
const renderKind = computed(() => String(renderModel.value?.kind || ''))
const renderError = computed(() => String(renderModel.value?.errorText || '').trim())

const specTitle = computed(() => String(baseSpec.value?.title || '').trim() || 'chart')
const datasetRows = computed(() => (Array.isArray(baseSpec.value?.dataset) ? baseSpec.value.dataset : []))
const datasetColumns = computed(() => {
  const columns = Array.isArray(baseSpec.value?.columns) ? baseSpec.value.columns.filter(Boolean) : []
  if (columns.length) return columns
  const firstRow = datasetRows.value[0]
  return firstRow && typeof firstRow === 'object' ? Object.keys(firstRow) : []
})

const isTableSpec = computed(() => renderKind.value === 'table' || baseChartType.value === 'table')
const showDataTable = computed(() => {
  if (renderState.value !== 'renderable') return false
  return isTableSpec.value || viewMode.value === 'data'
})

const toolbarVisible = computed(() => renderState.value === 'renderable' && (datasetRows.value.length > 0 || renderKind.value === 'echarts'))
const canToggleView = computed(() => renderState.value === 'renderable' && !isTableSpec.value && datasetRows.value.length > 0)
const canToggleType = computed(() => ['bar', 'line'].includes(baseChartType.value) && !showDataTable.value)
const canExportImage = computed(() => renderState.value === 'renderable' && !showDataTable.value && renderKind.value === 'echarts')
const clipboardImageSupported = typeof window !== 'undefined'
  && typeof window.ClipboardItem !== 'undefined'
  && Boolean(typeof navigator !== 'undefined' && navigator.clipboard?.write)
  && Boolean(typeof window !== 'undefined' && window.isSecureContext)
const canCopyImage = computed(() => canExportImage.value && clipboardImageSupported)

// Mirror the chart polish applied to tool-call charts so inline conclusion
// charts stay visually consistent with the ones rendered below tool blocks.
const option = computed(() => {
  if (renderModel.value?.kind !== 'echarts') return null
  const baseOption = renderModel.value.option
  if (!baseOption) return null
  try {
    const opt = JSON.parse(JSON.stringify(baseOption))

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

    if (!opt.legend) {
      opt.legend = { show: true, bottom: 0 }
    } else {
      opt.legend.show = true
      if (opt.legend.bottom === undefined) opt.legend.bottom = 0
    }

    if (!opt.grid) opt.grid = {}
    if (opt.grid.bottom === undefined) opt.grid.bottom = 35

    return opt
  } catch (_error) {
    return baseOption
  }
})

let chartRefreshFrame = 0
let chartInstance = null
let chartResizeObserver = null

const toggleChartType = () => {
  const next = effectiveChartType.value === 'bar' ? 'line' : 'bar'
  typeOverride.value = next === baseChartType.value ? '' : next
}

const chartImageDataUrl = () => {
  if (!chartInstance) return ''
  try {
    return chartInstance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
  } catch (_error) {
    return ''
  }
}

const downloadPng = () => {
  const dataUrl = chartImageDataUrl()
  if (!dataUrl || typeof document === 'undefined') return
  const link = document.createElement('a')
  link.href = dataUrl
  link.download = exportFilename(specTitle.value, 'png')
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

const copyImage = async () => {
  const dataUrl = chartImageDataUrl()
  if (!dataUrl || !clipboardImageSupported) return
  try {
    const blob = await (await fetch(dataUrl)).blob()
    await navigator.clipboard.write([new window.ClipboardItem({ [blob.type]: blob })])
    imageCopied.value = true
    if (imageCopiedTimer) window.clearTimeout(imageCopiedTimer)
    imageCopiedTimer = window.setTimeout(() => {
      imageCopied.value = false
    }, 1500)
  } catch (_error) {
    // 复制失败时静默，按钮状态不变化即可感知
  }
}

const exportCsv = () => {
  downloadCsv(specTitle.value, datasetColumns.value, datasetRows.value)
}

const disposeChart = () => {
  if (chartResizeObserver) {
    chartResizeObserver.disconnect()
    chartResizeObserver = null
  }
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
}

const observeChartResize = (container) => {
  if (chartResizeObserver || typeof window === 'undefined' || typeof window.ResizeObserver === 'undefined') return
  chartResizeObserver = new window.ResizeObserver(() => {
    if (chartInstance) chartInstance.resize()
  })
  chartResizeObserver.observe(container)
}

const refreshChart = async () => {
  if (typeof window === 'undefined') return
  await nextTick()
  if (chartRefreshFrame) window.cancelAnimationFrame(chartRefreshFrame)
  chartRefreshFrame = window.requestAnimationFrame(() => {
    const container = chartCanvasRef.value
    if (!container || !option.value) return
    try {
      if (!chartInstance) {
        chartInstance = echarts.init(container, undefined, { renderer: 'canvas' })
        observeChartResize(container)
      }
      chartInstance.clear()
      chartInstance.setOption(option.value, { notMerge: true, lazyUpdate: false })
      chartInstance.resize()
    } catch (_error) {
      // Swallow redraw failures and let the empty/error state remain visible.
    }
  })
}

watch(
  () => [renderState.value, option.value, showDataTable.value],
  () => {
    if (renderState.value === 'renderable' && option.value && !showDataTable.value) {
      refreshChart()
      return
    }
    disposeChart()
  },
  { deep: true }
)

watch(
  () => props.spec,
  () => {
    viewMode.value = 'chart'
    typeOverride.value = ''
  }
)

onMounted(() => {
  if (renderState.value === 'renderable' && option.value && !showDataTable.value) refreshChart()
})

onBeforeUnmount(() => {
  if (chartRefreshFrame && typeof window !== 'undefined') {
    window.cancelAnimationFrame(chartRefreshFrame)
  }
  if (imageCopiedTimer && typeof window !== 'undefined') {
    window.clearTimeout(imageCopiedTimer)
  }
  disposeChart()
})
</script>

<style scoped>
.chart-spec-view {
  margin: 12px 0;
}

.chart-spec-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  margin-bottom: 6px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.chart-spec-view:hover .chart-spec-toolbar,
.chart-spec-view:focus-within .chart-spec-toolbar {
  opacity: 1;
}

.chart-spec-btn {
  padding: 4px 10px;
  border: 1px solid #dbe3ec;
  border-radius: 8px;
  background: #fff;
  color: #31567a;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}

.chart-spec-btn:hover {
  border-color: #4f81ff;
  color: #1d3f5e;
}

.chart-spec-data-table {
  margin-top: 0;
}

.chart-spec-canvas {
  display: block;
  box-sizing: border-box;
  min-height: 340px;
  height: 340px;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  border-radius: 14px;
  background: #f9fafc;
  border: 1px solid #eef1f5;
  padding: 8px;
}

.chart-spec-empty {
  color: #8da0b3;
  font-size: 13px;
}
</style>
