import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({
  topicApi: {
    createTopic: vi.fn(),
    listTopics: vi.fn(),
    getTopic: vi.fn(),
    getTopicMessages: vi.fn()
  },
  taskApi: {
    deliverMessage: vi.fn(),
    streamSdkEvents: vi.fn()
  },
  adminApi: {
    getSettings: vi.fn()
  },
  agentApi: {
    listAgents: vi.fn()
  }
}))

const routeState = vi.hoisted(() => ({
  path: '/intelligent-query',
  name: 'IntelligentQuery',
  query: {},
  params: {}
}))

const routerReplace = vi.hoisted(() => vi.fn())

vi.mock('@/api/nl2sql', () => ({
  createNl2SqlApiClient: () => apiMocks
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
    error: vi.fn()
  },
  ElOption: { name: 'ElOption' },
  ElScrollbar: { name: 'ElScrollbar' },
  ElSelect: { name: 'ElSelect' }
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

const mountChat = () => mount(NL2SqlChatV2, {
  global: {
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
        props: ['modelValue', 'disabled'],
        emits: ['update:modelValue', 'change'],
        template: '<div class="el-select-stub"><slot name="prefix" /><slot /></div>'
      },
      ElOption: {
        props: ['label', 'value'],
        template: '<span class="el-option-stub" :data-value="value">{{ label }}</span>'
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
      ToolOutputRenderer: {
        props: ['tool'],
        template: '<div class="tool-output-renderer-stub" />'
      }
    }
  }
})

describe('NL2SqlChatV2 URL location', () => {
  beforeEach(() => {
    Object.values(apiMocks.topicApi).forEach((fn) => fn.mockReset())
    Object.values(apiMocks.taskApi).forEach((fn) => fn.mockReset())
    Object.values(apiMocks.adminApi).forEach((fn) => fn.mockReset())
    Object.values(apiMocks.agentApi).forEach((fn) => fn.mockReset())
    routerReplace.mockReset()
    scrollbarSetScrollTop.mockReset()

    routeState.path = '/intelligent-query'
    routeState.name = 'IntelligentQuery'
    routeState.query = {}
    routeState.params = {}

    apiMocks.adminApi.getSettings.mockResolvedValue({
      default_provider_id: 'provider-1',
      default_model: 'model-1',
      providers: [
        {
          provider_id: 'provider-1',
          models: ['model-1']
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
  })

  it('opens the topic from the URL and scrolls to the target message', async () => {
    const scrollIntoView = vi.fn()
    Object.defineProperty(window.HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView
    })
    routeState.query = {
      tab: 'chat-v2',
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

  it('surfaces the error card when reloading a failed (status=error) assistant message', async () => {
    routeState.query = {
      tab: 'chat-v2',
      topic_id: 'topic-3'
    }

    const wrapper = mountChat()

    await flushPromises()
    await nextTick()

    const errorCard = wrapper.find('.v2-error-card')
    expect(errorCard.exists()).toBe(true)
    expect(errorCard.text()).toContain('模型会话异常结束')
  })

  it('writes the selected topic to the URL and clears the previous message target', async () => {
    routeState.query = {
      tab: 'chat-v2',
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
      path: '/intelligent-query',
      query: {
        tab: 'chat-v2',
        topic_id: 'topic-2'
      }
    })
  })
})
