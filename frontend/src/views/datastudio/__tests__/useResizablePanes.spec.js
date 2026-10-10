import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { useResizablePanes } from '../composables/useResizablePanes'

let wrapper

function setup(kind) {
  let api
  wrapper = mount(defineComponent({
    setup() {
      api = useResizablePanes({ activeTab: ref('t1'), syncResultPaneLayout: vi.fn() })
      return () => h('div')
    },
  }))
  api.studioLayoutRef.value = { getBoundingClientRect: () => ({ width: 1440 }) }
  api.leftPaneRefs.value.t1 = {
    getBoundingClientRect: () => ({ height: 800 }),
    querySelector: () => ({ getBoundingClientRect: () => ({ height: 240 }) }),
  }
  api.leftPaneHeights.t1 = 240
  const start = (button = 0) => {
    const event = new MouseEvent('mousedown', { button, buttons: button === 0 ? 1 : 2, clientX: 500, clientY: 300 })
    if (kind === 'sidebar') api.startResize(event)
    else if (kind === 'workspace') api.startRightResize(event)
    else api.startLeftResize('t1', event)
  }
  const value = () => kind === 'sidebar' ? api.sidebarWidthRatio.value
    : kind === 'workspace' ? api.rightPanelWidthRatio.value : api.leftPaneHeights.t1
  const move = (buttons, delta = 50) => window.dispatchEvent(new MouseEvent('mousemove', {
    buttons, clientX: 500 + delta, clientY: 300 + delta,
  }))
  return { api, start, value, move }
}

beforeEach(() => {
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1))
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
})

afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
})

describe.each(['sidebar', 'workspace', 'query'])('%s pane drag', (kind) => {
  it('ignores mouse movement until the left button is pressed', () => {
    const { api, start, value, move } = setup(kind)
    const initial = value()
    move(0)
    expect(value()).toBe(initial)
    start(2)
    move(2)
    expect(api.isResizing.value).toBe(false)
    expect(value()).toBe(initial)
    start()
    move(1)
    expect(api.isResizing.value).toBe(true)
    expect(value()).not.toBe(initial)
  })

  it.each(['mouseup', 'blur', 'released-move'])('stops resizing after %s', (end) => {
    const { api, start, value, move } = setup(kind)
    start()
    move(1)
    const dragged = value()
    if (end === 'released-move') move(0, 100)
    else window.dispatchEvent(new Event(end))
    expect(api.isResizing.value).toBe(false)
    expect(value()).toBe(dragged)
    move(0, 150)
    move(1, 200)
    expect(value()).toBe(dragged)
  })
})
