<template>
  <div class="v2-perm-card" :class="`risk-${block.risk_level || 'high'}`">
    <div class="v2-perm-head">
      <span class="v2-perm-badge">{{ riskLabel }}</span>
      <span class="v2-perm-title">{{ block.title || (isPlan ? '请确认执行计划' : ('请确认操作：' + bareTool)) }}</span>
    </div>
    <!-- Plan body reads as a document: the model writes the plan in markdown
         (headings, ordered steps, code), so render it instead of showing raw
         markup. Non-plan summaries stay plain text — they are short tool blurbs. -->
    <template v-if="isPlan && block.summary">
      <div
        ref="planBodyRef"
        class="v2-perm-plan"
        :class="{ 'is-collapsed': !planExpanded }"
        v-html="renderedPlan"
      />
      <button
        v-if="planOverflows"
        type="button"
        class="v2-perm-plan-toggle"
        @click="planExpanded = !planExpanded"
      >
        {{ planExpanded ? '收起' : '展开全文' }}
      </button>
    </template>
    <div v-else-if="block.summary" class="v2-perm-summary">{{ block.summary }}</div>
    <div v-if="!isPlan" class="v2-perm-tool">工具：<code>{{ bareTool }}</code></div>
    <details v-if="!isPlan && hasPreview" class="v2-perm-preview">
      <summary>参数详情</summary>
      <pre>{{ prettyPreview }}</pre>
    </details>

    <div v-if="isPending" class="v2-perm-actions">
      <button type="button" class="v2-perm-btn deny" :disabled="disabled || submitting" @click="decide('deny')">{{ isPlan ? '继续完善' : '拒绝' }}</button>
      <button type="button" class="v2-perm-btn allow" :disabled="disabled || submitting" @click="decide('allow')">{{ isPlan ? '批准并执行' : '允许' }}</button>
    </div>
    <div v-else class="v2-perm-result" :class="block.decision">{{ resultLabel }}</div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import { renderMarkdown } from '../core/message.js'

