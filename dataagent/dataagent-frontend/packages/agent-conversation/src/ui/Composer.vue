<template>
  <div class="dac-composer" part="composer">
    <!-- Above the input: overlays that must sit against it, such as a slash
         command menu. Empty for hosts that have none. -->
    <slot name="composer-overlay" />

    <ul v-if="slash.open.value" class="dac-slash" part="slash-menu" role="listbox">
      <li
        v-for="(command, index) in slash.matches.value"
        :key="command.name"
        class="dac-slash-item"
        :class="{ 'is-active': index === slash.activeIndex.value }"
        role="option"
        :aria-selected="index === slash.activeIndex.value"
        @mousedown.prevent="acceptCommand(index)"
      >
        <span class="dac-slash-name">/{{ command.name }}</span>
        <span v-if="command.description" class="dac-slash-desc">{{ command.description }}</span>
      </li>
    </ul>

    <ul v-if="attachments.length || uploading" class="dac-chips" part="attachments">
      <li v-for="file in attachments" :key="file.relPath" class="dac-chip">
        <span class="dac-chip-name">{{ file.name }}</span>
        <button
          type="button"
          class="dac-chip-remove"
          :aria-label="`移除 ${file.name}`"
          @click="removeAttachment(file.relPath)"
        >×</button>
      </li>
      <li v-if="uploading" class="dac-chip is-pending">上传中…</li>
    </ul>
    <p v-if="uploadError" class="dac-upload-error" role="alert">{{ uploadError }}</p>

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
        <!-- Attaching exists only when the host can store a file. -->
        <template v-if="canUpload">
          <input
            ref="fileRef"
            type="file"
            class="dac-file-input"
            multiple
            @change="onFilesPicked"
          />
          <button
            type="button"
            class="dac-action-btn"
            part="attach-button"
            data-action="attach"
            aria-label="添加附件"
            :disabled="disabled || uploading"
            @click="fileRef?.click()"
          >📎</button>
        </template>

        <!-- Rendered only when the host supplies the options. A picker over an
             empty list is a control that looks broken. -->
        <select
          v-if="providers.length"
          class="dac-select"
          part="model-select"
          data-control="model"
          aria-label="模型"
          :disabled="disabled"
          :value="modelKey"
          @change="onModelChange($event.target.value)"
        >
          <optgroup v-for="provider in providers" :key="provider.id" :label="provider.label">
            <option
              v-for="model in provider.models || []"
              :key="`${provider.id}/${model.id}`"
              :value="`${provider.id}/${model.id}`"
            >{{ model.label }}</option>
          </optgroup>
        </select>

        <select
          v-if="permissionModes.length"
          class="dac-select"
          part="permission-select"
          data-control="permission-mode"
          aria-label="权限模式"
          :disabled="disabled"
          :value="permissionMode"
          @change="onPermissionChange($event.target.value)"
        >
          <option
            v-for="mode in permissionModes"
            :key="mode.id"
            :value="mode.id"
            :title="mode.description || ''"
          >{{ mode.label }}</option>
        </select>

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
          :disabled="disabled || uploading || (!modelValue.trim() && !attachments.length)"
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
import { computed, inject, ref, toRef, unref, watch } from 'vue'
import { isPlainEnterSubmit } from '../core/message.js'
import { useSlashMenu } from '../core/useSlashMenu.js'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  active: { type: Boolean, default: false },
  runDetail: { type: String, default: '' },
  config: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:modelValue', 'send', 'cancel', 'settings-change'])

const inputRef = ref(null)

const providers = computed(() =>
  (props.config?.providers || []).filter((item) => (item?.models || []).length)
)
const permissionModes = computed(() => props.config?.permissionModes || [])
const slashCommands = computed(() => props.config?.slashCommands || [])

const providerId = ref('')
const model = ref('')
const permissionMode = ref('')

// One <select> cannot hold two values, so provider and model travel as one key
// and are split apart on the way out.
const modelKey = computed(() => (providerId.value && model.value ? `${providerId.value}/${model.value}` : ''))

const settings = computed(() => {
  const value = {}
  if (providerId.value) value.providerId = providerId.value
  if (model.value) value.model = model.value
  if (permissionMode.value) value.permissionMode = permissionMode.value
  return value
})

// Default to the first option the host offers. Leaving them unset would send a
// message with no model while the picker plainly shows one selected.
watch(providers, (list) => {
  const current = list.find((item) => item.id === providerId.value)
  if (current?.models?.some((item) => item.id === model.value)) return
  const first = list[0]
  providerId.value = first?.id || ''
  model.value = first?.models?.[0]?.id || ''
}, { immediate: true })

