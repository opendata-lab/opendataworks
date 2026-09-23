import { describe, it, expect, vi } from 'vitest'
import { createNl2SqlTransport } from '../nl2sqlTransport'
import { StreamInterrupted } from '../../../../packages/agent-conversation/src/transport/errors.js'

const TOPIC = 'topic-1'

const makeApi = (over = {}) => ({
  topicApi: {
    getTopicMessages: vi.fn(async () => ({ items: [], total: 0 })),
    getTopic: vi.fn(async () => ({ current_task_id: '', current_task_status: '' })),
    fileUrl: vi.fn((topicId, relPath) => `/api/v1/nl2sql/topics/${topicId}/files/${relPath}`),
    updateTopic: vi.fn(async () => ({})),
    updateMessageFeedback: vi.fn(async () => ({})),
    uploadFile: vi.fn(async (_topicId, file) => ({ rel_path: `uploads/${file.name}`, name: file.name, size: 7 })),
    fetchFileBlob: vi.fn(async () => new Blob(['x'])),
    ...over.topicApi
  },
  taskApi: {
    deliverMessage: vi.fn(async () => ({ task_id: 't-1' })),
    // The real client is callback-style and lives on taskApi, not eventApi.
    // Mocking the shape I wished for is how the previous version of this file
    // passed while calling a method that does not exist.
    streamSdkEvents: vi.fn(async () => {}),
    getTask: vi.fn(async () => ({ task_id: 't-1', task_status: 'finished' })),
    cancelTask: vi.fn(async () => ({ task_id: 't-1', task_status: 'suspended' })),
    submitPermissionDecision: vi.fn(async () => ({})),
    submitQuestionAnswer: vi.fn(async () => ({})),
    ...over.taskApi
  },
  eventApi: { recordEvents: vi.fn(async () => {}), ...over.eventApi },
  queryApi: { executeSql: vi.fn(async () => ({ columns: [], rows: [] })), ...over.queryApi }
})

const drain = async (stream) => {
  const out = []
  for await (const item of stream) out.push(item)
  return out
}

describe('history', () => {
  it('pages to exhaustion rather than truncating a long conversation', async () => {
    // Upstream defaults to 200 per page and caps at 500; a single request drops
    // everything past the first page without saying so.
    const pages = [
      { items: Array.from({ length: 500 }, (_, i) => ({ id: `a${i}` })), total: 1200 },
      { items: Array.from({ length: 500 }, (_, i) => ({ id: `b${i}` })), total: 1200 },
      { items: Array.from({ length: 200 }, (_, i) => ({ id: `c${i}` })), total: 1200 }
    ]
    const api = makeApi({
      topicApi: { getTopicMessages: vi.fn(async (_id, { page }) => pages[page - 1]) }
    })

    const { messages } = await createNl2SqlTransport(api, TOPIC).loadConversation()

    expect(messages).toHaveLength(1200)
    expect(api.topicApi.getTopicMessages).toHaveBeenCalledTimes(3)
  })

  it('stops when a page comes back empty', async () => {
    const api = makeApi({
      topicApi: { getTopicMessages: vi.fn(async () => ({ items: [], total: 99 })) }
    })

    const { messages } = await createNl2SqlTransport(api, TOPIC).loadConversation()

    expect(messages).toEqual([])
    expect(api.topicApi.getTopicMessages).toHaveBeenCalledTimes(1)
  })

  it('reports an in-flight task as the active run', async () => {
    const api = makeApi({
      topicApi: {
        getTopic: vi.fn(async () => ({ current_task_id: 't-9', current_task_status: 'running' }))
      }
    })

    const { run } = await createNl2SqlTransport(api, TOPIC).loadConversation()

    expect(run).toMatchObject({ taskId: 't-9', status: 'running' })
  })
})

describe('status translation', () => {
  it('turns a cancelled task into the SDK vocabulary', async () => {
    // DataAgent says `suspended`; the SDK says `cancelled`. Leaving the raw
    // value through would make the client wait forever on a status it has no
    // terminal rule for.
    const run = await createNl2SqlTransport(makeApi(), TOPIC).cancelRun({ taskId: 't-1' })

    expect(run.status).toBe('cancelled')
  })

  it('treats a freshly delivered task as queued', async () => {
    const run = await createNl2SqlTransport(makeApi(), TOPIC).sendMessage({ content: 'hi' })

    expect(run).toMatchObject({ taskId: 't-1', status: 'queued' })
  })

  it('carries the metadata the host attached', async () => {
    const run = await createNl2SqlTransport(makeApi(), TOPIC)
      .sendMessage({ content: 'hi', metadata: { mode: 'model' } })

    expect(run.metadata).toEqual({ mode: 'model' })
  })
})

