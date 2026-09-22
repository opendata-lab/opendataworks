<template>
  <div class="dac-messages" part="messages" ref="scrollRef">
    <div v-if="!messages.length" class="dac-empty" part="empty">
      <slot name="empty">暂无消息</slot>
    </div>

    <article
      v-for="message in messages"
      :key="message.id"
      class="dac-message"
      :class="`dac-message-${message.role}`"
      :part="`message message-${message.role}`"
      :data-message-id="message.id"
    >
      <!-- A user turn is plain text. Rendering it as markdown would let a
           pasted snippet change how the page looks. -->
      <div v-if="message.role === 'user'" class="dac-bubble" part="bubble user-bubble">{{ message.content }}</div>

      <div v-else class="dac-assistant" part="assistant-turn">
        <!-- Blocks are the live shape: a streaming turn has them before it has
             any content, and interaction requests only ever exist here. A
             renderer that reads `content` alone shows an empty conversation
             until the run ends, and never shows a permission prompt at all. -->
        <template v-if="blocksOf(message).length">
          <template v-for="(block, index) in blocksOf(message)" :key="`${message.id}-${index}`">
            <div
              v-if="block.type === 'text'"
              class="dac-bubble"
              part="bubble assistant-bubble"
            >
              <!-- An answer can carry a chart inline. Rendering the block as
                   one markdown string leaks the spec JSON into the prose. -->
              <template v-for="(segment, part) in segmentsOf(block.content || block.text || '')" :key="part">
                <div v-if="segment.type === 'text'" v-html="markdown(segment.value)" />
                <ChartSpecView v-else :spec="segment.spec" />
              </template>
              <span v-if="block.status === 'streaming'" class="dac-cursor" part="cursor" aria-hidden="true" />
            </div>
            <ThinkingBlock
              v-else-if="block.type === 'thinking'"
              :block="block"
            />
            <ToolOutput
              v-else-if="block.type === 'tool_use'"
              :tool="blockToToolProp(block)"
            />
            <PermissionCard
              v-else-if="block.type === 'permission_request'"
              :block="block"
              :disabled="disabled"
              @decide="(payload) => $emit('decide', { ...payload, taskId: message.taskId })"
            />
            <QuestionCard
              v-else-if="block.type === 'question_request'"
              :block="block"
              :disabled="disabled"
              @answer="(payload) => $emit('answer', { ...payload, taskId: message.taskId })"
            />
          </template>
        </template>

        <!-- An open turn that has not produced anything yet. Without this the
             conversation shows an empty bubble for however long the agent
             spends before its first token, which reads as a stall. -->
        <p v-else-if="isStreaming(message)" class="dac-activity" part="activity">
          <span class="dac-activity-dot" aria-hidden="true" />{{ activityLabel }}
        </p>

        <div v-else class="dac-bubble" part="bubble assistant-bubble">
          <template v-for="(segment, part) in segmentsOf(message.content || '')" :key="part">
            <div v-if="segment.type === 'text'" v-html="markdown(segment.value)" />
            <ChartSpecView v-else :spec="segment.spec" />
          </template>
        </div>

        <!-- A failed turn keeps whatever it managed to produce; the card is
             appended rather than replacing it, so a run that died mid-answer
             still shows the half it finished. -->
        <div v-if="errorOf(message)" class="dac-error" part="error" role="alert">
          <span class="dac-error-text">{{ errorOf(message) }}</span>
          <button
            v-if="!disabled"
            type="button"
            class="dac-retry"
            part="retry-button"
            @click="$emit('retry', message)"
          >重试</button>
        </div>

        <MessageActions
          v-if="!isStreaming(message)"
          :message="message"
          :can-rate="canRate"
          @feedback="(payload) => $emit('feedback', payload)"
        />
      </div>

      <ul v-if="message.attachments?.length" class="dac-attachments" part="attachments">
        <li v-for="file in message.attachments" :key="file.relPath">
          <a :href="fileUrl(file.relPath)" target="_blank" rel="noreferrer">{{ file.name }}</a>
          <!-- Preview is a host capability. Without readFile the link is all
               there is, which is still the whole file — just not in place. -->
          <button
            v-if="canPreview(file)"
            type="button"
            class="dac-preview-open"
            data-action="preview"
            @click="$emit('preview', file)"
          >预览</button>
        </li>
      </ul>
    </article>
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import { extractErrorText, renderMarkdown } from '../core/message.js'
import { blockToToolProp } from '../core/streamParser.js'
import ToolOutput from './ToolOutput.vue'
import ThinkingBlock from './ThinkingBlock.vue'
import PermissionCard from './PermissionCard.vue'
import QuestionCard from './QuestionCard.vue'
import MessageActions from './MessageActions.vue'
import { previewKindFor } from '../core/previewKind.js'
import { splitChartSpecText } from '../core/chartSpec.js'
import ChartSpecView from './ChartSpecView.vue'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  fileUrl: { type: Function, default: (p) => p },
  disabled: { type: Boolean, default: false },
  canRate: { type: Boolean, default: false },
  canPreviewFiles: { type: Boolean, default: false },
  /** Shown while an open turn has produced nothing yet. */
  activityLabel: { type: String, default: '正在处理…' },
})

