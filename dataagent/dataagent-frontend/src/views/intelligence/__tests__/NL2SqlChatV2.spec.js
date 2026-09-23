import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { defineAgentConversation } from '@opendataworks/agent-conversation'

// The page renders the conversation as a custom element. Registering it is the
// application's job (`main.js`), which a component test never runs — without
// this the tag mounts as an inert unknown element: no transport calls, no
// messages, and every assertion about the conversation fails for one reason.
beforeAll(() => {
  defineAgentConversation()
})

/** The conversation element inside the page. */
const conversation = (wrapper) => wrapper.element.querySelector('dataagent-conversation')

/** Everything the element renders, which lives in its shadow root. */
const conversationText = (wrapper) => conversation(wrapper)?.shadowRoot?.textContent || ''

/** Query inside the element, where the messages and composer now live. */
const inConversation = (wrapper, selector) =>
  conversation(wrapper)?.shadowRoot?.querySelector(selector) || null

const apiMocks = vi.hoisted(() => ({
  topicApi: {
    createTopic: vi.fn(),
    listTopics: vi.fn(),
    getTopic: vi.fn(),
    getTopicMessages: vi.fn()
  },
  taskApi: {
    deliverMessage: vi.fn(),
    streamSdkEvents: vi.fn(),
    getTask: vi.fn(),
    cancelTask: vi.fn()
  },
  runtimeApi: {
    getConfig: vi.fn()
  },
  agentApi: {
    listAgents: vi.fn()
  }
}))

const routeState = vi.hoisted(() => ({
  path: '/chat',
  name: 'IntelligentQueryChat',
  query: {},
  params: {}
}))

const routerReplace = vi.hoisted(() => vi.fn())

const dataagentApiMock = vi.hoisted(() => ({
  listWidgetTopics: vi.fn(),
  listWidgetUsers: vi.fn(),
  getWidgetTopicMessages: vi.fn()
}))

vi.mock('@/api/nl2sql', () => ({
  createNl2SqlApiClient: () => apiMocks,
  DATAAGENT_CLIENT_HEADERS: Object.freeze({ 'X-ODW-Client': 'dataagent' })
}))

vi.mock('@/api/dataagent', () => ({
  dataagentApi: dataagentApiMock
}))

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => ({
    replace: routerReplace
  })
}))

vi.mock('element-plus', () => ({
  ElDropdown: { name: 'ElDropdown' },
  ElDropdownItem: { name: 'ElDropdownItem' },
  ElDropdownMenu: { name: 'ElDropdownMenu' },
  ElMessage: {
    error: vi.fn(),
    warning: vi.fn()
  },
  ElMessageBox: {
    confirm: vi.fn(async () => true)
  },
  ElOption: { name: 'ElOption' },
  ElPopover: { name: 'ElPopover' },
  ElRadio: { name: 'ElRadio' },
  ElRadioGroup: { name: 'ElRadioGroup' },
  ElScrollbar: { name: 'ElScrollbar' },
  ElSelect: { name: 'ElSelect' },
  ElTooltip: { name: 'ElTooltip' }
}))

import NL2SqlChatV2 from '../NL2SqlChatV2.vue'
import nl2SqlChatV2Source from '../NL2SqlChatV2.vue?raw'

const makeTopic = (topicId, title) => ({
  topic_id: topicId,
  title,
  created_at: '2026-05-30T02:00:00Z',
  updated_at: '2026-05-30T02:00:00Z'
})

