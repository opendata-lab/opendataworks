import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routeState = vi.hoisted(() => ({
  path: '/intelligent-query'
}))
const preloadRouteComponents = vi.hoisted(() => vi.fn())
const scheduleRouteWarmup = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => ({})
}))

vi.mock('@/demo/runtime', () => ({
  isDemoMode: false
}))

vi.mock('@/router/routeWarmup', () => ({
  preloadRouteComponents,
  scheduleRouteWarmup
}))

import Layout from '../Layout.vue'

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
    document.head.innerHTML = ''
    document.body.innerHTML = ''
    delete window.OpenDataWorksWidget
    delete window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
    scheduleRouteWarmup.mockClear()
    preloadRouteComponents.mockClear()
  })

  it('renames the intelligent query menu entry to Agent问答', () => {
    const wrapper = mount(Layout, {
      global: { plugins: [createPinia()], stubs }
    })

    const intelligentQueryItem = wrapper.find('[data-index="/intelligent-query"]')
    expect(intelligentQueryItem.exists()).toBe(true)
    expect(intelligentQueryItem.text()).toContain('Agent问答')
    expect(intelligentQueryItem.text()).not.toContain('智能问数')
  })

  it('loads the remote DataAgent widget script and installs the floating widget', async () => {
    const installWidget = vi.fn(() => ({ destroy: vi.fn() }))
    const wrapper = mount(Layout, {
      global: { plugins: [createPinia()], stubs }
    })

    const script = document.querySelector('script[data-odw-dataagent-widget-script]')
    expect(script).toBeTruthy()
    // The bundle URL is versioned with the portal build id to bust caches of the
    // fixed-name bundle file.
    expect(script.src).toMatch(/^http:\/\/localhost:3000\/dataagent\/widget\/opendataworks-widget\.bundle\.js\?v=[0-9a-z]+$/)

    window.OpenDataWorksWidget = { installWidget }
    script.dispatchEvent(new Event('load'))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(installWidget).toHaveBeenCalledWith(expect.objectContaining({
      displayMode: 'floating',
      position: 'bottom-right',
      agentId: 'agent_opendataworks',
      apiBaseUrl: ''
    }))

    wrapper.unmount()
    expect(installWidget.mock.results[0].value.destroy).toHaveBeenCalled()
  })
})
