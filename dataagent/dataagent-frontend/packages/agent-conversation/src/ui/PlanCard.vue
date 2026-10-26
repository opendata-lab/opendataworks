<template>
  <div ref="rootRef" class="dac-plan" part="plan-card">
    <div class="dac-plan-doc">
      <div class="dac-plan-head">
        <span class="dac-plan-title">
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.8a4.4 4.4 0 0 0-2.6 8c.4.3.6.7.6 1.2v.5h4v-.5c0-.5.2-.9.6-1.2A4.4 4.4 0 0 0 8 1.8zM6.2 13.2h3.6M6.8 14.7h2.4" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" /></svg>
          计划
        </span>
        <span class="dac-plan-tools">
          <button type="button" class="dac-plan-icon" aria-label="下载" title="下载 Markdown" @click="download">
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 2.5v8m0 0L4.8 7.3M8 10.5l3.2-3.2M3 13.5h10" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" /></svg>
          </button>
          <button type="button" class="dac-plan-icon" :aria-label="copied ? '已复制' : '复制'" :title="copied ? '已复制' : '复制'" @click="copy">
            <svg v-if="!copied" viewBox="0 0 16 16" aria-hidden="true"><rect x="5.5" y="5.5" width="8" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.3" /><path d="M10.5 3.5v-.5A1.5 1.5 0 0 0 9 1.5H4A1.5 1.5 0 0 0 2.5 3v5A1.5 1.5 0 0 0 4 9.5h.5" fill="none" stroke="currentColor" stroke-width="1.3" /></svg>
            <svg v-else viewBox="0 0 16 16" aria-hidden="true"><path d="M3 8.5l3 3 7-7" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
          </button>
          <button
            type="button"
            class="dac-plan-icon"
            :aria-label="expanded ? '收起' : '展开'"
            :title="expanded ? '收起' : '展开全文'"
            @click="openFull"
          >
            <svg v-if="!expanded" viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 6V2.5H6M10 2.5h3.5V6M13.5 10v3.5H10M6 13.5H2.5V10" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" /></svg>
            <svg v-else viewBox="0 0 16 16" aria-hidden="true"><path d="M6 2.5V6H2.5M13.5 6H10V2.5M10 13.5V10h3.5M2.5 10H6v3.5" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" /></svg>
          </button>
        </span>
      </div>
      <!-- renderMarkdown escapes the source before parsing, so model-authored
           plan text cannot inject markup here. -->
      <div class="dac-plan-body" :class="{ 'is-full': expanded }" v-html="rendered" />
    </div>

    <div v-if="isPending" class="dac-plan-ask">
      <div class="dac-plan-ask-head">
        <span class="dac-plan-ask-title">实施此计划？</span>
        <button type="button" class="dac-plan-icon" aria-label="不执行" title="不执行此计划" :disabled="disabled || submitting" @click="dismiss">×</button>
      </div>
      <template v-if="!revising">
        <button type="button" class="dac-plan-choice primary" :disabled="disabled || submitting" @click="approve">
          <span class="dac-plan-check" aria-hidden="true" />
          <span class="dac-plan-choice-label">是，开始执行</span>
          <span aria-hidden="true">→</span>
        </button>
        <button type="button" class="dac-plan-choice" :disabled="disabled || submitting" @click="startRevise">
          <svg class="dac-plan-pencil" viewBox="0 0 16 16" aria-hidden="true"><path d="M11.3 2.3a1 1 0 0 1 1.4 0l1 1a1 1 0 0 1 0 1.4L6.4 12 3 13l1-3.4z" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round" /></svg>
          <span class="dac-plan-choice-label">先调整计划</span>
        </button>
      </template>
      <div v-else class="dac-plan-revise">
        <textarea
          ref="noteRef"
          v-model="note"
          rows="3"
          placeholder="说说要怎么调整，例如：先不建汇总表，只做明细层"
          :disabled="disabled || submitting"
          @keydown.enter.exact.prevent="sendRevision"
        />
        <div class="dac-plan-revise-actions">
          <button type="button" class="dac-plan-btn" :disabled="submitting" @click="revising = false">取消</button>
          <button type="button" class="dac-plan-btn primary" :disabled="disabled || submitting || !note.trim()" @click="sendRevision">提交修改意见</button>
        </div>
      </div>
      <div class="dac-plan-ask-foot">
        <span v-if="block._submitFailed" class="dac-plan-failed">提交失败，请重试</span>
        <span v-else-if="countdown" class="dac-plan-countdown">{{ countdown }} 后自动开始执行</span>
      </div>
    </div>
    <div v-else class="dac-plan-result" :class="block.decision">{{ resultLabel }}</div>

  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'

