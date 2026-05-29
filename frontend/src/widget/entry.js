import { createApp, reactive } from 'vue'
import { ElScrollbar } from 'element-plus'
import OpenDataWorksWidget from './OpenDataWorksWidget.vue'
import { parseWidgetConfig, resolveCurrentScript, resolveVisitorId } from './config'
import { WIDGET_STYLES } from './styles'

const GLOBAL_NAME = 'OpenDataWorksWidget'
const MIN_INLINE_PARENT_HEIGHT = 320
const INLINE_VIEWPORT_BOTTOM_GAP = 8
const ASK_MODAL_STYLE_ID = 'odw-ask-modal-style'
const ASK_MODAL_ATTR = 'data-odw-ask-modal'

/** Registry of all live widget instances keyed by instanceId */
const _instances = new Map()
let _instanceSeq = 0
let _askRunSeq = 0
let _askTurns = []

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

const scheduleOutboundMessage = (state, text) => {
  const deliver = () => {
    state.outboundMessage = text
  }
  if (typeof window.requestAnimationFrame === 'function') {
    window.requestAnimationFrame(deliver)
    return
  }
  window.setTimeout(deliver, 0)
}

const escapeHtml = (value) => String(value || '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

const askDemoRows = [
  ['05-21', '31', '较前一日 +10，发布活动明显增加'],
  ['05-22', '28', '小幅回落，仍高于周均水平'],
  ['05-23', '36', '进入发布高峰，主要来自工作流批量上线'],
  ['05-24', '42', '样例周期最高值，建议关注失败率和回滚记录']
]

const askLayerRows = [
  ['ODS 原始层', '128', '保留业务系统原始结构，适合审计与追溯'],
  ['DWD 明细层', '86', '统一清洗口径，承载事实明细查询'],
  ['DWS 汇总层', '42', '面向主题聚合，提升报表查询速度'],
  ['ADS 应用层', '27', '服务看板、指标 API 和业务专题分析']
]

const isLayerAskQuestion = (question) => /表|数据层|分层|层|ODS|DWD|DWS|ADS/i.test(question)

const getAskSql = (question) => {
  if (isLayerAskQuestion(question)) {
    return `SELECT
  layer_name,
  COUNT(*) AS table_count
FROM metadata_table
GROUP BY layer_name
ORDER BY table_count DESC;`
  }

  return `SELECT
  DATE(publish_time) AS date,
  COUNT(*) AS publish_count
FROM workflow_publish_record
WHERE publish_time >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
GROUP BY DATE(publish_time)
ORDER BY date;`
}

const ensureAskModalStyle = () => {
  if (document.getElementById(ASK_MODAL_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = ASK_MODAL_STYLE_ID
  style.textContent = `
[${ASK_MODAL_ATTR}] {
  position: fixed;
  inset: 0;
  z-index: 2147483600;
  display: none;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.48);
  backdrop-filter: blur(4px);
  font-family: Inter, "PingFang SC", "Microsoft YaHei", Arial, sans-serif;
}
[${ASK_MODAL_ATTR}].active { display: flex; }
.odw-ask-card {
  width: min(980px, 94vw);
  height: min(760px, 92vh);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 24px 80px rgba(15, 23, 42, 0.24);
}
.odw-ask-header {
  min-height: 64px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  gap: 14px;
  border-bottom: 1px solid #e2e8f0;
}
.odw-ask-mark {
  width: 24px;
  height: 24px;
  border-radius: 7px;
  background: #10b981;
  transform: rotate(45deg);
}
.odw-ask-title {
  flex: 1;
  font-size: 20px;
  font-weight: 800;
  color: #0f172a;
}
.odw-ask-close {
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 50%;
  background: #f1f5f9;
  color: #475569;
  cursor: pointer;
  font-size: 22px;
  line-height: 1;
}
.odw-ask-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 22px 28px;
  background: #ffffff;
}
.odw-ask-thread {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.odw-ask-turn {
  display: flex;
}
.odw-ask-turn-user {
  justify-content: flex-end;
}
.odw-ask-turn-assistant {
  justify-content: flex-start;
}
.odw-ask-user-bubble {
  max-width: min(680px, 82%);
  border-radius: 16px 16px 4px 16px;
  padding: 11px 14px;
  background: #ecfdf5;
  color: #065f46;
  font-size: 15px;
  line-height: 1.6;
  border: 1px solid #a7f3d0;
}
.odw-ask-assistant-bubble {
  width: 100%;
}
.odw-ask-empty {
  color: #475569;
  font-size: 15px;
  line-height: 1.8;
}
.odw-ask-process {
  border-top: 1px solid #cbd5e1;
  border-bottom: 1px solid #cbd5e1;
  padding: 18px 0;
  color: #0f172a;
  font-size: 15px;
  line-height: 1.8;
}
.odw-ask-process h2 {
  margin: 0 0 10px;
  font-size: 20px;
}
.odw-ask-process ol {
  margin: 8px 0 16px 22px;
  padding: 0;
}
.odw-ask-link {
  color: #059669;
  font-weight: 800;
}
.odw-ask-thinking {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #475569;
}
.odw-ask-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #10b981;
  animation: odw-ask-pulse 1s ease-in-out infinite;
}
.odw-ask-sql {
  margin: 0;
  overflow-x: auto;
  font-family: "Fira Code", Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre;
  color: #334155;
}
.odw-ask-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 12px;
  font-size: 14px;
}
.odw-ask-table th,
.odw-ask-table td {
  border: 1px solid #cbd5e1;
  padding: 9px 10px;
  text-align: left;
  vertical-align: top;
}
.odw-ask-table th {
  background: #f8fafc;
  color: #0f172a;
  font-weight: 800;
}
.odw-ask-footer {
  padding: 14px 18px 18px;
  border-top: 1px solid #e2e8f0;
  background: #fff;
}
.odw-ask-follow-box {
  min-height: 118px;
  border: 1px solid #cbd5e1;
  border-radius: 12px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.odw-ask-input {
  width: 100%;
  min-height: 38px;
  resize: none;
  border: none;
  outline: none;
  font: inherit;
  font-size: 15px;
  color: #0f172a;
}
.odw-ask-input::placeholder { color: #94a3b8; }
.odw-ask-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.odw-ask-deep {
  height: 34px;
  border: 1px solid #cbd5e1;
  border-radius: 10px;
  padding: 0 14px;
  background: #fff;
  color: #0f172a;
  font-size: 13px;
  font-weight: 700;
}
.odw-ask-submit {
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 8px;
  background: #10b981;
  color: #fff;
  cursor: pointer;
  font-size: 18px;
}
@keyframes odw-ask-pulse {
  0%, 100% { opacity: 0.35; transform: scale(0.9); }
  50% { opacity: 1; transform: scale(1.1); }
}
@media (max-width: 640px) {
  .odw-ask-card {
    width: calc(100vw - 20px);
    height: calc(100vh - 20px);
    border-radius: 14px;
  }
  .odw-ask-body { padding: 16px; }
}
`
  document.head.appendChild(style)
}

const renderAskAnswer = (question) => {
  const isLayerQuestion = isLayerAskQuestion(question)
  const rows = (isLayerQuestion ? askLayerRows : askDemoRows).map((row) => `
    <tr>
      <td>${escapeHtml(row[0])}</td>
      <td><strong>${escapeHtml(row[1])}</strong></td>
      <td>${escapeHtml(row[2])}</td>
    </tr>
  `).join('')
  const title = isLayerQuestion ? '数据层表数量分析' : '工作流发布趋势分析'
  const intro = isLayerQuestion
    ? '我将问题识别为元数据统计类查询，优先使用 metadata_table 按数据层聚合。'
    : '我将问题识别为工作流发布趋势查询，优先使用 workflow_publish_record 按日期聚合。'
  const insight = isLayerQuestion
    ? 'DWD 明细层和 ODS 原始层表数量最多，说明当前平台样例数据更偏向资产接入和清洗建模阶段。'
    : '最近 7 天发布次数整体上升，05-24 达到 42 次，是当前样例数据中的最高值。'

  return `
    <div class="odw-ask-process">
      <h2>${title}</h2>
      <p>${intro} <span class="odw-ask-link">Analysis Overview</span>:</p>
      <ol>
        <li><strong>理解问题：</strong>抽取时间范围、统计口径和目标指标。</li>
        <li><strong>选择数据集：</strong>匹配工作流发布、任务实例或元数据表样例数据。</li>
        <li><strong>生成查询：</strong>构造聚合 SQL，并限制结果按业务维度排序。</li>
        <li><strong>组织结果：</strong>输出结论、SQL、趋势摘要和明细表。</li>
      </ol>
      <hr>
      <h2>生成 SQL</h2>
      <pre class="odw-ask-sql">${escapeHtml(getAskSql(question))}</pre>
      <h2>结果解读</h2>
      <p>${insight}</p>
      <table class="odw-ask-table">
        <thead>
          <tr>
            <th>${isLayerQuestion ? '数据层' : '日期'}</th>
            <th>${isLayerQuestion ? '表数量' : '发布次数'}</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>${rows}</tbody>
      </table>
    </div>
  `
}

const ensureAskModal = () => {
  ensureAskModalStyle()
  let modal = document.querySelector(`[${ASK_MODAL_ATTR}]`)
  if (modal) return modal

  modal = document.createElement('div')
  modal.setAttribute(ASK_MODAL_ATTR, '')
  modal.innerHTML = `
    <section class="odw-ask-card" role="dialog" aria-modal="true" aria-label="OpenDataWorks AI">
      <header class="odw-ask-header">
        <span class="odw-ask-mark" aria-hidden="true"></span>
        <div class="odw-ask-title">OpenDataWorks AI</div>
        <button class="odw-ask-close" type="button" aria-label="关闭">×</button>
      </header>
      <main class="odw-ask-body"></main>
      <footer class="odw-ask-footer">
        <div class="odw-ask-follow-box">
          <textarea class="odw-ask-input" placeholder="Ask a follow-up"></textarea>
          <div class="odw-ask-actions">
            <button class="odw-ask-deep" type="button">Deep thinking</button>
            <button class="odw-ask-submit" type="button" aria-label="发送追问">■</button>
          </div>
        </div>
      </footer>
    </section>
  `
  modal.querySelector('.odw-ask-close')?.addEventListener('click', () => modal.classList.remove('active'))
  modal.querySelector('.odw-ask-submit')?.addEventListener('click', () => {
    const input = modal.querySelector('.odw-ask-input')
    const text = input?.value?.trim() || ''
    if (!text) {
      input?.focus()
      return
    }
    input.value = ''
    openAskModal(text, { reset: false })
  })
  modal.querySelector('.odw-ask-input')?.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      modal.querySelector('.odw-ask-submit')?.click()
    }
  })
  document.body.appendChild(modal)
  return modal
}

