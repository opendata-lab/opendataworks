<template>
  <div class="dac-messages" part="messages" ref="scrollRef" @scroll.passive="handleScroll">
    <div class="dac-messages-inner" part="messages-inner">
    <div v-if="!messages.length" class="dac-empty" part="empty">
      <slot name="empty">暂无消息</slot>
    </div>

    <MessageItem
      v-for="message in messages"
      :key="message.id"
      :message="message"
      :render-version="message.renderVersion || 0"
      :presentation="presentationFor(message)"
      :file-url="fileUrl"
      :disabled="disabled"
      :can-rate="canRate"
      :can-preview-files="canPreviewFiles"
      :show-attachments="showAttachments"
      :activity-label="activityLabel"
      @rendered="scrollToBottom()"
      @preview="(file) => $emit('preview', file)"
      @decide="(payload) => $emit('decide', payload)"
      @answer="(payload) => $emit('answer', payload)"
      @retry="(message) => $emit('retry', message)"
      @feedback="(payload) => $emit('feedback', payload)"
    />
    </div>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { blocksOf, createBlockPresentation } from '../core/blockPresentation.js'
import MessageItem from './MessageItem.vue'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  fileUrl: { type: Function, default: (p) => p },
  disabled: { type: Boolean, default: false },
  canRate: { type: Boolean, default: false },
  canPreviewFiles: { type: Boolean, default: false },
  /** Hosts that surface generated files elsewhere turn the cards off. */
  showAttachments: { type: Boolean, default: true },
  /** Shown while an open turn has produced nothing yet. */
  activityLabel: { type: String, default: '正在处理…' },
})

defineEmits(['decide', 'answer', 'retry', 'feedback', 'preview'])
const scrollRef = ref(null)

// Stable across refreshMessages replacing message objects. Never pass freshly
// allocated state/callback props to historical items on each list render.
const presentations = new Map()
// New sends start with a local message ID, then receive a persisted ID. The
// same assistant task carries the expansion state across that one transition.
const taskPresentations = new Map()
const presentationFor = (message) => {
  const taskId = message.role === 'assistant' ? message.taskId : null
  let presentation = presentations.get(message.id) || (taskId && taskPresentations.get(taskId))
  if (!presentation) presentation = createBlockPresentation()
  presentations.set(message.id, presentation)
  if (taskId) taskPresentations.set(taskId, presentation)
  return presentation
}
watch(() => props.messages.map(message => [message.id, message.role === 'assistant' ? message.taskId : null]), (keys) => {
  const ids = new Set(keys.map(([id]) => id))
  const tasks = new Set(keys.map(([, taskId]) => taskId).filter(Boolean))
  for (const id of presentations.keys()) if (!ids.has(id)) presentations.delete(id)
  for (const taskId of taskPresentations.keys()) if (!tasks.has(taskId)) taskPresentations.delete(taskId)
})

let highlightTimer = null

/**
 * Bring one message into view and mark it, for a host restoring a deep link.
 *
 * Exposed as a method so the host never has to reach through the shadow root to
 * find a node: `element.shadowRoot.querySelector(...)` couples the host to
 * internal markup and silently stops working the moment that markup changes.
 */
const focusMessage = async (messageId) => {
  const wanted = String(messageId ?? '')
  if (!wanted) return false
  await nextTick()
  const target = [...(scrollRef.value?.querySelectorAll('[data-message-id]') || [])]
    .find((node) => node.dataset.messageId === wanted)
  if (!target) return false

  target.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
  target.classList.add('is-focused')
  if (highlightTimer) clearTimeout(highlightTimer)
  highlightTimer = setTimeout(() => target.classList.remove('is-focused'), 2000)
  return true
}

// Scroll state does not affect the template. Events only queue a read;
// reading layout in the event itself forces layout after every DOM patch.
let autoScroll = true
let scrollFrame = null
let measureScroll = false
let followScroll = false
let forceScroll = false
let programmaticTop = null
let measuredBottom = null

const scheduleScroll = () => {
  if (scrollFrame !== null) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = null
    const el = scrollRef.value
    if (el) {
      // All reads precede the optional write, once per frame.
      const height = el.scrollHeight
      const top = el.scrollTop
      const viewport = el.clientHeight
      const bottom = Math.max(0, height - viewport)
      // Content may grow between a user's scroll event and this frame.
      // Compare against the previous geometry when a follow was also queued.
      const userBottom = followScroll && measuredBottom !== null ? Math.min(bottom, measuredBottom) : bottom
      if (measureScroll && top !== programmaticTop) autoScroll = userBottom - top < 60
      measuredBottom = bottom
      if (followScroll && (forceScroll || autoScroll) && top !== bottom) {
        el.scrollTop = bottom
        programmaticTop = bottom
      } else if (measureScroll) programmaticTop = null
    }
    measureScroll = followScroll = forceScroll = false
  })
}
const handleScroll = () => {
  measureScroll = true
  scheduleScroll()
}
const scrollToBottom = (force = false) => {
  if (!force && !autoScroll) return
  followScroll = true
  forceScroll ||= force
  scheduleScroll()
}

