<template>
  <div class="dataagent-config">
    <div v-loading="loading" class="provider-workbench">
      <aside class="provider-nav">
        <div
          v-for="group in groupedProviders"
          :key="group.group"
          class="provider-group"
        >
          <div class="provider-group-title">{{ group.group }}</div>
          <button
            v-for="provider in group.items"
            :key="provider.provider_id"
            type="button"
            class="provider-card"
            :class="{ active: provider.provider_id === selectedProviderId }"
            @click="selectProvider(provider.provider_id)"
          >
            <div class="provider-card-head">
              <div class="provider-card-main">
                <div class="provider-card-name-row">
                  <div class="provider-card-name">{{ provider.display_name }}</div>
                  <span
                    v-if="provider.provider_id === selectedProviderId && isProviderDirty(provider.provider_id)"
                    class="provider-dirty-mark"
                  >
                    未保存
                  </span>
                </div>
                <div class="provider-card-id">{{ provider.provider_id }}</div>
              </div>
              <span class="provider-status" :class="statusClass(providerPreview(provider).status)">
                {{ statusLabel(providerPreview(provider).status, providerPreview(provider).providerEnabled) }}
              </span>
            </div>
            <div class="provider-card-meta">
              <span>{{ providerPreview(provider).enabledModels.length }} 个已启用模型</span>
              <span>{{ credentialSummary(provider) }}</span>
            </div>
          </button>
        </div>
      </aside>

      <section v-if="currentProvider && currentDraft" class="provider-detail">
        <div class="provider-titlebar">
          <div class="provider-title-main">
            <div class="provider-kicker">{{ currentProvider.provider_group || '模型供应商' }}</div>
            <h3>{{ currentProvider.display_name }}</h3>
            <p>{{ currentProviderPreview.message }}</p>
          </div>
          <div class="provider-title-actions">
            <el-button
              type="primary"
              :icon="Check"
              :loading="isSavingCurrentProvider"
              :disabled="!currentProviderDirty || isSavingCurrentProvider"
              @click="saveCurrentProvider"
            >
              {{ saveButtonText }}
            </el-button>
            <div class="provider-switch">
              <span>启用供应商</span>
              <el-switch v-model="currentDraft.provider_enabled" />
            </div>
          </div>
        </div>

        <div class="service-section">
          <div class="section-heading">
            <div class="section-title">连接配置</div>
          </div>

          <el-form label-position="top" class="provider-form">
            <el-row :gutter="16">
              <el-col :xs="24" :md="12">
                <el-form-item>
                  <template #label>
                    <span class="field-label">
                      {{ credentialLabel(currentProvider.provider_id) }}
                      <el-tooltip content="供应商控制台生成的访问凭证。留空表示继续使用后端已保存的凭证。" placement="top">
                        <el-icon><QuestionFilled /></el-icon>
                      </el-tooltip>
                    </span>
                  </template>
                  <el-input
                    v-model="currentDraft.token"
                    type="password"
                    show-password
                    :placeholder="credentialPlaceholder(currentProvider.provider_id)"
                    @input="clearCurrentDetections"
                  />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :md="12">
                <el-form-item>
                  <template #label>
                    <span class="field-label">
                      Base URL
                      <el-tooltip content="供应商或兼容网关的 API 服务地址。官方供应商可使用默认地址。" placement="top">
                        <el-icon><QuestionFilled /></el-icon>
                      </el-tooltip>
                    </span>
                  </template>
                  <el-input
                    v-model="currentDraft.base_url"
                    :placeholder="baseUrlPlaceholder(currentProvider.provider_id)"
                    @input="clearCurrentDetections"
                  />
                </el-form-item>
              </el-col>
            </el-row>

            <el-form-item>
              <template #label>
                <span class="field-label">
                  流式能力
                  <el-tooltip content="用于控制模型响应事件粒度。供应商兼容性不完整时可切换为兼容模式。" placement="top">
                    <el-icon><QuestionFilled /></el-icon>
                  </el-tooltip>
                </span>
              </template>
              <div class="stream-mode-row">
                <el-switch
                  v-model="currentDraft.supports_partial_messages"
                  inline-prompt
                  active-text="细粒度"
                  inactive-text="兼容"
                />
                <span>{{ currentDraft.supports_partial_messages ? '细粒度响应事件' : '兼容响应事件' }}</span>
              </div>
            </el-form-item>
          </el-form>
        </div>

        <div class="service-section">
          <div class="section-heading model-heading">
            <div class="section-title">模型列表</div>
            <div class="custom-model-row">
              <el-input
                v-model="customModelInput"
                placeholder="追加自定义模型"
                @keyup.enter="addCustomModel"
              />
              <el-button :icon="Plus" @click="addCustomModel">追加</el-button>
            </div>
          </div>

          <div v-if="currentSupportedModels.length" class="model-table">
            <div class="model-row model-row-head">
              <span>模型</span>
              <span>检测状态</span>
              <span>检测</span>
              <span>启用</span>
            </div>
            <div
              v-for="model in currentSupportedModels"
              :key="model"
              class="model-row"
            >
              <div class="model-name-cell">
                <span>{{ model }}</span>
                <button
                  v-if="currentDraft.custom_models.includes(model)"
                  type="button"
                  class="text-danger"
                  @click="removeCustomModel(model)"
                >
                  删除
                </button>
              </div>
              <div>
                <span class="model-detection" :class="detectionClass(model)">
                  {{ detectionLabel(model) }}
                </span>
              </div>
              <div>
                <el-button
                  size="small"
                  :loading="isDetecting(model)"
                  :disabled="!canDetectCurrentProvider"
                  @click="detectModel(model)"
                >
                  检测
                </el-button>
              </div>
              <div>
                <el-switch
                  :model-value="isModelEnabled(model)"
                  :disabled="!canEnableModel(model)"
                  @update:model-value="setModelEnabled(model, $event)"
                />
              </div>
            </div>
          </div>
          <div v-else class="empty-block">当前供应商暂无模型，请追加自定义模型。</div>
        </div>

        <div class="service-section default-section">
          <div class="section-title">默认模型</div>
          <el-select
            v-model="currentDefaultModel"
            placeholder="请先启用可用模型"
            :disabled="!currentEnabledModels.length"
          >
            <el-option
              v-for="model in currentEnabledModels"
              :key="model"
              :label="model"
              :value="model"
            />
          </el-select>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Plus, QuestionFilled } from '@element-plus/icons-vue'
