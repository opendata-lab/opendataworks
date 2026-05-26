<template>
  <div class="query-workbench">
    <aside class="query-sidebar">
      <div class="query-sidebar-head">
        <el-select
          v-model="agentSelectValue"
          class="query-agent-select"
          :disabled="!agents.length"
          @change="handleAgentChange"
        >
          <template #prefix>
            <svg class="query-agent-select-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2V4" />
              <rect x="4" y="6" width="16" height="12" rx="2" />
              <circle cx="9" cy="12" r="1.5" fill="currentColor" />
              <circle cx="15" cy="12" r="1.5" fill="currentColor" />
              <path d="M9 16c1.5 1 4.5 1 6 0" />
            </svg>
          </template>
          <el-option
            v-for="agent in agents"
            :key="agent.agent_id"
            :label="agent.name"
            :value="agent.agent_id"
          />
        </el-select>
        <button class="query-btn-new" @click="handleNewTopic">新建</button>
      </div>

      <div class="query-sidebar-search">
        <input
          v-model="searchKeyword"
          class="query-search-input"
          type="text"
          placeholder="搜索话题"
        >
      </div>

      <el-scrollbar class="query-session-scroll">
        <div class="query-session-list">
          <button
            v-for="topic in filteredTopics"
            :key="topic.topic_id"
            class="query-session-item"
            :class="{ active: topic.topic_id === activeTopicId }"
            @click="handleSelectTopic(topic.topic_id)"
          >
            <span class="query-session-title">{{ topic.title || '新话题' }}</span>
            <span v-if="isTopicWorking(topic)" class="query-session-loading" title="正在分析中...">
              <svg class="query-session-spinner" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                <circle class="query-session-spinner-track" cx="12" cy="12" r="10" stroke-width="3" />
                <path class="query-session-spinner-head" d="M12 2a10 10 0 0 1 10 10" stroke-width="3" stroke-linecap="round" />
              </svg>
            </span>
            <span v-else class="query-session-meta">{{ formatTime(topic.updated_at || topic.created_at) }}</span>
          </button>
          <div v-if="!filteredTopics.length" class="query-empty-sessions">暂无话题</div>
        </div>
      </el-scrollbar>
    </aside>

    <main class="query-main">
      <div class="query-main-top-bar">
        <div class="query-topic-info">
          <h4 class="query-topic-title">{{ activeTopic ? activeTopic.title : '开始一次新的数据分析' }}</h4>
        </div>

        <div class="query-current-agent">
          <svg class="query-current-agent-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 2V4" />
            <rect x="4" y="6" width="16" height="12" rx="2" />
            <circle cx="9" cy="12" r="1.5" fill="currentColor" />
            <circle cx="15" cy="12" r="1.5" fill="currentColor" />
            <path d="M9 16c1.5 1 4.5 1 6 0" />
          </svg>
          <span class="query-current-agent-name">{{ activeAgent?.name || '默认助手' }}</span>
        </div>
      </div>

      <el-scrollbar ref="messagesScrollbarRef" class="query-messages" @scroll="handleScroll">
        <div class="query-messages-inner">

          <div v-if="!settings.providers.length" class="query-config-empty">
            <div class="query-config-empty-title">还没有可用的智能问数模型</div>
            <div class="query-config-empty-text">请先完成模型配置。</div>
          </div>

          <div v-if="!activeMessages.length" class="query-empty">
            <div class="query-empty-mark">AI</div>
            <div class="query-empty-title">请输入你的数据问题</div>
            <div class="query-empty-subtitle">支持数据查询、趋势分析与结果可视化。</div>
            <div class="query-suggestions">
              <button
                v-for="suggestion in suggestions"
                :key="suggestion"
                class="query-suggestion"
                @click="handleSuggestion(suggestion)"
              >
                {{ suggestion }}
              </button>
            </div>
          </div>

          <template v-for="msg in activeMessages" :key="msg.id">
            <div v-if="msg.role === 'user'" class="query-message-row query-message-user">
              <div class="query-user-message-shell">
                <div class="query-user-bubble">{{ msg.content }}</div>
                <div class="query-message-footer query-message-footer-user">
                  <span v-if="formatMessageTime(msg.created_at)" class="query-message-time">{{ formatMessageTime(msg.created_at) }}</span>
                  <button type="button" class="query-message-tool query-message-copy" title="复制" aria-label="复制消息" @click.stop="handleCopyMessage(msg)">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><rect x="9" y="9" width="10" height="10" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v1" /></svg>
                  </button>
                </div>
              </div>
            </div>

            <div v-else class="query-message-row query-message-assistant">
              <div class="query-assistant-body">
                <div
                  v-if="hasProcessPanel(msg)"
                  class="query-process-panel"
                >
                  <div class="query-process-summary-row">
                    <button type="button" class="query-process-summary" @click.stop="toggleProcessPanel(msg)">
                      <span class="query-process-summary-label">
                        <span v-if="isActiveTaskStatus(msg.status)" class="query-process-badge-dot" />
                        深度思考
                      </span>
                      <span v-if="!isProcessPanelExpanded(msg) && processSummaryPreview(msg)" class="query-process-summary-preview">{{ processSummaryPreview(msg) }}</span>
                      <svg class="query-process-chevron" :class="{ open: isProcessPanelExpanded(msg) }" viewBox="0 0 24 24" fill="none" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M19 9l-7 7-7-7" /></svg>
                    </button>
                  </div>

                  <el-scrollbar
                    v-if="isProcessPanelExpanded(msg)"
                    class="query-process-content"
                    max-height="min(420px, 52vh)"
                  >
                    <div class="query-process-content-inner">
                      <div v-if="processPlaceholder(msg)" class="query-process-placeholder">
                        <span class="query-process-placeholder-text">{{ processPlaceholder(msg)?.text }}</span>
                        <span v-if="processPlaceholder(msg)?.preview" class="query-process-placeholder-preview">{{ processPlaceholder(msg)?.preview }}</span>
                        <span class="query-loading-dots">
                          <span>.</span>
                          <span>.</span>
                          <span>.</span>
                        </span>
                      </div>

                      <div v-for="block in processBlocksForMessage(msg)" :key="block.id" class="query-step-row">
                        <div v-if="block.kind === 'thinking' && block.text" class="query-process-thought">
                          <div class="query-process-thought-content" v-html="renderMarkdown(block.text)"></div>
                          <span v-if="msg.status === 'streaming' && block.status === 'streaming'" class="query-cursor">|</span>
                        </div>

                        <ToolOutputRenderer v-else-if="block.kind === 'tool' && block.tool" :tool="block.tool" />
                      </div>
                    </div>
                  </el-scrollbar>
                </div>

                <div v-for="block in finalBlocksForMessage(msg)" :key="block.id" class="query-step-row">
                  <template v-if="block.kind === 'main_text'">
                    <div v-if="displayTextBlock(block, msg)" class="query-main-text">
                      <div v-html="renderMarkdown(displayTextBlock(block, msg))"></div>
                      <span v-if="msg.status === 'streaming' && block.status === 'streaming'" class="query-cursor">|</span>
                    </div>
                  </template>

                  <div v-else-if="block.kind === 'error' && block.text" class="query-error-card">
                    <span class="query-error-label">错误</span>
                    <span>{{ block.text }}</span>
                  </div>

                  <div v-else-if="block.kind === 'tool' && block.tool" class="query-final-chart">
                    <ToolOutputRenderer :tool="block.tool" />
                  </div>
                </div>

                <div v-if="msg.citations.length" class="query-citations">
                  <a
                    v-for="(citation, index) in msg.citations"
                    :key="index"
                    :href="citation.url || '#'"
                    target="_blank"
                    rel="noopener"
                    class="query-citation-chip"
                  >
                    <span class="query-citation-index">{{ index + 1 }}</span>
                    <span>{{ citation.title || citation.url || '来源' }}</span>
                  </a>
                </div>

                <div v-if="msg.error && !hasErrorBlock(msg)" class="query-error-card">
                  <span class="query-error-label">错误</span>
                  <span>{{ errorMessage(msg.error) }}</span>
                </div>

                <div class="query-message-footer query-message-footer-assistant">
                  <span v-if="formatMessageTime(msg.created_at)" class="query-message-time">{{ formatMessageTime(msg.created_at) }}</span>
                  <button type="button" class="query-message-tool query-message-copy" title="复制" aria-label="复制消息" @click.stop="handleCopyMessage(msg)">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><rect x="9" y="9" width="10" height="10" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v1" /></svg>
                  </button>
                  <button type="button" class="query-message-tool query-message-feedback query-message-feedback-like" :class="{ active: msg.feedback === 'like' }" title="有帮助" aria-label="有帮助" @click.stop="toggleMessageFeedback(msg, 'like')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="M7 11v10H4a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2h3Z" /><path d="M7 11 12 2a3 3 0 0 1 3 3v4h4a2 2 0 0 1 2 2l-1 8a2 2 0 0 1-2 2H7" /></svg>
                  </button>
                  <button type="button" class="query-message-tool query-message-feedback query-message-feedback-dislike" :class="{ active: msg.feedback === 'dislike' }" title="没帮助" aria-label="没帮助" @click.stop="toggleMessageFeedback(msg, 'dislike')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="M17 13V3h3a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-3Z" /><path d="M17 13 12 22a3 3 0 0 1-3-3v-4H5a2 2 0 0 1-2-2l1-8a2 2 0 0 1 2-2h11" /></svg>
                  </button>
                </div>

                <div v-if="shouldShowFollowupForMessage(msg)" class="query-followup-suggestions">
                  <div class="query-followup-label">
                    <svg class="query-followup-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                      <circle cx="12" cy="12" r="10" />
                      <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
                      <line x1="12" y1="17" x2="12.01" y2="17" />
                    </svg>
                    猜你想问
                  </div>
                  <div class="query-followup-list">
                    <button
                      v-for="suggestion in followupSuggestions"
                      :key="suggestion"
                      type="button"
                      class="query-followup-suggestion"
                      @click="handleSuggestion(suggestion)"
                    >
                      {{ suggestion }}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </template>
        </div>
      </el-scrollbar>

      <div class="query-composer-wrap">
        <div class="query-composer">
          <div class="query-composer-textarea-wrap">
            <textarea
              v-model="inputText"
              class="query-textarea"
              rows="2"
              :disabled="!settings.providers.length || !availableModels.length"
              placeholder="例如：查询最近 30 天工作流发布次数趋势"
              @keydown.enter.exact.prevent="handleSend"
              @keydown.ctrl.enter.prevent="handleSend"
              @keydown.meta.enter.prevent="handleSend"
            />
          </div>

          <div class="query-composer-bottom-bar">
            <div class="query-composer-left-actions">
              <el-tooltip content="上传文件 (预留)" placement="top">
                <button
                  type="button"
                  class="query-composer-attach-btn"
                  aria-label="上传文件"
                >
                  <svg class="query-composer-attach-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="12" y1="5" x2="12" y2="19" />
                    <line x1="5" y1="12" x2="19" y2="12" />
                  </svg>
                </button>
              </el-tooltip>
            </div>

            <div class="query-composer-right-actions">
              <el-dropdown trigger="click" @command="handleModelSelectCommand">
                <div class="query-model-selector-trigger" :class="{ disabled: !settings.providers.length }">
                  <span class="query-model-selector-name">{{ selectedModel || '选择模型' }}</span>
                  <svg class="query-chevron-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M6 9l6 6 6-6" />
                  </svg>
                </div>
                <template #dropdown>
                  <el-dropdown-menu class="query-model-dropdown-menu">
                    <template v-for="provider in settings.providers" :key="provider.provider_id">
                      <div class="query-dropdown-group-title">{{ provider.display_name }}</div>
                      <el-dropdown-item
                        v-for="model in getProviderModels(provider)"
                        :key="provider.provider_id + '::' + model"
                        :command="provider.provider_id + '::' + model"
                        :class="{ active: selectedProvider === provider.provider_id && selectedModel === model }"
                      >
                        {{ model }}
                      </el-dropdown-item>
                    </template>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>

              <el-tooltip :content="contextWindowTooltip" placement="top">
                <button
                  type="button"
                  class="query-context-ring-wrap"
                  :class="{ 'is-empty': !contextWindowUsage.available }"
                  :aria-label="contextWindowTooltip"
                  title="上下文窗口使用情况"
                >
                  <svg class="query-context-ring" viewBox="0 0 36 36" aria-hidden="true">
                    <circle class="query-context-ring-track" cx="18" cy="18" r="16.5" pathLength="100" />
                    <circle
                      class="query-context-ring-value"
                      cx="18"
                      cy="18"
                      r="16.5"
                      pathLength="100"
                      :class="contextRingColorClass"
                      :stroke-dasharray="contextRingDashArray"
                    />
                  </svg>
                  <span class="query-context-ring-text">{{ contextWindowUsage.available ? contextWindowUsage.percentLabel : '--' }}</span>
                </button>
              </el-tooltip>

              <button
                type="button"
                class="query-composer-action"
                :class="[
                  composerActionMode === 'cancel' ? 'query-btn-cancel' : 'query-btn-send'
                ]"
                :disabled="composerActionDisabled"
                :aria-label="composerActionTitle"
                :title="composerActionTitle"
                @click="handleComposerAction"
              >
                <svg
                  v-if="composerActionMode === 'cancel'"
                  class="query-composer-action-icon"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <rect x="8" y="8" width="8" height="8" rx="1.5" />
                </svg>
                <svg
                  v-else
                  class="query-composer-action-icon"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M5 12h12" />
                  <path d="M13 6l6 6-6 6" />
                </svg>
              </button>
            </div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, triggerRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import { createNl2SqlApiClient } from '@/api/nl2sql'
