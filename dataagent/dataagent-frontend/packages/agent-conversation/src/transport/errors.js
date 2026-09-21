// Error codes the element reports through the `dataagent-error` event. Hosts
// switch on these; the message and hint are for humans.
export const ErrorCode = Object.freeze({
  TRANSPORT_UNREACHABLE: 'transport_unreachable',
  CONVERSATION_UNAVAILABLE: 'conversation_unavailable',
  STREAM_INTERRUPTED: 'stream_interrupted',
  PROTOCOL_ERROR: 'protocol_error'
})

export class ConversationError extends Error {
  constructor(code, message, hint = '') {
    super(message)
    this.name = 'ConversationError'
    this.code = code
    this.hint = hint
  }
}

/** The stream ended without a terminal frame — not the same as a finished run. */
export class StreamInterrupted extends ConversationError {
  constructor(message = '事件流意外结束') {
    super(ErrorCode.STREAM_INTERRUPTED, message)
    this.name = 'StreamInterrupted'
  }
}

/**
 * Turn a failed response into a ConversationError.
 *
 * The host BFF is expected to answer errors with `{ message, hint }`; `hint`
 * is what tells an operator which of several places to go fix. A body that is
 * not JSON is reported as a protocol error rather than guessed at.
 */
export async function errorFromResponse(response) {
  let payload = null
  try {
    payload = await response.json()
  } catch {
    return new ConversationError(
      ErrorCode.CONVERSATION_UNAVAILABLE,
      `请求失败 (${response.status})`
    )
  }
  const message = String(payload?.message || payload?.detail || `请求失败 (${response.status})`)
  return new ConversationError(ErrorCode.CONVERSATION_UNAVAILABLE, message, String(payload?.hint || ''))
}
