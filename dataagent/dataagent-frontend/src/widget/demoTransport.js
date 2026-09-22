/**
 * Canned conversation for the built-in `demo` agent.
 *
 * The widget used to fake a demo run by writing messages into the chat state
 * directly. That stopped being possible once the SDK owned the message list —
 * and the SDK is the better place for it anyway: a transport is exactly the
 * seam for "where do responses come from", so the demo becomes a source of
 * responses rather than a special case threaded through the UI.
 *
 * The records emitted here are the same shape the live stream produces, so
 * they run through the same parser and render identically.
 */

const PROGRESS_STEPS = [
  { id: 'tool-0', step: '正在解析问题语义...', label: 'text-to-sql' },
  { id: 'tool-1', step: '正在匹配数据库 Schema...', label: 'text-to-sql' },
  { id: 'tool-2', step: '正在生成执行 SQL 语句...', label: 'run-sql' },
  { id: 'tool-3', step: '已成功获取数据，正在整理报表...', label: 'render-chart' },
]

const REPLY = `这是一个演示回答。真实环境下，我会基于你的数据生成 SQL 并返回结果。

如有其他疑问，请随时提问！`

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

export function createDemoTransport() {
  const messages = []
  let seq = 0
  let run = null

  const next = () => ({ seq_id: ++seq })

  return {
    async loadConversation() {
      return { messages: [...messages], run }
    },

    async sendMessage({ content, metadata }) {
      messages.push({
        id: `demo-user-${messages.length + 1}`,
        role: 'user',
        content,
        createdAt: new Date().toISOString(),
      })
      run = { taskId: `demo-${++seq}`, status: 'running', detail: '演示运行中', metadata }
      return run
    },

    async cancelRun() {
      run = { ...(run || {}), status: 'cancelled', detail: '已取消' }
      return run
    },

    async submitInteraction() {},

    fileUrl(relPath) {
      return `#demo/${relPath}`
    },

    async *streamEvents({ signal }) {
      for (const [index, { id, step, label }] of PROGRESS_STEPS.entries()) {
        if (signal?.aborted) return
        yield {
          type: 'event',
          ...next(),
          event: {
            record_type: 'stream',
            data: {
              type: 'content_block_start',
              index,
              content_block: { type: 'tool_use', id, name: label },
            },
          },
        }
        await delay(500)
        if (signal?.aborted) return
        yield {
          type: 'event',
          ...next(),
          event: { record_type: 'stream', data: { type: 'content_block_stop', index } },
        }
        yield {
          type: 'event',
          ...next(),
          event: {
            record_type: 'tool_result',
            data: { tool_use_id: id, content: `[Demo] 已完成: ${step}` },
          },
        }
      }

      const textIndex = PROGRESS_STEPS.length
      yield {
        type: 'event',
        ...next(),
        event: {
          record_type: 'stream',
          data: { type: 'content_block_start', index: textIndex, content_block: { type: 'text' } },
        },
      }
      for (const char of REPLY) {
        if (signal?.aborted) return
        yield {
          type: 'event',
          ...next(),
          event: {
            record_type: 'stream',
            data: {
              type: 'content_block_delta',
              index: textIndex,
              delta: { type: 'text_delta', text: char },
            },
          },
        }
        await delay(15)
      }
      yield {
        type: 'event',
        ...next(),
        event: { record_type: 'stream', data: { type: 'content_block_stop', index: textIndex } },
      }

      messages.push({
        id: `demo-assistant-${messages.length + 1}`,
        role: 'assistant',
        content: REPLY,
        createdAt: new Date().toISOString(),
      })
      run = { ...(run || {}), status: 'finished', detail: '演示完成' }
      yield { type: 'terminal', run }
    },
  }
}