import ToolOutputRenderer from './ToolOutputRenderer.vue'
import { parseChartSpec, stripChartSpecsFromText } from './chartSpec'
import { describeToolAction, extractToolSkillName, formatSkillBootstrapLabel, isSkillBootstrapPlaceholder } from './toolPresentation'
import {
  createAssistantMessageState,
  hydrateAssistantMessageState,
  processAssistantStreamEvent
} from './messageStream'

marked.setOptions({ breaks: true, gfm: true })

const api = createNl2SqlApiClient({ timeout: 300000 })
const { topicApi, taskApi, adminApi, agentApi } = api
const route = useRoute()
const router = useRouter()

const topics = ref([])
const agents = ref([])
const activeTopicId = ref('')
const activeTopicSnapshot = ref(null)
const selectedAgentId = ref('')
const inputText = ref('')
const searchKeyword = ref('')
const messagesScrollbarRef = ref(null)
const autoScroll = ref(true)
const hydratedIds = new Set()
const taskSubscriptions = new Map()
const pendingSubmitKeys = ref(new Set())
const followupSuggestionsByMessage = ref({})
const followupSuggestionStateByMessage = ref({})

const settings = reactive({
  default_provider_id: '',
  default_model: '',
  providers: []
})

const selectedProvider = ref(settings.default_provider_id)
const selectedModel = ref(settings.default_model)
const agentSelectionReady = ref(false)

const suggestions = [
  '各数据层表数量对比',
  '最近 30 天工作流发布次数趋势',
  '各工作流发布操作类型占比',
  '查看 dwd_tech_dev_inspection_rule_cnt_di 的上下游血缘'
]

const findListedTopic = (topicId) => topics.value.find((topic) => topic.topic_id === topicId) || null
const activeTopic = computed(() => (
  findListedTopic(activeTopicId.value)
  || (activeTopicSnapshot.value?.topic_id === activeTopicId.value ? activeTopicSnapshot.value : null)
))
const activeAgent = computed(() => {
  const topicAgentId = String(activeTopic.value?.agent_id || activeTopic.value?.agent?.agent_id || '').trim()
  if (topicAgentId) {
    return agents.value.find((agent) => agent.agent_id === topicAgentId) || activeTopic.value?.agent || null
  }
  return agents.value.find((agent) => agent.agent_id === selectedAgentId.value) || agents.value[0] || null
})
const activeConversationAgentId = computed(() => String(
  activeTopic.value?.agent_id
  || activeTopic.value?.agent?.agent_id
  || selectedAgentId.value
  || ''
).trim())
const activeMessages = computed(() => activeTopic.value?.messages || [])
const latestAssistantMessage = computed(() => [...activeMessages.value]
  .reverse()
  .find((msg) => msg?.role === 'assistant') || null)
const activeCancelableMessage = computed(() => [...activeMessages.value]
  .reverse()
  .find((msg) => msg?.role === 'assistant' && msg?.task_id && isActiveTaskStatus(msg?.status)) || null)
const filteredTopics = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase()
  if (!keyword) return topics.value
  return topics.value.filter((topic) => String(topic.title || '').toLowerCase().includes(keyword))
})

const activeProviderConfig = computed(() => {
  const list = Array.isArray(settings.providers) ? settings.providers : []
  return list.find((provider) => provider.provider_id === selectedProvider.value) || list[0] || null
})

const availableModels = computed(() => {
  const provider = activeProviderConfig.value
  const models = Array.isArray(provider?.models) ? [...provider.models] : []
  const fallbackModel = provider?.default_model || settings.default_model
  if (fallbackModel && !models.includes(fallbackModel)) {
    models.unshift(fallbackModel)
  }
  return models
})

const getProviderModels = (provider) => {
  const models = Array.isArray(provider?.models) ? [...provider.models] : []
  const fallbackModel = provider?.default_model || settings.default_model
  if (fallbackModel && !models.includes(fallbackModel)) {
    models.unshift(fallbackModel)
  }
  return models
}

const combinedModelKey = computed({
  get() {
    if (!selectedProvider.value || !selectedModel.value) {
      return ''
    }
    return `${selectedProvider.value}::${selectedModel.value}`
  },
  set(val) {
    if (!val) return
    const [providerId, ...modelParts] = val.split('::')
    const model = modelParts.join('::')
    selectedProvider.value = providerId
    selectedModel.value = model
  }
})

const handleModelSelectCommand = (command) => {
  if (!command) return
  const [providerId, ...modelParts] = command.split('::')
  const model = modelParts.join('::')
  selectedProvider.value = providerId
  selectedModel.value = model
}

const NEW_TOPIC_PENDING_KEY = '__new_topic__'

const normalizePendingTopicKey = (topicId) => String(topicId || NEW_TOPIC_PENDING_KEY)

const isTopicSubmitting = (topicId) => pendingSubmitKeys.value.has(normalizePendingTopicKey(topicId))

const isTopicWorking = (topic) => {
  if (!topic) return false
  if (isTopicSubmitting(topic.topic_id)) return true
  const msgs = topic.messages || []
  return msgs.some((msg) => msg && msg.role === 'assistant' && isActiveTaskStatus(msg.status))
}

const markTopicSubmitting = (topicId) => {
  const key = normalizePendingTopicKey(topicId)
  const next = new Set(pendingSubmitKeys.value)
  next.add(key)
  pendingSubmitKeys.value = next
  return key
}

const moveTopicSubmitting = (fromTopicId, toTopicId) => {
  const fromKey = normalizePendingTopicKey(fromTopicId)
  const toKey = normalizePendingTopicKey(toTopicId)
  const next = new Set(pendingSubmitKeys.value)
  next.delete(fromKey)
  next.add(toKey)
  pendingSubmitKeys.value = next
  return toKey
}

const clearTopicSubmitting = (key) => {
  const next = new Set(pendingSubmitKeys.value)
  next.delete(String(key || ''))
  pendingSubmitKeys.value = next
}

const activeTopicSubmitting = computed(() => isTopicSubmitting(activeTopicId.value))
const composerActionMode = computed(() => (activeCancelableMessage.value ? 'cancel' : 'send'))
const composerActionTitle = computed(() => (composerActionMode.value === 'cancel' ? '取消当前任务' : '发送消息'))
const canSendMessage = computed(() => (
  Boolean(inputText.value.trim())
  && !activeTopicSubmitting.value
  && !activeCancelableMessage.value
  && Boolean(selectedProvider.value)
  && Boolean(selectedModel.value)
  && Boolean(activeConversationAgentId.value)
))
const composerActionDisabled = computed(() => (
  composerActionMode.value === 'cancel'
    ? !activeCancelableMessage.value
    : !canSendMessage.value
))

const tokenFormatter = new Intl.NumberFormat('en-US')

const parseTokenNumber = (value) => {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : 0
}

const formatTokenCount = (value) => tokenFormatter.format(Math.max(0, Math.round(Number(value) || 0)))

const estimateTextTokens = (value) => {
  const text = String(value || '').trim()
  if (!text) return 0
  return Math.max(1, Math.ceil(text.length / 4))
}

const estimateAssistantOutputTokens = (msg) => {
  if (!msg) return 0
  const directText = `${String(msg.thinkingText || '')}${String(msg.mainText || '')}`.trim()
  if (directText) return estimateTextTokens(directText)
  if (!Array.isArray(msg.renderBlocks)) return 0
  const blockText = msg.renderBlocks
    .filter((block) => block && ['thinking', 'main_text'].includes(block.kind))
    .map((block) => String(block.text || ''))
    .join('')
  return estimateTextTokens(blockText)
}

const getContextWindowLimit = (model) => {
  const text = String(model || '').toLowerCase()
  if (text.includes('claude-3') || text.includes('claude-opus') || text.includes('claude-sonnet') || text.includes('claude-haiku')) {
    return 200000
  }
  if (text.includes('gpt-4o')) return 128000
  if (text.includes('deepseek')) return 64000
  return 128000
}

