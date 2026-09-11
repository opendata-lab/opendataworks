import { describe, it, expect } from 'vitest'
import { createChatState, processV2Record } from '../v2StreamParser'
import { buildV2StateFromStoredRecords, buildV2StateFromStoredBlocks } from '../chatMessage'

/**
 * History used to run a second, independently written projection. Replaying the
 * same records through the live reducer is what removes that divergence, so the
 * property worth pinning is that both paths agree.
 */
function records() {
  const base = (seq, event_type, data) => ({
    seq_id: seq, record_type: 'agent_event', event_type, data,
  })
  return [
    base(1, 'run.started', { topic_id: 't1' }),
    base(2, 'turn.started', { turn_id: 'turn-1' }),
    base(3, 'content.started', { turn_id: 'turn-1', content_id: 'c-0', kind: 'answer' }),
    base(4, 'content.delta', { turn_id: 'turn-1', content_id: 'c-0', kind: 'answer', delta: '你好' }),
    base(5, 'content.completed', { turn_id: 'turn-1', content_id: 'c-0', kind: 'answer', text: '你好' }),
    base(6, 'turn.completed', { turn_id: 'turn-1' }),
    base(7, 'run.completed', { terminal_status: 'success' }),
  ]
}

describe('history replay', () => {
  it('produces the same state as the live stream for the same records', () => {
    const live = createChatState()
    for (const r of records()) processV2Record(live, r)

    const replayed = buildV2StateFromStoredRecords({ records: records(), status: 'success' })

    expect(replayed.status).toBe(live.status)
    expect(replayed.blocks.map((b) => [b.type, b.content]))
      .toEqual(live.blocks.map((b) => [b.type, b.content]))
  })

  it('keeps multiple turns separate instead of flattening them', () => {
    // The old hydrator hardcoded turnIndex 0, so every turn of a run collapsed
    // into one.
    const twoTurns = [
      ...records(),
      { seq_id: 8, record_type: 'agent_event', event_type: 'turn.started', data: { turn_id: 'turn-2' } },
      { seq_id: 9, record_type: 'agent_event', event_type: 'content.started', data: { turn_id: 'turn-2', content_id: 'c-1', kind: 'answer' } },
      { seq_id: 10, record_type: 'agent_event', event_type: 'content.delta', data: { turn_id: 'turn-2', content_id: 'c-1', kind: 'answer', delta: '第二轮' } },
    ]
    const state = buildV2StateFromStoredRecords({ records: twoTurns, status: 'success' })
    expect(state.turns.length).toBeGreaterThan(1)
  })

  it('replays legacy pi_event rows identically to agent_event rows', () => {
    const asLegacy = records().map((r) => ({ ...r, record_type: 'pi_event' }))
    const legacy = buildV2StateFromStoredRecords({ records: asLegacy, status: 'success' })
    const current = buildV2StateFromStoredRecords({ records: records(), status: 'success' })
    expect(legacy.blocks.map((b) => b.content)).toEqual(current.blocks.map((b) => b.content))
  })

  it('never leaves a replayed message streaming', () => {
    // A record set that ends without a terminal event would otherwise spin the
    // UI forever on a message that finished long ago.
    const truncated = records().slice(0, 4)
    const state = buildV2StateFromStoredRecords({ records: truncated, status: 'success' })
    expect(state.status).not.toBe('streaming')
    expect(state.blocks.every((b) => b.status !== 'streaming')).toBe(true)
  })

  it('falls back to stored blocks for messages saved before records existed', () => {
    const item = { blocks: [{ kind: 'main_text', text: '旧消息' }], status: 'success' }
    const viaRecords = buildV2StateFromStoredRecords(item)
    const viaBlocks = buildV2StateFromStoredBlocks(item)
    expect(viaRecords.blocks.map((b) => b.content)).toEqual(viaBlocks.blocks.map((b) => b.content))
  })

  it('surfaces a failed run as an error, not a silent finish', () => {
    const state = buildV2StateFromStoredRecords({
      records: records().slice(0, 5),
      status: 'error',
      error: { message: '执行出错' },
    })
    expect(state.status).toBe('error')
    expect(state.errorText).toBeTruthy()
  })
})

