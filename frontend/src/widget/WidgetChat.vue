<template>
  <div
    class="query-workbench"
    :class="{
      'is-inline': isInline,
      'is-floating': !isInline,
      'is-history-open': historyVisible
    }"
  >
    <div v-if="historyVisible && !isInline" class="query-sidebar-backdrop" @click="closeHistory" />

    <aside v-if="historyVisible" class="query-sidebar" aria-label="历史会话">
      <div class="query-sidebar-head">
        <div>
          <div class="query-brand">智能问数</div>
          <div class="query-brand-meta">数据分析</div>
        </div>
        <button class="query-btn-new" type="button" data-testid="new-conversation" :disabled="isBusy" @click="newConversation">
          新建
        </button>
      </div>

      <div class="query-sidebar-search">
        <input v-model="searchKeyword" class="query-search-input" type="text" placeholder="搜索话题">
      </div>

      <div class="query-session-scroll">
        <div class="query-session-list">
          <button
            v-for="topic in filteredTopics"
            :key="topic.topic_id"
            class="query-session-item"
            type="button"
            :class="{ active: topic.topic_id === topicId }"
            :data-testid="`history-topic-${topic.topic_id}`"
            :disabled="isBusy"
            @click="selectTopic(topic.topic_id)"
          >
            <div class="query-session-title">{{ truncate(topic.title || '新话题', 26) }}</div>
            <div class="query-session-meta">{{ formatTime(topic.updated_at || topic.created_at) }}</div>
          </button>
          <div v-if="!filteredTopics.length" class="query-empty-sessions">暂无话题</div>
        </div>
      </div>
    </aside>

    <main class="query-main">
      <div class="query-messages">
        <div class="query-messages-inner">
          <div class="query-main-head">
            <div>
              <h3>{{ activeTopic ? truncate(activeTopic.title, 48) : '开始一次新的数据分析' }}</h3>
              <p class="query-main-subtitle">围绕数据查询与分析开展连续对话。</p>
            </div>
            <div class="query-model-badge">
              <span>{{ activeProviderConfig?.display_name || '未配置' }}</span>
              <strong>{{ selectedModel || defaultModel || '默认模型' }}</strong>
            </div>
          </div>

          <div v-if="errorText" class="query-error-card query-error-banner">
            <span class="query-error-label">错误</span>
            <span>{{ errorText }}</span>
          </div>

          <div v-if="!providers.length" class="query-config-empty">
            <div class="query-config-empty-title">还没有可用的智能问数模型</div>
            <div class="query-config-empty-text">请先完成模型配置。</div>
          </div>

          <div v-if="!messages.length && !errorText" class="query-empty">
            <div class="query-empty-mark">AI</div>
            <div class="query-empty-title">请输入你的数据问题</div>
            <div class="query-empty-subtitle">支持数据查询、趋势分析与结果可视化。</div>
            <div class="query-suggestions">
              <button
                v-for="suggestion in suggestions"
                :key="suggestion"
                class="query-suggestion"
                type="button"
                :disabled="isBusy"
                @click="handleSuggestion(suggestion)"
              >
                {{ suggestion }}
              </button>
            </div>
          </div>

          <template v-for="msg in messages" :key="msg.id">
            <div v-if="msg.role === 'user'" class="query-message-row query-message-user">
              <div class="query-user-bubble">{{ msg.content }}</div>
            </div>

            <div v-else class="query-message-row query-message-assistant">
              <div class="query-assistant-body">
                <div v-if="hasProcessPanel(msg)" class="query-process-panel">
                  <div class="query-process-summary-row">
                    <button type="button" class="query-process-summary" @click.stop="toggleProcessPanel(msg)">
                      <span class="query-process-summary-label">
                        <span v-if="isActiveTaskStatus(msg.status)" class="query-process-badge-dot" />
                        深度思考
                      </span>
                      <span v-if="!isProcessPanelExpanded(msg) && processSummaryPreview(msg)" class="query-process-summary-preview">
                        {{ processSummaryPreview(msg) }}
                      </span>
                      <svg class="query-process-chevron" :class="{ open: isProcessPanelExpanded(msg) }" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M19 9l-7 7-7-7" />
                      </svg>
                    </button>
                  </div>

                  <div v-if="isProcessPanelExpanded(msg)" class="query-process-content">
                    <div class="query-process-content-inner">
                      <div v-for="block in processBlocksForMessage(msg)" :key="block.id" class="query-step-row">
                        <div v-if="block.kind === 'thinking' && block.text" class="query-process-thought">
                          <div class="query-process-thought-content" v-html="renderMarkdown(block.text)" />
                          <span v-if="msg.status === 'streaming' && block.status === 'streaming'" class="query-cursor">|</span>
                        </div>

                        <ToolOutputRenderer v-else-if="block.kind === 'tool' && block.tool" :tool="block.tool" />
                      </div>
                    </div>
                  </div>
                </div>

                <div v-for="block in finalBlocksForMessage(msg)" :key="block.id" class="query-step-row">
                  <template v-if="block.kind === 'main_text'">
                    <div v-if="displayTextBlock(block)" class="query-main-text">
                      <div v-html="renderMarkdown(displayTextBlock(block))" />
                      <span v-if="msg.status === 'streaming' && block.status === 'streaming'" class="query-cursor">|</span>
                    </div>
                  </template>

                  <div v-else-if="block.kind === 'error' && block.text" class="query-error-card">
                    <span class="query-error-label">错误</span>
                    <span>{{ block.text }}</span>
                  </div>
                </div>

                <div v-if="msg.error && !hasErrorBlock(msg)" class="query-error-card">
                  <span class="query-error-label">错误</span>
                  <span>{{ errorMessage(msg.error) }}</span>
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>

      <div class="query-composer-wrap">
        <form class="query-composer" @submit.prevent="send">
          <div class="query-composer-top">
            <div class="query-composer-control">
              <select v-model="selectedProvider" class="query-select" :disabled="!providers.length || isBusy">
                <option v-for="provider in providers" :key="provider.provider_id" :value="provider.provider_id">
                  {{ provider.display_name || provider.provider_id }}
                </option>
              </select>
            </div>
            <div class="query-composer-control">
              <select v-model="selectedModel" class="query-select" :disabled="!availableModels.length || isBusy">
                <option v-for="modelName in availableModels" :key="modelName" :value="modelName">
                  {{ modelName }}
                </option>
              </select>
            </div>
          </div>

          <div class="query-composer-input-row">
            <textarea
              v-model="inputText"
              class="query-textarea"
              rows="1"
              :disabled="!providers.length || !availableModels.length"
              placeholder="例如：查询最近 30 天工作流发布次数趋势"
              @keydown.ctrl.enter.prevent="send"
              @keydown.meta.enter.prevent="send"
            />
            <div class="query-composer-actions">
              <button
                type="button"
                class="query-composer-action"
                :class="[
                  activeTaskId ? 'query-btn-cancel' : 'query-btn-send',
                  { 'query-composer-action-labeled': activeTaskId }
                ]"
                :disabled="activeTaskId ? false : !canSend"
                :aria-label="activeTaskId ? '取消当前任务' : '发送消息'"
                :title="activeTaskId ? '取消当前任务' : '发送消息'"
                @click="activeTaskId ? cancel() : send()"
              >
                <svg
                  v-if="activeTaskId"
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
                <span v-if="activeTaskId" class="query-composer-action-text">停止回答</span>
              </button>
            </div>
          </div>
        </form>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, triggerRef, watch } from 'vue'
