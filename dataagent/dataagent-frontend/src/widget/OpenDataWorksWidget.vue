<template>
  <div
    ref="rootEl"
    class="odw-widget"
    :style="[themeStyle, rootStyle]"
    :class="[positionClass, modeClass, state.historyOpen ? 'is-history-open' : '', isDragged ? 'is-dragged' : '', isInteracting ? 'is-interacting' : '']"
  >
    <button v-if="!isInline && !state.isOpen" class="odw-launcher" type="button" :aria-label="`打开 ${config.projectName}`" @pointerdown="startLauncherDrag" @click.stop.prevent="openIfNotDragged">
      <svg class="odw-launcher__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      </svg>
    </button>

    <section v-else ref="panelEl" class="odw-panel" :style="panelStyle" aria-label="OpenDataWorks intelligent query widget">
      <header v-if="!isInline" class="odw-panel__header" @pointerdown="startDrag">
        <div class="odw-panel__heading">
          <div class="odw-panel__title">{{ config.projectName }}</div>
        </div>
        <div class="odw-panel__actions">
          <button class="odw-icon-button" type="button" aria-label="新建会话" title="新建会话" @click="newConversation">
            <Plus class="odw-icon-svg" aria-hidden="true" />
          </button>
          <button class="odw-icon-button odw-history-toggle" type="button" aria-label="历史会话" title="历史会话" @click="toggleHistory">
            <svg class="odw-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M3 3v5h5" />
              <path d="M3.05 13A9 9 0 1 0 6 5.3L3 8" />
              <path d="M12 7v5l4 2" />
            </svg>
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

      <div
        v-if="!isInline"
        class="odw-resize-handle"
        aria-hidden="true"
        title="拖动调整大小"
        @pointerdown.stop="startResize"
      >
        <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" aria-hidden="true">
          <path d="M11 4 4 11M11 8l-3 3" />
        </svg>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Close, Plus } from '@element-plus/icons-vue'
import WidgetChat from './WidgetChat.vue'
import { useWidgetGeometry } from './useWidgetGeometry'

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

const rootEl = ref(null)
const panelEl = ref(null)

const {
  rootStyle,
  panelStyle,
  isDragged,
  isInteracting,
  startDrag,
  startResize,
  startLauncherDrag,
  isLauncherDragMoved,
  bind,
  unbind
} = useWidgetGeometry({ rootEl, panelEl, config: props.config, state: props.state })

onMounted(() => {
  bind()
  if (isInline.value) {
    props.state.track?.('widget_open')
  }
})
onBeforeUnmount(unbind)

const open = () => {
  props.state.isOpen = true
  props.state.track?.('widget_open')
  props.emit('open')
}

const openIfNotDragged = () => {
  if (isLauncherDragMoved()) return
  open()
}

const close = () => {
  props.state.isOpen = false
  props.state.track?.('widget_close')
  props.emit('close')
}

const toggleHistory = () => {
  props.state.historyOpen = !props.state.historyOpen
  if (props.state.historyOpen) {
    props.state.track?.('history_open')
    props.emit('history:open')
  } else {
    props.state.track?.('history_close')
    props.emit('history:close')
  }
}

const newConversation = () => {
  props.state.newConversationSignal += 1
  props.state.track?.('conversation_new')
  props.emit('conversation:new')
}

const emitWidgetEvent = ({ name, payload }) => {
  props.emit(name, payload)
}
</script>
