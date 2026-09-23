// Pure, stateless helpers shared by the NL2SQL chat surfaces (the portal
// NL2SqlChatV2.vue and the embeddable WidgetChat.vue). These were previously
// duplicated, near-verbatim, in both components.

import { reactive } from 'vue'
import { marked } from 'marked'
import { createChatState, processV2Record } from './streamParser.js'
import { stripChartSpecsFromText } from './chartSpec.js'

marked.setOptions({ breaks: true, gfm: true })

// Escape before parsing so assistant-provided HTML can never inject markup.
const escapeHtml = (text) => String(text || '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')

// A workspace file reference is any relative href: no scheme (http:, mailto:,
// data:, ...), not host/root-absolute, not a fragment. The agent usually writes
// deliverables under `output/` by convention, but it may emit links to files
// anywhere in the workspace; a relative link has no other meaning in a chat
// answer, so rewriting is always an improvement (a missing path just 404s on
// the confined download endpoint).
const isWorkspaceFileHref = (href) => Boolean(href)
  && !/^[a-z][a-z0-9+.-]*:/i.test(href)
  && !href.startsWith('/')
  && !href.startsWith('#')

export function renderMarkdown(text, options = {}) {
  let html
  if (!text) return ''
  try {
    html = marked.parse(escapeHtml(text))
  } catch {
    return escapeHtml(text)
  }
  const resolveFileHref = typeof options.resolveFileHref === 'function' ? options.resolveFileHref : null
  if (!resolveFileHref) return html
  // Rewrite workspace-relative file links the agent emits (e.g.
  // `[报告](output/report.xlsx)`) into real topic file download URLs; every
  // other link is left untouched.
  return html.replace(/(<a href=")([^"]+)(")/g, (match, open, rawHref, close) => {
    if (!isWorkspaceFileHref(rawHref)) return match
    let relPath = rawHref.replace(/^\.\//, '')
    try { relPath = decodeURI(relPath) } catch { /* keep the raw path */ }
    const resolved = resolveFileHref(relPath)
    return resolved ? `${open}${resolved}${close}` : match
  })
}

// Human-readable text from a persisted task/message error object
// ({ message, detail, code }) or a raw string. Returns '' when nothing usable is
// present so callers can apply their own fallback copy.
// True when an Enter keydown on the composer should submit: plain Enter, no
// modifier keys, and not confirming an IME (CJK) composition. Shared by the
// widget and portal composers so both handle IME candidate-selection Enter the
// same way.
export function isPlainEnterSubmit(event) {
  if (!event) return false
  if (event.isComposing || event.keyCode === 229) return false
  if (event.shiftKey || event.ctrlKey || event.altKey || event.metaKey) return false
  return true
}

export function extractErrorText(error) {
  if (!error) return ''
  if (typeof error === 'string') return error
  if (typeof error === 'object') return String(error.message || error.detail || error.code || '')
  return String(error)
}


// Reconstruct the live stream state (_v2state) from a persisted assistant
// message's stored blocks so reload / topic-restore renders the same turns,
// tool calls, and error card as the original streamed run.
/**
 * Rebuild a finished message's state by replaying its stored records.
 *
 * The live stream and history used to run two independently written
 * projections, and they were not equivalent: this one flattened every turn of a
 * run into turn 0 and recognised only four block kinds, silently dropping
 * question_request. Replaying the same records through the same reducer the
 * live path uses removes that whole class of divergence — a new block type is
 * now implemented once.
 *
 * `blocks` hydration remains as a fallback for messages stored before the API
 * returned records, so old rows keep rendering.
 */
export function buildV2StateFromStoredRecords(item) {
  const records = Array.isArray(item?.records) ? item.records : []
  if (!records.length) {
    return buildV2StateFromStoredBlocks(item)
  }

  const v2state = createChatState()
  for (const record of records) {
    processV2Record(v2state, record)
  }
  // A replayed run is finished by definition; a stored record set that ends
  // without a terminal event would otherwise leave the UI spinning forever.
  if (v2state.status === 'streaming') {
    v2state.status = 'done'
  }
  for (const block of v2state.blocks) {
    if (block.status === 'streaming') block.status = 'done'
  }
  for (const turn of v2state.turns) {
    if (turn.status === 'streaming') turn.status = 'done'
  }
  if (['error', 'failed'].includes(String(item?.status || ''))) {
    v2state.status = 'error'
    v2state.errorText = extractErrorText(item?.error) || '会话执行失败'
  }
  return v2state
}

