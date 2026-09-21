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
 */
export function createNl2SqlTransport(api, topicId) {
  const toRunRef = (task, metadata) => {
    if (!task) return null
    return {
      taskId: String(task.task_id || task.taskId || ''),
      status: toRunStatus(task.task_status || task.status),
      detail: String(task.detail || task.error?.message || ''),
      metadata
    }
  }

  return {
    /**
     * DataAgent pages history at 200 per request and caps at 500, so a single
     * call silently truncates a long conversation. Page to exhaustion.
     */
    async loadConversation() {
      if (!topicId) return { messages: [], run: null }

      const messages = []
      let page = 1
      for (;;) {
        const payload = await api.topicApi.getTopicMessages(topicId, { page, page_size: 500 })
        const items = Array.isArray(payload?.items) ? payload.items : []
        messages.push(...items)
        const total = Number(payload?.total || 0)
        if (!items.length || messages.length >= total) break
        page += 1
      }

      const topic = await api.topicApi.getTopic(topicId)
      const currentTaskId = String(topic?.current_task_id || '')
      const run = currentTaskId
        ? toRunRef({ task_id: currentTaskId, task_status: topic?.current_task_status })
        : null

      return { messages, run }
    },

    async sendMessage({ content, metadata }) {
      const submitted = await api.taskApi.deliverMessage({ topic_id: topicId, content })
      return toRunRef({ ...submitted, task_status: submitted?.task_status || 'waiting' }, metadata)
    },

    async cancelRun({ taskId }) {
      return toRunRef(await api.taskApi.cancelTask(taskId))
    },

    async submitInteraction({ taskId, kind, requestId, payload }) {
      if (kind === 'permission') {
        await api.taskApi.submitPermissionDecision(taskId, requestId, payload?.decision, payload?.note || '')
        return
      }
      await api.taskApi.submitQuestionAnswer(taskId, requestId, payload?.answers || [])
    },

    fileUrl(relPath) {
      return api.topicApi.fileUrl(topicId, relPath)
    },

    /**
     * DataAgent's native stream is unnamed `data:` frames that stop at EOF.
     * The SDK needs an explicit terminal item, so EOF is resolved by asking
     * for the task's status — and only a genuinely terminal status becomes a
     * terminal item. An upstream stream that ends while the run is still going
     * is an interruption to reconnect from, not a completion.
     */
    async *streamEvents({ taskId, afterId, signal }) {
      let lastSeq = afterId

      for await (const record of api.eventApi.streamSdkEvents(taskId, { afterId, signal })) {
        if (signal?.aborted) return
        lastSeq = Math.max(lastSeq, Number(record?.seq_id || 0))
        yield { type: 'event', seqId: lastSeq, event: record }
      }

      if (signal?.aborted) return

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

    async submitFeedback(messageId, value) {
      await api.topicApi.updateMessageFeedback(topicId, messageId, value === 1 ? 'up' : value === -1 ? 'down' : '')
    }
  }
}
