import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

import IntelligentQueryRemoteEmbed from '../IntelligentQueryRemoteEmbed.vue'

const routeState = reactive({ query: {} })
vi.mock('vue-router', () => ({ useRoute: () => routeState }))
const getAgentSettings = vi.hoisted(() => vi.fn())
vi.mock('@/api/settings', () => ({ settingsApi: { getAgentSettings } }))


describe('IntelligentQueryRemoteEmbed', () => {
  beforeEach(() => {
    document.head.innerHTML = ''
    document.body.innerHTML = ''
    delete window.DataAgentWidget
    delete window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
    routeState.query = {}
    getAgentSettings.mockReset().mockResolvedValue({ queryAgentId: 'agent_query' })
    setActivePinia(createPinia())
    useAuthStore().currentUser = { username: 'alice', agentUserId: 'local:alice' }
  })

  it('loads the remote DataAgent widget script and installs inline mode into the page container', async () => {
    const destroy = vi.fn()
    const installWidget = vi.fn(() => ({ destroy }))
    const wrapper = mount(IntelligentQueryRemoteEmbed)

    const container = wrapper.find('#odw-dataagent-inline-widget')
    expect(container.exists()).toBe(true)

    const script = document.querySelector('script[data-odw-dataagent-widget-script]')
    expect(script).toBeTruthy()
    expect(script.src).toBe('http://localhost:3000/dataagent/widget/dataagent-widget.bundle.js')

    window.DataAgentWidget = { installWidget }
    script.dispatchEvent(new Event('load'))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(installWidget).toHaveBeenCalledWith(expect.objectContaining({
      displayMode: 'inline',
      containerId: 'odw-dataagent-inline-widget',
      agentId: 'agent_query',
      websiteId: 'opendataworks-portal',
      userId: 'local:alice',
      apiBaseUrl: ''
    }))

    wrapper.unmount()
    expect(destroy).toHaveBeenCalledTimes(1)
  })

  it('opens the transferred topic and follows changes to the menu route', async () => {
    routeState.query = { topicId: 'topic-current' }
    const selectConversation = vi.fn(), newConversation = vi.fn(), destroy = vi.fn()
    window.DataAgentWidget = { installWidget: vi.fn(() => ({ selectConversation, newConversation, destroy })) }
    const wrapper = mount(IntelligentQueryRemoteEmbed)
    await flushPromises()
    expect(selectConversation).toHaveBeenLastCalledWith('topic-current')
    routeState.query = { topicId: 'topic-other' }
    await flushPromises()
    expect(selectConversation).toHaveBeenLastCalledWith('topic-other')
    routeState.query = {}
    await flushPromises()
    expect(newConversation).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    expect(destroy).toHaveBeenCalledTimes(1)
  })

  it('uses the latest route if it changes while the remote bundle is loading', async () => {
    routeState.query = { topicId: 'topic-old' }
    const selectConversation = vi.fn(), destroy = vi.fn()
    const wrapper = mount(IntelligentQueryRemoteEmbed)
    routeState.query = { topicId: 'topic-latest' }
    window.DataAgentWidget = { installWidget: vi.fn(() => ({ selectConversation, destroy })) }
    document.querySelector('script[data-odw-dataagent-widget-script]').dispatchEvent(new Event('load'))
    await flushPromises()
    expect(selectConversation).toHaveBeenCalledTimes(1)
    expect(selectConversation).toHaveBeenCalledWith('topic-latest')
    wrapper.unmount()
  })

  it('asks for the query assistant to be configured instead of guessing one', async () => {
    getAgentSettings.mockResolvedValue({ queryAgentId: '' })
    const installWidget = vi.fn()
    window.DataAgentWidget = { installWidget }
    const wrapper = mount(IntelligentQueryRemoteEmbed)
    await flushPromises()
    expect(installWidget).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('尚未配置智能问数助手')
    wrapper.unmount()
  })
})
