import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import IntelligentQueryRemoteEmbed from '../IntelligentQueryRemoteEmbed.vue'

describe('IntelligentQueryRemoteEmbed', () => {
  beforeEach(() => {
    document.head.innerHTML = ''
    document.body.innerHTML = ''
    delete window.OpenDataWorksWidget
    delete window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
  })

  it('loads the remote DataAgent widget script and installs inline mode into the page container', async () => {
    const destroy = vi.fn()
    const installWidget = vi.fn(() => ({ destroy }))
    const wrapper = mount(IntelligentQueryRemoteEmbed)

    const container = wrapper.find('#odw-dataagent-inline-widget')
    expect(container.exists()).toBe(true)

    const script = document.querySelector('script[data-odw-dataagent-widget-script]')
    expect(script).toBeTruthy()
    expect(script.src).toBe('http://localhost:3000/dataagent/widget/opendataworks-widget.bundle.js')

    window.OpenDataWorksWidget = { installWidget }
    script.dispatchEvent(new Event('load'))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(installWidget).toHaveBeenCalledWith(expect.objectContaining({
      displayMode: 'inline',
      containerId: 'odw-dataagent-inline-widget',
      agentId: 'agent_opendataworks',
      apiBaseUrl: ''
    }))

    wrapper.unmount()
    expect(destroy).toHaveBeenCalledTimes(1)
  })
})
