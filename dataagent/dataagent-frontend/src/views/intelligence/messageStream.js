const isPlainObject = (value) => value && typeof value === 'object' && !Array.isArray(value)

const textOrEmpty = (value) => (value == null ? '' : String(value))

const toUiStatus = (value) => {
  const status = textOrEmpty(value).trim()
  if (!status) return 'streaming'
  if (status === 'waiting') return 'queued'
  if (status === 'finished') return 'success'
  if (status === 'error') return 'failed'
  if (status === 'suspended') return 'cancelled'
  return status
}

export const appendStr = (base, delta) => {
  const next = String(delta || '')
  if (!next) return String(base || '')
  const prev = String(base || '')
  if (!prev) return next
  if (next === prev) return prev
  if (next.startsWith(prev)) return next
  return prev + next
}

export const parseMaybeJson = (value) => {
  if (typeof value !== 'string') return null
  const raw = value.trim()
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch (_error) {
    return null
  }
}

const normalizeToolId = (value) => {
  const text = textOrEmpty(value).trim()
  return text || ''
}

const toolStatusFromEvent = (eventType) => {
  if (eventType === 'tool.pending') return 'pending'
  if (eventType === 'tool.complete') return 'success'
  if (eventType === 'tool.failed') return 'failed'
  return 'streaming'
}

const ensureRenderBlock = (msg, blockId, kind, defaults = {}) => {
  const key = String(blockId || '').trim() || `block-${msg.renderBlocks.length + 1}`
  if (!msg._renderBlockMap[key]) {
    const block = {
      id: key,
      messageKey: textOrEmpty(defaults.messageKey).trim() || textOrEmpty(msg._activeMessageKey).trim() || 'm0',
      kind,
      status: defaults.status || 'streaming',
      text: defaults.text || '',
      payload: defaults.payload || {},
      tool: defaults.tool || null,
      _partialJson: ''
    }
    msg._renderBlockMap[key] = block
    msg.renderBlocks.push(block)
  }
  const block = msg._renderBlockMap[key]
  if (!textOrEmpty(block.messageKey).trim()) {
    block.messageKey = textOrEmpty(defaults.messageKey).trim() || textOrEmpty(msg._activeMessageKey).trim() || 'm0'
  }
  if (kind && (!block.kind || block.kind === 'raw')) block.kind = kind
  return block
}

const ensureMessageMeta = (msg, messageKey, defaults = {}) => {
  const key = textOrEmpty(messageKey).trim() || 'm0'
  if (!msg._messageMeta[key]) {
    msg._messageMeta[key] = {
      messageKey: key,
      message_id: textOrEmpty(defaults.message_id).trim() || '',
      usage: isPlainObject(defaults.usage) ? { ...defaults.usage } : null,
      stop_reason: textOrEmpty(defaults.stop_reason),
      stop_sequence: textOrEmpty(defaults.stop_sequence),
      status: textOrEmpty(defaults.status).trim() || 'streaming'
    }
  }
  const meta = msg._messageMeta[key]
  if (defaults.message_id) meta.message_id = textOrEmpty(defaults.message_id).trim()
  if (isPlainObject(defaults.usage)) meta.usage = { ...(meta.usage || {}), ...defaults.usage }
  if (Object.prototype.hasOwnProperty.call(defaults, 'stop_reason')) meta.stop_reason = textOrEmpty(defaults.stop_reason)
  if (Object.prototype.hasOwnProperty.call(defaults, 'stop_sequence')) meta.stop_sequence = textOrEmpty(defaults.stop_sequence)
  if (defaults.status) meta.status = textOrEmpty(defaults.status).trim()
  return meta
}

