import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routerPush = vi.hoisted(() => vi.fn())
const routeState = vi.hoisted(() => ({
  params: { agentId: 'agent_1' }
}))
const dataagentApi = vi.hoisted(() => ({
  getAgent: vi.fn(),
  getAgentCapabilities: vi.fn(),
  updateAgent: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => ({ push: routerPush })
}))

vi.mock('@/api/dataagent', () => ({
  dataagentApi
}))

vi.mock('element-plus', () => ({
  ElButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  ElSkeleton: { template: '<div />' },
  ElForm: { template: '<form><slot /></form>' },
  ElFormItem: { template: '<label><slot /></label>' },
  ElInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  ElSelect: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>'
  },
  ElOption: { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
  ElInputNumber: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />'
  },
  ElCheckboxGroup: { template: '<div><slot /></div>' },
  ElCheckbox: { props: ['label'], template: '<label>{{ label }}</label>' },
  ElCheckboxButton: { props: ['label'], template: '<button type="button">{{ label }}</button>' },
  ElMessage: { success: vi.fn(), error: vi.fn() }
}))

import AgentDetailView from '../AgentDetailView.vue'

const stubs = {
  ElButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  ElSkeleton: { template: '<div />' },
  ElForm: { template: '<form><slot /></form>' },
  ElFormItem: { template: '<label><slot /></label>' },
  ElInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  ElSelect: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>'
  },
  ElOption: { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
  ElInputNumber: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />'
  },
  ElCheckboxGroup: { template: '<div><slot /></div>' },
  ElCheckbox: { props: ['label'], template: '<label>{{ label }}</label>' },
  ElCheckboxButton: { props: ['label'], template: '<button type="button">{{ label }}</button>' }
}

describe('AgentDetailView', () => {
  beforeEach(() => {
    routerPush.mockReset()
    Object.values(dataagentApi).forEach((fn) => fn.mockReset())
    dataagentApi.getAgent.mockResolvedValue({
      agent_id: 'agent_1',
      name: '营销分析',
      description: '营销场景',
      resolved_workdir: '/tmp/agents/agent_1',
      system_prompt: '只做营销分析',
      permission_mode: 'inherit',
      allowed_tools: ['Skill', 'Read'],
      mcp_server_ids: ['portal'],
      skill_folders: ['marketing-insights'],
      max_turns: 12,
      env_vars: { SAFE_FLAG: '1' },
      is_default: false
    })
    dataagentApi.getAgentCapabilities.mockResolvedValue({
      tools: ['Skill', 'Read', 'Bash'],
      mcp_servers: [{ id: 'portal', name: 'Portal MCP', enabled: true, tool_names: [] }],
      skills: [{ folder: 'marketing-insights', source: 'managed', enabled: true }],
      permission_modes: ['inherit', 'default', 'bypassPermissions']
    })
    dataagentApi.updateAgent.mockImplementation(async (_agentId, data) => ({
      agent_id: 'agent_1',
      resolved_workdir: '/tmp/agents/agent_1',
      is_default: false,
      ...data
    }))
  })

  it('loads profile data and saves edited values', async () => {
    const wrapper = shallowMount(AgentDetailView, { global: { stubs } })

    await flushPromises()

    expect(dataagentApi.getAgent).toHaveBeenCalledWith('agent_1')
    expect(wrapper.text()).toContain('营销分析')

    wrapper.vm.form.name = '营销分析 Plus'
    await wrapper.vm.handleSave()
    await flushPromises()

    expect(dataagentApi.updateAgent).toHaveBeenCalledWith(
      'agent_1',
      expect.objectContaining({
        name: '营销分析 Plus',
        env_vars: { SAFE_FLAG: '1' }
      })
    )
  })
})
