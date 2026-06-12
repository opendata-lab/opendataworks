import { afterEach, describe, expect, it, vi } from 'vitest'
import { reactive, ref } from 'vue'

import {
  clampPosition,
  clampSize,
  geometryStorageKey,
  launcherStorageKey,
  loadGeometry,
  saveGeometry,
  clearGeometry,
  useWidgetGeometry
} from '../useWidgetGeometry'

describe('clampPosition', () => {
  it('keeps the panel inside the viewport', () => {
    // try to drag far beyond bottom-right
    expect(clampPosition(5000, 5000, 400, 600, 1280, 800)).toEqual({ left: 880, top: 200 })
  })

  it('never returns negative coordinates', () => {
    expect(clampPosition(-100, -50, 400, 600, 1280, 800)).toEqual({ left: 0, top: 0 })
  })

  it('clamps to 0 when the panel is larger than the viewport', () => {
    expect(clampPosition(300, 300, 2000, 2000, 1280, 800)).toEqual({ left: 0, top: 0 })
  })
})

describe('clampSize', () => {
  it('enforces the minimum size', () => {
    expect(clampSize(10, 10, 1280, 800)).toEqual({ width: 360, height: 420 })
  })

  it('caps to the viewport minus the margin', () => {
    expect(clampSize(99999, 99999, 1280, 800)).toEqual({ width: 1256, height: 776 })
  })

  it('passes through a valid size', () => {
    expect(clampSize(600, 700, 1920, 1080)).toEqual({ width: 600, height: 700 })
  })
})

describe('geometryStorageKey', () => {
  it('namespaces by websiteId', () => {
    expect(geometryStorageKey('site_1')).toBe('odw:widget:geom:site_1')
  })

  it('falls back to default when no websiteId', () => {
    expect(geometryStorageKey('')).toBe('odw:widget:geom:default')
  })
})

describe('localStorage persistence', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    window.localStorage.clear()
  })

  it('round-trips geometry through localStorage', () => {
    saveGeometry('site_1', { left: 100, top: 50, width: 600, height: 700 })
    expect(loadGeometry('site_1')).toEqual({ left: 100, top: 50, width: 600, height: 700 })
  })

  it('returns null when nothing is stored', () => {
    expect(loadGeometry('missing')).toBeNull()
  })

  it('returns null for malformed JSON', () => {
    window.localStorage.setItem(geometryStorageKey('bad'), '{not json')
    expect(loadGeometry('bad')).toBeNull()
  })

  it('drops non-finite fields to null', () => {
    window.localStorage.setItem(
      geometryStorageKey('partial'),
      JSON.stringify({ left: 'x', top: 'y', width: 600, height: 700 })
    )
    expect(loadGeometry('partial')).toEqual({ left: null, top: null, width: 600, height: 700 })
  })

  it('returns null when every field is unusable', () => {
    window.localStorage.setItem(geometryStorageKey('empty'), JSON.stringify({ left: null }))
    expect(loadGeometry('empty')).toBeNull()
  })

  it('does not throw when storage write fails', () => {
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded')
    })
    expect(() => saveGeometry('site_1', { left: 1, top: 1, width: 400, height: 500 })).not.toThrow()
  })

  it('does not throw when storage read fails', () => {
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    expect(loadGeometry('site_1')).toBeNull()
  })

  it('clears stored geometry', () => {
    saveGeometry('site_1', { left: 1, top: 1, width: 400, height: 500 })
    clearGeometry('site_1')
    expect(loadGeometry('site_1')).toBeNull()
  })
})

describe('useWidgetGeometry drag activation', () => {
  const makeRect = (left, top, width, height) => ({ left, top, width, height })

  const setup = ({ isOpen = false } = {}) => {
    const rootEl = ref({ getBoundingClientRect: () => makeRect(100, 100, 56, 56) })
    const panelEl = ref({ getBoundingClientRect: () => makeRect(100, 50, 440, 600) })
    const config = { displayMode: 'floating', websiteId: 'site_drag' }
    const state = reactive({ isOpen })
    return { api: useWidgetGeometry({ rootEl, panelEl, config, state }), state }
  }

  const pointerDown = (clientX, clientY) => ({
    clientX,
    clientY,
    target: null,
    preventDefault: vi.fn(),
    stopPropagation: vi.fn()
  })

  const fire = (type, clientX = 0, clientY = 0) => {
    window.dispatchEvent(new MouseEvent(type, { clientX, clientY }))
  }

  afterEach(() => {
    window.localStorage.clear()
  })

  it('keeps the launcher anchored on a plain click (pointerdown + pointerup, no move)', () => {
    const { api } = setup()
    api.startLauncherDrag(pointerDown(110, 110))
    expect(api.isDragged.value).toBe(false)
    expect(api.rootStyle.value).toEqual({})
    fire('pointerup')
    expect(api.isDragged.value).toBe(false)
    expect(api.isLauncherDragMoved()).toBe(false)
    expect(window.localStorage.getItem(launcherStorageKey('site_drag'))).toBeNull()
  })

  it('ignores launcher movement below the drag threshold', () => {
    const { api } = setup()
    api.startLauncherDrag(pointerDown(110, 110))
    fire('pointermove', 112, 111)
    expect(api.isDragged.value).toBe(false)
    expect(api.rootStyle.value).toEqual({})
    fire('pointerup')
  })

  it('marks the launcher dragged with coordinates once the threshold is crossed', () => {
    const { api } = setup()
    api.startLauncherDrag(pointerDown(110, 110))
    fire('pointermove', 150, 140)
    expect(api.isDragged.value).toBe(true)
    expect(api.rootStyle.value).toEqual({ left: '140px', top: '130px' })
    fire('pointerup')
    expect(api.isLauncherDragMoved()).toBe(true)
    expect(JSON.parse(window.localStorage.getItem(launcherStorageKey('site_drag')))).toEqual({ left: 140, top: 130 })
  })

  it('keeps the panel anchored on a plain header click', () => {
    const { api } = setup({ isOpen: true })
    api.startDrag(pointerDown(120, 60))
    expect(api.isDragged.value).toBe(false)
    expect(api.rootStyle.value).toEqual({})
    fire('pointerup')
    expect(api.isDragged.value).toBe(false)
  })

  it('marks the panel dragged with coordinates once it moves', () => {
    const { api } = setup({ isOpen: true })
    api.startDrag(pointerDown(120, 60))
    fire('pointermove', 160, 90)
    expect(api.isDragged.value).toBe(true)
    expect(api.rootStyle.value).toEqual({ left: '140px', top: '80px' })
    fire('pointerup')
  })
})
