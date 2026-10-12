<template>
  <span class="ai-assistant-entry" :data-state="state">
    <button
      class="ai-assistant-trigger"
      type="button"
      :disabled="unavailable || loading"
      :aria-label="`AI助手${currentPrompt ? `，${currentPrompt}` : ''}`"
      :aria-expanded="expanded"
      :aria-busy="loading"
      :title="error ? '打开失败，点击重试' : currentPrompt || '打开 AI 助手'"
      @mouseenter="hovered = true"
      @mouseleave="hovered = false"
      @focus="focused = true"
      @blur="focused = false"
      @click="activate"
    >
      <span v-if="loading" class="ai-assistant-spinner" aria-hidden="true" />
      <img v-else :src="assistantIcon" width="18" height="18" alt="" aria-hidden="true">
      <span class="ai-assistant-label">AI助手</span>
      <span v-if="currentPrompt || loading" class="ai-assistant-prompt-window" aria-hidden="true">
        <Transition name="ai-assistant-prompt" mode="out-in">
          <span :key="loading ? 'loading' : currentPrompt" class="ai-assistant-prompt">{{ loading ? '正在打开…' : currentPrompt }}</span>
        </Transition>
      </span>
    </button>
    <span v-if="error" class="ai-assistant-error" role="status">打开失败，点击重试</span>
  </span>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import assistantIcon from '@/assets/ai-assistant.svg'
import { FLOATING_WIDGET_KEY } from '@/utils/floatingWidget'

const props = defineProps({
  prompts: { type: Array, default: () => [] },
  openAssistant: { type: Function, default: null },
  expanded: { type: Boolean, default: undefined },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['opened', 'error'])
const bridge = inject(FLOATING_WIDGET_KEY, null)
const prompts = computed(() => props.prompts.filter(text => typeof text === 'string' && text.trim()).map(text => text.trim()))
const index = ref(0)
const currentPrompt = computed(() => prompts.value[index.value % prompts.value.length] || '')
const loading = ref(false), error = ref(false), succeeded = ref(false)
const hovered = ref(false), focused = ref(false), reducedMotion = ref(false), hidden = ref(false)
const expanded = computed(() => props.expanded ?? (props.openAssistant ? undefined : bridge?.isOpen?.value))
const unavailable = computed(() => props.disabled || !(props.openAssistant || bridge?.open))
const state = computed(() => unavailable.value ? 'disabled' : loading.value ? 'loading' : error.value ? 'error' : expanded.value || succeeded.value ? 'success' : 'default')

watch(prompts, () => { index.value = 0 })
watch(expanded, (value) => { if (value === false) succeeded.value = false })

async function activate() {
  if (unavailable.value || loading.value) return
  const draft = currentPrompt.value
  loading.value = true
  error.value = false
  try {
    await (props.openAssistant || bridge.open)(draft)
    succeeded.value = true
    emit('opened', { draft })
  } catch (cause) {
    error.value = true
    emit('error', cause)
  } finally {
    loading.value = false
  }
}

let timer, media
const updateVisibility = () => { hidden.value = document.hidden }
const updateMotion = () => { reducedMotion.value = Boolean(media?.matches) }
onMounted(() => {
  media = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  updateMotion()
  updateVisibility()
  media?.addEventListener('change', updateMotion)
  document.addEventListener('visibilitychange', updateVisibility)
  timer = setInterval(() => {
    if (prompts.value.length > 1 && !hovered.value && !focused.value && !loading.value && !hidden.value && !reducedMotion.value) index.value += 1
  }, 5000)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  media?.removeEventListener('change', updateMotion)
  document.removeEventListener('visibilitychange', updateVisibility)
})
</script>

<style scoped>
/* Hallmark · component scope · Tencent reference · violet accent; P4 H4 E4 S5 R5 V4 */
.ai-assistant-entry {
  --ai-paper: var(--odw-bg-card, #fff);
  --ai-ink: var(--odw-text-primary, #1a202c);
  --ai-border: #e6e9ef;
  --ai-accent: #6f49d3;
  --ai-hover: #f4f2fa;
  --ai-active: #ede8fa;
  --ai-error: #b42318;
  --ai-ease-out: cubic-bezier(.2, .8, .2, 1);
  position: relative;
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  min-width: 0;
  vertical-align: middle;
}
.ai-assistant-trigger {
  display: inline-flex;
  align-items: center;
  min-width: 0;
  max-width: 100%;
  height: 32px;
  padding: 0 12px;
  gap: 7px;
  border: 1px solid var(--ai-border);
  border-radius: 999px;
  background: var(--ai-paper);
  color: var(--ai-ink);
  font: inherit;
  font-size: 13px;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
}
.ai-assistant-trigger::before { content: ''; position: absolute; inset: -6px 0; }
.ai-assistant-trigger img { flex: none; }
.ai-assistant-label { font-weight: 650; flex: none; }
.ai-assistant-prompt-window { display: block; overflow: hidden; min-width: 0; max-width: 220px; color: var(--ai-accent); }
.ai-assistant-prompt { display: block; overflow: hidden; text-overflow: ellipsis; line-height: 24px; }
.ai-assistant-trigger:hover:not(:disabled), .is-hover .ai-assistant-trigger { background: var(--ai-hover); }
.ai-assistant-trigger:active:not(:disabled), .is-active .ai-assistant-trigger { background: var(--ai-active); transform: translateY(1px); }
.ai-assistant-trigger:focus-visible, .is-focus .ai-assistant-trigger { outline: 2px solid var(--ai-accent); outline-offset: 3px; }
.ai-assistant-trigger:disabled { opacity: .55; cursor: not-allowed; }
[data-state='success'] .ai-assistant-trigger { border-color: var(--ai-accent); background: var(--ai-hover); }
[data-state='error'] .ai-assistant-trigger { border-color: var(--ai-error); }
.ai-assistant-error { position: absolute; top: calc(100% + 4px); left: 0; white-space: nowrap; color: var(--ai-error); font-size: 12px; }
.ai-assistant-spinner { flex: none; width: 16px; height: 16px; border: 2px solid var(--ai-border); border-top-color: var(--ai-accent); border-radius: 50%; animation: ai-assistant-spin .7s linear infinite; }
.ai-assistant-prompt-enter-active, .ai-assistant-prompt-leave-active { transition: transform .2s var(--ai-ease-out), opacity .2s var(--ai-ease-out); }
.ai-assistant-prompt-enter-from { opacity: 0; transform: translateY(6px); }
.ai-assistant-prompt-leave-to { opacity: 0; transform: translateY(-6px); }
@keyframes ai-assistant-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) {
  .ai-assistant-spinner { animation: none; }
  .ai-assistant-prompt-enter-active, .ai-assistant-prompt-leave-active { transition: opacity .1s linear; }
  .ai-assistant-prompt-enter-from, .ai-assistant-prompt-leave-to { transform: none; }
}
</style>
