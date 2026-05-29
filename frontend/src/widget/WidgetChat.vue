<template>
  <div
    class="query-workbench"
    :class="{
      'is-inline': isInline,
      'is-floating': !isInline,
      'is-history-open': historyVisible
    }"
  >
    <div v-if="historyVisible" class="query-sidebar-backdrop" @click="closeHistory" />

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
            <div class="query-main-head-left">
              <button
                v-if="isInline"
                class="query-btn-history-toggle"
                type="button"
                aria-label="历史会话"
                title="历史会话"
                @click="toggleHistory"
              >
                <svg class="query-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <line x1="3" y1="12" x2="21" y2="12"></line>
                  <line x1="3" y1="6" x2="21" y2="6"></line>
                  <line x1="3" y1="18" x2="21" y2="18"></line>
                </svg>
              </button>
              <div>
                <h3>{{ activeTopic ? truncate(activeTopic.title, 48) : '开始一次新的数据分析' }}</h3>
                <p class="query-main-subtitle">围绕数据查询与分析开展连续对话。</p>
              </div>
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
                <template v-if="msg._v2state">
                  <div v-if="!msg._v2state.turns.length && isActiveTask(msg)" class="query-typing-indicator">
                    <span /><span /><span />
                  </div>

                  <template v-for="(turn, ti) in msg._v2state.turns" :key="ti">
                    <template v-for="block in turn.blocks" :key="block.blockIndex + '-' + ti">
                      <!-- Thinking block -->
                      <div v-if="block.type === 'thinking'" class="query-process-panel">
                        <button
                          type="button"
                          class="query-process-summary"
                          @click.stop="toggleThinking(msg.id + '-' + ti + '-' + block.blockIndex)"
                        >
                          <span class="query-process-label">
                            <span v-if="block.status === 'streaming'" class="query-process-badge-dot" />
                            深度思考
                          </span>
                          <span v-if="!isThinkingExpanded(msg.id + '-' + ti + '-' + block.blockIndex) && block.content" class="query-process-preview">
                            {{ block.content.slice(0, 72) }}
                          </span>
                          <svg class="query-process-chevron" :class="{ open: isThinkingExpanded(msg.id + '-' + ti + '-' + block.blockIndex) }" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M19 9l-7 7-7-7" />
                          </svg>
                        </button>
                        <div v-if="isThinkingExpanded(msg.id + '-' + ti + '-' + block.blockIndex)" class="query-process-content">
                          <div class="query-process-thought" v-html="renderMarkdown(block.content)" />
                          <span v-if="block.status === 'streaming'" class="query-cursor">|</span>
                        </div>
                      </div>

                      <!-- Tool use block -->
                      <div v-else-if="block.type === 'tool_use'" class="query-tool-row">
                        <ToolOutputRenderer :tool="blockToToolProp(block)" />
                      </div>

                      <!-- Text block -->
                      <div v-else-if="block.type === 'text' && block.content" class="query-main-text">
                        <div v-if="cleanTextForDisplay(block.content)" v-html="renderMarkdown(cleanTextForDisplay(block.content))" />
                        <span v-if="block.status === 'streaming'" class="query-cursor">|</span>
                        <ToolOutputRenderer
                          v-for="(spec, si) in extractedChartSpecs(block.content)"
                          :key="si"
                          :tool="chartSpecToToolProp(spec)"
                        />
                      </div>
                    </template>
                  </template>

                  <div v-if="msg._v2state.status === 'error'" class="query-error-card">
                    <span class="query-error-label">错误</span>
                    <span>{{ msg._v2state.errorText || '处理出错' }}</span>
                  </div>
                </template>

                <template v-else>
                  <div v-if="msg.content" class="query-main-text" v-html="renderMarkdown(msg.content)" />
                  <div v-if="msg.error" class="query-error-card">
                    <span class="query-error-label">错误</span>
                    <span>{{ errorMessage(msg.error) }}</span>
                  </div>
                </template>
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
import { extractChartSpecsFromText, stripChartSpecsFromText } from '@/views/intelligence/chartSpec'
import { blockToToolProp, createChatState, processV2Record } from '@/views/intelligence/v2StreamParser'

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
const pendingOutboundMessage = ref('')
const hydratedTopicIds = new Set()

