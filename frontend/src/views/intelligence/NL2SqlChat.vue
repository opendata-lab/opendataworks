<template>
  <div class="query-workbench">
    <aside class="query-sidebar">
      <div class="query-sidebar-head">
        <div class="query-agent-panel">
          <div class="query-brand">智能问数</div>
          <select v-model="selectedAgentId" class="query-agent-select" :disabled="!agents.length">
            <option
              v-for="agent in agents"
              :key="agent.agent_id"
              :value="agent.agent_id"
            >
              {{ agent.name }}
            </option>
          </select>
        </div>
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
            <div class="query-session-title">{{ truncate(topic.title, 26) }}</div>
            <div class="query-session-meta">{{ formatTime(topic.updated_at || topic.created_at) }}</div>
          </button>
          <div v-if="!filteredTopics.length" class="query-empty-sessions">暂无话题</div>
        </div>
      </el-scrollbar>
    </aside>

    <main class="query-main">
      <el-scrollbar ref="messagesScrollbarRef" class="query-messages" @scroll="handleScroll">
        <div class="query-messages-inner">
          <div class="query-main-head">
            <div>
              <h3>{{ activeTopic ? truncate(activeTopic.title, 48) : '开始一次新的数据分析' }}</h3>
              <p class="query-main-subtitle">{{ activeAgent?.description || '围绕数据查询与分析开展连续对话。' }}</p>
            </div>
            <div class="query-model-badge">
              <span>{{ activeProviderConfig?.display_name || '未配置' }}</span>
              <strong>{{ selectedModel || settings.default_model || '默认模型' }}</strong>
            </div>
          </div>

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
              <div class="query-user-bubble">{{ msg.content }}</div>
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
              </div>
            </div>
          </template>
        </div>
      </el-scrollbar>

      <div class="query-composer-wrap">
        <div class="query-composer">
          <div class="query-composer-top">
            <div class="query-composer-control">
              <select v-model="selectedProvider" class="query-select" :disabled="!settings.providers.length">
                <option
                  v-for="provider in settings.providers"
                  :key="provider.provider_id"
                  :value="provider.provider_id"
                >
                  {{ provider.display_name }}
                </option>
              </select>
            </div>
            <div class="query-composer-control">
              <select v-model="selectedModel" class="query-select" :disabled="!availableModels.length">
                <option v-for="model in availableModels" :key="model" :value="model">
                  {{ model }}
                </option>
              </select>
            </div>
          </div>

          <div class="query-composer-input-row">
            <textarea
              v-model="inputText"
              class="query-textarea"
              rows="2"
              :disabled="!settings.providers.length || !availableModels.length"
              placeholder="例如：查询最近 30 天工作流发布次数趋势"
              @keydown.ctrl.enter.prevent="handleSend"
              @keydown.meta.enter.prevent="handleSend"
            />
            <div class="query-composer-actions">
              <button
                type="button"
                class="query-composer-action"
                :class="[
                  composerActionMode === 'cancel' ? 'query-btn-cancel' : 'query-btn-send',
                  { 'query-composer-action-labeled': composerActionMode === 'cancel' }
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
                <span v-if="composerActionMode === 'cancel'" class="query-composer-action-text">停止回答</span>
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
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import { createNl2SqlApiClient } from '@/api/nl2sql'
import ToolOutputRenderer from './ToolOutputRenderer.vue'
import { stripChartSpecsFromText } from './chartSpec'
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

const topics = ref([])
const agents = ref([])
const activeTopicId = ref('')
const selectedAgentId = ref('')
const inputText = ref('')
const searchKeyword = ref('')
const messagesScrollbarRef = ref(null)
const autoScroll = ref(true)
const hydratedIds = new Set()
const taskSubscriptions = new Map()
const pendingSubmitKeys = ref(new Set())

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

const activeTopic = computed(() => topics.value.find((topic) => topic.topic_id === activeTopicId.value) || null)
const activeAgent = computed(() => agents.value.find((agent) => agent.agent_id === selectedAgentId.value) || agents.value[0] || null)
const activeMessages = computed(() => activeTopic.value?.messages || [])
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

const NEW_TOPIC_PENDING_KEY = '__new_topic__'

const normalizePendingTopicKey = (topicId) => String(topicId || NEW_TOPIC_PENDING_KEY)

const isTopicSubmitting = (topicId) => pendingSubmitKeys.value.has(normalizePendingTopicKey(topicId))

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
  && Boolean(selectedAgentId.value)
))
const composerActionDisabled = computed(() => (
  composerActionMode.value === 'cancel'
    ? !activeCancelableMessage.value
    : !canSendMessage.value
))

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
    return formatInShanghai(date, { hour: '2-digit', minute: '2-digit', hour12: false })
  }
  return formatInShanghai(date, { month: '2-digit', day: '2-digit' })
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

