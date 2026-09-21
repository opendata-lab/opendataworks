import { vi } from 'vitest'
import { mount } from '@vue/test-utils'

// The chat message list re-renders constantly (every streamed token, the topic
// status poll, any unrelated reactive change) and hands ChartSpecView a freshly
// built spec object each time. These tests pin the behavior that made charts
// unusable: the viewer's 柱状/折线 toggle and legend selection being wiped, and
// the chart being torn down and redrawn on every one of those re-renders.

const echartsMocks = vi.hoisted(() => {
  const handlers = {}
  const instance = {
    setOption: vi.fn(),
    resize: vi.fn(),
    clear: vi.fn(),
    dispose: vi.fn(),
    getDataURL: vi.fn(() => 'data:image/png;base64,xxxx'),
    on: vi.fn((event, handler) => { handlers[event] = handler }),
    emit: (event, payload) => handlers[event]?.(payload)
  }
  return { instance, init: vi.fn(() => instance), handlers }
})

vi.mock('echarts/core', () => ({
  use: () => {},
  init: echartsMocks.init
}))
vi.mock('echarts/charts', () => ({
  BarChart: {}, LineChart: {}, PieChart: {}, ScatterChart: {}, RadarChart: {}, FunnelChart: {}, GaugeChart: {}
}))
vi.mock('echarts/components', () => ({
  GridComponent: {}, LegendComponent: {}, TitleComponent: {}, TooltipComponent: {}, RadarComponent: {}
}))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

import ChartSpecView from '../../../../packages/agent-conversation/src/ui/ChartSpecView.vue'

const multiSeriesSpec = () => ({
  kind: 'chart_spec',
  version: 1,
  chart_type: 'line',
  title: '趋势',
  x_field: 'day',
  series: [
    { name: '发布', field: 'publish_cnt', type: 'line' },
    { name: '下线', field: 'offline_cnt', type: 'line' }
  ],
  dataset: [
    { day: '2026-06-01', publish_cnt: 3, offline_cnt: 1 },
    { day: '2026-06-02', publish_cnt: 5, offline_cnt: 2 }
  ],
  error: null
})

// requestAnimationFrame drives the actual setOption call, so tests must let one
// frame run before asserting on the ECharts instance.
const flushChart = async (wrapper) => {
  await wrapper.vm.$nextTick()
  await new Promise((resolve) => requestAnimationFrame(() => resolve()))
  await wrapper.vm.$nextTick()
}

// Same flush for many instances at once: one shared frame drains every pending
// rAF callback, instead of paying ~16ms per chart.
const flushAllCharts = async () => {
  await new Promise((resolve) => setTimeout(resolve, 0))
  await new Promise((resolve) => requestAnimationFrame(() => resolve()))
  await new Promise((resolve) => setTimeout(resolve, 0))
}

const mountView = (spec) => mount(ChartSpecView, {
  props: { spec },
  global: {
    stubs: {
      ResultDataTable: { props: ['columns', 'rows', 'title', 'meta'], template: '<div class="result-table-stub" />' }
    }
  }
})

