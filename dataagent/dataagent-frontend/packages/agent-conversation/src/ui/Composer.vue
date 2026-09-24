<template>
  <div class="dac-composer" part="composer">
    <div class="dac-composer-inner" part="composer-inner">
    <!-- Above the input: overlays that must sit against it, such as a slash
         command menu. Empty for hosts that have none. -->
    <slot name="composer-overlay" />

    <ul v-if="slash.visible.value" class="dac-slash" part="slash-menu" role="listbox">
      <li
        v-for="(command, index) in slash.filtered.value"
        :key="command.id"
        class="dac-slash-item"
        :class="{ 'is-active': index === slash.activeIndex.value }"
        role="option"
        :aria-selected="index === slash.activeIndex.value"
        @mouseenter="slash.setActive(index)"
        @mousedown.prevent="slash.select(command)"
      >
        <span class="dac-slash-name">{{ command.id }}</span>
        <span v-if="command.label" class="dac-slash-label">{{ command.label }}</span>
        <span v-if="command.hint" class="dac-slash-desc">{{ command.hint }}</span>
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

    <div class="dac-input-card">
      <textarea
        ref="inputRef"
        class="dac-input"
        part="input"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled || !hasConfiguredModel"
        rows="1"
        @input="onInput($event.target.value)"
        @keydown="onKeydown"
      />
      <div class="dac-composer-inline">
        <span class="dac-composer-hint">Enter 发送，Shift + Enter 换行</span>
        <button
          v-if="active"
          type="button"
          class="dac-stop dac-send-control"
          part="stop-button"
          title="停止"
          aria-label="停止"
          @click="$emit('cancel')"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15" aria-hidden="true">
            <rect x="8" y="8" width="8" height="8" rx="1.5" />
          </svg>
        </button>
        <button
          v-else
          type="button"
          class="dac-send dac-send-control"
          part="send-button"
          title="发送"
          aria-label="发送"
          :disabled="disabled || !hasConfiguredModel || uploading || (!modelValue.trim() && !attachments.length)"
          @click="$emit('send')"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15" aria-hidden="true">
            <line x1="12" y1="19" x2="12" y2="5" />
            <polyline points="5 12 12 5 19 12" />
          </svg>
        </button>
      </div>
    </div>
    <div class="dac-composer-footer" part="footer">
      <div class="dac-composer-actions" part="actions">
        <label v-if="shows('permissionMode') && permissionModes.length" class="dac-picker dac-permission-picker" part="permission-picker" title="权限模式">
          <span class="dac-perm-dot" :class="`is-${permissionMode}`" aria-hidden="true" />
          <span class="dac-picker-label">{{ permissionModeLabel }}</span>
          <select
            class="dac-picker-select"
            part="permission-select"
            data-control="permission-mode"
            aria-label="权限模式"
            :disabled="disabled"
            :value="permissionMode"
            @change="onPermissionChange($event.target.value)"
          >
            <option
              v-for="mode in permissionModes"
              :key="mode.value"
              :value="mode.value"
              :title="mode.desc || ''"
            >{{ mode.label }}</option>
          </select>
        </label>

        <!-- Attaching exists only when the host can store a file. -->
        <template v-if="canUpload">
          <input
            ref="fileRef"
            type="file"
            class="dac-file-input"
            multiple
            @change="onFilesPicked"
          />
          <!-- The same plus the shells have always shown. A paperclip would
               be a perfectly good icon and still the wrong one: the control
               has to stay recognisable to people who used the old page. -->
          <button
            type="button"
            class="dac-action-btn"
            part="attach-button"
            data-action="attach"
            title="上传文件"
            aria-label="上传文件"
            :disabled="disabled || uploading"
            @click="fileRef?.click()"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" aria-hidden="true">
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
          </button>
        </template>

        <slot name="composer-actions" />
      </div>
      <div class="dac-composer-right" part="controls">
        <span v-if="runDetail" class="dac-run-detail" part="run-detail">{{ runDetail }}</span>

        <!-- Rendered only when the host supplies the options. A picker over an
             empty list is a control that looks broken. -->
        <!-- Borderless pickers with an icon, the way the shells have always
             drawn them. Native <select> underneath so keyboard and screen
             readers keep working; only the chrome is restyled. -->
        <label v-if="shows('model') && providers.length" class="dac-picker" part="model-picker" title="切换模型">
          <svg class="dac-picker-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="13" height="13" aria-hidden="true">
            <path d="M12 2V4" />
            <rect x="4" y="6" width="16" height="12" rx="2" />
            <circle cx="9" cy="12" r="1.5" fill="currentColor" stroke="none" />
            <circle cx="15" cy="12" r="1.5" fill="currentColor" stroke="none" />
          </svg>
          <span class="dac-picker-label">{{ model }}</span>
        <select
          class="dac-picker-select"
          part="model-select"
          data-control="model"
          aria-label="模型"
          :disabled="disabled"
          :value="modelKey"
          @change="onModelChange($event.target.value)"
        >
          <optgroup v-for="provider in providers" :key="provider.provider_id" :label="provider.provider_id">
            <option
              v-for="name in provider.models || []"
              :key="`${provider.provider_id}::${name}`"
              :value="`${provider.provider_id}::${name}`"
            >{{ name }}</option>
          </optgroup>
        </select>
        </label>
      </div>
    </div>
    <!-- Below the footer: a host's own composer controls. The widget puts its
         permission-mode and model selectors here; those are product choices
         the SDK has no opinion about, so it supplies the space rather than the
         controls. -->
    <slot name="composer-toolbar" />
    </div>
  </div>
