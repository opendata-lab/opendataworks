import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
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

/**
 * Wait for a node rather than for a fixed number of ticks.
 *
 * Reading blob text resolves on a microtask when `Blob.text()` exists and on a
 * macrotask through the FileReader fallback, and which one runs depends on the
 * jsdom build. Polling keeps the assertion about the rendered result instead of
 * about which route the environment happened to take.
 */
const waitFor = async (el, selector, attempts = 20) => {
  for (let i = 0; i < attempts; i += 1) {
    const found = el.shadowRoot.querySelector(selector)
    if (found) return found
    await settle(4)
  }
  return null
}

let created
let revoked

// jsdom's Blob predates Blob.text(). Every browser that can run a custom
// element has it, so this belongs here rather than as a branch in the SDK.
if (typeof Blob.prototype.text !== 'function') {
  Blob.prototype.text = function text() {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result ?? ''))
      reader.onerror = () => reject(reader.error)
      reader.readAsText(this)
    })
  }
}

beforeEach(() => {
  created = []
  revoked = []
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn((blob) => {
      const url = `blob:mock/${created.length}`
      created.push(blob)
      return url
    }),
    revokeObjectURL: vi.fn((url) => revoked.push(url)),
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const makeTransport = (over = {}) => ({
  loadConversation: vi.fn(async () => ({
    messages: [{
      id: 'a-1',
      role: 'assistant',
      content: '生成完毕。',
      attachments: [
        { name: '图表.png', rel_path: 'output/chart.png' },
        { name: '报告.html', rel_path: 'output/report.html' },
        { name: '明细.csv', rel_path: 'output/rows.csv' },
      ],
    }],
    run: null,
  })),
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

const previewButtons = (el) => [...el.shadowRoot.querySelectorAll('[data-action="preview"]')]

describe('attachment preview', () => {
  it('offers no preview when the host cannot read a file', async () => {
    const el = await mount(makeTransport())
    expect(previewButtons(el)).toHaveLength(0)
    el.remove()
  })

  it('offers preview only for the kinds it can actually show', async () => {
    // A CSV gets a download link and nothing else — a preview button that
    // opens "cannot preview this" is worse than no button.
    const el = await mount(makeTransport({ readFile: vi.fn(async () => new Blob(['x'])) }))
    expect(previewButtons(el)).toHaveLength(2)
    el.remove()
  })

  it('shows an image through an object URL and revokes it on close', async () => {
    // The blob stays alive until revoked; closing enough previews without this
    // leaks a session's worth of images.
    const readFile = vi.fn(async () => new Blob([new Uint8Array([1, 2, 3])], { type: 'image/png' }))
    const el = await mount(makeTransport({ readFile }))

    previewButtons(el)[0].click()
    const img = await waitFor(el, '.dac-preview-image')

    expect(readFile).toHaveBeenCalledWith('output/chart.png')
    expect(img.getAttribute('src')).toBe('blob:mock/0')

    el.shadowRoot.querySelector('.dac-preview-close').click()
    await settle()

    expect(revoked).toContain('blob:mock/0')
    expect(el.shadowRoot.querySelector('.dac-preview')).toBeNull()
    el.remove()
  })

  it('renders html in a frame with no privileges at all', async () => {
    // Generated HTML is content, not code. `sandbox=""` gives the document an
    // opaque origin and no scripting; allow-scripts plus allow-same-origin
    // together would be the same as not sandboxing it.
    const readFile = vi.fn(async () => new Blob(['<h1>报告</h1>'], { type: 'text/html' }))
    const el = await mount(makeTransport({ readFile }))

    previewButtons(el)[1].click()
    const frame = await waitFor(el, '.dac-preview-frame')
    expect(frame.getAttribute('sandbox')).toBe('')
    expect(frame.getAttribute('srcdoc')).toContain('<h1>报告</h1>')
    el.remove()
  })

  it('keeps the download link available while previewing', async () => {
    const el = await mount(makeTransport({ readFile: vi.fn(async () => new Blob(['x'])) }))
    previewButtons(el)[0].click()
    await waitFor(el, '.dac-preview-download')

    expect(el.shadowRoot.querySelector('.dac-preview-download').getAttribute('href'))
      .toBe('/files/output/chart.png')
    el.remove()
  })

  it('reports a read failure instead of showing a blank frame', async () => {
    const el = await mount(makeTransport({
      readFile: vi.fn(async () => { throw new Error('文件已过期') })
    }))

    previewButtons(el)[0].click()
    await waitFor(el, '.dac-preview-error')

    expect(el.shadowRoot.querySelector('.dac-preview-error').textContent).toContain('文件已过期')
    el.remove()
  })
})
