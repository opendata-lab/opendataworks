import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'

const routerPush = vi.hoisted(() => vi.fn())
const routeState = vi.hoisted(() => ({
  path: '/chat',
  name: 'IntelligentQueryChat',
  query: {},
  params: {},
  meta: { tab: 'chat-v2' }
}))

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal()),
  useRoute: () => routeState,
  useRouter: () => ({
    push: routerPush
  })
}))

import IntelligentQueryView from '../IntelligentQueryView.vue'
import { useAuthStore } from '@/stores/auth'

const stubs = {
  'router-view': { template: '<div data-test="router-view">routed content</div>' },
  'el-menu': {
    props: ['defaultActive'],
    emits: ['select'],
    template: '<nav class="el-menu-stub" :data-active="defaultActive"><slot /></nav>'
  },
  'el-menu-item': {
    props: ['index'],
    template: '<button class="el-menu-item-stub" :data-index="index"><slot /></button>'
  },
  'el-icon': { template: '<span><slot /></span>' },
  'el-dropdown': { template: '<div><slot /><slot name="dropdown" /></div>' },
  'el-dropdown-menu': { template: '<div><slot /></div>' },
  'el-dropdown-item': { template: '<div><slot /></div>' }
}

const mountView = (route = {}, currentUser = null) => {
  routeState.path = route.path || '/chat'
  routeState.name = route.name || 'IntelligentQueryChat'
  routeState.query = route.query || {}
  routeState.params = route.params || {}
  routeState.meta = route.meta || { tab: 'chat-v2' }
  const pinia = createPinia()
  const authStore = useAuthStore(pinia)
  if (currentUser) {
    authStore.enabled = true
    authStore.currentUser = currentUser
  }
  return mount(IntelligentQueryView, {
    global: { stubs, plugins: [pinia] }
  })
}

describe('IntelligentQueryView', () => {
  beforeEach(() => {
    routerPush.mockReset()
  })

  it('renders the routed child via router-view and highlights chat by default', () => {
    const wrapper = mountView()

    expect(wrapper.find('[data-test="router-view"]').exists()).toBe(true)
    expect(wrapper.find('.el-menu-stub').attributes('data-active')).toBe('chat-v2')
    expect(wrapper.text()).toContain('Chat')
    expect(wrapper.text()).not.toContain('智能问数')
  })

  it('highlights the menu entry from the matched route meta', () => {
    const wrapper = mountView({
      path: '/skills',
      name: 'IntelligentQuerySkills',
      meta: { tab: 'skills' }
    })

    expect(wrapper.find('.el-menu-stub').attributes('data-active')).toBe('skills')
  })

  it('falls back to chat when the route meta has no tab', () => {
    const wrapper = mountView({
      path: '/chat',
      name: 'IntelligentQueryChat',
      meta: {}
    })

    expect(wrapper.find('.el-menu-stub').attributes('data-active')).toBe('chat-v2')
  })

  it('navigates to the real route when a menu entry is selected', async () => {
    const wrapper = mountView()

    await wrapper.vm.handleMenuSelect('models')
    expect(routerPush).toHaveBeenCalledWith({ path: '/models', query: {} })

    await wrapper.vm.handleMenuSelect('skills')
    expect(routerPush).toHaveBeenCalledWith({ path: '/skills', query: {} })

    await wrapper.vm.handleMenuSelect('widget')
    expect(routerPush).toHaveBeenCalledWith({ path: '/widget-access', query: {} })
  })

  it('preserves only agent_id when switching page menus', async () => {
    const wrapper = mountView({
      path: '/chat',
      query: { agent_id: 'agent_sales', topic_id: 'topic-1', message_id: 'a1' },
      meta: { tab: 'chat-v2' }
    })

    await wrapper.vm.handleMenuSelect('skills')

    expect(routerPush).toHaveBeenCalledWith({
      path: '/skills',
      query: { agent_id: 'agent_sales' }
    })
  })

  it('does not navigate when selecting the already active route', async () => {
    const wrapper = mountView({
      path: '/models',
      name: 'IntelligentQueryModels',
      meta: { tab: 'models' }
    })

    await wrapper.vm.handleMenuSelect('models')
    expect(routerPush).not.toHaveBeenCalled()
  })

  it('keeps Skills highlighted on the skill detail route', () => {
    const wrapper = mountView({
      path: '/skills/marketing-insights',
      name: 'IntelligentQuerySkillDetail',
      params: { folder: 'marketing-insights' },
      meta: { tab: 'skills' }
    })

    expect(wrapper.find('.el-menu-stub').attributes('data-active')).toBe('skills')
  })

  it('keeps 智能体 highlighted on the agent detail route', () => {
    const wrapper = mountView({
      path: '/agents/agent_1',
      name: 'IntelligentQueryAgentDetail',
      params: { agentId: 'agent_1' },
      meta: { tab: 'agents' }
    })

    expect(wrapper.find('.el-menu-stub').attributes('data-active')).toBe('agents')
  })

  it('shows readable menus without a role label for regular users', () => {
    const wrapper = mountView({}, { display_name: 'Alice', role: 'user' })

    // The work is Chat and 智能体; configuration moved behind the gear into
    // /settings, where SettingsLayout decides what a non-admin may see.
    expect(wrapper.text()).toContain('Chat')
    expect(wrapper.text()).toContain('智能体')
    expect(wrapper.text()).not.toContain('Skills')
    expect(wrapper.text()).not.toContain('模型管理')
    expect(wrapper.text()).not.toContain('Widget 接入')
    expect(wrapper.text()).not.toContain('评测集')
    expect(wrapper.text()).toContain('Alice')
    expect(wrapper.text()).not.toContain('普通用户')
    expect(wrapper.text()).not.toContain('管理员')
  })
})
