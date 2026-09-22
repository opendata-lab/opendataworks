let runtimePromise = null

/**
 * Load and register the ECharts pieces used by ChartSpecView once per module.
 *
 * This deliberately lives in a regular module rather than <script setup>:
 * declarations in <script setup> are created once per component instance, so
 * a long conversation could otherwise start hundreds of identical import()
 * chains at the same time.
 */
export const loadECharts = () => {
  if (!runtimePromise) {
    runtimePromise = Promise.all([
      import('echarts/core'),
      import('echarts/charts'),
      import('echarts/components'),
      import('echarts/renderers')
    ]).then(([echarts, charts, components, renderers]) => {
      echarts.use([
        renderers.CanvasRenderer,
        charts.LineChart,
        charts.BarChart,
        charts.PieChart,
        charts.ScatterChart,
        charts.RadarChart,
        charts.FunnelChart,
        charts.GaugeChart,
        components.GridComponent,
        components.TooltipComponent,
        components.LegendComponent,
        components.TitleComponent,
        components.RadarComponent
      ])
      return echarts
    }).catch((error) => {
      runtimePromise = null
      throw error
    })
  }
  return runtimePromise
}
