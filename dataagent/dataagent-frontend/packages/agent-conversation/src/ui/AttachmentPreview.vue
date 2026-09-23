<template>
  <div class="dac-preview" part="preview" role="dialog" aria-modal="true" :aria-label="file.name">
    <div class="dac-preview-bar">
      <span class="dac-preview-name">{{ file.name }}</span>
      <!-- Saved through the transport, not a bare link: a browser navigation
           cannot carry the headers a runtime may require, so a plain href
           downloads an error page on any authenticated host. -->
      <button type="button" class="dac-preview-download" @click="download">下载</button>
      <button type="button" class="dac-preview-close" aria-label="关闭预览" @click="$emit('close')">×</button>
    </div>

    <div class="dac-preview-body">
      <p v-if="error" class="dac-preview-error" role="alert">{{ error }}</p>
      <p v-else-if="loading" class="dac-preview-loading">加载中…</p>
      <img v-else-if="kind === 'image' && objectUrl" class="dac-preview-image" :src="objectUrl" :alt="file.name" />
      <pre v-else-if="kind === 'text'" class="dac-preview-text">{{ text }}</pre>
      <!-- `sandbox` with no tokens is the whole point: the document gets an
           opaque origin and no script execution. Generated HTML is content, not
           code — granting allow-scripts and allow-same-origin together would be
           the same as not sandboxing it, since the frame could then reach back
           into this page. -->
      <iframe
        v-else-if="kind === 'html' && text !== null"
        class="dac-preview-frame"
        sandbox=""
        :srcdoc="text"
        :title="file.name"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { previewKindFor } from '../core/previewKind.js'

const props = defineProps({
  file: { type: Object, required: true },
  readFile: { type: Function, required: true },
})

defineEmits(['close'])

const loading = ref(false)
const error = ref('')
const objectUrl = ref('')
const text = ref(null)

const path = computed(() => String(props.file?.relPath || props.file?.name || ''))
const kind = computed(() => previewKindFor(path.value))

const release = () => {
  if (objectUrl.value) {
    URL.revokeObjectURL(objectUrl.value)
    objectUrl.value = ''
  }
  text.value = null
}

const load = async () => {
  release()
  error.value = ''
  if (!kind.value) {
    error.value = '这种文件暂不支持预览，请下载后查看'
    return
  }

  loading.value = true
  try {
    const blob = await props.readFile(path.value)
    if (kind.value === 'image') {
      objectUrl.value = URL.createObjectURL(blob)
    } else {
      text.value = await blob.text()
    }
  } catch (cause) {
    error.value = cause?.message || '预览加载失败'
  } finally {
    loading.value = false
  }
}

/** Save the file, fetching it through the transport so auth headers apply. */
const download = async () => {
  try {
    const blob = await props.readFile(path.value)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = String(props.file?.name || path.value.split('/').pop() || 'download')
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (cause) {
    error.value = cause?.message || '下载失败'
  }
}

watch(path, load, { immediate: true })

// An object URL holds its blob alive until revoked; closing enough previews
// without this is a straightforward way to leak a session's worth of images.
onBeforeUnmount(release)
</script>

<style>
.dac-preview {
  position: absolute;
  inset: 12px;
  z-index: 2;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--dac-border-color, #e2e8f0);
  border-radius: var(--dac-input-radius, 10px);
  background: var(--dac-preview-bg, #ffffff);
  box-shadow: 0 16px 48px rgb(15 23 42 / 22%);
  overflow: hidden;
}
.dac-preview-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--dac-border-color, #e2e8f0);
  font-size: 13px;
}
.dac-preview-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dac-preview-download {
  border: none;
  background: transparent;
  color: var(--dac-primary, #10b981);
  font: inherit;
  cursor: pointer;
}
.dac-preview-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.dac-preview-close {
  border: none;
  background: transparent;
  color: inherit;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
}
.dac-preview-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px;
}
.dac-preview-image { max-width: 100%; }
.dac-preview-frame {
  width: 100%;
  height: 100%;
  min-height: 320px;
  border: none;
  background: #ffffff;
}
.dac-preview-error { color: var(--dac-error-color, #b91c1c); }
.dac-preview-loading { color: var(--dac-text-muted, #64748b); }
</style>
