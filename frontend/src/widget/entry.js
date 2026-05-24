import { createApp, reactive } from 'vue'
import { ElScrollbar } from 'element-plus'
import OpenDataWorksWidget from './OpenDataWorksWidget.vue'
import { parseWidgetConfig, resolveCurrentScript } from './config'
import { WIDGET_STYLES } from './styles'

const GLOBAL_NAME = 'OpenDataWorksWidget'
const MIN_INLINE_PARENT_HEIGHT = 320
const INLINE_VIEWPORT_BOTTOM_GAP = 8

/** Registry of all live widget instances keyed by instanceId */
const _instances = new Map()
let _instanceSeq = 0

const resolveWidgetStylesheetUrl = (script) => {
  const explicitUrl = String(script?.dataset?.stylesheetUrl || '').trim()
  if (explicitUrl) return explicitUrl
  const scriptSrc = String(script?.src || '').trim()
  if (!scriptSrc) return ''
  return new URL('style.css', scriptSrc).href
}

const appendBundledStylesheet = (shadow, script) => {
  let href = resolveWidgetStylesheetUrl(script)
  // If no script element was provided (programmatic config), try to find the bundle
  // script tag in the DOM to resolve the stylesheet URL from its src.
  if (!href) {
    const fallbackScript = document.querySelector('script[src*="opendataworks-widget"]')
    href = resolveWidgetStylesheetUrl(fallbackScript)
  }
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

/**
 * Create and mount a widget instance.
 *
 * @param {HTMLScriptElement|Object} scriptOrConfig
 *   - An `<script>` element whose `data-*` attributes describe the widget, OR
 *   - A plain config object with keys matching `parseWidgetConfig` output
 *     (agentId, displayMode, containerId, projectName, projectColor, websiteId, apiBaseUrl, …).
 * @returns {Object} controller – the public API for this widget instance.
 */
export function installWidget(scriptOrConfig = resolveCurrentScript()) {
  if (typeof document === 'undefined') return null

  // Accept a plain config object for programmatic multi-instance usage.
  let config
  let scriptEl = null
  if (scriptOrConfig && typeof scriptOrConfig === 'object' && !(scriptOrConfig instanceof HTMLElement)) {
    config = { ...scriptOrConfig }
    // Normalise essential defaults
    config.displayMode = config.displayMode || 'floating'
    config.position = config.position || 'bottom-right'
    config.projectName = config.projectName || '智能问数'
    config.projectColor = config.projectColor || '#4A90A4'
    config.headers = config.headers || { 'X-ODW-Client': 'widget' }
    config.agentId = config.agentId || ''
    config.apiBaseUrl = config.apiBaseUrl ?? ''
  } else {
    scriptEl = scriptOrConfig
    config = parseWidgetConfig(scriptEl)
  }

  const instanceId = `odw_${++_instanceSeq}`
  const mountParent = resolveMountParent(config)
  const host = document.createElement('div')
  host.setAttribute('data-odw-widget-root', '')
  host.setAttribute('data-odw-widget-mode', config.displayMode)
  host.setAttribute('data-odw-instance-id', instanceId)
  const shadow = host.attachShadow({ mode: 'open' })
  appendBundledStylesheet(shadow, scriptEl)
  const style = document.createElement('style')
  style.textContent = WIDGET_STYLES
  const mountPoint = document.createElement('div')
  mountPoint.setAttribute('data-odw-widget-mount', '')
  shadow.appendChild(style)
  shadow.appendChild(mountPoint)
  mountParent.appendChild(host)
  const disposeHostSizing = bindInlineHostSizing(host, mountParent, config)

  const parentWidth = mountParent?.getBoundingClientRect?.()?.width || 0
  const state = reactive({
    isOpen: config.displayMode === 'inline',
    historyOpen: config.displayMode === 'inline' && parentWidth >= 600,
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
    instanceId,
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
      _instances.delete(instanceId)
      if (window[GLOBAL_NAME]?._lastController === controller) {
        window[GLOBAL_NAME]._lastController = null
      }
    }
  }

  _instances.set(instanceId, controller)

  // Expose as convenient last-installed controller (backward compat)
  if (!window[GLOBAL_NAME] || typeof window[GLOBAL_NAME].installWidget !== 'function') {
    // First load: create the global API surface
    window[GLOBAL_NAME] = {
      /** Create a new widget instance programmatically with a config object. */
      installWidget,
      /** Destroy all widget instances. */
      destroyAll() {
        for (const [, ctrl] of _instances) ctrl.destroy()
        _instances.clear()
      },
      /** Get a specific instance by ID. */
      getInstance(id) { return _instances.get(id) || null },
      /** Get all live instances. */
      getInstances() { return [..._instances.values()] },
      _lastController: controller,
      // Proxy convenience methods to the last-installed controller
      open() { this._lastController?.open() },
      close() { this._lastController?.close() },
      toggle() { this._lastController?.toggle() },
      isOpen() { return this._lastController?.isOpen() ?? false },
      sendMessage(text) { this._lastController?.sendMessage(text) },
      cancel() { this._lastController?.cancel() },
      openHistory() { this._lastController?.openHistory() },
      newConversation() { this._lastController?.newConversation() },
      selectConversation(topicId) { this._lastController?.selectConversation(topicId) },
      deleteConversation(topicId) { this._lastController?.deleteConversation(topicId) },
      on(eventName, handler) { return this._lastController?.on(eventName, handler) ?? (() => {}) },
      destroy() { this._lastController?.destroy() }
    }
  } else {
    // Subsequent loads: just update the last-controller reference
    window[GLOBAL_NAME]._lastController = controller
  }

  return controller
}

// Auto-install from the loading script tag (if it has widget data attributes).
// For dynamic/programmatic usage, call `window.OpenDataWorksWidget.installWidget(config)`.
if (typeof window !== 'undefined' && typeof document !== 'undefined') {
  const bootScript = resolveCurrentScript()
  // Only auto-install if we found a real script element with meaningful data attributes
  // (at minimum data-website-id or data-agent-id), AND it hasn't been processed yet.
  // A bare <script src="..."> without data attributes is treated as a bundle loader only.
  const hasWidgetAttrs = bootScript && (
    bootScript.dataset?.websiteId || bootScript.dataset?.agentId
  )
  if (bootScript && hasWidgetAttrs && !bootScript._odwProcessed) {
    bootScript._odwProcessed = true
    installWidget(bootScript)
  } else if (!window[GLOBAL_NAME]) {
    // Even without auto-mount, expose the global API surface so programmatic
    // installWidget() calls work immediately.
    window[GLOBAL_NAME] = {
      installWidget,
      destroyAll() {
        for (const [, ctrl] of _instances) ctrl.destroy()
        _instances.clear()
      },
      getInstance(id) { return _instances.get(id) || null },
      getInstances() { return [..._instances.values()] },
      _lastController: null,
      open() { this._lastController?.open() },
      close() { this._lastController?.close() },
      toggle() { this._lastController?.toggle() },
      isOpen() { return this._lastController?.isOpen() ?? false },
      sendMessage(text) { this._lastController?.sendMessage(text) },
      cancel() { this._lastController?.cancel() },
      openHistory() { this._lastController?.openHistory() },
      newConversation() { this._lastController?.newConversation() },
      selectConversation(topicId) { this._lastController?.selectConversation(topicId) },
      deleteConversation(topicId) { this._lastController?.deleteConversation(topicId) },
      on(eventName, handler) { return this._lastController?.on(eventName, handler) ?? (() => {}) },
      destroy() { this._lastController?.destroy() }
    }
  }
}

