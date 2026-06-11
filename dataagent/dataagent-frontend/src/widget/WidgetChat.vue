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
        <button class="query-btn-new" type="button" data-testid="new-conversation" @click="newConversation">
          新建会话
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
            @click="selectTopic(topic.topic_id)"
          >
            <div class="query-session-title">{{ truncate(topic.title || '新话题', 26) }}</div>
            <div v-if="isTopicWorking(topic)" class="query-session-loading" title="正在分析中...">
              <svg class="query-session-spinner" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                <circle class="query-session-spinner-track" cx="12" cy="12" r="10" stroke-width="3" />
                <path class="query-session-spinner-head" d="M12 2a10 10 0 0 1 10 10" stroke-width="3" stroke-linecap="round" />
              </svg>
            </div>
            <div v-else class="query-session-meta">
              <span v-if="topicBadgeKind(topic) === 'error'" class="query-session-dot is-error" title="执行失败" />
              <span v-else-if="topicBadgeKind(topic) === 'suspended'" class="query-session-dot is-suspended" title="已取消" />
              {{ formatTime(topic.updated_at || topic.created_at) }}
            </div>
          </button>
          <div v-if="!filteredTopics.length" class="query-empty-sessions">暂无话题</div>
        </div>
      </div>
    </aside>

    <main class="query-main">
      <div v-if="copyNotice" class="query-copy-toast" role="status">{{ copyNotice }}</div>

      <div ref="messagesEl" class="query-messages" @scroll="onMessagesScroll">
        <div class="query-messages-inner" :class="{ 'is-empty': !messages.length }">
          <div v-if="errorText" class="query-error-card query-error-banner">
            <span class="query-error-label">错误</span>
            <span>{{ errorText }}</span>
          </div>

          <template v-for="msg in messages" :key="msg.id">
            <div v-if="msg.role === 'user'" class="query-message-row query-message-user">
              <div class="query-user-message-shell">
                <div class="query-user-bubble">{{ msg.content }}</div>
                <div class="query-message-footer query-message-footer-user">
                  <span v-if="msg.created_at" class="query-message-time">{{ formatMessageTime(msg.created_at) }}</span>
                  <button
                    type="button"
                    class="query-message-tool query-message-copy"
                    title="复制"
                    aria-label="复制消息"
                    :data-testid="`copy-message-${msg.id}`"
                    @click.stop="handleCopyMessage(msg)"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><rect x="9" y="9" width="10" height="10" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v1" /></svg>
                  </button>
                </div>
              </div>
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
                          <svg class="query-process-chevron" :class="{ open: isThinkingExpanded(msg.id + '-' + ti + '-' + block.blockIndex) }" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M9 18l6-6-6-6" />
                          </svg>
                        </button>
                        <div v-if="isThinkingExpanded(msg.id + '-' + ti + '-' + block.blockIndex)" class="query-process-content">
                          <div class="query-process-thought" v-html="renderMarkdown(block.content)" />
                          <span v-if="block.status === 'streaming'" class="query-cursor">|</span>
                        </div>
                      </div>

                      <!-- Tool use block (chart-producing tools render their chart directly below the block) -->
                      <div v-else-if="block.type === 'tool_use'" class="query-tool-row">
                        <ToolOutputRenderer :tool="blockToToolProp(block)" />
                      </div>

                      <!-- Text block (inline chart_spec rendered as a real chart) -->
                      <div v-else-if="block.type === 'text' && block.content" class="query-main-text">
                        <template v-for="(seg, si) in answerSegments(block.content)" :key="si">
                          <div v-if="seg.type === 'text'" v-html="renderMarkdown(seg.value)" />
                          <ChartSpecView v-else :spec="seg.spec" />
                        </template>
                        <span v-if="block.status === 'streaming'" class="query-cursor">|</span>
                      </div>
                    </template>
                  </template>

                  <div v-if="msg._v2state.status === 'error'" class="query-error-card">
                    <span class="query-error-label">错误</span>
                    <span>{{ msg._v2state.errorText || '处理出错' }}</span>
                  </div>
                </template>

                <template v-else>
                  <div v-if="msg.content" class="query-main-text">
                    <template v-for="(seg, si) in answerSegments(msg.content)" :key="si">
                      <div v-if="seg.type === 'text'" v-html="renderMarkdown(seg.value)" />
                      <ChartSpecView v-else :spec="seg.spec" />
                    </template>
                  </div>
                  <div v-if="msg.error" class="query-error-card">
                    <span class="query-error-label">错误</span>
                    <span>{{ extractErrorText(msg.error) || '请求失败' }}</span>
                  </div>
                </template>
                <!-- Files generated by this run: direct download cards -->
                <div v-if="msg.attachments?.length" class="query-msg-attachments">
                  <a
                    v-for="file in msg.attachments"
                    :key="file.rel_path"
                    class="query-msg-attachment"
                    :href="attachmentDownloadUrl(file)"
                    download
                    :title="'下载 ' + file.name"
                  >
                    <svg class="query-msg-attachment-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><path d="M14 2v6h6" /></svg>
                    <span class="query-msg-attachment-name">{{ file.name }}</span>
                    <span class="query-msg-attachment-size">{{ formatBytes(file.size) }}</span>
                    <svg class="query-msg-attachment-dl" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M12 3v12m0 0 4-4m-4 4-4-4M5 21h14" /></svg>
                  </a>
                </div>
                <div class="query-message-footer query-message-footer-assistant">
                  <span v-if="msg.created_at" class="query-message-time">{{ formatMessageTime(msg.created_at) }}</span>
                  <button
                    type="button"
                    class="query-message-tool query-message-copy"
                    title="复制"
                    aria-label="复制消息"
                    :data-testid="`copy-message-${msg.id}`"
                    @click.stop="handleCopyMessage(msg)"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><rect x="9" y="9" width="10" height="10" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v1" /></svg>
                  </button>
                  <button
                    type="button"
                    class="query-message-tool query-message-feedback query-message-feedback-like"
                    :class="{ active: msg.feedback === 'like' }"
                    title="有帮助"
                    aria-label="有帮助"
                    :data-testid="`feedback-like-${msg.id}`"
                    @click.stop="toggleMessageFeedback(msg, 'like')"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="M7 11v10H4a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2h3Z" /><path d="M7 11 12 2a3 3 0 0 1 3 3v4h4a2 2 0 0 1 2 2l-1 8a2 2 0 0 1-2 2H7" /></svg>
                  </button>
                  <button
                    type="button"
                    class="query-message-tool query-message-feedback query-message-feedback-dislike"
                    :class="{ active: msg.feedback === 'dislike' }"
                    title="没帮助"
                    aria-label="没帮助"
                    :data-testid="`feedback-dislike-${msg.id}`"
                    @click.stop="toggleMessageFeedback(msg, 'dislike')"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="M17 13V3h3a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-3Z" /><path d="M17 13 12 22a3 3 0 0 1-3-3v-4H5a2 2 0 0 1-2-2l1-8a2 2 0 0 1 2-2h11" /></svg>
                  </button>
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>

      <!-- Composer bar -->
      <form class="query-composer-bar" :class="{ 'is-landing': !messages.length }" @submit.prevent="send">
        <div class="query-composer-wrap">
          <template v-if="!messages.length">
            <div v-if="!providers.length" class="query-config-empty">
              <div class="query-config-empty-title">还没有可用的模型</div>
              <div class="query-config-empty-text">请先完成模型配置。</div>
            </div>
            <div class="query-landing-greeting">您好，我是{{ agentName }}。</div>
          </template>

          <!-- Input pill -->
          <div class="query-composer">
            <textarea
              v-model="inputText"
              class="query-textarea"
              rows="1"
              placeholder="输入数据问题…"
              @keydown.enter="onEnterKey"
              @input="autoResizeTextarea"
            />
            <button
              type="button"
              class="query-send-btn"
              :class="{ 'query-cancel-btn': activeTaskId }"
              :disabled="activeTaskId ? false : !canSend"
              :aria-label="activeTaskId ? '取消当前任务' : '发送消息'"
              @click="activeTaskId ? cancel() : send()"
            >
              <svg v-if="activeTaskId" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15">
                <rect x="8" y="8" width="8" height="8" rx="1.5" />
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15">
                <line x1="12" y1="19" x2="12" y2="5" /><polyline points="5 12 12 5 19 12" />
              </svg>
            </button>
          </div>
          <!-- Toolbar row -->
          <div class="query-composer-toolbar">
            <div class="query-composer-hint">Enter 发送，Shift + Enter 换行</div>
            <div class="query-model-selector">
              <select v-model="selectedProvider" class="query-model-select" :disabled="!providers.length || isBusy" title="切换提供商">
                <option v-for="provider in providers" :key="provider.provider_id" :value="provider.provider_id">
                  {{ provider.display_name || provider.provider_id }}
                </option>
              </select>
              <select v-model="selectedModel" class="query-model-select" :disabled="!availableModels.length || isBusy" title="切换模型">
                <option v-for="modelName in availableModels" :key="modelName" :value="modelName">{{ modelName }}</option>
              </select>
            </div>
          </div>

          <template v-if="!messages.length">
            <div class="query-landing-suggestions-title">您可以问我以下问题</div>
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
          </template>
        </div>
      </form>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, triggerRef, watch } from 'vue'
