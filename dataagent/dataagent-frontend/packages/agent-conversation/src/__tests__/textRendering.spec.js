import { describe, it, expect, vi, beforeAll } from 'vitest'
import { nextTick } from 'vue'
import { defineAgentConversation, DEFAULT_TAG } from '../element.js'

beforeAll(() => {
  defineAgentConversation()
})

const settle = async (ticks = 16) => {
  for (let i = 0; i < ticks; i += 1) await Promise.resolve()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

const CHART = {
  kind: 'chart_spec',
  version: 1,
  chart_type: 'bar',
  title: '每月订单量',
  x_field: 'month',
  dataset: [{ month: '01', orders: 12 }, { month: '02', orders: 19 }],
  series: [{ field: 'orders', name: '订单量' }]
}

const makeTransport = (messages, over = {}) => ({
  loadConversation: vi.fn(async () => ({ messages, run: null })),
  sendMessage: vi.fn(async () => ({ taskId: 't-1', status: 'running', detail: '' })),
  cancelRun: vi.fn(async () => ({ taskId: 't-1', status: 'cancelled', detail: '' })),
  submitInteraction: vi.fn(async () => {}),
  fileUrl: (p) => `/files/${p}`,
  streamEvents: async function* () {},
  ...over
})

const mount = async (transport, assign = {}) => {
  const el = document.createElement(DEFAULT_TAG)
  Object.assign(el, { endpoint: '/conv/a', transportFactory: () => transport, ...assign })
  document.body.appendChild(el)
  await settle()
  return el
}

const text = (el) => el.shadowRoot.querySelector('.dac-messages').textContent

describe('answer text', () => {
  it('renders an embedded chart instead of leaking its json', async () => {
    // The agent writes a chart as a chart_spec object inside the answer. Sent
    // through markdown as one string, the reader gets the JSON.
    const answer = `销量稳步上升。\n\n${JSON.stringify(CHART)}\n\n建议关注 2 月。`
    const el = await mount(makeTransport([
      { id: 'a-1', role: 'assistant', content: answer }
    ]))

    expect(text(el)).toContain('销量稳步上升')
    expect(text(el)).toContain('建议关注 2 月')
    expect(text(el)).not.toContain('chart_spec')
    expect(text(el)).not.toContain('x_field')
    el.remove()
  })

  it('renders markdown around the chart', async () => {
    const el = await mount(makeTransport([
      { id: 'a-1', role: 'assistant', content: '## 结论\n\n**增长** 明显。' }
    ]))

    expect(el.shadowRoot.querySelector('.dac-bubble h2')).toBeTruthy()
    expect(el.shadowRoot.querySelector('.dac-bubble strong').textContent).toBe('增长')
    el.remove()
  })

  it('shows a cursor only on the block still being written', async () => {
    const el = await mount(makeTransport([
      {
        id: 'a-1',
        role: 'assistant',
        content: '',
        blocks: [
          { kind: 'main_text', content: '已完成的段落', status: 'done' }
        ]
      }
    ]))
    expect(el.shadowRoot.querySelector('.dac-cursor')).toBeNull()
    el.remove()
  })

  it('says it is working before the first token arrives', async () => {
    // Otherwise an open turn is an empty bubble for however long the agent
    // spends thinking, which reads as a stall.
    let release
    const transport = makeTransport([], {
      streamEvents: async function* () {
        await new Promise((resolve) => { release = resolve })
      }
    })
    const el = await mount(transport)

    await el.sendMessage('开始建模')
    await settle()

    const activity = el.shadowRoot.querySelector('.dac-activity')
    expect(activity).toBeTruthy()
    expect(activity.textContent).toContain('正在处理')
    release?.()
    el.remove()
  })

  it('lets the host word the waiting state', async () => {
    let release
    const transport = makeTransport([], {
      streamEvents: async function* () {
        await new Promise((resolve) => { release = resolve })
      }
    })
    const el = await mount(transport, { activityLabel: '正在推断本体…' })

    await el.sendMessage('开始建模')
    await settle()

    expect(el.shadowRoot.querySelector('.dac-activity').textContent).toContain('正在推断本体')
    release?.()
    el.remove()
  })
})