import { dataagentApi } from '@/api/dataagent'

const loading = ref(false)
const savingProviderId = ref('')
const providers = ref([])
const selectedProviderId = ref('')
const customModelInput = ref('')

const providerDrafts = reactive({})
const providerSnapshots = reactive({})
const detectingModels = reactive({})
const form = reactive({
  provider_id: '',
  model: ''
})
const savedSelection = reactive({
  provider_id: '',
  model: ''
})

const uniqueStrings = (values = []) => {
  const result = []
  const seen = new Set()
  values.forEach((value) => {
    const text = String(value || '').trim()
    if (!text || seen.has(text)) return
    seen.add(text)
    result.push(text)
  })
  return result
}

const normalizeDetections = (raw = {}) => {
  const result = {}
  if (!raw || typeof raw !== 'object') return result
  Object.entries(raw)
    .sort(([left], [right]) => String(left).localeCompare(String(right)))
    .forEach(([model, item]) => {
      if (!model || !item || typeof item !== 'object') return
      result[model] = {
        status: item.status || 'unverified',
        message: item.message || '',
        checked_at: item.checked_at || ''
      }
    })
  return result
}

const buildProviderDraft = (provider) => {
  const customModels = uniqueStrings(provider.custom_models || [])
  const modelDetections = normalizeDetections(provider.model_detections || {})
  return {
    provider_id: provider.provider_id,
    provider_enabled: Boolean(provider.provider_enabled || provider.enabled),
    token: '',
    base_url: provider.base_url || '',
    supports_partial_messages: provider.supports_partial_messages !== false,
    enabled_models: uniqueStrings(provider.models || []),
    custom_models: customModels,
    base_supported_models: uniqueStrings(provider.supported_models || []).filter((model) => !customModels.includes(model)),
    model_detections: modelDetections
  }
}

const buildProviderSnapshot = (draft) => {
  const modelDetections = normalizeDetections(draft?.model_detections)
  const enabledModels = uniqueStrings(draft?.enabled_models)
  return {
    provider_enabled: Boolean(draft?.provider_enabled),
    token: String(draft?.token || '').trim(),
    base_url: String(draft?.base_url || '').trim(),
    supports_partial_messages: draft?.supports_partial_messages !== false,
    enabled_models: enabledModels,
    custom_models: uniqueStrings(draft?.custom_models),
    model_detections: modelDetections
  }
}

