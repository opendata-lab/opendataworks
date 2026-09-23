import { describe, it, expect, vi, beforeAll } from 'vitest'
import { nextTick } from 'vue'
import { defineAgentConversation, DEFAULT_TAG } from '../element.js'
import { buildCommands } from '../core/slashCommands.js'

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
  // The exact shapes NL2SqlChatV2 already holds: `settings.providers` from the
  // runtime config, PERMISSION_MODE_OPTIONS, and buildCommands() output. A host
  // passes what it has; nothing here needs an adapter.
  providers: [
    { provider_id: 'anthropic', models: ['sonnet', 'opus'] },
    { provider_id: 'deepseek', models: ['v4-pro'] }
  ],
  permissionModes: [
    { value: 'default', label: 'Default', desc: '写操作前确认' },
    { value: 'bypassPermissions', label: 'Bypass permissions' }
  ],
  slashCommands: buildCommands(['compact', 'clear', 'cost']),
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

/**
 * Type into the composer the way a person does.
 *
 * The menu opens on input, not on the draft changing: setting `element.value`
 * from host code is not typing, and should not pop a command menu over the
 * conversation.
 */
const type = async (el, text) => {
  const input = el.shadowRoot.querySelector('.dac-input')
  input.value = text
  input.dispatchEvent(new Event('input'))
  await settle()
}

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
    select.value = 'deepseek::v4-pro'
    select.dispatchEvent(new Event('change'))
    await settle()

    await el.sendMessage('开始建模')
    await settle()

    expect(transport.sendMessage).toHaveBeenCalledWith(expect.objectContaining({
      content: '开始建模',
      settings: { provider_id: 'deepseek', model: 'v4-pro', permission_mode: 'default' }
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
      provider_id: 'anthropic', model: 'sonnet', permission_mode: 'default'
    })
    el.remove()
  })

  it('uses the runtime configured provider and model instead of array order', async () => {
    const transport = makeTransport()
    const el = await mount({
      transportFactory: () => transport,
      composerConfig: {
        ...CONFIG,
        default_provider_id: 'deepseek',
        default_model: 'v4-pro'
      }
    })

    await el.sendMessage('你好')
    await settle()

    expect(transport.sendMessage.mock.calls[0][0].settings).toEqual({
      provider_id: 'deepseek', model: 'v4-pro', permission_mode: 'default'
    })
    el.remove()
  })

  it('excludes disabled runtime providers before choosing the default', async () => {
    const transport = makeTransport()
    const el = await mount({
      transportFactory: () => transport,
      composerConfig: {
        providers: [
          { provider_id: 'disabled', models: ['old-model'], enabled: false },
          { provider_id: 'ready', models: ['live-model'], enabled: true },
        ],
        default_provider_id: 'disabled',
        default_model: 'old-model',
      }
    })

    const options = [...el.shadowRoot.querySelectorAll('[data-control="model"] option')]
    expect(options.map((option) => option.value)).toEqual(['ready::live-model'])

    await el.sendMessage('你好')
    await settle()

    expect(transport.sendMessage).toHaveBeenCalledWith(expect.objectContaining({
      settings: { provider_id: 'ready', model: 'live-model' }
    }))
    el.remove()
  })

  it('blocks suggestions, typing and programmatic send when an explicit provider list has no model', async () => {
    const transport = makeTransport()
    const el = await mount({
      transportFactory: () => transport,
      composerConfig: {
        providers: [{ provider_id: 'disabled', models: ['old-model'], enabled: false }],
        suggestions: ['分析订单'],
      }
    })

    expect(el.shadowRoot.querySelector('.dac-input').disabled).toBe(true)
    expect(el.shadowRoot.querySelector('.dac-send').disabled).toBe(true)
    expect(el.shadowRoot.querySelector('.dac-suggestion').disabled).toBe(true)

    await el.sendMessage('不应发送')
    await settle()

    expect(transport.sendMessage).not.toHaveBeenCalled()
    el.remove()
  })

  it('still sends when providers are omitted for a fixed-model host', async () => {
    const transport = makeTransport()
    const el = await mount({ transportFactory: () => transport, composerConfig: {} })

    expect(el.shadowRoot.querySelector('.dac-input').disabled).toBe(false)
    await el.sendMessage('使用宿主固定模型')
    await settle()

    expect(transport.sendMessage).toHaveBeenCalledWith(expect.objectContaining({
      content: '使用宿主固定模型'
    }))
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
    await type(el, '/comp')

    expect(el.shadowRoot.querySelector('.dac-slash')).toBeNull()
    el.remove()
  })

  it('filters as the user types', async () => {
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })

    await type(el, '/c')
    expect(items(el).map((n) => n.textContent)).toHaveLength(3)

    await type(el, '/co')
    expect(items(el)).toHaveLength(2)

    await type(el, '/comp')
    expect(items(el)).toHaveLength(1)
    el.remove()
  })

  it('does not open for prose that merely contains a slash', async () => {
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })
    await type(el, '统计 2026/09 的订单')

    expect(el.shadowRoot.querySelector('.dac-slash')).toBeNull()
    el.remove()
  })

  it('moves through the list and accepts with Enter instead of sending', async () => {
    const transport = makeTransport()
    const el = await mount({ transportFactory: () => transport, composerConfig: CONFIG })

    await type(el, '/c')
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
    await type(el, '/c')

    const event = key(el, { key: 'Enter', keyCode: 229 })
    await settle()

    expect(event.defaultPrevented).toBe(false)
    expect(el.value).toBe('/c')
    el.remove()
  })

  it('closes on Escape and reopens when the user keeps typing', async () => {
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })

    await type(el, '/c')
    key(el, { key: 'Escape' })
    await settle()
    expect(el.shadowRoot.querySelector('.dac-slash')).toBeNull()

    await type(el, '/cl')
    expect(el.shadowRoot.querySelector('.dac-slash')).toBeTruthy()
    el.remove()
  })

  it('accepts a command by clicking it', async () => {
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })
    await type(el, '/co')

    items(el)[1].dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }))
    await settle()

    expect(el.value).toBe('/cost ')
    el.remove()
  })
})