const props = defineProps({
  block: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['decide'])

const submitting = ref(false)

watch(() => props.block._submitFailed, (failed) => {
  if (failed) submitting.value = false
})

const isPending = computed(() => (props.block.decision || 'pending') === 'pending')
const bareTool = computed(() => {
  const name = String(props.block.tool_name || '')
  return name.startsWith('mcp__') ? name.split('__').pop() : name
})
const isPlan = computed(() => props.block.risk_level === 'plan')
// renderMarkdown escapes the source before parsing, so model-authored plan text
// cannot inject markup here.
const renderedPlan = computed(() => (isPlan.value ? renderMarkdown(props.block.summary) : ''))

// A long plan collapses to a readable height with a "展开全文" toggle; the toggle
// only appears when the body actually overflows.
const planExpanded = ref(false)
const planBodyRef = ref(null)
const planOverflows = ref(false)
const measurePlanOverflow = async () => {
  await nextTick()
  const el = planBodyRef.value
  if (!el) {
    planOverflows.value = false
    return
  }
  // Measuring an expanded body says nothing about whether it overflows when
  // collapsed — keep the current flag so the "收起" toggle stays reachable.
  if (planExpanded.value) return
  // scrollHeight/clientHeight are 0 in non-layout environments (jsdom), which
  // simply leaves the toggle hidden.
  planOverflows.value = el.scrollHeight > el.clientHeight + 4
}
onMounted(measurePlanOverflow)
watch([() => props.block.summary, isPlan], measurePlanOverflow)
const riskLabel = computed(() => {
  if (props.block.risk_level === 'plan') return '执行计划'
  if (props.block.risk_level === 'critical') return '高危操作'
  return '需要确认'
})
const hasPreview = computed(() => props.block.payload_preview != null && typeof props.block.payload_preview === 'object')
const prettyPreview = computed(() => {
  try {
    return JSON.stringify(props.block.payload_preview, null, 2)
  } catch {
    return String(props.block.payload_preview)
  }
})
const resultLabel = computed(() => {
  const plan = props.block.risk_level === 'plan'
  switch (props.block.decision) {
    case 'allow':
      return plan ? '✓ 计划已批准，继续执行' : '✓ 已允许执行'
    case 'deny':
      return plan ? '✕ 计划未批准' : '✕ 已拒绝'
    case 'timeout':
      return '⏱ 等待确认超时，已自动拒绝'
    default:
      return ''
  }
})

function decide(decision) {
  if (props.disabled || submitting.value) return
  submitting.value = true
  emit('decide', { requestId: props.block.requestId, decision })
}

</script>

<style scoped>
.v2-perm-card {
  border: 1px solid #f0c36d;
  border-radius: 10px;
  background: #fffaf0;
  padding: 12px 14px;
  margin: 8px 0;
}
.v2-perm-card.risk-critical {
  border-color: #e88;
  background: #fff5f5;
}
.v2-perm-card.risk-plan {
  border-color: #91b8f0;
  background: #f5f9ff;
}
.v2-perm-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.v2-perm-badge {
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  background: #e6a23c;
  border-radius: 4px;
  padding: 1px 8px;
}
.risk-critical .v2-perm-badge { background: #e05656; }
.risk-plan .v2-perm-badge { background: #3b82f6; }
.v2-perm-title { font-weight: 600; font-size: 14px; }
.v2-perm-summary { font-size: 13px; color: #555; margin: 4px 0; white-space: pre-wrap; }

/* The plan reads as a document: roomy surface, markdown typography, and a
   collapse that fades out rather than cutting a line in half. */
.v2-perm-plan {
  position: relative;
  font-size: 13px;
  color: #333;
  line-height: 1.65;
  background: #fff;
  border: 1px solid #e3ecfb;
  border-radius: 8px;
  padding: 14px 16px;
  margin: 8px 0 0;
  overflow: hidden;
}
.v2-perm-plan.is-collapsed {
  max-height: 460px;
  -webkit-mask-image: linear-gradient(to bottom, #000 calc(100% - 48px), transparent 100%);
  mask-image: linear-gradient(to bottom, #000 calc(100% - 48px), transparent 100%);
}
.v2-perm-plan-toggle {
  border: none;
  background: none;
  color: #2563eb;
  font-size: 12px;
  cursor: pointer;
  padding: 6px 2px 0;
}
.v2-perm-plan-toggle:hover { text-decoration: underline; }

.v2-perm-plan :deep(h1),
.v2-perm-plan :deep(h2),
.v2-perm-plan :deep(h3) {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
  margin: 14px 0 6px;
  line-height: 1.4;
}
.v2-perm-plan :deep(h1:first-child),
.v2-perm-plan :deep(h2:first-child),
.v2-perm-plan :deep(h3:first-child) { margin-top: 0; }
.v2-perm-plan :deep(p) { margin: 0 0 8px; }
.v2-perm-plan :deep(p:last-child) { margin: 0; }
.v2-perm-plan :deep(ul), .v2-perm-plan :deep(ol) { margin: 0 0 8px; padding-left: 20px; }
.v2-perm-plan :deep(li) { margin: 3px 0; }
.v2-perm-plan :deep(li::marker) { color: #64748b; }
.v2-perm-plan :deep(code) {
  background: #eef1f6;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 12px;
}
.v2-perm-plan :deep(pre) {
  background: #f4f7fb;
  border-radius: 6px;
  padding: 10px 12px;
  overflow-x: auto;
  margin: 8px 0;
}
.v2-perm-plan :deep(pre code) { background: none; padding: 0; }
.v2-perm-plan :deep(table) { border-collapse: collapse; width: 100%; margin: 8px 0; }
.v2-perm-plan :deep(th), .v2-perm-plan :deep(td) {
  border: 1px solid #dbe3ef;
  padding: 5px 10px;
  font-size: 12px;
  text-align: left;
}
.v2-perm-plan :deep(th) { background: #f4f7fb; font-weight: 600; }
.v2-perm-plan :deep(blockquote) {
  margin: 8px 0;
  padding-left: 10px;
  border-left: 3px solid #dbe3ef;
  color: #64748b;
}
.v2-perm-plan :deep(hr) { border: none; border-top: 1px solid #e3ecfb; margin: 12px 0; }
.v2-perm-plan :deep(a) { color: #2563eb; }
.v2-perm-tool { font-size: 12px; color: #777; margin: 4px 0; }
.v2-perm-tool code { background: #eef1f6; padding: 1px 5px; border-radius: 4px; }
.v2-perm-preview { margin: 6px 0; }
.v2-perm-preview pre {
  max-height: 200px;
  overflow: auto;
  background: #f4f7fb;
  border-radius: 6px;
  padding: 8px;
  font-size: 12px;
  margin: 6px 0 0;
}
.v2-perm-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }
.v2-perm-btn {
  border: none;
  border-radius: 6px;
  padding: 6px 16px;
  font-size: 13px;
  cursor: pointer;
}
.v2-perm-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.v2-perm-btn.allow { background: #2c9c5a; color: #fff; }
.v2-perm-btn.deny { background: #eef1f6; color: #333; }
.v2-perm-result { margin-top: 6px; font-size: 13px; font-weight: 600; }
.v2-perm-result.allow { color: #2c9c5a; }
.v2-perm-result.deny, .v2-perm-result.timeout { color: #c0392b; }
</style>