const snapshotEquals = (left, right) => JSON.stringify(left) === JSON.stringify(right)

const statusLabel = (status, providerEnabled = true) => {
  if (!providerEnabled) return '未启用'
  if (status === 'verified') return '可用'
  if (status === 'invalid' || status === 'failed') return '异常'
  return '待配置'
}

const statusClass = (status) => {
  if (status === 'verified') return 'is-verified'
  if (status === 'invalid' || status === 'failed') return 'is-invalid'
  return 'is-pending'
}

const credentialLabel = (providerId) => (providerId === 'anthropic' ? 'API Key' : 'Token')
const credentialPlaceholder = (providerId) => (providerId === 'anthropic' ? '留空保持现有 API Key' : '留空保持现有 Token')

const baseUrlPlaceholder = (providerId) => {
  if (providerId === 'anthropic') return 'https://api.anthropic.com'
  if (providerId === 'openrouter') return 'https://openrouter.ai/api'
  if (providerId === 'anyrouter') return 'https://a-ocnfniawgw.cn-shanghai.fcapp.run'
  return '请输入兼容网关地址'
}

const groupedProviders = computed(() => {
  const groups = new Map()
  providers.value.forEach((provider) => {
    const groupName = provider.provider_group || '其他'
    if (!groups.has(groupName)) groups.set(groupName, [])
    groups.get(groupName).push(provider)
  })
  return Array.from(groups.entries()).map(([group, items]) => ({ group, items }))
})

const currentProvider = computed(() => {
  return providers.value.find((item) => item.provider_id === selectedProviderId.value) || providers.value[0] || null
})

const currentDraft = computed(() => {
  if (!currentProvider.value) return null
  return providerDrafts[currentProvider.value.provider_id] || null
})

const currentProviderId = computed(() => currentProvider.value?.provider_id || '')

const currentDefaultModel = computed({
  get() {
    if (!currentProvider.value || form.provider_id !== currentProvider.value.provider_id) return ''
    return currentEnabledModels.value.includes(form.model) ? form.model : ''
  },
  set(model) {
    if (!currentProvider.value) return
    form.provider_id = currentProvider.value.provider_id
    form.model = model || ''
  }
})

const currentProviderDirty = computed(() => {
  return Boolean(currentProviderId.value) && isProviderDirty(currentProviderId.value)
})

const saveButtonText = computed(() => (currentProviderDirty.value ? '保存改动' : '保存配置'))
const isSavingCurrentProvider = computed(() => savingProviderId.value === currentProviderId.value)

const getDraft = (providerId) => {
  return providerDrafts[providerId] || {
    provider_enabled: false,
    enabled_models: [],
    custom_models: [],
    base_supported_models: [],
    token: '',
    base_url: '',
    supports_partial_messages: true,
    model_detections: {}
  }
}

const defaultModelForProvider = (selection, providerId) => {
  return selection.provider_id === providerId ? String(selection.model || '') : ''
}

const hasProviderFieldChanges = (providerId) => {
  const draft = providerDrafts[providerId]
  const snapshot = providerSnapshots[providerId]
  if (!draft || !snapshot) return false
  return !snapshotEquals(buildProviderSnapshot(draft), snapshot)
}

const hasProviderSelectionChanges = (providerId) => {
  return defaultModelForProvider(form, providerId) !== defaultModelForProvider(savedSelection, providerId)
}

function isProviderDirty(providerId) {
  return hasProviderFieldChanges(providerId) || hasProviderSelectionChanges(providerId)
}

const supportedModelsFor = (providerId) => {
  const draft = getDraft(providerId)
  return uniqueStrings([
    ...(draft.base_supported_models || []),
    ...(draft.custom_models || []),
    ...(draft.enabled_models || []),
    ...Object.keys(draft.model_detections || {})
  ])
}

const currentSupportedModels = computed(() => {
  if (!currentProvider.value) return []
  return supportedModelsFor(currentProvider.value.provider_id)
})

const modelDetection = (model) => {
  return currentDraft.value?.model_detections?.[model] || {
    status: 'unverified',
    message: '待检测',
    checked_at: ''
  }
}

const detectionLabel = (model) => {
  const detection = modelDetection(model)
  if (detection.status === 'verified') return '检测通过'
  if (detection.status === 'failed') return detection.message || '检测失败'
  return '未检测'
}