const extractToolEnvelope = (block) => {
  if (!isPlainObject(block)) return null
  const payload = isPlainObject(block.payload) ? block.payload : {}
  const toolId = normalizeToolId(block.tool_id || payload.tool_id || payload.tool_use_id || payload.id)
  const toolName = textOrEmpty(block.tool_name || payload.tool_name || payload.name).trim()
  const hasEnvelope = Boolean(
    toolId
    || toolName
    || Object.prototype.hasOwnProperty.call(block, 'input')
    || Object.prototype.hasOwnProperty.call(block, 'output')
    || Object.prototype.hasOwnProperty.call(payload, 'input')
    || Object.prototype.hasOwnProperty.call(payload, 'output')
    || Object.prototype.hasOwnProperty.call(payload, 'content')
  )
  if (!hasEnvelope) return null
  return {
    toolId,
    name: toolName || 'Tool',
    input: Object.prototype.hasOwnProperty.call(block, 'input') ? block.input : payload.input,
    output: Object.prototype.hasOwnProperty.call(block, 'output') ? block.output : (payload.output ?? payload.content),
    status: textOrEmpty(block.status).trim() || 'success'
  }
}

const ensureToolRenderBlock = (msg, patch = {}) => {
  const toolId = normalizeToolId(patch.toolId)
  const blockKey = textOrEmpty(patch.blockKey).trim()
  const messageKey = textOrEmpty(patch.messageKey).trim() || textOrEmpty(msg._activeMessageKey).trim() || 'm0'
  const mappedBlockId = toolId ? msg._toolBlockIds[toolId] : ''
  const renderId = mappedBlockId || blockKey || `tool-${toolId || msg.renderBlocks.length + 1}`
  const block = ensureRenderBlock(msg, renderId, 'tool', {
    messageKey,
    status: textOrEmpty(patch.status).trim() || 'pending',
    tool: {
      id: toolId || renderId,
      _toolId: toolId || '',
      _blockKey: blockKey,
      name: textOrEmpty(patch.name).trim() || 'Tool',
      status: textOrEmpty(patch.status).trim() || 'pending',
      input: null,
      output: null,
      _callComplete: false,
      _runtimeStarted: false,
      _resultStarted: false
    }
  })

  if (!block.tool) {
    block.tool = {
      id: toolId || renderId,
      _toolId: toolId || '',
      _blockKey: blockKey,
      name: textOrEmpty(patch.name).trim() || 'Tool',
      status: textOrEmpty(patch.status).trim() || 'pending',
      input: null,
      output: null,
      _callComplete: false,
      _runtimeStarted: false,
      _resultStarted: false
    }
  }

  const tool = block.tool
  if (toolId) {
    tool.id = toolId
    tool._toolId = toolId
    msg._toolBlockIds[toolId] = renderId
  }
  if (blockKey) tool._blockKey = blockKey
  if (patch.name) tool.name = textOrEmpty(patch.name).trim()
  if (Object.prototype.hasOwnProperty.call(patch, 'input') && patch.input !== undefined) {
    tool.input = patch.input
  }
  if (Object.prototype.hasOwnProperty.call(patch, 'output') && patch.output !== undefined) {
    if (typeof tool.output === 'string' && typeof patch.output === 'string') {
      tool.output = appendStr(tool.output, patch.output)
    } else {
      tool.output = patch.output
    }
  }

  if (patch.status) {
    tool.status = textOrEmpty(patch.status).trim()
    block.status = tool.status
  }
  if (Object.prototype.hasOwnProperty.call(patch, 'callComplete')) {
    tool._callComplete = Boolean(patch.callComplete)
  }
  if (Object.prototype.hasOwnProperty.call(patch, 'runtimeStarted')) {
    tool._runtimeStarted = Boolean(patch.runtimeStarted)
  }
  if (Object.prototype.hasOwnProperty.call(patch, 'resultStarted')) {
    tool._resultStarted = Boolean(patch.resultStarted)
  }

  if (tool._runtimeStarted && ['pending', 'streaming'].includes(tool.status)) {
    tool._startedAt = tool._startedAt || Date.now()
    delete tool._completedAt
  }
  if (['success', 'failed'].includes(tool.status)) {
    tool._completedAt = tool._completedAt || Date.now()
  }

  return block
}

const ensureTextBlock = (msg, blockId, kind) => ensureRenderBlock(msg, blockId, kind, { status: 'streaming', text: '' })