const topicMessages = {
  'topic-1': [
    {
      message_id: 'u1',
      topic_id: 'topic-1',
      sender_type: 'user',
      content: 'first question',
      created_at: '2026-05-30T02:00:00Z'
    },
    {
      message_id: 'a1',
      topic_id: 'topic-1',
      sender_type: 'assistant',
      content: 'first answer',
      created_at: '2026-05-30T02:01:00Z'
    }
  ],
  'topic-2': [
    {
      message_id: 'u2',
      topic_id: 'topic-2',
      sender_type: 'user',
      content: 'second question',
      created_at: '2026-05-30T03:00:00Z'
    },
    {
      message_id: 'a2',
      topic_id: 'topic-2',
      sender_type: 'assistant',
      content: 'second answer',
      created_at: '2026-05-30T03:01:00Z'
    }
  ],
  'topic-3': [
    {
      message_id: 'u3',
      topic_id: 'topic-3',
      sender_type: 'user',
      content: 'failing question',
      created_at: '2026-05-30T04:00:00Z'
    },
    {
      message_id: 'a3',
      topic_id: 'topic-3',
      sender_type: 'assistant',
      status: 'error',
      content: '',
      error: { code: 'model_error', message: '模型会话异常结束' },
      created_at: '2026-05-30T04:01:00Z'
    }
  ]
}

const scrollbarSetScrollTop = vi.fn()

const mountChat = ({ withAgent = true } = {}) => {
  if (withAgent && !Object.prototype.hasOwnProperty.call(routeState.query, 'agent_id')) {
    routeState.query = { ...routeState.query, agent_id: 'agent_default' }
  }
  return mount(NL2SqlChatV2, {
  global: {
    plugins: [createPinia()],
    stubs: {
      ElScrollbar: {
        name: 'ElScrollbar',
        emits: ['scroll'],
        methods: {
          setScrollTop: scrollbarSetScrollTop
        },
        template: '<div class="el-scrollbar-stub"><slot /></div>'
      },
      ElSelect: {
        inheritAttrs: false,
        props: ['modelValue', 'disabled'],
        emits: ['update:modelValue', 'change'],
        template: `
          <label class="el-select-wrapper" :class="$attrs.class">
            <slot name="prefix" />
            <select
              class="el-select-stub"
              :disabled="disabled"
              :value="modelValue"
              @change="$emit('update:modelValue', $event.target.value); $emit('change', $event.target.value)"
            >
              <slot />
            </select>
          </label>
        `
      },
      ElOption: {
        props: ['label', 'value'],
        template: '<option class="el-option-stub" :data-value="value" :value="value">{{ label }}</option>'
      },
      ElDropdown: {
        template: '<div class="el-dropdown-stub"><slot /><slot name="dropdown" /></div>'
      },
      ElDropdownMenu: {
        template: '<div class="el-dropdown-menu-stub"><slot /></div>'
      },
      ElDropdownItem: {
        props: ['command'],
        template: '<button type="button" class="el-dropdown-item-stub" :data-command="command"><slot /></button>'
      },
      ElPopover: {
        template: '<div class="el-popover-stub"><slot name="reference" /><slot /></div>'
      },
      ElTooltip: {
        template: '<span class="el-tooltip-stub"><slot /></span>'
      },
      ElRadioGroup: {
        props: ['modelValue'],
        emits: ['update:modelValue'],
        template: '<div class="el-radio-group-stub"><slot /></div>'
      },
      ElRadio: {
        props: ['label'],
        template: '<label class="el-radio-stub" :data-label="label"><slot /></label>'
      },
      ToolOutputRenderer: {
        props: ['tool'],
        template: '<div class="tool-output-renderer-stub" :data-output-kind="tool?.output?.kind || \'\'" />'
      },
      ChartSpecView: {
        props: ['spec'],
        template: '<div class="chart-spec-view-stub" :data-chart-type="spec?.chart_type || \'\'" />'
      }
    }
  }
  })
}