</template>

<script setup>
import { computed, inject, nextTick, onMounted, ref, unref, watch } from 'vue'
import { isPlainEnterSubmit } from '../core/message.js'
import { useSlashCommands } from '../core/slashCommands.js'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  active: { type: Boolean, default: false },
  runDetail: { type: String, default: '' },
  config: { type: Object, default: () => ({}) },
  endpointReady: { type: Boolean, default: false },
  ensureEndpoint: { type: Function, default: null },
})
const emit = defineEmits(['update:modelValue', 'send', 'cancel', 'settings-change', 'permission-error'])

const inputRef = ref(null)

const transport = inject('agentConversationTransport', null)

/**
 * Whether a composer control is offered at all.
 *
 * A control still needs its capability — a model picker without providers has
 * nothing to pick. This only lets a host withhold one it could have shown, so
 * omitting `controls` keeps every capable control visible.
 */
const shows = (name) => props.config?.controls?.[name] !== false

const providers = computed(() =>
  (props.config?.providers || []).filter(
    (item) => item?.enabled !== false && Array.isArray(item?.models) && item.models.length,
  )
)
const hasConfiguredModel = computed(() => !Array.isArray(props.config?.providers) || providers.value.length > 0)
const permissionModes = computed(() => props.config?.permissionModes || [])
const slashCommands = computed(() => props.config?.slashCommands || [])

const providerId = ref('')
const model = ref('')
const permissionMode = ref('')

// `provider_id::model` is the same composite key the existing dropdown uses; a
// <select> cannot hold two values, and reusing the separator keeps host code
// that already parses it working.
const modelKey = computed(() => (providerId.value && model.value ? `${providerId.value}::${model.value}` : ''))

// Snake_case because these are the host's own option objects, handed straight
// back. Asking a host to rename fields it already has is the kind of adapter
// that makes adopting the element a rewrite rather than a swap.
const settings = computed(() => {
  const value = {}
  if (providerId.value) value.provider_id = providerId.value
  if (model.value) value.model = model.value
  if (permissionMode.value) value.permission_mode = permissionMode.value
  return value
})

// Restore the runtime's configured defaults. Falling back to the first enabled
// option is only for a genuinely absent/invalid default, matching the existing
// NL2SqlChatV2 loadConfig path.
watch(() => [
  providers.value,
  props.config?.default_provider_id || '',
  props.config?.default_model || '',
], ([list, defaultProviderId, defaultModel], previous = []) => {
  const defaultsChanged = defaultProviderId !== previous[1] || defaultModel !== previous[2]
  const current = list.find((item) => item.provider_id === providerId.value)
  if (!defaultsChanged && current?.models?.includes(model.value)) return
  const selected = list.find((item) => item.provider_id === defaultProviderId) || list[0]
  providerId.value = selected?.provider_id || ''
  model.value = selected?.models?.includes(defaultModel)
    ? defaultModel
    : (selected?.models?.includes(selected?.default_model) ? selected.default_model : (selected?.models?.[0] || ''))
}, { immediate: true })