const isInline = computed(() => props.config.displayMode === 'inline')
const historyVisible = computed(() => Boolean(props.state.historyOpen))
const isBusy = computed(() => isSubmitting.value || Boolean(activeTaskId.value))
const agentId = computed(() => String(props.config.agentId || '').trim())
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
const canDeliverPendingOutbound = computed(() => (
  Boolean(pendingOutboundMessage.value)
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

  const fmtDate = (d, options) => d.toLocaleString('zh-CN', { hour12: false, ...options })
  const now = new Date()
  const dateKey = fmtDate(date, { year: 'numeric', month: '2-digit', day: '2-digit' })
  const nowKey = fmtDate(now, { year: 'numeric', month: '2-digit', day: '2-digit' })

  if (dateKey === nowKey) {
    const diffMs = now.getTime() - date.getTime()
    const diffSeconds = Math.floor(diffMs / 1000)
    const diffMinutes = Math.floor(diffSeconds / 60)
    if (diffSeconds < 60) return '刚刚'
    if (diffMinutes < 60) return `${diffMinutes}分钟前`
    return fmtDate(date, { hour: '2-digit', minute: '2-digit' })
  }

  const [y, m, d] = dateKey.split('/').map(Number)
  const [ny, nm, nd] = nowKey.split('/').map(Number)
  const diffDays = Math.floor((new Date(ny, nm - 1, nd) - new Date(y, m - 1, d)) / 86400000)

  if (diffDays === 1) return '1天前'
  if (diffDays <= 7) return `${diffDays}天前`

  const dateYear = fmtDate(date, { year: 'numeric' })
  const nowYear = fmtDate(now, { year: 'numeric' })
  if (dateYear === nowYear) {
    return fmtDate(date, { month: '2-digit', day: '2-digit' })
  }
  return fmtDate(date, { year: 'numeric', month: '2-digit', day: '2-digit' })
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

const thinkingExpanded = reactive({})

const toggleThinking = (key) => {
  thinkingExpanded[key] = !thinkingExpanded[key]
}

const isThinkingExpanded = (key) => Boolean(thinkingExpanded[key])

const isActiveTask = (msg) => Boolean(activeTaskId.value && msg.task_id === activeTaskId.value)

const cleanTextForDisplay = (content) => stripChartSpecsFromText(String(content || '')).trim()

const extractedChartSpecs = (content) => extractChartSpecsFromText(String(content || ''))

const chartSpecToToolProp = (spec) => ({ name: 'render_chart', input: null, output: spec, status: 'success', id: null, _callComplete: true, _runtimeStarted: true })

const buildV2StateFromStoredBlocks = (item) => {
  const v2state = createChatState()
  v2state.status = 'done'
  const storedBlocks = Array.isArray(item?.blocks) ? item.blocks : []
  const turn = { turnIndex: 0, blocks: [], status: 'done' }
  v2state.turns.push(turn)
  let blockIdx = 0
  for (const b of storedBlocks) {
    const kind = String(b?.kind || b?.type || '')
    if (kind === 'thinking' && b?.text) {
      const block = { turnIndex: 0, blockIndex: blockIdx++, type: 'thinking', content: b.text, status: 'done', id: null, name: null, inputJson: '', input: null, output: null, is_error: false }
      turn.blocks.push(block)
      v2state.blocks.push(block)
    } else if (kind === 'main_text' && b?.text) {
      const block = { turnIndex: 0, blockIndex: blockIdx++, type: 'text', content: b.text, status: 'done', id: null, name: null, inputJson: '', input: null, output: null, is_error: false }
      turn.blocks.push(block)
      v2state.blocks.push(block)
    } else if (kind === 'tool' && b?.tool) {
      const block = { turnIndex: 0, blockIndex: blockIdx++, type: 'tool_use', content: '', status: 'done', id: b.tool.id || b.tool._toolId || null, name: b.tool.name || 'Tool', inputJson: '', input: b.tool.input, output: b.tool.output, is_error: b.tool.status === 'failed' }
      turn.blocks.push(block)
      v2state.blocks.push(block)
    }
  }
  const content = String(item?.content || '')
  if (!turn.blocks.length && content) {
    const block = { turnIndex: 0, blockIndex: 0, type: 'text', content, status: 'done', id: null, name: null, inputJson: '', input: null, output: null, is_error: false }
    turn.blocks.push(block)
    v2state.blocks.push(block)
  }
  return v2state
}

const appendAssistantMessage = (taskId) => {
  const id = `a_${uid()}`
  const assistant = reactive({
    id,
    role: 'assistant',
    content: '',
    status: 'queued',
    task_id: taskId || '',
    error: null,
    created_at: new Date().toISOString(),
    _v2state: reactive(createChatState()),
  })
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
      created_at: item.created_at || '',
      _v2state: null,
    }
  }
  const id = String(item?.message_id || `assistant_${item?.seq_id || uid()}`)
  return reactive({
    id,
    role: 'assistant',
    content: messageContent(item),
    status: item?.status || 'success',
    task_id: String(item?.task_id || ''),
    error: item?.error || null,
    created_at: item?.created_at || '',
    _v2state: reactive(buildV2StateFromStoredBlocks(item)),
  })
}

