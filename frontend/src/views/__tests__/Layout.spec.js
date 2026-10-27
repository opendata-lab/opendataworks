import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routeState = vi.hoisted(() => ({
  path: '/intelligent-query'
}))
const preloadRouteComponents = vi.hoisted(() => vi.fn())
const scheduleRouteWarmup = vi.hoisted(() => vi.fn())
const routerPush = vi.hoisted(() => vi.fn())

vi.mock('vue-router', async (importOriginal) => ({
  ...await importOriginal(),
  useRoute: () => routeState,
  useRouter: () => ({ push: routerPush })
}))

vi.mock('@/demo/runtime', () => ({
  isDemoMode: false
}))

vi.mock('@/router/routeWarmup', () => ({
  preloadRouteComponents,
  scheduleRouteWarmup
}))

import Layout from '../Layout.vue'
import AiAssistantTrigger from '@/components/AiAssistantTrigger.vue'

const stubs = {
  'el-container': { template: '<div><slot /></div>' },
  'el-header': { template: '<header><slot /></header>' },
  'el-main': { template: '<main><slot /></main>' },
  'el-menu': {
    props: ['defaultActive'],
    template: '<nav class="el-menu-stub" :data-active="defaultActive"><slot /></nav>'
  },
  'el-menu-item': {
    props: ['index'],
    template: '<button class="el-menu-item-stub" :data-index="index"><slot /></button>'
  },
  'el-icon': { template: '<span><slot /></span>' },
  'el-tag': { template: '<span><slot /></span>' },
  RouterView: { template: '<div />' }
}

describe('Layout', () => {
  beforeEach(() => {
    routeState.path = '/intelligent-query'
    routeState.query = {}
    document.head.innerHTML = ''
    document.body.innerHTML = ''
    delete window.DataAgentWidget
    delete window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
    scheduleRouteWarmup.mockClear()
    preloadRouteComponents.mockClear()
    routerPush.mockReset().mockResolvedValue(undefined)
  })

  it('renames the intelligent query menu entry to 智能问数', () => {
    const wrapper = mount(Layout, {
      global: { plugins: [createPinia()], stubs }
    })

    const intelligentQueryItem = wrapper.find('[data-index="/intelligent-query"]')
    expect(intelligentQueryItem.exists()).toBe(true)
    expect(intelligentQueryItem.text()).toContain('智能问数')
    expect(intelligentQueryItem.text()).not.toContain('Agent问答')
    expect(wrapper.find('[data-index="/datastudio"]').text()).toContain('数据开发')
    expect(wrapper.find('[data-index="/workflows"]').text()).toContain('工作流')
  })

  it('loads the remote DataAgent widget script and installs the floating widget', async () => {
    const close = vi.fn()
    const handlers = {}
    const installWidget = vi.fn(() => ({ destroy: vi.fn(), close, on: (name, handler) => { handlers[name] = handler }, getPanelLayout: () => ({ side: null, reservedWidth: 0 }) }))
    const wrapper = mount(Layout, {
      global: { plugins: [createPinia()], stubs }
    })

    const script = document.querySelector('script[data-odw-dataagent-widget-script]')
    expect(script).toBeTruthy()
    expect(script.src).toBe('http://localhost:3000/dataagent/widget/dataagent-widget.bundle.js')

    window.DataAgentWidget = { installWidget }
    script.dispatchEvent(new Event('load'))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(installWidget).toHaveBeenCalledWith(expect.objectContaining({
      displayMode: 'floating',
      position: 'bottom-right',
      agentId: 'agent_opendataworks',
      apiBaseUrl: ''
    }))

    handlers['panel-layout']({ side: 'left', reservedWidth: 440 })
    await wrapper.vm.$nextTick()
    expect(wrapper.attributes('style')).toContain('margin-left: 440px')
    expect(wrapper.attributes('style')).toContain('width: calc(100% - 440px)')
    handlers['panel-layout']({ side: 'right', reservedWidth: 440 })
    await wrapper.vm.$nextTick()
    expect(wrapper.attributes('style')).toContain('margin-right: 440px')
    handlers['panel-layout']({ side: 'right', reservedWidth: 0 })
    await wrapper.vm.$nextTick()
    expect(wrapper.attributes('style') || '').not.toContain('440px')

    const { onFullscreen } = installWidget.mock.calls[0][0]
    await onFullscreen({ topicId: 'topic-current' })
    expect(routerPush).toHaveBeenLastCalledWith({ path: '/intelligent-query', query: { topicId: 'topic-current' } })
    expect(close).toHaveBeenCalledTimes(1)
    await onFullscreen({ topicId: null })
    expect(routerPush).toHaveBeenLastCalledWith({ path: '/intelligent-query', query: {} })
    expect(close).toHaveBeenCalledTimes(2)
    routerPush.mockResolvedValueOnce({ type: 4 })
    await onFullscreen({ topicId: 'blocked-topic' })
    expect(close).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    expect(installWidget.mock.results[0].value.destroy).toHaveBeenCalled()
  })

  it('shares the initial installation with a header click and opens its own instance', async () => {
    const open = vi.fn()
    const installWidget = vi.fn(() => ({ destroy: vi.fn(), on: vi.fn(), open, getPanelLayout: () => ({ side: null, reservedWidth: 0 }) }))
    const globalOpen = vi.fn()
    const wrapper = mount(Layout, {
      global: {
        plugins: [createPinia()],
        stubs: { ...stubs, RouterView: { components: { AiAssistantTrigger }, template: '<AiAssistantTrigger :prompts="[\'当前页提示\']" />' } }
      }
    })
    await wrapper.find('.portal-page-content .ai-assistant-trigger').trigger('click')
    window.DataAgentWidget = { installWidget, open: globalOpen }
    document.querySelector('script[data-odw-dataagent-widget-script]').dispatchEvent(new Event('load'))
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(installWidget).toHaveBeenCalledTimes(1)
    expect(open).toHaveBeenCalledWith({ draft: '当前页提示' })
    expect(globalOpen).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.each([
    ['/datastudio', {}, '数据开发', '帮我编写数据查询 SQL'],
    ['/workflows', { tab: 'tasks' }, '工作流', '帮我查询任务执行情况'],
    ['/workflows', { tab: 'monitor' }, '工作流', '帮我分析工作流执行失败原因'],
    ['/lineage', {}, '数据血缘', '帮我分析表的上下游依赖'],
    ['/dashboard', {}, '控制台', '帮我汇总平台数据资产概况'],
    ['/inspection', {}, '数据质量', '帮我分析数据质量问题'],
    ['/integration', {}, '数据集成', '帮我梳理数据同步任务配置'],
    ['/settings', {}, '设置', '帮我了解平台配置项']
  ])('shows the shared page heading and scoped assistant for %s', (path, query, title, prompt) => {
    routeState.path = path
    routeState.query = query
    const wrapper = mount(Layout, { global: { plugins: [createPinia()], stubs } })
    expect(wrapper.find('.portal-page-header h1').text()).toBe(title)
    const assistant = wrapper.find('.portal-page-header .ai-assistant-trigger')
    expect(assistant.exists()).toBe(Boolean(prompt))
    if (prompt) expect(assistant.attributes('aria-label')).toContain(prompt)
    wrapper.unmount()
  })
})