// The host supplies the conversation's saved mode; switching conversations
// changes it, and the picker has to follow or it shows the previous one's.
watch(() => [permissionModes.value, props.config?.permissionMode], ([list, saved]) => {
  const options = list || []
  if (saved && options.some((item) => item.value === saved)) {
    permissionMode.value = saved
    return
  }
  if (options.some((item) => item.value === permissionMode.value)) return
  permissionMode.value = options[0]?.value || ''
}, { immediate: true })

watch(settings, (value) => emit('settings-change', value), { immediate: true })

const onModelChange = (value) => {
  const [provider = '', name = ''] = String(value).split('::')
  providerId.value = provider
  model.value = name
}

/**
 * Switch permission mode, persisting it when the host can.
 *
 * Applied optimistically and rolled back if the save fails. The shell it
 * replaces showed a toast and kept the new value on screen, which left the
 * picker claiming a mode the server had not accepted — the next run would then
 * use the old one with nothing on screen saying so.
 */
const permissionModeLabel = computed(() =>
  permissionModes.value.find((item) => item.value === permissionMode.value)?.label || ''
)

const onPermissionChange = async (value) => {
  const previous = permissionMode.value
  const next = String(value)
  permissionMode.value = next

  const save = unref(transport)?.setPermissionMode
  if (typeof save !== 'function') return
  try {
    await save(next)
  } catch (error) {
    permissionMode.value = previous
    emit('permission-error', error)
  }
}

const canUpload = computed(() =>
  shows('attach') && (
    typeof unref(transport)?.uploadFiles === 'function' ||
    (!props.endpointReady && props.config?.uploadBeforeConversation === true && typeof props.ensureEndpoint === 'function')
  )
)

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
    if (!props.endpointReady) {
      const address = await props.ensureEndpoint?.({ reason: 'upload', files: picked })
      if (!address) throw new Error('无法创建会话，文件未上传')
    }
    const activeTransport = unref(transport)
    if (typeof activeTransport?.uploadFiles !== 'function') {
      throw new Error('当前会话不支持文件上传')
    }
    const uploaded = await activeTransport.uploadFiles(picked)
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

// A local mirror of the draft, because the menu has to read the text the user
// just typed. Reading the prop would read the previous value: the prop updates
// a tick after `update:modelValue` is emitted, so the menu would filter one
// keystroke behind and never open on the first `/`.
const localDraft = ref(props.modelValue)

function autoResize() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

watch(() => props.modelValue, (value) => {
  if (value !== localDraft.value) localDraft.value = value
  nextTick(autoResize)
})

onMounted(() => nextTick(autoResize))

const inputText = computed({
  get: () => localDraft.value,
  set: (value) => {
    localDraft.value = value
    emit('update:modelValue', value)
  }
})

const slash = useSlashCommands({
  getCommands: () => slashCommands.value,
  inputText,
  focusInput: () => nextTick(() => { inputRef.value?.focus(); autoResize() })
})

const onInput = (value) => {
  inputText.value = value
  slash.syncFromInput()
  autoResize()
}

defineExpose({
  focus: () => nextTick(() => { inputRef.value?.focus(); autoResize() }),
  getSettings: () => settings.value,
  getAttachments: () => attachments.value,
  clearAttachments: () => { attachments.value = [] }
})

const onKeydown = (event) => {
  // The menu gets first refusal: while it is open, Enter and Tab pick a
  // command rather than sending, and the arrows move through it.
  if (slash.handleKeydown(event)) return
  // isPlainEnterSubmit already accounts for IME composition — during Chinese
  // input Enter commits the candidate rather than sending.
  if (!isPlainEnterSubmit(event)) return
  event.preventDefault()
  emit('send')
}
</script>