const contextWindowUsage = computed(() => {
  const message = latestAssistantMessage.value
  const usage = message?.usage || message?.token_usage || null
  const inputTokens = parseTokenNumber(usage?.input_tokens)
  const actualOutputTokens = parseTokenNumber(usage?.output_tokens)
  const estimatedOutputTokens = actualOutputTokens ? 0 : estimateAssistantOutputTokens(message)
  const outputTokens = actualOutputTokens || estimatedOutputTokens
  const outputEstimated = !actualOutputTokens && estimatedOutputTokens > 0
  const summedTokens = inputTokens + outputTokens
  const reportedTotalTokens = parseTokenNumber(usage?.total_tokens)
  const totalTokens = Math.max(summedTokens, reportedTotalTokens)
  const limitTokens = getContextWindowLimit(selectedModel.value || message?.model || settings.default_model)
  if (!totalTokens) {
    return {
      available: false,
      inputTokens,
      outputTokens,
      outputEstimated: false,
      totalTokens: 0,
      limitTokens,
      percentage: 0,
      percentLabel: '--'
    }
  }
  const percentage = Math.min(100, Math.max(0, (totalTokens / limitTokens) * 100))
  const percentLabel = percentage > 0 && percentage < 1
    ? '<1%'
    : `${Math.round(percentage)}%`
  return {
    available: true,
    inputTokens,
    outputTokens,
    outputEstimated,
    totalTokens,
    limitTokens,
    percentage,
    percentLabel
  }
})

const contextRingDashArray = computed(() => `${contextWindowUsage.value.available ? contextWindowUsage.value.percentage : 0} 100`)

const contextRingColorClass = computed(() => {
  if (!contextWindowUsage.value.available) return 'is-muted'
  const pct = contextWindowUsage.value.percentage
  if (pct > 90) return 'is-danger'
  if (pct > 70) return 'is-warning'
  return 'is-primary'
})

const contextWindowTooltip = computed(() => {
  const usage = contextWindowUsage.value
  if (!usage.available) return '暂无 Token 用量'
  const inputText = usage.inputTokens ? formatTokenCount(usage.inputTokens) : '未知'
  const outputText = usage.outputTokens
    ? `${usage.outputEstimated ? '约 ' : ''}${formatTokenCount(usage.outputTokens)}`
    : '未知'
  return `上下文窗口使用情况：${formatTokenCount(usage.totalTokens)} / ${formatTokenCount(usage.limitTokens)} Tokens；输入：${inputText}；输出：${outputText}；占比：${usage.percentLabel}`
})

const truncate = (value, max) => {
  const text = String(value || '新话题')
  return text.length > max ? `${text.slice(0, max)}...` : text
}

const deriveTopicTitle = (value, max = 30) => {
  const text = String(value || '').trim()
  if (!text) return '新话题'
  return text.length > max ? `${text.slice(0, max)}...` : text
}

const DISPLAY_TIME_ZONE = 'Asia/Shanghai'

const parseDisplayDate = (value) => {
  const text = String(value || '').trim()
  if (!text) return null

  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(text)) {
    return new Date(`${text}+08:00`)
  }

  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(text)) {
    return new Date(text.replace(' ', 'T') + '+08:00')
  }

  const parsed = new Date(text)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

const formatInShanghai = (date, options) => new Intl.DateTimeFormat('zh-CN', {
  timeZone: DISPLAY_TIME_ZONE,
  ...options
}).format(date)

const formatTime = (value) => {
  const date = parseDisplayDate(value)
  if (!date) return ''
  const now = new Date()
  const dateKey = formatInShanghai(date, { year: 'numeric', month: '2-digit', day: '2-digit' })
  const nowKey = formatInShanghai(now, { year: 'numeric', month: '2-digit', day: '2-digit' })
  if (dateKey === nowKey) {
    const diffMs = now.getTime() - date.getTime()
    const diffSeconds = Math.floor(diffMs / 1000)
    const diffMinutes = Math.floor(diffSeconds / 60)
    if (diffSeconds < 60) return '刚刚'
    if (diffMinutes < 60) return `${diffMinutes}分钟前`
    return formatInShanghai(date, { hour: '2-digit', minute: '2-digit', hour12: false })
  }

  const [y, m, d] = dateKey.split('/').map(Number)
  const [ny, nm, nd] = nowKey.split('/').map(Number)
  const diffDays = Math.floor((new Date(ny, nm - 1, nd) - new Date(y, m - 1, d)) / 86400000)

  if (diffDays === 1) return '1天前'
  if (diffDays <= 7) return `${diffDays}天前`

  const dateYear = formatInShanghai(date, { year: 'numeric' })
  const nowYear = formatInShanghai(now, { year: 'numeric' })
  if (dateYear === nowYear) {
    return formatInShanghai(date, { month: '2-digit', day: '2-digit' })
  }
  return formatInShanghai(date, { year: 'numeric', month: '2-digit', day: '2-digit' })
}

const formatMessageTime = (value) => {
  const date = parseDisplayDate(value)
  if (!date) return ''
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: DISPLAY_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).formatToParts(date).reduce((result, part) => {
    if (part.type !== 'literal') result[part.type] = part.value
    return result
  }, {})
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}

