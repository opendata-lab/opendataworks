import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, reactive } from 'vue'

import WidgetChat from '../WidgetChat.vue'
import widgetChatSource from '../WidgetChat.vue?raw'

// ECharts touches the canvas API, which jsdom doesn't fully implement. The chart
// promotion tests only care about which container the chart lands in, so stub the
// renderer to keep the conclusion-area direct chart from emitting canvas errors.
const echartsMocks = vi.hoisted(() => {
  const instance = { setOption: vi.fn(), resize: vi.fn(), clear: vi.fn(), dispose: vi.fn() }
  return { instance, init: vi.fn(() => instance) }
})

vi.mock('echarts/core', () => ({
  use: () => {},
  init: echartsMocks.init
}))
vi.mock('echarts/charts', () => ({ BarChart: {}, LineChart: {}, PieChart: {} }))
vi.mock('echarts/components', () => ({
  GridComponent: {},
  LegendComponent: {},
  TitleComponent: {},
  TooltipComponent: {}
}))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

const apiMocks = vi.hoisted(() => ({
  createClient: vi.fn(),
  runtimeApi: {
    getConfig: vi.fn()
  },
  topicApi: {
    createTopic: vi.fn(),
    listTopics: vi.fn(),
    getTopicMessages: vi.fn(),
    deleteTopic: vi.fn(),
    updateMessageFeedback: vi.fn()
  },
  taskApi: {
    deliverMessage: vi.fn(),
    streamSdkEvents: vi.fn(),
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
    apiMocks.topicApi.updateMessageFeedback.mockImplementation(async (_topicId, messageId, feedback) => ({ message_id: messageId, feedback }))
    apiMocks.taskApi.deliverMessage.mockResolvedValue({ task_id: 'task-1' })
    apiMocks.taskApi.getTask.mockResolvedValue({ task_status: 'finished' })
    apiMocks.taskApi.streamSdkEvents.mockResolvedValue()
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
    expect(wrapper.find('.query-model-selector').text()).toContain('Anthropic Compatible')
    expect(wrapper.find('.query-model-selector').text()).toContain('claude-opus-4-6')
    expect(wrapper.text()).toContain('最近 30 天工作流发布趋势')
    expect(wrapper.text()).toContain('smoke-ok 测试')
    // Opens on a fresh conversation instead of auto-selecting the latest topic,
    // so the previous topic's history is not loaded on mount.
    expect(wrapper.text()).not.toContain('topic-1 历史回复')
    expect(wrapper.find('[data-testid^="delete-topic-"]').exists()).toBe(false)
    expect(apiMocks.createClient).toHaveBeenCalledWith(expect.objectContaining({
      defaultHeaders: baseConfig.headers
    }))
    expect(apiMocks.topicApi.listTopics).toHaveBeenCalledWith({ page: 1, page_size: 50, agent_id: 'agent_widget' })
    expect(apiMocks.topicApi.getTopicMessages).not.toHaveBeenCalled()
  })

  it('filters, switches, and creates conversations through the portal-style history UI', async () => {
    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('.query-search-input').setValue('smoke')
    expect(wrapper.findAll('.query-session-title').map((item) => item.text())).toEqual(['smoke-ok 测试'])

    await wrapper.get('[data-testid="history-topic-topic-2"]').trigger('click')
    await flushPromises()
    expect(apiMocks.topicApi.getTopicMessages).toHaveBeenLastCalledWith('topic-2', { page: 1, page_size: 500, order: 'asc' })
    expect(wrapper.text()).toContain('topic-2 历史回复')

    await wrapper.get('[data-testid="new-conversation"]').trigger('click')
    await flushPromises()
    expect(apiMocks.topicApi.createTopic).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('您可以问我以下问题')
    expect(apiMocks.topicApi.deleteTopic).not.toHaveBeenCalled()
  })

  it('shows status dots in the history list driven by current_task_status', async () => {
    apiMocks.topicApi.listTopics.mockResolvedValue([
      topic('topic-err', '失败的会话', { current_task_status: 'error' }),
      topic('topic-sus', '取消的会话', { current_task_status: 'suspended' }),
      topic('topic-ok', '完成的会话', { current_task_status: 'finished' })
    ])

    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    expect(wrapper.find('[data-testid="history-topic-topic-err"] .query-session-dot.is-error').exists()).toBe(true)
    expect(wrapper.find('[data-testid="history-topic-topic-sus"] .query-session-dot.is-suspended').exists()).toBe(true)
    expect(wrapper.find('[data-testid="history-topic-topic-ok"] .query-session-dot').exists()).toBe(false)
  })

  it('refreshes session record statuses while background widget topics are running', async () => {
    vi.useFakeTimers()
    apiMocks.topicApi.listTopics
      .mockResolvedValueOnce([
        topic('topic-bg', '后台运行会话', {
          current_task_id: 'task-bg',
          current_task_status: 'running'
        })
      ])
      .mockResolvedValueOnce([
        topic('topic-bg', '后台运行会话', {
          current_task_id: 'task-bg',
          current_task_status: 'finished'
        })
      ])

    try {
      const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
      await flushPromises()

      expect(wrapper.find('[data-testid="history-topic-topic-bg"] .query-session-loading').exists()).toBe(true)

      await vi.advanceTimersByTimeAsync(3000)
      await flushPromises()

      expect(apiMocks.topicApi.listTopics).toHaveBeenCalledTimes(2)
      expect(wrapper.find('[data-testid="history-topic-topic-bg"] .query-session-loading').exists()).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('surfaces the error card when opening a failed (status=error) history conversation', async () => {
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => ({
      topic_id: topicId,
      total: 1,
      items: [
        {
          message_id: `msg-${topicId}`,
          sender_type: 'assistant',
          content: '',
          status: 'error',
          error: { code: 'model_error', message: '模型会话异常结束' },
          seq_id: 1
        }
      ]
    }))

    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('[data-testid="history-topic-topic-2"]').trigger('click')
    await flushPromises()

    const errorCard = wrapper.find('.query-error-card')
    expect(errorCard.exists()).toBe(true)
    expect(errorCard.text()).toContain('模型会话异常结束')
  })

  it('supports copying and feedback on assistant messages like chat v2', async () => {
    const writeText = vi.fn().mockResolvedValue()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText }
    })
    Object.defineProperty(window, 'isSecureContext', {
      configurable: true,
      value: true
    })
    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('[data-testid="history-topic-topic-2"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="copy-message-msg-topic-2"]').trigger('click')
    expect(writeText).toHaveBeenCalledWith('topic-2 历史回复')

    await wrapper.get('[data-testid="feedback-like-msg-topic-2"]').trigger('click')
    expect(apiMocks.topicApi.updateMessageFeedback).toHaveBeenCalledWith('topic-2', 'msg-topic-2', 'like')
    expect(wrapper.get('[data-testid="feedback-like-msg-topic-2"]').classes()).toContain('active')

    await wrapper.get('[data-testid="feedback-dislike-msg-topic-2"]').trigger('click')
    expect(apiMocks.topicApi.updateMessageFeedback).toHaveBeenCalledWith('topic-2', 'msg-topic-2', 'dislike')
    expect(wrapper.get('[data-testid="feedback-dislike-msg-topic-2"]').classes()).toContain('active')
  })

  it('falls back when the widget clipboard API rejects and shows copy feedback', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('permission denied'))
    const execCommand = vi.fn().mockReturnValue(true)
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: execCommand
    })
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText }
    })
    Object.defineProperty(window, 'isSecureContext', {
      configurable: true,
      value: true
    })
    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('[data-testid="history-topic-topic-2"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="copy-message-msg-topic-2"]').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith('topic-2 历史回复')
    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(wrapper.find('.query-copy-toast').text()).toBe('已复制')
  })

  it('keeps widget message action footers hidden until hover or keyboard focus', () => {
    expect(widgetChatSource).toContain('.query-message-footer {\n  display: flex;')
    expect(widgetChatSource).toContain('opacity: 0;')
    expect(widgetChatSource).toContain('.query-message-row:hover .query-message-footer')
    expect(widgetChatSource).toContain('.query-message-footer:focus-within')
  })

  it('renders inline chart specs from assistant text without showing raw spec markup', async () => {
    const chartSpec = JSON.stringify({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'line',
      title: '发布趋势',
      x_field: 'stat_day',
      series: [{ name: '发布次数', field: 'publish_cnt', type: 'line' }],
      dataset: [{ stat_day: '2026-03-10', publish_cnt: 3 }]
    })
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => ({
      topic_id: topicId,
      total: 1,
      items: [
        {
          message_id: `msg-${topicId}`,
          sender_type: 'assistant',
          status: 'success',
          content: `趋势如下。\n${chartSpec}\n以上是最近发布情况。`,
          seq_id: 1
        }
      ]
    }))

    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('[data-testid="history-topic-topic-2"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('趋势如下。')
    expect(wrapper.text()).toContain('以上是最近发布情况。')
    expect(wrapper.text()).not.toContain('"kind":"chart_spec"')
    expect(wrapper.text()).not.toContain('"chart_type"')
    expect(wrapper.find('.chart-spec-view').exists()).toBe(true)
  })

  it('creates the first sent conversation at the top of the history list', async () => {
    apiMocks.topicApi.createTopic.mockResolvedValue(topic('topic-new', '新话题', {
      message_count: 0,
      last_message_preview: '',
      updated_at: '2026-04-30T09:00:00'
    }))

    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('[data-testid="new-conversation"]').trigger('click')
    await flushPromises()

    expect(apiMocks.topicApi.createTopic).not.toHaveBeenCalled()

    await wrapper.get('textarea').setValue('首次输入创建会话')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(apiMocks.topicApi.createTopic).toHaveBeenCalledWith('首次输入创建会话', { agent_id: 'agent_widget' })
    expect(wrapper.findAll('.query-session-title').map((item) => item.text())).toEqual([
      '首次输入创建会话',
      '最近 30 天工作流发布趋势',
      'smoke-ok 测试'
    ])
    expect(wrapper.get('[data-testid="history-topic-topic-new"]').classes()).toContain('active')
    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalledWith(expect.objectContaining({
      topic_id: 'topic-new',
      content: '首次输入创建会话',
      agent_id: 'agent_widget'
    }))
  })

  it('keeps the first sent conversation at the top when initial history load resolves late', async () => {
    let resolveTopics
    apiMocks.topicApi.listTopics.mockReturnValue(new Promise((resolve) => {
      resolveTopics = resolve
    }))
    apiMocks.topicApi.createTopic.mockResolvedValue(topic('topic-new', '新话题', {
      message_count: 0,
      last_message_preview: '',
      updated_at: '2026-04-30T09:00:00'
    }))

    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('[data-testid="new-conversation"]').trigger('click')
    await wrapper.get('textarea').setValue('首次输入创建会话')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    resolveTopics([
      topic('topic-1', '最近 30 天工作流发布趋势'),
      topic('topic-2', 'smoke-ok 测试')
    ])
    await flushPromises()

    expect(wrapper.findAll('.query-session-title').map((item) => item.text())).toEqual([
      '首次输入创建会话',
      '最近 30 天工作流发布趋势',
      'smoke-ok 测试'
    ])
    expect(wrapper.get('[data-testid="history-topic-topic-new"]').classes()).toContain('active')
  })

  it('loads topics without agent id filter when agentId is not configured', async () => {
    const { wrapper } = mountChat({ config: { agentId: '', displayMode: 'inline' } })

    await flushPromises()

    expect(wrapper.find('.query-workbench').exists()).toBe(true)
    expect(apiMocks.topicApi.listTopics).toHaveBeenCalledWith({ page: 1, page_size: 50, agent_id: undefined })
    expect(apiMocks.topicApi.createTopic).not.toHaveBeenCalled()
  })

  it('renders floating as a compact portal-style panel with a history drawer and no delete actions', async () => {
    const { wrapper } = mountChat({ config: { displayMode: 'floating' }, state: { historyOpen: true } })
    await flushPromises()

    expect(wrapper.find('.query-workbench').classes()).toContain('is-floating')
    expect(wrapper.find('.query-sidebar').exists()).toBe(true)
    expect(wrapper.find('.query-sidebar-backdrop').exists()).toBe(true)
    expect(wrapper.find('.query-model-selector').text()).toContain('claude-opus-4-6')
    expect(wrapper.find('[data-testid^="delete-topic-"]').exists()).toBe(false)
  })

  it('allows switching to an existing topic while an answer is running, detaching the run', async () => {
    let resolveStream
    apiMocks.taskApi.streamSdkEvents.mockImplementation(() => new Promise((resolve) => {
      resolveStream = resolve
    }))
    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('textarea').setValue('你好')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // History items stay clickable mid-run (consistent with chat v2's session list).
    const topicButton = wrapper.get('[data-testid="history-topic-topic-2"]')
    expect(topicButton.attributes('disabled')).toBeUndefined()

    await topicButton.trigger('click')
    await flushPromises()

    // Switching detaches the run locally without cancelling the backend task,
    // and loads the selected topic's history.
    expect(apiMocks.taskApi.cancelTask).not.toHaveBeenCalled()
    expect(apiMocks.topicApi.getTopicMessages).toHaveBeenLastCalledWith('topic-2', { page: 1, page_size: 500, order: 'asc' })
    expect(wrapper.text()).toContain('topic-2 历史回复')

    resolveStream()
  })

  it('allows starting a new conversation mid-run, detaching the backend task instead of cancelling it', async () => {
    let resolveStream
    apiMocks.taskApi.streamSdkEvents.mockImplementation(() => new Promise((resolve) => {
      resolveStream = resolve
    }))
    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('textarea').setValue('你好')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // New conversation stays available while an answer is running.
    const newButton = wrapper.get('[data-testid="new-conversation"]')
    expect(newButton.attributes('disabled')).toBeUndefined()

    await newButton.trigger('click')
    await flushPromises()

    // The in-flight run is detached locally; the backend task is left running.
    expect(apiMocks.taskApi.cancelTask).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('您可以问我以下问题')

    // The fresh conversation immediately accepts a new question.
    apiMocks.taskApi.deliverMessage.mockClear()
    apiMocks.taskApi.deliverMessage.mockResolvedValue({ task_id: 'task-2' })
    await wrapper.get('textarea').setValue('第二个问题')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalledWith(expect.objectContaining({
      content: '第二个问题'
    }))

    resolveStream()
  })

  it('reattaches the running task when returning to a detached widget conversation', async () => {
    const streams = []
    let topicAFinished = false
    apiMocks.topicApi.listTopics.mockResolvedValue([
      topic('topic-b', '另一个运行会话', {
        current_task_id: 'task-b',
        current_task_status: 'running'
      })
    ])
    apiMocks.topicApi.createTopic.mockResolvedValue(topic('topic-a', '当前新会话', {
      message_count: 0,
      last_message_preview: '',
      updated_at: '2026-04-30T09:00:00'
    }))
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => {
      if (topicId === 'topic-a') {
        return {
          topic_id: topicId,
          total: 2,
          items: [
            { message_id: 'user-a', sender_type: 'user', content: '新会话问题', seq_id: 1 },
            {
              message_id: 'assistant-a',
              sender_type: 'assistant',
              status: topicAFinished ? 'success' : 'running',
              task_id: 'task-a',
              content: '',
              blocks: topicAFinished ? [{ kind: 'main_text', text: '恢复完成' }] : [],
              resume_after_seq: 7,
              seq_id: 2
            }
          ]
        }
      }
      if (topicId === 'topic-b') {
        return {
          topic_id: topicId,
          total: 2,
          items: [
            { message_id: 'user-b', sender_type: 'user', content: '另一个问题', seq_id: 1 },
            {
              message_id: 'assistant-b',
              sender_type: 'assistant',
              status: 'running',
              task_id: 'task-b',
              content: '',
              blocks: [],
              resume_after_seq: 3,
              seq_id: 2
            }
          ]
        }
      }
      return messagePage(topicId, `${topicId} 历史回复`)
    })
    apiMocks.taskApi.deliverMessage.mockResolvedValue({ task_id: 'task-a' })
    apiMocks.taskApi.streamSdkEvents.mockImplementation((taskId, options = {}) => new Promise((resolve, reject) => {
      const stream = { taskId, options, resolve, reject }
      streams.push(stream)
      options.signal?.addEventListener('abort', () => {
        const error = new Error('aborted')
        error.name = 'AbortError'
        reject(error)
      })
    }))
    apiMocks.taskApi.getTask.mockImplementation(async (taskId) => ({
      task_id: taskId,
      topic_id: taskId === 'task-a' ? 'topic-a' : 'topic-b',
      task_status: 'finished'
    }))

    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('textarea').setValue('新会话问题')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(streams.map((stream) => stream.taskId)).toEqual(['task-a'])

    await wrapper.get('[data-testid="history-topic-topic-b"]').trigger('click')
    await flushPromises()

    expect(streams.map((stream) => stream.taskId)).toEqual(['task-a', 'task-b'])
    expect(apiMocks.taskApi.cancelTask).not.toHaveBeenCalled()
    expect(wrapper.find('.query-cancel-btn').exists()).toBe(true)

    await wrapper.get('[data-testid="history-topic-topic-a"]').trigger('click')
    await flushPromises()

    expect(streams.map((stream) => stream.taskId)).toEqual(['task-a', 'task-b', 'task-a'])
    expect(streams.at(-1).options.afterId).toBe(7)
    expect(wrapper.find('.query-cancel-btn').exists()).toBe(true)

    const resumedStream = streams.at(-1)
    resumedStream.options.onRecord({ seq_id: 8, record_type: 'stream', data: { type: 'message_start', usage: {} } })
    resumedStream.options.onRecord({ seq_id: 9, record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } })
    resumedStream.options.onRecord({ seq_id: 10, record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: '恢复完成' } } })
    resumedStream.options.onRecord({ seq_id: 11, record_type: 'stream', data: { type: 'content_block_stop', index: 0 } })
    resumedStream.options.onRecord({ seq_id: 12, record_type: 'done', data: {} })
    topicAFinished = true
    resumedStream.resolve()
    await flushPromises()

    expect(wrapper.text()).toContain('恢复完成')
    expect(wrapper.find('.query-cancel-btn').exists()).toBe(false)
    expect(apiMocks.topicApi.listTopics).toHaveBeenCalledTimes(2)
  })

  it('sends on plain Enter but keeps Shift+Enter / IME Enter as a newline', async () => {
    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    const textarea = wrapper.get('textarea')
    await textarea.setValue('你好')

    // Shift+Enter must not send and must not block the default newline.
    const shiftEvent = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, cancelable: true, bubbles: true })
    textarea.element.dispatchEvent(shiftEvent)
    await flushPromises()
    expect(shiftEvent.defaultPrevented).toBe(false)
    expect(apiMocks.taskApi.deliverMessage).not.toHaveBeenCalled()

    // IME composition Enter (confirming candidates) must not send.
    const imeEvent = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true, bubbles: true })
    Object.defineProperty(imeEvent, 'isComposing', { value: true })
    textarea.element.dispatchEvent(imeEvent)
    await flushPromises()
    expect(apiMocks.taskApi.deliverMessage).not.toHaveBeenCalled()

    // Plain Enter sends and prevents the newline.
    const enterEvent = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true, bubbles: true })
    textarea.element.dispatchEvent(enterEvent)
    await flushPromises()
    expect(enterEvent.defaultPrevented).toBe(true)
    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalledWith(expect.objectContaining({
      content: '你好',
      agent_id: 'agent_widget'
    }))
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
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => {
      if (topicId === 'topic-new') {
        return {
          topic_id: topicId,
          total: 1,
          items: [{
            message_id: 'msg-topic-new',
            sender_type: 'assistant',
            status: 'success',
            blocks: [{ kind: 'main_text', text: 'smoke-ok' }],
            seq_id: 1
          }]
        }
      }
      return messagePage(topicId, `${topicId} 历史回复`)
    })
    apiMocks.taskApi.streamSdkEvents.mockImplementation(async (_taskId, options) => {
      options.onRecord({ record_type: 'stream', data: { type: 'message_start', usage: {} } })
      options.onRecord({ record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } })
      options.onRecord({ record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'smoke-' } } })
      options.onRecord({ record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'ok' } } })
      options.onRecord({ record_type: 'stream', data: { type: 'content_block_stop', index: 0 } })
      options.onRecord({ record_type: 'done', data: {} })
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

  it('renders a tool-produced chart inline below its tool-call block (no conclusion duplicate)', async () => {
    const chartSpec = JSON.stringify({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'line',
      title: '发布趋势',
      x_field: 'stat_day',
      series: [{ name: '发布次数', field: 'publish_cnt', type: 'line' }],
      dataset: [{ stat_day: '2026-03-10', publish_cnt: 3 }],
      error: null
    })
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => {
      if (topicId === 'topic-new') {
        return {
          topic_id: topicId,
          total: 1,
          items: [{
            message_id: 'msg-topic-new',
            sender_type: 'assistant',
            status: 'success',
            blocks: [
              { kind: 'tool_use', tool_id: 'tool-chart', tool_name: 'Bash', input: null, output: `build ok\n${chartSpec}`, is_error: false },
              { kind: 'main_text', text: '最近发布趋势如下。' }
            ],
            seq_id: 1
          }]
        }
      }
      return messagePage(topicId, `${topicId} 历史回复`)
    })

    apiMocks.taskApi.streamSdkEvents.mockImplementation(async (_taskId, options) => {
      options.onRecord({ record_type: 'stream', data: { type: 'message_start', usage: {} } })
      options.onRecord({ record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'tool-chart', name: 'Bash' } } })
      options.onRecord({ record_type: 'stream', data: { type: 'content_block_stop', index: 0 } })
      // Build script result delivered as tool-result content blocks.
      options.onRecord({ record_type: 'tool_result', data: { tool_use_id: 'tool-chart', content: [{ type: 'text', text: `build ok\n${chartSpec}` }] } })
      options.onRecord({ record_type: 'stream', data: { type: 'content_block_start', index: 1, content_block: { type: 'text' } } })
      options.onRecord({ record_type: 'stream', data: { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: '最近发布趋势如下。' } } })
      options.onRecord({ record_type: 'stream', data: { type: 'content_block_stop', index: 1 } })
      options.onRecord({ record_type: 'done', data: {} })
    })

    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('textarea').setValue('最近发布趋势')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // Chart renders directly below its tool-call block, not in a separate conclusion area.
    expect(wrapper.find('.query-tool-row').exists()).toBe(true)
    expect(wrapper.find('.query-tool-row .tool-chart-below').exists()).toBe(true)
    expect(wrapper.find('.query-final-chart').exists()).toBe(false)
  })

  it('renders a raw chart_spec embedded in the conclusion prose as a chart instead of JSON', async () => {
    const chartSpec = JSON.stringify({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'line',
      title: '发布趋势',
      x_field: 'stat_day',
      series: [{ name: '发布次数', field: 'publish_cnt', type: 'line' }],
      dataset: [{ stat_day: '2026-03-10', publish_cnt: 3 }],
      error: null
    })
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => {
      if (topicId === 'topic-new') {
        return {
          topic_id: topicId,
          total: 1,
          items: [{
            message_id: 'msg-topic-new',
            sender_type: 'assistant',
            status: 'success',
            content: `结论：发布次数上升。\n${chartSpec}\n以上为结论。`,
            seq_id: 1
          }]
        }
      }
      return messagePage(topicId, `${topicId} 历史回复`)
    })

    apiMocks.taskApi.streamSdkEvents.mockImplementation(async (_taskId, options) => {
      options.onRecord({ record_type: 'stream', data: { type: 'message_start', usage: {} } })
      options.onRecord({ record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } })
      options.onRecord({ record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: `结论：发布次数上升。\n${chartSpec}\n以上为结论。` } } })
      options.onRecord({ record_type: 'stream', data: { type: 'content_block_stop', index: 0 } })
      options.onRecord({ record_type: 'done', data: {} })
    })

    const { wrapper } = mountChat({ config: { displayMode: 'inline' } })
    await flushPromises()

    await wrapper.get('textarea').setValue('最近发布趋势')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const mainText = wrapper.find('.query-main-text')
    expect(mainText.exists()).toBe(true)
    expect(mainText.find('.chart-spec-view').exists()).toBe(true)
    expect(mainText.text()).toContain('结论：发布次数上升。')
    expect(mainText.text()).toContain('以上为结论。')
    expect(mainText.text()).not.toContain('chart_type')
  })
})
