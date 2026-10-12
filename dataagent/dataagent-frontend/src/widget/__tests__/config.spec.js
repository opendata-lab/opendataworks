import { describe, expect, it } from 'vitest'

import { parseWidgetConfig, resolveFullscreenUrl } from '../config'

describe('parseWidgetConfig', () => {
  it('reads dataset attributes from the embedding script', () => {
    const script = document.createElement('script')
    script.src = 'https://odw.example.com/widget/opendataworks-widget.bundle.js'
    script.dataset.websiteId = 'demo'
    script.dataset.userId = 'user-123'
    script.dataset.projectName = 'Demo Project'
    script.dataset.projectColor = '#4A90A4'
    script.dataset.apiBaseUrl = 'https://odw.example.com/'
    script.dataset.agentId = 'agent_widget'

    const config = parseWidgetConfig(script)

    expect(config.websiteId).toBe('demo')
    expect(config.userId).toBe('user-123')
    expect(config.agentId).toBe('agent_widget')
    expect(config.projectName).toBe('Demo Project')
    expect(config.projectColor).toBe('#4A90A4')
    expect(config.apiBaseUrl).toBe('https://odw.example.com')
    expect(config.displayMode).toBe('floating')
    expect(config.containerId).toBe('')
    expect(config.fullscreenUrl).toBe('')
    expect(config.headers).toMatchObject({
      'X-ODW-Client': 'widget',
      'X-ODW-Website-Id': 'demo',
      'X-ODW-User-Id': 'user-123'
    })
    expect(config.headers).not.toHaveProperty('X-ODW-Visitor-Id')
  })

  it('does not generate a visitor id by default when no user id is available', () => {
    const script = document.createElement('script')
    script.dataset.websiteId = 'demo'
    script.dataset.loginUrl = '/login'
    window.localStorage.clear()

    const config = parseWidgetConfig(script)

    expect(config.userId).toBe('')
    expect(config.visitorId).toBe('')
    expect(config.allowAnonymous).toBe(false)
    expect(config.loginUrl).toBe('/login')
    expect(config.headers).toMatchObject({
      'X-ODW-Client': 'widget',
      'X-ODW-Website-Id': 'demo'
    })
    expect(config.headers).not.toHaveProperty('X-ODW-Visitor-Id')
  })

  it('generates a visitor id only when anonymous access is explicitly enabled', () => {
    const script = document.createElement('script')
    script.dataset.websiteId = 'demo'
    script.dataset.allowAnonymous = 'true'
    window.localStorage.clear()

    const config = parseWidgetConfig(script)

    expect(config.allowAnonymous).toBe(true)
    expect(config.visitorId).toMatch(/^visitor_/)
    expect(config.headers).toMatchObject({
      'X-ODW-Client': 'widget',
      'X-ODW-Website-Id': 'demo',
      'X-ODW-Visitor-Id': config.visitorId
    })
  })

  it('supports inline display mode with a target container id', () => {
    const script = document.createElement('script')
    script.dataset.websiteId = 'demo'
    script.dataset.displayMode = 'inline'
    script.dataset.containerId = 'odw-intelligent-query'

    const config = parseWidgetConfig(script)

    expect(config.displayMode).toBe('inline')
    expect(config.containerId).toBe('odw-intelligent-query')
  })

  it('resolves fullscreen routes against the host page, rather than the bundle origin', () => {
    const script = document.createElement('script')
    script.src = 'https://widget.example.com/widget/opendataworks-widget.bundle.js'
    script.dataset.fullscreenUrl = '/#/chat?topicId=one'
    expect(parseWidgetConfig(script).fullscreenUrl).toBe(new URL('/#/chat?topicId=one', window.location.href).href)
    expect(resolveFullscreenUrl('https://app.example.com/chat')).toBe('https://app.example.com/chat')
  })

  it.each(['javascript:alert(1)', 'data:text/html,test', 'file:///tmp/page.html', 'http://[', ''])('rejects invalid fullscreen target %s', (value) => {
    expect(resolveFullscreenUrl(value)).toBe('')
  })
})
