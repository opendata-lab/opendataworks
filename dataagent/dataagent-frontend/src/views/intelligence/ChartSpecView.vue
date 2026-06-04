<template>
  <div class="chart-spec-view">
    <div v-if="renderState === 'renderable' && renderKind === 'table'" class="chart-spec-table-wrap">
      <table class="chart-spec-table">
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

const renderModel = computed(() => buildChartRenderModel(props.spec))
const renderState = computed(() => String(renderModel.value?.state || 'empty'))
const renderKind = computed(() => String(renderModel.value?.kind || ''))
const renderError = computed(() => String(renderModel.value?.errorText || '').trim())
const columns = computed(() => (Array.isArray(renderModel.value?.columns) ? renderModel.value.columns : []))
const rows = computed(() => (Array.isArray(renderModel.value?.rows) ? renderModel.value.rows : []))

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
  () => [renderState.value, option.value],
  () => {
    if (renderState.value === 'renderable' && option.value) {
      refreshChart()
      return
    }
    disposeChart()
  },
  { deep: true }
)

onMounted(() => {
  if (renderState.value === 'renderable' && option.value) refreshChart()
})

onBeforeUnmount(() => {
  if (chartRefreshFrame && typeof window !== 'undefined') {
    window.cancelAnimationFrame(chartRefreshFrame)
  }
  disposeChart()
})
</script>

<style scoped>
.chart-spec-view {
  margin: 12px 0;
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

.chart-spec-table-wrap {
  border: 1px solid #e1e8f0;
  border-radius: 14px;
  background: #fff;
  overflow-x: auto;
  overscroll-behavior: contain;
}

.chart-spec-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 480px;
}

.chart-spec-table th,
.chart-spec-table td {
  padding: 10px 12px;
  border-bottom: 1px solid #edf2f7;
  text-align: left;
  font-size: 12px;
  color: #233142;
  white-space: pre-wrap;
  word-break: break-word;
  vertical-align: top;
}

.chart-spec-table th {
  background: #f8fbff;
  color: #607185;
  font-weight: 700;
  white-space: nowrap;
}

.chart-spec-empty {
  color: #8da0b3;
  font-size: 13px;
}
</style>