describe('the event stream', () => {
  it('synthesises a terminal item once the task is genuinely done', async () => {
    const api = makeApi({
      taskApi: {
        streamSdkEvents: async (_taskId, { onRecord }) => {
          onRecord({ seq_id: 1, kind: 'text' })
          onRecord({ seq_id: 2, kind: 'text' })
        },
      },
    })

    const items = await drain(
      createNl2SqlTransport(api, TOPIC).streamEvents({ taskId: 't-1', afterId: 0 })
    )

    expect(items.map((i) => i.type)).toEqual(['event', 'event', 'terminal'])
    expect(items[2].run.status).toBe('finished')
  })

  it('treats an early upstream EOF as an interruption, not a completion', async () => {
    // This is the whole reason the adapter checks status after EOF. The byte
    // stream looks identical whether the run finished or the connection died;
    // calling the second case "finished" would have the host refresh its data
    // while the agent is still working.
    const api = makeApi({
      taskApi: {
        streamSdkEvents: async (_taskId, { onRecord }) => onRecord({ seq_id: 1 }),
        getTask: vi.fn(async () => ({ task_id: 't-1', task_status: 'running' })),
      },
    })

    await expect(
      drain(createNl2SqlTransport(api, TOPIC).streamEvents({ taskId: 't-1', afterId: 0 }))
    ).rejects.toBeInstanceOf(StreamInterrupted)
  })

  it('maps an errored task to a failed terminal item', async () => {
    const api = makeApi({
      taskApi: { getTask: vi.fn(async () => ({ task_id: 't-1', task_status: 'error' })) }
    })

    const items = await drain(
      createNl2SqlTransport(api, TOPIC).streamEvents({ taskId: 't-1', afterId: 0 })
    )

    expect(items.at(-1)).toMatchObject({ type: 'terminal', run: { status: 'failed' } })
  })

  it('stays quiet when the caller aborts', async () => {
    const controller = new AbortController()
    controller.abort()
    const api = makeApi()

    const items = await drain(
      createNl2SqlTransport(api, TOPIC).streamEvents({
        taskId: 't-1', afterId: 0, signal: controller.signal
      })
    )

    expect(items).toEqual([])
    expect(api.taskApi.getTask).not.toHaveBeenCalled()
  })
})

describe('optional capabilities', () => {
  it('offers all of them, because the widget talks to DataAgent directly', () => {
    const transport = createNl2SqlTransport(makeApi(), TOPIC)

    // A host that proxies through its own backend exposes only what it chose
    // to implement; this one has the whole API available.
    for (const method of ['executeSql', 'setPermissionMode', 'submitFeedback']) {
      expect(typeof transport[method]).toBe('function')
    }
  })

  it('scopes SQL execution to the conversation', async () => {
    const api = makeApi()

    await createNl2SqlTransport(api, TOPIC).executeSql({ sql: 'SELECT 1', database: 'demo' })

    expect(api.queryApi.executeSql).toHaveBeenCalledWith(
      expect.objectContaining({ sql: 'SELECT 1', topicId: TOPIC })
    )
  })
})

describe('interactions', () => {
  it('routes a permission reply to the permission endpoint', async () => {
    const api = makeApi()

    await createNl2SqlTransport(api, TOPIC).submitInteraction({
      taskId: 't-1', kind: 'permission', requestId: 'r-1', payload: { decision: 'allow' }
    })

    expect(api.taskApi.submitPermissionDecision).toHaveBeenCalledWith('t-1', 'r-1', 'allow', '')
    expect(api.taskApi.submitQuestionAnswer).not.toHaveBeenCalled()
  })

  it('routes a question reply to the question endpoint', async () => {
    const api = makeApi()

    await createNl2SqlTransport(api, TOPIC).submitInteraction({
      taskId: 't-1', kind: 'question', requestId: 'r-2', payload: { answers: ['a'] }
    })

    expect(api.taskApi.submitQuestionAnswer).toHaveBeenCalledWith('t-1', 'r-2', ['a'])
  })
})

