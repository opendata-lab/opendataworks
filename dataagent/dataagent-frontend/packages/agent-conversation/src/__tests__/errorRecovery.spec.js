import { describe, it, expect, vi, beforeAll } from 'vitest'
import { nextTick } from 'vue'
import { defineAgentConversation, DEFAULT_TAG } from '../element.js'

beforeAll(() => {
  defineAgentConversation()
})

const settle = async (ticks = 12) => {
  for (let i = 0; i < ticks; i += 1) await Promise.resolve()
  await nextTick()
}

const makeTransport = (over = {}) => ({
  loadConversation: vi.fn(async () => ({ messages: [], run: null })),
  sendMessage: vi.fn(async () => ({ taskId: 't-1', status: 'running', detail: '' })),
  cancelRun: vi.fn(async () => ({ taskId: 't-1', status: 'cancelled', detail: '' })),
  submitInteraction: vi.fn(async () => {}),
  fileUrl: (p) => `/files/${p}`,
  streamEvents: async function* () {},
  ...over
})

const mount = (assign = {}) => {
  const el = document.createElement(DEFAULT_TAG)
  Object.assign(el, assign)
  document.body.appendChild(el)
  return el
}

describe('a run that fails', () => {
  it('surfaces the failure and hands the conversation back to the user', async () => {
    // The stuck state this guards against: the stream dies, the assistant turn
    // is marked failed, but `run` is left on its last active status. isActive
    // stays true forever, so the composer keeps showing "停止" and refuses both
    // a new message and a retry — an error nobody can act on.
    const transport = makeTransport({
      streamEvents: async function* () {
        throw new Error('后端连接被重置')
      }
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    await el.sendMessage('开始建模')
    await settle()

    expect(el.shadowRoot.querySelector('.dac-error').textContent).toContain('后端连接被重置')
    expect(el.shadowRoot.querySelector('.dac-stop')).toBeNull()
    expect(el.shadowRoot.querySelector('.dac-send')).toBeTruthy()
    el.remove()
  })

  it('retries by resending the question that produced it', async () => {
    const transport = makeTransport({
      streamEvents: async function* () {
        throw new Error('后端连接被重置')
      }
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    await el.sendMessage('开始建模')
    await settle()

    el.shadowRoot.querySelector('.dac-retry').click()
    await settle()

    expect(transport.sendMessage).toHaveBeenCalledTimes(2)
    expect(transport.sendMessage.mock.calls[1][0]).toMatchObject({ content: '开始建模' })
    el.remove()
  })

  it('retries with the current composer settings', async () => {
    let callCount = 0
    const transport = makeTransport({
      sendMessage: vi.fn(async () => {
        callCount += 1
        return { taskId: `t-${callCount}`, status: 'running', detail: '' }
      }),
      streamEvents: async function* () {
        if (callCount === 1) throw new Error('模型超时')
      }
    })
    const el = mount({
      endpoint: '/conv/a',
      transportFactory: () => transport,
      composerConfig: {
        providers: [
          { provider_id: 'openai', models: ['gpt-4o', 'gpt-4o-mini'] },
          { provider_id: 'anthropic', models: ['claude-3-5-sonnet'] }
        ]
      }
    })
    await settle()

    const select = el.shadowRoot.querySelector('select[data-control="model"]')
    select.value = 'anthropic::claude-3-5-sonnet'
    select.dispatchEvent(new Event('change'))
    await settle()

    await el.sendMessage('开始建模')
    await settle()

    expect(transport.sendMessage).toHaveBeenCalledTimes(1)
    expect(transport.sendMessage.mock.calls[0][0].settings).toMatchObject({
      provider_id: 'anthropic',
      model: 'claude-3-5-sonnet'
    })

    // Change model before clicking retry
    select.value = 'openai::gpt-4o-mini'
    select.dispatchEvent(new Event('change'))
    await settle()

    el.shadowRoot.querySelector('.dac-retry').click()
    await settle()

    expect(transport.sendMessage).toHaveBeenCalledTimes(2)
    expect(transport.sendMessage.mock.calls[1][0]).toMatchObject({
      content: '开始建模',
      settings: {
        provider_id: 'openai',
        model: 'gpt-4o-mini'
      }
    })
    el.remove()
  })

  it('reports a run that already failed before the page was opened', async () => {
    // Reloading onto a failed run has to look the same as watching it fail;
    // otherwise history quietly renders an empty assistant turn.
    const transport = makeTransport({
      loadConversation: vi.fn(async () => ({
        messages: [
          { id: 'u-1', role: 'user', content: '开始建模' },
          { id: 'a-1', role: 'assistant', content: '', status: 'failed', error: { message: '模型调用超时' } }
        ],
        run: { taskId: 't-1', status: 'failed', detail: '模型调用超时' }
      }))
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    expect(el.shadowRoot.querySelector('.dac-error').textContent).toContain('模型调用超时')

    el.shadowRoot.querySelector('.dac-retry').click()
    await settle()

    expect(transport.sendMessage).toHaveBeenCalledWith(expect.objectContaining({ content: '开始建模' }))
    el.remove()
  })

  it('offers no retry while the run is still going', async () => {
    let release
    const transport = makeTransport({
      streamEvents: async function* () {
        await new Promise((resolve) => { release = resolve })
      }
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    await el.sendMessage('开始建模')
    await settle()

    expect(el.shadowRoot.querySelector('.dac-error')).toBeNull()
    expect(el.shadowRoot.querySelector('.dac-retry')).toBeNull()
    release?.()
    el.remove()
  })
})

describe('a run the user stops', () => {
  it('ends the turn, not just the run', async () => {
    // Cancelling only the run left the open turn on 'streaming': a blinking
    // cursor and a waiting indicator over an answer that was stopped, with
    // copy and rating withheld because the turn still looked unfinished.
    let release
    const transport = makeTransport({
      streamEvents: async function* () {
        yield {
          type: 'event',
          seqId: 1,
          event: { record_type: 'stream', data: { type: 'message_start' } }
        }
        yield {
          type: 'event',
          seqId: 2,
          event: {
            record_type: 'stream',
            data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } }
          }
        }
        yield {
          type: 'event',
          seqId: 3,
          event: {
            record_type: 'stream',
            data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: '写到一半' } }
          }
        }
        await new Promise((resolve) => { release = resolve })
      },
      cancelRun: vi.fn(async () => ({ taskId: 't-1', status: 'cancelled', detail: '已取消' }))
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    await el.sendMessage('开始建模')
    await settle()
    expect(el.shadowRoot.querySelector('.dac-cursor')).toBeTruthy()

    await el.cancel()
    await settle()

    expect(el.shadowRoot.querySelector('.dac-cursor')).toBeNull()
    expect(el.shadowRoot.querySelector('.dac-activity')).toBeNull()
    // What it did produce stays, and is now actionable.
    expect(el.shadowRoot.textContent).toContain('写到一半')
    expect(el.shadowRoot.querySelector('[data-action="copy"]')).toBeTruthy()
    release?.()
    el.remove()
  })
})
