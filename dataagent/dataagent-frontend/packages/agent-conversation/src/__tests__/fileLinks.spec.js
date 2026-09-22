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

const mountWith = async (content, attachments = []) => {
  const el = document.createElement(DEFAULT_TAG)
  Object.assign(el, {
    endpoint: '/conv/a',
    transportFactory: () => ({
      loadConversation: vi.fn(async () => ({
        messages: [{ id: 'a-1', role: 'assistant', content, attachments }],
        run: null
      })),
      sendMessage: vi.fn(async () => ({ taskId: 't-1', status: 'running', detail: '' })),
      cancelRun: vi.fn(async () => ({ taskId: 't-1', status: 'cancelled', detail: '' })),
      submitInteraction: vi.fn(async () => {}),
      fileUrl: (relPath) => `/api/topics/t-1/files/${relPath}`,
      streamEvents: async function* () {}
    })
  })
  document.body.appendChild(el)
  await settle()
  return el
}

const hrefs = (el) => [...el.shadowRoot.querySelectorAll('a')].map((a) => a.getAttribute('href'))

describe('workspace file links', () => {
  it('rewrites a relative link the agent wrote into a download url', async () => {
    // The agent hands back deliverables as ordinary markdown links. Rendered
    // as-is they resolve against the host page and 404.
    const el = await mountWith('分析完成，见 [销售报告](output/report.xlsx)。')
    expect(hrefs(el)).toContain('/api/topics/t-1/files/output/report.xlsx')
    el.remove()
  })

  it('decodes a percent-encoded path before resolving it', async () => {
    const el = await mountWith('见 [月报](output/%E6%9C%88%E6%8A%A5.csv)。')
    expect(hrefs(el)).toContain('/api/topics/t-1/files/output/月报.csv')
    el.remove()
  })

  it('drops a leading ./ so the path matches what the workspace stores', async () => {
    const el = await mountWith('见 [图](./output/chart.png)。')
    expect(hrefs(el)).toContain('/api/topics/t-1/files/output/chart.png')
    el.remove()
  })

  it('leaves links that are not workspace files alone', async () => {
    // Absolute, host-rooted, fragment and non-http scheme links all have a
    // meaning of their own; rewriting them would break navigation and mail.
    const el = await mountWith([
      '[站外](https://example.com/a)',
      '[根路径](/dashboard)',
      '[锚点](#section)',
      '[邮件](mailto:ops@example.com)'
    ].join(' '))

    expect(hrefs(el)).toEqual([
      'https://example.com/a',
      '/dashboard',
      '#section',
      'mailto:ops@example.com'
    ])
    el.remove()
  })

  it('resolves history attachments through the same transport', async () => {
    const el = await mountWith('见附件。', [{ name: '明细.csv', rel_path: 'output/明细.csv' }])
    expect(hrefs(el)).toContain('/api/topics/t-1/files/output/明细.csv')
    el.remove()
  })
})