describe('NL2SqlChatV2 URL location', () => {
  beforeEach(() => {
    Object.values(apiMocks.topicApi).forEach((fn) => fn.mockReset())
    Object.values(apiMocks.taskApi).forEach((fn) => fn.mockReset())
    Object.values(apiMocks.runtimeApi).forEach((fn) => fn.mockReset())
    Object.values(apiMocks.agentApi).forEach((fn) => fn.mockReset())
    Object.values(dataagentApiMock).forEach((fn) => fn.mockReset())
    routerReplace.mockReset()
    scrollbarSetScrollTop.mockReset()

    dataagentApiMock.listWidgetTopics.mockResolvedValue({ items: [], total: 0, page: 1, page_size: 50 })
    dataagentApiMock.listWidgetUsers.mockResolvedValue({ items: [] })
    dataagentApiMock.getWidgetTopicMessages.mockResolvedValue({
      topic_id: '', page: 1, page_size: 500, order: 'asc', total: 0, items: []
    })

    routeState.path = '/chat'
    routeState.name = 'IntelligentQueryChat'
    routeState.query = {}
    routeState.params = {}

    apiMocks.runtimeApi.getConfig.mockResolvedValue({
      default_provider_id: 'provider-1',
      default_model: 'model-1',
      providers: [
        {
          provider_id: 'provider-1',
          models: ['model-1'],
          enabled: true
        }
      ]
    })
    apiMocks.agentApi.listAgents.mockResolvedValue([
      {
        agent_id: 'agent_default',
        name: 'Default agent',
        is_default: true
      }
    ])
    apiMocks.topicApi.listTopics.mockResolvedValue({
      list: [
        makeTopic('topic-1', 'First topic'),
        makeTopic('topic-2', 'Second topic'),
        makeTopic('topic-3', 'Failed topic')
      ]
    })
    apiMocks.topicApi.getTopic.mockImplementation(async (topicId) => makeTopic(topicId, `Topic ${topicId}`))
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => ({
      topic_id: topicId,
      page: 1,
      page_size: 500,
      order: 'asc',
      total: topicMessages[topicId]?.length || 0,
      items: topicMessages[topicId] || []
    }))
    apiMocks.taskApi.getTask.mockResolvedValue({ task_status: 'success' })
    apiMocks.taskApi.cancelTask.mockResolvedValue({ status: 'ok' })
  })

  it('shows an assistant-only welcome page when agent_id is missing', async () => {
    const wrapper = mountChat({ withAgent: false })

    await flushPromises()
    await nextTick()

    expect(wrapper.find('.v2-agent-welcome').exists()).toBe(true)
    expect(wrapper.text()).toContain('欢迎使用 DataAgent')
    expect(wrapper.text()).toContain('Default agent')
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('.v2-sidebar').exists()).toBe(false)
    expect(apiMocks.agentApi.listAgents).toHaveBeenCalledTimes(1)
    expect(apiMocks.runtimeApi.getConfig).not.toHaveBeenCalled()
    expect(apiMocks.topicApi.listTopics).not.toHaveBeenCalled()
  })

  it('enters the workbench only after the user selects an assistant', async () => {
    apiMocks.agentApi.listAgents.mockResolvedValue([
      { agent_id: 'agent_default', name: 'Default agent', description: 'General help', is_default: true },
      { agent_id: 'agent_sales', name: 'Sales agent', description: 'Sales analysis', is_default: false }
    ])
    const wrapper = mountChat({ withAgent: false })

    await flushPromises()
    await nextTick()
    const salesButton = wrapper.findAll('.agent-selector-pill').find((button) => button.text().includes('Sales agent'))
    expect(salesButton).toBeTruthy()
    await salesButton.trigger('click')
    await flushPromises()
    await nextTick()

    expect(routerReplace).toHaveBeenCalledWith({ path: '/chat', query: { agent_id: 'agent_sales' } })
    expect(wrapper.find('.v2-workbench').exists()).toBe(true)
    expect(wrapper.find('textarea').exists()).toBe(true)
    expect(wrapper.find('.v2-agent-title').text()).toBe('Sales agent')
    expect(wrapper.find('.v2-agent-select').exists()).toBe(true)
  })

  it('returns an invalid assistant URL to the welcome page', async () => {
    routeState.query = { agent_id: 'agent_removed', topic_id: 'topic-1', message_id: 'a1' }
    const wrapper = mountChat()

    await flushPromises()
    await nextTick()

    expect(wrapper.find('.v2-agent-welcome').exists()).toBe(true)
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(routerReplace).toHaveBeenCalledWith({ path: '/chat', query: {} })
  })

  it('shows only the static assistant name in the header and keeps switching in the sidebar', async () => {
    const wrapper = mountChat()

    await flushPromises()
    await nextTick()

    expect(wrapper.find('.v2-agent-title').text()).toBe('Default agent')
    expect(wrapper.find('.v2-agent-header').exists()).toBe(false)
    expect(wrapper.find('.v2-topic-title').exists()).toBe(false)
    expect(wrapper.find('.v2-agent-select').exists()).toBe(true)
  })

  it('opens the topic from the URL and scrolls to the target message', async () => {
    const scrollIntoView = vi.fn()
    Object.defineProperty(window.HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView
    })
    routeState.query = {
      topic_id: 'topic-2',
      message_id: 'a2'
    }

    const wrapper = mountChat()

    await flushPromises()
    await nextTick()

    expect(apiMocks.topicApi.getTopicMessages).toHaveBeenCalledWith('topic-2', {
      page: 1,
      page_size: 500,
      order: 'asc'
    })
    expect(wrapper.find('.v2-session-item.active .v2-session-title').text()).toBe('Second topic')
    expect(wrapper.find('[data-message-id="a2"]').classes()).toContain('is-target-message')
    expect(scrollIntoView).toHaveBeenCalledWith({
      block: 'center',
      behavior: 'smooth'
    })
  })

  it('keeps message action footers hidden until hover or keyboard focus', () => {
    expect(nl2SqlChatV2Source).toContain('.v2-msg-footer {')
    expect(nl2SqlChatV2Source).toContain('opacity: 0;')
    expect(nl2SqlChatV2Source).toContain('.v2-msg-row:hover .v2-msg-footer')
    expect(nl2SqlChatV2Source).toContain('.v2-msg-footer:focus-within')
  })

  it('shows status dots in the session list driven by current_task_status', async () => {
    apiMocks.topicApi.listTopics.mockResolvedValue({
      list: [
        { ...makeTopic('topic-err', 'Failed topic'), current_task_status: 'error' },
        { ...makeTopic('topic-sus', 'Cancelled topic'), current_task_status: 'suspended' },
        { ...makeTopic('topic-ok', 'Done topic'), current_task_status: 'finished' }
      ]
    })

    const wrapper = mountChat()

    await flushPromises()
    await nextTick()

    const items = wrapper.findAll('.v2-session-item')
    expect(items[0].find('.v2-session-dot.is-error').exists()).toBe(true)
    expect(items[1].find('.v2-session-dot.is-suspended').exists()).toBe(true)
    expect(items[2].find('.v2-session-dot').exists()).toBe(false)
  })

  it('surfaces the error card when reloading a failed (status=error) assistant message', async () => {
    routeState.query = {
      topic_id: 'topic-3'
    }

    const wrapper = mountChat()

    await flushPromises()
    await nextTick()

    const errorCard = wrapper.find('.v2-error-card')
    expect(errorCard.exists()).toBe(true)
    expect(errorCard.text()).toContain('模型会话异常结束')
    expect(errorCard.find('.v2-error-retry').exists()).toBe(true)
  })

  it('retries the failed question from the error card and continues the conversation', async () => {
    routeState.query = {
      topic_id: 'topic-3'
    }
    apiMocks.taskApi.deliverMessage.mockResolvedValue({ task_id: 'task-retry' })
    apiMocks.taskApi.streamSdkEvents.mockImplementation(async (_taskId, opts) => {
      opts.onRecord({ record_type: 'stream', data: { type: 'message_start' } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: '重试后的回答' } } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_stop', index: 0 } })
      opts.onRecord({ record_type: 'done', data: {} })
    })
    apiMocks.taskApi.getTask.mockResolvedValue({ task_status: 'finished' })
    // deliver-message persists the retried turn server-side; the portal reloads
    // messages after each run settles, so the mock must reflect that.
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => {
      const items = [...(topicMessages[topicId] || [])]
      if (topicId === 'topic-3' && apiMocks.taskApi.deliverMessage.mock.calls.length) {
        items.push(
          { message_id: 'u3r', topic_id: 'topic-3', sender_type: 'user', content: 'failing question', created_at: '2026-05-30T04:02:00Z' },
          { message_id: 'a3r', topic_id: 'topic-3', sender_type: 'assistant', content: '重试后的回答', created_at: '2026-05-30T04:03:00Z' }
        )
      }
      return { topic_id: topicId, page: 1, page_size: 500, order: 'asc', total: items.length, items }
    })

    const wrapper = mountChat()

    await flushPromises()
    await nextTick()

    await wrapper.find('.v2-error-card .v2-error-retry').trigger('click')
    await flushPromises()
    await nextTick()

    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalledTimes(1)
    expect(apiMocks.taskApi.deliverMessage.mock.calls[0][0]).toMatchObject({
      topic_id: 'topic-3',
      content: 'failing question'
    })
    // The failed reply's error card stays; the retried answer renders after it.
    expect(wrapper.find('.v2-error-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('重试后的回答')
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
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a-chart',
          topic_id: topicId,
          sender_type: 'assistant',
          status: 'finished',
          content: `趋势如下。\n${chartSpec}\n以上是最近发布情况。`,
          created_at: '2026-05-30T05:01:00Z'
        }
      ]
    }))
    routeState.query = {
      topic_id: 'topic-1'
    }

    const wrapper = mountChat()

    await flushPromises()
    await nextTick()

    expect(wrapper.text()).toContain('趋势如下。')
    expect(wrapper.text()).toContain('以上是最近发布情况。')
    expect(wrapper.text()).not.toContain('"kind":"chart_spec"')
    expect(wrapper.text()).not.toContain('"chart_type"')
    expect(wrapper.find('.chart-spec-view-stub[data-chart-type="line"]').exists()).toBe(true)
  })

  it('writes the selected topic to the URL and clears the previous message target', async () => {
    routeState.query = {
      topic_id: 'topic-1',
      message_id: 'a1'
    }
    const wrapper = mountChat()

    await flushPromises()
    await nextTick()
    await wrapper.findAll('.v2-session-item')[1].trigger('click')
    await flushPromises()

    expect(apiMocks.topicApi.getTopicMessages).toHaveBeenLastCalledWith('topic-2', {
      page: 1,
      page_size: 500,
      order: 'asc'
    })
    expect(routerReplace).toHaveBeenLastCalledWith({
      path: '/chat',
      query: {
        agent_id: 'agent_default',
        topic_id: 'topic-2'
      }
    })
  })

  it('clears the active conversation and removes stale topic query when switching assistants', async () => {
    routeState.query = {
      agent_id: 'agent_default',
      topic_id: 'topic-1',
      message_id: 'a1'
    }
    apiMocks.agentApi.listAgents.mockResolvedValue([
      {
        agent_id: 'agent_default',
        name: 'Default agent',
        is_default: true
      },
      {
        agent_id: 'agent_sales',
        name: 'Sales agent',
        is_default: false
      }
    ])

    const wrapper = mountChat()

    await flushPromises()
    await nextTick()
    expect(wrapper.text()).toContain('first answer')

    await wrapper.find('.v2-agent-select select').setValue('agent_sales')
    await flushPromises()
    await nextTick()

    expect(wrapper.text()).not.toContain('first answer')
    expect(wrapper.find('.v2-session-item.active').exists()).toBe(false)
    expect(routerReplace).toHaveBeenLastCalledWith({
      path: '/chat',
      query: {
        agent_id: 'agent_sales'
      }
    })
  })

  it('sends a new question through the shared engine: creates a topic, delivers, streams, and routes', async () => {
    apiMocks.topicApi.createTopic.mockResolvedValue(makeTopic('topic-new', 'hi there'))
    apiMocks.taskApi.deliverMessage.mockResolvedValue({ task_id: 'task-1' })
    apiMocks.taskApi.getTask = vi.fn().mockResolvedValue({ task_status: 'success' })
    apiMocks.taskApi.streamSdkEvents.mockImplementation(async (_taskId, opts) => {
      opts.onRecord({ record_type: 'stream', data: { type: 'message_start', usage: {} } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'streamed answer' } } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_stop', index: 0 } })
      opts.onRecord({ record_type: 'done', data: {} })
    })

    const wrapper = mountChat()
    await flushPromises()
    await nextTick()

    // Start a fresh conversation (mount auto-selects the latest topic).
    await wrapper.find('.v2-btn-new').trigger('click')
    await wrapper.find('textarea').setValue('hi there')
    await wrapper.find('.v2-send-btn').trigger('click')
    await flushPromises()

    expect(apiMocks.topicApi.createTopic).toHaveBeenCalledWith('hi there', { agent_id: 'agent_default', permission_mode: 'default' })
    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalledWith(
      expect.objectContaining({ topic_id: 'topic-new', content: 'hi there', provider_id: 'provider-1', model: 'model-1' })
    )
    // onTopicEnsured routes to the freshly created topic.
    expect(routerReplace).toHaveBeenCalledWith(expect.objectContaining({
      query: expect.objectContaining({ topic_id: 'topic-new' })
    }))
    expect(wrapper.text()).toContain('streamed answer')
  })

  it('does not send on Enter during IME composition, but sends on plain Enter', async () => {
    apiMocks.topicApi.createTopic.mockResolvedValue(makeTopic('topic-new', 'hi'))
    apiMocks.taskApi.deliverMessage.mockResolvedValue({ task_id: 'task-1' })
    apiMocks.taskApi.getTask = vi.fn().mockResolvedValue({ task_status: 'success' })
    apiMocks.taskApi.streamSdkEvents.mockResolvedValue()

    const wrapper = mountChat()
    await flushPromises()
    await nextTick()
    await wrapper.find('.v2-btn-new').trigger('click')
    await wrapper.find('textarea').setValue('hi')

    // IME candidate-selection Enter must not send.
    await wrapper.find('textarea').trigger('keydown.enter', { isComposing: true })
    await flushPromises()
    expect(apiMocks.taskApi.deliverMessage).not.toHaveBeenCalled()

    // Plain Enter sends.
    await wrapper.find('textarea').trigger('keydown.enter')
    await flushPromises()
    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalled()
  })

  it('disables the send button when no model is available', async () => {
    apiMocks.runtimeApi.getConfig.mockResolvedValue({ default_provider_id: '', default_model: '', providers: [] })

    const wrapper = mountChat()
    await flushPromises()
    await nextTick()
    await wrapper.find('.v2-btn-new').trigger('click')
    await wrapper.find('textarea').setValue('hi')

    expect(wrapper.find('.v2-send-btn').attributes('disabled')).toBeDefined()
  })

  it('shows cancel only after the backend task id is available', async () => {
    let resolveDeliver
    let resolveStream
    apiMocks.topicApi.createTopic.mockResolvedValue(makeTopic('topic-new', 'hi there'))
    apiMocks.taskApi.deliverMessage.mockImplementation(() => new Promise((resolve) => { resolveDeliver = resolve }))
    apiMocks.taskApi.streamSdkEvents.mockImplementation(() => new Promise((resolve) => { resolveStream = resolve }))

    const wrapper = mountChat()
    await flushPromises()
    await nextTick()

    await wrapper.find('.v2-btn-new').trigger('click')
    await wrapper.find('textarea').setValue('hi there')
    await wrapper.find('.v2-send-btn').trigger('click')
    await flushPromises()

    const buttonDuringDelivery = wrapper.get('.v2-send-btn')
    expect(buttonDuringDelivery.classes()).not.toContain('v2-cancel-btn')
    expect(buttonDuringDelivery.attributes('disabled')).toBeDefined()

    resolveDeliver({ task_id: 'task-1' })
    await flushPromises()
    await nextTick()

    const buttonWithTask = wrapper.get('.v2-send-btn')
    expect(buttonWithTask.classes()).toContain('v2-cancel-btn')
    expect(buttonWithTask.attributes('disabled')).toBeUndefined()

    resolveStream()
  })

  it('resumes the live stream and shows the stop button when re-entering a running topic', async () => {
    let resolveStream
    apiMocks.topicApi.listTopics.mockResolvedValue({
      list: [
        { ...makeTopic('topic-run', 'Running topic'), current_task_id: 'task-run', current_task_status: 'running' }
      ]
    })
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => ({
      topic_id: topicId,
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        { message_id: 'u-run', topic_id: topicId, sender_type: 'user', content: 'running question', created_at: '2026-05-30T02:00:00Z' }
      ]
    }))
    apiMocks.taskApi.streamSdkEvents.mockImplementation((_taskId, opts) => {
      opts.onRecord({ record_type: 'stream', data: { type: 'message_start', usage: {} } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'resumed stream' } } })
      return new Promise((resolve) => { resolveStream = resolve })
    })
    routeState.query = { topic_id: 'topic-run' }

    const wrapper = mountChat()
    await flushPromises()
    await nextTick()

    // The engine re-attaches to the still-running backend task from seq 0.
    expect(apiMocks.taskApi.streamSdkEvents).toHaveBeenCalledWith('task-run', expect.objectContaining({ afterId: 0 }))
    expect(wrapper.text()).toContain('resumed stream')
    // An active task turns the send button into the stop/cancel control.
    const button = wrapper.get('.v2-send-btn')
    expect(button.classes()).toContain('v2-cancel-btn')
    expect(button.attributes('disabled')).toBeUndefined()
    // The composer stays editable mid-run so the next question can be typed.
    expect(wrapper.find('textarea').attributes('disabled')).toBeUndefined()

    resolveStream()
  })

  it('keeps the loading indicator after re-entering a running topic that has not streamed content yet', async () => {
    let resolveStream
    apiMocks.topicApi.listTopics.mockResolvedValue({
      list: [
        { ...makeTopic('topic-run', 'Running topic'), current_task_id: 'task-run', current_task_status: 'running' }
      ]
    })
    // The persisted placeholder assistant message (created at deliver time with
    // status "waiting") rehydrates with a single empty turn — the case where the
    // old `!turns.length` indicator condition wrongly hid the spinner on resume.
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => ({
      topic_id: topicId,
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 2,
      items: [
        { message_id: 'u-run', topic_id: topicId, sender_type: 'user', content: 'running question', created_at: '2026-05-30T02:00:00Z' },
        { message_id: 'a-run', topic_id: topicId, sender_type: 'assistant', task_id: 'task-run', content: '', status: 'waiting', blocks: [], created_at: '2026-05-30T02:00:01Z' }
      ]
    }))
    // Resume re-attaches but the backend has produced nothing yet (still waiting).
    apiMocks.taskApi.streamSdkEvents.mockImplementation(() => new Promise((resolve) => { resolveStream = resolve }))
    routeState.query = { topic_id: 'topic-run' }

    const wrapper = mountChat()
    await flushPromises()
    await nextTick()

    expect(apiMocks.taskApi.streamSdkEvents).toHaveBeenCalledWith('task-run', expect.objectContaining({ afterId: 0 }))
    // The "thinking" dots stay visible because the resumed run is still live.
    expect(wrapper.find('.v2-typing-indicator').exists()).toBe(true)

    resolveStream()
  })

  it('keeps a trailing loading indicator between rendered content and completion, then clears it when done', async () => {
    let resolveStream
    let recordSink
    apiMocks.topicApi.listTopics.mockResolvedValue({
      list: [
        { ...makeTopic('topic-run', 'Running topic'), current_task_id: 'task-run', current_task_status: 'running' }
      ]
    })
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => ({
      topic_id: topicId,
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        { message_id: 'u-run', topic_id: topicId, sender_type: 'user', content: 'running question', created_at: '2026-05-30T02:00:00Z' }
      ]
    }))
    // Stream one settled text block, then keep the run open: the tail block is no
    // longer streaming but the run is still live, so the trailing dots must show.
    apiMocks.taskApi.streamSdkEvents.mockImplementation((_taskId, opts) => {
      recordSink = opts.onRecord
      opts.onRecord({ record_type: 'stream', data: { type: 'message_start', usage: {} } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'partial answer' } } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_stop', index: 0 } })
      return new Promise((resolve) => { resolveStream = resolve })
    })
    routeState.query = { topic_id: 'topic-run' }

    const wrapper = mountChat()
    await flushPromises()
    await nextTick()

    expect(wrapper.text()).toContain('partial answer')
    // The pre-content top indicator is gone (content rendered) but the trailing
    // cue keeps the run visibly active while it continues.
    expect(wrapper.find('.v2-typing-indicator-trailing').exists()).toBe(true)

    // The refresh after a terminal stream observes the persisted terminal row;
    // otherwise this mock's permanently-running row would correctly trigger a
    // second resume and keep the indicator visible.
    apiMocks.topicApi.listTopics.mockResolvedValue({
      list: [
        { ...makeTopic('topic-run', 'Running topic'), current_task_id: 'task-run', current_task_status: 'finished' }
      ]
    })
    recordSink({ record_type: 'done', data: {} })
    resolveStream()
    await flushPromises()
    await nextTick()

    // Completion clears every activity cue.
    await vi.waitFor(() => {
      expect(wrapper.find('.v2-typing-indicator-trailing').exists()).toBe(false)
      expect(wrapper.find('.v2-typing-indicator').exists()).toBe(false)
    })
  })

  it('stops the Pi reasoning animation when its completion event arrives', async () => {
    let resolveStream
    apiMocks.topicApi.listTopics.mockResolvedValue({
      list: [
        { ...makeTopic('topic-run', 'Running topic'), current_task_id: 'task-run', current_task_status: 'running' }
      ]
    })
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => ({
      topic_id: topicId,
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        { message_id: 'u-run', topic_id: topicId, sender_type: 'user', content: 'running question', created_at: '2026-05-30T02:00:00Z' }
      ]
    }))
    apiMocks.taskApi.streamSdkEvents.mockImplementation((_taskId, opts) => {
      opts.onRecord({ record_type: 'pi_event', event_type: 'run.started', data: {} })
      opts.onRecord({ record_type: 'pi_event', event_type: 'turn.started', data: { turn_id: 'turn-1' } })
      opts.onRecord({
        record_type: 'pi_event',
        event_type: 'content.started',
        data: { turn_id: 'turn-1', content_id: 'c-0', kind: 'reasoning' }
      })
      opts.onRecord({
        record_type: 'pi_event',
        event_type: 'content.delta',
        data: { turn_id: 'turn-1', content_id: 'c-0', kind: 'reasoning', delta: '分析中' }
      })
      opts.onRecord({
        record_type: 'pi_event',
        event_type: 'content.completed',
        data: { turn_id: 'turn-1', content_id: 'c-0', kind: 'reasoning', text: '分析中' }
      })
      opts.onRecord({
        record_type: 'pi_event',
        event_type: 'content.started',
        data: { turn_id: 'turn-1', content_id: 'c-1', kind: 'answer' }
      })
      opts.onRecord({
        record_type: 'pi_event',
        event_type: 'content.delta',
        data: { turn_id: 'turn-1', content_id: 'c-1', kind: 'answer', delta: '当前回答' }
      })
      return new Promise((resolve) => { resolveStream = resolve })
    })
    routeState.query = { topic_id: 'topic-run' }

    const wrapper = mountChat()
    await flushPromises()
    await nextTick()

    expect(wrapper.text()).toContain('当前回答')
    expect(wrapper.findAll('.v2-cursor')).toHaveLength(1)
    expect(wrapper.findAll('.v2-badge-dot')).toHaveLength(0)

    resolveStream()
  })

  it('forwards the selected assistant to the widget topic query', async () => {
    routeState.query = { agent_id: 'agent_sales' }
    apiMocks.agentApi.listAgents.mockResolvedValue([
      { agent_id: 'agent_default', name: 'Default agent', is_default: true },
      { agent_id: 'agent_sales', name: 'Sales agent', is_default: false }
    ])
    const wrapper = mountChat()

    await flushPromises()
    await nextTick()

    const widgetTab = wrapper.findAll('.v2-source-tab').find((b) => b.text() === 'Widget')
    expect(widgetTab).toBeTruthy()
    await widgetTab.trigger('click')
    await flushPromises()

    expect(dataagentApiMock.listWidgetTopics).toHaveBeenCalled()
    expect(dataagentApiMock.listWidgetTopics).toHaveBeenLastCalledWith(
      expect.objectContaining({ agent_id: 'agent_sales' })
    )
  })
})
