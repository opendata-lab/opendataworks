import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routerPush = vi.hoisted(() => vi.fn())
const authState = vi.hoisted(() => ({ isAdmin: true }))
const dataagentApi = vi.hoisted(() =>
  Object.fromEntries(
    [
      'getAgentProfile',
      'getAgentDraft',
      'getAgentBuiltinPrompt',
      'getAgentCapabilities',
      'listDataScopeOptions',
      'listAuthUsers',
      'updateAgent',
      'publishAgent',
      'createAgentPreviewTopic',
      'submitAgentPreview',
    ].map((key) => [key, vi.fn()]),
  ),
)
const runtimeApi = vi.hoisted(() => ({ getConfig: vi.fn() }))
const leaveGuard = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { agentId: 'agent_1' }, query: {} }),
  useRouter: () => ({ push: routerPush }),
  onBeforeRouteLeave: leaveGuard,
  onBeforeRouteUpdate: vi.fn(),
}))
vi.mock('@/api/dataagent', () => ({ dataagentApi }))
vi.mock('@/api/nl2sql', () => ({
  DATAAGENT_CLIENT_HEADERS: {},
  createNl2SqlApiClient: () => ({ runtimeApi }),
}))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authState }))
vi.mock('element-plus', async (original) => ({
  ...(await original()),
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockRejectedValue('cancel') },
}))
import AgentDetailView from '../AgentDetailView.vue'

const profile = {
  agent_id: 'agent_1',
  name: '营销分析',
  description: '营销场景',
  system_prompt: '只做营销分析',
  allowed_tools: ['Read'],
  mcp_server_ids: ['portal'],
  skill_folders: ['marketing-insights'],
  max_turns: 12,
  env_vars: { SAFE_FLAG: '1' },
  data_scope: {
    allowed_scopes: [
      { cluster_id: 3, source_type: 'DORIS', database: 'ads_user' },
    ],
  },
  visibility: {
    mode: 'selected',
    allowed_users: ['local:alice'],
    allowed_groups: [],
  },
  is_default: false,
  is_builtin: false,
  revision: 3,
  published_version: 1,
  has_changes: true,
  can_publish: true,
  preview_status: 'finished',
}
const mount = () =>
  shallowMount(AgentDetailView, {
    global: {
      stubs: {
        Icon: true,
        ElSelect: { template: '<div><slot /></div>' },
        ElOption: true,
      },
      config: {
        compilerOptions: {
          isCustomElement: (tag) => tag === 'dataagent-conversation',
        },
      },
    },
  })
beforeEach(() => {
  HTMLDialogElement.prototype.close = vi.fn()
  HTMLDialogElement.prototype.showModal = vi.fn()
  authState.isAdmin = true
  Object.values(dataagentApi).forEach((fn) => fn.mockReset())
  dataagentApi.getAgentDraft.mockResolvedValue(structuredClone(profile))
  dataagentApi.getAgentProfile.mockResolvedValue({
    ...profile,
    env_vars: undefined,
    visibility: undefined,
  })
  dataagentApi.getAgentBuiltinPrompt.mockResolvedValue({
    content: '# 真实内置提示词',
  })
  dataagentApi.getAgentCapabilities.mockResolvedValue({
    tools: ['Read'],
    skills: [{ folder: 'marketing-insights', enabled: true }],
    mcp_servers: [{ id: 'portal', name: 'Portal MCP', tool_names: [] }],
  })
  dataagentApi.listDataScopeOptions.mockResolvedValue([])
  dataagentApi.listAuthUsers.mockResolvedValue({ items: [] })
  dataagentApi.updateAgent.mockResolvedValue({
    ...profile,
    revision: 4,
    can_publish: false,
    preview_status: null,
  })
  runtimeApi.getConfig.mockResolvedValue({
    providers: [{ provider_id: 'model', models: ['test'], enabled: true }],
    default_provider_id: 'model',
    default_model: 'test',
  })
})

