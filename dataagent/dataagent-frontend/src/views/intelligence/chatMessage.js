// Pure, stateless helpers shared by the NL2SQL chat surfaces (the portal
// NL2SqlChatV2.vue and the embeddable WidgetChat.vue). These were previously
// duplicated, near-verbatim, in both components.

import { reactive } from 'vue'
import { marked } from 'marked'
import { createChatState } from './v2StreamParser'

marked.setOptions({ breaks: true, gfm: true })

// Escape before parsing so assistant-provided HTML can never inject markup.
const escapeHtml = (text) => String(text || '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')

export function renderMarkdown(text) {
  if (!text) return ''
  try {
    return marked.parse(escapeHtml(text))
  } catch {
    return escapeHtml(text)
  }
}

// Human-readable text from a persisted task/message error object
// ({ message, detail, code }) or a raw string. Returns '' when nothing usable is
// present so callers can apply their own fallback copy.
export function extractErrorText(error) {
  if (!error) return ''
  if (typeof error === 'string') return error
  if (typeof error === 'object') return String(error.message || error.detail || error.code || '')
  return String(error)
}

export function normalizeTopic(topic) {
  return {
    topic_id: String(topic?.topic_id || ''),
    title: String(topic?.title || '新话题'),
    message_count: Number(topic?.message_count || 0),
    last_message_preview: String(topic?.last_message_preview || ''),
    current_task_id: String(topic?.current_task_id || ''),
    current_task_status: String(topic?.current_task_status || ''),
    created_at: String(topic?.created_at || new Date().toISOString()),
    updated_at: String(topic?.updated_at || new Date().toISOString()),
  }
}

// Descending by recency: updated_at, falling back to created_at.
export function compareTopicsByRecency(a, b) {
  return String(b?.updated_at || b?.created_at || '').localeCompare(String(a?.updated_at || a?.created_at || ''))
}

// Reconstruct the live stream state (_v2state) from a persisted assistant
// message's stored blocks so reload / topic-restore renders the same turns,
// tool calls, and error card as the original streamed run.
export function buildV2StateFromStoredBlocks(item) {
  const v2state = createChatState()
  v2state.status = 'done'
  const storedBlocks = Array.isArray(item?.blocks) ? item.blocks : []
  const turn = { turnIndex: 0, blocks: [], status: 'done' }
  v2state.turns.push(turn)
  let blockIdx = 0
  for (const b of storedBlocks) {
    const kind = String(b?.kind || b?.type || '')
    if (kind === 'thinking' && b?.text) {
      const block = { turnIndex: 0, blockIndex: blockIdx++, type: 'thinking', content: b.text, status: 'done', id: null, name: null, inputJson: '', input: null, output: null, is_error: false }
      turn.blocks.push(block)
      v2state.blocks.push(block)
    } else if (kind === 'main_text' && b?.text) {
      const block = { turnIndex: 0, blockIndex: blockIdx++, type: 'text', content: b.text, status: 'done', id: null, name: null, inputJson: '', input: null, output: null, is_error: false }
      turn.blocks.push(block)
      v2state.blocks.push(block)
    } else if (kind === 'tool_use') {
      // SDK-derived format: flat tool_id / tool_name / input / output / is_error.
      const block = { turnIndex: 0, blockIndex: blockIdx++, type: 'tool_use', content: '', status: 'done', id: b.tool_id || null, name: b.tool_name || 'Tool', inputJson: '', input: b.input ?? null, output: b.output ?? null, is_error: Boolean(b.is_error) }
      turn.blocks.push(block)
      v2state.blocks.push(block)
    } else if (kind === 'tool' && b?.tool) {
      // Legacy magic-event format: nested b.tool object.
      const block = { turnIndex: 0, blockIndex: blockIdx++, type: 'tool_use', content: '', status: 'done', id: b.tool.id || b.tool._toolId || null, name: b.tool.name || 'Tool', inputJson: '', input: b.tool.input, output: b.tool.output, is_error: b.tool.status === 'failed' }
      turn.blocks.push(block)
      v2state.blocks.push(block)
    }
  }
  const content = String(item?.content || '')
  if (!turn.blocks.length && content) {
    const block = { turnIndex: 0, blockIndex: 0, type: 'text', content, status: 'done', id: null, name: null, inputJson: '', input: null, output: null, is_error: false }
    turn.blocks.push(block)
    v2state.blocks.push(block)
  }
  // A failed run persists status === 'error' (+ error). Surface it through
  // _v2state so the error card renders on reload, not just during live streaming.
  if (String(item?.status || '') === 'error') {
    v2state.status = 'error'
    turn.status = 'error'
    v2state.errorText = extractErrorText(item?.error) || '会话执行失败'
  }
  return v2state
}

function messageContent(message) {
  const content = String(message?.content || '').trim()
  if (content) return content
  const blocks = Array.isArray(message?.blocks) ? message.blocks : []
  return blocks
    .map((block) => String(block?.text || block?.output || '').trim())
    .filter(Boolean)
    .join('\n')
}

const uid = () => `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

// Hydrate a persisted message (user or assistant) into the local message shape.
// Returns a superset object so both surfaces find the fields they render: the
// widget reads status/task_id/error, the portal reads feedback.
export function hydrateMessageFromApi(item) {
  const role = String(item?.role || item?.sender_type || 'user')
  if (role !== 'assistant') {
    return {
      id: String(item?.message_id || item?.id || `user_${item?.seq_id || uid()}`),
      role: 'user',
      content: messageContent(item),
      created_at: item?.created_at || '',
      _v2state: null,
    }
  }
  return reactive({
    id: String(item?.message_id || item?.id || `assistant_${item?.seq_id || uid()}`),
    role: 'assistant',
    content: messageContent(item),
    status: item?.status || 'success',
    task_id: String(item?.task_id || ''),
    error: item?.error || null,
    feedback: String(item?.feedback || ''),
    created_at: item?.created_at || '',
    _v2state: reactive(buildV2StateFromStoredBlocks(item)),
  })
}