export function buildV2StateFromStoredBlocks(item) {
  const v2state = createChatState()
  v2state.status = 'done'
  const storedBlocks = Array.isArray(item?.blocks) ? item.blocks : []
  const turn = { turnIndex: 0, blocks: [], status: 'done' }
  v2state.turns.push(turn)
  let blockIdx = 0
  const base = { turnIndex: 0, content: '', status: 'done', id: null, name: null, inputJson: '', input: null, output: null, is_error: false }
  const push = (block) => {
    turn.blocks.push(block)
    v2state.blocks.push(block)
  }

  // Field names are the ones the backend projection emits (see the block writer
  // in topic_task_store.py) and nothing else. Accepting a second spelling per
  // field would be tolerating an upstream that does not exist, and each
  // alternative is a shape nobody is left responsible for converging.
  for (const b of storedBlocks) {
    const text = String(b?.text ?? '')
    switch (String(b?.type || '')) {
      case 'thinking':
        if (text) push({ ...b, ...base, blockIndex: blockIdx++, type: 'thinking', content: text })
        break
      case 'main_text':
        if (text) push({ ...b, ...base, blockIndex: blockIdx++, type: 'text', content: text })
        break
      case 'tool_use':
        push({
          ...b, ...base, blockIndex: blockIdx++, type: 'tool_use',
          id: b.tool_id || null,
          name: b.tool_name || 'Tool',
          input: b.input ?? null,
          output: b.output ?? null,
          is_error: Boolean(b.is_error),
        })
        break
      case 'permission_request':
        push({
          ...b, ...base, blockIndex: blockIdx++, type: 'permission_request',
          requestId: b.request_id || '',
          tool_name: b.tool_name || '',
          risk_level: b.risk_level || 'high',
          title: b.title || '',
          summary: b.summary || '',
          payload_preview: b.payload_preview ?? null,
          decision: b.decision || 'pending',
          note: b.note || '',
          decided_at: b.decided_at || '',
        })
        break
      case 'question_request':
        push({
          ...b, ...base, blockIndex: blockIdx++, type: 'question_request',
          requestId: b.request_id || '',
          questions: Array.isArray(b.questions) ? b.questions : [],
          answers: Array.isArray(b.answers) ? b.answers : [],
          answered: Boolean(b.answered),
          answered_at: b.answered_at || '',
        })
        break
      default:
        break
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
  if (['error', 'failed'].includes(String(item?.status || ''))) {
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

const normalizeAttachments = (attachments) => (Array.isArray(attachments) ? attachments : []).map((file) => ({
  ...file,
  name: String(file?.name || file?.relPath || file?.rel_path || ''),
  relPath: String(file?.relPath || file?.rel_path || ''),
  mediaType: file?.mediaType || file?.content_type || undefined,
  size: Number(file?.size || 0) || undefined,
}))

/**
 * Normalize a transport message into the one shape the SDK renders.
 *
 * A direct DataAgent transport can return stored event records while a BFF can
 * return already-projected blocks. Replaying both here keeps MessageList free
 * of protocol knowledge and gives a resumed run a writable `_v2state` before
 * new stream records arrive.
 */
export function normalizeConversationMessage(item) {
  const role = String(item?.role || item?.sender_type || 'user') === 'assistant' ? 'assistant' : 'user'
  const base = {
    ...item,
    id: String(item?.id || item?.message_id || `${role}_${item?.seq_id || uid()}`),
    role,
    content: messageContent(item),
    attachments: normalizeAttachments(item?.attachments),
    taskId: String(item?.taskId || item?.task_id || '') || undefined,
    createdAt: String(item?.createdAt || item?.created_at || '') || undefined,
    feedback: String(item?.feedback || ''),
  }
  if (role === 'user') return base

  const state = item?._v2state || buildV2StateFromStoredRecords(item)
  return reactive({
    ...base,
    status: String(item?.status || 'success'),
    error: item?.error || null,
    blocks: state.blocks,
    _v2state: reactive(state),
  })
}

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
    resume_after_seq: Number(item?.resume_after_seq || 0),
    error: item?.error || null,
    feedback: String(item?.feedback || ''),
    attachments: Array.isArray(item?.attachments) ? item.attachments : [],
    created_at: item?.created_at || '',
    _v2state: reactive(buildV2StateFromStoredRecords(item)),
  })
}

export function getMessageCopyText(message, options = {}) {
  const cleanText = typeof options.cleanText === 'function'
    ? options.cleanText
    : (value) => stripChartSpecsFromText(String(value || '')).trim()
  let text = String(message?.content || '')
  if (message?._v2state?.turns) {
    const texts = []
    for (const turn of message._v2state.turns) {
      if (!turn?.blocks) continue
      for (const block of turn.blocks) {
        if (block.type === 'text' && block.content) {
          const cleaned = String(cleanText(block.content) || '').trim()
          if (cleaned) texts.push(cleaned)
        }
      }
    }
    if (texts.length) text = texts.join('\n\n')
  } else {
    text = cleanText(text)
  }
  return text.trim()
}
