import { ConversationError, ErrorCode, StreamInterrupted, errorFromResponse } from './errors.js'

/**
 * Default transport: speaks the host BFF protocol documented in
 * docs/bff-protocol.md. It never reaches DataAgent directly — the host's own
 * backend proxies, which is what keeps the DataAgent address and credentials
 * out of the browser.
 *
 * @param {object} options
 * @param {() => string | Promise<string>} options.getEndpoint resolves the
 *   conversation address. Called per request so a lazily-created conversation
 *   picks up its address as soon as it exists.
 */
export function createHttpTransport({ getEndpoint }) {
  // fileUrl is synchronous by contract (it feeds href/src), so the last
  // resolved address is kept here for it to use.
  let cachedEndpoint = ''

  const endpoint = async () => {
    const value = await getEndpoint()
    if (!value) throw new ConversationError(ErrorCode.CONVERSATION_UNAVAILABLE, '会话地址尚未就绪')
    cachedEndpoint = String(value).replace(/\/$/, '')
    return cachedEndpoint
  }

  const request = async (path, init = {}) => {
    const base = await endpoint()
    let response
    try {
      response = await fetch(`${base}${path}`, {
        credentials: 'same-origin',
        ...init,
        headers: { Accept: 'application/json', ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers }
      })
    } catch (cause) {
      throw new ConversationError(
        ErrorCode.TRANSPORT_UNREACHABLE,
        '无法连接服务端',
        '检查网络，或确认应用后端是否在运行'
      )
    }
    if (!response.ok) throw await errorFromResponse(response)
    return response
  }

  const json = async (path, init) => {
    const response = await request(path, init)
    try {
      return await response.json()
    } catch {
      throw new ConversationError(ErrorCode.PROTOCOL_ERROR, '服务端返回了非 JSON 响应')
    }
  }

  const toRunRef = (raw) => {
    if (!raw) return null
    return {
      taskId: String(raw.task_id || raw.taskId || ''),
      status: String(raw.status || 'idle'),
      detail: String(raw.detail || ''),
      metadata: raw.metadata && typeof raw.metadata === 'object' ? raw.metadata : undefined
    }
  }

  return {
    async loadConversation() {
      const payload = await json('')
      if (!payload || !Array.isArray(payload.messages)) {
        throw new ConversationError(ErrorCode.PROTOCOL_ERROR, '会话快照缺少 messages')
      }
      return { messages: payload.messages, run: toRunRef(payload.run) }
    },

    async sendMessage({ content, metadata }) {
      return toRunRef(await json('/messages', {
        method: 'POST',
        body: JSON.stringify({ content, metadata })
      }))
    },

    async cancelRun({ taskId }) {
      return toRunRef(await json('/cancel', {
        method: 'POST',
        body: JSON.stringify({ task_id: taskId })
      }))
    },

    async submitInteraction({ taskId, kind, requestId, payload }) {
      await json('/interactions', {
        method: 'POST',
        body: JSON.stringify({ task_id: taskId, kind, request_id: requestId, payload })
      })
    },

    fileUrl(relPath) {
      // Only reachable once a conversation has loaded, which is what populates
      // cachedEndpoint.
      return `${cachedEndpoint}/files/${String(relPath || '').split('/').map(encodeURIComponent).join('/')}`
    },

    /**
     * Yields parsed items, not bytes. A `terminal` item is only produced on an
     * explicit `done` frame: an EOF without one means the connection dropped,
     * which must not be mistaken for a finished run.
     */
    async *streamEvents({ afterId, signal }) {
      const base = await endpoint()
      let response
      try {
        response = await fetch(`${base}/events?after_id=${encodeURIComponent(afterId || 0)}`, {
          credentials: 'same-origin',
          headers: { Accept: 'text/event-stream' },
          signal
        })
      } catch (cause) {
        if (signal?.aborted) return
        throw new StreamInterrupted('事件流连接失败')
      }
      if (!response.ok) throw await errorFromResponse(response)
      if (!response.body) throw new ConversationError(ErrorCode.PROTOCOL_ERROR, '事件流响应没有 body')

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let sawTerminal = false

      try {
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })

          let split
          while ((split = buffer.indexOf('\n\n')) !== -1) {
            const frame = buffer.slice(0, split)
            buffer = buffer.slice(split + 2)
            const item = parseFrame(frame)
            if (!item) continue
            if (item.type === 'terminal') {
              sawTerminal = true
              yield item
              return
            }
            yield item
          }
        }
      } finally {
        reader.releaseLock?.()
      }

      if (!sawTerminal && !signal?.aborted) throw new StreamInterrupted()
    }
  }

  function parseFrame(frame) {
    let name = 'message'
    const dataLines = []
    for (const line of frame.split('\n')) {
      if (line.startsWith(':')) continue // keep-alive
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    }
    if (!dataLines.length) return null
    let data
    try {
      data = JSON.parse(dataLines.join('\n'))
    } catch {
      throw new ConversationError(ErrorCode.PROTOCOL_ERROR, '事件流返回了非 JSON 数据帧')
    }
    if (name === 'done') return { type: 'terminal', run: toRunRef(data) }
    if (name === 'agent-event') return { type: 'event', seqId: Number(data?.seq_id || 0), event: data }
    return null
  }
}