watch(permissionModes, (list) => {
  if (list.some((item) => item.id === permissionMode.value)) return
  permissionMode.value = list[0]?.id || ''
}, { immediate: true })

watch(settings, (value) => emit('settings-change', value), { immediate: true })

const onModelChange = (value) => {
  const separator = String(value).indexOf('/')
  providerId.value = separator < 0 ? '' : String(value).slice(0, separator)
  model.value = separator < 0 ? '' : String(value).slice(separator + 1)
}

const onPermissionChange = (value) => {
  permissionMode.value = String(value)
}

const transport = inject('agentConversationTransport', null)
const canUpload = computed(() => typeof unref(transport)?.uploadFiles === 'function')

const fileRef = ref(null)
const attachments = ref([])
const uploading = ref(false)
const uploadError = ref('')

const onFilesPicked = async (event) => {
  const picked = [...(event.target.files || [])]
  // Clearing lets the same file be picked again after it was removed;
  // the input reports no change event for an unchanged value.
  event.target.value = ''
  if (!picked.length) return

  uploading.value = true
  uploadError.value = ''
  try {
    const uploaded = await unref(transport).uploadFiles(picked)
    const known = new Set(attachments.value.map((file) => file.relPath))
    for (const file of uploaded || []) {
      if (!file?.relPath || known.has(file.relPath)) continue
      attachments.value = [...attachments.value, file]
      known.add(file.relPath)
    }
  } catch (error) {
    uploadError.value = error?.message || '上传失败'
  } finally {
    uploading.value = false
  }
}

const removeAttachment = (relPath) => {
  attachments.value = attachments.value.filter((file) => file.relPath !== relPath)
}

const slash = useSlashMenu(toRef(props, 'modelValue'), slashCommands)

const acceptCommand = (index) => {
  const text = slash.accept(index)
  if (text === null) return
  emit('update:modelValue', text)
  inputRef.value?.focus()
}

defineExpose({
  focus: () => inputRef.value?.focus(),
  getSettings: () => settings.value,
  getAttachments: () => attachments.value,
  clearAttachments: () => { attachments.value = [] }
})

const onKeydown = (event) => {
  // The menu gets first refusal: while it is open, Enter picks a command
  // rather than sending, and the arrows move through it instead of the text.
  const consumed = slash.handleKeydown(event)
  if (consumed !== null) {
    if (consumed) emit('update:modelValue', consumed)
    return
  }
  // isPlainEnterSubmit already accounts for IME composition — during Chinese
  // input Enter commits the candidate rather than sending.
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
.dac-select {
  height: 28px;
  max-width: 180px;
  padding: 0 6px;
  border: 1px solid var(--dac-input-border, #cbd5e1);
  border-radius: var(--dac-button-radius, 8px);
  background: var(--dac-input-bg, #ffffff);
  color: var(--dac-input-color, inherit);
  font: inherit;
  font-size: 12px;
}
.dac-slash {
  margin: 0 0 8px;
  padding: 4px;
  max-height: 220px;
  overflow-y: auto;
  list-style: none;
  border: 1px solid var(--dac-border-color, #e2e8f0);
  border-radius: var(--dac-input-radius, 10px);
  background: var(--dac-slash-bg, #ffffff);
  box-shadow: 0 8px 24px rgb(15 23 42 / 12%);
}
.dac-slash-item {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
}
.dac-slash-item.is-active,
.dac-slash-item:hover {
  background: var(--dac-assistant-bubble-bg, #f1f5f9);
}
.dac-file-input { display: none; }
.dac-action-btn {
  height: 28px;
  width: 28px;
  border: 1px solid var(--dac-input-border, #cbd5e1);
  border-radius: var(--dac-button-radius, 8px);
  background: transparent;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
.dac-action-btn:disabled { cursor: not-allowed; opacity: 0.5; }
.dac-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0 0 8px;
  padding: 0;
  list-style: none;
}
.dac-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  border-radius: 999px;
  background: var(--dac-assistant-bubble-bg, #f1f5f9);
  font-size: 12px;
}
.dac-chip.is-pending { color: var(--dac-text-muted, #64748b); }
.dac-chip-name { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dac-chip-remove {
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  line-height: 1;
  cursor: pointer;
}
.dac-upload-error {
  margin: 0 0 8px;
  color: var(--dac-error-color, #b91c1c);
  font-size: 12px;
}
.dac-slash-name { font-weight: 600; }
.dac-slash-desc {
  color: var(--dac-text-muted, #64748b);
  font-size: 12px;
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
