import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, reactive } from 'vue'

import WidgetChat from '../WidgetChat.vue'

const apiMocks = vi.hoisted(() => ({
  createClient: vi.fn(),
  runtimeApi: {
    getConfig: vi.fn()
  },
  topicApi: {
    createTopic: vi.fn(),
    listTopics: vi.fn(),
    getTopicMessages: vi.fn(),
    deleteTopic: vi.fn()
  },
  taskApi: {
    deliverMessage: vi.fn(),
    streamTaskEvents: vi.fn(),
    getTask: vi.fn(),
    cancelTask: vi.fn()
  }
}))

vi.mock('@/api/nl2sql', () => ({
  createNl2SqlApiClient: apiMocks.createClient
}))

const baseConfig = {
  displayMode: 'floating',
  projectColor: '#4A90A4',
  agentId: 'agent_widget',
  headers: {
    'X-ODW-Client': 'widget',
    'X-ODW-Website-Id': 'demo',
    'X-ODW-User-Id': 'u1'
  }
}

const topic = (topicId, title, extra = {}) => ({
  topic_id: topicId,
  title,
  message_count: 2,
  last_message_preview: `${title} preview`,
  updated_at: '2026-04-30T08:00:00',
  ...extra
})

const messagePage = (topicId, text) => ({
  topic_id: topicId,
  total: 1,
  items: [
    {
      message_id: `msg-${topicId}`,
      sender_type: 'assistant',
      content: text,
      status: 'success',
      seq_id: 1
    }
  ]
})

function mountChat(options = {}) {
  const state = reactive({
    historyOpen: false,
    outboundMessage: '',
    cancelSignal: 0,
    newConversationSignal: 0,
    selectConversationSignal: 0,
    deleteConversationSignal: 0,
    requestedTopicId: '',
    deleteRequestedTopicId: '',
    ...(options.state || {})
  })
  const wrapper = mount(WidgetChat, {
    props: {
      config: {
        ...baseConfig,
        ...(options.config || {})
      },
      state
    }
  })
  return { wrapper, state }
}