const detectionClass = (model) => {
  const status = modelDetection(model).status
  if (status === 'verified') return 'is-verified'
  if (status === 'failed') return 'is-invalid'
  return 'is-pending'
}

const providerHasCredential = (provider, draft) => {
  const typed = String(draft?.token || '').trim()
  if (typed) return true
  if (provider.provider_id === 'anthropic') return Boolean(provider.api_key_set)
  return Boolean(provider.auth_token_set || provider.api_key_set)
}

const providerBaseUrlReady = (provider, draft) => {
  return provider.provider_id !== 'anthropic_compatible' || Boolean(String(draft?.base_url || '').trim())
}

const providerPreview = (provider) => {
  const draft = providerDrafts[provider.provider_id]
  if (!draft) {
    return {
      status: provider.validation_status || 'unverified',
      message: provider.validation_message || '待配置',
      providerEnabled: Boolean(provider.provider_enabled || provider.enabled),
      enabled: Boolean(provider.enabled),
      enabledModels: uniqueStrings(provider.models || [])
    }
  }

  const providerEnabled = Boolean(draft.provider_enabled)
  const enabledModels = uniqueStrings(draft.enabled_models)
  if (!providerEnabled) {
    return {
      status: 'unverified',
      message: '供应商未启用',
      providerEnabled,
      enabled: false,
      enabledModels: []
    }
  }
  if (!providerBaseUrlReady(provider, draft)) {
    return {
      status: 'unverified',
      message: 'Base URL 缺失',
      providerEnabled,
      enabled: false,
      enabledModels: []
    }
  }
  if (!providerHasCredential(provider, draft)) {
    return {
      status: 'unverified',
      message: provider.provider_id === 'anthropic' ? '请填写 API Key' : '请填写 Token',
      providerEnabled,
      enabled: false,
      enabledModels: []
    }
  }
  if (!enabledModels.length) {
    return {
      status: 'unverified',
      message: '请启用至少一个模型',
      providerEnabled,
      enabled: false,
      enabledModels: []
    }
  }
  return {
    status: 'verified',
    message: '模型服务已可用',
    providerEnabled,
    enabled: true,
    enabledModels
  }
}

const currentProviderPreview = computed(() => {
  if (!currentProvider.value) {
    return {
      status: 'unverified',
      message: '请选择供应商',
      providerEnabled: false,
      enabled: false,
      enabledModels: []
    }
  }
  return providerPreview(currentProvider.value)
})

const currentEnabledModels = computed(() => currentProviderPreview.value.enabledModels)

const canDetectCurrentProvider = computed(() => {
  if (!currentProvider.value || !currentDraft.value) return false
  return providerHasCredential(currentProvider.value, currentDraft.value) && providerBaseUrlReady(currentProvider.value, currentDraft.value)
})

const credentialSummary = (provider) => {
  const draft = providerDrafts[provider.provider_id]
  if (String(draft?.token || '').trim()) return '本次有新凭证'
  return providerHasCredential(provider, draft) ? '凭证已保存' : '未配置凭证'
}

const isModelEnabled = (model) => Boolean(currentDraft.value?.enabled_models?.includes(model))

const canEnableModel = (model) => {
  return Boolean(currentDraft.value?.provider_enabled)
}

const setModelEnabled = (model, enabled) => {
  if (!currentDraft.value) return
  const list = new Set(currentDraft.value.enabled_models)
  if (enabled) {
    if (!canEnableModel(model)) return
    list.add(model)
  } else {
    list.delete(model)
  }
  currentDraft.value.enabled_models = Array.from(list)
}

const detectKey = (model) => `${currentProvider.value?.provider_id || ''}::${model}`
const isDetecting = (model) => Boolean(detectingModels[detectKey(model)])

const clearCurrentDetections = () => {
  if (!currentDraft.value) return
  currentDraft.value.model_detections = {}
}

const resetProviderState = (items) => {
  Object.keys(providerDrafts).forEach((key) => {
    delete providerDrafts[key]
  })
  Object.keys(providerSnapshots).forEach((key) => {
    delete providerSnapshots[key]
  })
  items.forEach((provider) => {
    const draft = buildProviderDraft(provider)
    providerDrafts[provider.provider_id] = draft
    providerSnapshots[provider.provider_id] = buildProviderSnapshot(draft)
  })
}