/**
 * Split answer text into prose and the charts embedded in it.
 *
 * An agent writes a chart as a `chart_spec` object inside its answer; rendering
 * the whole block as one markdown string puts the raw JSON on screen.
 */
const segmentsOf = (text) => splitChartSpecText(text)

const canPreview = (file) =>
  props.canPreviewFiles && Boolean(previewKindFor(file?.relPath || file?.name))

defineEmits(['decide', 'answer', 'retry', 'feedback', 'preview'])

// Copy and rating are offered once a turn has settled. Mid-stream they would
// act on half an answer.
const isStreaming = (message) => message?._v2state?.status === 'streaming'

const scrollRef = ref(null)

/**
 * The failure text for a turn, from whichever of the two shapes carries it.
 *
 * A live run writes it into `_v2state` as the stream dies; a run that failed
 * before the page was opened arrives as `status` + `error` on the message. Both
 * have to render, or reloading a failed conversation shows an empty turn.
 */
const errorOf = (message) => {
  if (message?.role !== 'assistant') return ''
  const state = message?._v2state
  if (state?.status === 'error') return state.errorText || '会话执行失败'
  if (['error', 'failed'].includes(String(message?.status || ''))) {
    return extractErrorText(message?.error) || '会话执行失败'
  }
  return ''
}

/**
 * A live turn carries `_v2state` from the stream parser; a turn loaded from
 * history carries `blocks` the backend already projected. Both render the same
 * way, which is what keeps a reloaded page from looking different to one that
 * watched the run happen.
 */
const blocksOf = (message) => {
  const live = message?._v2state?.blocks
  if (Array.isArray(live) && live.length) return live
  return Array.isArray(message?.blocks) ? message.blocks : []
}

/**
 * Render answer text, turning workspace-relative links into download URLs.
 *
 * The agent writes deliverables as ordinary markdown links (`[报告](output/x.xlsx)`).
 * Without the resolver those render as relative hrefs against the host page and
 * 404 — the rewriting existed in `renderMarkdown` but no caller ever passed the
 * resolver, so every generated file link in a conversation was broken.
 */
const markdown = (text) => renderMarkdown(text, { resolveFileHref: props.fileUrl })

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

defineExpose({ focusMessage })

// Follow the conversation as it grows, but only when already near the bottom,
// so reading back through history is not yanked away by an arriving message.
watch(
  () => [props.messages.length, props.messages.at(-1)?._v2state?.blocks?.length],
  async () => {
    const el = scrollRef.value
    if (!el) return
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120
    if (!nearBottom) return
    await nextTick()
    el.scrollTop = el.scrollHeight
  },
)
</script>

<style>
.dac-messages { flex: 1; min-height: 0; overflow-y: auto; padding: 16px; }
.dac-empty { color: var(--dac-text-muted, #64748b); font-size: 14px; }
.dac-message { margin-bottom: 14px; display: flex; }
.dac-message-user { justify-content: flex-end; }
.dac-message.is-focused {
  border-radius: var(--dac-bubble-radius, 14px);
  outline: 2px solid var(--dac-accent, #0f766e);
  outline-offset: 4px;
}
.dac-assistant { width: 100%; display: flex; flex-direction: column; gap: 8px; }
.dac-bubble {
  max-width: 82%;
  padding: 10px 13px;
  border-radius: var(--dac-bubble-radius, 14px);
  background: var(--dac-assistant-bubble-bg, #f1f5f9);
  color: var(--dac-assistant-bubble-color, inherit);
  line-height: 1.65;
  word-break: break-word;
}
.dac-message-user .dac-bubble {
  background: var(--dac-user-bubble-bg, #ecfdf5);
  color: var(--dac-user-bubble-color, #065f46);
}
.dac-assistant .dac-bubble { max-width: 100%; }
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