const loadTopicMessages = async (targetTopicId) => {
  if (!targetTopicId) {
    messages.value = []
    return
  }
  if (String(targetTopicId).startsWith('topic_mock_')) {
    return
  }
  try {
    const page = await api.topicApi.getTopicMessages(targetTopicId, { page: 1, page_size: 200, order: 'asc' })
    messages.value = (page?.items || [])
      .filter((item) => item?.sender_type === 'user' || item?.sender_type === 'assistant')
      .map(messageFromApi)
    hydratedTopicIds.add(targetTopicId)
  } catch (error) {
    console.warn('[OpenDataWorksWidget] failed to load messages:', error)
    messages.value = []
  }
}

const loadTopics = async () => {
  try {
    const list = await api.topicApi.listTopics({ agent_id: agentId.value || undefined })
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

const ensureAgentConfigured = () => true

const guardIdle = () => {
  if (!isBusy.value) return true
  errorText.value = '回答中，请先停止'
  emit('event', { name: 'error', payload: errorText.value })
  return false
}

const closeHistory = () => {
  props.state.historyOpen = false
}

const toggleHistory = () => {
  props.state.historyOpen = !props.state.historyOpen
}

const selectTopic = async (targetTopicId) => {
  if (!targetTopicId || targetTopicId === topicId.value || !guardIdle()) return
  topicId.value = targetTopicId
  errorText.value = ''
  await loadTopicMessages(targetTopicId)
  closeHistory()
}

const newConversation = async () => {
  if (!ensureAgentConfigured()) return
  if (!guardIdle()) return
  errorText.value = ''
  searchKeyword.value = ''
  const topic = normalizeTopic(await api.topicApi.createTopic('Widget 会话', { agent_id: agentId.value || undefined }))
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
  if (!ensureAgentConfigured()) return ''
  if (topicId.value) return topicId.value
  const topic = normalizeTopic(await api.topicApi.createTopic(title || 'Widget 会话', { agent_id: agentId.value || undefined }))
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
    if (agentId.value === 'demo') {
      const mockProviders = [{
        provider_id: 'mock',
        display_name: '演示模型 (Mock AI)',
        models: ['mock-gpt-4o', 'mock-claude-3.5'],
        default_model: 'mock-gpt-4o'
      }]
      providers.value = mockProviders
      defaultProviderId.value = 'mock'
      defaultModel.value = 'mock-gpt-4o'
      selectedProvider.value = 'mock'
      selectedModel.value = 'mock-gpt-4o'
      errorText.value = ''
      console.warn('[OpenDataWorksWidget] Running in DEMO Mock mode because backend is unreachable.')
    } else {
      errorText.value = String(error?.message || '加载智能问数配置失败')
      emit('event', { name: 'error', payload: errorText.value })
    }
  }
}

const subscribe = async (taskId, assistant) => {
  abortController.value = new AbortController()
  try {
    await api.taskApi.streamSdkEvents(taskId, {
      signal: abortController.value.signal,
      afterId: 0,
      onRecord: (record) => {
        processV2Record(assistant._v2state, record)
        triggerRef(messages)
      }
    })
    assistant.status = assistant._v2state.status === 'error' ? 'failed' : 'success'
    emit('event', { name: 'message:done', payload: { taskId } })
  } catch (error) {
    if (abortController.value?.signal?.aborted) return
    assistant.status = 'failed'
    assistant.error = { message: String(error?.message || '请求失败') }
    assistant._v2state.status = 'error'
    assistant._v2state.errorText = assistant.error.message
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
  if (!ensureAgentConfigured()) return
  isSubmitting.value = true
  inputText.value = ''
  errorText.value = ''
  const mockTaskId = `task_mock_${uid()}`
  appendUserMessage(text)
  const assistant = appendAssistantMessage(selectedProvider.value === 'mock' ? mockTaskId : '')

  if (selectedProvider.value === 'mock') {
    if (!topicId.value) {
      const mockTopicId = `topic_mock_${uid()}`
      const newTopic = {
        topic_id: mockTopicId,
        title: truncate(text, 30),
        message_count: 2,
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString()
      }
      topics.value = [newTopic, ...topics.value]
      topicId.value = mockTopicId
      hydratedTopicIds.add(mockTopicId)
    } else {
      updateActiveTopicAfterSend(text, '')
    }

    activeTaskId.value = mockTaskId
    activeAssistantId.value = assistant.id
    assistant.status = 'running'

    emit('event', { name: 'message:sent', payload: { taskId: activeTaskId.value, text } })

    const replyText = `您好！检测到当前处于本地静态演示（Demo）模式，后端 API 服务未连接。

这是模拟的 AI 回复：
- 您提问的内容是：”${text}”
- 智能小组件包含悬浮触发（Floating）、侧边栏内嵌（Inline）、API 控制等多种集成能力。
- 接入真实后端服务时，请在引入小组件的 HTML 中指定正确的 \`data-api-base-url\`。

如有其他疑问，请随时提问！`

    const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
    const v2 = assistant._v2state
    const mockProgressSteps = [
      { id: 'tool-0', step: '正在解析问题语义...', label: 'text-to-sql' },
      { id: 'tool-1', step: '正在匹配数据库 Schema...', label: 'text-to-sql' },
      { id: 'tool-2', step: '正在生成执行 SQL 语句...', label: 'run-sql' },
      { id: 'tool-3', step: '已成功获取数据，正在整理报表...', label: 'render-chart' },
    ]

    try {
      processV2Record(v2, { record_type: 'stream', data: { type: 'message_start', usage: {} } })
      triggerRef(messages)

      for (let i = 0; i < mockProgressSteps.length; i++) {
        if (activeTaskId.value !== assistant.task_id) break
        const { id, step, label } = mockProgressSteps[i]
        processV2Record(v2, { record_type: 'stream', data: { type: 'content_block_start', index: i, content_block: { type: 'tool_use', id, name: label } } })
        triggerRef(messages)
        await delay(500)
        processV2Record(v2, { record_type: 'stream', data: { type: 'content_block_stop', index: i } })
        processV2Record(v2, { record_type: 'tool_result', data: { tool_use_id: id, content: `[Demo] 已完成: ${step}` } })
        triggerRef(messages)
      }

      const textIdx = mockProgressSteps.length
      processV2Record(v2, { record_type: 'stream', data: { type: 'content_block_start', index: textIdx, content_block: { type: 'text' } } })
      for (const char of replyText) {
        if (activeTaskId.value !== assistant.task_id) break
        processV2Record(v2, { record_type: 'stream', data: { type: 'content_block_delta', index: textIdx, delta: { type: 'text_delta', text: char } } })
        triggerRef(messages)
        await delay(15)
      }
      processV2Record(v2, { record_type: 'stream', data: { type: 'content_block_stop', index: textIdx } })
      processV2Record(v2, { record_type: 'done', data: {} })
      triggerRef(messages)

      assistant.status = 'success'
      emit('event', { name: 'message', payload: { id: assistant.id, content: replyText } })
    } catch (e) {
      console.error(e)
    } finally {
      activeTaskId.value = ''
      isSubmitting.value = false
    }
    return
  }

  try {
    const currentTopicId = await ensureTopic(truncate(text, 30))
    const response = await api.taskApi.deliverMessage({
      topic_id: currentTopicId,
      content: text,
      provider_id: selectedProvider.value,
      model: selectedModel.value,
      agent_id: agentId.value || undefined,
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
    assistant._v2state.status = 'error'
    assistant._v2state.errorText = assistant.error.message
    emit('event', { name: 'error', payload: assistant.error.message })
  } finally {
    isSubmitting.value = false
  }
}

const sendPendingOutbound = () => {
  const text = pendingOutboundMessage.value.trim()
  if (!text || !canDeliverPendingOutbound.value) return
  pendingOutboundMessage.value = ''
  inputText.value = text
  void send()
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

watch(
  () => props.state.outboundMessage,
  (value) => {
    const text = String(value || '').trim()
    if (!text) return
    pendingOutboundMessage.value = text
    inputText.value = text
    emit('consumed-outbound')
    sendPendingOutbound()
  }
)

watch(canDeliverPendingOutbound, (value) => {
  if (value) sendPendingOutbound()
})

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