const markAllStreamingBlocksComplete = (msg) => {
  for (const block of msg.renderBlocks) {
    if (block.kind === 'tool' && block.tool && ['pending', 'streaming'].includes(block.tool.status)) {
      block.tool.status = 'success'
      block.status = 'success'
      block.tool._completedAt = block.tool._completedAt || Date.now()
      continue
    }
    if (['pending', 'streaming', 'in_progress'].includes(textOrEmpty(block.status))) {
      block.status = 'success'
    }
  }
}

const markMessageBlocksComplete = (msg) => {
  for (const block of msg.renderBlocks) {
    if (block.kind === 'tool') continue
    if (['pending', 'streaming', 'in_progress'].includes(textOrEmpty(block.status))) {
      block.status = 'success'
    }
  }
}

const createErrorBlock = (msg, text) => {
  const block = ensureRenderBlock(msg, `error-${msg.renderBlocks.length + 1}`, 'error', { status: 'failed', text })
  block.status = 'failed'
  block.text = textOrEmpty(text)
  return block
}

const syncMainText = (msg) => {
  const text = msg.renderBlocks
    .filter((block) => block.kind === 'main_text')
    .map((block) => textOrEmpty(block.text))
    .join('')
  msg.mainText = text
  msg.content = text
}

const syncThinkingText = (msg) => {
  msg.thinkingText = msg.renderBlocks
    .filter((block) => block.kind === 'thinking')
    .map((block) => textOrEmpty(block.text))
    .join('')
}

const hasRenderableMainText = (msg) => msg.renderBlocks.some((block) => block.kind === 'main_text' && textOrEmpty(block.text).trim())

export const createAssistantMessageState = (seed = {}) => ({
  id: textOrEmpty(seed.id).trim() || '',
  message_id: textOrEmpty(seed.message_id || seed.id).trim() || '',
  task_id: textOrEmpty(seed.task_id).trim() || '',
  resume_after_seq: Number(seed.resume_after_seq || 0),
  role: 'assistant',
  content: textOrEmpty(seed.content),
  status: toUiStatus(seed.status) || 'streaming',
  mainText: textOrEmpty(seed.mainText || seed.content),
  thinkingText: textOrEmpty(seed.thinkingText),
  citations: Array.isArray(seed.citations) ? [...seed.citations] : [],
  error: seed.error || null,
  stop_reason: textOrEmpty(seed.stop_reason),
  stop_sequence: textOrEmpty(seed.stop_sequence),
  usage: isPlainObject(seed.usage) ? { ...seed.usage } : null,
  feedback: textOrEmpty(seed.feedback),
  provider_id: seed.provider_id || null,
  model: seed.model || null,
  created_at: seed.created_at || new Date().toISOString(),
  renderBlocks: [],
  _renderBlockMap: Object.create(null),
  _toolBlockIds: Object.create(null),
  _messageMeta: Object.create(null),
  _streamMessageSeq: 0,
  _activeMessageKey: 'm0',
  _rawBlockIds: Object.create(null)
})

