<template>
  <div class="v2-q-card">
    <div class="v2-q-head">
      <span class="v2-q-badge">请选择</span>
      <span class="v2-q-title">助手需要你确认以下问题</span>
    </div>

    <div v-for="(q, qi) in questions" :key="qi" class="v2-q-item">
      <div class="v2-q-row">
        <span v-if="q.header" class="v2-q-tag">{{ q.header }}</span>
        <span class="v2-q-question">{{ q.question }}</span>
        <span v-if="q.multiSelect" class="v2-q-multi">可多选</span>
      </div>

      <div class="v2-q-options">
        <button
          v-for="(opt, oi) in normalizedOptions(q)"
          :key="oi"
          type="button"
          class="v2-q-opt"
          :class="{ 'is-selected': isSelected(qi, opt.label) }"
          :disabled="!editable"
          :title="opt.description || ''"
          @click="toggle(qi, opt.label, q.multiSelect)"
        >
          <span class="v2-q-opt-mark" :class="{ multi: q.multiSelect }"></span>
          <span class="v2-q-opt-body">
            <span class="v2-q-opt-label">{{ opt.label }}</span>
            <span v-if="opt.description" class="v2-q-opt-desc">{{ opt.description }}</span>
          </span>
        </button>
      </div>

      <input
        v-model="otherText[qi]"
        class="v2-q-other"
        type="text"
        :disabled="!editable"
        placeholder="其他(可自由填写)…"
        @focus="editable && (otherActive[qi] = true)"
      />
    </div>

    <div v-if="editable" class="v2-q-actions">
      <button type="button" class="v2-q-submit" :disabled="disabled || submitting || !hasAnySelection" @click="submit">
        提交选择
      </button>
    </div>
    <div v-else class="v2-q-result">{{ resultLabel }}</div>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'

const props = defineProps({
  block: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['answer'])

const submitting = ref(false)
const selections = reactive({}) // qi -> Set of labels
const otherText = reactive({}) // qi -> string
const otherActive = reactive({}) // qi -> bool

const questions = computed(() => (Array.isArray(props.block.questions) ? props.block.questions : []))
const answered = computed(() => Boolean(props.block.answered))
const editable = computed(() => !answered.value && !props.disabled)

const normalizedOptions = (q) => {
  const opts = Array.isArray(q?.options) ? q.options : []
  return opts
    .map((o) => (typeof o === 'string' ? { label: o, description: '' } : { label: String(o?.label ?? ''), description: String(o?.description ?? '') }))
    .filter((o) => o.label)
}

const selectedSet = (qi) => {
  if (!selections[qi]) selections[qi] = new Set()
  return selections[qi]
}
const isSelected = (qi, label) => selectedSet(qi).has(label)

function toggle(qi, label, multiSelect) {
  if (!editable.value) return
  const set = selectedSet(qi)
  if (multiSelect) {
    if (set.has(label)) set.delete(label)
    else set.add(label)
  } else {
    set.clear()
    set.add(label)
  }
}

const hasAnySelection = computed(() =>
  questions.value.some((_, qi) => selectedSet(qi).size > 0 || String(otherText[qi] || '').trim())
)

function buildAnswers() {
  return questions.value.map((q, qi) => ({
    header: String(q?.header || ''),
    question: String(q?.question || ''),
    selected: Array.from(selectedSet(qi)),
    other: String(otherText[qi] || '').trim(),
  }))
}

function submit() {
  if (!editable.value || submitting.value || !hasAnySelection.value) return
  submitting.value = true
  emit('answer', { requestId: props.block.requestId, answers: buildAnswers() })
}

const resultLabel = computed(() => {
  const answers = Array.isArray(props.block.answers) ? props.block.answers : []
  if (!answers.length) return answered.value ? '✓ 已提交' : ''
  const parts = answers.map((a) => {
    const picks = [...(Array.isArray(a.selected) ? a.selected : [])]
    if (String(a.other || '').trim()) picks.push(`其他:${String(a.other).trim()}`)
    return `${a.header || a.question || ''}:${picks.length ? picks.join('、') : '(未选择)'}`
  })
  return `✓ 已提交 — ${parts.join(' ; ')}`
})
</script>

<style scoped>
.v2-q-card {
  border: 1px solid #b6d4fe;
  border-radius: 10px;
  background: #f5f9ff;
  padding: 12px 14px;
  margin: 8px 0;
}
.v2-q-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.v2-q-badge { font-size: 12px; font-weight: 600; color: #fff; background: #2f7bf0; border-radius: 4px; padding: 1px 8px; }
.v2-q-title { font-weight: 600; font-size: 14px; }
.v2-q-item { padding: 8px 0; border-top: 1px dashed #d6e4ff; }
.v2-q-item:first-of-type { border-top: none; }
.v2-q-row { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.v2-q-tag { font-size: 12px; font-weight: 600; color: #2f7bf0; background: #e6f0ff; border-radius: 4px; padding: 1px 7px; }
.v2-q-question { font-size: 13px; color: #333; }
.v2-q-multi { font-size: 11px; color: #888; }
.v2-q-options { display: flex; flex-direction: column; gap: 6px; }
.v2-q-opt {
  display: flex; align-items: flex-start; gap: 8px; text-align: left;
  border: 1px solid #d6e4ff; border-radius: 8px; background: #fff;
  padding: 7px 10px; cursor: pointer; font-size: 13px;
}
.v2-q-opt:hover:not(:disabled) { border-color: #2f7bf0; }
.v2-q-opt.is-selected { border-color: #2f7bf0; background: #eaf2ff; }
.v2-q-opt:disabled { cursor: default; opacity: 0.85; }
.v2-q-opt-mark { width: 14px; height: 14px; border: 1.5px solid #9bbcf0; border-radius: 50%; margin-top: 2px; flex: 0 0 auto; }
.v2-q-opt-mark.multi { border-radius: 4px; }
.v2-q-opt.is-selected .v2-q-opt-mark { background: #2f7bf0; border-color: #2f7bf0; box-shadow: inset 0 0 0 2px #fff; }
.v2-q-opt-body { display: flex; flex-direction: column; gap: 2px; }
.v2-q-opt-label { font-weight: 500; color: #222; }
.v2-q-opt-desc { font-size: 12px; color: #888; }
.v2-q-other {
  margin-top: 6px; width: 100%; box-sizing: border-box;
  border: 1px solid #d6e4ff; border-radius: 8px; padding: 6px 10px; font-size: 13px; background: #fff;
}
.v2-q-other:disabled { background: #f4f7fb; }
.v2-q-actions { display: flex; justify-content: flex-end; margin-top: 10px; }
.v2-q-submit { border: none; border-radius: 6px; padding: 6px 18px; font-size: 13px; cursor: pointer; background: #2f7bf0; color: #fff; }
.v2-q-submit:disabled { opacity: 0.5; cursor: not-allowed; }
.v2-q-result { margin-top: 8px; font-size: 13px; font-weight: 600; color: #2c9c5a; white-space: pre-wrap; }
</style>
