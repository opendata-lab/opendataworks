import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routerPush = vi.hoisted(() => vi.fn())
const dataagentApi = vi.hoisted(() => ({
  listAgents: vi.fn(),
  createAgent: vi.fn(),
  deleteAgent: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush })
}))

vi.mock('@/api/dataagent', () => ({
  dataagentApi
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
    routerPush.mockReset()
    Object.values(dataagentApi).forEach((fn) => fn.mockReset())
    dataagentApi.listAgents.mockResolvedValue([
      {
        agent_id: 'agent_default',
        name: '默认智能问数助手',
        description: '默认',
        resolved_workdir: '/tmp/default',
        allowed_tools: ['Skill', 'Read'],
        mcp_server_ids: ['portal'],
        skill_folders: ['dataagent-nl2sql'],
        is_default: true
      }
    ])
  })

  it('renders agent cards from the profile API', async () => {
    const wrapper = shallowMount(AgentStudio, { global: { stubs } })

    await flushPromises()

    expect(dataagentApi.listAgents).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('默认智能问数助手')
    expect(wrapper.text()).toContain('/tmp/default')
    expect(wrapper.text()).toContain('1 Skills')
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
      params: { agentId: 'agent_1' }
    })
  })
})