const mergeProviderState = (items, refreshedProviderId = '') => {
  const providerIds = new Set(items.map((item) => item.provider_id))
  Object.keys(providerDrafts).forEach((providerId) => {
    if (providerIds.has(providerId)) return
    delete providerDrafts[providerId]
    delete providerSnapshots[providerId]
  })
  items.forEach((provider) => {
    if (!providerDrafts[provider.provider_id] || provider.provider_id === refreshedProviderId) {
      const draft = buildProviderDraft(provider)
      providerDrafts[provider.provider_id] = draft
      providerSnapshots[provider.provider_id] = buildProviderSnapshot(draft)
    }
  })
}

const applySettings = (payload) => {
  providers.value = Array.isArray(payload?.providers) ? payload.providers : []
  resetProviderState(providers.value)

  savedSelection.provider_id = payload?.provider_id || ''
  savedSelection.model = payload?.model || ''
  form.provider_id = savedSelection.provider_id
  form.model = savedSelection.model

  selectedProviderId.value = providers.value.find((item) => item.provider_id === savedSelection.provider_id)?.provider_id
    || providers.value[0]?.provider_id
    || ''
  customModelInput.value = ''
}

const applySavedProvider = (payload, providerId) => {
  providers.value = Array.isArray(payload?.providers) ? payload.providers : []
  mergeProviderState(providers.value, providerId)

  savedSelection.provider_id = payload?.provider_id || ''
  savedSelection.model = payload?.model || ''
  form.provider_id = savedSelection.provider_id
  form.model = savedSelection.model

  if (!providers.value.some((item) => item.provider_id === selectedProviderId.value)) {
    selectedProviderId.value = providerId || savedSelection.provider_id || providers.value[0]?.provider_id || ''
  }
  customModelInput.value = ''
}

const restoreProviderDraft = (providerId) => {
  const provider = providers.value.find((item) => item.provider_id === providerId)
  if (!provider) return
  const draft = buildProviderDraft(provider)
  providerDrafts[providerId] = draft
  providerSnapshots[providerId] = buildProviderSnapshot(draft)
  form.provider_id = savedSelection.provider_id
  form.model = savedSelection.model
  customModelInput.value = ''
}

const loadSettings = async () => {
  loading.value = true
  try {
    const payload = await dataagentApi.getSettings()
    applySettings(payload)
  } finally {
    loading.value = false
  }
}

const selectProvider = async (providerId) => {
  if (!providerId || providerId === selectedProviderId.value || isSavingCurrentProvider.value) return
  const currentId = currentProviderId.value
  if (currentId && isProviderDirty(currentId)) {
    try {
      await ElMessageBox.confirm(
        '当前供应商有未保存改动，放弃后将恢复为上次保存内容。',
        '未保存改动',
        {
          confirmButtonText: '放弃改动',
          cancelButtonText: '继续编辑',
          type: 'warning',
          distinguishCancelAndClose: true
        }
      )
      restoreProviderDraft(currentId)
    } catch {
      return
    }
  }
  selectedProviderId.value = providerId
  customModelInput.value = ''
}

const addCustomModel = () => {
  if (!currentDraft.value) return
  const model = String(customModelInput.value || '').trim()
  if (!model) return
  currentDraft.value.custom_models = uniqueStrings([...(currentDraft.value.custom_models || []), model])
  if (!currentDraft.value.model_detections[model]) {
    currentDraft.value.model_detections[model] = {
      status: 'unverified',
      message: '待检测',
      checked_at: ''
    }
  }
  customModelInput.value = ''
}

const removeCustomModel = (model) => {
  if (!currentDraft.value) return
  currentDraft.value.custom_models = currentDraft.value.custom_models.filter((item) => item !== model)
  currentDraft.value.enabled_models = currentDraft.value.enabled_models.filter((item) => item !== model)
  delete currentDraft.value.model_detections[model]
  if (form.model === model && form.provider_id === currentProvider.value?.provider_id) {
    form.model = ''
  }
}

