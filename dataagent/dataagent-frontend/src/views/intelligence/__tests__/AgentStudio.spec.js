import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routerPush = vi.hoisted(() => vi.fn())
const authState = vi.hoisted(() => ({ isAdmin: true }))
const dataagentApi = vi.hoisted(() => ({
  listAgentProfiles: vi.fn(),
  listAgentWorkbench: vi.fn(),
  createAgent: vi.fn(),
  deleteAgent: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: routerPush })
}))

vi.mock('@/api/dataagent', () => ({
  dataagentApi
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => authState
}))

vi.mock('element-plus', () => ({
  ElButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  ElTag: { template: '<span><slot /></span>' },
  ElTooltip: { template: '<span><slot /></span>' },
  ElSkeleton: { template: '<div />' },
  ElEmpty: { template: '<div />' },
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue(undefined) }
}))

import AgentStudio from '../AgentStudio.vue'

const stubs = {
  ElButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  ElTag: { template: '<span><slot /></span>' },
  ElTooltip: { template: '<span><slot /></span>' },
  ElSkeleton: { template: '<div />' },
  ElEmpty: { template: '<div />' }
}

describe('AgentStudio', () => {
  beforeEach(() => {
    authState.isAdmin = true
    routerPush.mockReset()
    Object.values(dataagentApi).forEach((fn) => fn.mockReset())
    const profiles = [
      {
        agent_id: 'agent_default',
        name: '默认智能问数助手',
        description: '默认',
        allowed_tools: ['Skill', 'Read'],
        mcp_server_ids: ['portal'],
        skill_folders: ['dataagent-nl2sql'],
        data_scope: { allowed_scopes: [{ cluster_id: 3, database: 'ads_user', source_type: 'DORIS' }] },
        is_default: true,
        is_builtin: true
      }
    ]
    dataagentApi.listAgentProfiles.mockResolvedValue(profiles)
    dataagentApi.listAgentWorkbench.mockResolvedValue(profiles)
  })

  it('renders agent cards from the profile API', async () => {
    const wrapper = shallowMount(AgentStudio, { global: { stubs } })

    await flushPromises()

    expect(dataagentApi.listAgentWorkbench).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('默认智能问数助手')
    expect(wrapper.text()).toContain('1 Skills')
    expect(wrapper.text()).toContain('1 Schema')
    expect(wrapper.text()).toContain('内置')
  })

  it('keeps unpublished drafts out of the chat entrypoint', async () => {
    dataagentApi.listAgentWorkbench.mockResolvedValue([{ agent_id: 'draft', name: '未发布助手', published_version: 0 }])
    const wrapper = shallowMount(AgentStudio, { global: { stubs } })
    await flushPromises()
    expect(wrapper.text()).toContain('未发布')
    wrapper.vm.handleChat({ agent_id: 'draft', published_version: 0 })
    expect(routerPush).not.toHaveBeenCalled()
  })

  it('creates an agent and navigates to detail', async () => {
    dataagentApi.createAgent.mockResolvedValue({ agent_id: 'agent_1' })
    const wrapper = shallowMount(AgentStudio, { global: { stubs } })

    await flushPromises()
    await wrapper.vm.handleCreate()
    await flushPromises()

    expect(dataagentApi.createAgent).toHaveBeenCalledWith(expect.objectContaining({ name: '新智能体' }))
    expect(routerPush).toHaveBeenCalledWith({
      name: 'IntelligentQueryAgentDetail',
      params: { agentId: 'agent_1' },
      query: {}
    })
  })

  it('opens an agent chat through the readable chat URL', async () => {
    const wrapper = shallowMount(AgentStudio, { global: { stubs } })

    await flushPromises()
    wrapper.vm.handleChat({ agent_id: 'agent_default' })

    expect(routerPush).toHaveBeenCalledWith({
      path: '/chat',
      query: { agent_id: 'agent_default' }
    })
  })

  it('hides create and delete actions from regular users', async () => {
    authState.isAdmin = false
    const wrapper = shallowMount(AgentStudio, { global: { stubs } })
    await flushPromises()

    expect(wrapper.text()).not.toContain('新建智能体')
    await wrapper.vm.handleCreate()
    await wrapper.vm.handleDelete({ agent_id: 'agent_default', name: '默认智能问数助手' })
    expect(dataagentApi.createAgent).not.toHaveBeenCalled()
    expect(dataagentApi.deleteAgent).not.toHaveBeenCalled()
  })
})
