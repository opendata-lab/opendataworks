<template>
  <div class="dac-root" part="root">
    <MessageList
      ref="messageListRef"
      :messages="conversation.messages.value"
      :file-url="fileUrl"
      :disabled="disabled"
      :can-rate="canRate"
      :can-preview-files="canPreviewFiles"
      :activity-label="activityLabel"
      @decide="onDecide"
      @answer="onAnswer"
      @retry="conversation.retry"
      @feedback="({ message, value }) => conversation.submitFeedback(message, value)"
      @preview="(file) => { previewFile = file }"
    >
      <template #empty><slot name="empty">暂无消息</slot></template>
    </MessageList>

    <AttachmentPreview
      v-if="previewFile && canPreviewFiles"
      :file="previewFile"
      :read-file="endpointApi.transport.value.readFile"
      :download-url="fileUrl(previewFile.relPath)"
      @close="previewFile = null"
    />

    <!-- Openers the host supplies, offered only while there is nothing to read.
         Once a conversation exists they would compete with it for attention. -->
    <div v-if="suggestions.length && !conversation.messages.value.length" class="dac-suggestions" part="suggestions">
      <button
        v-for="text in suggestions"
        :key="text"
        type="button"
        class="dac-suggestion"
        part="suggestion"
        :disabled="disabled"
        @click="send(text)"
      >{{ text }}</button>
    </div>

    <Composer
      ref="composerRef"
      :model-value="conversation.draft.value"
      :placeholder="placeholder"
      :disabled="disabled || !endpointApi.ready.value && !endpointResolver"
      :active="conversation.isActive.value"
      :run-detail="conversation.run.value?.detail || ''"
      :config="composerConfig"
      @update:modelValue="onDraft"
      @send="() => send()"
      @cancel="conversation.cancel"
      @settings-change="(value) => { settings = value }"
    >
      <template #composer-overlay><slot name="composer-overlay" /></template>
      <template #composer-actions><slot name="composer-actions" /></template>
      <template #composer-toolbar><slot name="composer-toolbar" /></template>
    </Composer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, provide, ref, toRef, useHost, watch } from 'vue'
import MessageList from './MessageList.vue'
import Composer from './Composer.vue'
import AttachmentPreview from './AttachmentPreview.vue'
import { useEndpoint } from '../core/useEndpoint.js'
import { useConversation } from '../core/useConversation.js'

const props = defineProps({
  endpoint: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  active: { type: Boolean, default: true },
  disabled: { type: Boolean, default: false },
  // Functions and objects arrive as JS properties, never as attributes.
  endpointResolver: { type: Function, default: null },
  transportFactory: { type: Function, default: null },
  composerConfig: { type: Object, default: () => ({}) },
  /** Shown while an open turn has produced nothing yet. */
  activityLabel: { type: String, default: '正在处理…' }
})

const suggestions = computed(() => props.composerConfig?.suggestions || [])

const composerRef = ref(null)
const messageListRef = ref(null)
const previewFile = ref(null)
const host = useHost()
let settings = {}

/**
 * Dispatch on the host element with bubbles + composed, which is what lets a
 * host listen from outside the shadow root. Vue's own emit produces a
 * non-bubbling event that never escapes it.
 */
const emit = ({ name, detail }) => {
  host?.dispatchEvent(new CustomEvent(`dataagent-${name}`, {
    detail: detail ?? {},
    bubbles: true,
    composed: true
  }))
}

const endpointApi = useEndpoint({
  endpoint: toRef(props, 'endpoint'),
  endpointResolver: toRef(props, 'endpointResolver'),
  transportFactory: toRef(props, 'transportFactory'),
  onReset: (reason) => {
    previewFile.value = null
    conversation.reset()
    if (reason === 'switch' && !endpointApi.ready.value) return
    if (props.active) return conversation.load()
  }
})