describe('it talks to the client that actually exists', () => {
  it('only calls methods the real client provides', async () => {
    // The previous version of this adapter called
    // `api.eventApi.streamSdkEvents` — a method that does not exist. Every
    // test passed because they mocked it into being. A Proxy over the real
    // client's own key set is what would have caught that.
    const { createNl2SqlApiClient } = await import('@/api/nl2sql')
    const real = createNl2SqlApiClient({ baseURL: '' })

    const guard = (target, path) =>
      new Proxy(target, {
        get(obj, key) {
          if (typeof key !== 'string' || key in obj) {
            const value = Reflect.get(obj, key)
            return value && typeof value === 'object' && !Array.isArray(value)
              ? guard(value, `${path}.${key}`)
              : typeof value === 'function'
                ? () => Promise.resolve({})
                : value
          }
          throw new Error(`${path}.${String(key)} does not exist on the real client`)
        },
      })

    const transport = createNl2SqlTransport(guard(real, 'api'), TOPIC)

    // Touch every method the SDK contract requires. A missing namespace or a
    // renamed method fails here instead of at the first real subscription.
    await transport.loadConversation()
    await transport.sendMessage({ content: 'hi' })
    await transport.cancelRun({ taskId: 't-1' })
    await transport.submitInteraction({
      taskId: 't-1', kind: 'permission', requestId: 'r', payload: {},
    })
    await transport.submitInteraction({
      taskId: 't-1', kind: 'question', requestId: 'r', payload: {},
    })
    transport.fileUrl('output/x.json')
    await transport.executeSql({ sql: 'SELECT 1' })
    await transport.setPermissionMode('default')
    await transport.submitFeedback({ messageId: 'm-1', feedback: 'up' })
    await transport.uploadFiles([new File(['x'], 'a.csv')])
    await transport.readFile('output/a.csv')

    expect(typeof real.taskApi.streamSdkEvents).toBe('function')
    expect(real.eventApi.streamSdkEvents).toBeUndefined()
  }, 15000)

  it('takes feedback in the shape the SDK sends it', async () => {
    // The guard above only proves the namespaces exist; it calls each method
    // and ignores the arguments. That let the signature drift away from the
    // SDK's call — the rating buttons rendered, and every click sent an object
    // where a message id belonged.
    const api = makeApi()
    const transport = createNl2SqlTransport(api, TOPIC)

    const result = await transport.submitFeedback({ messageId: 'm-1', feedback: 'up' })

    expect(api.topicApi.updateMessageFeedback).toHaveBeenCalledWith(TOPIC, 'm-1', 'up')
    expect(result).toEqual({ feedback: 'up' })
  })

  it('carries the fields a reloaded conversation needs', async () => {
    // Each of these was dropped on the way through, and each loss is only
    // visible after a reload: no error card on a failed run, no rating
    // highlight, and a running turn that replays from the beginning.
    const api = makeApi({
      topicApi: {
        getTopicMessages: vi.fn(async () => ({
          items: [{
            message_id: 'a1',
            sender_type: 'assistant',
            content: '',
            status: 'failed',
            error: { message: '模型调用超时' },
            feedback: 'like',
            resume_after_seq: 12,
            task_id: 'task-1',
            attachments: [{ name: '明细.csv', rel_path: 'output/rows.csv', size: 2048 }],
          }],
          total: 1,
        })),
      },
    })

    const { messages } = await createNl2SqlTransport(api, TOPIC).loadConversation()

    expect(messages[0]).toMatchObject({
      status: 'failed',
      feedback: 'like',
      resumeAfterSeq: 12,
    })
    expect(messages[0].error).toEqual({ message: '模型调用超时' })
    expect(messages[0].attachments[0]).toMatchObject({ relPath: 'output/rows.csv', size: 2048 })
  })

  it('uploads through the topic files endpoint and returns workspace references', async () => {
    const api = makeApi()
    const transport = createNl2SqlTransport(api, TOPIC)

    const uploaded = await transport.uploadFiles([new File(['x'], '订单.csv', { type: 'text/csv' })])

    expect(api.topicApi.uploadFile).toHaveBeenCalledWith(TOPIC, expect.any(File))
    expect(uploaded).toEqual([
      { name: '订单.csv', relPath: 'uploads/订单.csv', mediaType: 'text/csv', size: 7 },
    ])
  })

  it('reads a file through fetch so the runtime headers apply', async () => {
    // A bare URL cannot carry the site and access-key headers, which is why
    // the chat surfaces already download via Blob rather than an <a href>.
    const api = makeApi()
    const transport = createNl2SqlTransport(api, TOPIC)

    const blob = await transport.readFile('output/report.html')

    expect(api.topicApi.fetchFileBlob).toHaveBeenCalledWith(TOPIC, 'output/report.html')
    expect(blob).toBeInstanceOf(Blob)
  })
})
