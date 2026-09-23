import { describe, it, expect, vi } from 'vitest'
import { ref, shallowRef, nextTick } from 'vue'
import { useConversation } from '../core/useConversation.js'
import { StreamInterrupted } from '../transport/errors.js'
import { toRunStatus, ACTIVE_RUN_STATUSES, TERMINAL_RUN_STATUSES } from '../core/runStatus.js'

const snapshot = (messages = [], run = null) => ({ messages, run })
const runRef = (over = {}) => ({ taskId: 't-1', status: 'running', detail: '', ...over })

/** A transport whose stream yields whatever the test queues. */
const makeTransport = (over = {}) => ({
  loadConversation: vi.fn(async () => snapshot()),
  sendMessage: vi.fn(async () => runRef({ status: 'queued' })),
  cancelRun: vi.fn(async () => runRef({ status: 'cancelled' })),
  submitInteraction: vi.fn(async () => {}),
  fileUrl: (p) => `/files/${p}`,
  streamEvents: vi.fn(async function* () {}),
  ...over
})

const setup = (transportImpl = makeTransport()) => {
  const transport = shallowRef(transportImpl)
  const generation = ref(0)
  const events = []
  const api = useConversation({ transport, generation, emit: (e) => events.push(e) })
  return { api, transport, generation, events, t: transportImpl }
}

const named = (events, name) => events.filter((e) => e.name === name)

/**
 * subscribe() is started but not awaited by send() — the caller should not be
 * blocked for the length of a run. Draining an async generator therefore takes
 * more than one tick.
 */
const settle = async (ticks = 12) => {
  for (let i = 0; i < ticks; i += 1) await Promise.resolve()
  await nextTick()
}

describe('status vocabulary', () => {
  it.each([
    ['waiting', 'queued'],
    ['running', 'running'],
    ['waiting_input', 'waiting_input'],
    ['waiting_permission', 'waiting_permission'],
    ['finished', 'finished'],
    ['error', 'failed'],
    ['suspended', 'cancelled']
  ])('maps %s to %s', (task, expected) => {
    expect(toRunStatus(task)).toBe(expected)
  })

  it('maps an unknown status to failed rather than leaving it active', () => {
    expect(toRunStatus('who-knows')).toBe('failed')
    expect(toRunStatus(undefined)).toBe('failed')
  })

  it('counts parked runs as active', () => {
    // A run waiting on input is not done. Hosts gate editing on this, so
    // classifying it as terminal would unlock the draft mid-run.
    expect(ACTIVE_RUN_STATUSES.has('waiting_input')).toBe(true)
    expect(ACTIVE_RUN_STATUSES.has('waiting_permission')).toBe(true)
    expect([...ACTIVE_RUN_STATUSES].some((s) => TERMINAL_RUN_STATUSES.has(s))).toBe(false)
  })
})

describe('loading', () => {
  it('publishes messages and announces readiness', async () => {
    const { api, events } = setup(makeTransport({
      loadConversation: async () => snapshot([{ id: 'm1', role: 'user', content: 'hi' }])
    }))

    await api.load()

    expect(api.messages.value).toHaveLength(1)
    expect(named(events, 'ready')).toHaveLength(1)
  })

  it('restores run metadata so a reloaded page still knows what the run was for', async () => {
    // The host uses metadata.mode to decide whether to refresh its own panels.
    // In-memory state does not survive the reload a long run outlives, so the
    // snapshot has to carry it.
    const { api, events } = setup(makeTransport({
      loadConversation: async () => snapshot([], runRef({ status: 'finished', metadata: { mode: 'model' } })),
      streamEvents: async function* () {}
    }))

    await api.load()

    expect(named(events, 'complete')[0].detail.metadata).toEqual({ mode: 'model' })
  })

  it('reports a failure instead of throwing', async () => {
    const { api, events } = setup(makeTransport({
      loadConversation: async () => { throw Object.assign(new Error('boom'), { code: 'conversation_unavailable', hint: 'fix it' }) }
    }))

    await api.load()

    expect(named(events, 'error')[0].detail).toMatchObject({ message: 'boom', hint: 'fix it' })
  })
})

describe('sending', () => {
  it('uses the draft when no content is given, and clears it', async () => {
    const { api, t } = setup()
    api.draft.value = '  开始建模  '

    await api.send()

    expect(t.sendMessage).toHaveBeenCalledWith({ content: '开始建模', metadata: undefined })
    expect(api.draft.value).toBe('')
  })

  it('keeps the draft when asked to', async () => {
    const { api } = setup()
    api.draft.value = 'keep me'

    await api.send(undefined, { clearDraft: false })

    expect(api.draft.value).toBe('keep me')
  })

  it('timestamps the local user turn so its action row can show a time immediately', async () => {
    const { api } = setup()

    await api.send('现在几点')

    const user = api.messages.value.find((message) => message.role === 'user')
    expect(user.createdAt).toEqual(expect.any(String))
    expect(Number.isNaN(Date.parse(user.createdAt))).toBe(false)
  })

  it('does nothing when there is nothing to send', async () => {
    const { api, t } = setup()
    await api.send('   ')
    expect(t.sendMessage).not.toHaveBeenCalled()
  })

  it('refuses to start a second run while one is active', async () => {
    const { api, t } = setup(makeTransport({
      loadConversation: async () => snapshot([], runRef({ status: 'running' })),
      streamEvents: async function* () {}
    }))
    await api.load()

    await api.send('again')

    expect(t.sendMessage).not.toHaveBeenCalled()
  })

  it('hands the metadata back when the run completes', async () => {
    const { api, events } = setup(makeTransport({
      sendMessage: async () => runRef({ taskId: 't-9', status: 'queued' }),
      streamEvents: async function* () {
        yield { type: 'terminal', run: runRef({ taskId: 't-9', status: 'finished' }) }
      }
    }))

    await api.send('go', { metadata: { mode: 'model' } })
    await settle()

    expect(named(events, 'complete')[0].detail).toMatchObject({
      taskId: 't-9',
      status: 'finished',
      metadata: { mode: 'model' }
    })
  })
})

