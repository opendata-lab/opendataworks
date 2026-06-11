const DEFAULT_PROJECT_NAME = '智能问数'
const DEFAULT_PROJECT_COLOR = '#4A90A4'
const DEFAULT_POSITION = 'bottom-right'
const DEFAULT_DISPLAY_MODE = 'floating'

const trimTrailingSlash = (value) => String(value || '').replace(/\/+$/, '')

const randomId = () => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '').slice(0, 24)
  }
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 14)}`
}

const visitorStorageKey = (websiteId) => `odw_widget_visitor_id_${websiteId || 'default'}`

export function resolveCurrentScript() {
  if (typeof document === 'undefined') return null
  return document.currentScript || document.querySelector('script[data-website-id][src*="opendataworks-widget"]')
}

export function resolveVisitorId(websiteId) {
  const key = visitorStorageKey(websiteId)
  try {
    const existing = window.localStorage.getItem(key)
    if (existing) return existing
    const created = `visitor_${randomId()}`
    window.localStorage.setItem(key, created)
    return created
  } catch (_error) {
    return `visitor_${randomId()}`
  }
}

export function parseWidgetConfig(script = resolveCurrentScript()) {
  const dataset = script?.dataset || {}
  const websiteId = String(dataset.websiteId || '').trim()
  const userId = String(dataset.userId || '').trim()
  const agentId = String(dataset.agentId || '').trim()
  const visitorId = userId ? '' : resolveVisitorId(websiteId)
  const apiBaseUrl = trimTrailingSlash(dataset.apiBaseUrl || (script?.src ? new URL(script.src, window.location.href).origin : ''))
  const projectName = String(dataset.projectName || DEFAULT_PROJECT_NAME).trim() || DEFAULT_PROJECT_NAME
  const projectColor = String(dataset.projectColor || DEFAULT_PROJECT_COLOR).trim() || DEFAULT_PROJECT_COLOR
  const position = String(dataset.position || DEFAULT_POSITION).trim() || DEFAULT_POSITION
  const rawDisplayMode = String(dataset.displayMode || DEFAULT_DISPLAY_MODE).trim().toLowerCase()
  const displayMode = rawDisplayMode === 'inline' ? 'inline' : 'floating'
  const containerId = String(dataset.containerId || '').trim()
  const headers = {
    'X-ODW-Client': 'widget',
    'X-ODW-Website-Id': websiteId
  }
  if (userId) {
    headers['X-ODW-User-Id'] = userId
  } else {
    headers['X-ODW-Visitor-Id'] = visitorId
  }

  return {
    websiteId,
    userId,
    agentId,
    visitorId,
    projectName,
    projectColor,
    apiBaseUrl,
    position,
    displayMode,
    containerId,
    headers
  }
}
