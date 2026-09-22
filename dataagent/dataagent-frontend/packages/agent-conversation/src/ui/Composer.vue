<template>
  <div class="dac-composer">
    <!-- Above the input: overlays that must sit against it, such as a slash
         command menu. Empty for hosts that have none. -->
    <slot name="composer-overlay" />
    <textarea
      ref="inputRef"
      class="dac-input"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      rows="3"
      @input="$emit('update:modelValue', $event.target.value)"
      @keydown="onKeydown"
    />
    <div class="dac-composer-footer">
      <div class="dac-composer-actions">
        <slot name="composer-actions" />
      </div>
      <div class="dac-composer-right">
        <span v-if="runDetail" class="dac-run-detail">{{ runDetail }}</span>
        <button v-if="active" type="button" class="dac-stop" @click="$emit('cancel')">停止</button>
        <button
          v-else
          type="button"
          class="dac-send"
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
.dac-composer { border-top: 1px solid #e2e8f0; padding: 10px 14px 12px; }
.dac-input {
  width: 100%; border: 1px solid #cbd5e1; border-radius: 10px;
  padding: 9px 11px; font: inherit; resize: none; outline: none; box-sizing: border-box;
}
.dac-input:focus { border-color: #10b981; }
.dac-composer-footer {
  display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 8px;
}
.dac-composer-actions { display: flex; align-items: center; gap: 8px; }
.dac-composer-right { display: flex; align-items: center; gap: 10px; }
.dac-run-detail { color: #64748b; font-size: 12px; }
.dac-send, .dac-stop {
  height: 32px; padding: 0 16px; border-radius: 8px; border: none;
  font-size: 13px; font-weight: 600; cursor: pointer;
}
.dac-send { background: #10b981; color: #fff; }
.dac-send:disabled { background: #cbd5e1; cursor: not-allowed; }
.dac-stop { background: #f1f5f9; color: #0f172a; }
</style>