import { renderMarkdown } from '../core/message.js'
import { useDeadline } from '../core/useDeadline.js'
import { downloadTextFile } from '../utils/tableExport.js'
import { useCopyFeedback } from '../utils/useCopyFeedback.js'

const props = defineProps({
  block: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['decide'])

const rootRef = ref(null)
const noteRef = ref(null)
const submitting = ref(false)
const revising = ref(false)
const note = ref('')
const expanded = ref(false)

watch(() => props.block._submitFailed, (failed) => {
  if (failed) submitting.value = false
})

const markdown = computed(() => String(props.block.summary || ''))
const rendered = computed(() => renderMarkdown(markdown.value))
// The plan's own first heading names it; a side panel shows that as its title
// and the rest as the body, so the title is not printed twice.
const HEADING = /^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$/m
const title = computed(() => markdown.value.match(HEADING)?.[1] || '计划')
const body = computed(() => (HEADING.test(markdown.value) ? markdown.value.replace(HEADING, '').trim() : markdown.value))
const isPending = computed(() => (props.block.decision || 'pending') === 'pending')

const { label: countdown } = useDeadline(() => props.block.deadline_at, () => isPending.value)

const { copiedKey, copyWithFeedback } = useCopyFeedback()
const copied = computed(() => copiedKey.value === 'copied')
async function copy() {
  try {
    // Falls back to a textarea copy on plain-HTTP LAN deployments.
    await copyWithFeedback(markdown.value)
  } catch {
    // Both clipboard paths refused; the download button still works.
  }
}

function download() {
  const name = title.value.replace(/[\\/:*?"<>|\s]+/g, '-').replace(/^-+|-+$/g, '') || 'plan'
  downloadTextFile(`${name}.md`, markdown.value, 'text/markdown;charset=utf-8;')
}

// The host decides where the full plan goes (OntoFoundry opens it in its side
// column); a host that does not claim the event gets the card expanded in place.
function openFull() {
  if (expanded.value) {
    expanded.value = false
    return
  }
  const event = new CustomEvent('dataagent-plan-open', {
    bubbles: true,
    composed: true,
    cancelable: true,
    detail: { markdown: markdown.value, title: title.value, body: body.value, requestId: props.block.requestId },
  })
  const notClaimed = rootRef.value ? rootRef.value.dispatchEvent(event) : true
  if (notClaimed) expanded.value = true
}

function approve() {
  if (props.disabled || submitting.value) return
  submitting.value = true
  emit('decide', { requestId: props.block.requestId, decision: 'allow' })
}

async function startRevise() {
  revising.value = true
  await nextTick()
  noteRef.value?.focus()
}

// Closing the question is a "no" without notes: the agent keeps refining.
function dismiss() {
  if (props.disabled || submitting.value) return
  submitting.value = true
  emit('decide', { requestId: props.block.requestId, decision: 'deny' })
}

function sendRevision() {
  const text = note.value.trim()
  if (props.disabled || submitting.value || !text) return
  submitting.value = true
  emit('decide', { requestId: props.block.requestId, decision: 'deny', note: text })
}

const resultLabel = computed(() => {
  switch (props.block.decision) {
    case 'allow':
      return props.block.source === 'auto_timeout' ? '✓ 等待超时，已按设置自动开始执行' : '✓ 已批准，开始执行'
    case 'deny':
      return props.block.note ? `✎ 已要求调整：${props.block.note}` : '✕ 未执行此计划'
    case 'timeout':
      return '⏱ 等待确认超时'
    default:
      return ''
  }
})
</script>

<style scoped>
.dac-plan { margin: 8px 0; display: flex; flex-direction: column; gap: 10px; }
.dac-plan-doc {
  border: 1px solid #e5e6eb; border-radius: 12px; background: #fff; overflow: hidden;
}
.dac-plan-head { display: flex; align-items: center; justify-content: space-between; padding: 10px 10px 0 16px; }
.dac-plan-title { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #8a9099; }
.dac-plan-title svg { width: 15px; height: 15px; }
.dac-plan-tools { display: inline-flex; gap: 2px; }
.dac-plan-icon {
  border: none; background: none; color: #8a9099; cursor: pointer;
  width: 26px; height: 26px; border-radius: 6px; display: inline-flex; align-items: center; justify-content: center;
  font-size: 18px; line-height: 1;
}
.dac-plan-icon:hover:not(:disabled) { background: #f2f3f5; color: #1f2329; }
.dac-plan-icon:disabled { cursor: not-allowed; opacity: 0.5; }
.dac-plan-icon svg { width: 15px; height: 15px; }
.dac-plan-body {
  padding: 6px 16px 14px; font-size: 13px; line-height: 1.7; color: #1f2329;
  max-height: 240px; overflow: hidden;
  -webkit-mask-image: linear-gradient(to bottom, #000 calc(100% - 64px), transparent 100%);
  mask-image: linear-gradient(to bottom, #000 calc(100% - 64px), transparent 100%);
}
.dac-plan-body.is-full { max-height: none; -webkit-mask-image: none; mask-image: none; }
.dac-plan-body :deep(h1) { font-size: 18px; font-weight: 600; margin: 6px 0 12px; line-height: 1.45; }
.dac-plan-body :deep(h2) { font-size: 15px; font-weight: 600; margin: 14px 0 8px; line-height: 1.45; }
.dac-plan-body :deep(h3), .dac-plan-body :deep(h4) { font-size: 13px; font-weight: 600; margin: 12px 0 6px; }
.dac-plan-body :deep(h1:first-child), .dac-plan-body :deep(h2:first-child), .dac-plan-body :deep(h3:first-child) { margin-top: 6px; }
.dac-plan-body :deep(p) { margin: 0 0 8px; }
.dac-plan-body :deep(ul), .dac-plan-body :deep(ol) { margin: 0 0 8px; padding-left: 20px; }
.dac-plan-body :deep(li) { margin: 3px 0; }
.dac-plan-body :deep(code) { background: #f2f3f5; padding: 1px 5px; border-radius: 4px; font-size: 12px; }
.dac-plan-body :deep(pre) { background: #f7f8fa; border-radius: 6px; padding: 10px 12px; overflow-x: auto; }
.dac-plan-body :deep(pre code) { background: none; padding: 0; }
.dac-plan-body :deep(table) { border-collapse: collapse; width: 100%; margin: 8px 0; }
.dac-plan-body :deep(th), .dac-plan-body :deep(td) { border-bottom: 1px solid #e5e6eb; padding: 7px 10px; font-size: 12px; text-align: left; }
.dac-plan-body :deep(th) { background: #f2f3f5; font-weight: 500; }
.dac-plan-ask {
  border: 1px solid #e5e6eb; border-radius: 12px; background: #fff; padding: 10px 12px 12px;
  display: flex; flex-direction: column; gap: 4px;
}
.dac-plan-ask-head { display: flex; align-items: center; justify-content: space-between; padding: 0 0 4px 4px; }
.dac-plan-ask-title { font-size: 13px; font-weight: 600; color: #1f2329; }
.dac-plan-choice {
  display: flex; align-items: center; gap: 10px; width: 100%;
  border: none; border-radius: 8px; background: transparent; padding: 9px 12px;
  font: inherit; font-size: 13px; color: #8a9099; cursor: pointer; text-align: left;
}
.dac-plan-choice-label { flex: 1; }
.dac-plan-choice.primary { background: #f2f3f5; color: #1f2329; }
.dac-plan-choice:hover:not(:disabled) { background: #ebecef; color: #1f2329; }
.dac-plan-check { width: 13px; height: 13px; border: 1.3px solid #8a9099; border-radius: 3px; flex: 0 0 auto; }
.dac-plan-pencil { width: 14px; height: 14px; flex: 0 0 auto; }
.dac-plan-choice:disabled, .dac-plan-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.dac-plan-revise textarea {
  width: 100%; box-sizing: border-box; resize: vertical; border: 1px solid #e5e6eb; border-radius: 8px;
  padding: 8px 12px; font: inherit; font-size: 13px; outline: none;
}
.dac-plan-revise textarea:focus { border-color: #8a9099; }
.dac-plan-revise-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 6px; }
.dac-plan-btn {
  border: 1px solid #e5e6eb; background: #fff; border-radius: 8px; padding: 5px 14px;
  font-size: 12px; color: #646a73; cursor: pointer;
}
.dac-plan-btn.primary { border-color: #1f2329; background: #1f2329; color: #fff; }
.dac-plan-ask-foot { padding: 2px 4px 0; }
.dac-plan-ask-foot:empty { display: none; }
.dac-plan-countdown { font-size: 12px; color: #8a9099; }
.dac-plan-failed { font-size: 12px; color: #d83931; }
.dac-plan-result { font-size: 13px; padding: 0 2px; color: #646a73; }
.dac-plan-result.allow { color: #2c9c5a; }
.dac-plan-result.timeout { color: #d83931; }
</style>
