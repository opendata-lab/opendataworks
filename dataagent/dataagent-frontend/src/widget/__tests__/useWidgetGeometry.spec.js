import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  clampPosition,
  clampSize,
  geometryStorageKey,
  loadGeometry,
  saveGeometry,
  clearGeometry
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
