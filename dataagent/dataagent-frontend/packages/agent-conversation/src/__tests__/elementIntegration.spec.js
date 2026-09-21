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
  sendMessage: vi.fn(async () => ({ taskId: 't-1', status: 'queued', detail: '' })),
  cancelRun: vi.fn(async () => ({ taskId: 't-1', status: 'cancelled', detail: '' })),
  submitInteraction: vi.fn(async () => {}),
  fileUrl: (p) => `/files/${p}`,
  streamEvents: async function* () {},
  ...over
})

/**
 * Mount the way a host framework does: create the element, assign JS
 * properties, then insert. Assigning before connection is the ordering React
 * produces when a ref callback runs in the same commit.
 */
const mount = (assign = {}, { lightDom = '' } = {}) => {
  const el = document.createElement(DEFAULT_TAG)
  if (lightDom) el.innerHTML = lightDom
  Object.assign(el, assign)
  document.body.appendChild(el)
  return el
}

describe('property proxying', () => {
  it('replays properties assigned before the element connects', async () => {
    const transport = makeTransport()
    const el = mount({
      endpoint: '/conv/a',
      transportFactory: () => transport
    })
    await settle()

    expect(transport.loadConversation).toHaveBeenCalled()
    el.remove()
  })

  it('exposes value as a readable and writable property', async () => {
    const el = mount({ endpoint: '/conv/a', transportFactory: () => makeTransport() })
    await settle()

    el.value = '开始建模'
    await settle()

    expect(el.value).toBe('开始建模')
    el.remove()
  })

  it('accepts properties assigned after connection', async () => {
    const transport = makeTransport()
    const el = mount({})
    await settle()

    el.transportFactory = () => transport
    el.endpoint = '/conv/late'
    await settle()

    expect(transport.loadConversation).toHaveBeenCalled()
    el.remove()
  })
})

describe('methods', () => {
  it('sends through the transport', async () => {
    const transport = makeTransport()
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    await el.sendMessage('开始建模', { metadata: { mode: 'model' } })
    await settle()

    expect(transport.sendMessage).toHaveBeenCalledWith({
      content: '开始建模',
      metadata: { mode: 'model' }
    })
    el.remove()
  })

  it('falls back to the composer draft when sendMessage gets no content', async () => {
    const transport = makeTransport()
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    el.value = 'from the box'
    await settle()
    await el.sendMessage()
    await settle()

    expect(transport.sendMessage).toHaveBeenCalledWith({
      content: 'from the box',
      metadata: undefined
    })
    el.remove()
  })
})