import { createNl2SqlApiClient } from '@/api/nl2sql'
import ToolOutputRenderer from '@/views/intelligence/ToolOutputRenderer.vue'
import ChartSpecView from '@/views/intelligence/ChartSpecView.vue'
import { splitChartSpecText, stripChartSpecsFromText } from '@/views/intelligence/chartSpec'
import { blockToToolProp, processV2Record } from '@/views/intelligence/v2StreamParser'
import { extractErrorText, isPlainEnterSubmit, renderMarkdown as renderMarkdownBase } from '@/views/intelligence/chatMessage'
import { useNl2SqlChat } from '@/views/intelligence/useNl2SqlChat'
import { useChatMessageActions } from '@/views/intelligence/useChatMessageActions'

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

const DEFAULT_SUGGESTIONS = [
  '各数据层表数量对比',
  '最近 30 天工作流发布次数趋势',
  '各工作流发布操作类型占比'
]
const TOPIC_STATUS_REFRESH_INTERVAL_MS = 3000

const agentPresetQuestions = ref([])
const agentName = ref('智能数据助手')
const suggestions = computed(() => agentPresetQuestions.value.length ? agentPresetQuestions.value : DEFAULT_SUGGESTIONS)

// widget-only UI state
const inputSource = ref('typed')
const pendingOutboundMessage = ref('')
const copyNotice = ref('')