const detectModel = async (model) => {
  if (!currentProvider.value || !currentDraft.value) return
  const key = detectKey(model)
  detectingModels[key] = true
  try {
    const token = String(currentDraft.value.token || '').trim()
    const payload = {
      provider_id: currentProvider.value.provider_id,
      model,
      base_url: currentDraft.value.base_url,
      supports_partial_messages: currentDraft.value.supports_partial_messages !== false
    }
    if (token) {
      if (currentProvider.value.provider_id === 'anthropic') {
        payload.api_key = token
      } else {
        payload.auth_token = token
      }
    }
    const result = await dataagentApi.detectModel(payload)
    currentDraft.value.model_detections[model] = {
      status: result.status || 'failed',
      message: result.message || '',
      checked_at: result.checked_at || ''
    }
    if (result.status !== 'verified') {
      ElMessage.error(result.message || '模型检测失败')
      return
    }
    ElMessage.success('模型检测通过')
  } finally {
    detectingModels[key] = false
  }
}

const buildProviderPayload = (providerId) => {
  const provider = providers.value.find((item) => item.provider_id === providerId)
  const draft = providerDrafts[providerId]
  const enabledModels = uniqueStrings(draft.enabled_models)
  const payload = {
    provider_id: providerId,
    provider_enabled: Boolean(draft.provider_enabled),
    base_url: draft.base_url,
    supports_partial_messages: draft.supports_partial_messages !== false,
    enabled_models: enabledModels,
    custom_models: uniqueStrings(draft.custom_models),
    model_detections: normalizeDetections(draft.model_detections)
  }
  const token = String(draft.token || '').trim()
  if (token) {
    if (provider?.provider_id === 'anthropic') {
      payload.api_key = token
    } else {
      payload.auth_token = token
    }
  }
  return payload
}

const shouldPersistSelectionWithProvider = (providerId) => {
  return savedSelection.provider_id === providerId || form.provider_id === providerId
}

const saveCurrentProvider = async () => {
  if (!currentProvider.value || !currentProviderDirty.value) return
  const providerId = currentProvider.value.provider_id
  savingProviderId.value = providerId
  try {
    const payload = {
      providers: [buildProviderPayload(providerId)]
    }
    if (shouldPersistSelectionWithProvider(providerId)) {
      payload.provider_id = form.provider_id || ''
      payload.model = form.model || ''
    }
    const saved = await dataagentApi.updateSettings(payload)
    applySavedProvider(saved, providerId)
    ElMessage.success('模型服务配置已保存')
  } catch (error) {
    ElMessage.error(error?.message || '保存失败，请重试')
  } finally {
    savingProviderId.value = ''
  }
}

const validatedProviders = computed(() => {
  return providers.value
    .map((provider) => ({
      ...provider,
      models: providerPreview(provider).enabledModels,
      enabled: providerPreview(provider).enabled
    }))
    .filter((provider) => provider.enabled && provider.models.length)
})

const validatedModels = computed(() => {
  const provider = validatedProviders.value.find((item) => item.provider_id === form.provider_id)
  return provider ? provider.models : []
})

watch(validatedProviders, (list) => {
  if (!list.length) {
    form.provider_id = ''
    form.model = ''
    return
  }
  if (!list.some((provider) => provider.provider_id === form.provider_id)) {
    form.provider_id = list[0].provider_id
  }
}, { deep: true, immediate: true })

watch(validatedModels, (models) => {
  if (!models.length) {
    form.model = ''
    return
  }
  if (!models.includes(form.model)) {
    form.model = models[0]
  }
}, { immediate: true })

onMounted(() => {
  loadSettings()
})
</script>

<style scoped>
.dataagent-config {
  color: #1f2937;
}

.provider-workbench {
  display: grid;
  grid-template-columns: 304px minmax(0, 1fr);
  gap: 18px;
}

.provider-nav {
  padding: 16px;
  border: 1px solid #d8e3ef;
  border-radius: 8px;
  background: #f7faff;
}

.provider-group + .provider-group {
  margin-top: 16px;
}

.provider-group-title {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #4f6680;
}

.provider-card {
  width: 100%;
  margin-bottom: 10px;
  padding: 13px 14px;
  border: 1px solid #d8e3ef;
  border-radius: 8px;
  background: #ffffff;
  text-align: left;
  transition: border-color 160ms ease, box-shadow 160ms ease, background 160ms ease;
  cursor: pointer;
}

.provider-card:hover {
  border-color: #9bb9d8;
  background: #fbfdff;
}

.provider-card.active {
  border-color: #1f5f99;
  background: #f2f7fc;
  box-shadow: inset 3px 0 0 #1f5f99, 0 1px 4px rgba(31, 95, 153, 0.12);
}

.provider-card-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.provider-card-main {
  min-width: 0;
}

