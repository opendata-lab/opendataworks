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

const mount = async (transport) => {
  const el = document.createElement(DEFAULT_TAG)
  Object.assign(el, { endpoint: '/conv/a', transportFactory: () => transport })
  document.body.appendChild(el)
  await settle()
  return el
}

/** Drive the hidden file input the way the browser does after a picker. */
const pickFiles = async (el, files) => {
  const input = el.shadowRoot.querySelector('input[type="file"]')
  Object.defineProperty(input, 'files', { value: files, configurable: true })
  input.dispatchEvent(new Event('change'))
  await settle()
}

const file = (name) => new File(['x'], name, { type: 'text/csv' })

const chips = (el) => [...el.shadowRoot.querySelectorAll('.dac-chip')]

describe('attachments', () => {
  it('offers no attach button when the host cannot store a file', async () => {
    const el = await mount(makeTransport())
    expect(el.shadowRoot.querySelector('[data-action="attach"]')).toBeNull()
    expect(el.shadowRoot.querySelector('input[type="file"]')).toBeNull()
    el.remove()
  })

  it('uploads through the transport and sends the references with the message', async () => {
    const uploadFiles = vi.fn(async (files) =>
      files.map((item) => ({ name: item.name, relPath: `uploads/${item.name}` }))
    )
    const transport = makeTransport({ uploadFiles })
    const el = await mount(transport)

    await pickFiles(el, [file('订单.csv')])
    expect(uploadFiles).toHaveBeenCalledTimes(1)
    expect(chips(el)[0].textContent).toContain('订单.csv')

    await el.sendMessage('看看这份数据')
    await settle()

    expect(transport.sendMessage).toHaveBeenCalledWith(expect.objectContaining({
      content: '看看这份数据',
      attachments: [{ name: '订单.csv', relPath: 'uploads/订单.csv' }]
    }))
    // Staged files belong to the message that carried them.
    expect(chips(el)).toHaveLength(0)
    el.remove()
  })

  it('keeps the files staged when the send fails', async () => {
    // Otherwise a failed send silently discards the upload and the user has to
    // pick every file again.
    const transport = makeTransport({
      uploadFiles: vi.fn(async (files) => files.map((f) => ({ name: f.name, relPath: `uploads/${f.name}` }))),
      sendMessage: vi.fn(async () => { throw new Error('网络中断') })
    })
    const el = await mount(transport)

    await pickFiles(el, [file('订单.csv')])
    await el.sendMessage('看看这份数据')
    await settle()

    expect(chips(el)).toHaveLength(1)
    el.remove()
  })

  it('lets a staged file be removed before sending', async () => {
    const transport = makeTransport({
      uploadFiles: vi.fn(async (files) => files.map((f) => ({ name: f.name, relPath: `uploads/${f.name}` })))
    })
    const el = await mount(transport)

    await pickFiles(el, [file('a.csv'), file('b.csv')])
    expect(chips(el)).toHaveLength(2)

    el.shadowRoot.querySelector('.dac-chip-remove').click()
    await settle()

    expect(chips(el)).toHaveLength(1)
    el.remove()
  })

  it('reports a failed upload without staging anything', async () => {
    const transport = makeTransport({
      uploadFiles: vi.fn(async () => { throw new Error('文件超过大小限制') })
    })
    const el = await mount(transport)

    await pickFiles(el, [file('big.csv')])

    expect(el.shadowRoot.querySelector('.dac-upload-error').textContent).toContain('大小限制')
    expect(chips(el)).toHaveLength(0)
    el.remove()
  })

  it('allows a message that is only attachments', async () => {
    const transport = makeTransport({
      uploadFiles: vi.fn(async (files) => files.map((f) => ({ name: f.name, relPath: `uploads/${f.name}` })))
    })
    const el = await mount(transport)

    await pickFiles(el, [file('订单.csv')])
    const send = el.shadowRoot.querySelector('.dac-send')

    expect(send.disabled).toBe(false)
    el.remove()
  })
})