<style>
.dac-composer {
  border-top: none;
  padding-top: 32px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0) 0%, rgba(255, 255, 255, 0.85) 30%, #ffffff 50%);
  transition: background 0.3s ease;
}
.dac-root.is-empty .dac-composer { padding-top: 0; background: transparent; }
.dac-composer-inner {
  box-sizing: border-box;
  width: 100%;
  max-width: var(--dac-content-max-width, none);
  margin: 0 auto;
  padding-block: 10px 12px;
  padding-inline: var(--dac-content-padding-inline, 14px);
}
.dac-input-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
  padding: 12px 14px 10px 16px;
  border: 1px solid #dde2ea;
  border-radius: 16px;
  background: #ffffff;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.04);
}
.dac-input-card:focus-within {
  border-color: #b0bbcc;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08);
}
.dac-input {
  flex: none;
  width: 100%;
  min-width: 0;
  border: none;
  padding: 0;
  font-size: 14px;
  line-height: 1.55;
  font-family: inherit;
  resize: none;
  outline: none;
  box-sizing: border-box;
  background: transparent;
  color: #162131;
  min-height: 22px;
  max-height: 160px;
  overflow-y: auto;
}
.dac-input::placeholder { color: #A0AABF; }
.dac-composer-inline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.dac-composer-hint {
  color: #9aa5b1;
  font-size: 11px;
  line-height: 1.4;
  white-space: nowrap;
}
.dac-composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  padding-inline: 4px;
}
.dac-composer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* Geometry and colours copied from the shells' `.v2-model-btn` / `.v2-perm-pill`. */
.dac-picker {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border-radius: 6px;
  color: var(--dac-icon-color, #8a96a6);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
  max-width: 160px;
}
.dac-picker:hover {
  background: var(--dac-icon-hover-bg, #eef1f5);
  color: var(--dac-icon-hover-color, #4a5568);
}
.dac-picker-icon { flex: none; }
/* The visible label sets the width; the select lies on top of it, invisible.
   A native <select> is as wide as its longest option, so "Bypass permissions"
   was sizing the control even while it read "Default" — which pushed the
   attach button and the whole left group out of place. */
.dac-picker-label {
  font-size: 12px;
  white-space: nowrap;
}
.dac-picker-select {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  appearance: none;
  border: none;
  background: transparent;
  font: inherit;
  cursor: pointer;
}
.dac-picker-select:disabled { cursor: not-allowed; }
.dac-permission-picker { gap: 6px; }
/* The mode is legible at a glance from the dot alone, as it was before. */
.dac-perm-dot {
  width: 8px;
  height: 8px;
  flex: none;
  border-radius: 50%;
  background: var(--dac-perm-dot-color, #e6a23c);
}
.dac-perm-dot.is-default { background: #409eff; }
.dac-perm-dot.is-acceptEdits { background: #e6a23c; }
.dac-perm-dot.is-plan { background: #909399; }
.dac-perm-dot.is-bypassPermissions { background: #c0392b; }
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
/* Geometry and colours copied from the shells' own `.v2-attach-btn`. */
.dac-action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  flex: none;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--dac-icon-color, #8a96a6);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.dac-action-btn:hover:not(:disabled) {
  background: var(--dac-icon-hover-bg, #eef1f5);
  color: var(--dac-icon-hover-color, #4a5568);
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
.dac-slash-label { color: var(--dac-text-color, #0f172a); }
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
.dac-send-control {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 8px;
  background: #e8eaed;
  color: #606878;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  padding: 0;
  transition: background 0.15s ease, color 0.15s ease, transform 0.15s ease;
}
.dac-send:not(:disabled) {
  background: linear-gradient(135deg, var(--dac-primary, #10b981) 0%, var(--dac-primary-dark, #059669) 100%);
  color: #fff;
}
.dac-send-control:disabled { opacity: 0.4; cursor: default; }
.dac-send-control:not(:disabled):hover { transform: scale(1.06); }
.dac-send-control:active { transform: scale(0.94); }
.dac-stop {
  background: linear-gradient(135deg, #7f1d1d 0%, #b91c1c 100%);
  color: #fff;
}
</style>
