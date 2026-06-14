import { vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'

const echartsMocks = vi.hoisted(() => {
  const setOption = vi.fn()
  const resize = vi.fn()
  const clear = vi.fn()
  const dispose = vi.fn()
  const init = vi.fn(() => ({
    setOption,
    resize,
    clear,
    dispose
  }))
  return { setOption, resize, clear, dispose, init }
})

vi.mock('echarts/core', () => ({
  use: () => {},
  init: echartsMocks.init
}))

vi.mock('echarts/charts', () => ({
  BarChart: {},
  LineChart: {},
  PieChart: {}
}))

vi.mock('echarts/components', () => ({
  GridComponent: {},
  LegendComponent: {},
  TitleComponent: {},
  TooltipComponent: {}
}))

vi.mock('echarts/renderers', () => ({
  CanvasRenderer: {}
}))

import ToolOutputRenderer from '../ToolOutputRenderer.vue'
import ChartSpecView from '../ChartSpecView.vue'

const mountRenderer = (tool, props = {}) => shallowMount(ToolOutputRenderer, {
  props: { tool, ...props },
  global: {
    stubs: {
      ElScrollbar: {
        template: '<div class="el-scrollbar-stub"><slot /></div>'
      }
    }
  }
})

describe('ToolOutputRenderer', () => {
  it('delegates table chart_spec payloads to ChartSpecView', () => {
    const wrapper = mountRenderer({
      name: 'build_chart_spec.py',
      status: 'streaming',
      output: {
        kind: 'chart_spec',
        version: 1,
        chart_type: 'table',
        title: '最近工作流发布记录',
        description: '以表格展示最近工作流发布记录',
        columns: ['workflow_id', 'status'],
        dataset: [{ workflow_id: 173, status: 'success' }],
        error: null
      }
    })

    const chartView = wrapper.findComponent(ChartSpecView)
    expect(chartView.exists()).toBe(true)
    expect(chartView.props('spec').chart_type).toBe('table')
    expect(chartView.props('spec').dataset).toEqual([{ workflow_id: 173, status: 'success' }])
  })

  it('renders chart_spec payloads through the chart renderer', () => {
    const wrapper = mountRenderer({
      name: 'build_chart_spec.py',
      status: 'streaming',
      output: {
        kind: 'chart_spec',
        version: 1,
        chart_type: 'line',
        title: '最近30天工作流发布趋势',
        x_field: 'stat_day',
        series: [{ name: '发布次数', field: 'publish_cnt', type: 'line' }],
        dataset: [
          { stat_day: '2026-03-01', publish_cnt: 3 },
          { stat_day: '2026-03-02', publish_cnt: 5 }
        ],
        error: null
      }
    })

    const chartView = wrapper.findComponent(ChartSpecView)
    expect(chartView.exists()).toBe(true)
    expect(chartView.classes()).toContain('tool-chart-below')
    expect(chartView.props('spec').chart_type).toBe('line')
  })

  it('passes invalid chart_spec to ChartSpecView without dumping raw JSON', () => {
    const wrapper = mountRenderer({
      name: 'build_chart_spec.py',
      status: 'streaming',
      output: {
        kind: 'chart_spec',
        version: 1,
        chart_type: 'bar',
        title: '各数据层表数量对比',
        dataset: [{ layer: 'DWD', table_cnt: 18 }],
        error: null
      }
    })

    expect(wrapper.findComponent(ChartSpecView).exists()).toBe(true)
    // Raw chart_spec JSON must never be dumped into the UI.
    expect(wrapper.text()).not.toContain('"chart_type": "bar"')
  })

  it('renders bash tools as collapsible shell traces', async () => {
    const wrapper = mountRenderer({
      name: 'Bash',
      status: 'streaming',
      _callComplete: true,
      _runtimeStarted: true,
      input: {
        command: 'python scripts/build_chart_spec.py --chart-type pie',
        description: '生成占比图表'
      },
      output: 'processing...'
    })

    expect(wrapper.text()).toContain('生成占比图表')
    expect(wrapper.text()).toContain('$ python scripts/build_chart_spec.py --chart-type pie')
    expect(wrapper.text()).toContain('processing...')
    expect(wrapper.text()).toContain('正在运行命令')
    expect(wrapper.find('.shell-trace-panel').exists()).toBe(true)

    await wrapper.setProps({
      tool: {
        name: 'Bash',
        status: 'success',
        _callComplete: true,
        _runtimeStarted: true,
        input: {
          command: 'python scripts/build_chart_spec.py --chart-type pie',
          description: '生成占比图表'
        },
        output: 'done'
      }
    })

    expect(wrapper.text()).toContain('已运行命令')
    expect(wrapper.find('.shell-trace-panel').exists()).toBe(false)
  })

  it('uses the Claude tool description as the collapsed shell trace summary', async () => {
    const wrapper = mountRenderer({
      name: 'Bash',
      status: 'success',
      _callComplete: true,
      _runtimeStarted: true,
      input: {
        command: 'python3 .claude/skills/opendataworks-business-knowledge/scripts/lookup_ontology.py --query 数据层',
        description: 'Lookup ontology for "数据层" data layer'
      },
      output: 'done'
    })

    expect(wrapper.find('.shell-trace-summary-text').text()).toBe('执行命令：Lookup ontology for "数据层" data layer')
    expect(wrapper.find('.shell-trace-summary-text').text()).not.toContain('lookup_ontology.py')

    await wrapper.find('.shell-trace-summary').trigger('click')

    expect(wrapper.text()).toContain('$ python3 .claude/skills/opendataworks-business-knowledge/scripts/lookup_ontology.py --query 数据层')
  })

  it('renders read tools with an expandable output panel once content is available', async () => {
    const wrapper = mountRenderer({
      name: 'Read',
      status: 'streaming',
      _callComplete: true,
      _runtimeStarted: true,
      input: {
        file_path: '/tmp/reference/00-skill-map.md'
      },
      output: '## skill map'
    })

    expect(wrapper.text()).toContain('正在读取')
    expect(wrapper.text()).toContain('/tmp/reference/00-skill-map.md')
    expect(wrapper.find('.shell-trace-panel').exists()).toBe(true)
    expect(wrapper.text()).toContain('skill map')

    await wrapper.setProps({
      tool: {
        name: 'Read',
        status: 'success',
        _callComplete: true,
        _runtimeStarted: true,
        input: {
          file_path: '/tmp/reference/00-skill-map.md'
        },
        output: '## skill map'
      }
    })

    expect(wrapper.text()).toContain('已读取')
    expect(wrapper.find('.shell-trace-panel').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('skill map')
  })

  it('keeps read traces compact across invocation and runtime states', async () => {
    const wrapper = mountRenderer({
      name: 'Read',
      status: 'streaming',
      _callComplete: false,
      _runtimeStarted: false,
      input: {
        file_path: '/tmp/reference/30-tool-recipes.md'
      },
      output: ''
    })

    expect(wrapper.text()).toContain('正在发起读取')
    expect(wrapper.find('.shell-trace-panel').exists()).toBe(false)

    await wrapper.setProps({
      tool: {
        name: 'Read',
        status: 'streaming',
        _callComplete: true,
        _runtimeStarted: false,
        input: {
          file_path: '/tmp/reference/30-tool-recipes.md'
        },
        output: ''
      }
    })

    expect(wrapper.text()).toContain('已发起读取')
    expect(wrapper.find('.shell-trace-panel').exists()).toBe(false)

    await wrapper.setProps({
      tool: {
        name: 'Read',
        status: 'streaming',
        _callComplete: true,
        _runtimeStarted: true,
        input: {
          file_path: '/tmp/reference/30-tool-recipes.md'
        },
        output: '正在读取...'
      }
    })

    expect(wrapper.text()).toContain('正在读取')
    expect(wrapper.find('.shell-trace-panel').exists()).toBe(true)
  })

  it('classifies ls and glob style tools without falling back to generic tool labels', () => {
    const lsWrapper = mountRenderer({
      name: 'LS',
      status: 'success',
      _callComplete: true,
      _runtimeStarted: true,
      input: {
        directory: '/tmp/reference'
      },
      output: '00-skill-map.md\n10-query-playbooks.md'
    })

    expect(lsWrapper.text()).toContain('查看目录：/tmp/reference')
    expect(lsWrapper.text()).toContain('已查看目录')

    const globWrapper = mountRenderer({
      name: 'Glob',
      status: 'success',
      _callComplete: true,
      _runtimeStarted: true,
      input: {
        pattern: '*.md',
        directory: '/tmp/reference'
      },
      output: '00-skill-map.md'
    })

    expect(globWrapper.text()).toContain('搜索文件：*.md · /tmp/reference')
    expect(globWrapper.text()).toContain('已搜索')
    expect(globWrapper.text()).not.toContain('工具调用')
  })

  it('infers shell traces from command input even when the tool name is generic', () => {
    const wrapper = mountRenderer({
      name: 'Tool',
      status: 'streaming',
      _callComplete: true,
      _runtimeStarted: true,
      input: {
        command: 'python scripts/run_sql.py --question trend'
      },
      output: 'running...'
    })

    expect(wrapper.text()).toContain('执行命令：python scripts/run_sql.py --question trend')
    expect(wrapper.text()).toContain('正在运行命令')
    expect(wrapper.text()).not.toContain('工具调用')
  })

  it('renders MCP tools with the same flat trace style as command/read traces', () => {
    const wrapper = mountRenderer({
      name: 'mcp__github__get_me',
      status: 'success',
      _callComplete: true,
      _runtimeStarted: true,
      output: '{"login":"octocat"}'
    })

    expect(wrapper.find('.shell-trace-summary').exists()).toBe(true)
    expect(wrapper.find('.tool-output-head').exists()).toBe(false)
    expect(wrapper.text()).toContain('执行工具：github / get_me')
    expect(wrapper.text()).not.toContain('mcp__github__get_me')
  })

  it('does not misclassify MCP tool names containing search/read substrings', () => {
    const wrapper = mountRenderer({
      name: 'mcp__github__search_code',
      status: 'success',
      _callComplete: true,
      _runtimeStarted: true,
      output: 'ok'
    })

    expect(wrapper.find('.shell-trace-summary').exists()).toBe(true)
    expect(wrapper.text()).toContain('执行工具：github / search_code')
    expect(wrapper.text()).not.toContain('搜索文件')
  })

  it('uses the bootstrap skill label on the concrete follow-up tool', () => {
    const wrapper = mountRenderer({
      name: 'Bash',
      status: 'streaming',
      _callComplete: true,
      _runtimeStarted: true,
      _skillBootstrapName: 'dataagent-nl2sql',
      input: {
        command: 'python scripts/run_sql.py --question trend'
      },
      output: 'running...'
    })

    expect(wrapper.text()).toContain('加载技能（dataagent-nl2sql）')
    expect(wrapper.text()).toContain('正在运行命令')
    expect(wrapper.text()).toContain('$ python scripts/run_sql.py --question trend')
  })

  it('renders markdown skill output as a collapsed preview and expands on demand', async () => {
    const wrapper = mountRenderer({
      id: 'tool-skill-preview',
      name: 'Skill',
      status: 'success',
      _callComplete: true,
      _runtimeStarted: true,
      input: {
        description: '加载技能说明'
      },
      output: [
        '1→# 场景 Playbooks',
        '2→',
        '3→先结论：优先覆盖统计、对比、趋势、占比、明细、诊断六类问题。',
        '4→',
        '5→## 托管业务表',
        '6→需要先查 metadata，再解析 datasource，最后执行 SQL。'
      ].join('\n')
    })

    await wrapper.find('.shell-trace-summary').trigger('click')

    expect(wrapper.find('.tool-output-body-scroll').exists()).toBe(true)
    expect(wrapper.find('.tool-markdown').exists()).toBe(true)
    expect(wrapper.text()).toContain('场景 Playbooks')
    expect(wrapper.text()).toContain('托管业务表')
    expect(wrapper.text()).not.toContain('1→# 场景 Playbooks')
    expect(wrapper.text()).not.toContain('5→## 托管业务表')
    expect(wrapper.text()).not.toContain('需要先查 metadata，再解析 datasource，最后执行 SQL。')
    expect(wrapper.find('.tool-markdown-toggle').text()).toContain('展开')
    expect(wrapper.findAll('.tool-code').length).toBe(0)

    await wrapper.find('.tool-markdown-toggle').trigger('click')

    expect(wrapper.text()).toContain('需要先查 metadata，再解析 datasource，最后执行 SQL。')
    expect(wrapper.text()).not.toContain('6→需要先查 metadata，再解析 datasource，最后执行 SQL。')
    expect(wrapper.find('.tool-markdown-toggle').text()).toContain('收起')
  })

  it('collapses generic tool output after completion and auto-expands while running', async () => {
    const wrapper = mountRenderer({
      name: 'SQLQuery',
      status: 'streaming',
      output: {
        kind: 'sql_execution',
        sql: 'select * from workflow_publish_record limit 2',
        columns: ['workflow_id'],
        rows: [{ workflow_id: 1 }]
      }
    })

    expect(wrapper.find('.tool-output-panel').exists()).toBe(true)
    expect(wrapper.find('.tool-output-body-scroll').exists()).toBe(true)

    await wrapper.setProps({
      tool: {
        name: 'SQLQuery',
        status: 'success',
        output: {
          kind: 'sql_execution',
          sql: 'select * from workflow_publish_record limit 2',
          columns: ['workflow_id'],
          rows: [{ workflow_id: 1 }]
        }
      }
    })

    expect(wrapper.find('.tool-output-panel').exists()).toBe(false)
    expect(wrapper.find('.tool-output-head.is-interactive').exists()).toBe(true)
  })

  it('renders generic tools as flat traces and strips numbered arrow prefixes', async () => {
    const wrapper = mountRenderer({
      id: 'tool-raw-preview',
      name: 'CustomTool',
      status: 'success',
      _callComplete: true,
      _runtimeStarted: true,
      output: '1→alpha\n2→beta'
    })

    // Generic tools share the flat trace presentation, not the bordered card.
    expect(wrapper.find('.shell-trace-summary').exists()).toBe(true)
    expect(wrapper.find('.tool-output-head').exists()).toBe(false)
    expect(wrapper.text()).toContain('执行工具：CustomTool')
    expect(wrapper.find('.shell-trace-panel').exists()).toBe(false)

    await wrapper.find('.shell-trace-summary').trigger('click')

    expect(wrapper.find('.tool-output-body-scroll').exists()).toBe(true)
    expect(wrapper.text()).toContain('alpha')
    expect(wrapper.text()).toContain('beta')
    expect(wrapper.text()).not.toContain('1→alpha')
    expect(wrapper.text()).not.toContain('2→beta')
  })

  it('shows a leading tool-type icon on the collapsed header without expanding', () => {
    const wrapper = mountRenderer({
      name: 'SQLQuery',
      status: 'success',
      output: {
        kind: 'sql_execution',
        sql: 'select 1',
        columns: ['workflow_id'],
        rows: [{ workflow_id: 1 }]
      }
    })

    const headerIcon = wrapper.find('.tool-output-head .tool-output-icon')
    expect(headerIcon.exists()).toBe(true)
    expect(headerIcon.findAll('path').length).toBeGreaterThan(0)
    // The collapsible panel stays closed, so the icon must be visible up front.
    expect(wrapper.find('.tool-output-panel').exists()).toBe(false)
  })

  it('labels a chart-producing shell step as 生成图表 instead of a generic command', async () => {
    const wrapper = mountRenderer({
      name: 'Bash',
      status: 'success',
      _callComplete: true,
      _runtimeStarted: true,
      input: {
        command: 'python scripts/build_chart_spec.py --chart-type line'
      },
      output: {
        kind: 'chart_spec',
        version: 1,
        chart_type: 'line',
        title: '发布趋势',
        x_field: 'stat_day',
        series: [{ name: '发布次数', field: 'publish_cnt', type: 'line' }],
        dataset: [{ stat_day: '2026-03-10', publish_cnt: 3 }],
        error: null
      }
    })

    expect(wrapper.text()).toContain('生成图表：发布趋势')
    expect(wrapper.text()).toContain('已生成图表')
    expect(wrapper.text()).not.toContain('执行命令：')
  })

  it('shows a leading tool-type icon on the shell-trace summary line', () => {
    const wrapper = mountRenderer({
      name: 'Bash',
      status: 'success',
      _callComplete: true,
      _runtimeStarted: true,
      input: {
        command: 'python scripts/run_sql.py --question trend'
      },
      output: 'done'
    })

    const traceIcon = wrapper.find('.shell-trace-summary .shell-trace-icon')
    expect(traceIcon.exists()).toBe(true)
    expect(traceIcon.findAll('path').length).toBeGreaterThan(0)
  })

  it('delegates sql_execution rendering to SqlCodePanel and ResultDataTable', () => {
    const wrapper = mountRenderer({
      name: 'SQLQuery',
      status: 'streaming',
      output: {
        kind: 'sql_execution',
        sql: 'select workflow_id from t limit 2',
        database: 'demo',
        engine: 'mysql',
        columns: ['workflow_id'],
        rows: [{ workflow_id: 1 }],
        row_count: 1,
        has_more: true,
        duration_ms: 12
      }
    })

    const sqlPanel = wrapper.findComponent({ name: 'SqlCodePanel' })
    expect(sqlPanel.exists()).toBe(true)
    expect(sqlPanel.props('sql')).toBe('select workflow_id from t limit 2')
    expect(sqlPanel.props('database')).toBe('demo')

    const table = wrapper.findComponent({ name: 'ResultDataTable' })
    expect(table.exists()).toBe(true)
    expect(table.props('columns')).toEqual(['workflow_id'])
    expect(table.props('meta')).toMatchObject({ rowCount: 1, hasMore: true, durationMs: 12 })
  })

  it('renders sql_export download link via fileUrlResolver and preview table', () => {
    const resolver = (relPath) => `/api/v1/nl2sql/topics/topic-1/files/${relPath}?download=1`
    const wrapper = mountRenderer(
      {
        name: 'SQLExport',
        status: 'streaming',
        output: {
          kind: 'sql_export',
          sql: 'select * from t',
          database: 'demo',
          file_path: '/workspace/topic-1/output/result.csv',
          columns: ['a'],
          preview_rows: [{ a: 1 }],
          row_count: 621
        }
      },
      { fileUrlResolver: resolver }
    )

    const link = wrapper.find('.tool-export-download')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('/api/v1/nl2sql/topics/topic-1/files/output/result.csv?download=1')
    expect(link.text()).toContain('result.csv')
    expect(wrapper.findComponent({ name: 'ResultDataTable' }).exists()).toBe(true)
  })
})
