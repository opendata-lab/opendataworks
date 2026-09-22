import { computed, reactive, ref, shallowRef, triggerRef } from 'vue'
import { ACTIVE_RUN_STATUSES, TERMINAL_RUN_STATUSES } from './runStatus.js'
import { createChatState, processV2Record } from './streamParser.js'
import { normalizeConversationMessage } from './message.js'
import { ErrorCode, StreamInterrupted } from '../transport/errors.js'

const RETRY_DELAYS_MS = [1000, 2000, 4000]
const POLL_INTERVAL_MS = 3000

/**
 * A single conversation: its messages, the run in flight, and the stream that
 * feeds both.
 *
 * Scope is deliberately one conversation. Listing, creating and switching
 * between conversations belong to the product shell — the widget has a history
 * drawer, OntoFoundry has its own session picker, and neither shape belongs in
 * a package the other has to carry.
 *
 * @param {object} options
 * @param {import('vue').Ref<object|null>} options.transport
 * @param {import('vue').Ref<number>} options.generation bumped when the
 *   conversation changes; anything in flight drops its result if it no longer
 *   matches, so a slow response from the previous conversation cannot land here.
 * @param {(payload: object) => void} options.emit
 */
export function useConversation({ transport, generation, emit }) {
  const messages = ref([])
  const run = shallowRef(null)
  const draft = ref('')
  const loading = ref(false)

  // Metadata the host attached when starting a run, remembered so it can be
  // handed back on completion. Rebuilt from the snapshot on load, because
  // in-memory state does not survive the page reload a long run outlives.
  const runMetadata = new Map()

  let abort = null
  let pollTimer = null

  const isActive = computed(() => Boolean(run.value && ACTIVE_RUN_STATUSES.has(run.value.status)))
  const canSend = computed(() => Boolean(transport.value) && !isActive.value)

  const stale = (at) => at !== generation.value

  /** A live assistant turn whose blocks the stream parser writes into. */
  const openAssistant = (taskId) => ({
    id: `local-assistant-${taskId || Date.now()}`,
    role: 'assistant',
    content: '',
    taskId,
    status: 'queued',
    // Streaming from the moment it opens, not from its first block. Leaving it
    // 'idle' made the turn indistinguishable from a finished one before any
    // token arrived: no waiting indicator, and copy / rating offered on an
    // answer that did not exist yet.
    _v2state: reactive({ ...createChatState(), status: 'streaming' }),
  })

  const normalizeMessages = (items) => (Array.isArray(items) ? items : []).map(normalizeConversationMessage)

  const assistantFor = (taskId) =>
    [...messages.value].reverse().find(
      (message) => message.role === 'assistant' && message.taskId === taskId,
    )

  const fail = (error) => {
    emit({
      name: 'error',
      detail: {
        code: error?.code || ErrorCode.PROTOCOL_ERROR,
        message: error?.message || String(error),
        hint: error?.hint || ''
      }
    })
  }

  const setRun = (next) => {
    const previous = run.value
    run.value = next
    if (!next) return
    if (!previous || previous.status !== next.status || previous.taskId !== next.taskId) {
      emit({ name: 'run-change', detail: { taskId: next.taskId, status: next.status, detail: next.detail } })
    }
    if (TERMINAL_RUN_STATUSES.has(next.status)) {
      emit({
        name: 'complete',
        detail: {
          taskId: next.taskId,
          status: next.status,
          metadata: next.metadata || runMetadata.get(next.taskId)
        }
      })
    }
  }

  const stopStream = () => {
    abort?.abort()
    abort = null
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function reset() {
    stopStream()
    messages.value = []
    run.value = null
    runMetadata.clear()
    loading.value = false
  }

  async function load() {
    const at = generation.value
    if (!transport.value) return
    loading.value = true
    try {
      const snapshot = await transport.value.loadConversation()
      if (stale(at)) return
      messages.value = normalizeMessages(snapshot.messages)
      if (snapshot.run?.metadata && snapshot.run.taskId) {
        runMetadata.set(snapshot.run.taskId, snapshot.run.metadata)
      }
      setRun(snapshot.run)
      emit({ name: 'ready', detail: {} })
      if (snapshot.run && ACTIVE_RUN_STATUSES.has(snapshot.run.status)) {
        let assistant = assistantFor(snapshot.run.taskId)
        if (!assistant) {
          assistant = openAssistant(snapshot.run.taskId)
          messages.value = [...messages.value, assistant]
        }
        assistant.status = snapshot.run.status
        assistant._v2state.status = 'streaming'
        subscribe(snapshot.run.taskId, 0, at)
      }
    } catch (error) {
      if (!stale(at)) fail(error)
    } finally {
      if (!stale(at)) loading.value = false
    }
  }

  /**
   * Follow a run to its terminal frame, reconnecting across interruptions.
   *
   * Only an explicit terminal item ends this. An interrupted stream retries
   * from the last sequence id, and once the retries are spent it falls back to
   * polling the snapshot — a run that outlives its connection still has to
   * reach the host.
   */
  async function subscribe(taskId, afterId, at, attempt = 0) {
    if (stale(at) || !transport.value) return
    abort = new AbortController()
    let lastSeq = afterId

    try {
      for await (const item of transport.value.streamEvents({ taskId, afterId: lastSeq, signal: abort.signal })) {
        if (stale(at)) return
        if (item.type === 'event') {
          lastSeq = Math.max(lastSeq, item.seqId || 0)
          // Reduce into the open assistant turn so tool calls, thinking and
          // text appear as they stream. Emitting the raw event and nothing
          // else is what left the element showing an empty conversation until
          // the run ended.
          const assistant = assistantFor(taskId)
          if (assistant) {
            processV2Record(assistant._v2state, item.event)
            triggerRef(messages)
          }
          emit({ name: 'agent-event', detail: item.event })
          continue
        }
        const assistant = assistantFor(taskId)
        if (assistant) {
          assistant.status = item.run.status
          if (item.run.status === 'failed') {
            assistant._v2state.status = 'error'
            assistant._v2state.errorText = item.run.detail || '会话执行失败'
          } else if (TERMINAL_RUN_STATUSES.has(item.run.status)) {
            assistant._v2state.status = 'done'
          }
          triggerRef(messages)
        }
        setRun({ ...item.run, metadata: item.run.metadata || runMetadata.get(item.run.taskId) })
        await refreshMessages(at)
        return
      }
    } catch (error) {
      if (stale(at)) return
      if (!(error instanceof StreamInterrupted)) {
        const assistant = assistantFor(taskId)
        if (assistant) {
          assistant.status = 'failed'
          assistant._v2state.status = 'error'
          assistant._v2state.errorText = error?.message || '会话执行失败'
          triggerRef(messages)
        }
        // The run has to end with the stream. Leaving it on its last active
        // status keeps isActive true forever, which disables the composer and
        // makes retry refuse — an error the user can see but cannot act on.
        setRun({
          taskId,
          status: 'failed',
          detail: error?.message || '会话执行失败',
          metadata: run.value?.metadata || runMetadata.get(taskId)
        })
        return fail(error)
      }

      fail(error)
      const delay = RETRY_DELAYS_MS[attempt]
      if (delay !== undefined) {
        pollTimer = setTimeout(() => subscribe(taskId, lastSeq, at, attempt + 1), delay)
        return
      }
      poll(at)
    }
  }

  /** Last resort once reconnection is exhausted: ask for the snapshot instead. */
  function poll(at) {
    pollTimer = setTimeout(async () => {
      if (stale(at) || !transport.value) return
      try {
        const snapshot = await transport.value.loadConversation()
        if (stale(at)) return
        messages.value = normalizeMessages(snapshot.messages)
        setRun(snapshot.run)
        if (snapshot.run && ACTIVE_RUN_STATUSES.has(snapshot.run.status)) poll(at)
      } catch {
        poll(at)
      }
    }, POLL_INTERVAL_MS)
  }

  /**
   * Replace the live turns with the server's version once a run ends.
   *
   * A snapshot that has not caught up yet must not win. The backend persists
   * the assistant row slightly after the run closes, so a refetch can return
   * fewer turns than were just streamed — overwriting unconditionally makes
   * the answer the user watched arrive flash and vanish.
   */
  async function refreshMessages(at) {
    try {
      const snapshot = await transport.value.loadConversation()
      if (stale(at)) return
      if (snapshot.messages.length >= messages.value.length) {
        messages.value = normalizeMessages(snapshot.messages)
      }
    } catch (error) {
      if (!stale(at)) fail(error)
    }
  }

  async function send(content, { metadata, clearDraft = true, attachments = [], settings } = {}) {
    const text = String(content ?? draft.value).trim()
    const files = Array.isArray(attachments) ? attachments.filter((file) => file?.relPath) : []
    if ((!text && !files.length) || !canSend.value) return false
    const at = generation.value

    try {
      const request = { content: text, metadata }
      if (files.length) request.attachments = files
      if (settings && Object.keys(settings).length) request.settings = { ...settings }
      const started = await transport.value.sendMessage(request)
      if (stale(at)) return false
      if (clearDraft) draft.value = ''
      if (started?.taskId && metadata) runMetadata.set(started.taskId, metadata)
      setRun(started)
      // Show the user's turn and an assistant placeholder immediately. Waiting
      // for a refetch would leave the conversation visually frozen for the
      // length of the run.
      messages.value = [
        ...messages.value,
        { id: `local-user-${Date.now()}`, role: 'user', content: text, attachments: files },
        openAssistant(started?.taskId),
      ]
      if (started?.taskId) subscribe(started.taskId, 0, at)
      return true
    } catch (error) {
      if (!stale(at)) fail(error)
      return false
    }
  }

  async function retry(message) {
    if (!message || isActive.value) return false
    const index = messages.value.findIndex((item) => item.id === message.id)
    for (let cursor = (index < 0 ? messages.value.length : index) - 1; cursor >= 0; cursor -= 1) {
      const candidate = messages.value[cursor]
      if (candidate?.role !== 'user') continue
      return send(candidate.content, { attachments: candidate.attachments || [] })
    }
    return false
  }

  async function cancel() {
    if (!run.value?.taskId || !transport.value) return
    const at = generation.value
    try {
      const next = await transport.value.cancelRun({ taskId: run.value.taskId })
      if (stale(at)) return
      stopStream()
      setRun(next)
    } catch (error) {
      if (!stale(at)) fail(error)
    }
  }

  async function submitInteraction(payload) {
    if (!transport.value) return
    try {
      await transport.value.submitInteraction(payload)
    } catch (error) {
      fail(error)
    }
  }

  /**
   * Record a thumbs up/down, clearing it when the same one is pressed twice.
   *
   * Applied optimistically and rolled back on failure: a rating is a one-click
   * aside, and waiting on a round trip to acknowledge it reads as a dead button.
   * Rolling back matters just as much — a rating that silently failed to save
   * looks identical to one that saved, and the user never knows to press again.
   */
  async function submitFeedback(message, value) {
    if (!message || typeof transport.value?.submitFeedback !== 'function') return false
    const previous = String(message.feedback || '')
    const next = previous === String(value || '') ? '' : String(value || '')

    message.feedback = next
    triggerRef(messages)
    const at = generation.value
    try {
      const saved = await transport.value.submitFeedback({ messageId: message.id, feedback: next })
      if (stale(at)) return false
      message.feedback = String(saved?.feedback ?? next)
      triggerRef(messages)
      return true
    } catch (error) {
      if (stale(at)) return false
      message.feedback = previous
      triggerRef(messages)
      fail(error)
      return false
    }
  }

  return {
    messages, run, draft, loading,
    isActive, canSend,
    load, reset, send, retry, cancel, submitInteraction, submitFeedback,
    stopStream
  }
}
