import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const getAgentSettings = vi.hoisted(() => vi.fn())

vi.mock('vue-router', async (importOriginal) => ({
  ...await importOriginal(),
  useRoute: () => ({ path: '/datastudio', fullPath: '/datastudio', query: {} }),
  useRouter: () => ({ push: vi.fn().mockResolvedValue(undefined) })
}))
vi.mock('@/api/settings', () => ({ settingsApi: { getAgentSettings } }))
vi.mock('@/demo/runtime', () => ({ isDemoMode: true }))
vi.mock('@/router/routeWarmup', () => ({
  preloadRouteComponents: vi.fn(),
  scheduleRouteWarmup: vi.fn()
}))

import Layout from '../Layout.vue'

const stubs = {
  'el-container': { template: '<div><slot /></div>' },
  'el-header': { template: '<header><slot /></header>' },
  'el-main': { template: '<main><slot /></main>' },
  'el-menu': { template: '<nav><slot /></nav>' },
  'el-menu-item': { template: '<button><slot /></button>' },
  'el-sub-menu': { template: '<div><slot name="title" /><slot /></div>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-icon': { template: '<span><slot /></span>' },
  'el-tag': { template: '<span><slot /></span>' },
  'router-link': { template: '<a><slot /></a>' },
  RouterView: { template: '<div />' }
}

describe('Layout demo mode', () => {
  beforeEach(() => {
    document.head.innerHTML = ''
    document.body.innerHTML = ''
    getAgentSettings.mockReset()
  })

  it('hides the AI assistant trigger and skips loading the remote widget in demo mode', async () => {
    const wrapper = mount(Layout, {
      global: { plugins: [createPinia()], stubs }
    })
    await flushPromises()

    expect(wrapper.find('.portal-page-header h1').text()).toBe('数据开发')
    expect(wrapper.find('.ai-assistant-trigger').exists()).toBe(false)
    expect(document.querySelector('script[data-odw-dataagent-widget-script]')).toBeNull()
    expect(getAgentSettings).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
