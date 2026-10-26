<template>
  <div class="dac-q-card" :class="{ 'is-answered': answered }" part="question-card">
    <template v-if="!answered">
      <div class="dac-q-head">
        <span class="dac-q-question">{{ current.question }}</span>
        <span class="dac-q-head-side">
          <span v-if="total > 1" class="dac-q-pager">
            <button type="button" class="dac-q-icon" aria-label="上一题" :disabled="page === 0" @click="page--">‹</button>
            <span class="dac-q-page">{{ page + 1 }} / {{ total }}</span>
            <button type="button" class="dac-q-icon" aria-label="下一题" :disabled="page >= total - 1" @click="page++">›</button>
          </span>
          <button
            type="button"
            class="dac-q-icon dac-q-close"
            aria-label="全部跳过"
            title="全部跳过"
            :disabled="!editable"
            @click="skipAll"
          >×</button>
        </span>
      </div>

      <ol class="dac-q-options">
        <li v-for="(opt, oi) in options" :key="oi">
          <button
            type="button"
            class="dac-q-opt"
            :class="{ 'is-selected': isSelected(opt.label) }"
            :disabled="!editable"
            @click="pick(opt.label)"
          >
            <span class="dac-q-num">{{ oi + 1 }}</span>
            <span class="dac-q-opt-body">
              <span class="dac-q-opt-label">{{ opt.label }}<span v-if="opt.recommended" class="dac-q-rec">（推荐）</span></span>
              <span v-if="opt.description" class="dac-q-opt-desc">{{ opt.description }}</span>
            </span>
          </button>
        </li>
      </ol>

      <label class="dac-q-other">
        <svg class="dac-q-pencil" viewBox="0 0 16 16" aria-hidden="true"><path d="M11.3 2.3a1 1 0 0 1 1.4 0l1 1a1 1 0 0 1 0 1.4L6.4 12 3 13l1-3.4z" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round" /></svg>
        <input
          v-model="otherText[page]"
          type="text"
          :disabled="!editable"
          placeholder="补充你的想法，或给出不同的答案…"
          @keydown.enter.prevent="next"
        />
      </label>

      <div class="dac-q-foot">
        <button type="button" class="dac-q-skip" :disabled="!editable" @click="skip">跳过</button>
        <span v-if="block._submitFailed" class="dac-q-failed">提交失败，请重试</span>
        <span v-else-if="countdown" class="dac-q-countdown">{{ countdown }} 后按推荐自动选择</span>
        <button
          v-if="current.multiSelect || hasOther"
          type="button"
          class="dac-q-next"
          :disabled="!editable || !hasAnswer"
          @click="next"
        >{{ page >= total - 1 ? '提交' : '下一题' }}</button>
      </div>
    </template>

    <div v-else class="dac-q-result">
      <button type="button" class="dac-q-result-head" :aria-expanded="detailsOpen" @click="detailsOpen = !detailsOpen">
        <svg class="dac-q-done" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="6.2" fill="none" stroke="currentColor" stroke-width="1.3" /><path d="M5.3 8.2l1.9 1.9 3.5-3.6" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" /></svg>
        <span class="dac-q-summary">{{ summary }}</span>
        <svg class="dac-q-chevron" :class="{ open: detailsOpen }" viewBox="0 0 16 16" aria-hidden="true"><path d="M6 3.5L10.5 8 6 12.5" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </button>
      <ul v-if="detailsOpen">
        <li v-for="(line, li) in resultLines" :key="li">
          <span class="dac-q-result-q">{{ line.question }}</span>
          <span class="dac-q-result-a">{{ line.answer }}</span>
        </li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'

import { useDeadline } from '../core/useDeadline.js'