const displayTextBlock = (block, msg) => {
  const text = stripChartSpecsFromText(cleanTextContent(block?.text)).trim()
  if (!text) return ''
  return text
}

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
  name: String(agent?.name || '默认智能体'),
  description: String(agent?.description || ''),
  is_default: Boolean(agent?.is_default)
})

const loadAgents = async () => {
  try {
    const list = await agentApi.listAgents()
    const normalized = (Array.isArray(list) ? list : []).map(normalizeAgent).filter((agent) => agent.agent_id)
    agents.value = normalized.length
      ? normalized
      : [{ agent_id: 'agent_default', name: '默认智能问数助手', description: '', is_default: true }]
    const routeAgentId = String(route.query.agent_id || '').trim()
    if (routeAgentId && agents.value.some((agent) => agent.agent_id === routeAgentId)) {
      selectedAgentId.value = routeAgentId
    } else if (!agents.value.some((agent) => agent.agent_id === selectedAgentId.value)) {
      selectedAgentId.value = (agents.value.find((agent) => agent.is_default) || agents.value[0])?.agent_id || ''
    }
  } catch (error) {
    console.warn('load agents failed', error)
    agents.value = [{ agent_id: 'agent_default', name: '默认智能问数助手', description: '', is_default: true }]
    selectedAgentId.value = selectedAgentId.value || 'agent_default'
  }
}

const hydrateTopic = async (topicId) => {
  if (!topicId || hydratedIds.has(topicId)) return

  try {
    const [detail, messagePage] = await Promise.all([
      topicApi.getTopic(topicId),
      topicApi.getTopicMessages(topicId, { page: 1, page_size: 500, order: 'asc' })
    ])
    const target = topics.value.find((topic) => topic.topic_id === topicId)
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
    }

    hydratedIds.add(topicId)
  } catch (error) {
    console.warn('hydrate topic failed', error)
  }
}

const loadTopics = async () => {
  try {
    const list = await topicApi.listTopics(selectedAgentId.value ? { agent_id: selectedAgentId.value } : {})
    topics.value = (Array.isArray(list) ? list : []).map(normalizeTopicSummary)
    sortTopics()
    if (!activeTopicId.value && topics.value.length) {
      activeTopicId.value = topics.value[0].topic_id
    }
    if (activeTopicId.value) {
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
  autoScroll.value = true
  scrollToBottom(true)
}

const handleSelectTopic = async (topicId) => {
  activeTopicId.value = topicId
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
      submitTopicId = created.topic_id
      pendingKey = moveTopicSubmitting('', submitTopicId)
    }

    submitTopicId = activeTopicId.value
    await hydrateTopic(submitTopicId)

    topic = topics.value.find((item) => item.topic_id === submitTopicId) || null
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
      agent_id: selectedAgentId.value,
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

watch(selectedAgentId, async (next, prev) => {
  if (!agentSelectionReady.value || !next || next === prev) return
  stopAllTaskSubscriptions()
  hydratedIds.clear()
  topics.value = []
  activeTopicId.value = ''
  await loadTopics()
})

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

.query-agent-panel {
  flex: 1;
  min-width: 0;
}

.query-brand {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.01em;
  color: #1F1F1F;
}

.query-agent-select {
  width: 100%;
  height: 32px;
  margin-top: 8px;
  padding: 0 9px;
  border: 1px solid #d8e0ec;
  border-radius: 8px;
  background: #f9fafc;
  color: #344054;
  font-size: 13px;
  outline: none;
}

.query-agent-select:focus {
  border-color: #4F81FF;
  background: #ffffff;
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
  text-align: left;
  cursor: pointer;
  transition: background-color 0.2s ease, border-color 0.2s ease;
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
  font-size: 14px;
  font-weight: 500;
  line-height: 1.3;
  color: #595959;
}

.query-session-item.active .query-session-title {
  color: #1F1F1F;
}

.query-session-meta {
  margin-top: 4px;
  font-size: 12px;
  color: #A0AABF;
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

.query-user-bubble {
  max-width: 72%;
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

.query-composer-top {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 12px 14px 0;
}

.query-composer-control {
  display: flex;
  align-items: center;
}

.query-select {
  min-width: 180px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface-muted);
  color: var(--text);
  font-size: 12px;
  outline: none;
}

.query-composer-input-row {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 8px 14px 14px;
}

.query-textarea {
  flex: 1;
  min-height: 46px;
  max-height: 140px;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  color: var(--text);
  font-size: 14px;
  line-height: 1.75;
  font-family: inherit;
}

.query-textarea::placeholder {
  color: var(--text-soft);
}

.query-composer-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-shrink: 0;
}

.query-composer-action {
  min-width: 44px;
  height: 44px;
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
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}

.query-composer-action-labeled {
  width: auto;
  gap: 8px;
  padding: 0 16px;
}

.query-composer-action-text {
  font-size: 13px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
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
