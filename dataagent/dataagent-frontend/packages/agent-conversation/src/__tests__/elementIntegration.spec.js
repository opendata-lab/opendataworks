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

  it('returns a promise from reload that resolves when the conversation loads', async () => {
    let finishedLoad = false
    const transport = makeTransport({
      loadConversation: async () => {
        await new Promise((r) => setTimeout(r, 20))
        finishedLoad = true
        return { messages: [], run: null }
      }
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    finishedLoad = false
    const reloadPromise = el.reload()
    expect(reloadPromise).toBeInstanceOf(Promise)
    await reloadPromise
    expect(finishedLoad).toBe(true)
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

describe('thinking blocks', () => {
  it('renders history reasoning collapsed and lets the user expand it', async () => {
    const transport = makeTransport({
      loadConversation: vi.fn(async () => ({
        messages: [{
          id: 'assistant-1',
          role: 'assistant',
          content: '',
          blocks: [{ type: 'thinking', text: '先分析业务目标，再确认数据范围。' }],
        }],
        run: null,
      })),
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    const toggle = el.shadowRoot.querySelector('.dac-thinking-summary')
    expect(toggle).toBeTruthy()
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(el.shadowRoot.querySelector('.dac-thinking-content')).toBeNull()

    toggle.click()
    await settle()

    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(el.shadowRoot.querySelector('.dac-thinking-content').textContent).toContain('再确认数据范围')
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

  it('releases submitting state and displays hint when interaction submission fails', async () => {
    let shouldFail = true
    const transport = makeTransport({
      sendMessage: async () => ({ taskId: 't-fail', status: 'queued', detail: '' }),
      submitInteraction: vi.fn(async () => {
        if (shouldFail) {
          throw new Error('网络断开')
        }
      }),
      streamEvents: async function* () {
        yield { type: 'event', seqId: 1, event: { record_type: 'stream', data: { type: 'message_start', usage: {} } } }
        yield {
          type: 'event',
          seqId: 2,
          event: {
            record_type: 'permission_request',
            data: { request_id: 'req-fail-1', tool_name: 'drop-table', input: {} },
          },
        }
        await new Promise(() => {})
      },
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    await el.sendMessage('删表')
    await settle(30)

    const allowBtn = [...el.shadowRoot.querySelectorAll('button')].find((b) =>
      /允许|同意|allow/i.test(b.textContent || ''),
    )
    expect(allowBtn).toBeTruthy()
    expect(allowBtn.disabled).toBe(false)

    // First attempt fails
    allowBtn.click()
    await settle(30)

    expect(transport.submitInteraction).toHaveBeenCalledTimes(1)
    // Submitting must be released so the button can be clicked again
    expect(allowBtn.disabled).toBe(false)
    // Card must display the retry hint
    expect(el.shadowRoot.querySelector('.v2-perm-failed-hint').textContent).toContain('提交失败，请重试')

    // Retry should work
    shouldFail = false
    allowBtn.click()
    await settle(30)

    expect(transport.submitInteraction).toHaveBeenCalledTimes(2)
    el.remove()
  })

  it('releases submitting state and displays hint when question submission fails', async () => {
    let shouldFail = true
    const transport = makeTransport({
      sendMessage: async () => ({ taskId: 't-q', status: 'queued', detail: '' }),
      submitInteraction: vi.fn(async () => {
        if (shouldFail) {
          throw new Error('提交问题超时')
        }
      }),
      streamEvents: async function* () {
        yield { type: 'event', seqId: 1, event: { record_type: 'stream', data: { type: 'message_start', usage: {} } } }
        yield {
          type: 'event',
          seqId: 2,
          event: {
            record_type: 'question_request',
            data: {
              request_id: 'req-q-1',
              questions: [{ question: '你想要哪种图表？', options: ['折线图', '柱状图'] }]
            },
          },
        }
        await new Promise(() => {})
      },
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    await el.sendMessage('画图')
    await settle(30)

    const opt = el.shadowRoot.querySelector('.v2-q-opt')
    expect(opt).toBeTruthy()
    opt.click()
    await settle(10)

    const submitBtn = el.shadowRoot.querySelector('.v2-q-submit')
    expect(submitBtn).toBeTruthy()
    expect(submitBtn.disabled).toBe(false)

    // First submit fails
    submitBtn.click()
    await settle(30)

    expect(transport.submitInteraction).toHaveBeenCalledTimes(1)
    expect(submitBtn.disabled).toBe(false)
    expect(el.shadowRoot.querySelector('.v2-q-failed-hint').textContent).toContain('提交失败，请重试')

    // Retry submit
    shouldFail = false
    submitBtn.click()
    await settle(30)

    expect(transport.submitInteraction).toHaveBeenCalledTimes(2)
    el.remove()
  })

  it('disables pending cards when loaded from history', async () => {
    const transport = makeTransport({
      loadConversation: async () => ({
        messages: [
          {
            id: 'a-history',
            role: 'assistant',
            status: 'success',
            _v2state: {
              status: 'done',
              blocks: [
                {
                  type: 'permission_request',
                  requestId: 'perm-hist',
                  tool_name: 'run-sql',
                  decision: 'pending'
                },
                {
                  type: 'question_request',
                  requestId: 'q-hist',
                  answered: false,
                  questions: [{ question: '历史问题', options: ['A', 'B'] }]
                }
              ]
            }
          }
        ],
        run: null
      })
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    const permBtn = el.shadowRoot.querySelector('.v2-perm-btn.allow')
    expect(permBtn).toBeTruthy()
    expect(permBtn.disabled).toBe(true)
    permBtn.click()

    const qOption = el.shadowRoot.querySelector('.v2-q-opt')
    expect(qOption).toBeTruthy()
    expect(qOption.disabled).toBe(true)
    qOption.click()
    const qBtn = el.shadowRoot.querySelector('.v2-q-submit')
    expect(qBtn).toBeTruthy()
    expect(qBtn.disabled).toBe(true)
    qBtn.click()
    await settle()

    expect(transport.submitInteraction).not.toHaveBeenCalled()

    el.remove()
  })
})

describe('tool rendering and activity cues', () => {
  it('suppresses raw AskUserQuestion tool_use block and only renders QuestionCard', async () => {
    const transport = makeTransport({
      loadConversation: async () => ({
        messages: [
          {
            id: 'a-tool',
            role: 'assistant',
            _v2state: {
              status: 'streaming',
              blocks: [
                {
                  type: 'tool_use',
                  id: 'tu-ask',
                  name: 'AskUserQuestion',
                  input: { questions: [] }
                },
                {
                  type: 'tool_use',
                  id: 'tu-sql',
                  name: 'run_sql',
                  input: { sql: 'SELECT 1' }
                },
                {
                  type: 'question_request',
                  requestId: 'q-1',
                  answered: false,
                  questions: [{ question: '选一个', options: ['1', '2'] }]
                }
              ]
            }
          }
        ],
        run: null
      })
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    // Only run_sql tool should be rendered as a tool output
    const toolOutputs = el.shadowRoot.querySelectorAll('.tool-output')
    expect(toolOutputs).toHaveLength(1)
    expect(toolOutputs[0].textContent).toContain('run_sql')

    // Question card should be rendered
    expect(el.shadowRoot.querySelector('.v2-q-card')).toBeTruthy()
    el.remove()
  })

  it('shows trailing activity cue when tool finishes but run continues, hides during waiting states', async () => {
    let stateRef
    const transport = makeTransport({
      loadConversation: async () => ({
        messages: [
          {
            id: 'a-active',
            role: 'assistant',
            taskId: 't-cue',
            _v2state: stateRef
          }
        ],
        run: { taskId: 't-cue', status: 'running', detail: '' }
      })
    })

    // 1. Tool finished with output, turn still streaming -> shows activity cue
    stateRef = {
      status: 'streaming',
      blocks: [
        { type: 'tool_use', id: 'tu-1', name: 'query_db', output: 'ok' }
      ]
    }
    let el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()
    expect(el.shadowRoot.querySelector('.dac-activity')).toBeTruthy()
    el.remove()

    // 2. Waiting permission -> activity cue suppressed
    stateRef = {
      status: 'streaming',
      blocks: [
        { type: 'tool_use', id: 'tu-1', name: 'query_db', output: 'ok' },
        { type: 'permission_request', requestId: 'p-1', decision: 'pending' }
      ]
    }
    el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()
    expect(el.shadowRoot.querySelector('.dac-activity')).toBeNull()
    el.remove()

    // 3. Waiting question -> activity cue suppressed
    stateRef = {
      status: 'streaming',
      blocks: [
        { type: 'tool_use', id: 'tu-1', name: 'query_db', output: 'ok' },
        { type: 'question_request', requestId: 'q-1', answered: false, questions: [] }
      ]
    }
    el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()
    expect(el.shadowRoot.querySelector('.dac-activity')).toBeNull()
    el.remove()

    // 4. Actively streaming text block -> activity cue suppressed (cursor conveys progress)
    stateRef = {
      status: 'streaming',
      blocks: [
        { type: 'tool_use', id: 'tu-1', name: 'query_db', output: 'ok' },
        { type: 'text', content: '正在写回答...', status: 'streaming' }
      ]
    }
    el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()
    expect(el.shadowRoot.querySelector('.dac-activity')).toBeNull()
    el.remove()
  })
})

describe('auto scroll', () => {
  it('deep watches message content changes to follow streaming output when near bottom', async () => {
    let streamPush
    const transport = makeTransport({
      sendMessage: async () => ({ taskId: 't-scroll', status: 'queued', detail: '' }),
      streamEvents: async function* () {
        yield { type: 'event', seqId: 1, event: { record_type: 'stream', data: { type: 'message_start', usage: {} } } }
        yield { type: 'event', seqId: 2, event: { record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } } }
        while (true) {
          const delta = await new Promise((resolve) => { streamPush = resolve })
          if (!delta) break
          yield { type: 'event', seqId: 3, event: { record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: delta } } } }
        }
        yield { type: 'terminal', run: { taskId: 't-scroll', status: 'finished', detail: '' } }
      }
    })
    const el = mount({ endpoint: '/conv/a', transportFactory: () => transport })
    await settle()

    await el.sendMessage('问长文')
    await settle(30)

    const scrollContainer = el.shadowRoot.querySelector('.dac-messages')
    Object.defineProperty(scrollContainer, 'clientHeight', { value: 300, configurable: true })
    Object.defineProperty(scrollContainer, 'scrollHeight', { value: 600, writable: true, configurable: true })
    scrollContainer.scrollTop = 250 // 600 - 250 - 300 = 50 < 60 (near bottom)
    scrollContainer.dispatchEvent(new Event('scroll'))

    // Stream new tokens into the same text block
    streamPush('更多新文字到来')
    scrollContainer.scrollHeight = 800
    await settle(30)

    expect(scrollContainer.scrollTop).toBe(800)

    // Scrolled far up (> 60 from bottom)
    scrollContainer.scrollTop = 100 // 800 - 100 - 300 = 400 >= 60
    scrollContainer.dispatchEvent(new Event('scroll'))
    scrollContainer.scrollHeight = 1000
    streamPush('用户正在往上看历史')
    await settle(30)

    // Must NOT jump to bottom
    expect(scrollContainer.scrollTop).toBe(100)

    streamPush(null)
    await settle(30)
    el.remove()
  })
})