describe('history replay — record kinds the old hydrator dropped', () => {
  const wrap = (seq, record_type, data, event_type = null) => ({
    seq_id: seq, record_type, event_type, data,
  })

  it('replays question_request, which block hydration dropped silently', () => {
    // The old hydrator recognised four kinds and ignored the rest, so a run
    // that asked the user something replayed as if it never had.
    const records = [
      wrap(1, 'agent_event', { topic_id: 't' }, 'run.started'),
      wrap(2, 'agent_event', { turn_id: 'turn-1' }, 'turn.started'),
      wrap(3, 'question_request', {
        request_id: 'q1', question: '选择数据库', options: ['a', 'b'],
      }),
    ]
    const live = createChatState()
    for (const r of records) processV2Record(live, r)
    const replayed = buildV2StateFromStoredRecords({ records, status: 'success' })

    expect(replayed.blocks.map((b) => b.type)).toEqual(live.blocks.map((b) => b.type))
  })

  it('replays the permission request/decision pair', () => {
    const records = [
      wrap(1, 'agent_event', { topic_id: 't' }, 'run.started'),
      wrap(2, 'permission_request', {
        request_id: 'p1', tool_name: 'Bash', risk_level: 'high', title: '执行命令',
      }),
      wrap(3, 'permission_decision', { request_id: 'p1', decision: 'approved' }),
    ]
    const live = createChatState()
    for (const r of records) processV2Record(live, r)
    const replayed = buildV2StateFromStoredRecords({ records, status: 'success' })

    expect(replayed.blocks.map((b) => [b.type, b.decision]))
      .toEqual(live.blocks.map((b) => [b.type, b.decision]))
  })

  it('tolerates an orphan tool.completed with no matching start', () => {
    // A crash between start and completion leaves one behind; dropping the
    // whole replay over it would lose the rest of the conversation.
    const records = [
      wrap(1, 'agent_event', { topic_id: 't' }, 'run.started'),
      wrap(2, 'agent_event', { turn_id: 'turn-1' }, 'turn.started'),
      wrap(3, 'agent_event', { tool_call_id: 'ghost', output: 'x', is_error: false }, 'tool.completed'),
      wrap(4, 'agent_event', { turn_id: 'turn-1', content_id: 'c-0', kind: 'answer', delta: '继续' }, 'content.delta'),
    ]
    const replayed = buildV2StateFromStoredRecords({ records, status: 'success' })
    expect(replayed.blocks.some((b) => b.content === '继续')).toBe(true)
  })

  it('tolerates content arriving without a turn.started', () => {
    const records = [
      wrap(1, 'agent_event', { topic_id: 't' }, 'run.started'),
      wrap(2, 'agent_event', { turn_id: 'turn-1', content_id: 'c-0', kind: 'answer', delta: '无 turn' }, 'content.delta'),
    ]
    const live = createChatState()
    for (const r of records) processV2Record(live, r)
    const replayed = buildV2StateFromStoredRecords({ records, status: 'success' })
    expect(replayed.blocks.map((b) => b.content)).toEqual(live.blocks.map((b) => b.content))
  })

  it('carries usage through replay', () => {
    const records = [
      wrap(1, 'agent_event', { topic_id: 't' }, 'run.started'),
      wrap(2, 'agent_event', { turn_id: 'turn-1' }, 'turn.started'),
      wrap(3, 'agent_event', {
        turn_id: 'turn-1', usage: { input_tokens: 10, output_tokens: 5 },
      }, 'usage.updated'),
      wrap(4, 'agent_event', { terminal_status: 'success' }, 'run.completed'),
    ]
    const live = createChatState()
    for (const r of records) processV2Record(live, r)
    const replayed = buildV2StateFromStoredRecords({ records, status: 'success' })
    expect(replayed.usage).toEqual(live.usage)
  })
})
