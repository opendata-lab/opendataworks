import { computed, reactive, ref, onMounted, onBeforeUnmount } from 'vue'

// Data Studio 三栏布局尺寸与拖拽（P2-2 F5）。
// 从 DataStudioNew.vue 逐字抽出，行为保持不变：侧栏/右栏宽度按比例 + clamp，
// 左侧上下面板高度拖拽；窗口 resize 时归一化比例并同步结果面板布局。
// 依赖注入 activeTab 与 syncResultPaneLayout（后者为前向引用，以惰性包装传入）。
export function useResizablePanes({ activeTab, syncResultPaneLayout }) {
  const studioLayoutRef = ref(null)
  const DEFAULT_SIDEBAR_RATIO = 0.22
  const DEFAULT_RIGHT_RATIO = 0.28
  const MIN_SIDEBAR_WIDTH = 220
  const MAX_SIDEBAR_WIDTH = 840
  // 窄详情栏使用原生 Tab 滚动，优先保留 SQL 编辑区空间。
  const MIN_RIGHT_WIDTH = 320
  const MAX_RIGHT_WIDTH = 900
  const sidebarWidthRatio = ref(DEFAULT_SIDEBAR_RATIO)
  const rightPanelWidthRatio = ref(DEFAULT_RIGHT_RATIO)
  const getLayoutWidth = () => {
    const width = studioLayoutRef.value?.getBoundingClientRect()?.width || window.innerWidth || 1
    return Math.max(1, width)
  }
  const clampWidth = (value, min, max) => Math.max(min, Math.min(max, value))
  const clampSidebarWidth = (value) => clampWidth(value, MIN_SIDEBAR_WIDTH, MAX_SIDEBAR_WIDTH)
  const clampRightWidth = (value) => clampWidth(value, MIN_RIGHT_WIDTH, MAX_RIGHT_WIDTH)
  const getSidebarWidthPx = () => clampSidebarWidth(getLayoutWidth() * sidebarWidthRatio.value)
  const getRightPanelWidthPx = () => clampRightWidth(getLayoutWidth() * rightPanelWidthRatio.value)
  const sidebarPaneStyle = computed(() => ({
    width: `${(sidebarWidthRatio.value * 100).toFixed(2)}%`,
    minWidth: `${MIN_SIDEBAR_WIDTH}px`,
    maxWidth: `${MAX_SIDEBAR_WIDTH}px`
  }))
  const rightPaneStyle = computed(() => ({
    width: `${(rightPanelWidthRatio.value * 100).toFixed(2)}%`,
    minWidth: `${MIN_RIGHT_WIDTH}px`,
    maxWidth: `${MAX_RIGHT_WIDTH}px`
  }))
  const normalizePaneRatios = () => {
    const layoutWidth = getLayoutWidth()
    // 纵向布局使用整行宽度，不让最小栏宽把桌面的比例放大。
    if (layoutWidth <= 900) return
    sidebarWidthRatio.value = clampSidebarWidth(layoutWidth * sidebarWidthRatio.value) / layoutWidth
    rightPanelWidthRatio.value = clampRightWidth(layoutWidth * rightPanelWidthRatio.value) / layoutWidth
  }
  const isResizing = ref(false)
  let resizeMoveHandler = null
  let resizeUpHandler = null
  let resizeRightMoveHandler = null
  let resizeRightUpHandler = null
  const leftPaneHeights = reactive({})
  const leftPaneRefs = ref({})
  let resizeLeftMoveHandler = null
  let resizeLeftUpHandler = null

  const setLeftPaneRef = (key, el) => {
    if (!key || !el) return
    leftPaneRefs.value[key] = el
  }

  const getLeftPaneStyle = (key) => {
    const height = leftPaneHeights[key]
    if (!height) return {}
    return { '--left-top': `${height}px` }
  }

  const handleResize = () => {
    normalizePaneRatios()
    const tabId = activeTab.value
    if (!tabId) return
    syncResultPaneLayout(tabId)
  }

  const startResize = (event) => {
    if (event.button !== 0 || isResizing.value) return
    event.preventDefault()
    const startX = event.clientX
    const startWidth = getSidebarWidthPx()
    isResizing.value = true

    resizeMoveHandler = (moveEvent) => {
      if (!(moveEvent.buttons & 1)) {
        resizeUpHandler()
        return
      }
      const delta = moveEvent.clientX - startX
      const next = clampSidebarWidth(startWidth + delta)
      sidebarWidthRatio.value = next / getLayoutWidth()
    }
    resizeUpHandler = () => {
      isResizing.value = false
      window.removeEventListener('mousemove', resizeMoveHandler)
      window.removeEventListener('mouseup', resizeUpHandler)
      resizeMoveHandler = null
      resizeUpHandler = null
    }
    window.addEventListener('mousemove', resizeMoveHandler)
    window.addEventListener('mouseup', resizeUpHandler)
  }

  const startRightResize = (event) => {
    if (event.button !== 0 || isResizing.value) return
    event.preventDefault()
    const startX = event.clientX
    const startWidth = getRightPanelWidthPx()
    isResizing.value = true

    resizeRightMoveHandler = (moveEvent) => {
      if (!(moveEvent.buttons & 1)) {
        resizeRightUpHandler()
        return
      }
      const delta = startX - moveEvent.clientX
      const next = clampRightWidth(startWidth + delta)
      rightPanelWidthRatio.value = next / getLayoutWidth()
    }
    resizeRightUpHandler = () => {
      isResizing.value = false
      window.removeEventListener('mousemove', resizeRightMoveHandler)
      window.removeEventListener('mouseup', resizeRightUpHandler)
      resizeRightMoveHandler = null
      resizeRightUpHandler = null
    }
    window.addEventListener('mousemove', resizeRightMoveHandler)
    window.addEventListener('mouseup', resizeRightUpHandler)
  }

  const startLeftResize = (tabId, event) => {
    if (event.button !== 0 || isResizing.value) return
    event.preventDefault()
    const container = leftPaneRefs.value[tabId]
    if (!container) return
    const queryPanel = container.querySelector('.query-panel')
    const containerRect = container.getBoundingClientRect()
    const startY = event.clientY
    const startHeight = queryPanel?.getBoundingClientRect().height || 220
    const minTop = 160
    const minBottom = 220
    const resizerHeight = 6
    isResizing.value = true
    let layoutRaf = 0

    resizeLeftMoveHandler = (moveEvent) => {
      if (!(moveEvent.buttons & 1)) {
        resizeLeftUpHandler()
        return
      }
      const delta = moveEvent.clientY - startY
      let next = startHeight + delta
      const maxTop = Math.max(minTop, containerRect.height - minBottom - resizerHeight)
      next = Math.max(minTop, Math.min(maxTop, next))
      leftPaneHeights[tabId] = next
      if (layoutRaf) cancelAnimationFrame(layoutRaf)
      layoutRaf = requestAnimationFrame(() => syncResultPaneLayout(tabId))
    }
    resizeLeftUpHandler = () => {
      isResizing.value = false
      window.removeEventListener('mousemove', resizeLeftMoveHandler)
      window.removeEventListener('mouseup', resizeLeftUpHandler)
      resizeLeftMoveHandler = null
      resizeLeftUpHandler = null
      if (layoutRaf) cancelAnimationFrame(layoutRaf)
      layoutRaf = requestAnimationFrame(() => syncResultPaneLayout(tabId))
    }
    window.addEventListener('mousemove', resizeLeftMoveHandler)
    window.addEventListener('mouseup', resizeLeftUpHandler)
  }

  const stopResize = () => {
    resizeUpHandler?.()
    resizeRightUpHandler?.()
    resizeLeftUpHandler?.()
  }

  onMounted(() => {
    window.addEventListener('resize', handleResize)
    window.addEventListener('blur', stopResize)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', handleResize)
    window.removeEventListener('blur', stopResize)
    if (resizeMoveHandler) {
      window.removeEventListener('mousemove', resizeMoveHandler)
      resizeMoveHandler = null
    }
    if (resizeUpHandler) {
      window.removeEventListener('mouseup', resizeUpHandler)
      resizeUpHandler = null
    }
    if (resizeRightMoveHandler) {
      window.removeEventListener('mousemove', resizeRightMoveHandler)
      resizeRightMoveHandler = null
    }
    if (resizeRightUpHandler) {
      window.removeEventListener('mouseup', resizeRightUpHandler)
      resizeRightUpHandler = null
    }
    if (resizeLeftMoveHandler) {
      window.removeEventListener('mousemove', resizeLeftMoveHandler)
      resizeLeftMoveHandler = null
    }
    if (resizeLeftUpHandler) {
      window.removeEventListener('mouseup', resizeLeftUpHandler)
      resizeLeftUpHandler = null
    }
  })

  return {
    studioLayoutRef,
    sidebarWidthRatio,
    rightPanelWidthRatio,
    sidebarPaneStyle,
    rightPaneStyle,
    normalizePaneRatios,
    isResizing,
    leftPaneHeights,
    leftPaneRefs,
    setLeftPaneRef,
    getLeftPaneStyle,
    startResize,
    startRightResize,
    startLeftResize,
  }
}
