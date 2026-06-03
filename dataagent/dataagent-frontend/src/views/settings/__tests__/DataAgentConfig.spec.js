import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({
  getSettings: vi.fn(),
  updateSettings: vi.fn(),
  detectModel: vi.fn()
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

import DataAgentConfig from '../DataAgentConfig.vue'

const stubs = {
  'el-row': { template: '<div><slot /></div>' },
  'el-col': { template: '<div><slot /></div>' },
  'el-button': {
    props: ['disabled', 'loading'],
    template: '<button :disabled="disabled"><slot /></button>'
  },
  'el-switch': {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template: '<button :disabled="disabled" @click="$emit(\'update:modelValue\', !modelValue)"><slot /></button>'
  },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot name="label" /><slot /></label>' },
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue', 'input'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value); $emit(\'input\', $event.target.value)" />'
  },
  'el-select': {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template: '<select :disabled="disabled"><slot /></select>'
  },
  'el-option': { template: '<option />' },
  'el-tooltip': { template: '<span><slot /></span>' },
  'el-icon': { template: '<i><slot /></i>' },
  QuestionFilled: { template: '<i />' }
}

const mountConfig = () => shallowMount(DataAgentConfig, {
  global: { stubs }
})

const basePayload = () => ({
  provider_id: 'openrouter',
  model: 'anthropic/claude-sonnet-4.5',
  providers: [
    {
      provider_id: 'openrouter',
      display_name: 'OpenRouter',
      provider_group: '聚合路由',
      enabled: true,
      provider_enabled: true,
      auth_token_set: true,
      api_key_set: false,
      base_url: 'https://openrouter.ai/api',
      models: ['anthropic/claude-sonnet-4.5'],
      supported_models: ['anthropic/claude-sonnet-4.5'],
      custom_models: [],
      model_detections: {
        'anthropic/claude-sonnet-4.5': {
          status: 'verified',
          message: '模型检测通过',
          checked_at: '2026-04-17T10:00:00'
        }
      },
      supports_partial_messages: true,
      validation_status: 'verified',
      validation_message: '模型服务已可用'
    },
    {
      provider_id: 'anyrouter',
      display_name: 'AnyRouter',
      provider_group: '聚合路由',
      enabled: false,
      provider_enabled: false,
      auth_token_set: false,
      api_key_set: false,
      base_url: 'https://a-ocnfniawgw.cn-shanghai.fcapp.run',
      models: [],
      supported_models: ['claude-opus-4-6'],
      custom_models: [],
      model_detections: {},
      supports_partial_messages: true,
      validation_status: 'unverified',
      validation_message: '供应商未启用'
    }
  ]
})

const clone = (value) => JSON.parse(JSON.stringify(value))

describe('DataAgentConfig', () => {
  beforeEach(() => {
    apiMocks.getSettings.mockReset()
    apiMocks.updateSettings.mockReset()
    apiMocks.detectModel.mockReset()
    messageMocks.success.mockReset()
    messageMocks.error.mockReset()
    messageBoxMocks.confirm.mockReset()

    apiMocks.getSettings.mockResolvedValue(basePayload())
    apiMocks.updateSettings.mockImplementation(async (payload) => {
      const current = basePayload()
      const patch = payload.providers?.[0]
      const providers = current.providers.map((provider) => {
        if (!patch || provider.provider_id !== patch.provider_id) return provider
        return {
          ...provider,
          ...patch,
          auth_token_set: provider.auth_token_set || Boolean(patch.auth_token),
          api_key_set: provider.api_key_set || Boolean(patch.api_key),
          models: patch.enabled_models || provider.models,
          supported_models: Array.from(new Set([
            ...(provider.supported_models || []),
            ...(patch.custom_models || []),
            ...(patch.enabled_models || []),
            ...Object.keys(patch.model_detections || {})
          ])),
          custom_models: patch.custom_models || [],
          model_detections: patch.model_detections || provider.model_detections,
          provider_enabled: patch.provider_enabled ?? provider.provider_enabled,
          enabled: Boolean((patch.provider_enabled ?? provider.provider_enabled) && (patch.enabled_models || []).length),
          validation_status: Boolean((patch.provider_enabled ?? provider.provider_enabled) && (patch.enabled_models || []).length)
            ? 'verified'
            : 'unverified',
          validation_message: Boolean((patch.provider_enabled ?? provider.provider_enabled) && (patch.enabled_models || []).length)
            ? '模型服务已可用'
            : '请启用至少一个模型'
        }
      })
      return {
        ...current,
        provider_id: Object.prototype.hasOwnProperty.call(payload, 'provider_id') ? payload.provider_id : current.provider_id,
        model: Object.prototype.hasOwnProperty.call(payload, 'model') ? payload.model : current.model,
        providers
      }
    })
  })

  it('removes top page header actions', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    expect(wrapper.text()).not.toContain('刷新')
    expect(wrapper.text()).not.toContain('配置供应商、检测模型可用性，并选择默认模型。')
  })

  it('allows enabling a model before detection succeeds', async () => {
    const payload = basePayload()
    payload.providers[0].models = []
    payload.providers[0].enabled = false
    payload.providers[0].validation_status = 'unverified'
    payload.providers[0].validation_message = '请启用至少一个模型'
    payload.providers[0].model_detections = {}
    apiMocks.getSettings.mockResolvedValue(payload)

    const wrapper = mountConfig()
    await flushPromises()

    const model = 'anthropic/claude-sonnet-4.5'
    expect(wrapper.vm.canEnableModel(model)).toBe(true)
    wrapper.vm.setModelEnabled(model, true)
    expect(wrapper.vm.currentDraft.enabled_models).toEqual([model])
  })

  it('keeps an enabled model when detection fails', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    const model = 'anthropic/claude-sonnet-4.5'
    expect(wrapper.vm.currentDraft.enabled_models).toEqual([model])

    apiMocks.detectModel.mockResolvedValue({
      provider_id: 'openrouter',
      model,
      status: 'failed',
      message: '模型检测失败',
      checked_at: '2026-04-17T10:00:00'
    })

    await wrapper.vm.detectModel(model)
    await flushPromises()

    expect(wrapper.vm.currentDraft.enabled_models).toEqual([model])
    expect(messageMocks.error).toHaveBeenCalledWith('模型检测失败')
  })

  it('saves only the current provider patch and updates dirty button state', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    wrapper.vm.currentDraft.supports_partial_messages = false

    expect(wrapper.vm.currentProviderDirty).toBe(true)
    expect(wrapper.vm.saveButtonText).toBe('保存改动')
    expect(wrapper.vm.isProviderDirty('anyrouter')).toBe(false)

    await wrapper.vm.saveCurrentProvider()
    await flushPromises()

    expect(apiMocks.updateSettings).toHaveBeenCalledTimes(1)
    expect(apiMocks.updateSettings.mock.calls[0][0].providers).toHaveLength(1)
    expect(apiMocks.updateSettings.mock.calls[0][0].providers[0].provider_id).toBe('openrouter')
    expect(apiMocks.updateSettings.mock.calls[0][0].provider_id).toBe('openrouter')
    expect(apiMocks.updateSettings.mock.calls[0][0].model).toBe('anthropic/claude-sonnet-4.5')
    expect(wrapper.vm.currentProviderDirty).toBe(false)
    expect(wrapper.vm.saveButtonText).toBe('保存配置')
  })

  it('confirms before switching provider with unsaved changes and restores discarded draft', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    wrapper.vm.currentDraft.supports_partial_messages = false
    messageBoxMocks.confirm.mockResolvedValue('confirm')

    await wrapper.vm.selectProvider('anyrouter')
    await flushPromises()

    expect(messageBoxMocks.confirm).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.selectedProviderId).toBe('anyrouter')
    expect(wrapper.vm.providerDrafts.openrouter.supports_partial_messages).toBe(true)
    expect(wrapper.vm.form.provider_id).toBe('openrouter')
    expect(wrapper.vm.form.model).toBe('anthropic/claude-sonnet-4.5')
  })

  it('stays on the current provider when discard confirmation is canceled', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    wrapper.vm.currentDraft.provider_enabled = false
    messageBoxMocks.confirm.mockRejectedValue(new Error('cancel'))

    await wrapper.vm.selectProvider('anyrouter')
    await flushPromises()

    expect(wrapper.vm.selectedProviderId).toBe('openrouter')
    expect(wrapper.vm.currentDraft.provider_enabled).toBe(false)
  })

  it('clears detection state when connection fields change without disabling models', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    const before = clone(wrapper.vm.providerSnapshots.openrouter)
    wrapper.vm.clearCurrentDetections()

    expect(wrapper.vm.currentDraft.model_detections).toEqual({})
    expect(wrapper.vm.currentDraft.enabled_models).toEqual(['anthropic/claude-sonnet-4.5'])
    expect(wrapper.vm.form.model).toBe('anthropic/claude-sonnet-4.5')
    expect(wrapper.vm.isProviderDirty('openrouter')).toBe(true)
    expect(before.model_detections['anthropic/claude-sonnet-4.5'].status).toBe('verified')
  })
})