describe('ChartSpecView redraw stability', () => {
  beforeEach(() => {
    echartsMocks.instance.setOption.mockClear()
    echartsMocks.instance.clear.mockClear()
    echartsMocks.instance.on.mockClear()
    echartsMocks.init.mockClear()
  })

  it('keeps the 柱状/折线 toggle when a re-render passes an equal spec object', async () => {
    const wrapper = mountView(multiSeriesSpec())
    await flushChart(wrapper)

    await wrapper.find('[data-action="toggle-type"]').trigger('click')
    expect(wrapper.find('[data-action="toggle-type"]').text()).toBe('折线')

    // A re-render of the parent: same chart, brand-new object.
    await wrapper.setProps({ spec: multiSeriesSpec() })
    await flushChart(wrapper)

    expect(wrapper.find('[data-action="toggle-type"]').text()).toBe('折线')
  })

  it('does not redraw the chart when a re-render passes an equal spec object', async () => {
    const wrapper = mountView(multiSeriesSpec())
    await flushChart(wrapper)
    const drawCount = echartsMocks.instance.setOption.mock.calls.length
    expect(drawCount).toBeGreaterThan(0)

    for (let i = 0; i < 3; i += 1) {
      await wrapper.setProps({ spec: multiSeriesSpec() })
      await flushChart(wrapper)
    }

    expect(echartsMocks.instance.setOption.mock.calls.length).toBe(drawCount)
  })

  it('never blanks the canvas with clear() before applying an option', async () => {
    const wrapper = mountView(multiSeriesSpec())
    await flushChart(wrapper)
    await wrapper.find('[data-action="toggle-type"]').trigger('click')
    await flushChart(wrapper)

    expect(echartsMocks.instance.setOption).toHaveBeenCalled()
    expect(echartsMocks.instance.clear).not.toHaveBeenCalled()
  })

  it('restores the legend selection when the chart is redrawn', async () => {
    const wrapper = mountView(multiSeriesSpec())
    await flushChart(wrapper)

    // The viewer isolates one line via the legend.
    echartsMocks.instance.emit('legendselectchanged', { selected: { 发布: true, 下线: false } })

    await wrapper.find('[data-action="toggle-type"]').trigger('click')
    await flushChart(wrapper)

    const lastOption = echartsMocks.instance.setOption.mock.calls.at(-1)[0]
    expect(lastOption.legend.selected).toEqual({ 发布: true, 下线: false })
  })

  // Stability must not depend on any fixed-capacity cache: a topic loads up to
  // 500 messages, so a bounded LRU would evict the charts at the top of the pass
  // before it reaches the bottom and rebuild every one of them on each render.
  it('stays stable for far more charts than any fixed cache would hold', async () => {
    const CHART_COUNT = 300
    const makeSpec = (index) => ({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'line',
      title: `趋势 ${index}`,
      x_field: 'day',
      series: [{ name: '次数', field: 'cnt', type: 'line' }],
      dataset: [{ day: '2026-06-01', cnt: index }],
      error: null
    })

    const wrappers = []
    for (let i = 0; i < CHART_COUNT; i += 1) wrappers.push(mountView(makeSpec(i)))
    await flushAllCharts()

    const drawCount = echartsMocks.instance.setOption.mock.calls.length
    expect(drawCount).toBe(CHART_COUNT)

    // Two more full passes, each handing every chart a fresh but equal object.
    for (let pass = 0; pass < 2; pass += 1) {
      for (let i = 0; i < CHART_COUNT; i += 1) await wrappers[i].setProps({ spec: makeSpec(i) })
      await flushAllCharts()
    }

    expect(echartsMocks.instance.setOption.mock.calls.length).toBe(drawCount)
    for (const wrapper of wrappers) wrapper.unmount()
  }, 30000)

  it('resets the toggle and legend selection when a different chart arrives', async () => {
    const wrapper = mountView(multiSeriesSpec())
    await flushChart(wrapper)
    await wrapper.find('[data-action="toggle-type"]').trigger('click')
    echartsMocks.instance.emit('legendselectchanged', { selected: { 发布: true, 下线: false } })
    expect(wrapper.find('[data-action="toggle-type"]').text()).toBe('折线')

    const otherSpec = { ...multiSeriesSpec(), title: '另一个趋势', dataset: [{ day: '2026-07-01', publish_cnt: 9, offline_cnt: 4 }] }
    await wrapper.setProps({ spec: otherSpec })
    await flushChart(wrapper)

    expect(wrapper.find('[data-action="toggle-type"]').text()).toBe('柱状')
    const lastOption = echartsMocks.instance.setOption.mock.calls.at(-1)[0]
    expect(lastOption.legend.selected).toBeUndefined()
  })
})
