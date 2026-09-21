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
    ...over.topicApi
  },
  taskApi: {
    deliverMessage: vi.fn(async () => ({ task_id: 't-1' })),
    getTask: vi.fn(async () => ({ task_id: 't-1', task_status: 'finished' })),
    cancelTask: vi.fn(async () => ({ task_id: 't-1', task_status: 'suspended' })),
    submitPermissionDecision: vi.fn(async () => ({})),
    submitQuestionAnswer: vi.fn(async () => ({})),
    ...over.taskApi
  },
  eventApi: {
    streamSdkEvents: vi.fn(async function* () {}),
    ...over.eventApi
  },
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
      eventApi: {
        streamSdkEvents: async function* () {
          yield { seq_id: 1, kind: 'text' }
          yield { seq_id: 2, kind: 'text' }
        }
      }
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
      eventApi: { streamSdkEvents: async function* () { yield { seq_id: 1 } } },
      taskApi: { getTask: vi.fn(async () => ({ task_id: 't-1', task_status: 'running' })) }
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
