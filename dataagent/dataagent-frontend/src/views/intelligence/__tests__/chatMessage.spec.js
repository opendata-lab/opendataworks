import { describe, expect, it } from 'vitest'

import {
  buildV2StateFromStoredBlocks,
  compareTopicsByRecency,
  extractErrorText,
  hydrateMessageFromApi,
  getMessageCopyText,
  isPlainEnterSubmit,
  normalizeTopic,
  renderMarkdown,
} from '../chatMessage'

describe('chatMessage helpers', () => {
  it('escapes HTML before rendering markdown', () => {
    const html = renderMarkdown('<img src=x onerror=alert(1)> **bold**')
    expect(html).not.toContain('<img')
    expect(html).toContain('&lt;img')
    expect(html).toContain('<strong>bold</strong>')
  })

  it('isPlainEnterSubmit only submits on plain Enter (guards IME and modifiers)', () => {
    expect(isPlainEnterSubmit({})).toBe(true)
    expect(isPlainEnterSubmit({ isComposing: true })).toBe(false)
    expect(isPlainEnterSubmit({ keyCode: 229 })).toBe(false)
    expect(isPlainEnterSubmit({ shiftKey: true })).toBe(false)
    expect(isPlainEnterSubmit({ ctrlKey: true })).toBe(false)
    expect(isPlainEnterSubmit({ altKey: true })).toBe(false)
    expect(isPlainEnterSubmit({ metaKey: true })).toBe(false)
    expect(isPlainEnterSubmit(null)).toBe(false)
  })

  it('extractErrorText reads strings, objects, and falls back to empty', () => {
    expect(extractErrorText('boom')).toBe('boom')
    expect(extractErrorText({ message: 'm' })).toBe('m')
    expect(extractErrorText({ detail: 'd' })).toBe('d')
    expect(extractErrorText({ code: 'c' })).toBe('c')
    expect(extractErrorText(null)).toBe('')
    expect(extractErrorText({})).toBe('')
  })

  it('normalizeTopic coerces fields and defaults the title', () => {
    const t = normalizeTopic({ topic_id: 't1', message_count: '3' })
    expect(t).toMatchObject({ topic_id: 't1', title: '新话题', message_count: 3 })
    expect(typeof t.created_at).toBe('string')
    expect(typeof t.updated_at).toBe('string')
  })

  it('compareTopicsByRecency sorts most-recent first', () => {
    const list = [
      { topic_id: 'a', updated_at: '2026-01-01T00:00:00' },
      { topic_id: 'b', updated_at: '2026-03-01T00:00:00' },
      { topic_id: 'c', created_at: '2026-02-01T00:00:00' },
    ]
    expect(list.slice().sort(compareTopicsByRecency).map((t) => t.topic_id)).toEqual(['b', 'c', 'a'])
  })

  it('hydrates a user message without _v2state', () => {
    const m = hydrateMessageFromApi({ sender_type: 'user', message_id: 'u1', content: '你好' })
    expect(m).toMatchObject({ id: 'u1', role: 'user', content: '你好', _v2state: null })
  })

  it('hydrates a user message content from blocks when content is empty', () => {
    const m = hydrateMessageFromApi({ role: 'user', blocks: [{ text: 'a' }, { output: 'b' }] })
    expect(m.content).toBe('a\nb')
  })

  it('builds copy text from v2 text blocks and strips display-only chart specs', () => {
    const msg = {
      content: 'fallback',
      _v2state: {
        turns: [
          {
            blocks: [
              { type: 'thinking', content: 'internal reasoning' },
              { type: 'text', content: '结论\n```chart\n{"kind":"chart_spec"}\n```' },
              { type: 'text', content: '补充说明' },
            ],
          },
        ],
      },
    }

    const text = getMessageCopyText(msg, {
      cleanText: (value) => String(value).replace(/```chart[\s\S]*?```/g, '').trim(),
    })

    expect(text).toBe('结论\n\n补充说明')
  })

  it('hydrates an assistant message with reconstructed turns and superset fields', () => {
    const m = hydrateMessageFromApi({
      sender_type: 'assistant',
      message_id: 'a1',
      content: '结果如下',
      task_id: 'task-1',
      feedback: 'up',
      blocks: [
        { kind: 'thinking', text: '想一下' },
        { kind: 'tool_use', tool_id: 'x', tool_name: 'run-sql', output: 'ok' },
        { kind: 'main_text', text: '结果如下' },
      ],
    })
    expect(m).toMatchObject({ id: 'a1', role: 'assistant', task_id: 'task-1', feedback: 'up' })
    const blocks = m._v2state.turns[0].blocks
    expect(blocks.map((b) => b.type)).toEqual(['thinking', 'tool_use', 'text'])
    expect(blocks[1]).toMatchObject({ name: 'run-sql', output: 'ok' })
  })

  it('ignores legacy magic-event nested tool blocks when hydrating stored blocks', () => {
    const v2 = buildV2StateFromStoredBlocks({
      blocks: [
        { kind: 'tool', tool: { id: 'legacy-tool', name: 'Bash', output: 'old' } },
      ],
      content: '最终回答',
    })

    expect(v2.turns[0].blocks.map((block) => block.type)).toEqual(['text'])
    expect(v2.turns[0].blocks[0].content).toBe('最终回答')
  })

  it('surfaces a persisted error through _v2state', () => {
    const v2 = buildV2StateFromStoredBlocks({ status: 'error', error: { message: '模型异常' } })
    expect(v2.status).toBe('error')
    expect(v2.errorText).toBe('模型异常')
  })

  it('falls back to default error copy when the error object is empty', () => {
    const v2 = buildV2StateFromStoredBlocks({ status: 'error', error: {} })
    expect(v2.errorText).toBe('会话执行失败')
  })
})