.provider-card-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.provider-card-name {
  min-width: 0;
  font-size: 15px;
  font-weight: 700;
  color: #1d2f43;
}

.provider-dirty-mark {
  flex: none;
  display: inline-flex;
  align-items: center;
  padding: 2px 6px;
  border-radius: 999px;
  background: #fff3d8;
  color: #8a5a12;
  font-size: 11px;
  font-weight: 700;
}

.provider-card-id {
  margin-top: 3px;
  font-size: 12px;
  color: #71839a;
  word-break: break-word;
}

.provider-card-meta {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-top: 12px;
  font-size: 12px;
  color: #66788a;
}

.provider-status,
.model-detection {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.provider-status::before,
.model-detection::before {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  content: '';
}

.is-verified {
  color: #146c43;
  background: #eaf7ef;
  border: 1px solid #b8e3c6;
}

.is-verified::before {
  background: #1f9d55;
}

.is-pending {
  color: #8a5a12;
  background: #fff8e6;
  border: 1px solid #f0d894;
}

.is-pending::before {
  background: #d99016;
}

.is-invalid {
  color: #a12828;
  background: #fff1f1;
  border: 1px solid #efc2c2;
}

.is-invalid::before {
  background: #d14343;
}

.provider-detail {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.provider-titlebar {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  border: 1px solid #cddceb;
  border-left: 4px solid #1f5f99;
  border-radius: 8px;
  background: linear-gradient(90deg, #f4f8fc 0%, #ffffff 72%);
}

.provider-title-main {
  min-width: 0;
}

.provider-kicker {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #2c659b;
}

.provider-titlebar h3 {
  margin: 7px 0 6px;
  font-size: 22px;
  font-weight: 700;
  color: #16324f;
}

.provider-titlebar p {
  margin: 0;
  font-size: 14px;
  color: #53677e;
}

.provider-title-actions {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  flex: none;
}

.provider-title-actions :deep(.el-button--primary) {
  --el-button-bg-color: #1f5f99;
  --el-button-border-color: #1f5f99;
  --el-button-hover-bg-color: #2c74b8;
  --el-button-hover-border-color: #2c74b8;
  --el-button-active-bg-color: #184d7d;
  --el-button-active-border-color: #184d7d;
}

.provider-switch {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: #40566e;
  font-size: 13px;
  white-space: nowrap;
}

.service-section {
  padding: 18px;
  border: 1px solid #d8e3ef;
  border-radius: 8px;
  background: #ffffff;
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.section-title {
  font-size: 15px;
  font-weight: 700;
  color: #16324f;
}

.field-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.field-label .el-icon {
  color: #71839a;
}

.provider-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.stream-mode-row {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  color: #53677e;
  font-size: 13px;
}

.model-heading {
  align-items: flex-start;
}

.custom-model-row {
  display: grid;
  grid-template-columns: minmax(180px, 260px) auto;
  gap: 10px;
}

.model-table {
  border: 1px solid #d8e3ef;
  border-radius: 8px;
  overflow: hidden;
}

.model-row {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(150px, 220px) 86px 72px;
  gap: 14px;
  align-items: center;
  padding: 12px 14px;
  border-top: 1px solid #e4eaf2;
}

.model-row:first-child {
  border-top: none;
}

.model-row-head {
  background: #f7faff;
  color: #4f6680;
  font-size: 12px;
  font-weight: 700;
}

.model-name-cell {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}

.model-name-cell span {
  min-width: 0;
  font-weight: 600;
  color: #1d2f43;
  word-break: break-word;
}

.text-danger {
  border: none;
  background: none;
  color: #b42318;
  font-weight: 600;
  cursor: pointer;
}

.empty-block {
  padding: 20px;
  border: 1px dashed #b7cbe1;
  border-radius: 8px;
  background: #f7faff;
  color: #53677e;
}

.default-section {
  display: grid;
  grid-template-columns: 120px minmax(0, 420px);
  gap: 16px;
  align-items: center;
}

@media (max-width: 1100px) {
  .provider-workbench {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .provider-titlebar,
  .section-heading,
  .provider-title-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .provider-title-actions {
    width: 100%;
  }

  .provider-switch {
    justify-content: space-between;
  }

  .custom-model-row,
  .default-section {
    grid-template-columns: 1fr;
  }

  .model-row,
  .model-row-head {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .model-row-head {
    display: none;
  }
}
</style>
