import { describe, it, expect, beforeAll } from 'vitest'
import { defineAgentConversation, DEFAULT_TAG } from '../element.js'

// Every test needs a registered tag, and registration is global + irreversible
// within a JSDOM realm. Register once, then assert behaviour through instances.
beforeAll(() => {
  defineAgentConversation()
})

function mount(tag = DEFAULT_TAG, lightDom = '') {
  const host = document.createElement('div')
  host.innerHTML = `<${tag}>${lightDom}</${tag}>`
  document.body.appendChild(host)
  return host.firstElementChild
}

describe('defineAgentConversation', () => {
  it('is idempotent for the same tag name', () => {
    expect(() => defineAgentConversation()).not.toThrow()
    expect(() => defineAgentConversation()).not.toThrow()
    expect(customElements.get(DEFAULT_TAG)).toBeTypeOf('function')
  })

  it('registers an alias tag alongside the default one', () => {
    // A shared constructor would fail here with NotSupportedError, which is
    // exactly the regression this case exists to catch.
    expect(() => defineAgentConversation('dataagent-conversation-v2')).not.toThrow()
    expect(customElements.get('dataagent-conversation-v2')).toBeTypeOf('function')
    expect(customElements.get('dataagent-conversation-v2')).not.toBe(
      customElements.get(DEFAULT_TAG)
    )
  })

  it('returns the registered tag name', () => {
    expect(defineAgentConversation()).toBe(DEFAULT_TAG)
  })
})

describe('<dataagent-conversation> lifecycle', () => {
  it('attaches a shadow root on connect', () => {
    const el = mount()
    expect(el.shadowRoot).toBeTruthy()
    expect(el.shadowRoot.querySelector('.dac-root')).toBeTruthy()
  })

  it('survives being detached and re-inserted', () => {
    const el = mount()
    const parent = el.parentElement
    expect(() => {
      parent.removeChild(el)
      parent.appendChild(el)
    }).not.toThrow()
    expect(el.shadowRoot.querySelector('.dac-root')).toBeTruthy()
  })
})

describe('slot projection', () => {
  it('projects composer-actions light DOM into the composer footer', () => {
    const el = mount(
      DEFAULT_TAG,
      '<button slot="composer-actions" id="start-modeling">开始建模</button>'
    )

    const slot = el.shadowRoot.querySelector('.dac-composer-actions slot[name="composer-actions"]')
    expect(slot, 'composer-actions slot must exist inside the shadow root').toBeTruthy()

    const assigned = slot.assignedNodes({ flatten: true })
    const button = el.querySelector('#start-modeling')
    expect(assigned).toContain(button)
  })

  it('keeps projected buttons clickable by the host', () => {
    const el = mount(
      DEFAULT_TAG,
      '<button slot="composer-actions" id="clickable">开始建模</button>'
    )
    const button = el.querySelector('#clickable')

    let clicks = 0
    button.addEventListener('click', () => {
      clicks += 1
    })
    button.click()

    expect(clicks).toBe(1)
  })

  it('falls back to default content when the empty slot is unused', () => {
    const el = mount()
    const slot = el.shadowRoot.querySelector('slot[name="empty"]')
    expect(slot).toBeTruthy()
    // Without `flatten` this is the assigned set only; flattening would fold in
    // the fallback content and make the assertion vacuous.
    expect(slot.assignedNodes()).toHaveLength(0)
    expect(el.shadowRoot.querySelector('.dac-messages').textContent).toContain('暂无消息')
  })
})
