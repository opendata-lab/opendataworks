<template>
  <div class="odw-widget" :style="themeStyle" :class="[positionClass, modeClass]">
    <button v-if="!isInline && !state.isOpen" class="odw-launcher" type="button" :aria-label="`打开 ${config.projectName}`" @click="open">
      <svg class="odw-launcher__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      </svg>
    </button>

    <section v-else class="odw-panel" aria-label="OpenDataWorks intelligent query widget">
      <header v-if="!isInline" class="odw-panel__header">
        <button class="odw-icon-button odw-history-toggle" type="button" aria-label="历史会话" title="历史会话" @click="openHistory">
          <Menu class="odw-icon-svg" aria-hidden="true" />
        </button>
        <div class="odw-panel__heading">
          <div class="odw-panel__title">{{ config.projectName }}</div>
        </div>
        <div class="odw-panel__actions">
          <button class="odw-icon-button" type="button" aria-label="新建会话" title="新建会话" :disabled="state.isBusy" @click="newConversation">
            <Plus class="odw-icon-svg" aria-hidden="true" />
          </button>
          <button v-if="!isInline" class="odw-icon-button odw-close-button" type="button" aria-label="关闭小窗" title="关闭小窗" @click="close">
            <Close class="odw-icon-svg" aria-hidden="true" />
          </button>
        </div>
      </header>

      <WidgetChat
        :config="config"
        :state="state"
        @consumed-outbound="state.outboundMessage = ''"
        @event="emitWidgetEvent"
      />
    </section>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Close, Menu, Plus } from '@element-plus/icons-vue'
import WidgetChat from './WidgetChat.vue'

const props = defineProps({
  config: {
    type: Object,
    required: true
  },
  state: {
    type: Object,
    required: true
  },
  emit: {
    type: Function,
    default: () => {}
  }
})

const themeStyle = computed(() => ({
  '--odw-widget-color': props.config.projectColor || '#4A90A4'
}))

const positionClass = computed(() => `is-${props.config.position || 'bottom-right'}`)
const modeClass = computed(() => `is-${props.config.displayMode || 'floating'}`)
const isInline = computed(() => props.config.displayMode === 'inline')

const open = () => {
  props.state.isOpen = true
  props.emit('open')
}

const close = () => {
  props.state.isOpen = false
  props.emit('close')
}

const openHistory = () => {
  props.state.historyOpen = true
  props.emit('history:open')
}

const newConversation = () => {
  props.state.newConversationSignal += 1
  props.emit('conversation:new')
}

const emitWidgetEvent = ({ name, payload }) => {
  props.emit(name, payload)
}
</script>