describe('slash behaviour matches the shells it replaces', () => {
  it('matches anywhere in the name, not just the start', async () => {
    // The existing menu filters by case-insensitive substring. A prefix-only
    // match silently drops commands a user is used to finding by their middle
    // (`/opendataworks-platform-tools` typed as "platform").
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })
    await type(el, '/ost')

    expect(items(el)).toHaveLength(1)
    expect(items(el)[0].textContent).toContain('/cost')
    el.remove()
  })

  it('accepts with Tab as well as Enter', async () => {
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })
    await type(el, '/comp')
    key(el, { key: 'Tab' })
    await settle()

    expect(el.value).toBe('/compact ')
    el.remove()
  })

  it('follows the pointer, so clicking picks what is highlighted', async () => {
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })
    await type(el, '/c')

    items(el)[2].dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }))
    await settle()
    expect(items(el)[2].classList.contains('is-active')).toBe(true)

    key(el, { key: 'Enter' })
    await settle()
    expect(el.value).toBe('/cost ')
    el.remove()
  })

  it('shows the hint the shells show for built-ins', async () => {
    const el = await mount({ transportFactory: () => makeTransport(), composerConfig: CONFIG })
    await type(el, '/compact')

    expect(items(el)[0].textContent).toContain('压缩对话历史')
    el.remove()
  })
})

describe('permission mode', () => {
  it('persists the choice when the host can store it', async () => {
    const setPermissionMode = vi.fn(async () => {})
    const transport = makeTransport({ setPermissionMode })
    const el = await mount({ transportFactory: () => transport, composerConfig: CONFIG })

    const select = el.shadowRoot.querySelector('[data-control="permission-mode"]')
    select.value = 'bypassPermissions'
    select.dispatchEvent(new Event('change'))
    await settle()

    expect(setPermissionMode).toHaveBeenCalledWith('bypassPermissions')
    expect(select.value).toBe('bypassPermissions')
    el.remove()
  })

  it('rolls back when the save fails', async () => {
    // The shell showed a toast and kept the new value on screen, so the picker
    // claimed a mode the server had not accepted and the next run quietly used
    // the old one.
    const transport = makeTransport({
      setPermissionMode: vi.fn(async () => { throw new Error('保存失败') })
    })
    const errors = []
    const el = await mount({ transportFactory: () => transport, composerConfig: CONFIG })
    el.addEventListener('dataagent-error', (event) => errors.push(event.detail))

    const select = el.shadowRoot.querySelector('[data-control="permission-mode"]')
    select.value = 'bypassPermissions'
    select.dispatchEvent(new Event('change'))
    await settle()

    expect(el.shadowRoot.querySelector('[data-control="permission-mode"]').value).toBe('default')
    expect(errors).toHaveLength(1)
    el.remove()
  })

  it('shows the mode this conversation was saved with', async () => {
    // Switching conversations must not leave the previous one's mode selected.
    const el = await mount({
      transportFactory: () => makeTransport(),
      composerConfig: { ...CONFIG, permissionMode: 'bypassPermissions' }
    })

    expect(el.shadowRoot.querySelector('[data-control="permission-mode"]').value)
      .toBe('bypassPermissions')
    el.remove()
  })

  it('still works when the host cannot persist it', async () => {
    const transport = makeTransport()
    const el = await mount({ transportFactory: () => transport, composerConfig: CONFIG })

    const select = el.shadowRoot.querySelector('[data-control="permission-mode"]')
    select.value = 'bypassPermissions'
    select.dispatchEvent(new Event('change'))
    await settle()

    await el.sendMessage('开始')
    await settle()

    expect(transport.sendMessage.mock.calls[0][0].settings.permission_mode)
      .toBe('bypassPermissions')
    el.remove()
  })
})

describe('textarea auto-resize', () => {
  it('starts at rows=1 and auto-resizes up to 160px', async () => {
    const el = await mount({ transportFactory: () => makeTransport() })
    const textarea = el.shadowRoot.querySelector('.dac-input')

    expect(textarea.getAttribute('rows')).toBe('1')

    // Simulate multi-line input expanding scrollHeight
    Object.defineProperty(textarea, 'scrollHeight', { value: 120, configurable: true, writable: true })
    textarea.value = 'line 1\nline 2\nline 3'
    textarea.dispatchEvent(new Event('input'))
    await settle()

    expect(textarea.style.height).toBe('120px')

    // Very long input capped at 160px
    textarea.scrollHeight = 300
    textarea.dispatchEvent(new Event('input'))
    await settle()

    expect(textarea.style.height).toBe('160px')

    // Cleared content shrinks back down
    textarea.scrollHeight = 38
    textarea.value = ''
    textarea.dispatchEvent(new Event('input'))
    await settle()

    expect(textarea.style.height).toBe('38px')

    el.remove()
  })
})