const conversation = useConversation({
  transport: endpointApi.transport,
  generation: endpointApi.generation,
  emit
})

// Without these the two waiting states have no exit: a run parked on
// waiting_permission renders a card nobody can answer, and stays parked.
const onDecide = ({ taskId, requestId, decision }) =>
  conversation.submitInteraction({
    taskId: taskId || conversation.run.value?.taskId,
    kind: 'permission',
    requestId,
    payload: { decision },
  })

const onAnswer = ({ taskId, requestId, answers }) =>
  conversation.submitInteraction({
    taskId: taskId || conversation.run.value?.taskId,
    kind: 'question',
    requestId,
    payload: { answers },
  })

const fileUrl = computed(() => (path) => endpointApi.transport.value?.fileUrl?.(path) ?? path)

/**
 * The transport, reachable from any block a message renders.
 *
 * Tool cards sit arbitrarily deep inside a message, so passing it down as props
 * would thread it through every renderer. Providing the ref rather than the
 * current value matters: switching conversations builds a new transport, and a
 * panel that captured the old one at setup would keep talking to the previous
 * conversation.
 */
provide('agentConversationTransport', endpointApi.transport)

// Capability-driven, not configuration-driven: the rating buttons exist only
// when the host's transport can store a rating.
const canRate = computed(() => typeof endpointApi.transport.value?.submitFeedback === 'function')
const canPreviewFiles = computed(() => typeof endpointApi.transport.value?.readFile === 'function')

const onDraft = (value) => {
  conversation.draft.value = value
  emit({ name: 'draft-change', detail: { value } })
}

/**
 * Send, creating the conversation first if the host deferred it.
 *
 * This is the only place endpointResolver is consulted, which is what keeps a
 * host from minting an empty conversation just because a user opened the page.
 */
async function send(content, options = {}) {
  if (!endpointApi.ready.value) {
    const address = await endpointApi.ensure()
    if (!address) return
  }
  // The composer's selection rides along with the message rather than being
  // pushed to the server separately, so what was sent and what the user could
  // see can never disagree.
  const attachments = composerRef.value?.getAttachments?.() || []
  const sent = await conversation.send(content, { settings, attachments, ...options })
  // Only on success: a send that failed leaves the files staged so retrying
  // does not mean picking every one of them again.
  if (sent) composerRef.value?.clearAttachments?.()
}

// Releasing the stream when the host parks the element keeps a hidden tab from
// holding a connection open; re-activating reloads to catch up on what it missed.
watch(() => props.active, (isActive) => {
  if (isActive) {
    if (endpointApi.ready.value) conversation.load()
  } else {
    conversation.stopStream()
  }
})

if (endpointApi.ready.value && props.active) conversation.load()

onBeforeUnmount(() => conversation.stopStream())

defineExpose({
  reload: () => endpointApi.reload(),
  sendMessage: (content, options) => send(content, options),
  cancel: () => conversation.cancel(),
  focus: () => composerRef.value?.focus(),
  focusMessage: (messageId) => messageListRef.value?.focusMessage(messageId),
  getValue: () => conversation.draft.value,
  setValue: (value) => onDraft(String(value ?? ''))
})
</script>

<style>
.dac-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0 14px 4px;
}
.dac-suggestion {
  padding: 6px 12px;
  border: 1px solid var(--dac-border-color, #e2e8f0);
  border-radius: 999px;
  background: var(--dac-assistant-bubble-bg, #f1f5f9);
  color: inherit;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.dac-suggestion:hover:not(:disabled) {
  border-color: var(--dac-primary, #10b981);
}
.dac-suggestion:disabled { cursor: not-allowed; opacity: 0.6; }

.dac-root {
  position: relative;
  z-index: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  font-family: var(--dac-font-family, Inter, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif);
  font-size: var(--dac-font-size, 14px);
  color: var(--dac-text-color, #0f172a);
  background: var(--dac-bg, transparent);
}
</style>
