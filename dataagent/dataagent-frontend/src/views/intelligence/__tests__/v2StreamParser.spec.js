import { describe, it, expect } from 'vitest'
import { createChatState, processV2Record, blockToToolProp } from '../v2StreamParser'

// Helpers to build the SSE records the backend emits from /sdk-events/stream.
const stream = (data) => ({ record_type: 'stream', data })
const toolResult = (data) => ({ record_type: 'tool_result', data })

function feed(state, records) {
  for (const record of records) processV2Record(state, record)
  return state
}

describe('createChatState', () => {
  it('returns an empty idle state', () => {
    expect(createChatState()).toEqual({
      turns: [],
      blocks: [],
      status: 'idle',
      usage: null,
      errorText: null,
    })
  })
})

describe('processV2Record — stream lifecycle', () => {
  it('message_start opens a streaming turn', () => {
    const state = feed(createChatState(), [stream({ type: 'message_start' })])
    expect(state.status).toBe('streaming')
    expect(state.turns).toHaveLength(1)
    expect(state.turns[0]).toMatchObject({ turnIndex: 0, status: 'streaming', blocks: [] })
  })

  it('ignores block events that arrive before any turn', () => {
    const state = feed(createChatState(), [
      stream({ type: 'content_block_start', index: 0, content_block: { type: 'text' } }),
    ])
    expect(state.turns).toHaveLength(0)
    expect(state.blocks).toHaveLength(0)
  })

  it('streams a text block via content_block_start/delta/stop', () => {
    const state = feed(createChatState(), [
      stream({ type: 'message_start' }),
      stream({ type: 'content_block_start', index: 0, content_block: { type: 'text' } }),
      stream({ type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'Hello ' } }),
      stream({ type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'world' } }),
      stream({ type: 'content_block_stop', index: 0 }),
    ])
    const [block] = state.blocks
    expect(block).toMatchObject({
      type: 'text',
      turnIndex: 0,
      blockIndex: 0,
      content: 'Hello world',
      status: 'done',
    })
    // The block is shared between the flat list and the turn.
    expect(state.turns[0].blocks[0]).toBe(block)
  })

  it('accumulates thinking_delta into a thinking block', () => {
    const state = feed(createChatState(), [
      stream({ type: 'message_start' }),
      stream({ type: 'content_block_start', index: 0, content_block: { type: 'thinking' } }),
      stream({ type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: 'step 1 ' } }),
      stream({ type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: 'step 2' } }),
    ])
    expect(state.blocks[0]).toMatchObject({ type: 'thinking', content: 'step 1 step 2', status: 'streaming' })
  })

  it('parses tool_use input JSON on content_block_stop', () => {
    const state = feed(createChatState(), [
      stream({ type: 'message_start' }),
      stream({ type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'tu_1', name: 'run_sql' } }),
      stream({ type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '{"sql":' } }),
      stream({ type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '"select 1"}' } }),
      stream({ type: 'content_block_stop', index: 0 }),
    ])
    expect(state.blocks[0]).toMatchObject({
      type: 'tool_use',
      id: 'tu_1',
      name: 'run_sql',
      input: { sql: 'select 1' },
      status: 'done',
    })
  })

  it('falls back to raw inputJson string when the JSON is malformed', () => {
    const state = feed(createChatState(), [
      stream({ type: 'message_start' }),
      stream({ type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'tu_1', name: 'run_sql' } }),
      stream({ type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '{bad json' } }),
      stream({ type: 'content_block_stop', index: 0 }),
    ])
    expect(state.blocks[0].input).toBe('{bad json')
  })

  it('captures usage on message_delta and closes the turn on message_stop', () => {
    const usage = { input_tokens: 10, output_tokens: 20 }
    const state = feed(createChatState(), [
      stream({ type: 'message_start' }),
      stream({ type: 'message_delta', usage }),
      stream({ type: 'message_stop' }),
    ])
    expect(state.usage).toEqual(usage)
    expect(state.turns[0].status).toBe('done')
  })

  it('keeps blocks separate across multiple turns', () => {
    const state = feed(createChatState(), [
      stream({ type: 'message_start' }),
      stream({ type: 'content_block_start', index: 0, content_block: { type: 'text' } }),
      stream({ type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'a' } }),
      stream({ type: 'message_stop' }),
      stream({ type: 'message_start' }),
      stream({ type: 'content_block_start', index: 0, content_block: { type: 'text' } }),
      stream({ type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'b' } }),
    ])
    expect(state.turns).toHaveLength(2)
    expect(state.turns[0].blocks[0]).toMatchObject({ turnIndex: 0, content: 'a' })
    expect(state.turns[1].blocks[0]).toMatchObject({ turnIndex: 1, content: 'b' })
    expect(state.blocks).toHaveLength(2)
  })
})

