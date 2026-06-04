import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useNl2SqlChat } from '../useNl2SqlChat'

function makeApi(overrides = {}) {
  return {
    runtimeApi: {
      getConfig: vi.fn().mockResolvedValue({
        default_provider_id: 'p1',
        default_model: 'm1',
        providers: [{ provider_id: 'p1', display_name: 'P1', models: ['m1'], default_model: 'm1', enabled: true }],
      }),
    },
    topicApi: {
      createTopic: vi.fn().mockResolvedValue({ topic_id: 'topic-new', title: '新话题' }),
      listTopics: vi.fn().mockResolvedValue([]),
      getTopicMessages: vi.fn().mockResolvedValue({ items: [] }),
      deleteTopic: vi.fn().mockResolvedValue({ status: 'ok' }),
    },
    taskApi: {
      deliverMessage: vi.fn().mockResolvedValue({ task_id: 'task-1' }),
      streamSdkEvents: vi.fn().mockResolvedValue(),
      getTask: vi.fn().mockResolvedValue({ task_status: 'finished' }),
      cancelTask: vi.fn().mockResolvedValue({ status: 'ok' }),
    },
    ...overrides,
  }
}

async function ready(api, options = {}) {
  const chat = useNl2SqlChat({ api, ...options })
  await chat.loadConfig()
  await flushPromises()
  return chat
}

describe('useNl2SqlChat engine', () => {
  beforeEach(() => vi.clearAllMocks())
  afterEach(() => vi.restoreAllMocks())

  it('sends: creates a topic, delivers, streams, and marks success', async () => {
    const api = makeApi()
    api.taskApi.streamSdkEvents.mockImplementation(async (_taskId, opts) => {
      opts.onRecord({ record_type: 'stream', data: { type: 'message_start', usage: {} } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'ok' } } })
      opts.onRecord({ record_type: 'stream', data: { type: 'content_block_stop', index: 0 } })
      opts.onRecord({ record_type: 'done', data: {} })
    })
    const chat = await ready(api)

    chat.inputText.value = '你好'
    await chat.send()
    await flushPromises()

    expect(api.topicApi.createTopic).toHaveBeenCalledWith('你好', { agent_id: undefined })
    expect(api.taskApi.deliverMessage).toHaveBeenCalledWith(expect.objectContaining({ topic_id: 'topic-new', content: '你好' }))
    const assistant = chat.messages.value.find((m) => m.role === 'assistant')
    expect(assistant.status).toBe('success')
    expect(chat.isBusy.value).toBe(false)
    expect(chat.topicId.value).toBe('topic-new')
  })

  it('newConversation while streaming detaches without cancelling the backend task', async () => {
    const api = makeApi()
    let resolveStream
    api.taskApi.streamSdkEvents.mockImplementation(() => new Promise((resolve) => { resolveStream = resolve }))
    const chat = await ready(api)

    chat.inputText.value = '你好'
    const sendPromise = chat.send()
    await flushPromises()
    expect(chat.isBusy.value).toBe(true)

    await chat.newConversation()
    expect(chat.isBusy.value).toBe(false)
    expect(chat.topicId.value).toBe('')
    expect(chat.messages.value).toEqual([])
    expect(api.taskApi.cancelTask).not.toHaveBeenCalled()

    resolveStream()
    await sendPromise
  })

  it('does not attach an old task after detaching during delivery and sending a new question', async () => {
    const api = makeApi()
    let resolveFirstDeliver
    api.topicApi.createTopic
      .mockResolvedValueOnce({ topic_id: 'topic-1', title: 'first' })
      .mockResolvedValueOnce({ topic_id: 'topic-2', title: 'second' })
    api.taskApi.deliverMessage
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirstDeliver = resolve }))
      .mockResolvedValueOnce({ task_id: 'task-2' })
    const chat = await ready(api)

    chat.inputText.value = 'first'
    const firstSend = chat.send()
    await flushPromises()

    await chat.newConversation()
    chat.inputText.value = 'second'
    await chat.send()

    resolveFirstDeliver({ task_id: 'task-1' })
    await firstSend
    await flushPromises()

    expect(api.taskApi.streamSdkEvents).toHaveBeenCalledTimes(1)
    expect(api.taskApi.streamSdkEvents).toHaveBeenCalledWith('task-2', expect.anything())
  })

  it('allows sending with text even when provider/model are not selected', async () => {
    const api = makeApi()
    const chat = await ready(api)
    chat.selectedProvider.value = ''
    chat.selectedModel.value = ''
    chat.inputText.value = 'use backend default'

    expect(chat.canSend.value).toBe(true)
    await chat.send()

    expect(api.taskApi.deliverMessage).toHaveBeenCalledWith(expect.objectContaining({
      content: 'use backend default',
      provider_id: undefined,
      model: undefined,
    }))
  })

  it('cancel aborts locally and cancels the backend task (suspended)', async () => {
    const api = makeApi()
    // Honor the abort signal so the in-flight send unwinds like a real fetch.
    api.taskApi.streamSdkEvents.mockImplementation((_taskId, opts) => new Promise((_resolve, reject) => {
      opts.signal?.addEventListener('abort', () => {
        const err = new Error('aborted')
        err.name = 'AbortError'
        reject(err)
      })
    }))
    const chat = await ready(api)
    chat.topics.value = [{ topic_id: 'topic-new', current_task_status: 'waiting' }]

    chat.inputText.value = '你好'
    const sendPromise = chat.send()
    await flushPromises()

    await chat.cancel()
    await flushPromises()
    expect(api.taskApi.cancelTask).toHaveBeenCalledWith('task-1')
    expect(chat.topics.value[0].current_task_status).toBe('suspended')
    expect(chat.messages.value.find((m) => m.role === 'assistant')?.status).toBe('cancelled')
    expect(chat.isBusy.value).toBe(false)

    await sendPromise
  })
})
