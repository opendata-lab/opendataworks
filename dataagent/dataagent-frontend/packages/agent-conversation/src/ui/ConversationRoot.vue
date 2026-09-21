<template>
  <div class="dac-root">
    <MessageList :messages="conversation.messages.value" :file-url="fileUrl">
      <template #empty><slot name="empty">暂无消息</slot></template>
    </MessageList>
    <Composer
      ref="composerRef"
      :model-value="conversation.draft.value"
      :placeholder="placeholder"
      :disabled="disabled || !endpointApi.ready.value && !endpointResolver"
      :active="conversation.isActive.value"
      :run-detail="conversation.run.value?.detail || ''"
      @update:modelValue="onDraft"
      @send="() => send()"
      @cancel="conversation.cancel"
    >
      <template #composer-overlay><slot name="composer-overlay" /></template>
      <template #composer-actions><slot name="composer-actions" /></template>
      <template #composer-toolbar><slot name="composer-toolbar" /></template>
    </Composer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, toRef, useHost, watch } from 'vue'
import MessageList from './MessageList.vue'
import Composer from './Composer.vue'
import { useEndpoint } from '../core/useEndpoint.js'
import { useConversation } from '../core/useConversation.js'

const props = defineProps({
  endpoint: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  active: { type: Boolean, default: true },
  disabled: { type: Boolean, default: false },
  // Functions and objects arrive as JS properties, never as attributes.
  endpointResolver: { type: Function, default: null },
  transportFactory: { type: Function, default: null }
})

const composerRef = ref(null)
const host = useHost()

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
    conversation.reset()
    if (reason === 'switch' && !endpointApi.ready.value) return
    if (props.active) conversation.load()
  }
})

const conversation = useConversation({
  transport: endpointApi.transport,
  generation: endpointApi.generation,
  emit
})

const fileUrl = computed(() => (path) => endpointApi.transport.value?.fileUrl?.(path) ?? path)

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
  await conversation.send(content, options)
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
  reload: () => { endpointApi.reload() },
  sendMessage: (content, options) => send(content, options),
  cancel: () => conversation.cancel(),
  focus: () => composerRef.value?.focus(),
  getValue: () => conversation.draft.value,
  setValue: (value) => onDraft(String(value ?? ''))
})
</script>

<style>
.dac-root {
  position: relative;
  z-index: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  font-family: Inter, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
  font-size: 14px;
  color: #0f172a;
}
</style>