describe('WidgetChat history conversations', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.createClient.mockReturnValue(apiMocks)
    apiMocks.runtimeApi.getConfig.mockResolvedValue({
      default_provider_id: 'anthropic_compatible',
      default_model: 'claude-opus-4-6',
      providers: [
        {
          provider_id: 'anthropic_compatible',
          display_name: 'Anthropic Compatible',
          models: ['claude-opus-4-6'],
          default_model: 'claude-opus-4-6',
          enabled: true
        }
      ]
    })
    apiMocks.topicApi.listTopics.mockResolvedValue([
      topic('topic-1', '最近 30 天工作流发布趋势'),
      topic('topic-2', 'smoke-ok 测试')
    ])
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => messagePage(topicId, `${topicId} 历史回复`))
    apiMocks.topicApi.createTopic.mockResolvedValue(topic('topic-new', 'Widget 会话', { message_count: 0, last_message_preview: '' }))
    apiMocks.topicApi.deleteTopic.mockResolvedValue({ status: 'ok' })
    apiMocks.taskApi.deliverMessage.mockResolvedValue({ task_id: 'task-1' })
    apiMocks.taskApi.getTask.mockResolvedValue({ task_status: 'finished' })
    apiMocks.taskApi.streamTaskEvents.mockResolvedValue()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders inline with portal-style layout, model info, suggestions, and no delete actions', async () => {
    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })

    await flushPromises()

    expect(wrapper.find('.query-workbench').exists()).toBe(true)
    expect(wrapper.find('.query-sidebar').exists()).toBe(true)
    expect(wrapper.find('.query-main').exists()).toBe(true)
    expect(wrapper.find('.query-model-badge').text()).toContain('Anthropic Compatible')
    expect(wrapper.find('.query-model-badge').text()).toContain('claude-opus-4-6')
    expect(wrapper.text()).toContain('最近 30 天工作流发布趋势')
    expect(wrapper.text()).toContain('smoke-ok 测试')
    expect(wrapper.text()).toContain('topic-1 历史回复')
    expect(wrapper.find('[data-testid^="delete-topic-"]').exists()).toBe(false)
    expect(apiMocks.createClient).toHaveBeenCalledWith(expect.objectContaining({
      defaultHeaders: baseConfig.headers
    }))
    expect(apiMocks.topicApi.listTopics).toHaveBeenCalledWith({ agent_id: 'agent_widget' })
    expect(apiMocks.topicApi.getTopicMessages).toHaveBeenCalledWith('topic-1', { page: 1, page_size: 200, order: 'asc' })
  })

  it('filters, switches, and creates conversations through the portal-style history UI', async () => {
    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('.query-search-input').setValue('smoke')
    expect(wrapper.findAll('.query-session-title').map((item) => item.text())).toEqual(['smoke-ok 测试'])

    await wrapper.get('[data-testid="history-topic-topic-2"]').trigger('click')
    await flushPromises()
    expect(apiMocks.topicApi.getTopicMessages).toHaveBeenLastCalledWith('topic-2', { page: 1, page_size: 200, order: 'asc' })
    expect(wrapper.text()).toContain('topic-2 历史回复')

    await wrapper.get('[data-testid="new-conversation"]').trigger('click')
    await flushPromises()
    expect(apiMocks.topicApi.createTopic).toHaveBeenCalledWith('Widget 会话', { agent_id: 'agent_widget' })
    expect(wrapper.text()).toContain('Widget 会话')
    expect(wrapper.text()).toContain('请输入你的数据问题')
    expect(apiMocks.topicApi.deleteTopic).not.toHaveBeenCalled()
  })

  it('shows a configuration error when the embedding script omits agent id', async () => {
    const { wrapper } = mountChat({ config: { agentId: '', displayMode: 'inline' } })

    await flushPromises()

    expect(wrapper.text()).toContain('Widget 缺少 data-agent-id 配置')
    expect(wrapper.get('[data-testid="new-conversation"]').attributes('disabled')).toBeDefined()
    expect(apiMocks.topicApi.listTopics).not.toHaveBeenCalled()
    expect(apiMocks.topicApi.createTopic).not.toHaveBeenCalled()
  })

  it('renders floating as a compact portal-style panel with a history drawer and no delete actions', async () => {
    const { wrapper } = mountChat({ config: { displayMode: 'floating' }, state: { historyOpen: true } })
    await flushPromises()

    expect(wrapper.find('.query-workbench').classes()).toContain('is-floating')
    expect(wrapper.find('.query-sidebar').exists()).toBe(true)
    expect(wrapper.find('.query-sidebar-backdrop').exists()).toBe(true)
    expect(wrapper.find('.query-model-badge').text()).toContain('claude-opus-4-6')
    expect(wrapper.find('[data-testid^="delete-topic-"]').exists()).toBe(false)
  })

  it('prevents switching and creating while an answer is running', async () => {
    let resolveStream
    apiMocks.taskApi.streamTaskEvents.mockImplementation(() => new Promise((resolve) => {
      resolveStream = resolve
    }))
    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('textarea').setValue('你好')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-testid="new-conversation"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="history-topic-topic-2"]').attributes('disabled')).toBeDefined()

    resolveStream()
  })

  it('queues outbound messages until runtime config is ready', async () => {
    let resolveConfig
    apiMocks.runtimeApi.getConfig.mockReturnValue(new Promise((resolve) => {
      resolveConfig = resolve
    }))
    const { state } = mountChat({ config: { displayMode: 'inline' } })

    state.outboundMessage = 'queued outbound question'
    await nextTick()
    await flushPromises()

    expect(apiMocks.taskApi.deliverMessage).not.toHaveBeenCalled()

    resolveConfig({
      default_provider_id: 'anthropic_compatible',
      default_model: 'claude-opus-4-6',
      providers: [
        {
          provider_id: 'anthropic_compatible',
          display_name: 'Anthropic Compatible',
          models: ['claude-opus-4-6'],
          default_model: 'claude-opus-4-6',
          enabled: true
        }
      ]
    })
    await flushPromises()
    await nextTick()

    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalledWith(expect.objectContaining({
      content: 'queued outbound question',
      agent_id: 'agent_widget'
    }))
  })

  it('renders messageStream-style text deltas in the portal-style assistant body', async () => {
    apiMocks.taskApi.streamTaskEvents.mockImplementation(async (_taskId, options) => {
      options.onEvent({ type: 'text.delta', payload: { text: 'smoke-' } })
      options.onEvent({ type: 'text.delta', payload: { text: 'ok' } })
      options.onEvent({ type: 'done', payload: { status: 'success' } })
    })
    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('textarea').setValue('你好')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.findAll('.query-main-text').some((item) => item.text().includes('smoke-ok'))).toBe(true)
    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalledWith(expect.objectContaining({
      agent_id: 'agent_widget'
    }))
  })
})
