<template>
  <template v-for="(segment, part) in segments" :key="part">
    <div v-if="segment.type === 'text'" v-html="segment.html" />
    <ChartSpecView v-else :spec="segment.spec" />
  </template>
</template>

<script setup>
import { computed, watch } from 'vue'
import { parseMarkdown, resolveMarkdownLinks } from '../core/message.js'
import { splitChartSpecText } from '../core/chartSpec.js'
import { useStreamingText } from '../core/useStreamingText.js'
import ChartSpecView from './ChartSpecView.vue'

const props = defineProps({
  text: { type: String, default: '' },
  streaming: { type: Boolean, default: false },
  fileUrl: { type: Function, default: (p) => p },
})
const emit = defineEmits(['rendered'])
const sampled = useStreamingText(() => props.text, () => props.streaming)
// A trailing sample can change layout after the last token's scroll frame.
watch(sampled, () => emit('rendered'), { flush: 'post' })
// Parsing depends only on the sampled source. Retain the current HTML even
// when it exceeds the shared LRU budget; resolver/parent updates only relink it.
const parsed = computed(() => splitChartSpecText(sampled.value).map(segment =>
  segment.type === 'text' ? { ...segment, html: parseMarkdown(segment.value) } : segment))
const segments = computed(() => parsed.value.map(segment => segment.type === 'text'
  ? { ...segment, html: resolveMarkdownLinks(segment.html, { resolveFileHref: props.fileUrl }) }
  : segment))
</script>
