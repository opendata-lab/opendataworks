import { computed, reactive } from 'vue'

const STORAGE_PREFIX = 'odw:widget:geom:'
const LAUNCHER_STORAGE_PREFIX = 'odw:widget:launcher:'
const MIN_WIDTH = 360
const MIN_HEIGHT = 420
const VIEWPORT_MARGIN = 24
const LAUNCHER_SIZE = 56
const DRAG_THRESHOLD = 5

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
export const launcherStorageKey = (websiteId) => `${LAUNCHER_STORAGE_PREFIX}${websiteId || 'default'}`

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
  const launcherGeom = reactive({ left: null, top: null, dragged: false, interacting: false })

  const viewport = () => ({
    vw: Number(window.innerWidth || 0),
    vh: Number(window.innerHeight || 0)
  })

  const isVisible = () => isFloating() && Boolean(state.isOpen)

  const rootStyle = computed(() => {
    if (!isFloating()) return {}
    // When the panel is open, use panel drag position
    if (state.isOpen) {
      if (!geom.dragged || geom.left == null || geom.top == null) return {}
      return { left: `${geom.left}px`, top: `${geom.top}px` }
    }
    // When closed (launcher visible), use launcher drag position
    if (launcherGeom.dragged && launcherGeom.left != null && launcherGeom.top != null) {
      return { left: `${launcherGeom.left}px`, top: `${launcherGeom.top}px` }
    }
    return {}
  })

  const panelStyle = computed(() => {
    if (!isFloating()) return {}
    const style = {}
    if (geom.width != null) style.width = `${geom.width}px`
    if (geom.height != null) style.height = `${geom.height}px`
    return style
  })

  const isDragged = computed(() => {
    if (!isFloating()) return false
    if (state.isOpen) return geom.dragged
    return launcherGeom.dragged
  })
  const isInteracting = computed(() => geom.interacting || launcherGeom.interacting)

  const persist = () => {
    saveGeometry(config.websiteId, {
      left: geom.left,
      top: geom.top,
      width: geom.width,
      height: geom.height
    })
  }

  const persistLauncher = () => {
    try {
      window.localStorage.setItem(launcherStorageKey(config.websiteId), JSON.stringify({
        left: launcherGeom.left,
        top: launcherGeom.top
      }))
    } catch (_error) { /* non-fatal */ }
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

  // ── Launcher Drag ──────────────────────────────────────────────────────────
  let launcherDragStart = null
  let launcherDragMoved = false

  const onLauncherDragMove = (event) => {
    if (!launcherDragStart) return
    const dx = event.clientX - launcherDragStart.x
    const dy = event.clientY - launcherDragStart.y
    if (!launcherDragMoved && Math.abs(dx) < DRAG_THRESHOLD && Math.abs(dy) < DRAG_THRESHOLD) return
    launcherDragMoved = true
    const { vw, vh } = viewport()
    const nextLeft = Math.min(Math.max(0, launcherDragStart.left + dx), vw - LAUNCHER_SIZE)
    const nextTop = Math.min(Math.max(0, launcherDragStart.top + dy), vh - LAUNCHER_SIZE)
    launcherGeom.left = nextLeft
    launcherGeom.top = nextTop
  }

  const endLauncherDrag = () => {
    window.removeEventListener('pointermove', onLauncherDragMove)
    window.removeEventListener('pointerup', endLauncherDrag)
    launcherDragStart = null
    launcherGeom.interacting = false
    if (launcherDragMoved) {
      persistLauncher()
    }
  }

  const startLauncherDrag = (event) => {
    if (!isFloating()) return
    const el = rootEl.value
    if (!el) return
    const rect = el.getBoundingClientRect()
    launcherDragStart = { x: event.clientX, y: event.clientY, left: rect.left, top: rect.top }
    launcherDragMoved = false
    launcherGeom.dragged = true
    launcherGeom.interacting = true
    window.addEventListener('pointermove', onLauncherDragMove)
    window.addEventListener('pointerup', endLauncherDrag)
    event.preventDefault()
    event.stopPropagation()
  }

  const isLauncherDragMoved = () => launcherDragMoved

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
  const restoreLauncherGeometry = () => {
    if (!isFloating()) return
    try {
      const raw = window.localStorage.getItem(launcherStorageKey(config.websiteId))
      if (!raw) return
      const saved = JSON.parse(raw)
      if (!saved || typeof saved !== 'object') return
      const left = toFiniteOrNull(saved.left)
      const top = toFiniteOrNull(saved.top)
      if (left == null || top == null) return
      const { vw, vh } = viewport()
      launcherGeom.left = Math.min(Math.max(0, left), vw - LAUNCHER_SIZE)
      launcherGeom.top = Math.min(Math.max(0, top), vh - LAUNCHER_SIZE)
      launcherGeom.dragged = true
    } catch (_error) { /* non-fatal */ }
  }

  const restoreGeometry = () => {
    if (!isFloating()) return
    restoreLauncherGeometry()
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
    if (launcherGeom.dragged && launcherGeom.left != null && launcherGeom.top != null) {
      launcherGeom.left = Math.min(Math.max(0, launcherGeom.left), vw - LAUNCHER_SIZE)
      launcherGeom.top = Math.min(Math.max(0, launcherGeom.top), vh - LAUNCHER_SIZE)
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
    launcherGeom.left = null
    launcherGeom.top = null
    launcherGeom.dragged = false
    launcherGeom.interacting = false
    try { window.localStorage.removeItem(launcherStorageKey(config.websiteId)) } catch (_e) { /* */ }
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
    window.removeEventListener('pointermove', onLauncherDragMove)
    window.removeEventListener('pointerup', endLauncherDrag)
  }

  return { rootStyle, panelStyle, isDragged, isInteracting, startDrag, startResize, startLauncherDrag, isLauncherDragMoved, resetGeometry, bind, unbind }
}
