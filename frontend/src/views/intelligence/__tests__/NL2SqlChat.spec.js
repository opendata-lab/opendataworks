import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({
  topicApi: {
    createTopic: vi.fn(),
    listTopics: vi.fn(),
    getTopic: vi.fn(),
    updateTopic: vi.fn(),
    deleteTopic: vi.fn(),
    getTopicMessages: vi.fn(),
    updateMessageFeedback: vi.fn(),
    generateFollowupSuggestions: vi.fn()
  },
  taskApi: {
    deliverMessage: vi.fn(),
    createTask: vi.fn(),
    getTask: vi.fn(),
    getTaskEvents: vi.fn(),
    cancelTask: vi.fn(),
    streamTaskEvents: vi.fn()
  },
  messageQueueApi: {
    query: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    consume: vi.fn()
  },
  scheduleApi: {
    query: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    get: vi.fn(),
    logs: vi.fn()
  },
  adminApi: {
    getSettings: vi.fn(),
    updateSettings: vi.fn()
  },
  agentApi: {
    listAgents: vi.fn(),
    getAgent: vi.fn(),
    createAgent: vi.fn(),
    updateAgent: vi.fn(),
    deleteAgent: vi.fn(),
    getCapabilities: vi.fn()
  },
  health: vi.fn()
}))
const routeState = vi.hoisted(() => ({
  query: {},
  params: {},
  path: '/intelligent-query',
  name: 'IntelligentQuery'
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
  ElMessage: {
    success: vi.fn(),
    error: vi.fn()
  },
  ElScrollbar: {
    name: 'ElScrollbar',
    template: '<div class="el-scrollbar-stub"><slot /></div>'
  },
  ElSelect: {
    name: 'ElSelect',
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue', 'change'],
    template: '<button type="button" class="el-select-stub" :class="$attrs.class" :disabled="disabled" @click="$emit(\'update:modelValue\', \'agent_sales\'); $emit(\'change\', \'agent_sales\')"><slot /></button>'
  },
  ElOption: {
    name: 'ElOption',
    props: ['label', 'value'],
    template: '<span class="el-option-stub" :data-value="value">{{ label }}</span>'
  },
  ElTooltip: {
    name: 'ElTooltip',
    props: ['content'],
    template: '<div class="el-tooltip-stub" :data-content="content"><slot /></div>'
  },
  ElDropdown: {
    name: 'ElDropdown',
    emits: ['command'],
    template: '<div class="el-dropdown-stub"><slot /><slot name="dropdown" /></div>'
  },
  ElDropdownMenu: {
    name: 'ElDropdownMenu',
    template: '<div class="el-dropdown-menu-stub"><slot /></div>'
  },
  ElDropdownItem: {
    name: 'ElDropdownItem',
    props: ['command'],
    template: '<button type="button" class="el-dropdown-item-stub" :data-command="command"><slot /></button>'
  }
}))

vi.mock('element-plus/theme-chalk/el-message.css', () => ({}))

import NL2SqlChat from '../NL2SqlChat.vue'

const mountChat = () => shallowMount(NL2SqlChat, {
  global: {
    stubs: {
      ElScrollbar: {
        template: '<div class="el-scrollbar-stub"><slot /></div>'
      },
      ElSelect: {
        props: ['modelValue', 'disabled'],
        emits: ['update:modelValue', 'change'],
        template: '<button type="button" class="el-select-stub" :class="$attrs.class" :disabled="disabled" @click="$emit(\'update:modelValue\', \'agent_sales\'); $emit(\'change\', \'agent_sales\')"><slot /></button>'
      },
      ElOption: {
        props: ['label', 'value'],
        template: '<span class="el-option-stub" :data-value="value">{{ label }}</span>'
      },
      ElTooltip: {
        props: ['content'],
        template: '<div class="el-tooltip-stub" :data-content="content"><slot /></div>'
      },
      ElDropdown: {
        emits: ['command'],
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
        props: {
          tool: {
            type: Object,
            default: () => ({})
          }
        },
        template: '<div class="tool-output-renderer-stub" :data-output-kind="tool?.output?.kind || \'\'" :data-skill-name="tool?._skillBootstrapName || \'\'" :data-tool-name="tool?.name || \'\'"></div>'
      }
    }
  }
})