describe('processV2Record — tool_result', () => {
  it('attaches output and error flag to the matching tool_use block', () => {
    const state = feed(createChatState(), [
      stream({ type: 'message_start' }),
      stream({ type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'tu_1', name: 'run_sql' } }),
      stream({ type: 'content_block_stop', index: 0 }),
      toolResult({ tool_use_id: 'tu_1', content: [{ type: 'text', text: 'rows: 3' }], is_error: false }),
    ])
    expect(state.blocks[0].output).toEqual([{ type: 'text', text: 'rows: 3' }])
    expect(state.blocks[0].is_error).toBe(false)
  })

  it('marks the block as errored when is_error is set', () => {
    const state = feed(createChatState(), [
      stream({ type: 'message_start' }),
      stream({ type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'tu_1', name: 'run_sql' } }),
      toolResult({ tool_use_id: 'tu_1', content: 'boom', is_error: true }),
    ])
    expect(state.blocks[0]).toMatchObject({ output: 'boom', is_error: true })
  })

  it('creates a synthetic tool block for a tool_result with no matching block', () => {
    const state = feed(createChatState(), [
      toolResult({ tool_use_id: 'call_skill_1', content: 'Launching skill: opendataworks-business-knowledge' }),
    ])
    expect(state.blocks).toHaveLength(1)
    expect(state.blocks[0]).toMatchObject({
      type: 'tool_use',
      id: 'call_skill_1',
      name: 'Skill',
      input: { skill: 'opendataworks-business-knowledge' },
      output: 'Launching skill: opendataworks-business-knowledge',
      status: 'done',
    })
  })
})

describe('processV2Record — terminal records', () => {
  it('done marks the state done', () => {
    const state = feed(createChatState(), [stream({ type: 'message_start' }), { record_type: 'done', data: {} }])
    expect(state.status).toBe('done')
  })

  it('done with is_error marks the state errored', () => {
    const state = feed(createChatState(), [{ record_type: 'done', data: { is_error: true } }])
    expect(state.status).toBe('error')
  })

  it('error record records status and message', () => {
    const state = feed(createChatState(), [{ record_type: 'error', data: { message: 'timeout' } }])
    expect(state.status).toBe('error')
    expect(state.errorText).toBe('timeout')
  })

  it('error record falls back to a default message', () => {
    const state = feed(createChatState(), [{ record_type: 'error', data: {} }])
    expect(state.errorText).toBe('未知错误')
  })
})

describe('blockToToolProp', () => {
  const baseBlock = (over) => ({
    type: 'tool_use',
    id: 'tu_1',
    name: 'run_sql',
    input: { sql: 'select 1' },
    output: null,
    is_error: false,
    status: 'streaming',
    ...over,
  })

  it('maps a still-streaming tool call', () => {
    expect(blockToToolProp(baseBlock())).toMatchObject({
      name: 'run_sql',
      input: { sql: 'select 1' },
      status: 'streaming',
      _callComplete: false,
      _runtimeStarted: false,
    })
  })

  it('maps a finished call awaiting its result as pending', () => {
    expect(blockToToolProp(baseBlock({ status: 'done' }))).toMatchObject({
      status: 'pending',
      _callComplete: true,
      _runtimeStarted: false,
    })
  })

  it('maps a successful result', () => {
    expect(blockToToolProp(baseBlock({ status: 'done', output: 'rows: 3' }))).toMatchObject({
      status: 'success',
      _callComplete: true,
      _runtimeStarted: true,
    })
  })

  it('maps a failed result', () => {
    expect(blockToToolProp(baseBlock({ status: 'done', output: 'boom', is_error: true }))).toMatchObject({
      status: 'failed',
      _runtimeStarted: true,
    })
  })
})
