<template>
  <div class="dataagent-config">
    <div v-loading="loading" class="provider-workbench">
      <aside class="provider-nav">
        <div class="provider-nav-body">
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
                <div class="provider-card-name">{{ provider.name || provider.display_name }}</div>
                <span
                  class="provider-dot"
                  :class="statusClass(providerPreview(provider).status)"
                  :title="statusLabel(providerPreview(provider).status, providerPreview(provider).providerEnabled)"
                />
              </div>
            </button>
          </div>
        </div>

        <div class="provider-nav-footer">
          <el-button
            class="add-provider-btn"
            :icon="Plus"
            @click="addNewProvider"
          >
            添加供应商
          </el-button>
        </div>
      </aside>

      <section v-if="currentProvider && currentDraft" class="provider-detail">
        <div class="provider-titlebar">
          <div class="provider-title-main">
            <h3>{{ currentDraft.name || currentProvider.display_name || currentProvider.provider_id }}</h3>
            <el-button
              text
              :icon="EditPen"
              title="编辑供应商名称"
              aria-label="编辑供应商名称"
              @click="focusProviderName"
            />
            <span
              class="provider-enabled-pill"
              :class="{ 'is-enabled': currentDraft.provider_enabled }"
            >
              {{ currentDraft.provider_enabled ? '已启用' : '未启用' }}
            </span>
            <el-button plain @click="currentDraft.provider_enabled = !currentDraft.provider_enabled">
              {{ currentDraft.provider_enabled ? '禁用' : '启用' }}
            </el-button>
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
            <el-button
              v-if="canDeleteCurrentProvider"
              text
              type="danger"
              :icon="Delete"
              title="删除供应商"
              aria-label="删除供应商"
              @click="deleteCurrentProvider"
            />
          </div>
        </div>

        <div class="service-section">
          <el-form label-position="top" class="provider-form">
            <el-row :gutter="16">
              <el-col :xs="24" :md="12">
                <el-form-item label="供应商名称">
                  <el-input
                    ref="providerNameInput"
                    v-model="currentDraft.name"
                    placeholder="例如：自建 DeepSeek 网关"
                  />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :md="12">
                <el-form-item>
                  <template #label>
                    <span class="field-label">
                      {{ credentialLabel(currentProvider.provider_id) }}
                      <el-tooltip content="供应商生成的访问凭证。留空表示继续使用后端已保存的凭证。" placement="top">
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
            </el-row>

            <el-row :gutter="16">
              <el-col :xs="24" :md="16">
                <el-form-item>
                  <template #label>
                    <span class="field-label">
                      API Base URL
                      <el-tooltip content="供应商或兼容网关的 API 服务地址。可从下拉列表选择常见厂商，或直接输入自定义 URL。" placement="top">
                        <el-icon><QuestionFilled /></el-icon>
                      </el-tooltip>
                    </span>
                  </template>
                  <el-select
                    v-model="currentDraft.base_url"
                    filterable
                    allow-create
                    default-first-option
                    clearable
                    placeholder="选择预置地址，或输入自定义 Base URL"
                    class="full-width"
                    @change="clearCurrentDetections"
                  >
                    <el-option
                      v-for="preset in BASE_URL_PRESETS"
                      :key="preset.value"
                      :label="preset.label"
                      :value="preset.value"
                    />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :md="8">
                <el-form-item>
                  <template #label>
                    <span class="field-label">
                      响应事件格式
                      <el-tooltip content="控制 SDK 是否接收细粒度流式事件；兼容性不完整的供应商请选择兼容模式。" placement="top">
                        <el-icon><QuestionFilled /></el-icon>
                      </el-tooltip>
                    </span>
                  </template>
                  <el-select
                    v-model="currentDraft.supports_partial_messages"
                    class="full-width"
                    @change="clearCurrentDetections"
                  >
                    <el-option label="细粒度流式事件（推荐）" :value="true" />
                    <el-option label="兼容事件模式" :value="false" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>
        </div>

        <div class="service-section">
          <div class="section-heading model-heading">
            <div>
              <div class="section-title">模型列表</div>
              <div class="section-subtitle">连接检测和高级参数都在对应模型行内处理。</div>
            </div>
            <div class="model-toolbar">
              <div class="default-model-control">
                <span>默认模型</span>
                <el-select
                  v-model="currentDefaultModel"
                  placeholder="请先启用模型"
                  :disabled="!currentEnabledModels.length"
                  class="default-model-select"
                >
                  <el-option
                    v-for="model in currentEnabledModels"
                    :key="model"
                    :label="model"
                    :value="model"
                  />
                </el-select>
              </div>
              <div class="custom-model-row">
                <el-input
                  v-model="customModelInput"
                  placeholder="模型 ID，例如：gpt-4o"
                  @keyup.enter="addCustomModel"
                />
                <el-button :icon="Plus" plain @click="addCustomModel">添加</el-button>
              </div>
            </div>
          </div>

          <div v-if="currentSupportedModels.length" class="model-list">
            <div
              v-for="model in currentModelRows"
              :key="model.id"
              class="model-card"
              :class="{ 'is-disabled': !isModelEnabled(model.id) }"
            >
              <div class="model-row-main">
                <div class="model-name-cell">
                  <span class="model-id">{{ model.id }}</span>
                  <span v-if="formatContextWindow(model.details.context_window)" class="model-context-badge">
                    {{ formatContextWindow(model.details.context_window) }}
                  </span>
                </div>
                <div class="model-ops-cell">
                  <el-button
                    text
                    :icon="Connection"
                    size="small"
                    class="model-icon-button"
                    :class="{ 'is-verified': modelDetection(model.id).status === 'verified' }"
                    :loading="isDetecting(model.id)"
                    :disabled="!canDetectCurrentProvider"
                    :title="modelDetectionTitle(model.id)"
                    :aria-label="modelDetectionTitle(model.id)"
                    @click="detectModel(model.id)"
                  />
                  <el-button
                    text
                    :icon="EditPen"
                    size="small"
                    class="model-icon-button"
                    :title="isAdvancedOpen(model.id) ? '收起模型设置' : '编辑模型设置'"
                    :aria-label="isAdvancedOpen(model.id) ? '收起模型设置' : '编辑模型设置'"
                    @click="toggleAdvanced(model.id)"
                  />
                  <el-button
                    text
                    :icon="Delete"
                    size="small"
                    type="danger"
                    class="model-icon-button"
                    title="删除模型"
                    aria-label="删除模型"
                    @click="removeModel(model.id)"
                  />
                </div>
              </div>

              <div v-if="modelDetection(model.id).status === 'failed'" class="model-error-row">
                <span>{{ detectionError(model.id) }}</span>
                <el-button
                  size="small"
                  :loading="isDetecting(model.id)"
                  :disabled="!canDetectCurrentProvider"
                  @click="detectModel(model.id)"
                >
                  重新检测
                </el-button>
              </div>

              <div v-if="isAdvancedOpen(model.id)" class="model-advanced-pane">
                <el-row :gutter="16">
                  <el-col :xs="24">
                    <div class="model-enabled-control">
                      <div>
                        <div class="model-enabled-title">用于对话</div>
                        <div class="model-enabled-desc">关闭后，该模型不会出现在默认模型和对话选择中。</div>
                      </div>
                      <el-switch
                        :model-value="isModelEnabled(model.id)"
                        :disabled="!canEnableModel(model.id)"
                        @update:model-value="setModelEnabled(model.id, $event)"
                      />
                    </div>
                  </el-col>
                  <el-col :xs="24" :sm="12">
                    <el-form-item label="Max Output Tokens">
                      <el-input-number
                        v-model="model.details.max_output_tokens"
                        :min="1"
                        :max="2000000"
                        :step="1024"
                        placeholder="如 4096"
                        class="full-width"
                      />
                    </el-form-item>
                  </el-col>
                  <el-col :xs="24" :sm="12">
                    <el-form-item label="Context Window">
                      <el-input-number
                        v-model="model.details.context_window"
                        :min="1"
                        :max="10000000"
                        :step="8192"
                        placeholder="如 128000"
                        class="full-width"
                      />
                    </el-form-item>
                  </el-col>
                </el-row>
              </div>
            </div>
          </div>
          <div v-else class="empty-block">还没有模型。输入模型 ID 后点击「添加」。</div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Connection, Delete, EditPen, Plus, QuestionFilled } from '@element-plus/icons-vue'
