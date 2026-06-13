import { vi } from 'vitest'
import { mount } from '@vue/test-utils'

const echartsMocks = vi.hoisted(() => {
  const instance = {
    setOption: vi.fn(),
    resize: vi.fn(),
    clear: vi.fn(),
    dispose: vi.fn(),
    getDataURL: vi.fn(() => 'data:image/png;base64,xxxx')
  }
  return { instance, init: vi.fn(() => instance) }
})

vi.mock('echarts/core', () => ({
  use: () => {},
  init: echartsMocks.init
}))

vi.mock('echarts/charts', () => ({ BarChart: {}, LineChart: {}, PieChart: {} }))
vi.mock('echarts/components', () => ({
  GridComponent: {},
  LegendComponent: {},
  TitleComponent: {},
  TooltipComponent: {}
}))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

const exportMocks = vi.hoisted(() => ({ downloadCsv: vi.fn() }))

vi.mock('@/utils/tableExport', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, downloadCsv: exportMocks.downloadCsv }
})

import ChartSpecView from '../ChartSpecView.vue'

const lineSpec = {
  kind: 'chart_spec',
  version: 1,
  chart_type: 'line',
  title: '趋势',
  x_field: 'day',
  series: [{ name: '次数', field: 'cnt', type: 'line' }],
  dataset: [
    { day: '2026-06-01', cnt: 3 },
    { day: '2026-06-02', cnt: 5 }
  ],
  error: null
}

const tableSpec = {
  kind: 'chart_spec',
  version: 1,
  chart_type: 'table',
  title: '明细',
  columns: ['day', 'cnt'],
  dataset: [{ day: '2026-06-01', cnt: 3 }],
  error: null
}

const mountView = (spec) => mount(ChartSpecView, {
  props: { spec },
  global: {
    stubs: {
      ResultDataTable: {
        props: ['columns', 'rows', 'title', 'meta'],
        template: '<div class="result-table-stub">{{ columns.join(",") }}</div>'
      }
    }
  }
})

describe('ChartSpecView toolbar', () => {
  beforeEach(() => {
    exportMocks.downloadCsv.mockClear()
    echartsMocks.instance.getDataURL.mockClear()
  })

  it('renders toolbar actions for an echarts spec', () => {
    const wrapper = mountView(lineSpec)
    expect(wrapper.find('[data-action="toggle-view"]').exists()).toBe(true)
    expect(wrapper.find('[data-action="toggle-type"]').exists()).toBe(true)
    expect(wrapper.find('[data-action="download-png"]').exists()).toBe(true)
    expect(wrapper.find('[data-action="export-csv"]').exists()).toBe(true)
  })

  it('toggles between chart and data table view', async () => {
    const wrapper = mountView(lineSpec)
    expect(wrapper.find('.chart-spec-canvas').exists()).toBe(true)
    expect(wrapper.find('.result-table-stub').exists()).toBe(false)

    await wrapper.find('[data-action="toggle-view"]').trigger('click')
    expect(wrapper.find('.result-table-stub').text()).toBe('day,cnt')
    expect(wrapper.find('.chart-spec-canvas').exists()).toBe(false)
    expect(wrapper.find('[data-action="toggle-view"]').text()).toBe('查看图表')
  })

  it('toggles bar/line chart type', async () => {
    const wrapper = mountView(lineSpec)
    const toggle = wrapper.find('[data-action="toggle-type"]')
    expect(toggle.text()).toBe('柱状')
    await toggle.trigger('click')
    expect(wrapper.find('[data-action="toggle-type"]').text()).toBe('折线')
  })

  it('exports the dataset as csv', async () => {
    const wrapper = mountView(lineSpec)
    await wrapper.find('[data-action="export-csv"]').trigger('click')
    expect(exportMocks.downloadCsv).toHaveBeenCalledWith('趋势', ['day', 'cnt'], lineSpec.dataset)
  })

  it('renders table specs through ResultDataTable without chart-only actions', () => {
    const wrapper = mountView(tableSpec)
    expect(wrapper.find('.result-table-stub').exists()).toBe(true)
    expect(wrapper.find('[data-action="toggle-view"]').exists()).toBe(false)
    expect(wrapper.find('[data-action="download-png"]').exists()).toBe(false)
    expect(wrapper.find('[data-action="export-csv"]').exists()).toBe(true)
  })

  it('shows validation message for invalid specs', () => {
    const wrapper = mountView({ kind: 'chart_spec', version: 1, chart_type: 'bar', title: 'x', dataset: [{ a: 1 }] })
    expect(wrapper.text()).toContain('bar 类型必须提供 x_field')
  })
})
