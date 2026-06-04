import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routerReplace = vi.hoisted(() => vi.fn())
const routeState = vi.hoisted(() => ({
  path: '/intelligent-query',
  name: 'IntelligentQuery',
  query: {},
  params: {}
}))

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal()),
  useRoute: () => routeState,
  useRouter: () => ({
    replace: routerReplace
  })
}))

import IntelligentQueryView from '../IntelligentQueryView.vue'

const stubs = {
  NL2SqlChat: { template: '<div data-test="nl2sql-chat">Agent问答聊天</div>' },
  NL2SqlChatV2: { template: '<div data-test="nl2sql-chat-v2">Chat V2</div>' },
  AgentStudio: { template: '<div data-test="agent-studio">智能体内容</div>' },
  AgentDetailView: { template: '<div data-test="agent-detail">智能体详情</div>' },
  SkillStudio: { template: '<div data-test="skill-studio">Skills 内容</div>' },
  DataAgentConfig: { template: '<div data-test="dataagent-config">模型管理内容</div>' },
  WidgetAccessConfig: { template: '<div data-test="widget-access">Widget 接入内容</div>' },
  SkillDetailView: { template: '<div data-test="skill-detail">Skill 详情</div>' },
  'el-menu': {
    props: ['defaultActive'],
    emits: ['select'],
    template: '<nav class="el-menu-stub" :data-active="defaultActive"><slot /></nav>'
  },
  'el-menu-item': {
    props: ['index'],
    template: '<button class="el-menu-item-stub" :data-index="index"><slot /></button>'
  },
  'el-icon': { template: '<span><slot /></span>' }
}

const mountView = (route = {}) => {
  routeState.path = route.path || '/intelligent-query'
  routeState.name = route.name || 'IntelligentQuery'
  routeState.query = route.query || {}
  routeState.params = route.params || {}
  return mount(IntelligentQueryView, {
    global: { stubs }
  })
}

describe('IntelligentQueryView', () => {
  beforeEach(() => {
    routerReplace.mockReset()
  })

  it('renders chat v2 by default', () => {
    const wrapper = mountView()

    expect(wrapper.find('[data-test="nl2sql-chat-v2"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="nl2sql-chat"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skill-studio"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="agent-studio"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="dataagent-config"]').exists()).toBe(false)
    expect(wrapper.find('.el-menu-stub').attributes('data-active')).toBe('chat-v2')
    expect(wrapper.text()).toContain('Chat')
    expect(wrapper.text()).not.toContain('智能问数')
  })

  it('renders Skills from the tab query and updates the query from menu selection', async () => {
    const wrapper = mountView({ query: { tab: 'skills' } })

    expect(wrapper.find('[data-test="skill-studio"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="nl2sql-chat-v2"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="nl2sql-chat"]').exists()).toBe(false)
    expect(wrapper.find('.el-menu-stub').attributes('data-active')).toBe('skills')

    await wrapper.vm.handleMenuSelect('models')
    expect(routerReplace).toHaveBeenCalledWith({
      path: '/intelligent-query',
      query: { tab: 'models' }
    })
  })

  it('renders model management from the tab query', () => {
    const wrapper = mountView({ query: { tab: 'models' } })

    expect(wrapper.find('[data-test="dataagent-config"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="nl2sql-chat-v2"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="nl2sql-chat"]').exists()).toBe(false)
    expect(wrapper.find('.el-menu-stub').attributes('data-active')).toBe('models')
  })

  it('renders widget access config from the tab query', () => {
    const wrapper = mountView({ query: { tab: 'widget' } })

    expect(wrapper.find('[data-test="widget-access"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="nl2sql-chat-v2"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="nl2sql-chat"]').exists()).toBe(false)
    expect(wrapper.find('.el-menu-stub').attributes('data-active')).toBe('widget')
  })

  it('renders agent studio from the tab query', () => {
    const wrapper = mountView({ query: { tab: 'agents' } })

    expect(wrapper.find('[data-test="agent-studio"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="nl2sql-chat-v2"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="nl2sql-chat"]').exists()).toBe(false)
    expect(wrapper.find('.el-menu-stub').attributes('data-active')).toBe('agents')
  })

  it('keeps Skills selected when rendering the skill detail route', () => {
    const wrapper = mountView({
      path: '/intelligent-query/skills/marketing-insights',
      name: 'IntelligentQuerySkillDetail',
      params: { folder: 'marketing-insights' }
    })

    expect(wrapper.find('[data-test="skill-detail"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="skill-studio"]').exists()).toBe(false)
    expect(wrapper.find('.el-menu-stub').attributes('data-active')).toBe('skills')
  })

  it('keeps agents selected when rendering the agent detail route', () => {
    const wrapper = mountView({
      path: '/intelligent-query/agents/agent_1',
      name: 'IntelligentQueryAgentDetail',
      params: { agentId: 'agent_1' }
    })

    expect(wrapper.find('[data-test="agent-detail"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="agent-studio"]').exists()).toBe(false)
    expect(wrapper.find('.el-menu-stub').attributes('data-active')).toBe('agents')
  })
})