describe('agent draft workbench', () => {
  it('loads the actual builtin prompt and restores saved draft configuration', async () => {
    const wrapper = mount()
    await flushPromises()
    expect(wrapper.find('.builtin-content').text()).toBe('# 真实内置提示词')
    expect(wrapper.find('#custom-prompt').element.value).toBe('只做营销分析')
    expect(wrapper.vm.scopeSelection).toEqual(['3::ads_user'])
    expect(wrapper.vm.canPublish).toBe(true)
    expect(wrapper.findAll('.workbench > .panel')).toHaveLength(3)
    wrapper.unmount()
  })
  it('ignores an older detail response after a newer load has completed', async () => {
    const wrapper = mount()
    await flushPromises()
    let resolveOld
    dataagentApi.getAgentDraft.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const oldLoad = wrapper.vm.loadDetail()
    dataagentApi.getAgentDraft.mockResolvedValueOnce({ ...profile, name: '最新草稿' })
    await wrapper.vm.loadDetail()
    resolveOld({ ...profile, name: '旧草稿' })
    await oldLoad
    expect(wrapper.vm.form.name).toBe('最新草稿')
    wrapper.unmount()
  })

  it('invalidates publication immediately when editing, and saves with a revision', async () => {
    const wrapper = mount()
    await flushPromises()
    await wrapper.find('#custom-prompt').setValue('更新后的草稿')
    expect(wrapper.vm.canPublish).toBe(false)
    await wrapper.vm.handleSave()
    await flushPromises()
    expect(dataagentApi.updateAgent).toHaveBeenCalledWith(
      'agent_1',
      expect.objectContaining({
        system_prompt: '更新后的草稿',
        expected_revision: 3,
        env_vars: { SAFE_FLAG: '1' },
        visibility: profile.visibility,
        data_scope: profile.data_scope,
      }),
    )
    expect(dataagentApi.publishAgent).not.toHaveBeenCalled()
    expect(wrapper.vm.draft.revision).toBe(4)
    wrapper.unmount()
  })
  it('saves before shared-composer send and rotates an outdated preview without remounting', async () => {
    const wrapper = mount()
    await flushPromises()
    wrapper.vm.previewTopicId = 'preview_old'
    wrapper.vm.previewRevision = 3
    const key = wrapper.vm.conversationKey
    await wrapper.find('#custom-prompt').setValue('新的草稿')
    dataagentApi.updateAgent.mockResolvedValue({ ...profile, revision: 4, system_prompt: '新的草稿', can_publish: false })
    expect(await wrapper.vm.beforePreviewSend()).toBe(true)
    expect(dataagentApi.updateAgent).toHaveBeenCalledWith('agent_1', expect.objectContaining({ expected_revision: 3 }))
    expect(wrapper.vm.previewTopicId).toBe('')
    expect(wrapper.vm.conversationKey).toBe(key)
    expect(wrapper.vm.canPublish).toBe(false)
    wrapper.unmount()
  })
  it('rejects attachments from an earlier draft snapshot and clears the stale endpoint', async () => {
    const wrapper = mount()
    await flushPromises()
    wrapper.vm.previewTopicId = 'preview_old'
    wrapper.vm.previewRevision = 2
    await expect(wrapper.vm.beforePreviewSend({ attachments: [{ relPath: 'uploads/old.csv' }] })).rejects.toThrow('重新添加附件')
    expect(wrapper.vm.previewTopicId).toBe('')
    expect(dataagentApi.submitAgentPreview).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('rejects invalid environment JSON without sending a save request', async () => {
    const wrapper = mount()
    await flushPromises()
    wrapper.vm.envVarsText = '[]'
    expect(await wrapper.vm.handleSave()).toBe(false)
    expect(dataagentApi.updateAgent).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('requires explicit acknowledgement before publishing the tested revision', async () => {
    const wrapper = mount()
    await flushPromises()
    await wrapper.vm.handlePublish()
    expect(dataagentApi.publishAgent).not.toHaveBeenCalled()
    wrapper.vm.publishAck = true
    wrapper.vm.publishDialog.close = vi.fn()
    dataagentApi.publishAgent.mockResolvedValue({
      ...profile,
      published_version: 2,
      has_changes: false,
      can_publish: false,
    })
    await wrapper.vm.handlePublish()
    expect(dataagentApi.publishAgent).toHaveBeenCalledWith('agent_1', 3)
    expect(wrapper.vm.draft.published_version).toBe(2)
    wrapper.unmount()
  })
  it('does not overwrite edits made during an in-flight save', async () => {
    const wrapper = mount()
    await flushPromises()
    let resolve
    dataagentApi.updateAgent.mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    await wrapper.find('#agent-name').setValue('第一稿')
    const save = wrapper.vm.handleSave()
    await wrapper.find('#agent-name').setValue('第二稿')
    resolve({ ...profile, name: '第一稿', revision: 4, can_publish: false })
    await save
    expect(wrapper.vm.form.name).toBe('第二稿')
    expect(wrapper.vm.dirty).toBe(true)
    wrapper.unmount()
  })
  it('regular users only load published configuration and cannot save or publish', async () => {
    authState.isAdmin = false
    const wrapper = mount()
    await flushPromises()
    expect(dataagentApi.getAgentDraft).not.toHaveBeenCalled()
    expect(dataagentApi.getAgentCapabilities).not.toHaveBeenCalled()
    expect(wrapper.find('#env-vars').exists()).toBe(false)
    expect(wrapper.find('#visibility').exists()).toBe(false)
    expect(wrapper.find('#custom-prompt').attributes('readonly')).toBeDefined()
    await wrapper.vm.handleSave()
    wrapper.vm.publishAck = true
    await wrapper.vm.handlePublish()
    expect(dataagentApi.updateAgent).not.toHaveBeenCalled()
    expect(dataagentApi.publishAgent).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('keeps publication disabled after a stream error', async () => {
    const wrapper = mount()
    await flushPromises()
    wrapper.vm.onConversationError({ detail: { message: 'SSE 中断' } })
    expect(wrapper.vm.canPublish).toBe(false)
    expect(wrapper.vm.verdict).toBe('SSE 中断')
    wrapper.unmount()
  })
})
