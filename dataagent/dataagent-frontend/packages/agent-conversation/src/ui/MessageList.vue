<template>
  <div class="dac-messages" ref="scrollRef">
    <div v-if="!messages.length" class="dac-empty">
      <slot name="empty">暂无消息</slot>
    </div>

    <article
      v-for="message in messages"
      :key="message.id"
      class="dac-message"
      :class="`dac-message-${message.role}`"
    >
      <!-- A user turn is plain text. Rendering it as markdown would let a
           pasted snippet change how the page looks. -->
      <div v-if="message.role === 'user'" class="dac-bubble">{{ message.content }}</div>

      <div v-else class="dac-assistant">
        <!-- Blocks are the live shape: a streaming turn has them before it has
             any content, and interaction requests only ever exist here. A
             renderer that reads `content` alone shows an empty conversation
             until the run ends, and never shows a permission prompt at all. -->
        <template v-if="blocksOf(message).length">
          <template v-for="(block, index) in blocksOf(message)" :key="`${message.id}-${index}`">
            <div
              v-if="block.type === 'text'"
              class="dac-bubble"
              v-html="renderMarkdown(block.content || block.text || '')"
            />
            <div
              v-else-if="block.type === 'thinking'"
              class="dac-thinking"
              v-html="renderMarkdown(block.content || block.text || '')"
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

        <div v-else class="dac-bubble" v-html="renderMarkdown(message.content || '')" />
      </div>

      <ul v-if="message.attachments?.length" class="dac-attachments">
        <li v-for="file in message.attachments" :key="file.relPath">
          <a :href="fileUrl(file.relPath)" target="_blank" rel="noreferrer">{{ file.name }}</a>
        </li>
      </ul>
    </article>
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import { renderMarkdown } from '../core/message.js'
import { blockToToolProp } from '../core/streamParser.js'
import ToolOutput from './ToolOutput.vue'
import PermissionCard from './PermissionCard.vue'
import QuestionCard from './QuestionCard.vue'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  fileUrl: { type: Function, default: (p) => p },
  disabled: { type: Boolean, default: false },
})

defineEmits(['decide', 'answer'])

const scrollRef = ref(null)

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
.dac-empty { color: #64748b; font-size: 14px; }
.dac-message { margin-bottom: 14px; display: flex; }
.dac-message-user { justify-content: flex-end; }
.dac-assistant { width: 100%; display: flex; flex-direction: column; gap: 8px; }
.dac-bubble {
  max-width: 82%;
  padding: 10px 13px;
  border-radius: 14px;
  background: #f1f5f9;
  line-height: 1.65;
  word-break: break-word;
}
.dac-message-user .dac-bubble { background: #ecfdf5; color: #065f46; }
.dac-assistant .dac-bubble { max-width: 100%; }
.dac-thinking {
  font-size: 13px;
  color: #64748b;
  border-left: 2px solid #cbd5e1;
  padding-left: 10px;
  line-height: 1.6;
}
.dac-attachments { margin: 6px 0 0; padding-left: 18px; font-size: 13px; }
</style>