describe('streaming', () => {
  it('forwards events and completes on the terminal item', async () => {
    const { api, events } = setup(makeTransport({
      streamEvents: async function* () {
        yield { type: 'event', seqId: 1, event: { kind: 'text' } }
        yield { type: 'event', seqId: 2, event: { kind: 'text' } }
        yield { type: 'terminal', run: runRef({ status: 'finished' }) }
      }
    }))

    await api.send('go')
    await settle()

    expect(named(events, 'agent-event')).toHaveLength(2)
    expect(named(events, 'complete')).toHaveLength(1)
  })

  it('reports an interrupted stream without completing the run', async () => {
    // An interruption must never look like completion: the run is still going.
    const { api, events } = setup(makeTransport({
      streamEvents: async function* () {
        yield { type: 'event', seqId: 1, event: {} }
        throw new StreamInterrupted()
      }
    }))

    await api.send('go')
    await settle()

    expect(named(events, 'error')[0].detail.code).toBe('stream_interrupted')
    expect(named(events, 'complete')).toHaveLength(0)
    api.stopStream()
  })
})

describe('conversation switching', () => {
  it('drops results that arrive after the conversation changed', async () => {
    let release
    const gate = new Promise((resolve) => { release = resolve })
    const { api, generation } = setup(makeTransport({
      loadConversation: async () => {
        await gate
        return snapshot([{ id: 'stale', role: 'user', content: 'from the old one' }])
      }
    }))

    const pending = api.load()
    generation.value += 1   // the host switched conversations mid-flight
    release()
    await pending

    expect(api.messages.value).toEqual([])
  })

  it('clears everything on reset', async () => {
    const { api } = setup(makeTransport({
      loadConversation: async () => snapshot([{ id: 'm1', role: 'user', content: 'hi' }], runRef())
    }))
    await api.load()

    api.reset()

    expect(api.messages.value).toEqual([])
    expect(api.run.value).toBeNull()
  })
})

describe('cancelling', () => {
  it('stops the stream and records the terminal status', async () => {
    const { api, events } = setup(makeTransport({
      loadConversation: async () => snapshot([], runRef({ status: 'running' })),
      streamEvents: async function* () { await new Promise(() => {}) }
    }))
    await api.load()

    await api.cancel()

    expect(api.run.value.status).toBe('cancelled')
    expect(named(events, 'complete')).toHaveLength(1)
  })
})

describe('retrying', () => {
  it('forwards current settings on retry', async () => {
    let settings = { provider_id: 'deepseek', model: 'v4-pro' }
    const transport = makeTransport({
      sendMessage: vi.fn(async () => runRef({ taskId: 't-1' })),
    })
    const generation = ref(0)
    const api = useConversation({
      transport: shallowRef(transport),
      generation,
      emit: () => {},
      settings: () => settings,
    })

    await api.send('原问题')
    expect(transport.sendMessage).toHaveBeenCalledWith(expect.objectContaining({
      content: '原问题',
      settings: { provider_id: 'deepseek', model: 'v4-pro' }
    }))

    // Turn failed / finished, user changes settings
    settings = { provider_id: 'openai', model: 'gpt-4o' }
    api.closeTurn?.('t-1', 'failed', 'error')
    api.run.value = { taskId: 't-1', status: 'failed', detail: '' }

    const assistant = api.messages.value.find((m) => m.role === 'assistant')
    await api.retry(assistant)

    expect(transport.sendMessage).toHaveBeenCalledTimes(2)
    expect(transport.sendMessage.mock.calls[1][0]).toMatchObject({
      content: '原问题',
      settings: { provider_id: 'openai', model: 'gpt-4o' }
    })
  })
})

describe('interaction failure recovery', () => {
  it('marks _submitFailed on blocks when submitInteraction rejects', async () => {
    const transport = makeTransport({
      submitInteraction: vi.fn(async () => {
        throw new Error('网络超时')
      })
    })
    const generation = ref(0)
    const events = []
    const api = useConversation({
      transport: shallowRef(transport),
      generation,
      emit: (e) => events.push(e)
    })

    api.messages.value = [
      {
        id: 'a-1',
        role: 'assistant',
        taskId: 't-1',
        _v2state: {
          blocks: [
            {
              type: 'permission_request',
              requestId: 'req-perm-1',
              decision: 'pending',
              summary: '执行 SQL 操作'
            },
            {
              type: 'question_request',
              requestId: 'req-q-1',
              answered: false
            }
          ]
        }
      }
    ]

    await api.submitInteraction({
      taskId: 't-1',
      kind: 'permission',
      requestId: 'req-perm-1',
      payload: { decision: 'allow' }
    })

    const permBlock = api.messages.value[0]._v2state.blocks[0]
    expect(permBlock._submitFailed).toBeTruthy()
    expect(permBlock.summary).toContain('[提交失败，请重试]')
    expect(events.some((e) => e.name === 'error')).toBe(true)

    await api.submitInteraction({
      taskId: 't-1',
      kind: 'question',
      requestId: 'req-q-1',
      payload: { answers: [] }
    })

    const qBlock = api.messages.value[0]._v2state.blocks[1]
    expect(qBlock._submitFailed).toBeTruthy()
  })
})
