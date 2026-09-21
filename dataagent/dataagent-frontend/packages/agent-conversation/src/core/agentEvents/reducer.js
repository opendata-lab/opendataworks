/**
 * Neutral Pi AgentEvent -> the same chat blocks the SDK stream produces.
 *
 * The Pi data plane writes `record_type: "pi_event"` rows into the same
 * da_agent_sdk_record table as the SDK path. Rendering must not be able to tell
 * which engine ran a turn, so this reducer deliberately produces the *identical*
 * block shape as `v2StreamParser` — `output`/`is_error`/`inputJson`, not
 * `result`/`error`. The render adapter `blockToToolProp` reads `block.output`
 * and `block.is_error`; a block that used different names would render every
 * Pi tool call as a permanently pending call with no output.
 *
 * That equality is not a convention to remember: the shared fixture at
 * dataagent/contracts/sdk-block-projection/cases.json carries "pi " cases whose
 * expected output is byte-identical to their SDK counterparts, and both this
 * reducer and the backend projection are held to it.
 */

/** Neutral event types, mirroring dataagent/contracts/agent-events/v1. */
export const AgentEventType = Object.freeze({
  RUN_STARTED: 'run.started',
  TURN_STARTED: 'turn.started',
  CONTENT_DELTA: 'content.delta',
  CONTENT_STARTED: 'content.started',
  CONTENT_COMPLETED: 'content.completed',
  TOOL_STARTED: 'tool.started',
  TOOL_COMPLETED: 'tool.completed',
  TOOL_DENIED: 'tool.denied',
  USAGE_UPDATED: 'usage.updated',
  TURN_COMPLETED: 'turn.completed',
  RUN_COMPLETED: 'run.completed',
  RUN_FAILED: 'run.failed',
  RUN_CANCELLED: 'run.cancelled',
  RUN_SUSPENDED: 'run.suspended',
})

const TERMINAL_TYPES = new Set([
  AgentEventType.RUN_COMPLETED,
  AgentEventType.RUN_FAILED,
  AgentEventType.RUN_CANCELLED,
  AgentEventType.RUN_SUSPENDED,
])

/**
 * Apply one `pi_event` record to the chat state.
 *
 * @param {object} state  Created by createChatState() in v2StreamParser
 * @param {object} record { record_type: 'pi_event', event_type, data }
 */