defineExpose({ focusMessage, scrollToBottom })

// Follow the conversation as it grows, but only when already near the bottom,
// so reading back through history is not yanked away by an arriving message.
watch(
  () => {
    const last = props.messages.at(-1)
    const blocks = blocksOf(last)
    const tail = blocks.at(-1)
    return [props.messages, props.messages.length, last?.id, last?.renderVersion,
      last?.status, last?._v2state?.status, last?.content, blocks.length,
      tail?.content, tail?.status, tail?.output]
  },
  () => scrollToBottom(),
  { flush: 'post', immediate: true },
)

onBeforeUnmount(() => {
  if (scrollFrame !== null) cancelAnimationFrame(scrollFrame)
  if (highlightTimer) clearTimeout(highlightTimer)
})
</script>

<style>
.dac-messages { flex: 1; min-height: 0; overflow-y: auto; }
/* Width and gutters are the host's call — the message column and the composer
   read the same two tokens so the two stay aligned with each other. */
.dac-messages-inner {
  box-sizing: border-box;
  width: 100%;
  max-width: var(--dac-content-max-width, none);
  margin: 0 auto;
  padding-block: var(--dac-content-padding-block, 16px);
  padding-inline: var(--dac-content-padding-inline, 16px);
}
.dac-empty { color: var(--dac-text-muted, #64748b); font-size: 14px; }
.dac-message { margin-bottom: var(--dac-message-gap, 14px); display: flex; }
.dac-message-user { justify-content: flex-end; }
.dac-user {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  max-width: 72%;
}
.dac-user .dac-bubble { max-width: 100%; }
.dac-message.is-focused {
  border-radius: var(--dac-bubble-radius, 14px);
  outline: 2px solid var(--dac-accent, #0f766e);
  outline-offset: 4px;
}
.dac-assistant {
  width: 100%;
  max-width: 88%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.dac-bubble {
  max-width: 100%;
  word-break: break-word;
}
.dac-message-user .dac-bubble {
  padding: 10px 16px;
  border-radius: var(--dac-user-bubble-radius, 16px 16px 4px 16px);
  background: var(--dac-user-bubble-bg, #ecfdf5);
  color: var(--dac-user-bubble-color, #065f46);
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
}
.dac-assistant .dac-bubble {
  max-width: 100%;
  padding: 0;
  border-radius: 0;
  background: transparent;
  color: #162131;
  font-size: 14px;
  line-height: 1.7;
}
.dac-assistant .dac-bubble p { margin: 0 0 10px; }
.dac-assistant .dac-bubble p:last-child { margin: 0; }
.dac-assistant .dac-bubble pre {
  background: #f3f7fb;
  border-radius: 8px;
  padding: 12px 16px;
  overflow-x: auto;
  font-size: 13px;
}
.dac-assistant .dac-bubble code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  padding: 2px 6px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--dac-primary, #10b981) 8%, transparent);
  color: var(--dac-primary, #10b981);
}
.dac-assistant .dac-bubble table { border-collapse: collapse; width: 100%; margin: 10px 0; }
.dac-assistant .dac-bubble th,
.dac-assistant .dac-bubble td { border: 1px solid #dbe3ef; padding: 6px 12px; font-size: 13px; }
.dac-assistant .dac-bubble th { background: #f4f7fb; font-weight: 600; }
.dac-attachments { margin: 6px 0 0; padding-left: 18px; font-size: 13px; }
.dac-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: currentColor;
  animation: dac-blink 1s step-end infinite;
}
@keyframes dac-blink { 50% { opacity: 0; } }
.dac-activity {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--dac-text-muted, #64748b);
  font-size: 13px;
}
.dac-activity-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--dac-primary, #10b981);
  animation: dac-pulse 1.2s ease-in-out infinite;
}
@keyframes dac-pulse { 50% { opacity: 0.25; } }
.dac-preview-open {
  margin-left: 8px;
  padding: 0 6px;
  border: 1px solid var(--dac-border-color, #e2e8f0);
  border-radius: 6px;
  background: transparent;
  color: var(--dac-text-muted, #64748b);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.dac-error {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border: 1px solid var(--dac-error-border, #fecaca);
  border-radius: var(--dac-bubble-radius, 14px);
  background: var(--dac-error-bg, #fef2f2);
  color: var(--dac-error-color, #b91c1c);
  font-size: 13px;
}
.dac-error-text { flex: 1; word-break: break-word; }
.dac-retry {
  flex: none;
  padding: 4px 12px;
  border: 1px solid currentColor;
  border-radius: 999px;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.dac-retry:hover { background: var(--dac-error-border, #fecaca); }
</style>
