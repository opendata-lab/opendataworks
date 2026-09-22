<template>
  <section class="dac-thinking" part="thinking">
    <button
      type="button"
      class="dac-thinking-summary"
      part="thinking-toggle"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      <span class="dac-thinking-label">
        <span v-if="isStreaming" class="dac-thinking-dot" aria-hidden="true" />
        深度思考
      </span>
      <span v-if="!expanded && preview" class="dac-thinking-preview">{{ preview }}</span>
      <svg
        class="dac-thinking-chevron"
        :class="{ 'is-expanded': expanded }"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        aria-hidden="true"
      >
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 9l6 6 6-6" />
      </svg>
    </button>

    <div v-if="expanded" class="dac-thinking-content" part="thinking-content">
      <div v-html="renderMarkdown(content)" />
      <span v-if="isStreaming" class="dac-thinking-cursor" aria-hidden="true">|</span>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { renderMarkdown } from '../core/message.js'

const props = defineProps({
  block: { type: Object, required: true },
})

const expanded = ref(false)
const content = computed(() => String(props.block?.content || props.block?.text || ''))
const isStreaming = computed(() => props.block?.status === 'streaming')
const preview = computed(() => content.value.replace(/\s+/g, ' ').trim().slice(0, 80))
</script>

<style>
.dac-thinking {
  overflow: hidden;
  border: 1px solid var(--dac-border-color, #dbe3ef);
  border-radius: 10px;
  background: var(--dac-thinking-bg, #f8fafc);
}
.dac-thinking-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  background: transparent;
  color: var(--dac-text-color, #334155);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.dac-thinking-summary:hover { background: var(--dac-thinking-hover-bg, #f1f5f9); }
.dac-thinking-summary:focus-visible {
  outline: 2px solid var(--dac-primary-color, #2563eb);
  outline-offset: -2px;
}
.dac-thinking-label {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}
.dac-thinking-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--dac-primary-color, #2563eb);
  animation: dac-thinking-pulse 1.4s ease-in-out infinite;
}
.dac-thinking-preview {
  flex: 1 1 auto;
  overflow: hidden;
  color: var(--dac-text-muted, #64748b);
  font-size: 12px;
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dac-thinking-chevron {
  flex: 0 0 auto;
  width: 16px;
  height: 16px;
  color: var(--dac-text-muted, #64748b);
  transition: transform 160ms ease;
}
.dac-thinking-chevron.is-expanded { transform: rotate(180deg); }
.dac-thinking-content {
  max-height: min(360px, 50vh);
  overflow: auto;
  padding: 12px 14px;
  border-top: 1px solid var(--dac-border-color, #dbe3ef);
  color: var(--dac-text-muted, #64748b);
  font-size: 13px;
  line-height: 1.65;
}
.dac-thinking-content p { margin: 0 0 8px; }
.dac-thinking-content p:last-child { margin-bottom: 0; }
.dac-thinking-cursor { animation: dac-thinking-blink 1s steps(1) infinite; }

@keyframes dac-thinking-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(0.85); }
}

@keyframes dac-thinking-blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .dac-thinking-dot,
  .dac-thinking-cursor { animation: none; }
  .dac-thinking-chevron { transition: none; }
}
</style>
