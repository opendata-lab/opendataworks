import { afterEach, expect, it, vi } from 'vitest'
import { loadDataAgentWidgetScript } from '../dataagentWidgetLoader'

afterEach(() => {
  document.querySelectorAll('[data-odw-dataagent-widget-script]').forEach(el => el.remove())
  delete window.DataAgentWidget
  delete window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
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