import { marked } from 'marked'
import { createNl2SqlApiClient } from '@/api/nl2sql'
import ToolOutputRenderer from '@/views/intelligence/ToolOutputRenderer.vue'
import { stripChartSpecsFromText } from '@/views/intelligence/chartSpec'
import { describeToolAction, extractToolSkillName, formatSkillBootstrapLabel, isSkillBootstrapPlaceholder } from '@/views/intelligence/toolPresentation'
import {
  createAssistantMessageState,
  hydrateAssistantMessageState,
  processAssistantStreamEvent
} from '@/views/intelligence/messageStream'

marked.setOptions({ breaks: true, gfm: true })

const props = defineProps({
  config: {
    type: Object,
    required: true
  },
  state: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['event', 'consumed-outbound'])

const api = createNl2SqlApiClient({
  baseURL: props.config.apiBaseUrl,
  timeout: 300000,
  defaultHeaders: props.config.headers
})

const suggestions = [
  '各数据层表数量对比',
  '最近 30 天工作流发布次数趋势',
  '各工作流发布操作类型占比'
]

const inputText = ref('')
const searchKeyword = ref('')
const topicId = ref('')
const isSubmitting = ref(false)
const activeTaskId = ref('')
const activeAssistantId = ref('')
const topics = ref([])
const messages = ref([])
const errorText = ref('')
const abortController = ref(null)
const defaultProviderId = ref('')
const defaultModel = ref('')
const providers = ref([])
const selectedProvider = ref('')
const selectedModel = ref('')
const hydratedTopicIds = new Set()

const isInline = computed(() => props.config.displayMode === 'inline')
const historyVisible = computed(() => isInline.value || Boolean(props.state.historyOpen))
const isBusy = computed(() => isSubmitting.value || Boolean(activeTaskId.value))
const activeTopic = computed(() => topics.value.find((topic) => topic.topic_id === topicId.value) || null)
const activeProviderConfig = computed(() => (
  providers.value.find((provider) => provider.provider_id === selectedProvider.value)
  || providers.value[0]
  || null
))
const availableModels = computed(() => {
  const provider = activeProviderConfig.value
  const models = Array.isArray(provider?.models) ? [...provider.models] : []
  const fallbackModel = provider?.default_model || defaultModel.value
  if (fallbackModel && !models.includes(fallbackModel)) models.unshift(fallbackModel)
  return models
})
const canSend = computed(() => (
  Boolean(inputText.value.trim())
  && !isBusy.value
  && Boolean(selectedProvider.value)
  && Boolean(selectedModel.value)
))
const filteredTopics = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase()
  if (!keyword) return topics.value
  return topics.value.filter((topic) => String(topic.title || '').toLowerCase().includes(keyword))
})