const uid = () => `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

const cleanTextContent = (value) => {
  let text = String(value || '')
  text = text.replace(/Base directory for this skill:[\s\S]*?(?:ARGUMENTS:\s*[^\n]*\n?)/gi, '')
  text = text.replace(/^ARGUMENTS:\s*[^\n]*\n?/gm, '')
  return text.replace(/^\s+/, '')
}

const thinkingPreview = (block) => {
  const lines = String(block?.text || '')
    .split('\n')
    .map((line) => line.replace(/^[-*]\s*/, '').trim())
    .filter(Boolean)

  if (!lines.length) return ''
  const preview = lines[lines.length - 1]
  return preview.length > 38 ? `${preview.slice(0, 38)}...` : preview
}

const normalizeInlinePreview = (value) => String(value || '')
  .replace(/\s+/g, ' ')
  .trim()

const truncatePreviewEnd = (value, max) => {
  const text = normalizeInlinePreview(value)
  if (!text) return ''
  if (text.length <= max) return text
  return `${text.slice(0, Math.max(0, max - 3))}...`
}

const truncatePreviewMiddle = (value, max) => {
  const text = normalizeInlinePreview(value)
  if (!text) return ''
  if (text.length <= max) return text
  if (max <= 8) return truncatePreviewEnd(text, max)
  const head = Math.ceil((max - 3) * 0.62)
  const tail = Math.max(3, max - 3 - head)
  return `${text.slice(0, head)}...${text.slice(-tail)}`
}

const compactProcessPreview = (value, kind = '') => {
  const text = normalizeInlinePreview(value)
  if (!text) return ''
  return kind === 'shell'
    ? truncatePreviewMiddle(text, 84)
    : truncatePreviewEnd(text, 72)
}

const hasMeaningfulToolPayload = (value) => {
  if (value == null) return false
  if (typeof value === 'string') return Boolean(value.trim())
  if (Array.isArray(value)) return value.some((item) => hasMeaningfulToolPayload(item))
  if (typeof value === 'object') {
    return Object.values(value).some((item) => hasMeaningfulToolPayload(item))
  }
  return true
}

const shouldRenderToolBlock = (tool) => {
  if (!tool || typeof tool !== 'object') return false

  const action = describeToolAction(tool)
  if (!action.isTrace) return true

  const detail = String(
    action.preview
    || action.command
    || action.path
    || action.directory
    || action.pattern
    || action.description
    || ''
  ).trim()

  return Boolean(detail || hasMeaningfulToolPayload(tool.output))
}

const extractChartSpecFromToolOutput = (value) => {
  const parsed = parseChartSpec(value)
  if (parsed) return parsed
  if (Array.isArray(value)) {
    for (const item of value) {
      const itemParsed = extractChartSpecFromToolOutput(item)
      if (itemParsed) return itemParsed
    }
  }
  return null
}

const isChartBlock = (block) => block?.kind === 'tool'
  && block.tool
  && Boolean(extractChartSpecFromToolOutput(block.tool.output))

const renderBlocksForMessage = (msg) => (Array.isArray(msg?.renderBlocks) ? msg.renderBlocks : []).filter((block) => {
  if (!block || typeof block !== 'object') return false
  if (block.kind === 'tool') {
    if (!shouldRenderToolBlock(block.tool)) return false
    const name = String(block.tool?.name || '').toLowerCase()
    if (name === 'glob' && String(block.tool?.status || '') === 'success') return false
    return true
  }
  return ['thinking', 'main_text', 'error'].includes(String(block.kind || ''))
})

const markFollowupToolWithSkillContext = (blocks) => {
  let pendingSkillName = ''

  return blocks.reduce((result, block, index) => {
    if (block?.kind !== 'tool' || !block.tool) {
      result.push(block)
      return result
    }

    if (isSkillBootstrapPlaceholder(block.tool)) {
      const hasFollowingConcreteTool = blocks.slice(index + 1).some((nextBlock) => (
        nextBlock?.kind === 'tool'
        && nextBlock.tool
        && describeToolAction(nextBlock.tool).kind !== 'skill'
      ))

      if (hasFollowingConcreteTool) {
        pendingSkillName = extractToolSkillName(block.tool) || pendingSkillName
        return result
      }
    }

    if (pendingSkillName && describeToolAction(block.tool).kind !== 'skill') {
      block.tool._skillBootstrapName = pendingSkillName
      pendingSkillName = ''
    }

    result.push(block)
    return result
  }, [])
}

const processBlocksForMessage = (msg) => markFollowupToolWithSkillContext(renderBlocksForMessage(msg))
  .filter((block) => ['thinking', 'tool'].includes(block.kind) && !isChartBlock(block))

const finalBlocksForMessage = (msg) => renderBlocksForMessage(msg)
  .filter((block) => ['main_text', 'error'].includes(block.kind) || isChartBlock(block))

const displayTextBlock = (block, msg) => {
  const text = stripChartSpecsFromText(cleanTextContent(block?.text)).trim()
  if (!text) return ''
  return text
}

const visibleMessageText = (msg) => {
  if (msg?.role === 'user') return String(msg.content || '')
  const textBlocks = renderBlocksForMessage(msg)
    .filter((block) => block.kind === 'main_text')
    .map((block) => displayTextBlock(block, msg))
    .filter(Boolean)
  return textBlocks.join('\n\n') || String(msg?.content || '')
}

const handleCopyMessage = async (msg) => {
  const text = visibleMessageText(msg).trim()
  if (!text) return
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.left = '-9999px'
      ta.style.top = '-9999px'
      document.body.appendChild(ta)
      ta.focus()
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    ElMessage.success('已复制')
  } catch (_error) {
    ElMessage.error('复制失败，请手动复制')
  }
}

const toggleMessageFeedback = async (msg, value) => {
  if (!msg || typeof msg !== 'object') return
  const previousFeedback = String(msg.feedback || '')
  const nextFeedback = previousFeedback === value ? '' : value
  const topicId = String(msg.topic_id || activeTopicId.value || '')
  const messageId = String(msg.message_id || msg.id || '')

  msg.feedback = nextFeedback
  if (!topicId || !messageId) {
    msg.feedback = previousFeedback
    ElMessage.error('反馈保存失败，请稍后重试')
    return
  }

  try {
    const updated = await topicApi.updateMessageFeedback(topicId, messageId, nextFeedback)
    msg.feedback = String(updated?.feedback ?? nextFeedback)
  } catch (_error) {
    msg.feedback = previousFeedback
    ElMessage.error('反馈保存失败，请稍后重试')
  }
}

const assistantAgentName = (msg) => String(
  msg?.agent?.name
  || activeTopic.value?.agent?.name
  || activeAgent.value?.name
  || '智能问数'
)

const latestVisibleMessage = computed(() => [...activeMessages.value].reverse().find((msg) => msg?.role) || null)

const latestSuccessfulAssistantMessage = computed(() => {
  const latest = latestVisibleMessage.value
  if (!latest || latest.role !== 'assistant') return null
  if (latest.status !== 'success') return null
  if (activeCancelableMessage.value || activeTopicSubmitting.value) return null
  return latest
})

const followupMessageKey = (msg) => String(msg?.message_id || msg?.id || '').trim()

const normalizeFollowupSuggestions = (values) => {
  if (!Array.isArray(values)) return []
  const seen = new Set()
  return values
    .map((value) => String(value || '').trim())
    .filter((value) => {
      if (!value || seen.has(value)) return false
      seen.add(value)
      return true
    })
    .slice(0, 3)
}

const setFollowupSuggestionState = (messageKey, state, suggestions = []) => {
  if (!messageKey) return
  followupSuggestionStateByMessage.value = {
    ...followupSuggestionStateByMessage.value,
    [messageKey]: state
  }
  followupSuggestionsByMessage.value = {
    ...followupSuggestionsByMessage.value,
    [messageKey]: suggestions
  }
}

const loadFollowupSuggestionsForMessage = async (msg) => {
  const messageKey = followupMessageKey(msg)
  const topicId = String(activeTopicId.value || '').trim()
  if (!topicId || !messageKey) return
  const currentState = followupSuggestionStateByMessage.value[messageKey]
  if (currentState === 'loading' || currentState === 'loaded') return
  setFollowupSuggestionState(messageKey, 'loading', [])
  try {
    const response = await topicApi.generateFollowupSuggestions(topicId, messageKey)
    setFollowupSuggestionState(
      messageKey,
      'loaded',
      normalizeFollowupSuggestions(response?.suggestions)
    )
  } catch (_error) {
    setFollowupSuggestionState(messageKey, 'error', [])
  }
}

const followupSuggestions = computed(() => {
  const msg = latestSuccessfulAssistantMessage.value
  const messageKey = followupMessageKey(msg)
  return messageKey ? (followupSuggestionsByMessage.value[messageKey] || []) : []
})

const shouldShowFollowupForMessage = (msg) => latestSuccessfulAssistantMessage.value === msg && followupSuggestions.value.length > 0

const hasErrorBlock = (msg) => renderBlocksForMessage(msg).some((block) => block.kind === 'error' && String(block.text || '').trim())

const errorMessage = (error) => {
  if (!error) return ''
  if (typeof error === 'string') return error
  if (typeof error === 'object') return String(error.message || error.detail || '请求失败')
  return String(error)
}

const escapeHtml = (text) => String(text || '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')

const renderMarkdown = (text) => {
  if (!text) return ''
  try {
    return marked.parse(escapeHtml(text))
  } catch (_error) {
    return escapeHtml(text)
  }
}

const sortTopics = () => {
  topics.value.sort((left, right) => new Date(right.updated_at || right.created_at || 0) - new Date(left.updated_at || left.created_at || 0))
}

const normalizeTopicSummary = (topic) => ({
  topic_id: String(topic?.topic_id || ''),
  title: String(topic?.title || '新话题'),
  agent_id: String(topic?.agent_id || topic?.agent?.agent_id || selectedAgentId.value || ''),
  agent: topic?.agent || null,
  message_count: Number(topic?.message_count || 0),
  current_task_id: String(topic?.current_task_id || ''),
  current_task_status: String(topic?.current_task_status || ''),
  created_at: String(topic?.created_at || new Date().toISOString()),
  updated_at: String(topic?.updated_at || new Date().toISOString()),
  messages: []
})

const rememberActiveTopic = (topic) => {
  if (topic && String(topic.topic_id || '') === String(activeTopicId.value || '')) {
    activeTopicSnapshot.value = topic
  }
}

const makeAssistantMsg = () => reactive(createAssistantMessageState({
  id: `a_${uid()}`,
  created_at: new Date().toISOString()
}))

const syncAssistantMessage = (target, source) => {
  if (!target || !source) return
  Object.assign(target, source)
}

const toUiTaskStatus = (status) => {
  const raw = String(status || '').trim()
  if (!raw) return 'queued'
  if (raw === 'waiting') return 'queued'
  if (raw === 'finished') return 'success'
  if (raw === 'error') return 'failed'
  if (raw === 'suspended') return 'cancelled'
  return raw
}

const isActiveTaskStatus = (status) => ['queued', 'running', 'streaming'].includes(String(status || '').trim())

const describeToolActivity = (tool) => {
  const bootstrapSkillName = String(tool?._skillBootstrapName || '').trim()
  if (bootstrapSkillName) {
    return {
      text: '正在加载技能',
      preview: formatSkillBootstrapLabel(bootstrapSkillName)
    }
  }

  const action = describeToolAction(tool)

  if (action.kind === 'shell') {
    return {
      text: '正在执行命令',
      preview: compactProcessPreview(action.preview || '等待命令输出', 'shell')
    }
  }

  if (action.kind === 'read') {
    return {
      text: '正在读取文件',
      preview: compactProcessPreview(action.preview || '等待文件内容', 'read')
    }
  }

  if (action.kind === 'list') {
    return {
      text: '正在查看目录',
      preview: compactProcessPreview(action.preview || '等待目录结果', 'list')
    }
  }

  if (action.kind === 'search') {
    return {
      text: '正在搜索文件',
      preview: compactProcessPreview(action.preview || '等待搜索结果', 'search')
    }
  }

  if (action.kind === 'edit') {
    return {
      text: '正在修改文件',
      preview: compactProcessPreview(action.preview || '等待修改结果', 'edit')
    }
  }

  if (action.kind === 'skill') {
    return {
      text: '正在执行技能',
      preview: compactProcessPreview(action.preview || '正在准备技能上下文', 'skill')
    }
  }

  return {
    text: action.label || `正在执行 ${action.name || '工具'}`,
    preview: compactProcessPreview(action.preview, action.kind)
  }
}

const streamingActivity = (msg) => {
  const status = String(msg?.status || '').trim()
  if (!isActiveTaskStatus(status)) return null
  if (status === 'queued') {
    return {
      kind: 'thinking',
      text: '等待执行',
      preview: ''
    }
  }
  const activeBlock = [...renderBlocksForMessage(msg)].reverse().find((block) => {
    if (block?.kind === 'tool' && block.tool) {
      return ['pending', 'streaming'].includes(String(block.tool.status || '').trim())
    }
    return ['pending', 'streaming'].includes(String(block?.status || '').trim())
  }) || null
  if (activeBlock?.kind === 'tool' && activeBlock.tool) {
    return {
      kind: 'executing',
      ...describeToolActivity(activeBlock.tool)
    }
  }
  if (activeBlock?.kind === 'main_text') {
    return {
      kind: 'thinking',
      text: '正在整理回答',
      preview: ''
    }
  }
  const latestThinking = [...renderBlocksForMessage(msg)].reverse().find((block) => block.kind === 'thinking' && String(block.text || '').trim())
  const preview = thinkingPreview(activeBlock?.kind === 'thinking' ? activeBlock : latestThinking)
  return {
    kind: 'thinking',
    text: '正在思考',
    preview
  }
}

const processPlaceholder = (msg) => {
  const hasRenderableProcessBlock = processBlocksForMessage(msg).some((block) => {
    if (block.kind === 'tool' && block.tool) return true
    return Boolean(String(block.text || '').trim())
  })
  if (hasRenderableProcessBlock) return null
  return streamingActivity(msg)
}

const hasFinalResult = (msg) => finalBlocksForMessage(msg)
  .some((block) => block.kind === 'main_text' && Boolean(displayTextBlock(block, msg)))

const hasProcessPanel = (msg) => processBlocksForMessage(msg).some((block) => {
  if (block.kind === 'tool' && block.tool) return true
  return Boolean(String(block.text || '').trim())
}) || Boolean(processPlaceholder(msg))

const processSummaryPreview = (msg) => {
  const activity = streamingActivity(msg)
  if (activity) return activity.preview || activity.text

  const latestProcessBlock = [...processBlocksForMessage(msg)].reverse().find((block) => {
    if (block.kind === 'tool' && block.tool) return true
    return Boolean(String(block.text || '').trim())
  })

  if (latestProcessBlock?.kind === 'tool' && latestProcessBlock.tool) {
    const summary = describeToolActivity(latestProcessBlock.tool)
    return summary.preview || summary.text
  }

  if (latestProcessBlock?.kind === 'thinking') {
    return thinkingPreview(latestProcessBlock)
  }

  return hasFinalResult(msg) ? '已完成' : ''
}

const processSummaryMeta = (msg) => {
  if (isActiveTaskStatus(msg?.status)) return '进行中'
  const steps = processBlocksForMessage(msg).filter((block) => {
    if (block.kind === 'tool' && block.tool) return true
    return Boolean(String(block.text || '').trim())
  }).length
  return `${steps || 1} 步`
}

const processPanelKey = (msg) => String(msg?.message_id || msg?.id || '')

const defaultProcessPanelExpanded = (msg) => isActiveTaskStatus(msg?.status) || !hasFinalResult(msg)

const isProcessPanelExpanded = (msg) => {
  if (msg?._processPanelTouched) return Boolean(msg._processPanelExpanded)
  return defaultProcessPanelExpanded(msg)
}

const toggleProcessPanel = (msg) => {
  if (!processPanelKey(msg) || !msg || typeof msg !== 'object') return
  msg._processPanelTouched = true
  msg._processPanelExpanded = !isProcessPanelExpanded(msg)
}

const processEvent = processAssistantStreamEvent

const stopTaskSubscription = (taskId) => {
  const key = String(taskId || '').trim()
  const current = taskSubscriptions.get(key)
  if (!current) return
  current.controller.abort()
  taskSubscriptions.delete(key)
}

const stopAllTaskSubscriptions = () => {
  for (const taskId of taskSubscriptions.keys()) {
    stopTaskSubscription(taskId)
  }
}

const subscribeTask = (taskId, assistantMsg) => {
  const key = String(taskId || assistantMsg?.task_id || '').trim()
  if (!key || !assistantMsg || taskSubscriptions.has(key)) return

  const controller = new AbortController()
  let afterSeq = Math.max(0, Number(assistantMsg?.resume_after_seq || 0))

  const finalizeWithTaskState = async () => {
    try {
      const task = await taskApi.getTask(key)
      if (!task) return false
      const taskStatus = String(task.task_status || task.status || '').trim()
      if (taskStatus === 'suspended' && assistantMsg.status === 'queued') {
        processEvent(assistantMsg, {
          task_id: key,
          message_id: assistantMsg.message_id,
          record_type: 'event',
          event_type: 'AGENT_SUSPENDED',
          data: {
            status: 'suspended',
            error: { code: 'task_cancelled', message: '任务已取消' }
          }
        })
      } else if (taskStatus === 'error' && assistantMsg.status === 'queued') {
        assistantMsg.status = 'failed'
        if (task.error?.message) {
          assistantMsg.error = { message: String(task.error.message) }
        }
      } else if (taskStatus === 'finished') {
        assistantMsg.status = 'success'
      } else if (isActiveTaskStatus(taskStatus === 'waiting' ? 'queued' : taskStatus)) {
        assistantMsg.status = taskStatus === 'waiting' ? 'queued' : taskStatus
        return true
      }
      triggerRef(topics)
      scrollToBottom()
      return false
    } catch (_error) {
      return false
    }
  }

  const pump = async () => {
    try {
      while (!controller.signal.aborted) {
        try {
          await taskApi.streamTaskEvents(key, {
            afterSeq,
            signal: controller.signal,
            onEvent: (event) => {
              afterSeq = Math.max(afterSeq, Number(event?.seq_id || event?.seq || 0))
              processEvent(assistantMsg, event)
              assistantMsg.resume_after_seq = Math.max(Number(assistantMsg.resume_after_seq || 0), afterSeq)
              triggerRef(topics)
              scrollToBottom()
            }
          })
          const shouldContinue = await finalizeWithTaskState()
          if (!shouldContinue) break
        } catch (error) {
          if (controller.signal.aborted) break
          const shouldContinue = await finalizeWithTaskState()
          if (!shouldContinue) break
          await new Promise((resolve) => window.setTimeout(resolve, 1500))
        }
      }
    } finally {
      taskSubscriptions.delete(key)
    }
  }

  taskSubscriptions.set(key, { controller })
  void pump()
}

const resumePendingTasks = (topic) => {
  if (!topic || !Array.isArray(topic.messages)) return
  for (const message of topic.messages) {
    if (message?.role !== 'assistant') continue
    if (!message?.task_id) continue
    if (!isActiveTaskStatus(message?.status)) continue
    subscribeTask(message.task_id, message)
  }
}

const cancelTask = async (msg) => {
  const taskId = String(msg?.task_id || '').trim()
  if (!taskId) return
  try {
    await taskApi.cancelTask(taskId)
    stopTaskSubscription(taskId)
    processEvent(msg, {
      task_id: taskId,
      message_id: msg.message_id,
      record_type: 'event',
      event_type: 'AGENT_SUSPENDED',
      data: {
        status: 'suspended',
        error: { code: 'task_cancelled', message: '任务已取消' }
      }
    })
    triggerRef(topics)
    scrollToBottom()
  } catch (error) {
    ElMessage.error(String(error?.message || '取消任务失败'))
  }
}

const handleComposerAction = () => {
  if (composerActionMode.value === 'cancel') {
    if (activeCancelableMessage.value) {
      void cancelTask(activeCancelableMessage.value)
    }
    return
  }
  void handleSend()
}

const loadSettings = async () => {
  try {
    const payload = await adminApi.getSettings()
    const enabledProviders = Array.isArray(payload?.providers)
      ? payload.providers.filter((item) => item?.enabled && Array.isArray(item?.models) && item.models.length)
      : []
    settings.providers = enabledProviders
    const preferredProviderId = payload?.provider_id || payload?.default_provider_id || ''
    const resolvedProvider = enabledProviders.find((item) => item.provider_id === preferredProviderId) || enabledProviders[0] || null
    settings.default_provider_id = resolvedProvider?.provider_id || ''

    const preferredModel = payload?.model || payload?.default_model || ''
    const providerModels = Array.isArray(resolvedProvider?.models) ? resolvedProvider.models : []
    settings.default_model = providerModels.includes(preferredModel)
      ? preferredModel
      : (resolvedProvider?.default_model || providerModels[0] || '')

    selectedProvider.value = settings.default_provider_id
    selectedModel.value = settings.default_model
  } catch (error) {
    console.warn('load settings failed', error)
  }
}

const normalizeAgent = (agent) => ({
  agent_id: String(agent?.agent_id || ''),
  name: String(agent?.name || '默认助手'),
  description: String(agent?.description || ''),
  is_default: Boolean(agent?.is_default),
  is_builtin: Boolean(agent?.is_builtin)
})

const loadAgents = async () => {
  try {
    const list = await agentApi.listAgents()
    const normalized = (Array.isArray(list) ? list : []).map(normalizeAgent).filter((agent) => agent.agent_id)
    agents.value = normalized.length
      ? normalized
      : [{ agent_id: 'agent_default', name: '默认助手', description: '', is_default: true, is_builtin: true }]
    const routeAgentId = String(route.query.agent_id || '').trim()
    if (routeAgentId && agents.value.some((agent) => agent.agent_id === routeAgentId)) {
      selectedAgentId.value = routeAgentId
    } else if (!agents.value.some((agent) => agent.agent_id === selectedAgentId.value)) {
      selectedAgentId.value = (agents.value.find((agent) => agent.is_default) || agents.value[0])?.agent_id || ''
    }
  } catch (error) {
    console.warn('load agents failed', error)
    agents.value = [{ agent_id: 'agent_default', name: '默认助手', description: '', is_default: true, is_builtin: true }]
    selectedAgentId.value = selectedAgentId.value || 'agent_default'
  }
}

const persistSelectedAgentInRoute = (agentId) => {
  const value = String(agentId || '').trim()
  if (String(route.query.agent_id || '').trim() === value) return

  const query = { ...route.query }
  if (value) {
    query.agent_id = value
  } else {
    delete query.agent_id
  }

  const navigation = router.replace({
    path: '/intelligent-query',
    query
  })
  if (navigation && typeof navigation.catch === 'function') {
    navigation.catch(() => {})
  }
}

const hydrateTopic = async (topicId) => {
  if (!topicId || hydratedIds.has(topicId)) return

  try {
    const [detail, messagePage] = await Promise.all([
      topicApi.getTopic(topicId),
      topicApi.getTopicMessages(topicId, { page: 1, page_size: 500, order: 'asc' })
    ])
    let target = findListedTopic(topicId)
    if (!target && activeTopicSnapshot.value?.topic_id === topicId) {
      target = activeTopicSnapshot.value
    }
    if (!target && detail) {
      target = normalizeTopicSummary(detail)
      rememberActiveTopic(target)
    }
    if (target && detail) {
      target.title = String(detail.title || target.title)
      target.agent_id = String(detail.agent_id || target.agent_id || '')
      target.agent = detail.agent || target.agent || null
      target.updated_at = String(detail.updated_at || target.updated_at)
      target.current_task_id = String(detail.current_task_id || target.current_task_id || '')
      target.current_task_status = String(detail.current_task_status || target.current_task_status || '')
      const rawMessages = Array.isArray(messagePage?.items) ? messagePage.items : []
      target.messages = rawMessages.map((message) => {
        if (!message) return null
        const senderType = String(message.sender_type || message.role || 'assistant')
        if (senderType === 'user') {
          return {
            id: String(message.message_id || uid()),
            role: 'user',
            content: String(message.content || ''),
            created_at: message.created_at
          }
        }

        return reactive(hydrateAssistantMessageState(message))
      }).filter(Boolean)
      target.message_count = Number(messagePage?.total || target.messages.length)
      resumePendingTasks(target)
      rememberActiveTopic(target)
    }

    hydratedIds.add(topicId)
  } catch (error) {
    console.warn('hydrate topic failed', error)
  }
}

const loadTopics = async () => {
  try {
    const currentActiveTopic = activeTopic.value
    rememberActiveTopic(currentActiveTopic)
    const preservedHydratedTopicId = currentActiveTopic && hydratedIds.has(currentActiveTopic.topic_id)
      ? currentActiveTopic.topic_id
      : ''
    const list = await topicApi.listTopics(selectedAgentId.value ? { agent_id: selectedAgentId.value } : {})
    topics.value = (Array.isArray(list) ? list : []).map(normalizeTopicSummary)
    hydratedIds.clear()
    if (preservedHydratedTopicId) {
      hydratedIds.add(preservedHydratedTopicId)
    }
    sortTopics()
    if (!activeTopicId.value && topics.value.length) {
      activeTopicId.value = topics.value[0].topic_id
      rememberActiveTopic(topics.value[0])
    }
    const listedActiveTopic = activeTopicId.value ? findListedTopic(activeTopicId.value) : null
    if (listedActiveTopic) {
      if (currentActiveTopic?.topic_id === listedActiveTopic.topic_id && preservedHydratedTopicId) {
        Object.assign(listedActiveTopic, currentActiveTopic)
      }
      rememberActiveTopic(listedActiveTopic)
      await hydrateTopic(activeTopicId.value)
    }
  } catch (error) {
    console.warn('load topics failed', error)
  }
}

const handleNewTopic = async () => {
  const topic = normalizeTopicSummary(await topicApi.createTopic('新话题', { agent_id: selectedAgentId.value }))
  topics.value.unshift(topic)
  hydratedIds.add(topic.topic_id)
  activeTopicId.value = topic.topic_id
  rememberActiveTopic(topic)
  autoScroll.value = true
  scrollToBottom(true)
}

const handleSelectTopic = async (topicId) => {
  activeTopicId.value = topicId
  rememberActiveTopic(findListedTopic(topicId))
  await hydrateTopic(topicId)
  autoScroll.value = true
  scrollToBottom(true)
}

const maybePersistTopicTitle = async (topic, text) => {
  if (!topic) return
  const currentTitle = String(topic.title || '').trim()
  if (currentTitle && currentTitle !== '新话题') return

  const nextTitle = deriveTopicTitle(text)
  topic.title = nextTitle

  try {
    const updated = await topicApi.updateTopic(String(topic.topic_id || ''), { title: nextTitle })
    if (updated?.title) {
      topic.title = String(updated.title)
    }
    if (updated?.updated_at) {
      topic.updated_at = String(updated.updated_at)
    }
  } catch (error) {
    console.warn('persist topic title failed', error)
  }
}

const handleSend = async () => {
  const text = inputText.value.trim()
  if (!text || isTopicSubmitting(activeTopicId.value) || activeCancelableMessage.value || !selectedProvider.value || !selectedModel.value) return

  inputText.value = ''
  autoScroll.value = true
  scrollToBottom(true)

  let topic = null
  let assistantMsg = null
  let submitTopicId = activeTopicId.value
  let pendingKey = markTopicSubmitting(submitTopicId)

  try {
    if (!activeTopicId.value) {
      const title = deriveTopicTitle(text, 20)
      const created = normalizeTopicSummary(await topicApi.createTopic(title, { agent_id: selectedAgentId.value }))
      topics.value.unshift(created)
      hydratedIds.add(created.topic_id)
      activeTopicId.value = created.topic_id
      rememberActiveTopic(created)
      submitTopicId = created.topic_id
      pendingKey = moveTopicSubmitting('', submitTopicId)
    }

    submitTopicId = activeTopicId.value
    await hydrateTopic(submitTopicId)

    topic = activeTopic.value || null
    if (!topic) {
      throw new Error('话题初始化失败')
    }

    if (!Array.isArray(topic.messages)) {
      topic.messages = []
    }

    topic.messages.push({
      id: `u_${uid()}`,
      role: 'user',
      content: text,
      created_at: new Date().toISOString()
    })

    assistantMsg = makeAssistantMsg()
    assistantMsg.status = 'queued'
    topic.messages.push(assistantMsg)
    scrollToBottom(true)

    const response = await taskApi.deliverMessage({
      topic_id: submitTopicId,
      content: text,
      agent_id: activeConversationAgentId.value,
      provider_id: selectedProvider.value,
      model: selectedModel.value,
      debug: true,
      execution_mode: 'auto'
    })

    assistantMsg.message_id = String(response?.assistant_message_id || assistantMsg.message_id || '')
    assistantMsg.task_id = String(response?.task_id || assistantMsg.task_id || '')
    assistantMsg.status = toUiTaskStatus(response?.task_status)
    topic.current_task_id = assistantMsg.task_id
    topic.current_task_status = String(response?.task_status || topic.current_task_status || '')
    if (assistantMsg.task_id) {
      subscribeTask(assistantMsg.task_id, assistantMsg)
    }

    topic.updated_at = new Date().toISOString()
    topic.message_count = topic.messages.length
    await maybePersistTopicTitle(topic, text)
    sortTopics()
    triggerRef(topics)
    scrollToBottom(true)
  } catch (error) {
    const message = String(error?.message || '请求失败')
    if (assistantMsg) {
      assistantMsg.status = 'failed'
      assistantMsg.error = message
    } else {
      ElMessage.error(message)
    }
  } finally {
    clearTopicSubmitting(pendingKey)
  }
}

const handleSuggestion = (value) => {
  inputText.value = value
  void handleSend()
}

const getMessagesScrollWrap = () => {
  const scrollbar = messagesScrollbarRef.value
  if (!scrollbar) return null
  if (scrollbar.wrapRef) return scrollbar.wrapRef
  if (scrollbar.$el && typeof scrollbar.$el.querySelector === 'function') {
    return scrollbar.$el.querySelector('.el-scrollbar__wrap') || scrollbar.$el
  }
  if (typeof scrollbar.querySelector === 'function') {
    return scrollbar.querySelector('.el-scrollbar__wrap') || scrollbar
  }
  return null
}

const isNearBottom = (scrollTop) => {
  const element = getMessagesScrollWrap()
  if (!element) return true
  const currentScrollTop = Number.isFinite(scrollTop) ? scrollTop : element.scrollTop
  return element.scrollHeight - currentScrollTop - element.clientHeight < 60
}

const handleScroll = ({ scrollTop } = {}) => {
  autoScroll.value = isNearBottom(scrollTop)
}

const scrollToBottom = (force = false) => {
  if (!force && !autoScroll.value) return
  nextTick(() => {
    const scrollbar = messagesScrollbarRef.value
    if (scrollbar?.update) {
      scrollbar.update()
    }
    const element = getMessagesScrollWrap()
    if (!element) return
    if (scrollbar?.setScrollTop) {
      scrollbar.setScrollTop(element.scrollHeight)
      return
    }
    element.scrollTop = element.scrollHeight
  })
}

watch(
  () => [selectedProvider.value, availableModels.value.join('|')],
  () => {
    if (!availableModels.value.includes(selectedModel.value)) {
      selectedModel.value = availableModels.value[0] || settings.default_model || ''
    }
  }
)

const agentSelectValue = ref('')

watch(selectedAgentId, (val) => {
  agentSelectValue.value = val
}, { immediate: true })

const handleAgentChange = async (nextValue) => {
  if (!agentSelectionReady.value) return

  const value = String(nextValue || '').trim()
  if (!value || value === selectedAgentId.value) {
    agentSelectValue.value = selectedAgentId.value
    return
  }

  persistSelectedAgentInRoute(value)
  selectedAgentId.value = value
}

watch(selectedAgentId, async (next, prev) => {
  if (!agentSelectionReady.value || !next || next === prev) return
  await loadTopics()
})

watch(
  () => [
    activeTopicId.value,
    followupMessageKey(latestSuccessfulAssistantMessage.value),
    latestSuccessfulAssistantMessage.value?.status
  ],
  () => {
    const msg = latestSuccessfulAssistantMessage.value
    if (!msg) return
    void loadFollowupSuggestionsForMessage(msg)
  }
)

onMounted(async () => {
  await loadSettings()
  await loadAgents()
  await loadTopics()
  agentSelectionReady.value = true
  scrollToBottom(true)
})

onBeforeUnmount(() => {
  stopAllTaskSubscriptions()
})
</script>

<style scoped>
.query-workbench {
  --sidebar-bg: #ffffff;
  --sidebar-border: #E5EAF1;
  --sidebar-text: #1F1F1F;
  --sidebar-text-muted: #8C8C8C;
  --surface: #F4F5F7;
  --surface-muted: #F9FAFC;
  --surface-soft: #EEF1F5;
  --line: #E5EAF1;
  --line-soft: #eff1f5;
  --text: #1F1F1F;
  --text-muted: #595959;
  --text-soft: #A0AABF;
  --accent: #4F81FF;
  --accent-soft: rgba(79, 129, 255, 0.10);
  --primary: #4F81FF;
  --content-max-width: clamp(860px, 82%, 1180px);
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-columns: 260px 1fr;
  border: 1px solid #E5EAF1;
  border-radius: 18px;
  overflow: hidden;
  background: var(--surface);
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.035);
  font-family: 'IBM Plex Sans', 'PingFang SC', 'Hiragino Sans GB', sans-serif;
}

.query-sidebar {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 16px 12px 16px;
  background: var(--sidebar-bg);
  border-right: 1px solid var(--sidebar-border);
  color: var(--sidebar-text);
}

.query-sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 8px 16px;
}

.query-agent-select {
  flex: 1;
  width: 100%;
  min-width: 0;
}

.query-agent-select :deep(.el-select__wrapper) {
  min-height: 36px;
  height: 36px;
  border-radius: 10px;
  background: #f8fafc;
  box-shadow: 0 0 0 1px #e2e8f0 inset !important;
  transition: all 0.2s ease;
  padding: 0 12px;
}

.query-agent-select :deep(.el-select__wrapper.is-focused) {
  background: #ffffff;
  box-shadow: 0 0 0 1px #4F81FF inset, 0 0 0 3px rgba(79, 129, 255, 0.1) inset !important;
}

.query-agent-select :deep(.el-select__wrapper:hover:not(.is-focused)) {
  background: #f1f5f9;
  box-shadow: 0 0 0 1px #cbd5e1 inset !important;
}

.query-topic-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
  margin-right: 24px;
}

.query-topic-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.query-agent-select-icon {
  width: 15px;
  height: 15px;
  color: #64748b;
  flex-shrink: 0;
}

.query-btn-new {
  height: 34px;
  padding: 0 16px;
  border: none;
  border-radius: 8px;
  background: #4F81FF;
  color: #ffffff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
  box-shadow: 0 2px 8px rgba(79, 129, 255, 0.25);
}

.query-btn-new:hover {
  background: #3D6FE3;
  transform: translateY(-1px);
}

.query-sidebar-search {
  padding: 0 8px 14px;
}

.query-search-input {
  width: 100%;
  height: 38px;
  padding: 0 14px;
  border: 1px solid #E5EAF1;
  border-radius: 10px;
  background: #ffffff;
  color: #344054;
  font-size: 14px;
  outline: none;
  transition: border-color 0.18s ease, background-color 0.18s ease;
}

.query-search-input::placeholder {
  color: #BBBBBB;
}

.query-search-input:focus {
  border-color: #4F81FF;
  background: #ffffff;
}

.query-session-scroll {
  flex: 1;
  min-height: 0;
  margin-top: 2px;
}

.query-session-scroll :deep(.el-scrollbar__wrap) {
  overflow-x: hidden;
}

.query-session-list {
  padding: 0 4px;
}

.query-session-item {
  width: 100%;
  margin-bottom: 2px;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  color: var(--sidebar-text);
  cursor: pointer;
  transition: background-color 0.2s ease, border-color 0.2s ease;
  
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.query-session-item:hover {
  background: #F9FAFC;
  border-color: transparent;
}

.query-session-item.active {
  background: #F4F7FF;
  border-color: transparent;
}

.query-session-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.3;
  color: #595959;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: left;
}

.query-session-item.active .query-session-title {
  color: #1F1F1F;
}

.query-session-meta {
  flex-shrink: 0;
  font-size: 12px;
  color: #A0AABF;
  white-space: nowrap;
  text-align: right;
}

.query-session-loading {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.query-session-spinner {
  width: 14px;
  height: 14px;
  color: #4F81FF;
  animation: query-spin 1s linear infinite;
}

.query-session-spinner-track {
  stroke: rgba(0, 0, 0, 0.05);
}

.query-session-spinner-head {
  stroke: currentColor;
}

.query-empty-sessions {
  padding: 30px 8px;
  color: #8C8C8C;
  font-size: 13px;
  text-align: center;
}

.query-main {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #F4F5F7;
}

.query-main-top-bar {
  min-height: 64px;
  padding: 12px 26px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.92);
}

.query-current-agent {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-muted);
}

.query-current-agent-icon {
  width: 16px;
  height: 16px;
  color: #64748b;
  flex-shrink: 0;
}

.query-current-agent-name {
  min-width: 0;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #334155;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
}

.query-messages {
  flex: 1;
  min-height: 0;
}

.query-messages :deep(.el-scrollbar__wrap) {
  overscroll-behavior: contain;
}

.query-messages-inner {
  width: 100%;
  max-width: var(--content-max-width);
  margin: 0 auto;
  padding: 28px 26px 36px;
}

.query-main-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 20px;
  margin-bottom: 18px;
  border-bottom: 1px dashed var(--line);
}

.query-main-head h3 {
  margin: 0;
  color: var(--text);
  font-size: 28px;
  font-weight: 700;
}

.query-main-subtitle {
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 14px;
}

.query-model-badge {
  min-width: 200px;
  padding: 12px 14px;
  border: 1px solid #eff1f5;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 2px 12px rgba(15, 23, 42, 0.04);
}

.query-model-badge-top {
  min-width: 180px;
  padding: 8px 12px;
  border-radius: 10px;
  box-shadow: none;
}

.query-model-badge span {
  display: block;
  color: var(--text-muted);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.query-model-badge strong {
  display: block;
  margin-top: 8px;
  color: var(--text);
  font-size: 14px;
  line-height: 1.5;
  word-break: break-all;
}

.query-model-badge-top strong {
  margin-top: 3px;
}

.query-model-badge-note {
  display: block;
  margin-top: 8px;
  color: var(--text-soft);
  font-size: 12px;
  font-style: normal;
  line-height: 1.5;
}

.query-config-empty {
  margin-top: 18px;
  padding: 18px 20px;
  border-radius: 18px;
  border: 1px dashed rgba(245, 158, 11, 0.45);
  background: linear-gradient(180deg, rgba(255, 247, 237, 0.92) 0%, rgba(255, 255, 255, 0.98) 100%);
}

.query-config-empty-title {
  font-size: 15px;
  font-weight: 700;
  color: #9a3412;
}

.query-config-empty-text {
  margin-top: 6px;
  font-size: 13px;
  color: #b45309;
}

.query-empty {
  min-height: 360px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.query-empty-mark {
  width: 68px;
  height: 68px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 20px;
  background: #4F81FF;
  color: #ffffff;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 0.08em;
  box-shadow: 0 8px 24px rgba(79, 129, 255, 0.2);
}

.query-empty-title {
  margin-top: 18px;
  color: var(--text);
  font-size: 24px;
  font-weight: 700;
}

.query-empty-subtitle {
  margin-top: 10px;
  color: var(--text-muted);
  font-size: 14px;
  line-height: 1.8;
}

.query-suggestions {
  margin-top: 22px;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}

.query-suggestion {
  height: 38px;
  padding: 0 16px;
  border: 1px solid #eff1f5;
  border-radius: 999px;
  background: #ffffff;
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.18s ease, background-color 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
}

.query-suggestion:hover {
  border-color: #4F81FF;
  background: #ffffff;
  transform: translateY(-1px);
  box-shadow: 0 4px 20px rgba(79, 129, 255, 0.08);
}

.query-message-row {
  margin-bottom: 24px;
  display: flex;
}

.query-message-user {
  justify-content: flex-end;
}

.query-user-message-shell {
  max-width: 72%;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.query-user-bubble {
  max-width: 100%;
  padding: 14px 18px;
  border-radius: 16px 16px 4px 16px;
  background: #4F81FF;
  color: #ffffff;
  font-size: 15px;
  line-height: 1.75;
  white-space: pre-wrap;
  box-shadow: 0 4px 16px rgba(79, 129, 255, 0.15);
}

.query-message-assistant {
  justify-content: flex-start;
}

.query-assistant-body {
  width: 100%;
  max-width: 100%;
}

.query-message-footer {
  min-height: 26px;
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 7px;
  color: #A0AABF;
  font-size: 12px;
  opacity: 0;
  transition: opacity 0.16s ease;
}

.query-message-row:hover .query-message-footer,
.query-message-footer:focus-within {
  opacity: 1;
}

.query-message-footer-user {
  justify-content: flex-end;
}

.query-message-time {
  white-space: nowrap;
}

.query-message-tool {
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: #A0AABF;
  cursor: pointer;
  transition: background-color 0.16s ease, border-color 0.16s ease, color 0.16s ease;
}

.query-message-tool svg {
  width: 14px;
  height: 14px;
  stroke-width: 1.8;
}

.query-message-tool:hover {
  border-color: #D9E2F2;
  background: #ffffff;
  color: #4F81FF;
}

.query-message-tool.active {
  border-color: #D9E2F2;
  background: #ffffff;
  color: #1a1a1a;
}

.query-followup-suggestions {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  border-top: 1px dashed var(--line);
  padding-top: 14px;
}

.query-followup-label {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: 12.5px;
  font-weight: 600;
  user-select: none;
}

.query-followup-icon {
  width: 13px;
  height: 13px;
  color: #4F81FF;
  flex-shrink: 0;
}

.query-followup-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.query-followup-suggestion {
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid #DDE5F3;
  border-radius: 999px;
  background: #ffffff;
  color: #425466;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, box-shadow 0.16s ease;
}

.query-followup-suggestion:hover {
  border-color: #4F81FF;
  color: #2F5BD5;
  box-shadow: 0 4px 16px rgba(79, 129, 255, 0.10);
}

.query-step-row {
  margin-bottom: 8px;
}

.query-process-panel {
  margin-bottom: 16px;
}

.query-process-summary-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.query-process-summary {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-width: 0;
  padding: 0;
  border: none;
  background: transparent;
  color: #595959;
  font-size: 14px;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  user-select: none;
}

.query-process-summary-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  letter-spacing: 0.04em;
}

.query-process-badge-dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #4F81FF;
  box-shadow: 0 0 0 3px rgba(79, 129, 255, 0.12);
  animation: query-process-pulse 1.4s ease-in-out infinite;
}

.query-process-summary-preview {
  flex: 1;
  min-width: 0;
  color: #A0AABF;
  font-size: 12px;
  font-weight: 400;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.query-process-chevron {
  width: 14px;
  height: 14px;
  color: #8C8C8C;
  flex-shrink: 0;
  transition: transform 0.18s ease;
}

.query-process-chevron.open {
  transform: rotate(-180deg);
}

.query-process-content {
  margin-top: 12px;
}

.query-process-content :deep(.el-scrollbar__wrap) {
  overflow-x: hidden;
  overscroll-behavior: contain;
}

.query-process-content-inner {
  padding: 4px 12px 4px 18px;
  border-left: 3px solid #eff1f5;
}

.query-process-placeholder {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

.query-process-placeholder-text {
  color: #595959;
  font-size: 13px;
  font-weight: 500;
}

.query-process-placeholder-preview {
  min-width: 0;
  color: #A0AABF;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.query-process-thought {
  padding: 2px 0;
  color: #A0AABF;
  font-size: 13.5px;
  line-height: 1.7;
}

.query-process-thought-content :deep(p) {
  margin: 0 0 6px;
}

.query-process-thought-content :deep(p:last-child) {
  margin: 0;
}

.query-process-thought-content :deep(ul),
.query-process-thought-content :deep(ol) {
  margin: 4px 0 6px;
  padding-left: 20px;
}

.query-process-thought-content :deep(li) {
  margin-bottom: 2px;
}

.query-process-thought-content :deep(strong) {
  color: #8C8C8C;
}

.query-process-thought-content :deep(code) {
  padding: 2px 4px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.04);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

/* Tool output overrides inside the thinking panel */
.query-process-content :deep(.tool-output) {
  padding: 0;
  border: none;
  border-radius: 0;
  background: transparent;
}

.query-process-content :deep(.tool-output-shell) {
  padding: 0;
}

.query-process-content :deep(.shell-trace-summary-text) {
  color: #8C8C8C;
  font-size: 12.5px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.query-process-content :deep(.shell-trace-summary-status) {
  color: #A0AABF;
  font-size: 11px;
  font-weight: 500;
}

.query-process-content :deep(.shell-trace-chevron-icon) {
  width: 12px;
  height: 12px;
  color: #A0AABF;
}

.query-process-content :deep(.shell-trace-panel) {
  border-color: #eff1f5;
  background: #FAFBFD;
}

.query-process-content :deep(.shell-trace-command),
.query-process-content :deep(.shell-trace-output) {
  font-size: 11px;
  color: #595959;
}

.query-process-content :deep(.shell-trace-description) {
  color: #A0AABF;
  font-size: 11px;
}

.query-process-content :deep(.tool-output-head) {
  gap: 8px;
}

.query-process-content :deep(.tool-output-label) {
  font-size: 12.5px;
  font-weight: 600;
  color: #595959;
}

.query-process-content :deep(.tool-output-meta) {
  font-size: 11px;
  color: #A0AABF;
}

.query-process-content :deep(.tool-output-toggle) {
  font-size: 11px;
  color: #A0AABF;
}

.query-process-content :deep(.tool-output-toggle:hover) {
  color: #595959;
}

.query-process-content :deep(.tool-output-panel) {
  border: 1px solid #D5DCE8;
  border-radius: 10px;
  background: transparent;
  padding: 6px 10px;
  margin-top: 6px;
}

.query-process-content :deep(.tool-output-body-scroll) {
  max-height: 200px;
}

.query-process-content :deep(.tool-output-body-scroll .el-scrollbar__wrap) {
  max-height: 200px !important;
}

.query-process-content :deep(.tool-chart) {
  min-height: 200px;
  height: 200px;
}

.query-process-content :deep(.tool-code) {
  margin-top: 6px;
  padding: 0;
  border: none;
  background: transparent;
  font-size: 11.5px;
  color: #8C8C8C;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.query-process-content :deep(.tool-code-light) {
  background: transparent;
  color: #8C8C8C;
}

.query-process-content :deep(.tool-markdown) {
  margin-top: 8px;
  padding: 0;
  border: none;
  background: transparent;
  border-radius: 0;
}

.query-process-content :deep(.tool-markdown-body) {
  font-size: 13px;
  color: #595959;
  line-height: 1.65;
}

.query-process-content :deep(.tool-markdown-toggle) {
  margin-top: 6px;
  color: #4F81FF;
}

.query-process-content :deep(.tool-output-summary) {
  margin-top: 8px;
  font-size: 12px;
  color: #595959;
}

.query-process-content :deep(.tool-output-error) {
  margin-top: 8px;
  padding: 8px 10px;
  font-size: 12px;
  border-radius: 8px;
}

.query-process-content :deep(.tool-table) {
  font-size: 11.5px;
}

.query-process-content :deep(.tool-table th),
.query-process-content :deep(.tool-table td) {
  padding: 6px 8px;
}

.query-process-content .query-step-row {
  margin-bottom: 14px;
}

.query-main-text {
  color: var(--text);
  font-size: 14.5px;
  line-height: 1.8;
}

.query-final-chart {
  margin-top: 12px;
}

.query-final-chart :deep(.tool-output) {
  background: #ffffff;
}

.query-main-text :deep(p) {
  margin: 0 0 12px;
}

.query-main-text :deep(p:last-child) {
  margin-bottom: 0;
}

.query-main-text :deep(strong) {
  color: var(--text);
  font-weight: 700;
}

.query-main-text :deep(ul),
.query-main-text :deep(ol) {
  margin: 8px 0 12px 20px;
  padding: 0;
}

.query-main-text :deep(li) {
  margin-bottom: 4px;
}

.query-main-text :deep(code) {
  padding: 2px 6px;
  border-radius: 6px;
  background: #edf2ff;
  color: #425cc8;
  font-size: 13px;
}

.query-main-text :deep(pre) {
  margin: 12px 0;
  padding: 14px 16px;
  border-radius: 16px;
  background: #1c2647;
  color: #e6ebff;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.65;
}

.query-main-text :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
  font-size: inherit;
}

.query-main-text :deep(blockquote) {
  margin: 10px 0;
  padding: 8px 14px;
  border-left: 3px solid var(--accent);
  background: rgba(102, 126, 234, 0.06);
  color: var(--text-muted);
}

.query-main-text :deep(a) {
  color: var(--accent);
  text-decoration: none;
}

.query-main-text :deep(a:hover) {
  text-decoration: underline;
}

.query-main-text :deep(table) {
  width: 100%;
  margin: 12px 0;
  border-collapse: collapse;
  font-size: 13px;
}

.query-main-text :deep(th),
.query-main-text :deep(td) {
  padding: 8px 10px;
  border: 1px solid var(--line-soft);
  text-align: left;
}

.query-main-text :deep(th) {
  background: #f3f8fc;
  font-weight: 700;
}

.query-cursor {
  color: var(--accent);
  animation: query-cursor-blink 1s ease-in-out infinite;
}

.query-citations {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.query-citation-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.9);
  color: var(--text-muted);
  font-size: 12px;
  text-decoration: none;
  transition: border-color 0.18s ease, color 0.18s ease;
}

.query-citation-chip:hover {
  border-color: rgba(102, 126, 234, 0.35);
  color: var(--accent);
}

.query-citation-index {
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: var(--surface-soft);
  color: var(--text);
  font-size: 11px;
  font-weight: 700;
}

.query-sql-card,
.query-exec-card {
  margin-top: 14px;
  border: 1px solid var(--line);
  border-radius: 18px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.04);
}

.query-sql-header,
.query-exec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line-soft);
}

.query-sql-header span,
.query-exec-head span:first-child {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.query-sql-actions {
  display: flex;
  gap: 8px;
}

.query-btn-sm {
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: #ffffff;
  color: var(--text);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.18s ease, background-color 0.18s ease;
}

.query-btn-sm:hover {
  border-color: rgba(102, 126, 234, 0.28);
  background: #f7f9ff;
}

.query-btn-primary {
  border-color: rgba(102, 126, 234, 0.35);
  color: var(--accent);
}

.query-sql-code {
  margin: 0;
  padding: 15px 16px;
  background: #1c2647;
  color: #e6ebff;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.65;
}

.query-exec-meta {
  color: var(--text-soft);
  font-size: 12px;
}

.query-exec-error {
  padding: 14px;
  color: #b42318;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.query-table-wrap {
  overflow-x: auto;
}

.query-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.query-table th,
.query-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--line-soft);
  text-align: left;
  white-space: nowrap;
}

.query-table th {
  background: #f4f7ff;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.query-exec-empty {
  padding: 14px;
  color: var(--text-soft);
  font-size: 13px;
}

.query-error-card {
  margin-top: 12px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid rgba(244, 114, 94, 0.25);
  border-radius: 16px;
  background: #fff6f3;
  color: #a2391c;
  font-size: 13px;
  line-height: 1.65;
}

.query-error-label {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 40px;
  height: 24px;
  padding: 0 8px;
  border-radius: 999px;
  background: rgba(244, 114, 94, 0.12);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.query-message-meta {
  margin-top: 8px;
  display: flex;
  width: 100%;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  color: rgba(96, 113, 133, 0.74);
  font-size: 11px;
  line-height: 1.4;
}

.query-message-meta-total {
  color: rgba(96, 113, 133, 0.82);
  font-weight: 400;
}

.query-message-meta-arrow,
.query-message-meta-cache {
  display: inline-flex;
  align-items: center;
  color: rgba(120, 132, 145, 0.76);
}

.query-message-meta-arrow {
  gap: 2px;
}

.query-message-meta-arrow.is-up,
.query-message-meta-arrow.is-down {
  font-variant-numeric: tabular-nums;
}

.query-message-meta-cache {
  margin-left: 1px;
  color: rgba(140, 152, 166, 0.72);
}

.query-loading-dots {
  display: inline-flex;
}

.query-loading-dots span {
  color: var(--text-muted);
  font-size: 14px;
  animation: query-dot 1.4s ease-in-out infinite;
}

.query-loading-dots span:nth-child(2) {
  animation-delay: 0.2s;
}

.query-loading-dots span:nth-child(3) {
  animation-delay: 0.4s;
}

.query-composer-wrap {
  flex-shrink: 0;
  padding: 12px 22px 22px;
}

.query-composer {
  width: 100%;
  max-width: var(--content-max-width);
  margin: 0 auto;
  border: 1px solid #eff1f5;
  border-radius: 20px;
  background: #ffffff;
  box-shadow: 0 4px 30px rgba(0, 0, 0, 0.06);
  overflow: hidden;
  transition: box-shadow 0.2s ease, border-color 0.2s ease;
}

.query-composer:focus-within {
  box-shadow: 0 8px 40px rgba(79, 129, 255, 0.12);
  border-color: #C0D3FF;
}

.query-composer-textarea-wrap {
  padding: 14px 16px 4px;
}

.query-textarea {
  display: block;
  width: 100%;
  min-height: 46px;
  max-height: 140px;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  color: var(--text);
  font-size: 14.5px;
  line-height: 1.75;
  font-family: inherit;
  padding: 0;
  box-shadow: none !important;
}

.query-textarea::placeholder {
  color: var(--text-soft);
}

.query-composer-bottom-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 14px 12px;
  background: transparent;
}

.query-composer-left-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.query-composer-attach-btn {
  width: 30px;
  height: 30px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  outline: none;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.query-composer-attach-btn:hover {
  background: #f1f5f9;
  color: #1e293b;
}

.query-composer-attach-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.query-composer-right-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.query-model-selector-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  user-select: none;
  transition: background-color 0.15s ease;
}

.query-model-selector-trigger:hover {
  background: #f1f5f9;
}

.query-model-selector-trigger.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.query-model-selector-name {
  font-size: 13px;
  color: #64748b;
  font-weight: 500;
}

.query-chevron-icon {
  width: 12px;
  height: 12px;
  color: #94a3b8;
  flex-shrink: 0;
  transition: transform 0.2s ease;
}

.query-model-dropdown-menu {
  padding: 6px 0;
  min-width: 160px;
}

.query-dropdown-group-title {
  padding: 6px 16px 4px;
  font-size: 11px;
  font-weight: 700;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.query-model-dropdown-menu :deep(.el-dropdown-menu__item) {
  font-size: 13px;
  color: #334155;
  padding: 8px 16px;
}

.query-model-dropdown-menu :deep(.el-dropdown-menu__item.active) {
  color: #4F81FF;
  background: rgba(79, 129, 255, 0.06);
  font-weight: 600;
}

.query-context-ring-wrap {
  position: relative;
  width: 36px;
  height: 36px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: #475569;
  cursor: pointer;
  outline: none;
  transition: transform 0.2s ease;
}

.query-context-ring-wrap:hover {
  transform: scale(1.06);
}

.query-context-ring {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}

.query-context-ring-track,
.query-context-ring-value {
  fill: none;
  stroke-width: 2.5;
}

.query-context-ring-track {
  stroke: rgba(0, 0, 0, 0.05);
}

.query-context-ring-value {
  stroke-linecap: round;
  transition: stroke-dasharray 0.3s cubic-bezier(0.4, 0, 0.2, 1), stroke 0.3s ease;
}

.query-context-ring-value.is-primary {
  stroke: #1f1f1f;
}

.query-context-ring-value.is-warning {
  stroke: #F59E0B;
}

.query-context-ring-value.is-danger {
  stroke: #EF4444;
}

.query-context-ring-value.is-muted {
  stroke: transparent;
}

.query-context-ring-wrap.is-empty .query-context-ring-value {
  stroke: transparent;
}

.query-context-ring-text {
  position: relative;
  font-size: 8.5px;
  font-weight: 700;
  color: #1f1f1f;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  line-height: 1;
  white-space: nowrap;
}

.query-composer-action {
  width: 36px;
  min-width: 36px;
  height: 36px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 999px;
  cursor: pointer;
  transition: opacity 0.18s ease, transform 0.18s ease, background-color 0.18s ease, box-shadow 0.18s ease;
}

.query-composer-action:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  box-shadow: none;
}

.query-composer-action:not(:disabled):hover {
  transform: translateY(-1px);
}

.query-composer-action-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.query-btn-send {
  background: #4F81FF;
  color: #ffffff;
  box-shadow: 0 4px 16px rgba(79, 129, 255, 0.2);
}

.query-btn-send:not(:disabled):hover {
  background: #3D6FE3;
}

.query-btn-cancel {
  background: #ffffff;
  color: #D64545;
  border: 1px solid rgba(214, 69, 69, 0.18);
  box-shadow: 0 4px 16px rgba(214, 69, 69, 0.08);
}

.query-btn-cancel:not(:disabled):hover {
  background: #FFF5F5;
  border-color: rgba(214, 69, 69, 0.3);
}

@keyframes query-cursor-blink {
  0% {
    opacity: 0.25;
  }

  50% {
    opacity: 1;
  }

  100% {
    opacity: 0.25;
  }
}

@keyframes query-dot {
  0%,
  20% {
    opacity: 0.2;
  }

  50% {
    opacity: 1;
  }

  80%,
  100% {
    opacity: 0.2;
  }
}

@keyframes query-process-pulse {
  0%,
  100% {
    transform: scale(0.9);
    opacity: 0.8;
  }

  50% {
    transform: scale(1);
    opacity: 1;
  }
}

@media (max-width: 1024px) {
  .query-main-head {
    flex-direction: column;
  }

  .query-model-badge {
    min-width: 0;
    width: 100%;
  }
}

@media (max-width: 960px) {
  .query-workbench {
    grid-template-columns: 1fr;
  }

  .query-sidebar {
    display: none;
  }

  .query-messages-inner,
  .query-composer {
    max-width: 100%;
  }

  .query-user-bubble {
    max-width: 88%;
  }
}

@media (max-width: 768px) {
  .query-workbench {
    border-radius: 22px;
  }

  .query-messages-inner {
    padding: 20px 16px 24px;
  }

  .query-main-head h3 {
    font-size: 24px;
  }

  .query-composer-wrap {
    padding: 10px 12px 14px;
  }

  .query-composer-input-row {
    align-items: flex-end;
  }
}
</style>