const isInline = computed(() => props.config.displayMode === 'inline')
const agentId = computed(() => String(props.config.agentId || '').trim())

// Shared NL2SQL conversation engine. The widget keeps its own demo/mock send,
// tracking, outbound API, and shadow-DOM template; everything else is the engine.
const chat = useNl2SqlChat({
  api,
  getAgentId: () => agentId.value,
  messagePageSize: 500,
  topicTitleLength: 60,
  listTopicsParams: () => ({ page: 1, page_size: 50, agent_id: agentId.value || undefined }),
  afterRun: () => loadTopics(),
  emitEvent: (event) => emit('event', event),
})
const {
  topics, topicId, messages, errorText,
  providers, defaultProviderId, defaultModel, selectedProvider, selectedModel,
  inputText, searchKeyword,
  isSubmitting, activeTaskId, activeAssistantId, abortController, hydratedTopicIds,
  isBusy, availableModels, canSend, filteredTopics,
  isTopicWorking, topicBadgeKind, upsertTopicAtTop, updateActiveTopicAfterSend,
  appendUserMessage, appendAssistantMessage,
  toggleThinking, isThinkingExpanded, isActiveTask,
  loadConfig: loadRuntimeConfig, loadTopics, refreshTopics,
  send: sendReal, cancel,
  selectTopic: selectTopicEngine, newConversation: newConversationEngine, deleteConversation,
} = chat

const historyVisible = computed(() => isInline.value || Boolean(props.state.historyOpen))
const hasWorkingTopicRecord = computed(() => topics.value.some((topic) => isTopicWorking(topic)))
const canDeliverPendingOutbound = computed(() => (
  Boolean(pendingOutboundMessage.value)
  && !isBusy.value
  && Boolean(selectedProvider.value)
  && Boolean(selectedModel.value)
))

watch(
  isBusy,
  (value) => {
    props.state.isBusy = value
  },
  { immediate: true }
)

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

