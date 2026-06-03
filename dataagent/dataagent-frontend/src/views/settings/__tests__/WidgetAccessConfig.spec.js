import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({
  getSettings: vi.fn(),
  updateSettings: vi.fn()
}))

const messageMocks = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn()
}))

const messageBoxMocks = vi.hoisted(() => ({
  confirm: vi.fn()
}))

vi.mock('@/api/dataagent', () => ({
  dataagentApi: apiMocks
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal()),
  ElMessage: messageMocks,
  ElMessageBox: messageBoxMocks
}))

import WidgetAccessConfig from '../WidgetAccessConfig.vue'

const stubs = {
  'el-row': { template: '<div><slot /></div>' },
  'el-col': { template: '<div><slot /></div>' },
  'el-button': {
    props: ['disabled', 'loading'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
  },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot name="label" /><slot /></label>' },
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-color-picker': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<span class="color-picker" />'
  },
  'el-tooltip': { template: '<span><slot /><slot name="content" /></span>' },
  'el-icon': { template: '<i><slot /></i>' },
  Check: { template: '<i />' },
  Close: { template: '<i />' },
  Delete: { template: '<i />' },
  Plus: { template: '<i />' },
  QuestionFilled: { template: '<i />' }
}

const mountConfig = () => mount(WidgetAccessConfig, { global: { stubs } })

describe('WidgetAccessConfig', () => {
  beforeEach(() => {
    apiMocks.getSettings.mockReset()
    apiMocks.updateSettings.mockReset()
    messageMocks.success.mockReset()
    messageMocks.error.mockReset()
    messageBoxMocks.confirm.mockReset()
  })

  it('renders existing sites from settings', async () => {
    apiMocks.getSettings.mockResolvedValue({
      widget_allowed_sites: [
        { website_id: 'demo', allowed_origins: ['https://a.com'], project_name: 'Demo', project_color: '#4A90A4' }
      ]
    })
    const wrapper = mountConfig()
    await flushPromises()

    expect(wrapper.text()).toContain('站点 1')
    expect(wrapper.find('.empty-block').exists()).toBe(false)
    // not dirty right after load → save button disabled
    const saveButton = wrapper.findAll('button').find((b) => b.text().includes('已保存'))
    expect(saveButton?.attributes('disabled')).toBeDefined()
  })

  it('shows the empty state when no sites exist', async () => {
    apiMocks.getSettings.mockResolvedValue({ widget_allowed_sites: [] })
    const wrapper = mountConfig()
    await flushPromises()

    expect(wrapper.find('.empty-block').exists()).toBe(true)
  })

  it('adds a site and saves the normalized payload', async () => {
    apiMocks.getSettings.mockResolvedValue({ widget_allowed_sites: [] })
    apiMocks.updateSettings.mockResolvedValue({
      widget_allowed_sites: [{ website_id: 'new-site', allowed_origins: ['https://b.com'], project_name: '', project_color: '' }]
    })
    const wrapper = mountConfig()
    await flushPromises()

    wrapper.vm.addSite()
    await flushPromises()
    wrapper.vm.sites[0].website_id = 'new-site'
    wrapper.vm.sites[0].allowed_origins = ['https://b.com', 'https://b.com', '']
    await flushPromises()

    await wrapper.vm.save()
    await flushPromises()

    expect(apiMocks.updateSettings).toHaveBeenCalledWith({
      widget_allowed_sites: [{ website_id: 'new-site', project_name: '', project_color: '', allowed_origins: ['https://b.com'] }]
    })
    expect(messageMocks.success).toHaveBeenCalled()
  })

  it('blocks saving when a website id is missing', async () => {
    apiMocks.getSettings.mockResolvedValue({ widget_allowed_sites: [] })
    const wrapper = mountConfig()
    await flushPromises()

    wrapper.vm.addSite()
    wrapper.vm.sites[0].website_id = '   '
    wrapper.vm.sites[0].allowed_origins = ['https://c.com']
    await flushPromises()

    // payloadSites filters out blank website_id → still dirty? no payload sites → snapshot '[]' equals saved → not dirty
    // Force dirty by adding a valid site too
    wrapper.vm.addSite()
    wrapper.vm.sites[1].website_id = 'valid'
    await flushPromises()

    await wrapper.vm.save()
    await flushPromises()

    expect(apiMocks.updateSettings).not.toHaveBeenCalled()
    expect(messageMocks.error).toHaveBeenCalled()
  })
})