export const hydrateAssistantMessageState = (message) => {
  const msg = createAssistantMessageState({
    id: textOrEmpty(message?.message_id).trim(),
    message_id: textOrEmpty(message?.message_id).trim(),
    task_id: textOrEmpty(message?.task_id).trim(),
    content: textOrEmpty(message?.content),
    status: toUiStatus(message?.status) || 'success',
    stop_reason: textOrEmpty(message?.stop_reason),
    stop_sequence: textOrEmpty(message?.stop_sequence),
    created_at: message?.created_at,
    error: isPlainObject(message?.error) ? message.error : null,
    provider_id: message?.provider_id || null,
    model: message?.model || null,
    usage: isPlainObject(message?.usage) ? message.usage : null,
    feedback: textOrEmpty(message?.feedback),
    resume_after_seq: Number(message?.resume_after_seq || 0)
  })

  const rawBlocks = Array.isArray(message?.blocks) ? message.blocks : []
  ensureMessageMeta(msg, 'm0', {
    message_id: textOrEmpty(message?.message_id).trim(),
    usage: isPlainObject(message?.usage) ? message.usage : null,
    stop_reason: textOrEmpty(message?.stop_reason),
    stop_sequence: textOrEmpty(message?.stop_sequence),
    status: toUiStatus(message?.status) || 'success'
  })
  for (const rawBlock of rawBlocks) {
    if (!isPlainObject(rawBlock)) continue
    const blockId = textOrEmpty(rawBlock.block_id).trim() || `stored-${msg.renderBlocks.length + 1}`
    const blockType = textOrEmpty(rawBlock.type).trim()
    const blockStatus = textOrEmpty(rawBlock.status).trim() || 'success'

    if (blockType === 'thinking') {
      const block = ensureTextBlock(msg, blockId, 'thinking')
      block.messageKey = 'm0'
      block.status = blockStatus
      block.text = textOrEmpty(rawBlock.text)
      msg.thinkingText = appendStr(msg.thinkingText, block.text)
    } else if (blockType === 'main_text') {
      const block = ensureTextBlock(msg, blockId, 'main_text')
      block.messageKey = 'm0'
      block.status = blockStatus
      block.text = textOrEmpty(rawBlock.text)
      msg.mainText = appendStr(msg.mainText, block.text)
    } else if (blockType === 'error') {
      const block = ensureRenderBlock(msg, blockId, 'error', { status: 'failed', text: textOrEmpty(rawBlock.text) })
      block.messageKey = 'm0'
      block.status = 'failed'
      block.text = textOrEmpty(rawBlock.text)
      msg.error = msg.error || { message: block.text }
    }

    const envelope = extractToolEnvelope(rawBlock)
    if (envelope) {
      const envelopeStatus = textOrEmpty(envelope.status).trim() || 'success'
      ensureToolRenderBlock(msg, {
        toolId: envelope.toolId,
        blockKey: `${blockId}::tool`,
        messageKey: 'm0',
        name: envelope.name,
        input: envelope.input,
        output: envelope.output,
        status: envelopeStatus,
        callComplete: true,
        runtimeStarted: true
      })
    }

    const payloadCitations = isPlainObject(rawBlock.payload) ? rawBlock.payload.citations : null
    if (Array.isArray(payloadCitations)) {
      msg.citations.push(...payloadCitations)
    }
  }

  if (!hasRenderableMainText(msg) && msg.content) {
    const block = ensureTextBlock(msg, 'main-text', 'main_text')
    block.status = msg.status === 'failed' ? 'failed' : 'success'
    block.text = msg.content
  }

  return msg
}