const props = defineProps({
  block: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['answer'])

const RECOMMENDED_SUFFIX = /\s*[（(](推荐|Recommended|recommended)[）)]\s*$/

const submitting = ref(false)
watch(() => props.block._submitFailed, (failed) => {
  if (failed) submitting.value = false
})

const questions = computed(() => (Array.isArray(props.block.questions) ? props.block.questions : []))
const total = computed(() => Math.max(1, questions.value.length))
const answered = computed(() => Boolean(props.block.answered))
const editable = computed(() => !answered.value && !props.disabled && !submitting.value)
const autoAnswered = computed(() => props.block.source === 'auto_timeout')

const page = ref(0)
const selections = reactive({}) // page -> labels
const otherText = reactive({}) // page -> string
const skipped = reactive({}) // page -> bool

const current = computed(() => questions.value[page.value] || {})

// The model may mark a recommendation either with `recommended: true` or by
// ending the label with （推荐）; both render as the same badge.
const normalizeOption = (o) => {
  const raw = typeof o === 'string' ? { label: o } : (o || {})
  const label = String(raw.label ?? '')
  const suffixed = RECOMMENDED_SUFFIX.test(label)
  return {
    label: suffixed ? label.replace(RECOMMENDED_SUFFIX, '') : label,
    description: String(raw.description ?? ''),
    recommended: raw.recommended === true || suffixed,
  }
}
const options = computed(() =>
  (Array.isArray(current.value.options) ? current.value.options : []).map(normalizeOption).filter((o) => o.label),
)

const picked = (qi) => selections[qi] || (selections[qi] = [])
const isSelected = (label) => picked(page.value).includes(label)
const hasOther = computed(() => Boolean(String(otherText[page.value] || '').trim()))
const hasAnswer = computed(() => picked(page.value).length > 0 || hasOther.value)

function pick(label) {
  if (!editable.value) return
  const list = picked(page.value)
  skipped[page.value] = false
  if (current.value.multiSelect) {
    const at = list.indexOf(label)
    if (at >= 0) list.splice(at, 1)
    else list.push(label)
    return
  }
  list.splice(0, list.length, label)
  // A single choice is the answer: move on without a confirm click.
  advance()
}

function next() {
  if (!editable.value || !hasAnswer.value) return
  skipped[page.value] = false
  advance()
}

function skip() {
  if (!editable.value) return
  picked(page.value).splice(0)
  skipped[page.value] = true
  advance()
}

function skipAll() {
  if (!editable.value) return
  for (let qi = page.value; qi < questions.value.length; qi++) {
    if (!picked(qi).length && !String(otherText[qi] || '').trim()) skipped[qi] = true
  }
  submit()
}

function advance() {
  if (page.value < questions.value.length - 1) {
    page.value++
    return
  }
  submit()
}

function buildAnswers() {
  return questions.value.map((q, qi) => {
    const selected = [...picked(qi)]
    const other = String(otherText[qi] || '').trim()
    return {
      header: String(q?.header || ''),
      question: String(q?.question || ''),
      selected,
      other,
      skipped: Boolean(skipped[qi]) || (!selected.length && !other),
    }
  })
}

function submit() {
  if (submitting.value) return
  submitting.value = true
  emit('answer', { requestId: props.block.requestId, answers: buildAnswers() })
}

const { label: countdown } = useDeadline(() => props.block.deadline_at, () => !answered.value)

const detailsOpen = ref(false)

const resultLines = computed(() => {
  const answers = Array.isArray(props.block.answers) ? props.block.answers : []
  return answers.map((a) => {
    const picks = (Array.isArray(a.selected) ? a.selected : []).map((s) => String(s).replace(RECOMMENDED_SUFFIX, ''))
    if (String(a.other || '').trim()) picks.push(String(a.other).trim())
    return {
      question: a.header || a.question || '',
      answer: picks.length ? picks.join('、') : '已跳过',
      skipped: !picks.length,
    }
  })
})

// One line, the way the answers read back: "已补充 3 项 · 按订单行 · 扣减 · …".
const summary = computed(() => {
  const answeredLines = resultLines.value.filter((line) => !line.skipped)
  const lead = autoAnswered.value ? `等待超时，已按推荐选择 ${answeredLines.length} 项` : `已补充 ${answeredLines.length} 项`
  if (!answeredLines.length) return autoAnswered.value ? '等待超时，没有推荐项，已全部跳过' : '已全部跳过'
  return [lead, ...answeredLines.map((line) => line.answer)].join(' · ')
})
</script>

<style scoped>
.dac-q-card {
  border: 1px solid #e3e6eb;
  border-radius: 12px;
  background: #fff;
  padding: 14px 16px 12px;
  margin: 8px 0;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.dac-q-head { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 10px; }
.dac-q-question { flex: 1; font-size: 14px; font-weight: 600; color: #1f2329; line-height: 1.5; }
.dac-q-head-side { display: inline-flex; align-items: center; gap: 6px; color: #8a9099; flex: 0 0 auto; }
.dac-q-pager { display: inline-flex; align-items: center; gap: 2px; }
.dac-q-page { font-size: 12px; min-width: 36px; text-align: center; }
.dac-q-icon {
  border: none; background: none; color: inherit; cursor: pointer;
  width: 22px; height: 22px; border-radius: 6px; font-size: 16px; line-height: 1;
}
.dac-q-icon:hover:not(:disabled) { background: #f2f3f5; color: #1f2329; }
.dac-q-icon:disabled { opacity: 0.35; cursor: default; }
.dac-q-close { font-size: 18px; }
.dac-q-options { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.dac-q-opt {
  width: 100%; display: flex; align-items: flex-start; gap: 10px; text-align: left;
  border: 1px solid #e3e6eb; border-radius: 10px; background: #fff;
  padding: 9px 12px; cursor: pointer; font: inherit; color: inherit;
}
.dac-q-opt:hover:not(:disabled):not(.is-selected) { border-color: #b8c4d6; background: #f7f8fa; }
.dac-q-opt.is-selected { border-color: #3370ff; background: #f0f5ff; }
.dac-q-opt:disabled { cursor: default; }
.dac-q-num {
  flex: 0 0 auto; width: 20px; height: 20px; border-radius: 6px; margin-top: 1px;
  background: #f2f3f5; color: #646a73; font-size: 12px; line-height: 20px; text-align: center;
}
.dac-q-opt.is-selected .dac-q-num { background: #3370ff; color: #fff; }
.dac-q-opt-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.dac-q-opt-label { font-size: 13px; font-weight: 500; color: #1f2329; }
.dac-q-rec { color: #3370ff; font-weight: 400; }
.dac-q-opt-desc { font-size: 12px; color: #8a9099; line-height: 1.5; }
.dac-q-other {
  display: flex; align-items: center; gap: 8px; margin-top: 8px;
  border: 1px solid #e3e6eb; border-radius: 10px; padding: 7px 12px; color: #8a9099;
}
.dac-q-other:focus-within { border-color: #3370ff; }
.dac-q-pencil { width: 14px; height: 14px; flex: 0 0 auto; }
.dac-q-other input { flex: 1; border: none; outline: none; background: transparent; font: inherit; font-size: 13px; color: #1f2329; }
.dac-q-foot { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.dac-q-skip {
  border: 1px solid #e3e6eb; background: #fff; border-radius: 8px;
  padding: 4px 12px; font-size: 12px; color: #646a73; cursor: pointer;
}
.dac-q-skip:hover:not(:disabled) { background: #f2f3f5; }
.dac-q-skip:disabled, .dac-q-next:disabled { opacity: 0.5; cursor: not-allowed; }
.dac-q-countdown { font-size: 12px; color: #8a9099; }
.dac-q-failed { font-size: 12px; color: #d83931; }
.dac-q-next {
  margin-left: auto; border: none; border-radius: 8px; padding: 5px 14px;
  font-size: 12px; background: #3370ff; color: #fff; cursor: pointer;
}
.dac-q-card.is-answered { padding: 0; box-shadow: none; }
.dac-q-result-head {
  display: flex; align-items: center; gap: 8px; width: 100%; padding: 10px 14px;
  border: none; background: none; font: inherit; font-size: 13px; color: #646a73; cursor: pointer; text-align: left;
}
.dac-q-done { width: 16px; height: 16px; color: #2c9c5a; flex: 0 0 auto; }
.dac-q-summary { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dac-q-chevron { width: 14px; height: 14px; flex: 0 0 auto; color: #8a9099; transition: transform 0.15s ease; }
.dac-q-chevron.open { transform: rotate(90deg); }
.dac-q-result ul { list-style: none; margin: 0; padding: 0 14px 12px 38px; display: flex; flex-direction: column; gap: 4px; }
.dac-q-result li { display: flex; gap: 8px; font-size: 13px; }
.dac-q-result-q { color: #8a9099; flex: 0 0 auto; max-width: 45%; }
.dac-q-result-a { color: #1f2329; }
</style>
