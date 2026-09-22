<template>
  <div class="dac-actions" part="message-actions">
    <time v-if="time" class="dac-time" part="message-time" :datetime="message.createdAt">{{ time }}</time>

    <button
      type="button"
      class="dac-action"
      part="copy-button"
      data-action="copy"
      :aria-label="copied ? '已复制' : '复制'"
      @click="copy"
    >{{ copied ? '已复制' : '复制' }}</button>

    <!-- Rating only exists when the host can store it. Showing the buttons
         without somewhere to send them produces a control that appears to work
         and silently discards the answer. -->
    <template v-if="canRate">
      <button
        type="button"
        class="dac-action"
        part="feedback-button"
        data-action="like"
        :class="{ 'is-on': message.feedback === 'like' }"
        :aria-pressed="message.feedback === 'like'"
        aria-label="有帮助"
        @click="$emit('feedback', { message, value: 'like' })"
      >👍</button>
      <button
        type="button"
        class="dac-action"
        part="feedback-button"
        data-action="dislike"
        :class="{ 'is-on': message.feedback === 'dislike' }"
        :aria-pressed="message.feedback === 'dislike'"
        aria-label="没帮助"
        @click="$emit('feedback', { message, value: 'dislike' })"
      >👎</button>
    </template>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { getMessageCopyText } from '../core/message.js'
import { copyText } from '../utils/clipboard.js'

const props = defineProps({
  message: { type: Object, required: true },
  canRate: { type: Boolean, default: false },
})

defineEmits(['feedback'])

const copied = ref(false)
let resetTimer = null

const time = computed(() => {
  const raw = props.message?.createdAt
  if (!raw) return ''
  const at = new Date(raw)
  if (Number.isNaN(at.getTime())) return ''
  return `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`
})

const copy = async () => {
  const text = getMessageCopyText(props.message)
  if (!text) return
  try {
    await copyText(text)
    copied.value = true
    if (resetTimer) clearTimeout(resetTimer)
    resetTimer = setTimeout(() => { copied.value = false }, 1500)
  } catch {
    // A denied clipboard is the browser's decision, not a conversation error;
    // the text stays selectable either way.
  }
}
</script>

<style>
.dac-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 20px;
  color: var(--dac-text-muted, #64748b);
  font-size: 12px;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.dac-message:hover .dac-actions,
.dac-actions:focus-within { opacity: 1; }
.dac-action {
  padding: 2px 6px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.dac-action:hover { background: var(--dac-assistant-bubble-bg, #f1f5f9); }
.dac-action.is-on { color: var(--dac-accent, #0f766e); }
</style>
