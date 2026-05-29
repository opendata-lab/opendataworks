const FLUSH_DEBOUNCE_MS = 800
const MAX_QUEUE_SIZE = 100

export function createWidgetTracker({ apiBaseUrl, headers }) {
  let queue = []
  let debounceTimer = null
  let destroyed = false

  const { eventApi } = _buildEventApi(apiBaseUrl, headers)

  const flush = (keepalive = false) => {
    if (!queue.length) return
    const batch = queue.splice(0)
    try {
      eventApi.recordEvents(batch, { keepalive }).catch(() => {})
    } catch (_error) {
      // best-effort: silently discard on any synchronous failure
    }
  }

  const scheduleFlush = () => {
    if (debounceTimer !== null) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(() => {
      debounceTimer = null
      flush(false)
    }, FLUSH_DEBOUNCE_MS)
  }

  const track = (event_type, payload = null) => {
    if (destroyed) return
    try {
      const event = { event_type, client_ts: new Date().toISOString() }
      if (payload && typeof payload === 'object') event.payload = payload
      queue.push(event)
      if (queue.length >= MAX_QUEUE_SIZE) {
        if (debounceTimer !== null) { clearTimeout(debounceTimer); debounceTimer = null }
        flush(false)
      } else {
        scheduleFlush()
      }
    } catch (_error) {
      // never throw to caller
    }
  }

  const destroy = () => {
    destroyed = true
    if (debounceTimer !== null) { clearTimeout(debounceTimer); debounceTimer = null }
    flush(true)
  }

  const _onVisibilityHidden = () => {
    if (document.visibilityState === 'hidden') flush(true)
  }
  const _onPageHide = () => flush(true)

  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', _onVisibilityHidden)
    window.addEventListener('pagehide', _onPageHide)
  }

  const unbindPageEvents = () => {
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', _onVisibilityHidden)
      window.removeEventListener('pagehide', _onPageHide)
    }
  }

  return {
    track,
    flush,
    destroy() {
      unbindPageEvents()
      destroy()
    }
  }
}

function _buildEventApi(baseURL, defaultHeaders) {
  const RUNTIME_PREFIX = '/api/v1/nl2sql'
  const base = String(baseURL || '').replace(/\/+$/, '')

  const eventApi = {
    async recordEvents(events, { keepalive = false } = {}) {
      return fetch(
        `${base}${RUNTIME_PREFIX}/widget-events`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...(defaultHeaders || {}) },
          body: JSON.stringify({ events }),
          keepalive
        }
      )
    }
  }
  return { eventApi }
}
