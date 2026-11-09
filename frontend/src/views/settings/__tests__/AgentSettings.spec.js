import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import AgentSettings from '../AgentSettings.vue'
import { settingsApi } from '@/api/settings'

vi.mock('@/api/settings', () => ({
  settingsApi: {
    getAgentSettings: vi.fn(),
    updateAgentSettings: vi.fn()
  }
}))

describe('AgentSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders inputs and saves configured agent IDs directly', async () => {
    settingsApi.getAgentSettings.mockResolvedValue({ queryAgentId: '', metadataAgentId: '' })
    settingsApi.updateAgentSettings.mockResolvedValue({
      queryAgentId: 'custom_query_agent',
      metadataAgentId: 'custom_meta_agent'
    })

    const wrapper = mount(AgentSettings, {
      global: {
        stubs: {
          SettingsSection: {
            template: '<div class="settings-section"><slot name="actions" /><slot /></div>'
          },
          SettingsRow: {
            props: ['label', 'description'],
            template: '<div class="settings-row"><span class="label">{{ label }}</span><slot /></div>'
          }
        }
      }
    })

    await nextTick()
    await new Promise((r) => setTimeout(r, 20))
    await nextTick()

    // Alert shows guidance when unset
    const alert = wrapper.findComponent({ name: 'ElAlert' })
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('尚未配置')

    const inputs = wrapper.findAllComponents({ name: 'ElInput' })
    expect(inputs.length).toBe(2)
    expect(inputs[0].props('disabled')).toBe(false)
    expect(inputs[1].props('disabled')).toBe(false)

    // User types agent ID directly
    await inputs[0].setValue('custom_query_agent')
    await inputs[1].setValue('custom_meta_agent')

    const saveBtn = wrapper.findAllComponents({ name: 'ElButton' }).find((b) => b.text().includes('保存'))
    expect(saveBtn.props('disabled')).toBe(false)

    await saveBtn.trigger('click')
    await nextTick()
    await new Promise((r) => setTimeout(r, 20))

    expect(settingsApi.updateAgentSettings).toHaveBeenCalledWith({
      queryAgentId: 'custom_query_agent',
      metadataAgentId: 'custom_meta_agent'
    })
  })

  it('renders already saved settings as configured', async () => {
    settingsApi.getAgentSettings.mockResolvedValue({
      queryAgentId: 'agent_default',
      metadataAgentId: 'agent_default'
    })

    const wrapper = mount(AgentSettings, {
      global: {
        stubs: {
          SettingsSection: {
            template: '<div class="settings-section"><slot name="actions" /><slot /></div>'
          },
          SettingsRow: {
            props: ['label', 'description'],
            template: '<div class="settings-row"><span class="label">{{ label }}</span><slot /></div>'
          }
        }
      }
    })

    await nextTick()
    await new Promise((r) => setTimeout(r, 20))
    await nextTick()

    const inputs = wrapper.findAllComponents({ name: 'ElInput' })
    expect(inputs[0].props('modelValue')).toBe('agent_default')
    expect(inputs[1].props('modelValue')).toBe('agent_default')

    const tags = wrapper.findAllComponents({ name: 'ElTag' })
    expect(tags[0].text()).toBe('已配置')
    expect(tags[1].text()).toBe('已配置')

    // No alert when both are configured
    const alert = wrapper.findComponent({ name: 'ElAlert' })
    expect(alert.exists()).toBe(false)
  })
})
