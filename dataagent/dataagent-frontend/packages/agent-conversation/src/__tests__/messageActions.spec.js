import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { defineAgentConversation, DEFAULT_TAG } from '../element.js'

beforeAll(() => {
  defineAgentConversation()
})

const settle = async (ticks = 12) => {
  for (let i = 0; i < ticks; i += 1) await Promise.resolve()
  await nextTick()
}

const HISTORY = [
  { id: 'u-1', role: 'user', content: '最近 30 天的订单趋势' },
  {
    id: 'a-1',
    role: 'assistant',
    content: '订单量环比增长 12%。',
    created_at: '2026-09-22T09:05:00.000Z',
    feedback: ''
  }
]

const makeTransport = (over = {}) => ({
  loadConversation: vi.fn(async () => ({ messages: HISTORY.map((m) => ({ ...m })), run: null })),
  sendMessage: vi.fn(async () => ({ taskId: 't-1', status: 'running', detail: '' })),
  cancelRun: vi.fn(async () => ({ taskId: 't-1', status: 'cancelled', detail: '' })),
  submitInteraction: vi.fn(async () => {}),
  fileUrl: (p) => `/files/${p}`,
  streamEvents: async function* () {},
  ...over
})

const mount = async (transport) => {
  const el = document.createElement(DEFAULT_TAG)
  Object.assign(el, { endpoint: '/conv/a', transportFactory: () => transport })
  document.body.appendChild(el)
  await settle()
  return el
}

let writeText

beforeEach(() => {
  writeText = vi.fn(async () => {})
  vi.stubGlobal('navigator', { clipboard: { writeText } })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('message actions', () => {
  it('copies the answer text', async () => {
    const el = await mount(makeTransport())

    el.shadowRoot.querySelector('[data-action="copy"]').click()
    await settle()

    expect(writeText).toHaveBeenCalledWith('订单量环比增长 12%。')
    expect(el.shadowRoot.querySelector('[data-action="copy"]').textContent).toContain('已复制')
    el.remove()
  })

  it('shows the time the answer arrived', async () => {
    const el = await mount(makeTransport())
    expect(el.shadowRoot.querySelector('.dac-time')).toBeTruthy()
    el.remove()
  })

  it('hides rating when the host has nowhere to store it', async () => {
    // Capability-driven: a host without submitFeedback gets no buttons rather
    // than buttons that quietly discard the answer.
    const el = await mount(makeTransport())
    expect(el.shadowRoot.querySelector('[data-action="like"]')).toBeNull()
    el.remove()
  })

  it('records a rating and reports it to the transport', async () => {
    const submitFeedback = vi.fn(async ({ feedback }) => ({ feedback }))
    const el = await mount(makeTransport({ submitFeedback }))

    el.shadowRoot.querySelector('[data-action="like"]').click()
    await settle()

    expect(submitFeedback).toHaveBeenCalledWith({ messageId: 'a-1', feedback: 'like' })
    expect(el.shadowRoot.querySelector('[data-action="like"]').getAttribute('aria-pressed')).toBe('true')
    el.remove()
  })

  it('clears a rating when the same one is pressed again', async () => {
    const submitFeedback = vi.fn(async ({ feedback }) => ({ feedback }))
    const el = await mount(makeTransport({ submitFeedback }))

    el.shadowRoot.querySelector('[data-action="like"]').click()
    await settle()
    el.shadowRoot.querySelector('[data-action="like"]').click()
    await settle()

    expect(submitFeedback).toHaveBeenLastCalledWith({ messageId: 'a-1', feedback: '' })
    expect(el.shadowRoot.querySelector('[data-action="like"]').getAttribute('aria-pressed')).toBe('false')
    el.remove()
  })

  it('rolls the rating back when saving it fails', async () => {
    // A rating that failed to save looks exactly like one that saved. Without
    // the rollback the user has no way to know to press it again.
    const submitFeedback = vi.fn(async () => { throw new Error('保存失败') })
    const errors = []
    const el = await mount(makeTransport({ submitFeedback }))
    el.addEventListener('dataagent-error', (event) => errors.push(event.detail))

    el.shadowRoot.querySelector('[data-action="like"]').click()
    await settle()

    expect(el.shadowRoot.querySelector('[data-action="like"]').getAttribute('aria-pressed')).toBe('false')
    expect(errors).toHaveLength(1)
    el.remove()
  })
})

describe('deep linking', () => {
  it('brings a message into view by id, without the host touching the shadow root', async () => {
    const el = await mount(makeTransport())

    const found = await el.focusMessage('a-1')
    await settle()

    expect(found).toBe(true)
    expect(el.shadowRoot.querySelector('[data-message-id="a-1"]').classList.contains('is-focused')).toBe(true)
    el.remove()
  })

  it('reports when the message is not in this conversation', async () => {
    const el = await mount(makeTransport())
    expect(await el.focusMessage('missing')).toBe(false)
    el.remove()
  })
})