const formatMessageTime = (value) => {
  const date = value ? new Date(value) : null
  if (!date || Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}

const uid = () => `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

// Inline chart_spec written into the model's prose is stripped from plain-text
// uses (copy/preview); rendering splits it into text/chart segments instead.
const cleanTextForDisplay = (content) => stripChartSpecsFromText(String(content || '')).trim()

// Message markdown: workspace-relative file links the agent emits
// (`output/...`, `uploads/...`) become download URLs for the active topic.
const renderMarkdown = (text) => renderMarkdownBase(text, { resolveFileHref: resolveWorkspaceFileHref })
const resolveWorkspaceFileHref = (relPath) => (
  topicId.value ? api.topicApi.fileUrl(topicId.value, relPath, { download: true }) : ''
)
const attachmentDownloadUrl = (file) => api.topicApi.fileUrl(topicId.value, file.rel_path, { download: true })
const formatBytes = (size) => {
  const n = Number(size) || 0
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

// Split answer prose into ordered text/chart segments so an inline chart_spec
// (fenced, tagged, or raw JSON) renders as a real chart instead of leaking JSON.
const answerSegments = (content) => splitChartSpecText(String(content || ''))

let copyNoticeTimer = null
const showCopyNotice = (message) => {
  copyNotice.value = String(message || '已复制')
  if (copyNoticeTimer) window.clearTimeout(copyNoticeTimer)
  copyNoticeTimer = window.setTimeout(() => {
    copyNotice.value = ''
    copyNoticeTimer = null
  }, 1400)
}

const { handleCopyMessage, toggleMessageFeedback } = useChatMessageActions({
  api,
  topicId,
  cleanText: cleanTextForDisplay,
  notifyCopied: showCopyNotice,
  notifyError: (message) => emit('event', { name: 'error', payload: message }),
  emitEvent: (event) => emit('event', event),
})
const closeHistory = () => {
  props.state.historyOpen = false
}

// Engine navigation, plus closing the floating history drawer.
const selectTopic = async (targetTopicId) => {
  if (!targetTopicId || targetTopicId === topicId.value) return
  await selectTopicEngine(targetTopicId)
  closeHistory()
}

const newConversation = async () => {
  await newConversationEngine()
  closeHistory()
}

// Runtime config with the widget's demo/mock fallback when the backend is
// unreachable (only for the built-in demo agent).
const loadConfig = async () => {
  try {
    await loadRuntimeConfig()
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

// Demo/mock send: drives the engine's message primitives locally with a
// scripted reply when running the built-in demo agent without a backend.
const runMockSend = async (text) => {
  isSubmitting.value = true
  inputText.value = ''
  errorText.value = ''
  const mockTaskId = `task_mock_${uid()}`
  appendUserMessage(text)
  const assistant = appendAssistantMessage(mockTaskId)

  if (!topicId.value) {
    const mockTopicId = `topic_mock_${uid()}`
    const newTopic = {
      topic_id: mockTopicId,
      title: truncate(text, 30),
      message_count: 2,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString()
    }
    upsertTopicAtTop(newTopic)
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
}

// Track the send, then dispatch to the demo/mock flow or the shared engine.
const send = async () => {
  const text = inputText.value.trim()
  if (!text || isBusy.value) return
  const currentInputSource = inputSource.value
  inputSource.value = 'typed'
  try {
    props.state.track?.('message_send', {
      input_source: currentInputSource,
      length: text.length,
      provider_id: selectedProvider.value,
      model: selectedModel.value,
      topic_id: topicId.value || null
    })
  } catch (_e) { /* best-effort */ }
  if (selectedProvider.value === 'mock') {
    await runMockSend(text)
  } else {
    await sendReal()
  }
}

const sendPendingOutbound = () => {
  const text = pendingOutboundMessage.value.trim()
  if (!text || !canDeliverPendingOutbound.value) return
  pendingOutboundMessage.value = ''
  inputText.value = text
  inputSource.value = 'outbound'
  void send()
}

const handleSuggestion = (suggestion) => {
  if (isBusy.value) return
  inputText.value = suggestion
  inputSource.value = 'suggestion'
  void send()
}


const autoResizeTextarea = (event) => {
  const el = event.target
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 160)}px`
}

// Enter 发送，Shift + Enter 换行;输入法组合输入期间的回车用于确认候选词,不发送。
const onEnterKey = (event) => {
  if (!isPlainEnterSubmit(event)) return
  event.preventDefault()
  send()
}

