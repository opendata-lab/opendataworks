/**
 * v2StreamParser — parses native Anthropic SDK block events from the /sdk-events/stream endpoint.
 *
 * The backend writes StreamEvent.event dicts directly, so the frontend receives the Anthropic
 * streaming protocol without any intermediate "magic event" transformation:
 *
 *   message_start → content_block_start → content_block_delta… → content_block_stop
 *   → message_delta → message_stop
 *   tool_result record (after each UserMessage with tool results)
 *   done record (after ResultMessage)
 */

const SKILL_LAUNCH_OUTPUT_RE = /^Launching skill(?::\s*(.+))?$/i

/**
 * Create a fresh chat state for one assistant message.
 * One state object maps to one user question + all AI reply turns.
 */
export function createChatState() {
  return {
    /** All turns in this response (one turn = one message_start…message_stop cycle). */
    turns: [],
    /** Flat list of all blocks across all turns — convenient for looking up by id. */
    blocks: [],
    /** Final stream status: 'idle' | 'streaming' | 'done' | 'error' */
    status: 'idle',
    /** Token usage from the last message_delta. */
    usage: null,
    /** Error text if record_type === 'error'. */
    errorText: null,
  }
}

/**
 * Process one record from the /sdk-events/stream endpoint into the given state.
 * Mutates state in place (designed for Vue reactivity via reactive() or ref()).
 *
 * @param {object} state  Created by createChatState()
 * @param {object} record Raw SSE record parsed from JSON
 */
export function processV2Record(state, record) {
  if (record.record_type === 'stream') {
    _handleStreamEvent(state, record.data || {})
  } else if (record.record_type === 'tool_result') {
    _handleToolResult(state, record.data || {})
  } else if (record.record_type === 'permission_request') {
    _handlePermissionRequest(state, record.data || {})
  } else if (record.record_type === 'permission_decision') {
    _handlePermissionDecision(state, record.data || {})
  } else if (record.record_type === 'done') {
    state.status = (record.data || {}).is_error ? 'error' : 'done'
  } else if (record.record_type === 'error') {
    state.status = 'error'
    state.errorText = String((record.data || {}).message || '未知错误')
  }
}

function _handlePermissionRequest(state, data) {
  const currentTurn = state.turns.at(-1)
  const block = {
    turnIndex: currentTurn ? currentTurn.turnIndex : 0,
    blockIndex: currentTurn ? currentTurn.blocks.length : state.blocks.length,
    type: 'permission_request',
    content: '',
    status: 'done',
    requestId: data.request_id || '',
    tool_name: data.tool_name || '',
    risk_level: data.risk_level || 'high',
    title: data.title || '',
    summary: data.summary || '',
    payload_preview: data.payload_preview ?? null,
    decision: 'pending',
    note: '',
    decided_at: '',
  }
  if (currentTurn) currentTurn.blocks.push(block)
  state.blocks.push(block)
}

function _handlePermissionDecision(state, data) {
  const requestId = data.request_id || ''
  const block = state.blocks.find((b) => b.type === 'permission_request' && b.requestId === requestId)
  if (!block) return
  block.decision = data.decision || 'pending'
  block.note = data.note || ''
  block.decided_at = data.decided_at || ''
}

function _handleStreamEvent(state, evt) {
  const etype = evt.type
  if (!etype) return

  if (etype === 'message_start') {
    state.status = 'streaming'
    state.turns.push({ turnIndex: state.turns.length, blocks: [], status: 'streaming' })
    return
  }

  const currentTurn = state.turns.at(-1)
  if (!currentTurn) return

  if (etype === 'content_block_start') {
    const cb = evt.content_block || {}
    const block = {
      turnIndex: currentTurn.turnIndex,
      blockIndex: typeof evt.index === 'number' ? evt.index : currentTurn.blocks.length,
      type: cb.type || 'text',   // 'thinking' | 'text' | 'tool_use'
      content: '',
      status: 'streaming',
      // tool_use fields
      id: cb.id || null,
      name: cb.name || null,
      inputJson: '',
      input: null,
      output: null,
      is_error: false,
    }
    currentTurn.blocks.push(block)
    state.blocks.push(block)
    return
  }

  if (etype === 'content_block_delta') {
    const block = _findBlock(currentTurn, evt.index)
    if (!block) return
    const d = evt.delta || {}
    if (d.type === 'thinking_delta') {
      block.content += d.thinking || ''
    } else if (d.type === 'text_delta') {
      block.content += d.text || ''
    } else if (d.type === 'input_json_delta') {
      block.inputJson += d.partial_json || ''
    }
    return
  }

  if (etype === 'content_block_stop') {
    const block = _findBlock(currentTurn, evt.index)
    if (!block) return
    block.status = 'done'
    if (block.type === 'tool_use' && block.inputJson) {
      try {
        block.input = JSON.parse(block.inputJson)
      } catch {
        block.input = block.inputJson
      }
    }
    return
  }

  if (etype === 'message_delta') {
    if (evt.usage) state.usage = evt.usage
    return
  }

  if (etype === 'message_stop') {
    currentTurn.status = 'done'
  }
}

function _handleToolResult(state, data) {
  const toolUseId = data.tool_use_id
  if (!toolUseId) return
  let block = state.blocks.find((b) => b.type === 'tool_use' && b.id === toolUseId)
  if (!block) {
    block = _createSyntheticToolBlock(state, data)
  }
  block.output = data.content
  block.is_error = Boolean(data.is_error)
}

function _createSyntheticToolBlock(state, data) {
  const currentTurn = _ensureCurrentTurn(state)
  const skillName = _extractSkillLaunchName(data.content)
  const block = {
    turnIndex: currentTurn.turnIndex,
    blockIndex: currentTurn.blocks.length,
    type: 'tool_use',
    content: '',
    status: 'done',
    id: data.tool_use_id,
    name: skillName != null ? 'Skill' : 'Tool',
    inputJson: '',
    input: skillName ? { skill: skillName } : null,
    output: null,
    is_error: false,
  }
  currentTurn.blocks.push(block)
  state.blocks.push(block)
  return block
}

function _ensureCurrentTurn(state) {
  let currentTurn = state.turns.at(-1)
  if (!currentTurn || currentTurn.status === 'done') {
    currentTurn = { turnIndex: state.turns.length, blocks: [], status: 'streaming' }
    state.turns.push(currentTurn)
    if (state.status === 'idle') state.status = 'streaming'
  }
  return currentTurn
}

function _extractSkillLaunchName(output) {
  if (typeof output !== 'string') return null
  const match = output.trim().match(SKILL_LAUNCH_OUTPUT_RE)
  if (!match) return null
  return String(match[1] || '').trim()
}

function _findBlock(turn, index) {
  if (typeof index !== 'number') return turn.blocks.at(-1) || null
  return turn.blocks.find((b) => b.blockIndex === index) || null
}

/**
 * Convert a v2 tool_use block to the prop shape expected by ToolOutputRenderer.
 */
export function blockToToolProp(block) {
  const hasOutput = block.output != null
  return {
    name: block.name,
    input: block.input,
    output: block.output,
    status: hasOutput ? (block.is_error ? 'failed' : 'success') : (block.status === 'done' ? 'pending' : 'streaming'),
    id: block.id,
    _callComplete: block.status === 'done',
    _runtimeStarted: hasOutput,
    _startedAt: null,
  }
}
