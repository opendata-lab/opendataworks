import { computed, reactive } from 'vue'

const STORAGE_PREFIX = 'odw:widget:geom:'
const MIN_WIDTH = 360
const MIN_HEIGHT = 420
const VIEWPORT_MARGIN = 24

const toFiniteOrNull = (value) => {
  if (value == null || value === '') return null
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}

/** Keep an x/y position inside the viewport given the panel size. */
export const clampPosition = (left, top, width, height, vw, vh) => {
  const maxLeft = Math.max(0, vw - width)
  const maxTop = Math.max(0, vh - height)
  return {
    left: Math.min(Math.max(0, left), maxLeft),
    top: Math.min(Math.max(0, top), maxTop)
  }
}

/** Clamp a width/height to [min, viewport - margin]. */
export const clampSize = (width, height, vw, vh) => {
  const maxWidth = Math.max(MIN_WIDTH, vw - VIEWPORT_MARGIN)
  const maxHeight = Math.max(MIN_HEIGHT, vh - VIEWPORT_MARGIN)
  return {
    width: Math.min(Math.max(MIN_WIDTH, width), maxWidth),
    height: Math.min(Math.max(MIN_HEIGHT, height), maxHeight)
  }
}

export const geometryStorageKey = (websiteId) => `${STORAGE_PREFIX}${websiteId || 'default'}`

/** Read persisted geometry; returns null when missing or unusable. */
export const loadGeometry = (websiteId) => {
  try {
    const raw = window.localStorage.getItem(geometryStorageKey(websiteId))
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return null
    const geom = {
      left: toFiniteOrNull(parsed.left),
      top: toFiniteOrNull(parsed.top),
      width: toFiniteOrNull(parsed.width),
      height: toFiniteOrNull(parsed.height)
    }
    if (geom.left == null && geom.top == null && geom.width == null && geom.height == null) return null
    return geom
  } catch (_error) {
    return null
  }
}

export const saveGeometry = (websiteId, geom) => {
  try {
    window.localStorage.setItem(geometryStorageKey(websiteId), JSON.stringify(geom))
  } catch (_error) {
    // storage disabled / quota exceeded — non-fatal
  }
}

export const clearGeometry = (websiteId) => {
  try {
    window.localStorage.removeItem(geometryStorageKey(websiteId))
  } catch (_error) {
    // non-fatal
  }
}

/**
 * Drag-to-move + resize + localStorage persistence for the floating widget panel.
 * No-ops in inline mode. Consumed only by OpenDataWorksWidget.vue.
 */
