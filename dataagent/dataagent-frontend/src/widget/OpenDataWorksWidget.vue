<template>
  <div
    ref="rootEl"
    class="odw-widget"
    :style="[themeStyle, rootStyle]"
    :class="[positionClass, modeClass, dockClass, state.historyOpen ? 'is-history-open' : '', isDragged ? 'is-dragged' : '', isInteracting ? 'is-interacting' : '']"
  >
    <button v-if="!isInline && !state.isOpen" class="odw-launcher" type="button" :aria-label="`打开 ${config.projectName}`" @pointerdown="startLauncherDrag" @click.stop.prevent="openIfNotDragged">
      <img class="odw-launcher__icon" :src="assistantIcon" alt="" aria-hidden="true">
    </button>

    <section v-else ref="panelEl" class="odw-panel" :style="panelStyle" aria-label="OpenDataWorks intelligent query widget">
      <header v-if="!isInline" class="odw-panel__header" @pointerdown="startDrag">
        <div class="odw-panel__heading">
          <div class="odw-panel__title">{{ config.projectName }}</div>
        </div>
        <div class="odw-panel__actions">
          <button v-if="!state.loginRequired" class="odw-icon-button" type="button" aria-label="新建会话" title="新建会话" @click="newConversation">
            <Plus class="odw-icon-svg" aria-hidden="true" />
          </button>
          <button v-if="!state.loginRequired" class="odw-icon-button odw-history-toggle" type="button" aria-label="历史会话" title="历史会话" @click="toggleHistory">
            <svg class="odw-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M3 3v5h5" />
              <path d="M3.05 13A9 9 0 1 0 6 5.3L3 8" />
              <path d="M12 7v5l4 2" />
            </svg>
          </button>
          <div ref="layoutMenuEl" class="odw-layout-control" @pointerdown.stop @keydown="onMenuKeydown" @focusout="onMenuFocusout">
            <button ref="layoutButton" class="odw-icon-button" type="button" aria-label="面板布局" title="面板布局" aria-haspopup="menu" :aria-expanded="layoutMenuOpen" @click="toggleLayoutMenu">
              <svg class="odw-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"/><path :d="state.panelMode === 'docked-left' ? 'M9 4v16' : 'M15 4v16'"/></svg>
            </button>
            <div v-if="layoutMenuOpen" class="odw-layout-menu" role="menu" aria-label="面板布局">
              <button v-for="option in panelOptions" :key="option.mode" type="button" role="menuitemradio" :aria-checked="(state.panelMode || 'floating') === option.mode" @click="selectPanelMode(option.mode)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"/><path :d="option.mode === 'docked-left' ? 'M9 4v16' : option.mode === 'docked-right' ? 'M15 4v16' : 'M13 12h9v9h-9z'"/></svg>
                <span>{{ option.label }}</span><span class="odw-layout-check" aria-hidden="true">{{ (state.panelMode || 'floating') === option.mode ? '✓' : '' }}</span>
              </button>
            </div>
          </div>
          <component
            :is="hasFullscreenCallback ? 'button' : 'a'"
            v-if="hasFullscreenAction"
            class="odw-icon-button odw-fullscreen-button"
            :type="hasFullscreenCallback ? 'button' : undefined"
            :href="hasFullscreenCallback ? undefined : config.fullscreenUrl"
            aria-label="全屏打开"
            title="全屏打开"
            @pointerdown.stop
            @click="openFullscreen"
          >
            <FullScreen class="odw-icon-svg" aria-hidden="true" />
          </component>
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
        v-if="!isInline && !isDocked"
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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Close, FullScreen, Plus } from '@element-plus/icons-vue'
import WidgetChat from './WidgetChat.vue'
import { useWidgetGeometry } from './useWidgetGeometry'
import assistantIcon from '../assets/ai-assistant.svg'

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
const isDocked = computed(() => !isInline.value && ['docked-left', 'docked-right'].includes(props.state.panelMode))
const dockClass = computed(() => isDocked.value && props.state.isOpen ? `is-${props.state.panelMode}` : '')
const layoutMenuOpen = ref(false)
const layoutMenuEl = ref(null)
const layoutButton = ref(null)
const panelOptions = [
  { mode: 'floating', label: '悬浮面板' },
  { mode: 'docked-left', label: '固定面板（左）' },
  { mode: 'docked-right', label: '固定面板（右）' }
]
const menuItems = () => [...(layoutMenuEl.value?.querySelectorAll('[role="menuitemradio"]') || [])]
const toggleLayoutMenu = async () => {
  layoutMenuOpen.value = !layoutMenuOpen.value
  if (layoutMenuOpen.value) {
    await nextTick()
    menuItems()[panelOptions.findIndex(option => option.mode === (props.state.panelMode || 'floating'))]?.focus()
  }
}
const selectPanelMode = (mode) => {
  props.state.panelMode = mode
  layoutMenuOpen.value = false
  layoutButton.value?.focus()
}
const onMenuKeydown = (event) => {
  if (event.key === 'Escape') {
    layoutMenuOpen.value = false
    layoutButton.value?.focus()
    event.preventDefault()
  } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    event.preventDefault()
    if (!layoutMenuOpen.value) { toggleLayoutMenu(); return }
    const items = menuItems()
    const index = items.indexOf(event.target)
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (index + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length
    items[next]?.focus()
  }
}
const onMenuFocusout = (event) => {
  if (!layoutMenuEl.value?.contains(event.relatedTarget)) layoutMenuOpen.value = false
}
const dismissLayoutMenu = (event) => {
  if (!event.composedPath().includes(layoutMenuEl.value)) layoutMenuOpen.value = false
}
watch(() => props.state.isOpen, value => { if (!value) layoutMenuOpen.value = false })
const hasFullscreenCallback = computed(() => typeof props.config.onFullscreen === 'function')
const hasFullscreenAction = computed(() => hasFullscreenCallback.value || Boolean(props.config.fullscreenUrl))

const openFullscreen = async () => {
  const context = {
    instanceId: props.state.instanceId,
    websiteId: props.config.websiteId || props.config.headers?.['X-ODW-Website-Id'] || '',
    agentId: props.config.agentId || '',
    topicId: props.state.topicId || null,
    displayMode: props.config.displayMode || 'floating'
  }
  props.emit('fullscreen', { ...context })
  if (!hasFullscreenCallback.value) return
  try {
    await props.config.onFullscreen(context)
  } catch (error) {
    props.emit('error', {
      code: 'FULLSCREEN_ACTION_FAILED',
      message: error?.message || '打开完整页面失败'
    })
  }
}

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
  document.addEventListener('pointerdown', dismissLayoutMenu)
  if (isInline.value && !props.state.loginRequired) {
    props.state.track?.('widget_open')
  }
})
onBeforeUnmount(() => {
  unbind()
  document.removeEventListener('pointerdown', dismissLayoutMenu)
})

const open = () => {
  props.state.isOpen = true
  if (!props.state.loginRequired) props.state.track?.('widget_open')
  props.emit('open')
}

const openIfNotDragged = () => {
  if (isLauncherDragMoved()) return
  open()
}

const close = () => {
  props.state.isOpen = false
  if (!props.state.loginRequired) props.state.track?.('widget_close')
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
