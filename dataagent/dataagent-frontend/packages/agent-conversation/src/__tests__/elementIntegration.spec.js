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
