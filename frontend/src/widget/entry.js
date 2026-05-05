import { createApp, reactive } from 'vue'
import { ElScrollbar } from 'element-plus'
import OpenDataWorksWidget from './OpenDataWorksWidget.vue'
import { parseWidgetConfig, resolveCurrentScript } from './config'
import { WIDGET_STYLES } from './styles'

const GLOBAL_NAME = 'OpenDataWorksWidget'
const MIN_INLINE_PARENT_HEIGHT = 320
const INLINE_VIEWPORT_BOTTOM_GAP = 8

const resolveWidgetStylesheetUrl = (script) => {
  const explicitUrl = String(script?.dataset?.stylesheetUrl || '').trim()
  if (explicitUrl) return explicitUrl
  const scriptSrc = String(script?.src || '').trim()
  if (!scriptSrc) return ''
  return new URL('style.css', scriptSrc).href
}

const appendBundledStylesheet = (shadow, script) => {
  const href = resolveWidgetStylesheetUrl(script)
  if (!href) return
  const link = document.createElement('link')
  link.rel = 'stylesheet'
  link.href = href
  shadow.appendChild(link)
}

const resolveMountParent = (config) => {
  if (config.displayMode !== 'inline') return document.body
  if (!config.containerId) return document.body
  const target = document.getElementById(config.containerId)
  if (!target) {
    console.warn(`[OpenDataWorksWidget] container #${config.containerId} not found; falling back to body`)
    return document.body
  }
  return target
}

const applyHostSizing = (host, mountParent, config) => {
  if (config.displayMode !== 'inline') return

  host.style.display = 'block'
  host.style.width = '100%'
  host.style.minHeight = '0'

  const parentRect = mountParent?.getBoundingClientRect?.()
  const parentTop = Math.max(0, Math.round(Number(parentRect?.top || 0)))
  const viewportHeight = Number(window.visualViewport?.height || window.innerHeight || 0)
  const remainingViewportHeight = Math.floor(viewportHeight - parentTop - INLINE_VIEWPORT_BOTTOM_GAP)
  const parentHeight = Number(parentRect?.height || 0)
  const fallbackHeight = parentHeight >= MIN_INLINE_PARENT_HEIGHT ? parentHeight : MIN_INLINE_PARENT_HEIGHT
  const inlineHeight = remainingViewportHeight >= MIN_INLINE_PARENT_HEIGHT ? remainingViewportHeight : fallbackHeight
  host.style.height = `${inlineHeight}px`
}

const bindInlineHostSizing = (host, mountParent, config) => {
  if (config.displayMode !== 'inline') return () => {}

  const sync = () => applyHostSizing(host, mountParent, config)
  const rafIds = []
  const scheduleSync = () => {
    if (typeof window.requestAnimationFrame !== 'function') {
      sync()
      return
    }
    const id = window.requestAnimationFrame(sync)
    rafIds.push(id)
  }

  sync()
  scheduleSync()
  scheduleSync()
  window.addEventListener('resize', sync)
  window.visualViewport?.addEventListener?.('resize', sync)

  return () => {
    window.removeEventListener('resize', sync)
    window.visualViewport?.removeEventListener?.('resize', sync)
    rafIds.forEach((id) => window.cancelAnimationFrame?.(id))
  }
}

export function installWidget(script = resolveCurrentScript()) {
  if (typeof document === 'undefined') return null

  window[GLOBAL_NAME]?.destroy?.()

  const config = parseWidgetConfig(script)
  const mountParent = resolveMountParent(config)
  const host = document.createElement('div')
  host.setAttribute('data-odw-widget-root', '')
  host.setAttribute('data-odw-widget-mode', config.displayMode)
  const shadow = host.attachShadow({ mode: 'open' })
  appendBundledStylesheet(shadow, script)
  const style = document.createElement('style')
  style.textContent = WIDGET_STYLES
  const mountPoint = document.createElement('div')
  mountPoint.setAttribute('data-odw-widget-mount', '')
  shadow.appendChild(style)
  shadow.appendChild(mountPoint)
  mountParent.appendChild(host)
  const disposeHostSizing = bindInlineHostSizing(host, mountParent, config)

  const state = reactive({
    isOpen: config.displayMode === 'inline',
    historyOpen: false,
    isBusy: false,
    outboundMessage: '',
    cancelSignal: 0,
    newConversationSignal: 0,
    selectConversationSignal: 0,
    deleteConversationSignal: 0,
    requestedTopicId: '',
    deleteRequestedTopicId: ''
  })
  const listeners = new Map()

  const emit = (eventName, payload) => {
    const handlers = listeners.get(eventName) || []
    handlers.forEach((handler) => {
      try {
        handler(payload)
      } catch (error) {
        console.warn('[OpenDataWorksWidget] listener failed', error)
      }
    })
  }

  const app = createApp(OpenDataWorksWidget, { config, state, emit })
  app.component(ElScrollbar.name, ElScrollbar)
  app.mount(mountPoint)

  const controller = {
    open() {
      state.isOpen = true
      emit('open')
    },
    close() {
      state.isOpen = false
      emit('close')
    },
    toggle() {
      state.isOpen ? this.close() : this.open()
    },
    isOpen() {
      return Boolean(state.isOpen)
    },
    sendMessage(text) {
      state.isOpen = true
      state.outboundMessage = String(text || '')
    },
    cancel() {
      state.cancelSignal += 1
      emit('cancel')
    },
    openHistory() {
      state.isOpen = true
      state.historyOpen = true
      emit('history:open')
    },
    newConversation() {
      state.isOpen = true
      state.newConversationSignal += 1
      emit('conversation:new')
    },
    selectConversation(topicId) {
      state.isOpen = true
      state.requestedTopicId = String(topicId || '')
      state.selectConversationSignal += 1
      emit('conversation:select', { topicId: state.requestedTopicId })
    },
    deleteConversation(topicId) {
      state.isOpen = true
      state.deleteRequestedTopicId = String(topicId || '')
      state.deleteConversationSignal += 1
      emit('conversation:delete', { topicId: state.deleteRequestedTopicId })
    },
    on(eventName, handler) {
      const key = String(eventName || '')
      if (!key || typeof handler !== 'function') return () => {}
      const handlers = listeners.get(key) || []
      handlers.push(handler)
      listeners.set(key, handlers)
      return () => {
        listeners.set(key, (listeners.get(key) || []).filter((item) => item !== handler))
      }
    },
    destroy() {
      disposeHostSizing()
      app.unmount()
      host.remove()
      listeners.clear()
      if (window[GLOBAL_NAME] === controller) {
        delete window[GLOBAL_NAME]
      }
    }
  }

  window[GLOBAL_NAME] = controller
  return controller
}

if (typeof window !== 'undefined' && typeof document !== 'undefined') {
  installWidget()
}
