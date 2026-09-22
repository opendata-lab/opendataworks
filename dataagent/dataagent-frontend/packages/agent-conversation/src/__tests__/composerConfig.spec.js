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

const CONFIG = {
  providers: [
    { id: 'anthropic', label: 'Anthropic', models: [{ id: 'sonnet', label: 'Sonnet' }, { id: 'opus', label: 'Opus' }] },
    { id: 'deepseek', label: 'DeepSeek', models: [{ id: 'v4-pro', label: 'V4 Pro' }] }
  ],
  permissionModes: [
    { id: 'default', label: '默认', description: '写操作前确认' },
    { id: 'auto', label: '自动放行' }
  ],
  slashCommands: [
    { name: 'compact', description: '压缩上下文' },
    { name: 'clear', description: '清空会话' },
    { name: 'cost', description: '查看用量' }
  ],
  suggestions: ['分析最近 30 天的订单趋势']
}

const mount = async (assign = {}) => {
  const el = document.createElement(DEFAULT_TAG)
  Object.assign(el, { endpoint: '/conv/a', ...assign })
  document.body.appendChild(el)
  await settle()
  return el
}

const key = (el, init) => {
  const input = el.shadowRoot.querySelector('.dac-input')
  const event = new KeyboardEvent('keydown', { cancelable: true, bubbles: true, ...init })
  input.dispatchEvent(event)
  return event
}

const items = (el) => [...el.shadowRoot.querySelectorAll('.dac-slash-item')]

describe('composer configuration', () => {
  it('renders nothing extra when the host configures nothing', async () => {
    // Capability-driven: OntoFoundry has one model and no slash commands, and
    // must not inherit a picker with nothing in it.
    const el = await mount({ transportFactory: () => makeTransport() })

    expect(el.shadowRoot.querySelector('[data-control="model"]')).toBeNull()
    expect(el.shadowRoot.querySelector('[data-control="permission-mode"]')).toBeNull()
    expect(el.shadowRoot.querySelector('.dac-suggestions')).toBeNull()
    el.remove()
  })

  it('sends the current model and permission mode with the message', async () => {
    const transport = makeTransport()
    const el = await mount({ transportFactory: () => transport, composerConfig: CONFIG })

    const select = el.shadowRoot.querySelector('[data-control="model"]')
    select.value = 'deepseek/v4-pro'
    select.dispatchEvent(new Event('change'))
    await settle()

    await el.sendMessage('开始建模')
    await settle()

    expect(transport.sendMessage).toHaveBeenCalledWith(expect.objectContaining({
      content: '开始建模',
      settings: { providerId: 'deepseek', model: 'v4-pro', permissionMode: 'default' }
    }))
    el.remove()
  })

  it('defaults to the first option the host offers', async () => {
    // An unset value would send a message with no model while the picker
    // plainly shows one selected.
    const transport = makeTransport()
    const el = await mount({ transportFactory: () => transport, composerConfig: CONFIG })

    await el.sendMessage('你好')
    await settle()

    expect(transport.sendMessage.mock.calls[0][0].settings).toEqual({
      providerId: 'anthropic', model: 'sonnet', permissionMode: 'default'
    })
    el.remove()
  })

  it('offers the host openers only while the conversation is empty', async () => {
    const transport = makeTransport()
    const el = await mount({ transportFactory: () => transport, composerConfig: CONFIG })

    const suggestion = el.shadowRoot.querySelector('.dac-suggestion')
    expect(suggestion.textContent).toContain('订单趋势')

    suggestion.click()
    await settle()

    expect(transport.sendMessage).toHaveBeenCalledWith(
      expect.objectContaining({ content: '分析最近 30 天的订单趋势' })
    )
    expect(el.shadowRoot.querySelector('.dac-suggestions')).toBeNull()
    el.remove()
  })
})

describe('slash commands', () => {
  it('stays hidden when the host supplies none', async () => {
    const el = await mount({ transportFactory: () => makeTransport() })
    el.value = '/comp'
    await settle()

    expect(el.shadowRoot.querySelector('.dac-slash')).toBeNull()
    el.remove()
  })

  it('filters as the user types', async () => {
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })

    el.value = '/c'
    await settle()
    expect(items(el).map((n) => n.textContent)).toHaveLength(3)

    el.value = '/co'
    await settle()
    expect(items(el)).toHaveLength(2)

    el.value = '/comp'
    await settle()
    expect(items(el)).toHaveLength(1)
    el.remove()
  })

  it('does not open for prose that merely contains a slash', async () => {
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })
    el.value = '统计 2026/09 的订单'
    await settle()

    expect(el.shadowRoot.querySelector('.dac-slash')).toBeNull()
    el.remove()
  })

  it('moves through the list and accepts with Enter instead of sending', async () => {
    const transport = makeTransport()
    const el = await mount({ transportFactory: () => transport, composerConfig: CONFIG })

    el.value = '/c'
    await settle()
    key(el, { key: 'ArrowDown' })
    await settle()
    expect(items(el)[1].classList.contains('is-active')).toBe(true)

    key(el, { key: 'Enter' })
    await settle()

    expect(el.value).toBe('/clear ')
    expect(transport.sendMessage).not.toHaveBeenCalled()
    el.remove()
  })

  it('leaves Enter alone while an IME candidate is open', async () => {
    // Enter has three meanings in this input. Committing a Chinese candidate
    // must never be read as picking a command.
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })
    el.value = '/c'
    await settle()

    const event = key(el, { key: 'Enter', keyCode: 229 })
    await settle()

    expect(event.defaultPrevented).toBe(false)
    expect(el.value).toBe('/c')
    el.remove()
  })

  it('closes on Escape and reopens when the user keeps typing', async () => {
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })

    el.value = '/c'
    await settle()
    key(el, { key: 'Escape' })
    await settle()
    expect(el.shadowRoot.querySelector('.dac-slash')).toBeNull()

    el.value = '/cl'
    await settle()
    expect(el.shadowRoot.querySelector('.dac-slash')).toBeTruthy()
    el.remove()
  })

  it('accepts a command by clicking it', async () => {
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })
    el.value = '/co'
    await settle()

    items(el)[1].dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }))
    await settle()

    expect(el.value).toBe('/cost ')
    el.remove()
  })
})
