<template>
  <div class="v2-perm-card" :class="`risk-${block.risk_level || 'high'}`">
    <div class="v2-perm-head">
      <span class="v2-perm-badge">{{ riskLabel }}</span>
      <span class="v2-perm-title">{{ block.title || ('请确认操作：' + bareTool) }}</span>
    </div>
    <div v-if="block.summary" class="v2-perm-summary">{{ block.summary }}</div>
    <div class="v2-perm-tool">工具：<code>{{ bareTool }}</code></div>
    <details v-if="hasPreview" class="v2-perm-preview">
      <summary>参数详情</summary>
      <pre>{{ prettyPreview }}</pre>
    </details>

    <div v-if="isPending" class="v2-perm-actions">
      <button type="button" class="v2-perm-btn deny" :disabled="disabled || submitting" @click="decide('deny')">拒绝</button>
      <button type="button" class="v2-perm-btn allow" :disabled="disabled || submitting" @click="decide('allow')">允许</button>
    </div>
    <div v-else class="v2-perm-result" :class="block.decision">{{ resultLabel }}</div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  block: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['decide'])

const submitting = ref(false)

const isPending = computed(() => (props.block.decision || 'pending') === 'pending')
const bareTool = computed(() => {
  const name = String(props.block.tool_name || '')
  return name.startsWith('mcp__') ? name.split('__').pop() : name
})
const riskLabel = computed(() => (props.block.risk_level === 'critical' ? '高危操作' : '需要确认'))
const hasPreview = computed(() => props.block.payload_preview != null && typeof props.block.payload_preview === 'object')
const prettyPreview = computed(() => {
  try {
    return JSON.stringify(props.block.payload_preview, null, 2)
  } catch {
    return String(props.block.payload_preview)
  }
})
const resultLabel = computed(() => {
  switch (props.block.decision) {
    case 'allowed':
      return '✓ 已允许执行'
    case 'denied':
      return '✕ 已拒绝'
    case 'timeout':
      return '⏱ 等待确认超时，已自动拒绝'
    default:
      return ''
  }
})

function decide(decision) {
  if (props.disabled || submitting.value) return
  submitting.value = true
  // Optimistic: parent posts the decision; the streamed permission_decision
  // record will reconcile the final state.
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
.v2-perm-title { font-weight: 600; font-size: 14px; }
.v2-perm-summary { font-size: 13px; color: #555; margin: 4px 0; white-space: pre-wrap; }
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
.v2-perm-result.allowed { color: #2c9c5a; }
.v2-perm-result.denied, .v2-perm-result.timeout { color: #c0392b; }
</style>