import { dataagentApi } from '@/api/dataagent'

const BASE_URL_PRESETS = [
  { label: 'Anthropic 官方（https://api.anthropic.com）', value: 'https://api.anthropic.com' },
  { label: 'OpenAI 官方（https://api.openai.com/v1）', value: 'https://api.openai.com/v1' },
  { label: 'OpenRouter（https://openrouter.ai/api）', value: 'https://openrouter.ai/api' },
  { label: 'DeepSeek（https://api.deepseek.com）', value: 'https://api.deepseek.com' },
  { label: 'SiliconFlow 硅基流动（https://api.siliconflow.cn/v1）', value: 'https://api.siliconflow.cn/v1' },
  { label: '阿里云百炼 DashScope（https://dashscope.aliyuncs.com/compatible-mode/v1）', value: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { label: '智谱 BigModel（https://open.bigmodel.cn/api/paas/v4）', value: 'https://open.bigmodel.cn/api/paas/v4' },
  { label: 'AnyRouter（https://a-ocnfniawgw.cn-shanghai.fcapp.run）', value: 'https://a-ocnfniawgw.cn-shanghai.fcapp.run' },
  { label: '本地 Ollama（http://localhost:11434/v1）', value: 'http://localhost:11434/v1' },
  { label: '本地 vLLM（http://localhost:8000/v1）', value: 'http://localhost:8000/v1' }
]

const loading = ref(false)
const savingProviderId = ref('')
const providers = ref([])
const selectedProviderId = ref('')
const customModelInput = ref('')
const providerNameInput = ref(null)
const advancedOpenMap = reactive({})

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

const normalizeModelItems = (rawModels = []) => {
  if (!Array.isArray(rawModels)) return []
  return rawModels.map((item) => {
    if (typeof item === 'string') {
      return { id: item, max_output_tokens: null, context_window: null }
    }
    if (item && typeof item === 'object') {
      return {
        id: item.id || item.model_id || '',
        max_output_tokens: item.max_output_tokens ?? null,
        context_window: item.context_window ?? null
      }
    }
    return null
  }).filter((item) => item && item.id)
}

const getModelIds = (list = []) => {
  if (!Array.isArray(list)) return []
  return list.map((m) => (typeof m === 'string' ? m : m?.id || m?.model_id || '')).filter(Boolean)
}

const buildProviderDraft = (provider) => {
  const customModels = uniqueStrings(provider.custom_models || [])
  const modelDetections = normalizeDetections(provider.model_detections || {})
  const normalizedModels = normalizeModelItems(provider.models || [])
  const rawModelIds = getModelIds(provider.models || [])
  const supportedModelIds = uniqueStrings([
    ...(provider.supported_models || []),
    ...customModels,
    ...rawModelIds,
    ...normalizedModels.map((m) => m.id)
  ])

  // Build model objects map
  const modelDetails = {}
  normalizedModels.forEach((m) => {
    modelDetails[m.id] = {
      max_output_tokens: m.max_output_tokens,
      context_window: m.context_window
    }
  })
  supportedModelIds.forEach((modelId) => {
    if (!modelDetails[modelId]) {
      modelDetails[modelId] = {
        max_output_tokens: null,
        context_window: null
      }
    }
  })

  const enabledModelIds = provider.enabled_models
    ? getModelIds(provider.enabled_models)
    : (rawModelIds.length ? rawModelIds : uniqueStrings(provider.models || []))

  return {
    provider_id: provider.provider_id,
    name: provider.name || provider.display_name || provider.provider_id,
    provider_group: provider.provider_group || provider.group || '其他',
    provider_enabled: Boolean(provider.provider_enabled || provider.enabled),
    token: '',
    base_url: provider.base_url || '',
    supports_partial_messages: provider.supports_partial_messages !== false,
    enabled_models: uniqueStrings(enabledModelIds),
    custom_models: customModels,
    base_supported_models: supportedModelIds.filter((id) => !customModels.includes(id)),
    model_details: modelDetails,
    model_detections: modelDetections,
    is_new: Boolean(provider.is_new)
  }
}

const buildProviderSnapshot = (draft) => {
  const modelDetections = normalizeDetections(draft?.model_detections)
  const enabledModels = uniqueStrings(draft?.enabled_models)
  return {
    name: String(draft?.name || '').trim(),
    provider_enabled: Boolean(draft?.provider_enabled),
    token: String(draft?.token || '').trim(),
    base_url: String(draft?.base_url || '').trim(),
    supports_partial_messages: draft?.supports_partial_messages !== false,
    enabled_models: enabledModels,
    custom_models: uniqueStrings(draft?.custom_models),
    model_details: JSON.parse(JSON.stringify(draft?.model_details || {})),
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

const credentialLabel = (providerId) => (providerId === 'anthropic' ? 'API Key' : 'API Key / Token')
const credentialPlaceholder = (providerId) => (providerId === 'anthropic' ? '留空保持现有 API Key' : '输入访问凭证，留空保持现有配置')

const groupedProviders = computed(() => {
  const groups = new Map()
  providers.value.forEach((provider) => {
    const groupName = provider.provider_group || provider.group || '其他'
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

const canDeleteCurrentProvider = computed(() => {
  if (!currentProvider.value) return false
  return currentDraft.value?.is_new || currentProvider.value.provider_group === '自定义供应商' || providers.value.length > 1
})

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
    name: '',
    provider_enabled: false,
    enabled_models: [],
    custom_models: [],
    base_supported_models: [],
    token: '',
    base_url: '',
    supports_partial_messages: true,
    model_details: {},
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

const currentModelRows = computed(() => {
  if (!currentDraft.value) return []
  return currentSupportedModels.value.map((id) => {
    return {
      id,
      details: currentDraft.value.model_details[id]
    }
  })
})

const isAdvancedOpen = (modelId) => Boolean(advancedOpenMap[`${currentProviderId.value}::${modelId}`])

const toggleAdvanced = (modelId) => {
  const key = `${currentProviderId.value}::${modelId}`
  advancedOpenMap[key] = !advancedOpenMap[key]
}

const modelDetection = (model) => {
  return currentDraft.value?.model_detections?.[model] || {
    status: 'unverified',
    message: '待检测',
    checked_at: ''
  }
}

const detectionError = (model) => {
  const message = String(modelDetection(model).message || '模型检测失败').trim()
  return message.startsWith('连接失败') ? message : `连接失败：${message}`
}

const modelDetectionTitle = (model) => {
  const detection = modelDetection(model)
  if (detection.status === 'verified') return '模型连接正常，点击重新检测'
  if (detection.status === 'failed') return detectionError(model)
  return '检测模型连接'
}

const formatContextWindow = (value) => {
  const amount = Number(value)
  if (!Number.isFinite(amount) || amount <= 0) return ''
  if (amount >= 1000000) return `${Number((amount / 1000000).toFixed(1))}M`
  if (amount >= 1000) return `${Math.round(amount / 1000)}K`
  return String(amount)
}

const providerHasCredential = (provider, draft) => {
  const typed = String(draft?.token || '').trim()
  if (typed) return true
  if (provider.provider_id === 'anthropic') return Boolean(provider.api_key_set)
  return Boolean(provider.auth_token_set || provider.api_key_set)
}

const providerBaseUrlReady = (provider, draft) => {
  if (provider.provider_id === 'anthropic_compatible' || draft?.is_new) {
    return Boolean(String(draft?.base_url || '').trim())
  }
  return true
}

const providerPreview = (provider) => {
  const draft = providerDrafts[provider.provider_id]
  if (!draft) {
    return {
      status: provider.validation_status || 'unverified',
      message: provider.validation_message || '待配置',
      providerEnabled: Boolean(provider.provider_enabled || provider.enabled),
      enabled: Boolean(provider.enabled),
      enabledModels: uniqueStrings(provider.models?.map?.((m) => (typeof m === 'string' ? m : m.id)) || provider.models || [])
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
      message: provider.provider_id === 'anthropic' ? '请填写 API Key' : '请填写 Token / API Key',
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

const focusProviderName = async () => {
  await nextTick()
  providerNameInput.value?.focus?.()
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
    let providerList = null
    if (typeof dataagentApi.listProviders === 'function') {
      try {
        const res = await dataagentApi.listProviders()
        if (res && Array.isArray(res.providers)) {
          providerList = res.providers
        } else if (Array.isArray(res)) {
          providerList = res
        }
      } catch {
        // Continue to getSettings
      }
    }

    const payload = await dataagentApi.getSettings()
    if (providerList && providerList.length) {
      payload.providers = providerList
    }
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

const addNewProvider = () => {
  const newId = `custom_provider_${Date.now()}`
  const newProvider = {
    provider_id: newId,
    name: '新建供应商',
    display_name: '新建供应商',
    provider_group: '自定义供应商',
    base_url: '',
    token: '',
    auth_token_set: false,
    api_key_set: false,
    provider_enabled: true,
    enabled: true,
    supports_partial_messages: true,
    models: [],
    supported_models: [],
    custom_models: [],
    model_detections: {},
    is_new: true
  }

  providers.value.push(newProvider)
  const draft = buildProviderDraft(newProvider)
  providerDrafts[newId] = draft
  providerSnapshots[newId] = buildProviderSnapshot(draft)
  selectedProviderId.value = newId
}

const addCustomModel = () => {
  if (!currentDraft.value) return
  const model = String(customModelInput.value || '').trim()
  if (!model) return
  currentDraft.value.custom_models = uniqueStrings([...(currentDraft.value.custom_models || []), model])
  if (!currentDraft.value.model_details) {
    currentDraft.value.model_details = {}
  }
  if (!currentDraft.value.model_details[model]) {
    currentDraft.value.model_details[model] = {
      max_output_tokens: null,
      context_window: null
    }
  }
  if (!currentDraft.value.model_detections[model]) {
    currentDraft.value.model_detections[model] = {
      status: 'unverified',
      message: '待检测',
      checked_at: ''
    }
  }
  customModelInput.value = ''
}

const removeModel = (model) => {
  if (!currentDraft.value) return
  currentDraft.value.custom_models = currentDraft.value.custom_models.filter((item) => item !== model)
  currentDraft.value.base_supported_models = currentDraft.value.base_supported_models.filter((item) => item !== model)
  currentDraft.value.enabled_models = currentDraft.value.enabled_models.filter((item) => item !== model)
  delete currentDraft.value.model_detections[model]
  if (currentDraft.value.model_details) {
    delete currentDraft.value.model_details[model]
  }
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
  const modelsWithDetails = supportedModelsFor(providerId).map((id) => {
    const detail = draft.model_details?.[id] || {}
    return {
      id,
      max_output_tokens: detail.max_output_tokens ?? null,
      context_window: detail.context_window ?? null
    }
  })

  const payload = {
    provider_id: providerId,
    name: draft.name || provider?.display_name || providerId,
    provider_enabled: Boolean(draft.provider_enabled),
    base_url: draft.base_url,
    supports_partial_messages: draft.supports_partial_messages !== false,
    enabled_models: enabledModels,
    custom_models: uniqueStrings(draft.custom_models),
    models: modelsWithDetails,
    model_detections: normalizeDetections(draft.model_detections)
  }
  const token = String(draft.token || '').trim()
  if (token) {
    payload.api_key = token
    payload.auth_token = token
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
    const providerPayload = buildProviderPayload(providerId)

    if (currentDraft.value?.is_new) {
      if (typeof dataagentApi.createProvider === 'function') {
        try {
          await dataagentApi.createProvider(providerPayload)
        } catch {
          // Fallback if needed
        }
      }
      currentDraft.value.is_new = false
    } else {
      if (typeof dataagentApi.updateProvider === 'function') {
        try {
          await dataagentApi.updateProvider(providerId, providerPayload)
        } catch {
          // Fallback if needed
        }
      }
    }

    const payload = {
      providers: [providerPayload]
    }
    if (shouldPersistSelectionWithProvider(providerId)) {
      payload.provider_id = form.provider_id || ''
      payload.model = form.model || ''
    }
    const saved = await dataagentApi.updateSettings(payload)
    applySavedProvider(saved, providerId)
    ElMessage.success('供应商配置已保存')
  } catch (error) {
    ElMessage.error(error?.message || '保存失败，请重试')
  } finally {
    savingProviderId.value = ''
  }
}

const deleteCurrentProvider = async () => {
  if (!currentProvider.value) return
  const providerId = currentProvider.value.provider_id
  try {
    await ElMessageBox.confirm(
      `确定要删除供应商「${currentDraft.value?.name || providerId}」吗？`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  try {
    if (typeof dataagentApi.deleteProvider === 'function') {
      await dataagentApi.deleteProvider(providerId)
    }
    providers.value = providers.value.filter((item) => item.provider_id !== providerId)
    delete providerDrafts[providerId]
    delete providerSnapshots[providerId]
    selectedProviderId.value = providers.value[0]?.provider_id || ''
    ElMessage.success('供应商已删除')
  } catch (error) {
    ElMessage.error(error?.message || '删除供应商失败')
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
/* Hallmark · pre-emit critique: P5 H4 E5 S5 R5 V4 */
/* Hallmark · macrostructure: Workbench · tone: utilitarian · anchor hue: blue */
.dataagent-config {
  --settings-ink: #172033;
  --settings-muted: #64748b;
  --settings-muted-light: #94a3b8;
  --settings-rule: #e2e8f0;
  --settings-rule-soft: #eef2f7;
  --settings-rule-strong: #cbd5e1;
  --settings-paper: #fdfefe;
  --settings-paper-muted: #f8fafc;
  --settings-paper-hover: #f1f5f9;
  --settings-paper-panel: #fbfcfe;
  --settings-accent: #1f5f99;
  --settings-accent-hover: #2c74b8;
  --settings-accent-active: #184d7d;
  --settings-success: #17834d;
  --settings-success-dot: #22a861;
  --settings-success-paper: #e8f7ef;
  --settings-warning: #d89a28;
  --settings-danger: #b42318;
  --settings-danger-dot: #d14343;
  --settings-danger-rule: #efb5b0;
  --settings-danger-paper: #fff4f3;
  --settings-focus: #2563eb;
  --settings-ease-out: cubic-bezier(0.16, 1, 0.3, 1);
  min-width: 0;
  color: var(--settings-ink);
}

.provider-workbench {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  min-width: 0;
  min-height: 640px;
  border: 1px solid var(--settings-rule);
  border-radius: 8px;
  background: var(--settings-paper);
  overflow: hidden;
}

.provider-nav {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 20px 16px;
  border-right: 1px solid var(--settings-rule);
  background: var(--settings-paper-muted);
}

.provider-nav-body {
  flex: 1 1 auto;
}

.provider-nav-footer {
  margin-top: 20px;
}

.add-provider-btn {
  width: 100%;
}

.provider-group + .provider-group {
  margin-top: 20px;
}

.provider-group-title {
  margin: 0 10px 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--settings-muted);
}

.provider-card {
  width: 100%;
  margin: 0 0 2px;
  padding: 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  text-align: left;
  transition: border-color 160ms var(--settings-ease-out), background 160ms var(--settings-ease-out);
  cursor: pointer;
}

@media (hover: hover) and (pointer: fine) {
  .provider-card:hover {
    background: var(--settings-paper-hover);
  }
}

.provider-card.active {
  border-color: var(--settings-rule-strong);
  background: var(--settings-paper);
}

.provider-card:active {
  background: var(--settings-rule-soft);
}

.provider-card:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.provider-card:focus-visible {
  outline: 2px solid var(--settings-focus);
  outline-offset: 2px;
}

.provider-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.provider-card-name {
  min-width: 0;
  overflow: hidden;
  color: var(--settings-ink);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.provider-dot {
  flex: 0 0 auto;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--settings-muted-light);
}

.provider-dot.is-verified {
  background: var(--settings-success-dot);
}

.provider-dot.is-pending {
  background: var(--settings-warning);
}

.provider-dot.is-invalid {
  background: var(--settings-danger-dot);
}

.provider-detail {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 28px;
  padding: 24px;
}

.provider-titlebar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--settings-rule);
}

.provider-title-main {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.provider-titlebar h3 {
  min-width: 0;
  margin: 0;
  color: var(--settings-ink);
  font-size: 20px;
  font-weight: 700;
  overflow-wrap: anywhere;
}

.provider-title-main :deep(.el-button) {
  margin-left: 0;
}

.provider-enabled-pill {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 9px;
  border-radius: 999px;
  background: var(--settings-paper-hover);
  color: var(--settings-muted);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.provider-enabled-pill.is-enabled {
  background: var(--settings-success-paper);
  color: var(--settings-success);
}

.provider-title-actions {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  flex: none;
}

.provider-title-actions :deep(.el-button--primary) {
  --el-button-bg-color: var(--settings-accent);
  --el-button-border-color: var(--settings-accent);
  --el-button-hover-bg-color: var(--settings-accent-hover);
  --el-button-hover-border-color: var(--settings-accent-hover);
  --el-button-active-bg-color: var(--settings-accent-active);
  --el-button-active-border-color: var(--settings-accent-active);
}

.service-section {
  min-width: 0;
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
  color: var(--settings-ink);
}

.section-subtitle {
  margin-top: 4px;
  font-size: 13px;
  color: var(--settings-muted);
}

.field-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.field-label .el-icon {
  color: var(--settings-muted);
}

.provider-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.full-width {
  width: 100%;
}

.model-heading {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  gap: 12px;
}

.model-toolbar {
  min-width: 0;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.default-model-control {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--settings-muted);
  font-size: 12px;
  white-space: nowrap;
}

.custom-model-row {
  display: grid;
  grid-template-columns: minmax(160px, 220px) auto;
  gap: 8px;
}

.model-list {
  border-top: 1px solid var(--settings-rule);
}

.model-card {
  border-bottom: 1px solid var(--settings-rule);
  transition: background 150ms var(--settings-ease-out);
}

@media (hover: hover) and (pointer: fine) {
  .model-card:hover {
    background: var(--settings-paper-muted);
  }
}

.model-card.is-disabled .model-id {
  color: var(--settings-muted-light);
}

.model-row-main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  min-height: 58px;
  padding: 10px 4px 10px 12px;
}

.model-name-cell {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  overflow: hidden;
}

.model-id {
  min-width: 0;
  font-weight: 600;
  font-size: 14px;
  color: var(--settings-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-ops-cell {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  flex-shrink: 0;
}

.model-context-badge {
  flex: 0 0 auto;
  padding: 2px 7px;
  border: 1px solid var(--settings-rule-strong);
  border-radius: 999px;
  color: var(--settings-muted);
  font-size: 11px;
  line-height: 18px;
  white-space: nowrap;
}

.model-icon-button {
  width: 32px;
  padding: 0;
  color: var(--settings-muted);
}

.model-icon-button.is-verified {
  color: var(--settings-success);
  background: transparent;
  border: none;
}

.model-error-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0 12px 12px;
  padding: 9px 10px;
  border: 1px solid var(--settings-danger-rule);
  border-radius: 6px;
  background: var(--settings-danger-paper);
  color: var(--settings-danger);
  font-size: 13px;
  line-height: 1.45;
}

.model-advanced-pane {
  padding: 16px 12px 0;
  border-top: 1px solid var(--settings-rule-soft);
  background: var(--settings-paper-panel);
}

.model-enabled-control {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.model-enabled-title {
  color: var(--settings-ink);
  font-size: 13px;
  font-weight: 600;
}

.model-enabled-desc {
  margin-top: 3px;
  color: var(--settings-muted);
  font-size: 12px;
  line-height: 1.45;
}

.empty-block {
  padding: 24px;
  border: 1px dashed var(--settings-rule-strong);
  border-radius: 8px;
  color: var(--settings-muted);
  text-align: center;
}

.default-model-select {
  width: 220px;
}

@media (max-width: 1100px) {
  .provider-workbench {
    grid-template-columns: 1fr;
  }

  .provider-nav {
    border-right: none;
    border-bottom: 1px solid var(--settings-rule);
  }
}

@media (max-width: 768px) {
  .provider-titlebar,
  .section-heading {
    flex-direction: column;
    align-items: stretch;
  }

  .provider-detail {
    padding: 18px 14px;
  }

  .provider-title-actions {
    width: 100%;
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .model-toolbar,
  .default-model-control {
    width: 100%;
    align-items: stretch;
    flex-direction: column;
  }

  .custom-model-row {
    grid-template-columns: 1fr;
  }

  .default-model-select {
    width: 100%;
  }

  .model-error-row {
    align-items: stretch;
    flex-direction: column;
  }
}

@media (prefers-reduced-motion: reduce) {
  .provider-card,
  .model-card {
    transition-duration: 0ms;
  }
}
</style>