const processMagicTaskEvent = (msg, event) => {
  const recordType = textOrEmpty(event?.record_type).trim()
  if (!recordType) return false

  msg.resume_after_seq = Math.max(Number(msg.resume_after_seq || 0), Number(event?.seq_id || event?.seq || 0))

  if (event.task_id) {
    msg.task_id = textOrEmpty(event.task_id)
  }

  if (recordType === 'chunk') {
    const delta = isPlainObject(event.delta) ? event.delta : {}
    const metadata = isPlainObject(event.metadata) ? event.metadata : {}
    const contentType = textOrEmpty(metadata.content_type).trim()
    const correlationId = textOrEmpty(metadata.correlation_id).trim() || `phase-${msg.renderBlocks.length + 1}`
    const blockId = `${contentType || 'chunk'}:${correlationId}`
    const chunkText = textOrEmpty(event.content)
    const status = textOrEmpty(delta.status).trim()

    if (contentType === 'reasoning') {
      const block = ensureTextBlock(msg, blockId, 'thinking')
      block.status = status === 'END' ? 'success' : 'streaming'
      block.text = status === 'END' ? chunkText : appendStr(block.text, chunkText)
      syncThinkingText(msg)
      return true
    }

    if (contentType === 'content') {
      const block = ensureTextBlock(msg, blockId, 'main_text')
      block.status = status === 'END' ? 'success' : 'streaming'
      block.text = status === 'END' ? chunkText : appendStr(block.text, chunkText)
      syncMainText(msg)
      return true
    }

    return true
  }

  if (recordType !== 'event') return false

  const eventType = textOrEmpty(event.event_type).trim()
  const data = isPlainObject(event.data) ? event.data : {}
  const eventStatus = toUiStatus(data.status)
  const correlationId = textOrEmpty(event.correlation_id).trim() || `evt-${msg.renderBlocks.length + 1}`

  if (eventStatus && eventStatus !== 'streaming') {
    msg.status = eventStatus
  }

  if (eventType === 'BEFORE_AGENT_REPLY') {
    const contentType = textOrEmpty(event.content_type).trim()
    if (contentType === 'reasoning') {
      ensureTextBlock(msg, `reasoning:${correlationId}`, 'thinking').status = 'streaming'
    } else if (contentType === 'content') {
      ensureTextBlock(msg, `content:${correlationId}`, 'main_text').status = 'streaming'
    }
    return true
  }

  if (eventType === 'AFTER_AGENT_REPLY') {
    const contentType = textOrEmpty(event.content_type).trim()
    const block = msg._renderBlockMap[`${contentType}:${correlationId}`]
    if (block && block.status !== 'failed') {
      block.status = 'success'
    }
    if (isPlainObject(data.token_usage)) {
      msg.usage = { ...(msg.usage || {}), ...data.token_usage }
    }
    if (eventStatus) {
      msg.status = eventStatus
    }
    return true
  }

  if (eventType === 'PENDING_TOOL_CALL' || eventType === 'BEFORE_TOOL_CALL' || eventType === 'AFTER_TOOL_CALL') {
    const tool = isPlainObject(data.tool) ? data.tool : {}
    const toolStatus = eventType === 'PENDING_TOOL_CALL'
      ? 'pending'
      : eventType === 'BEFORE_TOOL_CALL'
        ? 'streaming'
        : textOrEmpty(tool.status).trim() || 'success'
    ensureToolRenderBlock(msg, {
      toolId: tool.id || correlationId,
      blockKey: `tool:${tool.id || correlationId}`,
      name: tool.name || 'Tool',
      input: Object.prototype.hasOwnProperty.call(tool, 'input') ? tool.input : undefined,
      output: Object.prototype.hasOwnProperty.call(tool, 'output') ? tool.output : undefined,
      status: toolStatus,
      callComplete: eventType !== 'PENDING_TOOL_CALL',
      runtimeStarted: eventType !== 'PENDING_TOOL_CALL',
      resultStarted: eventType === 'AFTER_TOOL_CALL'
    })
    return true
  }

  if (eventType === 'AGENT_SUSPENDED') {
    msg.status = 'cancelled'
    const errorPayload = isPlainObject(data.error) ? data.error : { message: '任务已取消' }
    msg.error = { message: textOrEmpty(errorPayload.message || '任务已取消') }
    createErrorBlock(msg, msg.error.message)
    markAllStreamingBlocksComplete(msg)
    return true
  }

  if (eventType === 'ERROR') {
    msg.status = 'failed'
    const errorPayload = isPlainObject(data.error) ? data.error : { message: data.message || '请求失败' }
    msg.error = { message: textOrEmpty(errorPayload.message || '请求失败') }
    createErrorBlock(msg, msg.error.message)
    markAllStreamingBlocksComplete(msg)
    return true
  }

  if (eventType === 'BEFORE_AGENT_THINK' || eventType === 'AFTER_AGENT_THINK' || eventType === 'DEBUG') {
    return true
  }

  return false
}

