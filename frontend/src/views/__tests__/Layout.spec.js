import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

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
  it('renames the intelligent query menu entry to Agent问答', () => {
    const wrapper = mount(Layout, {
      global: { stubs }
    })

    const intelligentQueryItem = wrapper.find('[data-index="/intelligent-query"]')
    expect(intelligentQueryItem.exists()).toBe(true)
    expect(intelligentQueryItem.text()).toContain('Agent问答')
    expect(intelligentQueryItem.text()).not.toContain('智能问数')
  })
})
