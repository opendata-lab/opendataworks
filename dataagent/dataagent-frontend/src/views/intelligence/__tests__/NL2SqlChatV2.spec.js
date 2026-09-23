import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
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
const mountedWrappers = []

const mountChat = ({ withAgent = true } = {}) => {
  if (withAgent && !Object.prototype.hasOwnProperty.call(routeState.query, 'agent_id')) {
    routeState.query = { ...routeState.query, agent_id: 'agent_default' }
  }
  const wrapper = mount(NL2SqlChatV2, {
  // A registered custom element starts its Vue app from connectedCallback.
  // VTU's detached default never connects it, so transport/ready behaviour
  // would be skipped for a reason that cannot happen in the real page.
  attachTo: document.body,
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
  mountedWrappers.push(wrapper)
  return wrapper
}

describe('NL2SqlChatV2 URL location', () => {
  afterEach(() => {
    while (mountedWrappers.length) mountedWrappers.pop().unmount()
  })

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
    expect(conversation(wrapper)).toBeInstanceOf(HTMLElement)
    expect(typeof conversation(wrapper).transportFactory).toBe('function')
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
    let resolveTopicMessages
    apiMocks.topicApi.getTopicMessages.mockImplementation((topicId) => new Promise((resolve) => {
      resolveTopicMessages = () => resolve({
        topic_id: topicId,
        page: 1,
        page_size: 500,
        order: 'asc',
        total: topicMessages[topicId]?.length || 0,
        items: topicMessages[topicId] || []
      })
    }))
    routeState.query = {
      topic_id: 'topic-2',
      message_id: 'a2'
    }

    const wrapper = mountChat()
    await vi.waitFor(() => expect(conversation(wrapper)?._instance?.exposed).toBeTruthy())
    const focusMessage = vi.spyOn(conversation(wrapper)._instance.exposed, 'focusMessage')
    resolveTopicMessages()

    await flushPromises()
    await nextTick()

    expect(apiMocks.topicApi.getTopicMessages).toHaveBeenCalledWith('topic-2', {
      page: 1,
      page_size: 500
    })
    expect(wrapper.find('.v2-session-item.active .v2-session-title').text()).toBe('Second topic')
    await vi.waitFor(() => expect(focusMessage).toHaveBeenCalledWith('a2'))
  })

  // Message-action hover/focus visibility lives in SDK `messageActions.spec.js`.

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

  // Failed-message error cards live in SDK `errorRecovery.spec.js`.
  // Error-card retry and continued rendering live in SDK `errorRecovery.spec.js`.
  // Inline chart-spec extraction/rendering lives in SDK `textRendering.spec.js`.

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
      page_size: 500
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
    expect(apiMocks.topicApi.getTopicMessages).toHaveBeenCalledWith('topic-1', {
      page: 1,
      page_size: 500
    })
    expect(conversation(wrapper).endpoint).toBe('topic-1')

    await wrapper.find('.v2-agent-select select').setValue('agent_sales')
    await flushPromises()
    await nextTick()

    expect(conversation(wrapper).endpoint).toBe('')
    expect(wrapper.find('.v2-session-item.active').exists()).toBe(false)
    expect(routerReplace).toHaveBeenLastCalledWith({
      path: '/chat',
      query: {
        agent_id: 'agent_sales'
      }
    })
  })

  it('passes topic creation, transport, and composer settings to the conversation element', async () => {
    apiMocks.topicApi.createTopic.mockResolvedValue(makeTopic('topic-new', 'hi there'))

    const wrapper = mountChat()
    await flushPromises()
    await nextTick()

    // Start a fresh conversation (mount auto-selects the latest topic).
    await wrapper.find('.v2-btn-new').trigger('click')
    await flushPromises()

    const element = conversation(wrapper)
    expect(element.endpoint).toBe('')
    expect(typeof element.endpointResolver).toBe('function')
    expect(typeof element.transportFactory).toBe('function')
    expect(element.composerConfig.providers).toEqual([
      expect.objectContaining({ provider_id: 'provider-1', models: ['model-1'] })
    ])
    expect(element.composerConfig.default_provider_id).toBe('provider-1')
    expect(element.composerConfig.default_model).toBe('model-1')

    await element.endpointResolver({ content: 'hi there', settings: { permission_mode: 'default' } })

    expect(apiMocks.topicApi.createTopic).toHaveBeenCalledWith('hi there', {
      agent_id: 'agent_default',
      permission_mode: 'default'
    })
    expect(routerReplace).toHaveBeenCalledWith(expect.objectContaining({
      query: expect.objectContaining({ topic_id: 'topic-new' })
    }))
  })

  // IME Enter handling lives in SDK `composerConfig.spec.js`.

  it('passes an empty provider list to the conversation element when no model is available', async () => {
    apiMocks.runtimeApi.getConfig.mockResolvedValue({ default_provider_id: '', default_model: '', providers: [] })

    const wrapper = mountChat()
    await flushPromises()
    await nextTick()

    expect(conversation(wrapper).composerConfig.providers).toEqual([])
    expect(conversation(wrapper).composerConfig.default_provider_id).toBe('')
    expect(conversation(wrapper).composerConfig.default_model).toBe('')
  })

  // Send/cancel control transitions live in SDK `errorRecovery.spec.js`.

  it('reconnects the transport when re-entering a running topic', async () => {
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
    apiMocks.topicApi.getTopic.mockResolvedValue({
      ...makeTopic('topic-run', 'Running topic'),
      current_task_id: 'task-run',
      current_task_status: 'running'
    })
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

    resolveStream()
  })

  // Re-entered-run pre-content activity cues live in SDK `textRendering.spec.js`.
  // Settled-content trailing activity cues live in SDK `elementIntegration.spec.js`.
  // Pi content completion lives in `agentEventReducer.spec.js`; reasoning/cursor
  // rendering lives in SDK `thinkingBlock.spec.js` and `textRendering.spec.js`.

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