const scrollAskModalToBottom = (modal) => {
  const body = modal.querySelector('.odw-ask-body')
  if (!body) return
  body.scrollTop = body.scrollHeight
}

const renderAskThread = (modal) => {
  const body = modal.querySelector('.odw-ask-body')
  if (!body) return
  if (!_askTurns.length) {
    body.innerHTML = '<div class="odw-ask-empty">在下方输入问题后按 Enter，查看本地演示对话过程。</div>'
    return
  }
  body.innerHTML = `
    <div class="odw-ask-thread">
      ${_askTurns.map((turn) => {
        if (turn.role === 'user') {
          return `
            <div class="odw-ask-turn odw-ask-turn-user">
              <div class="odw-ask-user-bubble">${escapeHtml(turn.text)}</div>
            </div>
          `
        }
        return `
          <div class="odw-ask-turn odw-ask-turn-assistant">
            <div class="odw-ask-assistant-bubble">${turn.html}</div>
          </div>
        `
      }).join('')}
    </div>
  `
  scrollAskModalToBottom(modal)
}

const renderAskThinking = (question, message) => `
  <div class="odw-ask-process">
    <div class="odw-ask-thinking">
      <span class="odw-ask-dot"></span>
      <span>${escapeHtml(message)}「${escapeHtml(question)}」...</span>
    </div>
  </div>
`

