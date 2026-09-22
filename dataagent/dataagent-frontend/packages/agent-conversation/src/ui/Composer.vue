<template>
  <div class="dac-composer" part="composer">
    <!-- Above the input: overlays that must sit against it, such as a slash
         command menu. Empty for hosts that have none. -->
    <slot name="composer-overlay" />
    <textarea
      ref="inputRef"
      class="dac-input"
      part="input"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      rows="3"
      @input="$emit('update:modelValue', $event.target.value)"
      @keydown="onKeydown"
    />
    <div class="dac-composer-footer" part="footer">
      <div class="dac-composer-actions" part="actions">
        <slot name="composer-actions" />
      </div>
      <div class="dac-composer-right" part="controls">
        <span v-if="runDetail" class="dac-run-detail" part="run-detail">{{ runDetail }}</span>
        <button v-if="active" type="button" class="dac-stop" part="stop-button" @click="$emit('cancel')">停止</button>
        <button
          v-else
          type="button"
          class="dac-send"
          part="send-button"
          :disabled="disabled || !modelValue.trim()"
          @click="$emit('send')"
        >发送</button>
      </div>
    </div>
    <!-- Below the footer: a host's own composer controls. The widget puts its
         permission-mode and model selectors here; those are product choices
         the SDK has no opinion about, so it supplies the space rather than the
         controls. -->
    <slot name="composer-toolbar" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { isPlainEnterSubmit } from '../core/message.js'

defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  active: { type: Boolean, default: false },
  runDetail: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'send', 'cancel'])

const inputRef = ref(null)
defineExpose({ focus: () => inputRef.value?.focus() })

// isPlainEnterSubmit already accounts for IME composition — during Chinese
// input Enter commits the candidate rather than sending.
const onKeydown = (event) => {
  if (!isPlainEnterSubmit(event)) return
  event.preventDefault()
  emit('send')
}
</script>

<style>
.dac-composer {
  border-top: 1px solid var(--dac-border-color, #e2e8f0);
  padding: 10px 14px 12px;
  background: var(--dac-composer-bg, transparent);
}
.dac-input {
  width: 100%;
  border: 1px solid var(--dac-input-border, #cbd5e1);
  border-radius: var(--dac-input-radius, 10px);
  padding: 9px 11px;
  font: inherit;
  resize: none;
  outline: none;
  box-sizing: border-box;
  background: var(--dac-input-bg, #ffffff);
  color: var(--dac-input-color, inherit);
  transition: border-color 0.15s ease;
}
.dac-input:focus {
  border-color: var(--dac-input-focus-border, var(--dac-primary, #10b981));
}
.dac-composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
}
.dac-composer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.dac-composer-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.dac-run-detail {
  color: var(--dac-text-muted, #64748b);
  font-size: 12px;
}
.dac-send, .dac-stop {
  height: 32px;
  padding: 0 16px;
  border-radius: var(--dac-button-radius, 8px);
  border: none;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, opacity 0.15s ease;
}
.dac-send {
  background: var(--dac-primary, #10b981);
  color: var(--dac-send-color, #ffffff);
}
.dac-send:hover:not(:disabled) {
  background: var(--dac-primary-hover, #059669);
}
.dac-send:disabled {
  background: var(--dac-disabled-bg, #cbd5e1);
  color: var(--dac-disabled-color, #ffffff);
  cursor: not-allowed;
}
.dac-stop {
  background: var(--dac-stop-bg, #f1f5f9);
  color: var(--dac-stop-color, #0f172a);
  border: 1px solid var(--dac-border-color, #e2e8f0);
}
.dac-stop:hover {
  background: var(--dac-stop-hover-bg, #e2e8f0);
}
</style>