const deferred = () => {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const makeTopicSummary = (topicId, title) => ({
  topic_id: topicId,
  title,
  agent_id: 'agent_default',
  agent: {
    agent_id: 'agent_default',
    name: '默认智能问数助手',
    description: '默认描述',
    is_default: true
  },
  chat_topic_id: `chat_${topicId}`,
  chat_conversation_id: `conversation_${topicId}`,
  current_task_id: '',
  current_task_status: '',
  message_count: 0,
  last_message_preview: '',
  created_at: '2026-03-10T02:00:00Z',
  updated_at: '2026-03-10T02:00:00Z'
})

const makeTopicDetail = (topicId, title) => ({
  topic_id: topicId,
  title,
  agent_id: 'agent_default',
  agent: {
    agent_id: 'agent_default',
    name: '默认智能问数助手',
    description: '默认描述',
    is_default: true
  },
  chat_topic_id: `chat_${topicId}`,
  chat_conversation_id: `conversation_${topicId}`,
  current_task_id: '',
  current_task_status: '',
  created_at: '2026-03-10T02:00:00Z',
  updated_at: '2026-03-10T02:00:00Z'
})

describe('NL2SqlChat', () => {
  beforeEach(() => {
    Object.values(apiMocks.topicApi).forEach((fn) => fn.mockReset())
    Object.values(apiMocks.taskApi).forEach((fn) => fn.mockReset())
    Object.values(apiMocks.messageQueueApi).forEach((fn) => fn.mockReset())
    Object.values(apiMocks.scheduleApi).forEach((fn) => fn.mockReset())
    Object.values(apiMocks.adminApi).forEach((fn) => fn.mockReset())
    Object.values(apiMocks.agentApi).forEach((fn) => fn.mockReset())
    apiMocks.health.mockReset()
    routerReplace.mockReset()
    routeState.query = {}
    routeState.params = {}
    routeState.path = '/intelligent-query'
    routeState.name = 'IntelligentQuery'

    apiMocks.adminApi.getSettings.mockResolvedValue({
      provider_id: 'anyrouter',
      model: 'claude-opus-4-6',
      providers: [
        {
          provider_id: 'anyrouter',
          display_name: 'AnyRouter',
          enabled: true,
          models: ['claude-opus-4-6'],
          default_model: 'claude-opus-4-6',
          supports_partial_messages: true
        }
      ]
    })
    apiMocks.agentApi.listAgents.mockResolvedValue([
      {
        agent_id: 'agent_default',
        name: '默认智能问数助手',
        description: '默认描述',
        is_default: true
      }
    ])
    apiMocks.topicApi.createTopic.mockResolvedValue(makeTopicSummary('topic-new', '新话题'))
    apiMocks.topicApi.listTopics.mockResolvedValue([
      makeTopicSummary('topic-1', '流式话题')
    ])
    apiMocks.topicApi.getTopic.mockImplementation(async (topicId) => makeTopicDetail(topicId, topicId === 'topic-1' ? '流式话题' : '新话题'))
    apiMocks.topicApi.updateTopic.mockImplementation(async (topicId, data) => makeTopicDetail(topicId, data?.title || '新话题'))
    apiMocks.topicApi.generateFollowupSuggestions.mockResolvedValue({
      topic_id: 'topic-1',
      message_id: 'a1',
      suggestions: [],
      source: 'empty'
    })
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'running',
          content: '',
          resume_after_seq: 12,
          blocks: [
            {
              block_id: 'think-1',
              type: 'thinking',
              status: 'streaming',
              text: '问题类型：趋势分析。指标：workflow_publish_record 的发布记录数，按 created_at 按天聚合，最近 30 天。平台核心表，直接进入 opendataworks / mysql 的只读查询快路径。'
            },
            {
              block_id: 'tool-1',
              type: 'tool',
              status: 'streaming',
              tool_id: 'tool-bash-1',
              tool_name: 'Bash',
              input: {
                command: 'python scripts/run_sql.py --question trend'
              },
              output: ''
            }
          ],
          created_at: '2026-03-10T02:00:00Z'
        }
      ]
    })
    apiMocks.taskApi.streamTaskEvents.mockResolvedValue(undefined)
    apiMocks.taskApi.getTask.mockResolvedValue({
      task_id: 'task-1',
      topic_id: 'topic-1',
      task_status: 'finished'
    })
  })

  it('formats sidebar timestamps in Asia/Shanghai for backend naive datetimes', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-03-10T04:00:00Z'))

    apiMocks.topicApi.listTopics.mockResolvedValue([
      {
        ...makeTopicSummary('topic-1', '流式话题'),
        created_at: '2026-03-10T10:00:00',
        updated_at: '2026-03-10T10:00:00'
      }
    ])
    apiMocks.topicApi.getTopic.mockResolvedValue({
      ...makeTopicDetail('topic-1', '流式话题'),
      created_at: '2026-03-10T10:00:00',
      updated_at: '2026-03-10T10:00:00'
    })
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 0,
      items: []
    })

    try {
      const wrapper = mountChat()

      await flushPromises()
      await flushPromises()

      expect(wrapper.find('.query-session-meta').text()).toBe('10:00')
    } finally {
      vi.useRealTimers()
    }
  })

  it('shows status dots in the session list driven by current_task_status', async () => {
    apiMocks.topicApi.listTopics.mockResolvedValue([
      { ...makeTopicSummary('topic-err', '失败的话题'), current_task_status: 'error' },
      { ...makeTopicSummary('topic-sus', '取消的话题'), current_task_status: 'suspended' },
      { ...makeTopicSummary('topic-ok', '完成的话题'), current_task_status: 'finished' }
    ])

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    const items = wrapper.findAll('.query-session-item')
    expect(items[0].find('.query-session-dot.is-error').exists()).toBe(true)
    expect(items[1].find('.query-session-dot.is-suspended').exists()).toBe(true)
    expect(items[2].find('.query-session-dot').exists()).toBe(false)
  })

  it('shows empty config state when admin settings has no enabled provider', async () => {
    apiMocks.adminApi.getSettings.mockResolvedValue({
      provider_id: '',
      model: '',
      providers: [
        {
          provider_id: 'openrouter',
          display_name: 'OpenRouter',
          enabled: false,
          models: [],
          default_model: 'anthropic/claude-sonnet-4.5',
          supports_partial_messages: true
        }
      ]
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    expect(wrapper.find('.query-config-empty').exists()).toBe(true)
    expect(wrapper.find('.query-model-selector-trigger.disabled').exists()).toBe(true)
    expect(wrapper.find('.query-model-selector-name').text()).toContain('选择模型')
  })

  it('shows the agent filter above sessions and current conversation agent in the top bar', async () => {
    apiMocks.agentApi.listAgents.mockResolvedValue([
      {
        agent_id: 'agent_default',
        name: '默认智能问数助手',
        description: '默认描述',
        is_default: true
      },
      {
        agent_id: 'agent_sales',
        name: '销售分析助手',
        description: '销售分析',
        is_default: false
      }
    ])
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 0,
      items: []
    })
    apiMocks.topicApi.deleteTopic.mockResolvedValue({})

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    expect(wrapper.find('.query-sidebar-head .el-select-stub.query-agent-select').exists()).toBe(true)
    expect(wrapper.find('.query-brand').exists()).toBe(false)
    expect(wrapper.find('.query-main-top-bar .el-select-stub.query-agent-select').exists()).toBe(false)
    expect(wrapper.find('.query-current-agent').exists()).toBe(true)
    expect(wrapper.find('.query-main-top-bar').text()).toContain('默认智能问数助手')

    apiMocks.topicApi.listTopics.mockClear()
    apiMocks.topicApi.listTopics.mockResolvedValue([])

    await wrapper.find('.query-sidebar-head .el-select-stub.query-agent-select').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(apiMocks.topicApi.listTopics).toHaveBeenCalledWith({ agent_id: 'agent_sales' })
  })

  it('filters sessions by agent without replacing the active conversation', async () => {
    apiMocks.agentApi.listAgents.mockResolvedValue([
      {
        agent_id: 'agent_default',
        name: '默认智能问数助手',
        description: '默认描述',
        is_default: true
      },
      {
        agent_id: 'agent_sales',
        name: '销售分析助手',
        description: '销售分析',
        is_default: false
      }
    ])
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 0,
      items: []
    })
    apiMocks.topicApi.deleteTopic.mockResolvedValue({})

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    apiMocks.topicApi.listTopics.mockClear()
    apiMocks.topicApi.listTopics.mockResolvedValue([])
    apiMocks.taskApi.deliverMessage.mockResolvedValue({
      accepted: true,
      topic_id: 'topic-1',
      task_id: 'task-after-filter',
      task_status: 'waiting',
      user_message_id: 'u-after-filter',
      assistant_message_id: 'a-after-filter'
    })

    await wrapper.find('.query-sidebar-head .el-select-stub.query-agent-select').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('.query-topic-title').text()).toBe('流式话题')
    expect(wrapper.find('.query-main-top-bar').text()).toContain('默认智能问数助手')
    expect(apiMocks.topicApi.createTopic).not.toHaveBeenCalled()
    expect(apiMocks.topicApi.deleteTopic).not.toHaveBeenCalled()
    expect(routerReplace).toHaveBeenCalledWith({
      path: '/intelligent-query',
      query: { agent_id: 'agent_sales' }
    })

    await wrapper.find('.query-textarea').setValue('继续当前对话')
    await wrapper.find('.query-btn-send').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalledWith(expect.objectContaining({
      topic_id: 'topic-1',
      agent_id: 'agent_default',
      content: '继续当前对话'
    }))
  })

  it('keeps streamed main text visible while tools are still running', async () => {
    apiMocks.taskApi.streamTaskEvents.mockImplementation(() => new Promise(() => {}))
    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('问题类型：趋势分析')
    expect(wrapper.text()).toContain('workflow_publish_record')
    expect(wrapper.text()).toContain('opendataworks / mysql 的只读查询快路径')
    expect(wrapper.text()).toContain('深度思考')
    expect(wrapper.find('.tool-output-renderer-stub').exists()).toBe(true)
    expect(wrapper.find('.query-process-panel').exists()).toBe(true)
    expect(wrapper.find('.query-process-content').attributes('style') || '').not.toContain('display: none')
    expect(wrapper.find('.query-process-summary-preview').exists()).toBe(false)
    expect(apiMocks.taskApi.streamTaskEvents).toHaveBeenCalledWith(
      'task-1',
      expect.objectContaining({ afterSeq: 12 })
    )
  })

  it('hides empty trace placeholders and only keeps meaningful tool blocks', async () => {
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'running',
          content: '',
          resume_after_seq: 12,
          blocks: [
            {
              block_id: 'tool-empty-read',
              type: 'tool',
              status: 'streaming',
              tool_id: 'tool-empty-read',
              tool_name: 'Read'
            },
            {
              block_id: 'tool-empty-bash',
              type: 'tool',
              status: 'streaming',
              tool_id: 'tool-empty-bash',
              tool_name: 'Bash'
            },
            {
              block_id: 'tool-real-read',
              type: 'tool',
              status: 'streaming',
              tool_id: 'tool-real-read',
              tool_name: 'Read',
              input: {
                file_path: '/tmp/reference/30-tool-recipes.md'
              },
              output: '## tool recipes'
            }
          ],
          created_at: '2026-03-10T02:00:00Z'
        }
      ]
    })
    apiMocks.taskApi.streamTaskEvents.mockImplementation(() => new Promise(() => {}))

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).not.toContain('读取文件：正在读取文件')
    expect(wrapper.text()).not.toContain('执行命令：正在执行命令')
    expect(wrapper.findAll('.tool-output-renderer-stub')).toHaveLength(1)
  })

  it('hides redundant skill bootstrap blocks once the concrete tool output is present', async () => {
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'finished',
          content: '最近 30 天累计发布 4 次。',
          blocks: [
            {
              block_id: 'tool-skill',
              type: 'tool',
              status: 'success',
              tool_id: 'tool-skill-1',
              tool_name: 'Skill',
              input: {
                skill: 'dataagent-nl2sql'
              },
              output: 'Launching skill: dataagent-nl2sql'
            },
            {
              block_id: 'tool-bash',
              type: 'tool',
              status: 'success',
              tool_id: 'tool-bash-1',
              tool_name: 'Bash',
              input: {
                command: 'python scripts/run_sql.py --question trend'
              },
              output: '2026-03-10,3\n2026-03-11,1'
            },
            {
              block_id: 'main-1',
              type: 'main_text',
              status: 'success',
              text: '最近 30 天累计发布 4 次。'
            }
          ],
          created_at: '2026-03-10T02:00:00Z'
        }
      ]
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()
    await wrapper.find('.query-process-summary').trigger('click')
    await flushPromises()

    const toolBlocks = wrapper.findAll('.tool-output-renderer-stub')
    expect(toolBlocks).toHaveLength(1)
    expect(toolBlocks[0].attributes('data-skill-name')).toBe('dataagent-nl2sql')
  })

  it('moves the cancel action into the composer while the active task is running', async () => {
    apiMocks.taskApi.streamTaskEvents.mockImplementation(() => new Promise(() => {}))
    apiMocks.taskApi.cancelTask.mockResolvedValue({
      task_id: 'task-1',
      task_status: 'suspended'
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    expect(wrapper.find('.query-process-cancel').exists()).toBe(false)

    const cancelButton = wrapper.find('.query-btn-cancel')
    expect(cancelButton.exists()).toBe(true)
    expect(cancelButton.attributes('aria-label')).toBe('取消当前任务')
    expect(cancelButton.attributes('title')).toBe('取消当前任务')

    await cancelButton.trigger('click')
    await flushPromises()

    expect(apiMocks.taskApi.cancelTask).toHaveBeenCalledWith('task-1')
  })

  it('shows a visible stop-answer button after a newly sent question enters the running state', async () => {
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 0,
      items: []
    })
    apiMocks.taskApi.deliverMessage.mockResolvedValue({
      accepted: true,
      topic_id: 'topic-1',
      task_id: 'task-new',
      task_status: 'waiting',
      user_message_id: 'u-new',
      assistant_message_id: 'a-new'
    })
    apiMocks.taskApi.streamTaskEvents.mockImplementation(() => new Promise(() => {}))

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    await wrapper.find('.query-textarea').setValue('最近 30 天工作流发布次数趋势')
    await wrapper.find('.query-btn-send').trigger('click')
    await flushPromises()
    await flushPromises()

    const cancelButton = wrapper.find('.query-btn-cancel')
    expect(cancelButton.exists()).toBe(true)
    expect(cancelButton.attributes('aria-label')).toBe('取消当前任务')
    expect(cancelButton.attributes('title')).toBe('取消当前任务')
  })

  it('submits with Enter while Shift Enter keeps the textarea draft', async () => {
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 0,
      items: []
    })
    apiMocks.taskApi.deliverMessage.mockResolvedValue({
      accepted: true,
      topic_id: 'topic-1',
      task_id: 'task-new',
      task_status: 'waiting',
      user_message_id: 'u-new',
      assistant_message_id: 'a-new'
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    const textarea = wrapper.find('.query-textarea')
    await textarea.setValue('最近 30 天工作流发布次数趋势')
    await textarea.trigger('keydown', { key: 'Enter', shiftKey: true })

    expect(apiMocks.taskApi.deliverMessage).not.toHaveBeenCalled()

    await textarea.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    await flushPromises()

    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalledTimes(1)
    expect(apiMocks.taskApi.deliverMessage.mock.calls[0][0].content).toBe('最近 30 天工作流发布次数趋势')
  })

  it('collapses the process panel after the final answer is ready', async () => {
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'finished',
          content: '最终结果：北区的下单量最高。',
          blocks: [
            {
              block_id: 'think-1',
              type: 'thinking',
              status: 'success',
              text: '先检查可用数据表，再决定聚合方式。'
            },
            {
              block_id: 'tool-1',
              type: 'tool',
              status: 'success',
              tool_id: 'tool-bash-1',
              tool_name: 'Bash',
              input: {
                command: 'python scripts/run_sql.py --question top10'
              },
              output: {
                kind: 'python_execution',
                summary: '查询执行完成',
                stdout: 'done'
              }
            },
            {
              block_id: 'main-1',
              type: 'main_text',
              status: 'success',
              text: '最终结果：北区的下单量最高。'
            }
          ],
          created_at: '2026-03-10T02:00:00Z'
        }
      ]
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('最终结果：北区的下单量最高。')
    expect(wrapper.find('.query-process-panel').exists()).toBe(true)
    expect(wrapper.find('.query-process-content').exists()).toBe(false)

    await wrapper.find('.query-process-summary').trigger('click')
    await flushPromises()

    expect(wrapper.find('.query-process-content').exists()).toBe(true)
  })

  it('shortens overly long shell previews in the collapsed process summary', async () => {
    const longCommand = 'python /opt/dataagent/scripts/run_sql.py --workspace /very/long/project/path/with/many/segments --database opendataworks --question "最近 30 天工作流发布次数趋势并按天输出明细以及累计值" --output-format json'

    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'finished',
          content: '最近 30 天共发布 12 次。',
          blocks: [
            {
              block_id: 'tool-1',
              type: 'tool',
              status: 'success',
              tool_id: 'tool-bash-1',
              tool_name: 'Bash',
              input: {
                command: longCommand
              },
              output: {
                kind: 'python_execution',
                summary: '执行完成',
                stdout: 'done'
              }
            },
            {
              block_id: 'main-1',
              type: 'main_text',
              status: 'success',
              text: '最近 30 天共发布 12 次。'
            }
          ],
          created_at: '2026-03-10T02:00:00Z'
        }
      ]
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    const preview = wrapper.find('.query-process-summary-preview')
    expect(preview.exists()).toBe(true)
    expect(preview.text()).toContain('python /opt/dataagent/scripts/run_sql.py')
    expect(preview.text()).toContain('...')
    expect(preview.text()).toContain('--output-format json')
    expect(preview.text().length).toBeLessThan(longCommand.length)
  })

  it('does not inject inline chart tools into the conclusion area', async () => {
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'finished',
          content: '最近 30 天共发布 4 次。',
          feedback: '',
          blocks: [
            {
              block_id: 'main-1',
              type: 'main_text',
              status: 'success',
              text: [
                '最近 30 天共发布 4 次。',
                '<chart_spec>',
                '{"kind":"chart_spec","chart_type":"line","title":"发布趋势","x_field":"stat_day","series":[{"name":"发布次数","field":"publish_cnt","type":"line"}],"dataset":[{"stat_day":"2026-03-10","publish_cnt":3}]}',
                '</chart_spec>'
              ].join('\n')
            }
          ],
          created_at: '2026-03-10T02:00:00Z'
        }
      ]
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('最近 30 天共发布 4 次。')
    expect(wrapper.text()).not.toContain('<chart_spec>')
    expect(wrapper.text()).not.toContain('发布趋势')
    expect(wrapper.find('.tool-output-renderer-stub').exists()).toBe(false)
  })

  it('shows context window usage from the latest assistant usage', async () => {
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'finished',
          content: '最近 30 天共发布 4 次。',
          usage: {
            input_tokens: 1000,
            output_tokens: 200
          },
          blocks: [
            {
              block_id: 'main-1',
              type: 'main_text',
              status: 'success',
              text: '最近 30 天共发布 4 次。'
            }
          ],
          created_at: '2026-03-10T02:00:00Z'
        }
      ]
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    const ring = wrapper.find('.query-context-ring-wrap')
    expect(ring.exists()).toBe(true)
    expect(ring.attributes('aria-label')).toContain('1,200 / 200,000')
    expect(ring.attributes('aria-label')).toContain('输入：1,000')
    expect(ring.attributes('aria-label')).toContain('输出：200')
  })

  it('keeps previous context window usage while a follow-up question is queued', async () => {
    const deliverPending = deferred()
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 2,
      items: [
        {
          message_id: 'u1',
          topic_id: 'topic-1',
          sender_type: 'user',
          content: '最近 30 天工作流发布次数趋势',
          created_at: '2026-03-10T02:00:00Z'
        },
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'finished',
          content: '最近 30 天共发布 4 次。',
          usage: {
            input_tokens: 1000,
            output_tokens: 200
          },
          blocks: [
            {
              block_id: 'main-1',
              type: 'main_text',
              status: 'success',
              text: '最近 30 天共发布 4 次。'
            }
          ],
          created_at: '2026-03-10T02:01:00Z'
        }
      ]
    })
    apiMocks.taskApi.deliverMessage.mockImplementation(() => deliverPending.promise)

    const wrapper = mountChat()

    try {
      await flushPromises()
      await flushPromises()

      expect(wrapper.find('.query-context-ring-wrap').attributes('aria-label')).toContain('1,200 / 200,000')

      await wrapper.find('.query-textarea').setValue('继续追问同比情况')
      await wrapper.find('.query-btn-send').trigger('click')
      await flushPromises()

      const ring = wrapper.find('.query-context-ring-wrap')
      expect(ring.attributes('aria-label')).toContain('1,200 / 200,000')
      expect(ring.attributes('aria-label')).toContain('输入：1,000')
      expect(ring.attributes('aria-label')).toContain('输出：200')
    } finally {
      deliverPending.resolve({
        accepted: true,
        topic_id: 'topic-1',
        task_id: 'task-new',
        task_status: 'waiting',
        user_message_id: 'u-new',
        assistant_message_id: 'a-new'
      })
      await flushPromises()
      wrapper.unmount()
    }
  })

  it('estimates context window usage while assistant text is streaming', async () => {
    const streamHold = deferred()
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'running',
          content: '',
          resume_after_seq: 12,
          blocks: [],
          created_at: '2026-03-10T02:00:00Z'
        }
      ]
    })
    apiMocks.taskApi.streamTaskEvents.mockImplementation(async (_taskId, options = {}) => {
      options.onEvent?.({
        seq_id: 13,
        type: 'message_start',
        message: { id: 'stream-a1', usage: { input_tokens: 1000 } }
      })
      options.onEvent?.({
        seq_id: 14,
        type: 'content_block_start',
        index: 0,
        content_block: { type: 'text' }
      })
      options.onEvent?.({
        seq_id: 15,
        type: 'content_block_delta',
        index: 0,
        delta: { type: 'text_delta', text: 'abcdefghijklmnopqrst' }
      })
      await streamHold.promise
    })

    const wrapper = mountChat()

    try {
      await flushPromises()
      await flushPromises()

      const ring = wrapper.find('.query-context-ring-wrap')
      expect(ring.attributes('aria-label')).toContain('1,005 / 200,000')
      expect(ring.attributes('aria-label')).toContain('输入：1,000')
      expect(ring.attributes('aria-label')).toContain('输出：约 5')
    } finally {
      streamHold.resolve()
      wrapper.unmount()
    }
  })

  it('shows message footer actions without an edit resend entry', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText }
    })
    Object.defineProperty(window, 'isSecureContext', {
      configurable: true,
      value: true
    })

    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 2,
      items: [
        {
          message_id: 'u1',
          topic_id: 'topic-1',
          sender_type: 'user',
          content: '最近 30 天工作流发布次数趋势',
          created_at: '2026-03-10T02:00:00Z'
        },
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'finished',
          content: '最近 30 天共发布 4 次。',
          blocks: [
            {
              block_id: 'main-1',
              type: 'main_text',
              status: 'success',
              text: '最近 30 天共发布 4 次。'
            }
          ],
          created_at: '2026-03-10T02:01:00Z'
        }
      ]
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    expect(wrapper.findAll('.query-message-footer').length).toBeGreaterThanOrEqual(2)
    expect(wrapper.find('.query-message-edit').exists()).toBe(false)

    await wrapper.find('.query-message-copy').trigger('click')
    expect(writeText).toHaveBeenCalledWith('最近 30 天工作流发布次数趋势')

    const likeButton = wrapper.find('.query-message-feedback-like')
    const dislikeButton = wrapper.find('.query-message-feedback-dislike')
    expect(likeButton.exists()).toBe(true)
    expect(dislikeButton.exists()).toBe(true)

    apiMocks.topicApi.updateMessageFeedback.mockResolvedValue({
      topic_id: 'topic-1',
      message_id: 'a1',
      sender_type: 'assistant',
      type: 'assistant',
      status: 'finished',
      content: '最近 30 天共发布 4 次。',
      feedback: 'like'
    })

    await likeButton.trigger('click')
    await flushPromises()
    expect(apiMocks.topicApi.updateMessageFeedback).toHaveBeenCalledWith('topic-1', 'a1', 'like')
    expect(wrapper.find('.query-message-feedback-like').classes()).toContain('active')

    apiMocks.topicApi.updateMessageFeedback.mockResolvedValue({
      topic_id: 'topic-1',
      message_id: 'a1',
      sender_type: 'assistant',
      type: 'assistant',
      status: 'finished',
      content: '最近 30 天共发布 4 次。',
      feedback: 'dislike'
    })

    await dislikeButton.trigger('click')
    await flushPromises()
    expect(apiMocks.topicApi.updateMessageFeedback).toHaveBeenLastCalledWith('topic-1', 'a1', 'dislike')
    expect(wrapper.find('.query-message-feedback-like').classes()).not.toContain('active')
    expect(wrapper.find('.query-message-feedback-dislike').classes()).toContain('active')
  })

  it('renders API follow-up suggestions for the latest successful assistant answer and submits one when clicked', async () => {
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'finished',
          content: 'SQL 查询如下：select count(*) from workflow_publish_record;',
          blocks: [
            {
              block_id: 'main-1',
              type: 'main_text',
              status: 'success',
              text: 'SQL 查询如下：select count(*) from workflow_publish_record;'
            }
          ],
          created_at: '2026-03-10T02:01:00Z'
        }
      ]
    })
    apiMocks.taskApi.deliverMessage.mockResolvedValue({
      accepted: true,
      topic_id: 'topic-1',
      task_id: 'task-new',
      task_status: 'waiting',
      user_message_id: 'u-new',
      assistant_message_id: 'a-new'
    })
    apiMocks.topicApi.generateFollowupSuggestions.mockResolvedValue({
      topic_id: 'topic-1',
      message_id: 'a1',
      suggestions: ['查看异常峰值对应的明细', '按发布操作类型拆解这个趋势'],
      source: 'generated'
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()
    await flushPromises()

    expect(apiMocks.topicApi.generateFollowupSuggestions).toHaveBeenCalledWith('topic-1', 'a1')
    const suggestions = wrapper.findAll('.query-followup-suggestion')
    expect(suggestions).toHaveLength(2)
    expect(suggestions[0].text()).toBe('查看异常峰值对应的明细')

    await suggestions[0].trigger('click')
    await flushPromises()
    await flushPromises()

    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalledWith(expect.objectContaining({
      topic_id: 'topic-1',
      content: suggestions[0].text()
    }))
  })

  it('normalizes structured API follow-up suggestions before rendering them', async () => {
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'finished',
          content: 'SQL 查询如下：select count(*) from workflow_publish_record;',
          blocks: [
            {
              block_id: 'main-1',
              type: 'main_text',
              status: 'success',
              text: 'SQL 查询如下：select count(*) from workflow_publish_record;'
            }
          ],
          created_at: '2026-03-10T02:01:00Z'
        }
      ]
    })
    apiMocks.topicApi.generateFollowupSuggestions.mockResolvedValue({
      topic_id: 'topic-1',
      message_id: 'a1',
      suggestions: [
        { question: '查看异常峰值对应的明细' },
        '{"question":"按发布操作类型拆解这个趋势"}',
        { suggestions: ['查看失败任务明细'] }
      ],
      source: 'generated'
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()
    await flushPromises()

    const suggestions = wrapper.findAll('.query-followup-suggestion')
    expect(suggestions.map((suggestion) => suggestion.text())).toEqual([
      '查看异常峰值对应的明细',
      '按发布操作类型拆解这个趋势',
      '查看失败任务明细'
    ])
    expect(wrapper.text()).not.toContain('[object Object]')
    expect(wrapper.text()).not.toContain('{"question"')
  })

  it('does not render follow-up suggestions when the API returns an empty list', async () => {
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'finished',
          content: '最近 30 天共发布 4 次。',
          blocks: [
            {
              block_id: 'main-1',
              type: 'main_text',
              status: 'success',
              text: '最近 30 天共发布 4 次。'
            }
          ],
          created_at: '2026-03-10T02:01:00Z'
        }
      ]
    })
    apiMocks.topicApi.generateFollowupSuggestions.mockResolvedValue({
      topic_id: 'topic-1',
      message_id: 'a1',
      suggestions: [],
      source: 'empty'
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()
    await flushPromises()

    expect(apiMocks.topicApi.generateFollowupSuggestions).toHaveBeenCalledWith('topic-1', 'a1')
    expect(wrapper.find('.query-followup-suggestion').exists()).toBe(false)
  })

  it('keeps tool chart_spec blocks in the process panel and not in the conclusion area', async () => {
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'finished',
          content: '最近 30 天共发布 4 次。',
          blocks: [
            {
              block_id: 'think-1',
              type: 'thinking',
              status: 'success',
              text: '生成趋势图。'
            },
            {
              block_id: 'tool-chart',
              type: 'tool',
              status: 'success',
              tool_id: 'tool-chart',
              tool_name: 'build_chart_spec.py',
              output: {
                kind: 'chart_spec',
                chart_type: 'line',
                title: '发布趋势',
                x_field: 'stat_day',
                series: [{ name: '发布次数', field: 'publish_cnt', type: 'line' }],
                dataset: [{ stat_day: '2026-03-10', publish_cnt: 3 }]
              }
            },
            {
              block_id: 'main-1',
              type: 'main_text',
              status: 'success',
              text: '最近 30 天共发布 4 次。'
            }
          ],
          created_at: '2026-03-10T02:00:00Z'
        }
      ]
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    // Chart is no longer promoted to a separate conclusion area.
    expect(wrapper.find('.query-final-chart').exists()).toBe(false)

    // The chart tool stays inside the 深度思考 process panel (visible once expanded);
    // ToolOutputRenderer renders its chart directly below the tool block.
    expect(wrapper.find('.query-process-panel').exists()).toBe(true)
    expect(wrapper.find('.query-process-content').exists()).toBe(false)
    await wrapper.find('.query-process-summary').trigger('click')
    expect(wrapper.find('.query-process-content .tool-output-renderer-stub[data-output-kind="chart_spec"]').exists()).toBe(true)
  })

  it('keeps a stdout-content-block chart tool in the process panel without a conclusion duplicate', async () => {
    const chartSpecJson = JSON.stringify({
      kind: 'chart_spec',
      chart_type: 'line',
      title: '发布趋势',
      x_field: 'stat_day',
      series: [{ name: '发布次数', field: 'publish_cnt', type: 'line' }],
      dataset: [{ stat_day: '2026-03-10', publish_cnt: 3 }]
    })

    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-1',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 1,
      items: [
        {
          message_id: 'a1',
          topic_id: 'topic-1',
          task_id: 'task-1',
          sender_type: 'assistant',
          type: 'assistant',
          status: 'finished',
          content: '最近 30 天共发布 4 次。',
          blocks: [
            {
              block_id: 'tool-chart',
              type: 'tool',
              status: 'success',
              tool_id: 'tool-chart',
              tool_name: 'Bash',
              // Build script result delivered as Claude content blocks, not a structured object.
              output: [{ type: 'text', text: `build ok\n${chartSpecJson}` }]
            },
            {
              block_id: 'main-1',
              type: 'main_text',
              status: 'success',
              text: '最近 30 天共发布 4 次。'
            }
          ],
          created_at: '2026-03-10T02:00:00Z'
        }
      ]
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    // No separate conclusion chart; the tool stays in the process panel where
    // ToolOutputRenderer detects the stdout chart_spec and draws it below the block.
    expect(wrapper.find('.query-final-chart').exists()).toBe(false)
    expect(wrapper.find('.query-process-panel').exists()).toBe(true)
    await wrapper.find('.query-process-summary').trigger('click')
    expect(wrapper.find('.query-process-content .tool-output-renderer-stub').exists()).toBe(true)
  })

  it('allows another topic to submit while the current topic is still awaiting acceptance', async () => {
    const firstPending = deferred()
    const secondPending = deferred()

    apiMocks.topicApi.listTopics.mockResolvedValue([
      makeTopicSummary('topic-1', '话题一'),
      makeTopicSummary('topic-2', '话题二')
    ])
    apiMocks.topicApi.getTopic.mockImplementation(async (topicId) => makeTopicDetail(topicId, topicId === 'topic-1' ? '话题一' : '话题二'))
    apiMocks.topicApi.getTopicMessages.mockImplementation(async (topicId) => ({
      topic_id: topicId,
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 0,
      items: []
    }))
    apiMocks.taskApi.deliverMessage
      .mockImplementationOnce(() => firstPending.promise)
      .mockImplementationOnce(() => secondPending.promise)
    apiMocks.taskApi.getTask.mockResolvedValue({
      task_id: 'task-finished',
      topic_id: 'topic-1',
      task_status: 'finished'
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    await wrapper.find('.query-textarea').setValue('第一个问题')
    await wrapper.find('.query-btn-send').trigger('click')
    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalledTimes(1)

    const topicButtons = wrapper.findAll('.query-session-item')
    await topicButtons[1].trigger('click')
    await flushPromises()

    await wrapper.find('.query-textarea').setValue('第二个问题')
    await wrapper.find('.query-btn-send').trigger('click')

    expect(apiMocks.taskApi.deliverMessage).toHaveBeenCalledTimes(2)
    const requestedTopicIds = apiMocks.taskApi.deliverMessage.mock.calls.map((call) => call[0].topic_id).sort()
    expect(requestedTopicIds).toEqual(['topic-1', 'topic-2'])
    expect(apiMocks.taskApi.deliverMessage.mock.calls[0][0].agent_id).toBe('agent_default')

    firstPending.resolve({
      accepted: true,
      topic_id: 'topic-1',
      task_id: 'task-1',
      task_status: 'waiting',
      user_message_id: 'u-1',
      assistant_message_id: 'a-1'
    })
    secondPending.resolve({
      accepted: true,
      topic_id: 'topic-2',
      task_id: 'task-2',
      task_status: 'waiting',
      user_message_id: 'u-2',
      assistant_message_id: 'a-2'
    })

    await flushPromises()
    await flushPromises()
  })

  it('persists the first real question as the title for a newly created placeholder topic', async () => {
    apiMocks.topicApi.listTopics.mockResolvedValue([])
    apiMocks.topicApi.getTopic.mockImplementation(async (topicId) => makeTopicDetail(topicId, '新话题'))
    apiMocks.topicApi.getTopicMessages.mockResolvedValue({
      topic_id: 'topic-new',
      page: 1,
      page_size: 500,
      order: 'asc',
      total: 0,
      items: []
    })
    apiMocks.taskApi.deliverMessage.mockResolvedValue({
      accepted: true,
      topic_id: 'topic-new',
      task_id: 'task-new',
      task_status: 'waiting',
      user_message_id: 'u-new',
      assistant_message_id: 'a-new'
    })

    const wrapper = mountChat()

    await flushPromises()
    await flushPromises()

    await wrapper.find('.query-textarea').setValue('这是一个新的会话标题')
    await wrapper.find('.query-btn-send').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(apiMocks.topicApi.updateTopic).toHaveBeenCalledWith(
      'topic-new',
      { title: '这是一个新的会话标题' }
    )
    expect(apiMocks.topicApi.createTopic).toHaveBeenCalledWith('这是一个新的会话标题', { agent_id: 'agent_default' })
    expect(wrapper.text()).toContain('这是一个新的会话标题')
  })
})