describe('events', () => {
  it('bubble out of the shadow root so a host can listen on the element', async () => {
    const transport = makeTransport()
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    const seen = []
    for (const name of ['ready', 'draft-change', 'run-change', 'complete']) {
      el.addEventListener(`dataagent-${name}`, (e) => seen.push([name, e.detail]))
    }
    await settle()

    el.value = 'hi'
    await settle()

    expect(seen.map(([n]) => n)).toContain('ready')
    expect(seen.find(([n]) => n === 'draft-change')?.[1]).toEqual({ value: 'hi' })
    el.remove()
  })

  it('carries the metadata back on completion', async () => {
    const transport = makeTransport({
      sendMessage: async () => ({ taskId: 't-7', status: 'queued', detail: '' }),
      streamEvents: async function* () {
        yield { type: 'terminal', run: { taskId: 't-7', status: 'finished', detail: '' } }
      }
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    const completions = []
    el.addEventListener('dataagent-complete', (e) => completions.push(e.detail))
    await settle()

    await el.sendMessage('go', { metadata: { mode: 'model' } })
    await settle()

    expect(completions[0]).toMatchObject({ taskId: 't-7', metadata: { mode: 'model' } })
    el.remove()
  })
})

describe('lazy conversation creation', () => {
  it('does not call the resolver until the first send', async () => {
    const transport = makeTransport()
    const resolver = vi.fn(async () => '/conv/created')
    const el = mount({ endpointResolver: resolver, transportFactory: () => transport })
    await settle()

    expect(resolver).not.toHaveBeenCalled()
    expect(transport.loadConversation).not.toHaveBeenCalled()

    await el.sendMessage('first')
    await settle()

    expect(resolver).toHaveBeenCalledTimes(1)
    expect(transport.sendMessage).toHaveBeenCalled()
    el.remove()
  })
})

describe('switching conversations', () => {
  it('reloads when the endpoint changes, without the host calling reload', async () => {
    const calls = []
    const el = mount({
      endpoint: '/conv/a',
      transportFactory: (address) => {
        calls.push(address)
        return makeTransport()
      }
    })
    await settle()

    el.endpoint = '/conv/b'
    await settle()

    expect(calls).toEqual(['/conv/a', '/conv/b'])
    el.remove()
  })
})

describe('host slots', () => {
  it('gives a host somewhere to put composer overlays and its own toolbar', async () => {
    // The widget's composer is not just a send button: a slash-command menu
    // sits against the textarea, and permission-mode and model selectors sit
    // in a row of their own. Those are product choices the SDK has no opinion
    // about, so it supplies the space rather than the controls.
    const el = mount(
      { endpoint: '/conv/a', transportFactory: () => makeTransport() },
      {
        lightDom: `
          <div slot="composer-overlay" id="slash-menu">/commands</div>
          <button slot="composer-actions" id="start">开始建模</button>
          <div slot="composer-toolbar" id="toolbar">模型选择</div>
        `,
      },
    )
    await settle()

    const shadow = el.shadowRoot
    const named = (name) => shadow.querySelector(`slot[name="${name}"]`)

    for (const [slotName, id] of [
      ['composer-overlay', 'slash-menu'],
      ['composer-actions', 'start'],
      ['composer-toolbar', 'toolbar'],
    ]) {
      const slot = named(slotName)
      expect(slot, `${slotName} slot must exist`).toBeTruthy()
      expect(slot.assignedNodes({ flatten: true })).toContain(el.querySelector(`#${id}`))
    }

    // Position matters as much as presence: the overlay has to precede the
    // input and the toolbar has to follow the footer, or the widget's layout
    // cannot be reproduced.
    const composer = shadow.querySelector('.dac-composer')
    const order = [...composer.children].map((child) =>
      child.tagName === 'SLOT' ? child.getAttribute('name') : child.className,
    )
    expect(order.indexOf('composer-overlay')).toBeLessThan(order.indexOf('dac-input'))
    expect(order.indexOf('composer-toolbar')).toBeGreaterThan(
      order.indexOf('dac-composer-footer'),
    )
    el.remove()
  })

  it('projects composer actions into the footer', async () => {
    const el = mount(
      { endpoint: '/conv/a', transportFactory: () => makeTransport() },
      { lightDom: '<button slot="composer-actions" id="start">开始建模</button>' }
    )
    await settle()

    const slot = el.shadowRoot.querySelector('.dac-composer-actions slot[name="composer-actions"]')
    expect(slot).toBeTruthy()
    expect(slot.assignedNodes({ flatten: true })).toContain(el.querySelector('#start'))
    el.remove()
  })
})

describe('streaming actually renders', () => {
  /** Records shaped like the ones the runtime emits. */
  const textRun = (taskId) => async function* () {
    yield { type: 'event', seqId: 1, event: { record_type: 'stream', data: { type: 'message_start', usage: {} } } }
    yield { type: 'event', seqId: 2, event: { record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } } }
    yield { type: 'event', seqId: 3, event: { record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: '你好' } } } }
    yield { type: 'event', seqId: 4, event: { record_type: 'stream', data: { type: 'content_block_stop', index: 0 } } }
    yield { type: 'terminal', run: { taskId, status: 'finished', detail: '' } }
  }

  it('shows assistant text as it streams, not only after the run ends', async () => {
    // The element used to dispatch events and nothing else: the conversation
    // stayed visually empty for the whole run, then filled in from a refetch.
    let releaseTerminal
    const held = new Promise((resolve) => { releaseTerminal = resolve })
    const transport = makeTransport({
      sendMessage: async () => ({ taskId: 't-1', status: 'queued', detail: '' }),
      streamEvents: async function* () {
        yield { type: 'event', seqId: 1, event: { record_type: 'stream', data: { type: 'message_start', usage: {} } } }
        yield { type: 'event', seqId: 2, event: { record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } } }
        yield { type: 'event', seqId: 3, event: { record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: '流式内容' } } } }
        await held
        yield { type: 'terminal', run: { taskId: 't-1', status: 'finished', detail: '' } }
      },
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    await el.sendMessage('hi')
    await settle(30)

    // Still mid-run — the terminal item has not been yielded.
    expect(el.shadowRoot.textContent).toContain('流式内容')

    releaseTerminal()
    await settle(30)
    el.remove()
  })

  it('renders a tool call rather than dropping it', async () => {
    const transport = makeTransport({
      sendMessage: async () => ({ taskId: 't-2', status: 'queued', detail: '' }),
      streamEvents: async function* () {
        yield { type: 'event', seqId: 1, event: { record_type: 'stream', data: { type: 'message_start', usage: {} } } }
        yield { type: 'event', seqId: 2, event: { record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'tu-1', name: 'run-sql' } } } }
        yield { type: 'event', seqId: 3, event: { record_type: 'stream', data: { type: 'content_block_stop', index: 0 } } }
        yield { type: 'terminal', run: { taskId: 't-2', status: 'finished', detail: '' } }
      },
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    await el.sendMessage('查一下')
    await settle(30)

    expect(el.shadowRoot.querySelector('.tool-output')).toBeTruthy()
    el.remove()
  })

  it('renders history blocks the same way a live run renders', async () => {
    // A reloaded page must not look different from one that watched the run.
    const transport = makeTransport({
      loadConversation: async () => ({
        messages: [{
          id: 'm-1',
          role: 'assistant',
          content: '',
          blocks: [{ type: 'text', text: '历史回复' }],
        }],
        run: null,
      }),
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    expect(el.shadowRoot.textContent).toContain('历史回复')
    el.remove()
  })
})

describe('waiting states have a way out', () => {
  it('submits a permission decision so a parked run can continue', async () => {
    // Without this the card renders and nothing can answer it: the run stays
    // on waiting_permission forever.
    const transport = makeTransport({
      sendMessage: async () => ({ taskId: 't-3', status: 'queued', detail: '' }),
      streamEvents: async function* () {
        yield { type: 'event', seqId: 1, event: { record_type: 'stream', data: { type: 'message_start', usage: {} } } }
        yield {
          type: 'event',
          seqId: 2,
          event: {
            record_type: 'permission_request',
            data: { request_id: 'req-1', tool_name: 'run-sql', input: {} },
          },
        }
        await new Promise(() => {})
      },
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    await el.sendMessage('删点东西')
    await settle(30)

    const card = el.shadowRoot.querySelector('.v2-perm-card')
    expect(card, 'a permission request must render a card').toBeTruthy()

    const allow = [...el.shadowRoot.querySelectorAll('button')].find((b) =>
      /允许|同意|allow/i.test(b.textContent || ''),
    )
    expect(allow, 'the card must offer an answer').toBeTruthy()
    allow.click()
    await settle(20)

    expect(transport.submitInteraction).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'permission', requestId: 'req-1' }),
    )
    el.remove()
  })
})
