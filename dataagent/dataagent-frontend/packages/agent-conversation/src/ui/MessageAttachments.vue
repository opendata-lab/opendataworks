<template>
  <div class="dac-attachments" part="attachments">
    <!-- A card that can be previewed opens the viewer and offers download
         alongside it. Without readFile there is nothing to preview in place,
         so the whole card becomes the download link — the file is still
         reachable, just not without leaving the page. -->
    <template v-for="file in attachments" :key="file.relPath">
      <div
        v-if="canPreview(file)"
        class="dac-attachment is-clickable"
        part="attachment"
        :title="'预览 ' + file.name"
        @click="$emit('preview', file)"
      >
        <svg class="dac-attachment-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><path d="M14 2v6h6" /></svg>
        <span class="dac-attachment-name" part="attachment-name">{{ file.name }}</span>
        <span v-if="file.size != null" class="dac-attachment-size" part="attachment-size">{{ formatBytes(file.size) }}</span>
        <span class="dac-attachment-actions">
          <button
            type="button"
            class="dac-attachment-btn"
            data-action="preview"
            title="预览"
            aria-label="预览"
            @click.stop="$emit('preview', file)"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7Z" /><circle cx="12" cy="12" r="3" /></svg>
          </button>
          <a
            class="dac-attachment-btn"
            :href="fileUrl(file.relPath)"
            target="_blank"
            rel="noreferrer"
            :download="file.name"
            title="下载"
            aria-label="下载"
            @click.stop
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M12 3v12m0 0 4-4m-4 4-4-4M5 21h14" /></svg>
          </a>
        </span>
      </div>

      <a
        v-else
        class="dac-attachment"
        part="attachment"
        :href="fileUrl(file.relPath)"
        target="_blank"
        rel="noreferrer"
        :title="'下载 ' + file.name"
      >
        <svg class="dac-attachment-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><path d="M14 2v6h6" /></svg>
        <span class="dac-attachment-name" part="attachment-name">{{ file.name }}</span>
        <span v-if="file.size != null" class="dac-attachment-size" part="attachment-size">{{ formatBytes(file.size) }}</span>
      </a>
    </template>
  </div>
</template>

<script setup>
import { previewKindFor } from '../core/previewKind.js'

const props = defineProps({
  attachments: { type: Array, default: () => [] },
  fileUrl: { type: Function, default: (p) => p },
  /** Preview is a host capability: it needs the transport's readFile. */
  canPreviewFiles: { type: Boolean, default: false },
})

defineEmits(['preview'])

const canPreview = (file) =>
  props.canPreviewFiles && Boolean(previewKindFor(file?.relPath || file?.name))

const formatBytes = (size) => {
  const n = Number(size) || 0
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}
</script>

<style scoped>
.dac-attachments { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.dac-attachment {
  display: inline-flex; align-items: center; gap: 8px;
  max-width: 320px; padding: 8px 12px;
  border: 1px solid #E5EAF1; border-radius: 10px;
  background: #F8FAFC; color: #1F2937; font-size: 13px;
  text-decoration: none;
}
.dac-attachment.is-clickable { cursor: pointer; }
.dac-attachment:hover { background: #EEF2F7; border-color: #D4DCE6; }
.dac-attachment-icon { flex: none; color: #4F46E5; }
.dac-attachment-name {
  min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.dac-attachment-size { flex: none; font-size: 11px; color: #9AA4B2; }
.dac-attachment-actions { flex: none; display: flex; align-items: center; gap: 2px; }
.dac-attachment-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border: none; border-radius: 6px;
  background: transparent; color: #6B7280; cursor: pointer;
  text-decoration: none;
}
.dac-attachment-btn:hover { background: #E6EAF0; color: #111827; }
</style>