const openAskModal = (text = '', options = {}) => {
  if (typeof document === 'undefined') return
  const modal = ensureAskModal()
  const question = String(text || '').trim()
  modal.classList.add('active')

  if (!question) {
    renderAskThread(modal)
    modal.querySelector('.odw-ask-input')?.focus()
    return
  }

  if (options.reset !== false) {
    _askTurns = []
  }
  const runSeq = ++_askRunSeq
  _askTurns.push({ role: 'user', text: question })
  _askTurns.push({ role: 'assistant', html: renderAskThinking(question, '正在理解问题：') })
  renderAskThread(modal)

  window.setTimeout(() => {
    if (!modal.classList.contains('active') || runSeq !== _askRunSeq) return
    const lastAssistant = _askTurns[_askTurns.length - 1]
    if (lastAssistant?.role === 'assistant') {
      lastAssistant.html = `
        <div class="odw-ask-process">
          <div class="odw-ask-thinking">
            <span class="odw-ask-dot"></span>
            <span>已匹配到工作流发布记录、元数据表和任务实例等演示数据，正在生成 SQL...</span>
          </div>
        </div>
      `
      renderAskThread(modal)
    }
  }, 350)
  window.setTimeout(() => {
    if (!modal.classList.contains('active') || runSeq !== _askRunSeq) return
    const lastAssistant = _askTurns[_askTurns.length - 1]
    if (lastAssistant?.role === 'assistant') {
      lastAssistant.html = renderAskAnswer(question)
      renderAskThread(modal)
    }
    modal.querySelector('.odw-ask-input')?.focus()
  }, 700)
}

