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

const mountWithMessages = async (messages, run = null) => {
  const el = document.createElement(DEFAULT_TAG)
  Object.assign(el, {
    endpoint: '/conv/a',
    transportFactory: () => makeTransport({
      loadConversation: vi.fn(async () => ({ messages, run }))
    })
  })
  document.body.appendChild(el)
  await settle()
  return el
}

const rendered = (el) => el.shadowRoot.querySelector('.dac-messages').innerHTML

/**
 * One assistant turn, as the stored event records a direct DataAgent transport
 * returns.
 */
const RECORDS = [
  { record_type: 'stream', data: { type: 'message_start' } },
  { record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'thinking' } } },
  { record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: '先看四张表的外键。' } } },
  { record_type: 'stream', data: { type: 'content_block_stop', index: 0 } },
  { record_type: 'stream', data: { type: 'content_block_start', index: 1, content_block: { type: 'tool_use', id: 'tool-1', name: 'read_schema' } } },
  { record_type: 'stream', data: { type: 'content_block_stop', index: 1 } },
  { record_type: 'tool_result', data: { tool_use_id: 'tool-1', content: 'customers, products, orders, order_items' } },
  { record_type: 'stream', data: { type: 'content_block_start', index: 2, content_block: { type: 'text' } } },
  { record_type: 'stream', data: { type: 'content_block_delta', index: 2, delta: { type: 'text_delta', text: '已识别 4 张表。' } } },
  { record_type: 'stream', data: { type: 'content_block_stop', index: 2 } },
  { record_type: 'stream', data: { type: 'message_stop' } },
  { record_type: 'done', data: {} }
]

/** The same turn, as the blocks a BFF projects server-side. */
const BLOCKS = [
  { type: 'thinking', text: '先看四张表的外键。' },
  { type: 'tool_use', tool_id: 'tool-1', tool_name: 'read_schema', output: 'customers, products, orders, order_items' },
  { type: 'main_text', text: '已识别 4 张表。' }
]

describe('history projection', () => {
  it('renders stored records and server-projected blocks identically', async () => {
    // The SDK has to serve two kinds of host: one whose transport returns raw
    // event records, and one whose BFF has already projected them into blocks.
    // These were separately written projections that drifted; the point of
    // replaying both through one reducer is that this assertion holds.
    const fromRecords = await mountWithMessages([
      { id: 'a-1', role: 'assistant', content: '已识别 4 张表。', records: RECORDS }
    ])
    const recordsHtml = rendered(fromRecords)
    fromRecords.remove()

    const fromBlocks = await mountWithMessages([
      { id: 'a-1', role: 'assistant', content: '已识别 4 张表。', blocks: BLOCKS }
    ])
    const blocksHtml = rendered(fromBlocks)
    fromBlocks.remove()

    expect(recordsHtml).toBe(blocksHtml)
    expect(recordsHtml).toContain('已识别 4 张表。')
    expect(recordsHtml).toContain('dac-thinking')
  })

  it('keeps every turn of a multi-turn run', async () => {
    // A run that calls a tool and then keeps going produces a second
    // message_start. Flattening those into one turn used to drop the blocks
    // that arrived after the first message_stop.
    const el = await mountWithMessages([
      {
        id: 'a-1',
        role: 'assistant',
        content: '',
        records: [
          ...RECORDS,
          { record_type: 'stream', data: { type: 'message_start' } },
          { record_type: 'stream', data: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } },
          { record_type: 'stream', data: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: '接着推断实体关系。' } } },
          { record_type: 'stream', data: { type: 'content_block_stop', index: 0 } },
          { record_type: 'stream', data: { type: 'message_stop' } }
        ]
      }
    ])

    const html = rendered(el)
    expect(html).toContain('已识别 4 张表。')
    expect(html).toContain('接着推断实体关系。')
    el.remove()
  })

  it('restores an unanswered question from projected blocks', async () => {
    // question_request had no branch in the blocks projection at all, so a
    // reload of a run parked on a question showed nothing to answer — the run
    // could never be resumed from that tab.
    const el = await mountWithMessages([
      {
        id: 'a-1',
        role: 'assistant',
        content: '',
        blocks: [{
          type: 'question_request',
          request_id: 'q-1',
          questions: [{ question: '按下单时间还是发货时间统计？', options: [{ label: '下单时间' }, { label: '发货时间' }] }]
        }]
      }
    ], { taskId: 't-1', status: 'waiting_input', detail: '' })

    expect(el.shadowRoot.querySelector('.v2-q-card')).toBeTruthy()
    expect(rendered(el)).toContain('按下单时间还是发货时间统计？')
    el.remove()
  })

  it('renders a failed run the same way from records and from blocks', async () => {
    const fromRecords = await mountWithMessages([
      { id: 'a-1', role: 'assistant', content: '', status: 'failed', error: { message: '模型调用超时' }, records: RECORDS }
    ])
    const recordsHtml = rendered(fromRecords)
    fromRecords.remove()

    const fromBlocks = await mountWithMessages([
      { id: 'a-1', role: 'assistant', content: '', status: 'failed', error: { message: '模型调用超时' }, blocks: BLOCKS }
    ])
    const blocksHtml = rendered(fromBlocks)
    fromBlocks.remove()

    expect(recordsHtml).toBe(blocksHtml)
    expect(recordsHtml).toContain('模型调用超时')
  })
})

describe('returning to a run still in flight', () => {
  it.each([
    ['public shape', { resumeAfterSeq: 12 }],
    ['DataAgent/BFF row', { resume_after_seq: 12 }],
  ])('resumes where the persisted %s ends instead of replaying it', async (_shape, resumeField) => {
    // Leaving a running conversation and coming back used to restart the
    // stream at 0, so the thinking, tool calls and answer text already stored
    // on the turn arrived a second time and rendered twice.
    const seen = []
    const transport = makeTransport({
      loadConversation: vi.fn(async () => ({
        messages: [{
          id: 'a-1',
          role: 'assistant',
          content: '',
          taskId: 't-1',
          ...resumeField,
          records: RECORDS
        }],
        run: { taskId: 't-1', status: 'running', detail: '' }
      })),
      streamEvents: vi.fn(async function* ({ afterId }) {
        seen.push(afterId)
        yield { type: 'terminal', run: { taskId: 't-1', status: 'finished', detail: '' } }
      })
    })
    const el = document.createElement(DEFAULT_TAG)
    Object.assign(el, { endpoint: '/conv/a', transportFactory: () => transport })
    document.body.appendChild(el)
    await settle()

    expect(seen).toEqual([12])
    el.remove()
  })
})
