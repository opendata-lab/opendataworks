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
      <div class="dac-bubble" v-html="renderMarkdown(message.content)" />
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

const props = defineProps({
  messages: { type: Array, default: () => [] },
  fileUrl: { type: Function, default: (p) => p }
})

const scrollRef = ref(null)

// Follow the conversation as it grows. Only when already near the bottom, so
// reading back through history is not yanked away by an arriving message.
watch(() => props.messages.length, async () => {
  const el = scrollRef.value
  if (!el) return
  const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120
  if (!nearBottom) return
  await nextTick()
  el.scrollTop = el.scrollHeight
})
</script>

<style>
.dac-messages { flex: 1; min-height: 0; overflow-y: auto; padding: 16px; }
.dac-empty { color: #64748b; font-size: 14px; }
.dac-message { margin-bottom: 14px; display: flex; }
.dac-message-user { justify-content: flex-end; }
.dac-bubble {
  max-width: 82%;
  padding: 10px 13px;
  border-radius: 14px;
  background: #f1f5f9;
  line-height: 1.65;
  word-break: break-word;
}
.dac-message-user .dac-bubble { background: #ecfdf5; color: #065f46; }
.dac-attachments { margin: 6px 0 0; padding-left: 18px; font-size: 13px; }
</style>