const closeAskModal = () => {
  document.querySelector(`[${ASK_MODAL_ATTR}]`)?.classList.remove('active')
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
    if (!config.headers) {
      const websiteId = String(config.websiteId || '').trim()
      if (websiteId) {
        const userId = String(config.userId || '').trim()
        config.headers = { 'X-ODW-Client': 'widget', 'X-ODW-Website-Id': websiteId }
        if (userId) {
          config.headers['X-ODW-User-Id'] = userId
        } else {
          config.headers['X-ODW-Visitor-Id'] = config.visitorId || resolveVisitorId(websiteId)
        }
      } else {
        config.headers = {}
      }
    }
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
      const message = String(text || '').trim()
      if (!message) return
      state.isOpen = true
      scheduleOutboundMessage(state, message)
    },
    ask(text) {
      openAskModal(text)
      emit('ask:open', { text: String(text || '').trim() })
    },
    openAskModal(text) {
      openAskModal(text)
      emit('ask:open', { text: String(text || '').trim() })
    },
    closeAskModal() {
      closeAskModal()
      emit('ask:close')
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
      ask(text) { this._lastController?.ask(text) ?? openAskModal(text) },
      openAskModal(text) { this._lastController?.openAskModal(text) ?? openAskModal(text) },
      closeAskModal() { this._lastController?.closeAskModal() ?? closeAskModal() },
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
      ask(text) { this._lastController?.ask(text) ?? openAskModal(text) },
      openAskModal(text) { this._lastController?.openAskModal(text) ?? openAskModal(text) },
      closeAskModal() { this._lastController?.closeAskModal() ?? closeAskModal() },
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