export function reducePiEvent(state, record) {
  const type = String(record.event_type || '')
  const data = record.data || {}

  if (!state._piContentBlocks) {
    // Pi addresses content blocks by an opaque content_id rather than the SDK's
    // integer index, so it needs its own lookup. Underscore-prefixed so the
    // canonical projection drops it, exactly as the backend does with _idx.
    state._piContentBlocks = {}
  }
  if (!state._piTurns) state._piTurns = {}

  switch (type) {
    case AgentEventType.RUN_STARTED: {
      state.status = 'streaming'
      break
    }

    case AgentEventType.TURN_STARTED: {
      const turnId = String(data.turn_id || '')
      const turn = { turnIndex: state.turns.length, blocks: [], status: 'streaming' }
      Object.defineProperty(turn, '_turnId', { value: turnId, writable: true, configurable: true, enumerable: false })
      state.turns.push(turn)
      if (turnId) state._piTurns[turnId] = turn
      state._piContentBlocks = {}
      break
    }

    case AgentEventType.CONTENT_STARTED:
    case AgentEventType.CONTENT_DELTA: {
      const turn = _turnForEvent(state, data)
      if (!turn) break
      const isReasoning = String(data.kind || '') === 'reasoning'
      // 'text', not the backend's 'main_text': the two projections use
      // different names for this block and the shared fixture normalizes each
      // to the same canonical kind.
      const blockType = isReasoning ? 'thinking' : 'text'
      const key = _contentKey(turn, data)
      let block = key ? state._piContentBlocks[key] : null
      if (!block || block.type !== blockType) {
        block = _newBlock(turn, blockType)
        turn.blocks.push(block)
        state.blocks.push(block)
        if (key) state._piContentBlocks[key] = block
      }
      if (type === AgentEventType.CONTENT_DELTA) {
        block.content += String(data.delta || '')
      }
      break
    }

    case AgentEventType.CONTENT_COMPLETED: {
      const turn = _turnForEvent(state, data)
      if (!turn) break
      const blockType = String(data.kind || '') === 'reasoning' ? 'thinking' : 'text'
      const key = _contentKey(turn, data)
      let block = key ? state._piContentBlocks[key] : null
      if (!block || block.type !== blockType) {
        block = _newBlock(turn, blockType)
        turn.blocks.push(block)
        state.blocks.push(block)
        if (key) state._piContentBlocks[key] = block
      }
      if (Object.prototype.hasOwnProperty.call(data, 'text')) {
        block.content = String(data.text || '')
      }
      block.status = 'done'
      break
    }

    case AgentEventType.TOOL_STARTED: {
      const turn = _turnForEvent(state, data)
      if (!turn) break
      const block = _newBlock(turn, 'tool_use')
      block.id = String(data.tool_call_id || '')
      block.name = String(data.tool_name || 'Tool')
      block.input = data.input ?? null
      turn.blocks.push(block)
      state.blocks.push(block)
      break
    }

    case AgentEventType.TOOL_COMPLETED: {
      const block = _findToolBlock(state, data.tool_call_id)
      if (block) {
        block.output = data.output ?? null
        block.is_error = Boolean(data.is_error)
        block.status = 'done'
      }
      break
    }

    case AgentEventType.TOOL_DENIED: {
      const block = _findToolBlock(state, data.tool_call_id)
      if (block) {
        block.output = String(data.reason || '工具调用被工作区边界策略拒绝')
        block.is_error = true
        block.status = 'done'
      }
      break
    }

    case AgentEventType.USAGE_UPDATED: {
      state.usage = data.usage ?? state.usage
      break
    }

    case AgentEventType.TURN_COMPLETED: {
      _finishTurn(_turnForEvent(state, data, false))
      break
    }

    case AgentEventType.RUN_FAILED: {
      state.status = 'error'
      state.errorText = String(data.message || data.error_code || '执行出错')
      break
    }

    default:
      // Unknown types are ignored on purpose. Records written before
      // tool.progress was removed still carry it, and a stored event must never
      // be able to break replay of the events around it.
      break
  }

  if (TERMINAL_TYPES.has(type)) {
    // A suspended run is terminal too: leaving it 'streaming' would spin the
    // UI forever waiting for events that will never arrive.
    if (type !== AgentEventType.RUN_FAILED) {
      state.status = 'done'
    }
    for (const block of state.blocks) {
      if (block.status === 'streaming') block.status = 'done'
    }
  }
}

function _currentTurn(state) {
  if (!state.turns.length) {
    // Tolerate a missing turn.started so a dropped event costs one grouping,
    // not the whole answer.
    const turn = { turnIndex: 0, blocks: [], status: 'streaming' }
    Object.defineProperty(turn, '_turnId', { value: '', writable: true, configurable: true, enumerable: false })
    state.turns.push(turn)
  }
  return state.turns[state.turns.length - 1]
}

/**
 * A block carrying every field the SDK path's blocks carry.
 *
 * turnIndex and blockIndex are not cosmetic: the chat template keys its v-for
 * on `block.blockIndex + '-' + ti` and derives the thinking-panel toggle id from
 * it. Omitting them makes every block in a turn share the key "undefined-0",
 * which makes Vue reuse the wrong DOM nodes and makes one thinking block's
 * toggle expand all of them.
 */
function _newBlock(turn, type) {
  return {
    turnIndex: turn.turnIndex ?? 0,
    blockIndex: turn.blocks.length,
    type,
    content: '',
    status: 'streaming',
    id: null,
    name: null,
    inputJson: '',
    input: null,
    output: null,
    is_error: false,
  }
}

function _turnForEvent(state, data, create = true) {
  const turnId = String(data?.turn_id || '')
  if (turnId && state._piTurns?.[turnId]) return state._piTurns[turnId]
  if (!create) return turnId ? null : state.turns.at(-1)
  const turn = _currentTurn(state)
  if (turnId && !turn._turnId) {
    turn._turnId = turnId
    state._piTurns[turnId] = turn
  }
  return turn
}

function _contentKey(turn, data) {
  const contentId = String(data?.content_id || '')
  return contentId ? `${String(turn?._turnId || data?.turn_id || '')}:${contentId}` : ''
}

function _finishTurn(turn) {
  if (!turn) return
  turn.status = 'done'
  for (const block of turn.blocks || []) {
    if (block.status === 'streaming') block.status = 'done'
  }
}

function _findToolBlock(state, toolCallId) {
  const id = String(toolCallId || '')
  if (!id) return null
  for (let i = state.blocks.length - 1; i >= 0; i -= 1) {
    const block = state.blocks[i]
    if (block.type === 'tool_use' && block.id === id) return block
  }
  return null
}
