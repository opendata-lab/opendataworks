import { toRunStatus } from '../../../packages/agent-conversation/src/core/runStatus.js'
import { StreamInterrupted } from '../../../packages/agent-conversation/src/transport/errors.js'

/**
 * Adapts this application's DataAgent client to the SDK transport contract.
 *
 * It lives here, not in the package: this is the one caller that legitimately
 * talks to DataAgent directly, because the widget *is* DataAgent's own UI.
 * Every other host proxies through its own backend, which is what keeps the
 * DataAgent address and credentials out of third-party pages.
 *
 * @param {object} api result of createNl2SqlApiClient
 * @param {string} topicId conversation this transport is bound to
 * @param {object} [context] run parameters the shell owns
 * @param {() => string} [context.getAgentId]
 * @param {() => string} [context.getProviderId]
 * @param {() => string} [context.getModel]
 * @param {() => string} [context.getPermissionMode]
 */
export function createNl2SqlTransport(api, topicId, context = {}) {
  const {
    getAgentId = () => '',
    getProviderId = () => '',
    getModel = () => '',
    getPermissionMode = () => '',
  } = context

  const toRunRef = (task, metadata) => {
    if (!task) return null
    return {
      taskId: String(task.task_id || task.taskId || ''),
      status: toRunStatus(task.task_status || task.status),
      detail: String(task.detail || task.error?.message || ''),
      metadata,
    }
  }

  /**
   * DataAgent's message rows are not the SDK's shape. Mapping explicitly beats
   * passing them through: `sender_type` is not `role`, and a renderer reading
   * `message.role` would silently treat every row as an assistant turn.
   */
  const toMessage = (row) => ({
    id: String(row?.message_id || row?.id || ''),
    role: String(row?.sender_type) === 'user' ? 'user' : 'assistant',
    content: String(row?.content || ''),
    blocks: Array.isArray(row?.blocks) ? row.blocks : undefined,
    records: Array.isArray(row?.records) ? row.records : undefined,
    attachments: (row?.attachments || []).map((file) => ({
      name: String(file?.name || file?.rel_path || ''),
      relPath: String(file?.rel_path || ''),
      mediaType: file?.content_type || undefined,
      size: Number(file?.size) || undefined,
    })),
    taskId: row?.task_id ? String(row.task_id) : undefined,
    createdAt: row?.created_at ? String(row.created_at) : undefined,
    // A run that already failed carries its outcome on the row. Dropping these
    // meant reloading a failed conversation rendered an empty assistant turn
    // with no error and nothing to retry.
    status: row?.status ? String(row.status) : undefined,
    error: row?.error ?? undefined,
    feedback: row?.feedback ? String(row.feedback) : '',
    // Where to resume the event stream for a run still in flight. Without it
    // the SDK restarts from 0 and replays thinking, tool calls and answer text
    // the user has already seen.
    resumeAfterSeq: Number(row?.resume_after_seq) || 0,
  })

  return {
    /**
     * DataAgent pages history at 200 per request and caps at 500, so a single
     * call silently truncates a long conversation. Page to exhaustion.
     */
    async loadConversation() {
      if (!topicId) return { messages: [], run: null }

      const rows = []
      let page = 1
      for (;;) {
        const payload = await api.topicApi.getTopicMessages(topicId, { page, page_size: 500 })
        const items = Array.isArray(payload?.items) ? payload.items : []
        rows.push(...items)
        const total = Number(payload?.total || 0)
        if (!items.length || rows.length >= total) break
        page += 1
      }

      const topic = await api.topicApi.getTopic(topicId)
      const currentTaskId = String(topic?.current_task_id || '')

      return {
        messages: rows.map(toMessage),
        run: currentTaskId
          ? toRunRef({ task_id: currentTaskId, task_status: topic?.current_task_status })
          : null,
      }
    },

    async sendMessage({ content, metadata }) {
      // The shell owns agent, provider, model and permission mode; omitting
      // them would submit to the default agent at the interactive timeout
      // tier, which is not what the user selected.
      const submitted = await api.taskApi.deliverMessage({
        topic_id: topicId,
        content,
        provider_id: getProviderId() || undefined,
        model: getModel() || undefined,
        agent_id: getAgentId() || undefined,
        permission_mode: getPermissionMode() || undefined,
        debug: false,
        execution_mode: 'auto',
      })
      return toRunRef({ ...submitted, task_status: submitted?.task_status || 'waiting' }, metadata)
    },

    async cancelRun({ taskId }) {
      return toRunRef(await api.taskApi.cancelTask(taskId))
    },

    async submitInteraction({ taskId, kind, requestId, payload }) {
      if (kind === 'permission') {
        await api.taskApi.submitPermissionDecision(
          taskId, requestId, payload?.decision, payload?.note || '',
        )
        return
      }
      await api.taskApi.submitQuestionAnswer(taskId, requestId, payload?.answers || [])
    },

    fileUrl(relPath) {
      return api.topicApi.fileUrl(topicId, relPath)
    },

    /**
     * Bridges the client's callback-style stream to an async iterable.
     *
     * `taskApi.streamSdkEvents` pushes records into an `onRecord` callback and
     * resolves when the socket closes. The SDK pulls. A queue plus a waiter
     * connects the two without dropping records that arrive between pulls.
     *
     * Closure alone does not mean the run finished — the same bytes appear
     * when a connection drops — so the task's status decides, and only a
     * genuinely terminal one becomes a terminal item.
     */
    async *streamEvents({ taskId, afterId, signal }) {
      const queue = []
      let notify = null
      let finished = false
      let failure = null

      const push = (record) => {
        queue.push(record)
        notify?.()
        notify = null
      }

      const pump = api.taskApi
        .streamSdkEvents(taskId, { afterId, signal, onRecord: push })
        .then(() => { finished = true })
        .catch((error) => { failure = error; finished = true })
        .finally(() => { notify?.(); notify = null })

      let lastSeq = afterId
      for (;;) {
        if (queue.length) {
          const record = queue.shift()
          if (signal?.aborted) return
          lastSeq = Math.max(lastSeq, Number(record?.seq_id || 0))
          yield { type: 'event', seqId: lastSeq, event: record }
          continue
        }
        if (finished) break
        await new Promise((resolve) => { notify = resolve })
      }

      await pump
      if (signal?.aborted) return
      if (failure) throw new StreamInterrupted(`事件流中断: ${failure.message || failure}`)

      const task = await api.taskApi.getTask(taskId)
      const status = toRunStatus(task?.task_status)
      if (status === 'finished' || status === 'failed' || status === 'cancelled') {
        yield { type: 'terminal', run: toRunRef(task) }
        return
      }
      throw new StreamInterrupted('上游事件流提前结束，任务仍在运行')
    },

    // Direct access means every optional capability is available here, unlike
    // a host whose backend proxies only what it chose to expose.
    async executeSql(input) {
      return api.queryApi.executeSql({ ...input, topicId })
    },

    async setPermissionMode(mode) {
      await api.topicApi.updateTopic(topicId, { permission_mode: mode })
    },

    /**
     * Put the picked files in the topic's workspace and hand back references.
     *
     * Uploaded one at a time because that is the endpoint DataAgent exposes;
     * the SDK only needs the references back in order.
     */
    async uploadFiles(files) {
      const uploaded = []
      for (const file of files) {
        const saved = await api.topicApi.uploadFile(topicId, file)
        const relPath = String(saved?.rel_path || '')
        if (!relPath) continue
        uploaded.push({
          name: String(saved?.name || file.name || relPath),
          relPath,
          mediaType: saved?.content_type || file.type || undefined,
          size: Number(saved?.size ?? file.size) || undefined,
        })
      }
      return uploaded
    },

    /**
     * Read a workspace file as bytes, for preview and for download.
     *
     * Goes through fetch rather than handing out a URL because a bare browser
     * navigation cannot carry the site and access-key headers this runtime
     * requires — the same reason the chat surfaces already download via Blob.
     */
    readFile(relPath) {
      return api.topicApi.fetchFileBlob(topicId, relPath)
    },

    async submitFeedback({ messageId, feedback }) {
      const saved = await api.topicApi.updateMessageFeedback(topicId, messageId, feedback)
      return { feedback: String(saved?.feedback ?? feedback) }
    },
  }
}