// Pin the message list to the latest content while streaming, but only when the
// user is already near the bottom (same rule as the portal's autoScroll).
const messagesEl = ref(null)
const autoScroll = ref(true)
const onMessagesScroll = () => {
  const el = messagesEl.value
  if (!el) return
  autoScroll.value = el.scrollHeight - el.scrollTop - el.clientHeight < 60
}
const scrollMessagesToBottom = () => {
  nextTick(() => {
    const el = messagesEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}
watch(messages, () => {
  if (autoScroll.value) scrollMessagesToBottom()
}, { deep: true, flush: 'post' })

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

let topicStatusRefreshTimer = null
const stopTopicStatusRefresh = () => {
  if (!topicStatusRefreshTimer) return
  window.clearInterval(topicStatusRefreshTimer)
  topicStatusRefreshTimer = null
}
const refreshTopicStatuses = async () => {
  if (!hasWorkingTopicRecord.value) {
    stopTopicStatusRefresh()
    return
  }
  try {
    await refreshTopics()
  } catch {
    // Keep the current list; the next interval will retry while a topic is marked running.
  }
}
const startTopicStatusRefresh = () => {
  if (topicStatusRefreshTimer) return
  topicStatusRefreshTimer = window.setInterval(() => {
    void refreshTopicStatuses()
  }, TOPIC_STATUS_REFRESH_INTERVAL_MS)
}
watch(
  hasWorkingTopicRecord,
  (value) => {
    if (value) startTopicStatusRefresh()
    else stopTopicStatusRefresh()
  },
  { immediate: true }
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

onMounted(async () => {
  await loadConfig()
  if (agentId.value && agentId.value !== 'demo') {
    try {
      const agentProfile = await api.agentApi.getAgent(agentId.value)
      if (agentProfile?.name) agentName.value = String(agentProfile.name)
      const questions = Array.isArray(agentProfile?.preset_questions) ? agentProfile.preset_questions.filter(Boolean) : []
      agentPresetQuestions.value = questions.slice(0, 3)
    } catch {
      // non-fatal, fall back to defaults
    }
  }
  void loadTopics()
})

onBeforeUnmount(() => {
  stopTopicStatusRefresh()
  if (copyNoticeTimer) window.clearTimeout(copyNoticeTimer)
  abortController.value?.abort()
})
</script>

<style scoped>
.query-msg-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.query-msg-attachment {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 320px;
  padding: 8px 12px;
  border: 1px solid #e5eaf1;
  border-radius: 10px;
  background: #f8fafc;
  color: #1f2937;
  font-size: 13px;
  text-decoration: none;
}

.query-msg-attachment:hover {
  background: #eef2f7;
  border-color: #d4dce6;
}

.query-msg-attachment-icon {
  flex: none;
  color: var(--odw-widget-color, #4a90a4);
}

.query-msg-attachment-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.query-msg-attachment-size {
  flex: none;
  font-size: 11px;
  color: #9aa4b2;
}

.query-msg-attachment-dl {
  flex: none;
  color: #6b7280;
}

.query-msg-attachment:hover .query-msg-attachment-dl {
  color: #111827;
}

.query-user-message-shell {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  max-width: 100%;
}

.query-message-footer {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  min-height: 24px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.16s ease;
}

.query-message-row:hover .query-message-footer,
.query-message-footer:focus-within {
  opacity: 1;
  pointer-events: auto;
}

.query-message-footer-user {
  justify-content: flex-end;
}

.query-message-footer-assistant {
  justify-content: flex-start;
}

.query-message-time {
  font-size: 11px;
  color: #8a96a8;
}

.query-message-tool {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #8a96a8;
  cursor: pointer;
  transition: background 120ms ease, color 120ms ease;
}

.query-message-tool svg {
  width: 14px;
  height: 14px;
  stroke-width: 2;
}

.query-message-tool:hover,
.query-message-tool.active {
  background: #eef4ff;
  color: var(--odw-widget-color, #4A90A4);
}

.query-message-feedback-dislike.active {
  background: #fff1f0;
  color: #d93025;
}

.query-copy-toast {
  position: absolute;
  top: 14px;
  left: 50%;
  z-index: 20;
  transform: translateX(-50%);
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.88);
  color: #fff;
  font-size: 12px;
  line-height: 1.4;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.18);
  pointer-events: none;
}
</style>