export const processAssistantStreamEvent = (msg, event) => {
  if (!isPlainObject(msg) || !isPlainObject(event)) return

  if (processMagicTaskEvent(msg, event)) {
    return
  }

  const type = textOrEmpty(event.type).trim()
  const payload = isPlainObject(event.payload) ? event.payload : {}

  if (event.message_id) msg.message_id = textOrEmpty(event.message_id)
  if (payload.provider_id) msg.provider_id = textOrEmpty(payload.provider_id)
  if (payload.model) msg.model = textOrEmpty(payload.model)

  const ensureActiveMessageKey = () => {
    if (!textOrEmpty(msg._activeMessageKey)) {
      msg._activeMessageKey = `m${Number(msg._streamMessageSeq || 0)}`
    }
    return msg._activeMessageKey
  }

  const beginNewStreamMessage = () => {
    msg._streamMessageSeq = Number(msg._streamMessageSeq || 0) + 1
    msg._activeMessageKey = `m${msg._streamMessageSeq}`
  }

  const bindRawBlockId = (rawId, renderId) => {
    const raw = textOrEmpty(rawId).trim()
    if (!raw || !renderId) return
    msg._rawBlockIds[`${ensureActiveMessageKey()}:${raw}`] = renderId
  }

  const resolveRenderBlockId = (rawId) => {
    const raw = textOrEmpty(rawId).trim()
    if (!raw) return ''
    return msg._rawBlockIds[`${ensureActiveMessageKey()}:${raw}`] || `${ensureActiveMessageKey()}:${raw}`
  }

  if (type === 'message_start') {
    const message = isPlainObject(event.message) ? event.message : (isPlainObject(payload.message) ? payload.message : {})
    beginNewStreamMessage()
    ensureMessageMeta(msg, ensureActiveMessageKey(), {
      message_id: textOrEmpty(message.id).trim(),
      usage: isPlainObject(message.usage) ? message.usage : null,
      status: 'streaming'
    })
    if (message.id) msg.message_id = textOrEmpty(message.id)
    if (message.model) msg.model = textOrEmpty(message.model)
    if (isPlainObject(message.usage)) msg.usage = { ...message.usage }
    msg.status = 'streaming'
    return
  }

  if (type === 'ping' || type === 'llm_response_created' || type === 'block_complete' || type === 'raw') {
    return
  }

  if (type === 'content_block_start') {
    const index = event.index ?? payload.index
    const contentBlock = isPlainObject(event.content_block) ? event.content_block : (isPlainObject(payload.content_block) ? payload.content_block : {})
    const contentType = textOrEmpty(contentBlock.type).trim()
    const rawBlockId = `cb-${index}`
    const blockId = `${ensureActiveMessageKey()}:${rawBlockId}`
    bindRawBlockId(rawBlockId, blockId)

    if (contentType === 'text') {
      const block = ensureTextBlock(msg, blockId, 'main_text')
      block.status = 'streaming'
      if (contentBlock.text) {
        block.text = appendStr(block.text, contentBlock.text)
        msg.mainText = appendStr(msg.mainText, contentBlock.text)
        msg.content = appendStr(msg.content, contentBlock.text)
      }
      return
    }

    if (contentType === 'thinking') {
      const block = ensureTextBlock(msg, blockId, 'thinking')
      block.status = 'streaming'
      if (contentBlock.thinking) {
        block.text = appendStr(block.text, contentBlock.thinking)
        msg.thinkingText = appendStr(msg.thinkingText, contentBlock.thinking)
      }
      return
    }

    if (contentType === 'tool_use' || contentType === 'server_tool_use') {
      const block = ensureToolRenderBlock(msg, {
        toolId: contentBlock.id,
        blockKey: blockId,
        messageKey: ensureActiveMessageKey(),
        name: contentBlock.name || 'Tool',
        input: Object.prototype.hasOwnProperty.call(contentBlock, 'input') ? contentBlock.input : undefined,
        status: 'streaming',
        callComplete: false,
        runtimeStarted: false
      })
      bindRawBlockId(rawBlockId, block.id)
      return
    }

    if (contentType === 'tool_result') {
      const block = ensureToolRenderBlock(msg, {
        toolId: contentBlock.tool_use_id,
        blockKey: blockId,
        messageKey: ensureActiveMessageKey(),
        name: contentBlock.name || 'Tool',
        output: Object.prototype.hasOwnProperty.call(contentBlock, 'content') ? contentBlock.content : undefined,
        status: 'streaming',
        callComplete: true,
        runtimeStarted: true,
        resultStarted: true
      })
      bindRawBlockId(rawBlockId, block.id)
      return
    }
    const block = ensureRenderBlock(msg, blockId, 'raw', {
      messageKey: ensureActiveMessageKey(),
      status: 'streaming',
      payload: isPlainObject(contentBlock) ? { ...contentBlock } : {}
    })
    block.payload = isPlainObject(contentBlock) ? { ...contentBlock } : {}
    return
  }

  if (type === 'content_block_delta') {
    const index = event.index ?? payload.index
    const rawBlockId = `cb-${index}`
    const blockId = resolveRenderBlockId(rawBlockId) || `${ensureActiveMessageKey()}:${rawBlockId}`
    const delta = isPlainObject(event.delta) ? event.delta : (isPlainObject(payload.delta) ? payload.delta : {})
    const deltaType = textOrEmpty(delta.type).trim()

    if (deltaType === 'text_delta') {
      const block = ensureTextBlock(msg, blockId, 'main_text')
      block.status = 'streaming'
      block.text = appendStr(block.text, delta.text)
      msg.mainText = appendStr(msg.mainText, delta.text)
      msg.content = appendStr(msg.content, delta.text)
      return
    }

    if (deltaType === 'thinking_delta') {
      const block = ensureTextBlock(msg, blockId, 'thinking')
      block.status = 'streaming'
      block.text = appendStr(block.text, delta.thinking)
      msg.thinkingText = appendStr(msg.thinkingText, delta.thinking)
      return
    }

    if (deltaType === 'input_json_delta') {
      const block = ensureToolRenderBlock(msg, {
        blockKey: blockId,
        messageKey: ensureActiveMessageKey(),
        status: 'streaming',
        callComplete: false,
        runtimeStarted: false
      })
      block._partialJson = appendStr(block._partialJson, delta.partial_json || '')
      const parsed = parseMaybeJson(block._partialJson)
      block.tool.input = parsed !== null ? parsed : block._partialJson
      return
    }

    if (deltaType === 'citation_start_delta' && delta.citation) {
      msg.citations.push(delta.citation)
      const block = msg._renderBlockMap[blockId]
      if (block) {
        block.payload = isPlainObject(block.payload) ? block.payload : {}
        block.payload.citations = Array.isArray(block.payload.citations) ? [...block.payload.citations, delta.citation] : [delta.citation]
      }
      return
    }

    if (deltaType === 'signature_delta') {
      const block = msg._renderBlockMap[blockId] || ensureRenderBlock(msg, blockId, 'raw', { status: 'streaming' })
      block.payload = isPlainObject(block.payload) ? block.payload : {}
      block.payload.signature = appendStr(block.payload.signature, delta.signature || '')
    }
    return
  }

  if (type === 'content_block_stop') {
    const index = event.index ?? payload.index
    const block = msg._renderBlockMap[resolveRenderBlockId(`cb-${index}`)]
    if (!block) return
    if (block.kind === 'tool' && block.tool && block.status !== 'failed') {
      block.tool._callComplete = true
      if (block.tool.status === 'failed') {
        block.status = 'failed'
        return
      }
      if (block.tool._resultStarted) {
        block.tool.status = 'success'
        block.tool._completedAt = block.tool._completedAt || Date.now()
        block.status = 'success'
        return
      }
      if (block.tool.status === 'success') {
        block.status = 'success'
        return
      }
      block.status = 'streaming'
      return
    }
    if (block.status !== 'failed') block.status = 'success'
    return
  }

  if (type === 'message_delta') {
    const delta = isPlainObject(event.delta) ? event.delta : (isPlainObject(payload.delta) ? payload.delta : {})
    ensureMessageMeta(msg, ensureActiveMessageKey(), {
      stop_reason: delta.stop_reason,
      stop_sequence: delta.stop_sequence,
      usage: isPlainObject(delta.usage) ? delta.usage : null
    })
    if (delta.stop_reason != null) msg.stop_reason = textOrEmpty(delta.stop_reason)
    if (delta.stop_sequence != null) msg.stop_sequence = textOrEmpty(delta.stop_sequence)
    if (isPlainObject(delta.usage)) {
      msg.usage = { ...(msg.usage || {}), ...delta.usage }
    }
    return
  }

  if (type === 'message_stop') {
    ensureMessageMeta(msg, ensureActiveMessageKey(), { status: 'success' })
    markMessageBlocksComplete(msg)
    return
  }

  if (type === 'text.delta') {
    const block = ensureTextBlock(msg, 'main-text', 'main_text')
    block.status = 'streaming'
    block.text = appendStr(block.text, payload.text)
    msg.mainText = appendStr(msg.mainText, payload.text)
    msg.content = appendStr(msg.content, payload.text)
    return
  }

  if (type === 'text.complete') {
    const block = ensureTextBlock(msg, 'main-text', 'main_text')
    block.status = 'success'
    if (typeof payload.text === 'string') {
      block.text = payload.text
      msg.mainText = payload.text
      msg.content = payload.text
    }
    return
  }

  if (type === 'thinking.delta') {
    const block = ensureTextBlock(msg, 'thinking-main', 'thinking')
    block.status = 'streaming'
    block.text = appendStr(block.text, payload.text)
    msg.thinkingText = appendStr(msg.thinkingText, payload.text)
    return
  }

  if (type === 'thinking.complete') {
    const block = ensureTextBlock(msg, 'thinking-main', 'thinking')
    block.status = 'success'
    if (typeof payload.text === 'string') {
      block.text = payload.text
      msg.thinkingText = payload.text
    }
    return
  }

  if (type.startsWith('tool.')) {
    const runtimeStarted = type !== 'tool.failed'
    ensureToolRenderBlock(msg, {
      toolId: payload.tool_id || payload.block_id,
      blockKey: resolveRenderBlockId(payload.block_id) || payload.block_id,
      name: payload.tool_name || 'Tool',
      input: Object.prototype.hasOwnProperty.call(payload, 'input') ? payload.input : undefined,
      output: Object.prototype.hasOwnProperty.call(payload, 'output') ? payload.output : undefined,
      status: toolStatusFromEvent(type),
      callComplete: true,
      runtimeStarted: runtimeStarted || ['success', 'failed'].includes(toolStatusFromEvent(type))
    })
    return
  }

  if (type === 'error') {
    msg.status = 'failed'
    const errorPayload = isPlainObject(payload.error) ? payload.error : payload
    msg.error = {
      message: textOrEmpty(errorPayload.message || payload.message || '请求失败'),
      type: textOrEmpty(errorPayload.type || payload.type)
    }
    createErrorBlock(msg, msg.error.message)
    return
  }

  if (type === 'done') {
    msg.status = textOrEmpty(payload.status).trim() || msg.status || 'success'
    if (payload.model) msg.model = textOrEmpty(payload.model)
    if (payload.stop_reason != null) msg.stop_reason = textOrEmpty(payload.stop_reason)
    if (payload.stop_sequence != null) msg.stop_sequence = textOrEmpty(payload.stop_sequence)
    if (isPlainObject(payload.usage)) {
      msg.usage = { ...(msg.usage || {}), ...payload.usage }
    }
    if (payload.error) {
      const errorMessage = isPlainObject(payload.error)
        ? textOrEmpty(payload.error.message || '请求失败')
        : textOrEmpty(payload.error)
      msg.error = { message: errorMessage }
      createErrorBlock(msg, errorMessage)
    }
    if (Array.isArray(payload.blocks) && !msg.renderBlocks.length) {
      const hydrated = hydrateAssistantMessageState({
        message_id: msg.message_id || msg.id,
        content: payload.content,
        status: msg.status,
        created_at: msg.created_at,
        blocks: payload.blocks,
        error: msg.error,
        provider_id: msg.provider_id,
        model: msg.model
      })
      Object.assign(msg, hydrated)
      return
    }
    if (!hasRenderableMainText(msg) && payload.content) {
      const block = ensureTextBlock(msg, 'main-text', 'main_text')
      block.status = msg.status === 'failed' ? 'failed' : 'success'
      block.text = textOrEmpty(payload.content)
    }
    if (payload.content) {
      msg.content = textOrEmpty(payload.content)
    }
    markAllStreamingBlocksComplete(msg)
  }
}

export const activeStreamingBlock = (msg) => [...(Array.isArray(msg?.renderBlocks) ? msg.renderBlocks : [])]
  .reverse()
  .find((block) => {
    if (block?.kind === 'tool' && block.tool) {
      return ['pending', 'streaming'].includes(textOrEmpty(block.tool.status))
    }
    return ['pending', 'streaming'].includes(textOrEmpty(block?.status))
  })
