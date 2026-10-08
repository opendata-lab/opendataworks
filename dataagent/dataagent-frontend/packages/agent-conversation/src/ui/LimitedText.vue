<template>
  <div :class="{ 'tool-markdown-body': markdown }">
    <div v-if="markdown" v-html="html" />
    <pre v-else><code>{{ displayed }}</code></pre>
    <button v-if="truncated" type="button" class="tool-markdown-toggle" :aria-expanded="expanded" @click="expanded = !expanded">
      {{ expanded ? '收起' : '展开全部' }}
    </button>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { renderMarkdown } from '../core/message.js'

const props = defineProps({
  text: { type: String, default: '' },
  markdown: { type: Boolean, default: false },
  previewLines: { type: Number, default: 0 },
})
const expanded = ref(false)
// Slice before line splitting or parsing: a single very long line is bounded too.
const preview = computed(() => {
  const prefix = props.text.slice(0, 4000)
  return props.previewLines ? prefix.split('\n').slice(0, props.previewLines).join('\n') : prefix
})
const truncated = computed(() => preview.value.length < props.text.length)
const displayed = computed(() => expanded.value ? props.text : preview.value)
const html = computed(() => renderMarkdown(displayed.value))
watch(() => props.markdown, () => { expanded.value = false })
</script>

<style scoped>
pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; }
button { margin-top: 8px; cursor: pointer; color: var(--dac-primary, #10b981); background: transparent; border: 0; }
</style>
