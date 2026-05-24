import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

import { installWidget } from '../entry'

const apiMocks = vi.hoisted(() => ({
  createClient: vi.fn(),
  runtimeApi: {
    getConfig: vi.fn()
  },
  topicApi: {
    listTopics: vi.fn()
  }
}))

vi.mock('@/api/nl2sql', () => ({
  createNl2SqlApiClient: apiMocks.createClient
}))

describe('installWidget', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.createClient.mockReturnValue(apiMocks)
    apiMocks.runtimeApi.getConfig.mockRejectedValue(new Error('demo backend unavailable'))
    apiMocks.topicApi.listTopics.mockResolvedValue([])
    Object.defineProperty(window, 'innerHeight', {
      configurable: true,
      writable: true,
      value: 760
    })
    Object.defineProperty(window, 'visualViewport', {
      configurable: true,
      value: null
    })
  })

  afterEach(() => {
    window.OpenDataWorksWidget?.destroy?.()
    delete window.OpenDataWorksWidget
    document.querySelectorAll('[data-odw-widget-root]').forEach((node) => node.remove())
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

  it('mounts a shadow-root widget and exposes the public controller', () => {
    const script = document.createElement('script')
    script.dataset.websiteId = 'demo'
    script.dataset.projectName = 'Demo'
    document.body.appendChild(script)

    const controller = installWidget(script)

    expect(document.querySelector('[data-odw-widget-root]')).toBeTruthy()
    expect(window.OpenDataWorksWidget._lastController).toBe(controller)
    expect(controller.isOpen()).toBe(false)
    expect(typeof controller.toggle).toBe('function')
    expect(typeof controller.sendMessage).toBe('function')
    expect(typeof controller.cancel).toBe('function')
    expect(typeof controller.openHistory).toBe('function')
    expect(typeof controller.newConversation).toBe('function')
    expect(typeof controller.selectConversation).toBe('function')
    expect(typeof controller.deleteConversation).toBe('function')

    const opened = vi.fn()
    const dispose = controller.on('open', opened)

    controller.open()
    expect(controller.isOpen()).toBe(true)
    expect(opened).toHaveBeenCalledTimes(1)

    controller.close()
    expect(controller.isOpen()).toBe(false)

    controller.toggle()
    expect(controller.isOpen()).toBe(true)

    dispose()
    controller.sendMessage('hello')
    controller.cancel()

    controller.destroy()
    expect(document.querySelector('[data-odw-widget-root]')).toBeNull()
  })

  it('delivers a message when sendMessage opens a closed floating panel', async () => {
    const script = document.createElement('script')
    script.dataset.websiteId = 'demo'
    script.dataset.agentId = 'demo'
    script.dataset.projectName = 'Demo'
    document.body.appendChild(script)

    const controller = installWidget(script)
    const root = document.querySelector('[data-odw-widget-root]')

    controller.sendMessage('hello from closed panel')
    await nextTick()
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await flushPromises()
    await nextTick()

    expect(root.shadowRoot.textContent).toContain('hello from closed panel')
  })

  it('opens the built-in ask modal with a mock answer', async () => {
    const script = document.createElement('script')
    script.dataset.websiteId = 'demo'
    script.dataset.agentId = 'demo'
    script.dataset.projectName = 'Demo'
    document.body.appendChild(script)

    const controller = installWidget(script)

    controller.ask('最近30天工作流发布次数趋势')
    expect(document.querySelector('[data-odw-ask-modal]')).toBeTruthy()
    expect(document.body.textContent).toContain('正在理解问题')

    await new Promise((resolve) => setTimeout(resolve, 900))

    expect(document.body.textContent).toContain('最近30天工作流发布次数趋势')
    expect(document.body.textContent).toContain('工作流发布趋势分析')
    expect(document.querySelector('.odw-ask-input').getAttribute('placeholder')).toBe('Ask a follow-up')
    expect(typeof window.OpenDataWorksWidget.ask).toBe('function')
  })

  it('mounts inline widgets into the configured container without a launcher', () => {
    const target = document.createElement('div')
    target.id = 'odw-intelligent-query'
    document.body.appendChild(target)
    const script = document.createElement('script')
    script.dataset.websiteId = 'demo'
    script.dataset.displayMode = 'inline'
    script.dataset.containerId = 'odw-intelligent-query'
    document.body.appendChild(script)

    const controller = installWidget(script)
    const root = target.querySelector('[data-odw-widget-root]')

    expect(root).toBeTruthy()
    expect(root.getAttribute('data-odw-widget-mode')).toBe('inline')
    expect(controller.isOpen()).toBe(true)
    expect(root.shadowRoot.textContent).toContain('智能问数')
    expect(root.shadowRoot.textContent).not.toContain('AI Demo')
  })

  it('uses viewport height for inline widgets when the target container has no measurable height', () => {
    const target = document.createElement('div')
    target.id = 'odw-intelligent-query'
    document.body.appendChild(target)
    const script = document.createElement('script')
    script.dataset.websiteId = 'demo'
    script.dataset.displayMode = 'inline'
    script.dataset.containerId = 'odw-intelligent-query'
    document.body.appendChild(script)

    installWidget(script)
    const root = target.querySelector('[data-odw-widget-root]')

    expect(root.style.height).toBe('752px')
    expect(root.style.width).toBe('100%')
  })

  it('uses the remaining viewport height when an inline target starts below the top of the page', () => {
    const target = document.createElement('div')
    target.id = 'odw-intelligent-query'
    target.getBoundingClientRect = vi.fn(() => ({
      x: 0,
      y: 80,
      top: 80,
      left: 0,
      right: 0,
      bottom: 80,
      width: 0,
      height: 0,
      toJSON: () => {}
    }))
    document.body.appendChild(target)
    const script = document.createElement('script')
    script.dataset.websiteId = 'demo'
    script.dataset.displayMode = 'inline'
    script.dataset.containerId = 'odw-intelligent-query'
    document.body.appendChild(script)

    installWidget(script)
    const root = target.querySelector('[data-odw-widget-root]')

    expect(root.style.height).toBe('672px')
  })

  it('ignores border-only inline target height and still uses the remaining viewport height', () => {
    const target = document.createElement('div')
    target.id = 'odw-intelligent-query'
    target.getBoundingClientRect = vi.fn(() => ({
      x: 0,
      y: 80,
      top: 80,
      left: 0,
      right: 0,
      bottom: 82,
      width: 1000,
      height: 2,
      toJSON: () => {}
    }))
    document.body.appendChild(target)
    const script = document.createElement('script')
    script.dataset.websiteId = 'demo'
    script.dataset.displayMode = 'inline'
    script.dataset.containerId = 'odw-intelligent-query'
    document.body.appendChild(script)

    installWidget(script)
    const root = target.querySelector('[data-odw-widget-root]')

    expect(root.style.height).toBe('672px')
  })

  it('uses the remaining viewport height even when the inline target has a short content height', () => {
    const target = document.createElement('div')
    target.id = 'odw-intelligent-query'
    target.getBoundingClientRect = vi.fn(() => ({
      x: 0,
      y: 80,
      top: 80,
      left: 0,
      right: 1000,
      bottom: 500,
      width: 1000,
      height: 420,
      toJSON: () => {}
    }))
    document.body.appendChild(target)
    const script = document.createElement('script')
    script.dataset.websiteId = 'demo'
    script.dataset.displayMode = 'inline'
    script.dataset.containerId = 'odw-intelligent-query'
    document.body.appendChild(script)

    installWidget(script)
    const root = target.querySelector('[data-odw-widget-root]')

    expect(root.style.height).toBe('672px')
  })

  it('updates inline height when the viewport changes after installation', () => {
    const target = document.createElement('div')
    target.id = 'odw-intelligent-query'
    target.getBoundingClientRect = vi.fn(() => ({
      x: 0,
      y: 80,
      top: 80,
      left: 0,
      right: 1000,
      bottom: 500,
      width: 1000,
      height: 420,
      toJSON: () => {}
    }))
    document.body.appendChild(target)
    const script = document.createElement('script')
    script.dataset.websiteId = 'demo'
    script.dataset.displayMode = 'inline'
    script.dataset.containerId = 'odw-intelligent-query'
    document.body.appendChild(script)

    installWidget(script)
    const root = target.querySelector('[data-odw-widget-root]')
    expect(root.style.height).toBe('672px')

    window.innerHeight = 700
    window.dispatchEvent(new Event('resize'))

    expect(root.style.height).toBe('612px')
  })

  it('loads the generated widget stylesheet into the shadow root when script src is available', () => {
    const script = document.createElement('script')
    script.src = 'https://odw.example.com/widget/opendataworks-widget.bundle.js'
    script.dataset.websiteId = 'demo'
    script.dataset.projectName = 'Demo'
    document.body.appendChild(script)

    installWidget(script)
    const root = document.querySelector('[data-odw-widget-root]')
    const stylesheet = root.shadowRoot.querySelector('link[rel="stylesheet"]')

    expect(stylesheet).toBeTruthy()
    expect(stylesheet.href).toBe('https://odw.example.com/widget/style.css')
  })
})