watch(
  isBusy,
  (value) => {
    props.state.isBusy = value
  },
  { immediate: true }
)

watch(availableModels, (models) => {
  if (!models.length) {
    selectedModel.value = ''
    return
  }
  if (!models.includes(selectedModel.value)) {
    selectedModel.value = models[0]
  }
})

const truncate = (value, max) => {
  const text = String(value || '新话题')
  return text.length > max ? `${text.slice(0, max)}...` : text
}

const formatTime = (value) => {
  const date = value ? new Date(value) : null
  if (!date || Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}

const normalizeTopic = (topic) => ({
  topic_id: String(topic?.topic_id || ''),
  title: String(topic?.title || '新话题'),
  message_count: Number(topic?.message_count || 0),
  last_message_preview: String(topic?.last_message_preview || ''),
  current_task_id: String(topic?.current_task_id || ''),
  current_task_status: String(topic?.current_task_status || ''),
  created_at: String(topic?.created_at || new Date().toISOString()),
  updated_at: String(topic?.updated_at || new Date().toISOString())
})

const sortTopics = () => {
  topics.value = [...topics.value].sort((a, b) => String(b.updated_at || b.created_at).localeCompare(String(a.updated_at || a.created_at)))
}

const uid = () => `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

const appendUserMessage = (content) => {
  messages.value.push({
    id: `u_${uid()}`,
    role: 'user',
    content,
    created_at: new Date().toISOString()
  })
}

const appendAssistantMessage = () => {
  const assistant = reactive(createAssistantMessageState({
    id: `a_${uid()}`,
    status: 'queued',
    created_at: new Date().toISOString()
  }))
  messages.value.push(assistant)
  return assistant
}

const messageContent = (message) => {
  const content = String(message?.content || '').trim()
  if (content) return content
  const blocks = Array.isArray(message?.blocks) ? message.blocks : []
  return blocks
    .map((block) => String(block?.text || block?.output || '').trim())
    .filter(Boolean)
    .join('\n')
}

const messageFromApi = (item) => {
  if (item?.sender_type === 'user') {
    return {
      id: String(item.message_id || `user_${item.seq_id || uid()}`),
      role: 'user',
      content: messageContent(item),
      created_at: item.created_at || ''
    }
  }
  return reactive(hydrateAssistantMessageState({
    ...item,
    message_id: item?.message_id || `assistant_${item?.seq_id || uid()}`,
    content: messageContent(item),
    status: item?.status || 'success'
  }))
}

const loadTopicMessages = async (targetTopicId) => {
  if (!targetTopicId) {
    messages.value = []
    return
  }
  const page = await api.topicApi.getTopicMessages(targetTopicId, { page: 1, page_size: 200, order: 'asc' })
  messages.value = (page?.items || [])
    .filter((item) => item?.sender_type === 'user' || item?.sender_type === 'assistant')
    .map(messageFromApi)
  hydratedTopicIds.add(targetTopicId)
}

const loadTopics = async () => {
  try {
    const list = await api.topicApi.listTopics()
    topics.value = (Array.isArray(list) ? list : []).map(normalizeTopic).filter((topic) => topic.topic_id)
    sortTopics()
    const nextTopicId = topicId.value || topics.value[0]?.topic_id || ''
    topicId.value = nextTopicId
    await loadTopicMessages(nextTopicId)
  } catch (_error) {
    topics.value = []
    messages.value = []
  }
}

const guardIdle = () => {
  if (!isBusy.value) return true
  errorText.value = '回答中，请先停止'
  emit('event', { name: 'error', payload: errorText.value })
  return false
}

const closeHistory = () => {
  props.state.historyOpen = false
}

const selectTopic = async (targetTopicId) => {
  if (!targetTopicId || targetTopicId === topicId.value || !guardIdle()) return
  topicId.value = targetTopicId
  errorText.value = ''
  await loadTopicMessages(targetTopicId)
  closeHistory()
}

const newConversation = async () => {
  if (!guardIdle()) return
  errorText.value = ''
  searchKeyword.value = ''
  const topic = normalizeTopic(await api.topicApi.createTopic('Widget 会话'))
  if (!topic.topic_id) return
  topics.value = [topic, ...topics.value.filter((item) => item.topic_id !== topic.topic_id)]
  topicId.value = topic.topic_id
  messages.value = []
  hydratedTopicIds.add(topic.topic_id)
  closeHistory()
}

const deleteConversation = async (targetTopicId) => {
  if (!targetTopicId || !guardIdle()) return
  await api.topicApi.deleteTopic(targetTopicId)
  topics.value = topics.value.filter((topic) => topic.topic_id !== targetTopicId)
  hydratedTopicIds.delete(targetTopicId)
  if (topicId.value !== targetTopicId) return
  const nextTopicId = topics.value[0]?.topic_id || ''
  topicId.value = nextTopicId
  await loadTopicMessages(nextTopicId)
}

const ensureTopic = async (title) => {
  if (topicId.value) return topicId.value
  const topic = normalizeTopic(await api.topicApi.createTopic(title || 'Widget 会话'))
  if (!topic.topic_id) return ''
  topics.value = [topic, ...topics.value.filter((item) => item.topic_id !== topic.topic_id)]
  topicId.value = topic.topic_id
  hydratedTopicIds.add(topic.topic_id)
  return topic.topic_id
}

const loadConfig = async () => {
  try {
    const config = await api.runtimeApi.getConfig()
    const enabledProviders = Array.isArray(config?.providers)
      ? config.providers.filter((provider) => provider?.enabled !== false && Array.isArray(provider?.models) && provider.models.length)
      : []
    providers.value = enabledProviders
    defaultProviderId.value = config?.default_provider_id || enabledProviders[0]?.provider_id || ''
    defaultModel.value = config?.default_model || enabledProviders[0]?.default_model || enabledProviders[0]?.models?.[0] || ''
    const resolvedProvider = enabledProviders.find((provider) => provider.provider_id === defaultProviderId.value) || enabledProviders[0] || null
    selectedProvider.value = resolvedProvider?.provider_id || ''
    selectedModel.value = resolvedProvider?.models?.includes(defaultModel.value)
      ? defaultModel.value
      : (resolvedProvider?.default_model || resolvedProvider?.models?.[0] || '')
  } catch (error) {
    errorText.value = String(error?.message || '加载智能问数配置失败')
    emit('event', { name: 'error', payload: errorText.value })
  }
}

const processStreamEvent = (assistant, event) => {
  const beforeContent = assistant.content || ''
  const beforeBlocks = Array.isArray(assistant.renderBlocks) ? assistant.renderBlocks.length : 0
  processAssistantStreamEvent(assistant, event)

  const afterContent = assistant.content || ''
  const afterBlocks = Array.isArray(assistant.renderBlocks) ? assistant.renderBlocks.length : 0
  const data = event?.data || {}
  const legacyText = data?.text || data?.content || event?.content || ''
  if (legacyText && beforeContent === afterContent && beforeBlocks === afterBlocks) {
    processAssistantStreamEvent(assistant, { type: 'text.delta', payload: { text: legacyText } })
  }
  triggerRef(messages)
}

const subscribe = async (taskId, assistant) => {
  abortController.value = new AbortController()
  try {
    await api.taskApi.streamTaskEvents(taskId, {
      signal: abortController.value.signal,
      onEvent: (event) => processStreamEvent(assistant, event)
    })
    const task = await api.taskApi.getTask(taskId)
    assistant.status = String(task?.task_status || '') === 'error' ? 'failed' : 'success'
    if (!assistant.content && !assistant.renderBlocks?.length) {
      processAssistantStreamEvent(assistant, { type: 'done', payload: { content: '已完成', status: 'success' } })
    }
    emit('event', { name: 'message:done', payload: { taskId } })
  } catch (error) {
    if (abortController.value?.signal?.aborted) return
    assistant.status = 'failed'
    assistant.error = { message: String(error?.message || '请求失败') }
    processAssistantStreamEvent(assistant, { type: 'error', payload: assistant.error })
    emit('event', { name: 'error', payload: assistant.error.message })
  } finally {
    activeTaskId.value = ''
    activeAssistantId.value = ''
    abortController.value = null
  }
}

const updateActiveTopicAfterSend = (text, taskId) => {
  const target = topics.value.find((topic) => topic.topic_id === topicId.value)
  if (!target) return
  if (!target.title || target.title === 'Widget 会话' || target.title === '新话题') {
    target.title = truncate(text, 30)
  }
  target.current_task_id = taskId
  target.current_task_status = 'waiting'
  target.last_message_preview = text
  target.message_count = Number(target.message_count || 0) + 1
  target.updated_at = new Date().toISOString()
  sortTopics()
}

const send = async () => {
  const text = inputText.value.trim()
  if (!text || isBusy.value) return
  isSubmitting.value = true
  inputText.value = ''
  errorText.value = ''
  appendUserMessage(text)
  const assistant = appendAssistantMessage()

  try {
    const currentTopicId = await ensureTopic(truncate(text, 30))
    const response = await api.taskApi.deliverMessage({
      topic_id: currentTopicId,
      content: text,
      provider_id: selectedProvider.value,
      model: selectedModel.value,
      debug: false,
      execution_mode: 'auto'
    })
    activeTaskId.value = String(response?.task_id || '')
    activeAssistantId.value = assistant.id
    assistant.task_id = activeTaskId.value
    updateActiveTopicAfterSend(text, activeTaskId.value)
    emit('event', { name: 'message:sent', payload: { taskId: activeTaskId.value, text } })
    if (activeTaskId.value) {
      await subscribe(activeTaskId.value, assistant)
    }
  } catch (error) {
    assistant.status = 'failed'
    assistant.error = { message: String(error?.message || '请求失败') }
    processAssistantStreamEvent(assistant, { type: 'error', payload: assistant.error })
    emit('event', { name: 'error', payload: assistant.error.message })
  } finally {
    isSubmitting.value = false
  }
}

const cancel = async () => {
  const taskId = activeTaskId.value
  if (!taskId) return
  abortController.value?.abort()
  await api.taskApi.cancelTask(taskId)
  activeTaskId.value = ''
  const assistant = messages.value.find((item) => item.id === activeAssistantId.value)
  if (assistant) {
    assistant.status = 'cancelled'
    assistant.error = assistant.error || { message: '任务已取消' }
  }
}

const handleSuggestion = (suggestion) => {
  if (isBusy.value) return
  inputText.value = suggestion
  void send()
}

const cleanTextContent = (value) => String(value || '')
  .replace(/Base directory for this skill:[\s\S]*?(?:ARGUMENTS:\s*[^\n]*\n?)/gi, '')
  .replace(/^ARGUMENTS:\s*[^\n]*\n?/gm, '')
  .replace(/^\s+/, '')

const normalizeInlinePreview = (value) => String(value || '').replace(/\s+/g, ' ').trim()
const truncatePreviewEnd = (value, max) => {
  const text = normalizeInlinePreview(value)
  if (!text) return ''
  return text.length <= max ? text : `${text.slice(0, Math.max(0, max - 3))}...`
}

const hasMeaningfulToolPayload = (value) => {
  if (value == null) return false
  if (typeof value === 'string') return Boolean(value.trim())
  if (Array.isArray(value)) return value.some((item) => hasMeaningfulToolPayload(item))
  if (typeof value === 'object') return Object.values(value).some((item) => hasMeaningfulToolPayload(item))
  return true
}

const shouldRenderToolBlock = (tool) => {
  if (!tool || typeof tool !== 'object') return false
  const action = describeToolAction(tool)
  if (!action.isTrace) return true
  const detail = String(action.preview || action.command || action.path || action.directory || action.pattern || action.description || '').trim()
  return Boolean(detail || hasMeaningfulToolPayload(tool.output))
}

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
  .filter((block) => ['thinking', 'tool'].includes(block.kind))

const finalBlocksForMessage = (msg) => renderBlocksForMessage(msg)
  .filter((block) => ['main_text', 'error'].includes(block.kind))

const displayTextBlock = (block) => stripChartSpecsFromText(cleanTextContent(block?.text)).trim()
const hasErrorBlock = (msg) => renderBlocksForMessage(msg).some((block) => block.kind === 'error' && String(block.text || '').trim())
const errorMessage = (error) => {
  if (!error) return ''
  if (typeof error === 'string') return error
  if (typeof error === 'object') return String(error.message || error.detail || '请求失败')
  return String(error)
}

const hasProcessPanel = (msg) => processBlocksForMessage(msg).length > 0
const isProcessPanelExpanded = (msg) => msg?._processExpanded !== false
const toggleProcessPanel = (msg) => {
  if (!msg) return
  msg._processExpanded = msg._processExpanded === false
}
const isActiveTaskStatus = (status) => ['queued', 'running', 'streaming'].includes(String(status || '').trim())
const processSummaryPreview = (msg) => {
  const blocks = processBlocksForMessage(msg)
  const lastBlock = [...blocks].reverse().find((block) => block.kind === 'thinking' || block.kind === 'tool')
  if (!lastBlock) return ''
  if (lastBlock.kind === 'thinking') return truncatePreviewEnd(lastBlock.text, 72)
  const action = describeToolAction(lastBlock.tool)
  if (lastBlock.tool?._skillBootstrapName) return formatSkillBootstrapLabel(lastBlock.tool._skillBootstrapName)
  return truncatePreviewEnd(action.preview || action.command || action.description || action.kind || '', 72)
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

watch(
  () => props.state.outboundMessage,
  (value) => {
    const text = String(value || '').trim()
    if (!text) return
    inputText.value = text
    emit('consumed-outbound')
    void send()
  }
)

watch(
  () => props.state.cancelSignal,
  (value) => {
    if (!value) return
    void cancel()
  }
)

watch(
  () => props.state.newConversationSignal,
  (value) => {
    if (!value) return
    void newConversation()
  }
)

watch(
  () => props.state.selectConversationSignal,
  (value) => {
    if (!value) return
    void selectTopic(props.state.requestedTopicId)
  }
)

watch(
  () => props.state.deleteConversationSignal,
  (value) => {
    if (!value) return
    void deleteConversation(props.state.deleteRequestedTopicId)
  }
)

onMounted(() => {
  void loadConfig()
  void loadTopics()
})

onBeforeUnmount(() => {
  abortController.value?.abort()
})
</script>
