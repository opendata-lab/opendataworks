import { unref } from 'vue'
import { getMessageCopyText } from './chatMessage'

const noop = () => {}

function resolveValue(value) {
  return typeof value === 'function' ? value() : unref(value)
}

async function copyTextToClipboard(text) {
  if (typeof navigator !== 'undefined' && navigator.clipboard && typeof window !== 'undefined' && window.isSecureContext) {
    await navigator.clipboard.writeText(text)
    return
  }
  if (typeof document === 'undefined') {
    throw new Error('clipboard unavailable')
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  textarea.style.top = '-9999px'
  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()
  document.execCommand('copy')
  document.body.removeChild(textarea)
}

export function useChatMessageActions(options = {}) {
  const {
    api,
    topicId,
    cleanText,
    notifyCopied = noop,
    notifyError = noop,
    emitEvent = noop,
  } = options

  const messageCopyText = (message) => getMessageCopyText(message, { cleanText })

  const handleCopyMessage = async (message) => {
    const text = messageCopyText(message)
    if (!text) return
    try {
      await copyTextToClipboard(text)
      notifyCopied('已复制')
      emitEvent({ name: 'message:copied', payload: { id: message?.id || '' } })
    } catch (error) {
      notifyError('复制失败，请手动复制', error)
    }
  }

  const toggleMessageFeedback = async (message, value) => {
    if (!message || typeof message !== 'object') return
    const previousFeedback = String(message.feedback || '')
    const nextFeedback = previousFeedback === value ? '' : value
    const currentTopicId = String(resolveValue(topicId) || '')
    const messageId = String(message.id || '')

    message.feedback = nextFeedback
    if (!currentTopicId || !messageId) {
      message.feedback = previousFeedback
      notifyError('反馈保存失败，请稍后重试')
      return
    }

    try {
      const updated = await api.topicApi.updateMessageFeedback(currentTopicId, messageId, nextFeedback)
      message.feedback = String(updated?.feedback ?? nextFeedback)
      emitEvent({ name: 'message:feedback', payload: { id: messageId, feedback: message.feedback } })
    } catch (error) {
      message.feedback = previousFeedback
      notifyError('反馈保存失败，请稍后重试', error)
    }
  }

  return {
    messageCopyText,
    handleCopyMessage,
    toggleMessageFeedback,
  }
}
