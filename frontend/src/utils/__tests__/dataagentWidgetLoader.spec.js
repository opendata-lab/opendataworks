import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { loadDataAgentWidgetScript, portalWidgetIdentity } from '../dataagentWidgetLoader'

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  document.querySelectorAll('[data-odw-dataagent-widget-script]').forEach(el => el.remove())
  delete window.DataAgentWidget
  delete window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
})

it('sends the signed-in username unchanged as widget userId', () => {
  const authStore = useAuthStore()
  expect(portalWidgetIdentity()).toEqual({ websiteId: 'opendataworks-portal' })

  authStore.currentUser = { username: 'alice', authSource: 'local' }
  expect(portalWidgetIdentity()).toEqual({
    websiteId: 'opendataworks-portal',
    userId: 'alice'
  })

  authStore.currentUser = { username: 'sso:u-42', authSource: 'sso' }
  expect(portalWidgetIdentity()).toEqual({
    websiteId: 'opendataworks-portal',
    userId: 'sso:u-42'
  })
})

it('shares concurrent loads and allows a new user attempt after an error', async () => {
  const first = loadDataAgentWidgetScript()
  expect(loadDataAgentWidgetScript()).toBe(first)
  const script = document.querySelector('[data-odw-dataagent-widget-script]')
  script.dispatchEvent(new Event('error'))
  await expect(first).rejects.toThrow('Failed to load')
  expect(script.isConnected).toBe(false)
  const retry = loadDataAgentWidgetScript()
  window.DataAgentWidget = { installWidget: vi.fn() }
  document.querySelector('[data-odw-dataagent-widget-script]').dispatchEvent(new Event('load'))
  await expect(retry).resolves.toBe(window.DataAgentWidget)
})
