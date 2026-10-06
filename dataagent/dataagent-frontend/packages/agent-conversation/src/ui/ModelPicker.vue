<template>
  <div ref="root" class="dac-model-picker" part="model-picker" :style="{ '--dac-model-menu-space': menuSpace }">
    <button ref="trigger" type="button" class="dac-model-trigger" data-control="model" part="model-select"
      role="combobox" aria-label="模型" aria-haspopup="listbox" :aria-expanded="open"
      :aria-controls="listId" :disabled="disabled" :title="selected?.label"
      @click="toggle" @keydown="onKeydown">
      <span class="dac-model-label">{{ selected?.name || '选择模型' }}</span>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
    </button>
    <ul v-if="open" :id="listId" class="dac-model-options" role="listbox" aria-label="可用模型">
      <li v-for="(option, index) in options" :key="option.value">
        <button type="button" role="option" :data-value="option.value"
          :aria-selected="option.value === modelValue" :class="{ 'is-active': index === activeIndex }"
          @mouseenter="activeIndex = index" @click="choose(option.value)" @keydown="onKeydown">
          {{ option.label }}<span v-if="option.value === modelValue" aria-hidden="true">✓</span>
        </button>
      </li>
    </ul>
  </div>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
const props = defineProps({ modelValue: String, options: { type: Array, default: () => [] }, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const root = ref(null), trigger = ref(null), open = ref(false), activeIndex = ref(0)
const menuSpace = ref('290px')
const listId = `dac-model-${useId()}`
const selected = computed(() => props.options.find(item => item.value === props.modelValue))
function close() { open.value = false }
function toggle() {
  if (props.disabled || !props.options.length) return
  if (open.value) return close()
  const card = root.value?.closest('.dac-input-card')
  if (card) menuSpace.value = `${Math.max(root.value.getBoundingClientRect().width, card.getBoundingClientRect().right - root.value.getBoundingClientRect().left - 10)}px`
  activeIndex.value = Math.max(0, props.options.findIndex(item => item.value === props.modelValue))
  open.value = true
}
function choose(value) {
  if (props.disabled) return
  emit('update:modelValue', value)
  close()
  trigger.value?.focus()
}
async function focusOption() {
  await nextTick()
  root.value?.querySelectorAll('[role="option"]')[activeIndex.value]?.focus()
}
function onKeydown(event) {
  if (props.disabled || !props.options.length) return
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); trigger.value?.focus(); return }
  if (event.key === 'Tab') { close(); return }
  if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    event.preventDefault()
    if (!open.value) toggle()
    else if (event.key === 'Home') activeIndex.value = 0
    else if (event.key === 'End') activeIndex.value = props.options.length - 1
    else activeIndex.value = (activeIndex.value + (event.key === 'ArrowDown' ? 1 : -1) + props.options.length) % props.options.length
    focusOption()
  } else if (open.value && ['Enter', ' '].includes(event.key)) {
    event.preventDefault()
    choose(props.options[activeIndex.value]?.value)
  }
}
function onOutside(event) { if (!event.composedPath().includes(root.value)) close() }
watch(() => props.disabled, value => { if (value) close() })
onMounted(() => { document.addEventListener('pointerdown', onOutside); window.addEventListener('resize', close) })
onBeforeUnmount(() => { document.removeEventListener('pointerdown', onOutside); window.removeEventListener('resize', close) })
</script>
<style>
.dac-model-picker { position: relative; flex: 0 1 170px; min-width: 0; max-width: 170px; }
.dac-model-trigger { display: flex; align-items: center; justify-content: space-between; gap: 6px; width: 100%; border: 0; border-radius: 6px; padding: 6px 8px; background: #f8fafc; color: #475569; font-family: inherit; font-size: 12px; line-height: 1.5; cursor: pointer; }
.dac-model-trigger:disabled { opacity: .5; cursor: not-allowed; }
.dac-model-trigger svg { flex: none; }
.dac-model-trigger:focus-visible, .dac-model-options button:focus-visible { outline: 2px solid var(--dac-primary, #3b82f6); outline-offset: 2px; }
.dac-model-label { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.dac-model-options { position: absolute; bottom: calc(100% + 8px); left: 0; z-index: 20; width: max-content; min-width: 100%; max-width: min(290px, var(--dac-picker-max-width, 290px), var(--dac-model-menu-space, 290px)); max-height: 240px; overflow: auto; margin: 0; padding: 4px; list-style: none; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; box-shadow: 0 8px 24px rgb(15 23 42 / 12%); }
.dac-model-options button { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%; padding: 8px 10px; border: 0; border-radius: 5px; background: transparent; color: #475569; font: inherit; font-size: 12px; text-align: left; overflow-wrap: anywhere; cursor: pointer; }
.dac-model-options button.is-active { background: #eff6ff; }
.dac-model-options button[aria-selected="true"] { color: var(--dac-primary, #3b82f6); }
</style>