export function useWidgetGeometry({ rootEl, panelEl, config, state }) {
  const isFloating = () => config.displayMode !== 'inline'
  const geom = reactive({ left: null, top: null, width: null, height: null, dragged: false, interacting: false })

  const viewport = () => ({
    vw: Number(window.innerWidth || 0),
    vh: Number(window.innerHeight || 0)
  })

  const isVisible = () => isFloating() && Boolean(state.isOpen)

  const rootStyle = computed(() => {
    if (!isVisible() || !geom.dragged || geom.left == null || geom.top == null) return {}
    return { left: `${geom.left}px`, top: `${geom.top}px` }
  })

  const panelStyle = computed(() => {
    if (!isFloating()) return {}
    const style = {}
    if (geom.width != null) style.width = `${geom.width}px`
    if (geom.height != null) style.height = `${geom.height}px`
    return style
  })

  const isDragged = computed(() => isVisible() && geom.dragged)
  const isInteracting = computed(() => geom.interacting)

  const persist = () => {
    saveGeometry(config.websiteId, {
      left: geom.left,
      top: geom.top,
      width: geom.width,
      height: geom.height
    })
  }

  // ── Drag ──────────────────────────────────────────────────────────────────
  let dragStart = null

  const onDragMove = (event) => {
    if (!dragStart) return
    const { vw, vh } = viewport()
    const next = clampPosition(
      dragStart.left + (event.clientX - dragStart.x),
      dragStart.top + (event.clientY - dragStart.y),
      dragStart.width,
      dragStart.height,
      vw,
      vh
    )
    geom.left = next.left
    geom.top = next.top
  }

  const endDrag = () => {
    window.removeEventListener('pointermove', onDragMove)
    window.removeEventListener('pointerup', endDrag)
    dragStart = null
    geom.interacting = false
    persist()
  }

  const startDrag = (event) => {
    if (!isFloating()) return
    if (event.target?.closest?.('button')) return
    const rect = panelEl.value?.getBoundingClientRect?.()
    if (!rect) return
    dragStart = { x: event.clientX, y: event.clientY, left: rect.left, top: rect.top, width: rect.width, height: rect.height }
    geom.dragged = true
    geom.interacting = true
    window.addEventListener('pointermove', onDragMove)
    window.addEventListener('pointerup', endDrag)
    event.preventDefault()
  }

  // ── Resize ────────────────────────────────────────────────────────────────
  let resizeStart = null

  const onResizeMove = (event) => {
    if (!resizeStart) return
    const { vw, vh } = viewport()
    const size = clampSize(
      resizeStart.width + (event.clientX - resizeStart.x),
      resizeStart.height + (event.clientY - resizeStart.y),
      vw,
      vh
    )
    geom.width = size.width
    geom.height = size.height
  }

  const endResize = () => {
    window.removeEventListener('pointermove', onResizeMove)
    window.removeEventListener('pointerup', endResize)
    resizeStart = null
    geom.interacting = false
    persist()
  }

  const startResize = (event) => {
    if (!isFloating()) return
    const rect = panelEl.value?.getBoundingClientRect?.()
    if (!rect) return
    resizeStart = { x: event.clientX, y: event.clientY, width: rect.width, height: rect.height }
    geom.interacting = true
    window.addEventListener('pointermove', onResizeMove)
    window.addEventListener('pointerup', endResize)
    event.preventDefault()
  }

  // ── Restore / lifecycle ─────────────────────────────────────────────────────
  const restoreGeometry = () => {
    if (!isFloating()) return
    const saved = loadGeometry(config.websiteId)
    if (!saved) return
    const { vw, vh } = viewport()
    if (saved.width != null || saved.height != null) {
      const fallback = panelEl.value?.getBoundingClientRect?.()
      const size = clampSize(
        saved.width ?? fallback?.width ?? MIN_WIDTH,
        saved.height ?? fallback?.height ?? MIN_HEIGHT,
        vw,
        vh
      )
      geom.width = size.width
      geom.height = size.height
    }
    if (saved.left != null && saved.top != null) {
      const pos = clampPosition(saved.left, saved.top, geom.width ?? MIN_WIDTH, geom.height ?? MIN_HEIGHT, vw, vh)
      geom.left = pos.left
      geom.top = pos.top
      geom.dragged = true
    }
  }

  const onViewportResize = () => {
    if (!isFloating()) return
    const { vw, vh } = viewport()
    if (geom.width != null && geom.height != null) {
      const size = clampSize(geom.width, geom.height, vw, vh)
      geom.width = size.width
      geom.height = size.height
    }
    if (geom.dragged && geom.left != null && geom.top != null) {
      const pos = clampPosition(geom.left, geom.top, geom.width ?? MIN_WIDTH, geom.height ?? MIN_HEIGHT, vw, vh)
      geom.left = pos.left
      geom.top = pos.top
    }
  }

  const resetGeometry = () => {
    geom.left = null
    geom.top = null
    geom.width = null
    geom.height = null
    geom.dragged = false
    geom.interacting = false
    clearGeometry(config.websiteId)
  }

  const bind = () => {
    restoreGeometry()
    window.addEventListener('resize', onViewportResize)
  }

  const unbind = () => {
    window.removeEventListener('resize', onViewportResize)
    window.removeEventListener('pointermove', onDragMove)
    window.removeEventListener('pointerup', endDrag)
    window.removeEventListener('pointermove', onResizeMove)
    window.removeEventListener('pointerup', endResize)
  }

  return { rootStyle, panelStyle, isDragged, isInteracting, startDrag, startResize, resetGeometry, bind, unbind }
}
