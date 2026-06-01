<template>
  <div class="v2-workbench">
    <!-- Sidebar: topic list + agent selector -->
    <aside class="v2-sidebar">
      <div class="v2-sidebar-head">
        <el-select
          v-model="agentSelectValue"
          class="v2-agent-select"
          :disabled="!agents.length"
          @change="handleAgentChange"
        >
          <template #prefix>
            <svg class="v2-agent-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2V4" />
              <rect x="4" y="6" width="16" height="12" rx="2" />
              <circle cx="9" cy="12" r="1.5" fill="currentColor" stroke="none" />
              <circle cx="15" cy="12" r="1.5" fill="currentColor" stroke="none" />
              <path d="M9 16c1.5 1 4.5 1 6 0" />
            </svg>
          </template>
          <el-option v-for="a in agents" :key="a.agent_id" :label="a.name" :value="a.agent_id" />
        </el-select>
        <button class="v2-btn-new" @click="handleNewTopic">新建</button>
      </div>

      <div class="v2-sidebar-search">
        <input v-model="searchKeyword" class="v2-search-input" type="text" placeholder="搜索话题">
      </div>

      <el-scrollbar class="v2-session-scroll">
        <div class="v2-session-list">
          <button
            v-for="topic in filteredTopics"
            :key="topic.topic_id"
            class="v2-session-item"
            :class="{ active: topic.topic_id === activeTopicId }"
            @click="handleSelectTopic(topic.topic_id)"
          >
            <span class="v2-session-title">{{ topic.title || '新话题' }}</span>
            <span v-if="topic.topic_id === activeTopicId && isStreaming" class="v2-session-loading" title="正在分析中...">
              <svg class="v2-session-spinner" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                <circle class="v2-session-spinner-track" cx="12" cy="12" r="10" stroke-width="3" />
                <path class="v2-session-spinner-head" d="M12 2a10 10 0 0 1 10 10" stroke-width="3" stroke-linecap="round" />
              </svg>
            </span>
            <span v-else class="v2-session-meta">{{ formatTime(topic.updated_at || topic.created_at) }}</span>
          </button>
          <div v-if="!filteredTopics.length" class="v2-empty-sessions">暂无话题</div>
        </div>
      </el-scrollbar>
    </aside>

    <!-- Main chat area -->
    <main class="v2-main">
      <div v-if="messages.length" class="v2-main-top-bar">
        <h4 class="v2-topic-title">{{ activeTopic?.title }}</h4>
      </div>

      <el-scrollbar v-show="messages.length" ref="messagesScrollbarRef" class="v2-messages" @scroll="handleScroll">
        <div class="v2-messages-inner">
          <!-- Message loop -->
          <template v-for="msg in messages" :key="msg.id">
            <!-- User message -->
            <div v-if="msg.role === 'user'" class="v2-msg-row v2-msg-user">
              <div class="v2-user-shell">
                <div class="v2-user-bubble">{{ msg.content }}</div>
                <div class="v2-msg-footer">
                  <span v-if="msg.created_at" class="v2-msg-time">{{ formatMessageTime(msg.created_at) }}</span>
                  <button type="button" class="v2-message-tool" title="复制" aria-label="复制消息" @click.stop="handleCopyMessage(msg)">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><rect x="9" y="9" width="10" height="10" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v1" /></svg>
                  </button>
                </div>
              </div>
            </div>

            <!-- Assistant message -->
            <div v-else class="v2-msg-row v2-msg-assistant">
              <div class="v2-assistant-body">
                <!-- Streaming: render turns from v2 state -->
                <template v-if="msg._v2state">
                  <!-- Loading indicator: waiting for first block -->
                  <div v-if="!msg._v2state.turns.length && isStreaming" class="v2-typing-indicator">
                    <span /><span /><span />
                  </div>

                  <template v-for="(turn, ti) in msg._v2state.turns" :key="ti">
                    <template v-for="block in turn.blocks" :key="block.blockIndex + '-' + ti">
                      <!-- Thinking block -->
                      <div v-if="block.type === 'thinking'" class="v2-process-panel">
                        <button
                          class="v2-process-summary"
                          type="button"
                          @click="toggleThinking(msg.id + '-' + ti + '-' + block.blockIndex)"
                        >
                          <span class="v2-process-label">
                            <span v-if="block.status === 'streaming'" class="v2-badge-dot" />
                            深度思考
                          </span>
                          <span v-if="!thinkingExpanded[msg.id + '-' + ti + '-' + block.blockIndex]" class="v2-process-preview">
                            {{ block.content.slice(0, 80) }}
                          </span>
                          <svg class="v2-chevron" :class="{ expanded: thinkingExpanded[msg.id + '-' + ti + '-' + block.blockIndex] }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6" /></svg>
                        </button>
                        <el-scrollbar v-if="thinkingExpanded[msg.id + '-' + ti + '-' + block.blockIndex]" class="v2-process-content">
                          <div class="v2-process-thought" v-html="renderMarkdown(block.content)" />
                          <span v-if="block.status === 'streaming'" class="v2-cursor">|</span>
                        </el-scrollbar>
                      </div>

                      <!-- Tool use block (chart-producing tools render their chart directly below the block) -->
                      <div v-else-if="block.type === 'tool_use'" class="v2-tool-row">
                        <ToolOutputRenderer :tool="blockToToolProp(block)" />
                      </div>

                      <!-- Text block -->
                      <div v-else-if="block.type === 'text' && block.content" class="v2-text-block">
                        <div v-if="cleanTextForDisplay(block.content)" v-html="renderMarkdown(cleanTextForDisplay(block.content))" />
                        <span v-if="block.status === 'streaming'" class="v2-cursor">|</span>
                      </div>
                    </template>
                  </template>

                  <!-- Error from stream -->
                  <div v-if="msg._v2state.status === 'error'" class="v2-error-card">
                    <span class="v2-error-label">错误</span>
                    {{ msg._v2state.errorText || '流式处理出错' }}
                  </div>
                </template>

                <!-- Fallback for non-assistant or empty v2state -->
                <template v-else>
                  <div v-if="msg.content" class="v2-text-block" v-html="renderMarkdown(msg.content)" />
                </template>

                <!-- Message footer -->
                <div class="v2-msg-footer">
                  <span v-if="msg.created_at" class="v2-msg-time">{{ formatMessageTime(msg.created_at) }}</span>
                  <button type="button" class="v2-message-tool" title="复制" aria-label="复制消息" @click.stop="handleCopyMessage(msg)">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><rect x="9" y="9" width="10" height="10" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v1" /></svg>
                  </button>
                  <button type="button" class="v2-message-tool v2-message-feedback-like" :class="{ active: msg.feedback === 'like' }" title="有帮助" aria-label="有帮助" @click.stop="toggleMessageFeedback(msg, 'like')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="M7 11v10H4a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2h3Z" /><path d="M7 11 12 2a3 3 0 0 1 3 3v4h4a2 2 0 0 1 2 2l-1 8a2 2 0 0 1-2 2H7" /></svg>
                  </button>
                  <button type="button" class="v2-message-tool v2-message-feedback-dislike" :class="{ active: msg.feedback === 'dislike' }" title="没帮助" aria-label="没帮助" @click.stop="toggleMessageFeedback(msg, 'dislike')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="M17 13V3h3a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-3Z" /><path d="M17 13 12 22a3 3 0 0 1-3-3v-4H5a2 2 0 0 1-2-2l1-8a2 2 0 0 1 2-2h11" /></svg>
                  </button>
                </div>
              </div>
            </div>
          </template>
        </div>
      </el-scrollbar>

      <!-- Composer -->
      <div class="v2-composer-bar" :class="{ 'is-landing': !messages.length }">
        <div class="v2-composer-wrap">
          <template v-if="!messages.length">
            <div v-if="!settings.providers.length" class="v2-config-empty">
              <div class="v2-config-empty-title">还没有可用的模型</div>
              <div class="v2-config-empty-text">请先完成模型配置。</div>
            </div>
            <div class="v2-landing-greeting">您好，我是{{ currentAgentName }}。</div>
          </template>

          <!-- Input bar -->
          <div class="v2-composer" :class="{ 'is-focused': inputText }">
            <textarea
              ref="textareaRef"
              v-model="inputText"
              class="v2-textarea"
              :placeholder="isStreaming ? '正在回复中…' : '输入数据问题…'"
              :disabled="isStreaming"
              rows="1"
              @keydown.enter.exact.prevent="handleSend"
              @input="autoResize"
            />
            <button
              type="button"
              class="v2-send-btn"
              :class="{ 'v2-cancel-btn': isStreaming }"
              :disabled="!isStreaming && !inputText.trim()"
              @click="isStreaming ? handleCancel() : handleSend()"
            >
              <svg v-if="isStreaming" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15"><rect x="8" y="8" width="8" height="8" rx="1.5" /></svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15"><line x1="12" y1="19" x2="12" y2="5" /><polyline points="5 12 12 5 19 12" /></svg>
            </button>
          </div>
          <!-- Bottom toolbar -->
          <div class="v2-composer-toolbar">
            <div class="v2-composer-toolbar-left" />
            <div class="v2-composer-toolbar-right">
              <el-dropdown trigger="click" @command="handleModelCommand">
                <button type="button" class="v2-model-btn" title="切换模型">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="13" height="13"><path d="M12 2V4" /><rect x="4" y="6" width="16" height="12" rx="2" /><circle cx="9" cy="12" r="1.5" fill="currentColor" stroke="none" /><circle cx="15" cy="12" r="1.5" fill="currentColor" stroke="none" /></svg>
                  <span class="v2-model-label">{{ selectedModel || '默认' }}</span>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <template v-for="provider in settings.providers" :key="provider.provider_id">
                      <el-dropdown-item
                        v-for="model in provider.models"
                        :key="model"
                        :command="provider.provider_id + '::' + model"
                        :class="{ active: selectedProvider === provider.provider_id && selectedModel === model }"
                      >
                        {{ model }}
                      </el-dropdown-item>
                    </template>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <template v-if="!messages.length">
            <div class="v2-landing-suggestions-title">您可以问我以下问题</div>
            <div class="v2-landing-suggestions">
              <button
                v-for="s in suggestions"
                :key="s"
                class="v2-suggestion-card"
                :disabled="isStreaming"
                @click="handleSuggestion(s)"
              >{{ s }}</button>
            </div>
          </template>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { marked } from 'marked'
import { ElMessage } from 'element-plus'
import { createNl2SqlApiClient } from '@/api/nl2sql'
import ToolOutputRenderer from './ToolOutputRenderer.vue'
import { blockToToolProp, createChatState, processV2Record } from './v2StreamParser'
import { stripChartSpecsFromText } from './chartSpec'

marked.setOptions({ breaks: true, gfm: true })

const route = useRoute()
const router = useRouter()

const api = createNl2SqlApiClient({ timeout: 300000 })
const { topicApi, taskApi, adminApi, agentApi } = api

// ── State ────────────────────────────────────────────────────────────────────
const topics = ref([])
const agents = ref([])
const activeTopicId = ref('')
const messages = ref([])
const settings = reactive({ providers: [], default_provider_id: '', default_model: '' })
const selectedProvider = ref('')
const selectedModel = ref('')
const agentSelectValue = ref('')
const searchKeyword = ref('')
const inputText = ref('')
const isStreaming = ref(false)
const autoScroll = ref(true)

const currentAgentName = computed(() => {
  const currentId = agentSelectValue.value
  const found = agents.value.find((a) => a.agent_id === currentId)
  return found?.name || '智能数据助手'
})
const thinkingExpanded = reactive({})
const messagesScrollbarRef = ref(null)
const textareaRef = ref(null)

let abortController = null

// ── Computed ─────────────────────────────────────────────────────────────────
const filteredTopics = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  return kw
    ? topics.value.filter((t) => (t.title || '').toLowerCase().includes(kw))
    : topics.value
})

const activeTopic = computed(() => topics.value.find((t) => t.topic_id === activeTopicId.value) || null)

const agentSelectOptions = computed(() => agents.value.map((a) => ({ label: a.name, value: a.agent_id })))

// ── Markdown ──────────────────────────────────────────────────────────────────
function renderMarkdown(text) {
  if (!text) return ''
  try {
    return marked.parse(String(text))
  } catch {
    return String(text)
  }
}

// ── Thinking toggle ────────────────────────────────────────────────────────
function toggleThinking(key) {
  thinkingExpanded[key] = !thinkingExpanded[key]
}

// ── Time formatting ────────────────────────────────────────────────────────
function formatTime(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return ''
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}

function formatMessageTime(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return ''
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

// ── Auto-resize textarea ───────────────────────────────────────────────────
function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

// ── Scroll ─────────────────────────────────────────────────────────────────
function scrollToBottom(force = false) {
  if (!force && !autoScroll.value) return
  nextTick(() => {
    const sb = messagesScrollbarRef.value
    if (sb?.setScrollTop) {
      sb.setScrollTop(999999)
    }
  })
}

function handleScroll({ scrollTop, scrollHeight, clientHeight }) {
  autoScroll.value = scrollHeight - scrollTop - clientHeight < 60
}

// ── Data loading ───────────────────────────────────────────────────────────
async function loadSettings() {
  try {
    const data = await adminApi.getSettings()
    settings.providers = Array.isArray(data?.providers) ? data.providers : []
    settings.default_provider_id = String(data?.default_provider_id || '')
    settings.default_model = String(data?.default_model || '')
    if (!selectedModel.value) {
      selectedProvider.value = settings.default_provider_id
      selectedModel.value = settings.default_model
    }
  } catch {
    // non-fatal
  }
}

async function loadAgents() {
  try {
    const list = await agentApi.listAgents()
    const normalized = (Array.isArray(list) ? list : []).map((a) => ({
      agent_id: String(a?.agent_id || ''),
      name: String(a?.name || '默认助手'),
      is_default: Boolean(a?.is_default),
      preset_questions: Array.isArray(a?.preset_questions) ? a.preset_questions.filter(Boolean) : [],
    })).filter((a) => a.agent_id)
    agents.value = normalized.length
      ? normalized
      : [{ agent_id: 'agent_default', name: '默认助手', is_default: true }]
    const routeAgentId = String(route.query.agent_id || '').trim()
    if (routeAgentId && agents.value.some((a) => a.agent_id === routeAgentId)) {
      agentSelectValue.value = routeAgentId
    } else if (!agentSelectValue.value) {
      const def = agents.value.find((a) => a.is_default) || agents.value[0]
      agentSelectValue.value = def?.agent_id || ''
    }
  } catch {
    agents.value = [{ agent_id: 'agent_default', name: '默认助手', is_default: true }]
    agentSelectValue.value = 'agent_default'
  }
}

async function loadTopics() {
  try {
    const params = { page: 1, page_size: 50 }
    if (route.query.agent_id) params.agent_id = route.query.agent_id
    const data = await topicApi.listTopics(params)
    topics.value = Array.isArray(data?.list) ? data.list : (Array.isArray(data) ? data : [])
    if (topics.value.length && !activeTopicId.value) {
      await selectTopic(topics.value[0].topic_id)
    }
  } catch {
    // non-fatal
  }
}

async function selectTopic(topicId) {
  activeTopicId.value = topicId
  try {
    const data = await topicApi.getTopicMessages(topicId, { page: 1, page_size: 500, order: 'asc' })
    const list = Array.isArray(data?.items) ? data.items : (Array.isArray(data) ? data : [])
    messages.value = list.map((m) => hydrateHistoryMessage(m))
    scrollToBottom(true)
  } catch {
    messages.value = []
  }
}

function buildV2StateFromStoredBlocks(item) {
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
    } else if (kind === 'tool_use') {
      // SDK-derived format: flat fields tool_id / tool_name / input / output / is_error
      const block = { turnIndex: 0, blockIndex: blockIdx++, type: 'tool_use', content: '', status: 'done', id: b.tool_id || null, name: b.tool_name || 'Tool', inputJson: '', input: b.input ?? null, output: b.output ?? null, is_error: Boolean(b.is_error) }
      turn.blocks.push(block)
      v2state.blocks.push(block)
    } else if (kind === 'tool' && b?.tool) {
      // Legacy magic-event format: nested b.tool object
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

function hydrateHistoryMessage(m) {
  const role = String(m?.role || m?.sender_type || 'user')
  const content = String(m?.content || '')
  if (role !== 'assistant') {
    return {
      id: String(m?.message_id || m?.id || Math.random()),
      role: 'user',
      content,
      created_at: m?.created_at || null,
      _v2state: null,
    }
  }
  return reactive({
    id: String(m?.message_id || m?.id || Math.random()),
    role: 'assistant',
    content,
    feedback: String(m?.feedback || ''),
    created_at: m?.created_at || null,
    _v2state: reactive(buildV2StateFromStoredBlocks(m)),
  })
}

// ── Chart spec helpers ────────────────────────────────────────────────────
// Inline chart_spec written into the model's prose is stripped from display;
// charts must come from a real tool call (rendered below that tool block).
function cleanTextForDisplay(content) {
  return stripChartSpecsFromText(String(content || '')).trim()
}

// ── Suggestions ───────────────────────────────────────────────────────────
const DEFAULT_SUGGESTIONS = [
  '最近 30 天工作流发布次数趋势',
  '各数据层表数量对比',
  '各工作流发布操作类型占比',
  '有哪些失败的任务实例',
]

const suggestions = computed(() => {
  const agent = agents.value.find((a) => a.agent_id === agentSelectValue.value)
  const questions = Array.isArray(agent?.preset_questions) ? agent.preset_questions.filter(Boolean) : []
  return questions.length ? questions : DEFAULT_SUGGESTIONS
})

function handleSuggestion(text) {
  if (isStreaming.value) return
  inputText.value = text
  nextTick(() => handleSend())
}

// ── Topic management ───────────────────────────────────────────────────────
async function handleNewTopic() {
  if (isStreaming.value) return
  activeTopicId.value = ''
  messages.value = []
  searchKeyword.value = ''
}

async function handleSelectTopic(topicId) {
  if (topicId === activeTopicId.value) return
  if (isStreaming.value) handleCancel()
  await selectTopic(topicId)
}

function handleAgentChange(agentId) {
  agentSelectValue.value = agentId
  const value = String(agentId || '').trim()
  if (String(route.query.agent_id || '').trim() === value) return
  const query = { ...route.query }
  if (value) {
    query.agent_id = value
  } else {
    delete query.agent_id
  }
  router.replace({ path: route.path, query }).catch(() => {})
}

function handleModelCommand(command) {
  const [providerId, model] = String(command || '').split('::')
  if (providerId && model) {
    selectedProvider.value = providerId
    selectedModel.value = model
  }
}

// ── Message Tools ─────────────────────────────────────────────────────────
async function handleCopyMessage(msg) {
  let text = String(msg?.content || '')
  if (msg?._v2state?.turns) {
    const texts = []
    for (const turn of msg._v2state.turns) {
      if (!turn.blocks) continue
      for (const block of turn.blocks) {
        if (block.type === 'text' && block.content) {
          texts.push(cleanTextForDisplay(block.content))
        }
      }
    }
    if (texts.length) text = texts.join('\n\n')
  }
  text = text.trim()
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

async function toggleMessageFeedback(msg, value) {
  if (!msg || typeof msg !== 'object') return
  const previousFeedback = String(msg.feedback || '')
  const nextFeedback = previousFeedback === value ? '' : value
  const topicId = String(activeTopicId.value || '')
  const messageId = String(msg.id || '')

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

// ── Send message ───────────────────────────────────────────────────────────
async function handleSend() {
  const text = inputText.value.trim()
  if (!text || isStreaming.value) return

  inputText.value = ''
  autoResize()

  let topicId = activeTopicId.value
  if (!topicId) {
    try {
      const topic = await topicApi.createTopic(text.slice(0, 60), {
        agent_id: agentSelectValue.value || undefined,
      })
      topicId = topic.topic_id
      activeTopicId.value = topicId
      topics.value.unshift(topic)
    } catch (err) {
      ElMessage.error('创建话题失败: ' + String(err?.message || err))
      return
    }
  }

  // Append user message locally
  messages.value.push({
    id: 'user-' + Date.now(),
    role: 'user',
    content: text,
    created_at: new Date().toISOString(),
    _v2state: null,
  })
  scrollToBottom(true)

  // Prepare assistant placeholder
  const v2state = reactive(createChatState())
  const assistantMsg = reactive({
    id: 'asst-' + Date.now(),
    role: 'assistant',
    content: '',
    thinkingText: '',
    feedback: '',
    created_at: new Date().toISOString(),
    _v2state: v2state,
  })
  messages.value.push(assistantMsg)

  isStreaming.value = true
  abortController = new AbortController()

  try {
    // Deliver message (creates task)
    const taskResp = await taskApi.deliverMessage({
      topic_id: topicId,
      content: text,
      provider_id: selectedProvider.value || undefined,
      model: selectedModel.value || undefined,
      agent_id: agentSelectValue.value || undefined,
    })
    const taskId = taskResp?.task_id
    if (!taskId) throw new Error('任务创建失败，未获取到 task_id')

    // Stream SDK events
    await taskApi.streamSdkEvents(taskId, {
      signal: abortController.signal,
      afterId: 0,
      onRecord: (record) => {
        processV2Record(v2state, record)
        scrollToBottom()
      },
    })
  } catch (err) {
    if (err?.name !== 'AbortError') {
      v2state.status = 'error'
      v2state.errorText = String(err?.message || '请求失败')
      ElMessage.error('请求失败: ' + String(err?.message || err))
    }
  } finally {
    isStreaming.value = false
    abortController = null
    // Refresh topic list to update title
    loadTopics()
    scrollToBottom(true)
  }
}

function handleCancel() {
  abortController?.abort()
  isStreaming.value = false
}

// ── Lifecycle ─────────────────────────────────────────────────────────────
onMounted(async () => {
  await Promise.all([loadSettings(), loadAgents()])
  await loadTopics()
})

watch(() => route.query.agent_id, async () => {
  activeTopicId.value = ''
  messages.value = []
  await loadTopics()
})

onBeforeUnmount(() => {
  abortController?.abort()
})
</script>

<style scoped>
/* ── Root layout ─────────────────────────────────────────────────────────── */
.v2-workbench {
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-columns: 260px 1fr;
  border: 1px solid #E5EAF1;
  border-radius: 18px;
  overflow: hidden;
  background: #F4F5F7;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.035);
  font-family: 'IBM Plex Sans', 'PingFang SC', 'Hiragino Sans GB', sans-serif;
}

@media (min-width: 1280px) {
  .v2-workbench { grid-template-columns: 300px 1fr; }
}

/* ── Sidebar ─────────────────────────────────────────────────────────────── */
.v2-sidebar {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 16px 12px;
  background: #ffffff;
  border-right: 1px solid #E5EAF1;
}

.v2-sidebar-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 16px;
}

.v2-agent-select {
  flex: 1;
  min-width: 0;
}

.v2-agent-icon {
  width: 16px;
  height: 16px;
}

.v2-btn-new {
  flex-shrink: 0;
  padding: 6px 12px;
  border: none;
  border-radius: 6px;
  background: var(--odw-primary);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  transition: background var(--odw-transition);
}

.v2-btn-new:hover { background: var(--odw-primary-light); }

.v2-sidebar-search { padding: 0 8px 12px; }

.v2-search-input {
  width: 100%;
  box-sizing: border-box;
  padding: 6px 10px;
  border: 1px solid #dbe3ef;
  border-radius: 6px;
  font-size: 13px;
  outline: none;
  background: #f9fafc;
}

.v2-search-input:focus { border-color: var(--odw-primary); }

.v2-session-scroll { flex: 1; min-height: 0; }

.v2-session-list { display: flex; flex-direction: column; gap: 2px; padding: 0 8px 8px; }

.v2-session-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background var(--odw-transition);
}

.v2-session-item:hover { background: #f0f3f8; }
.v2-session-item.active { background: #e8eef8; }

.v2-session-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: #1F1F1F;
}

.v2-session-meta { flex-shrink: 0; font-size: 11px; color: #8C8C8C; }

.v2-session-loading {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.v2-session-spinner {
  width: 14px;
  height: 14px;
  color: var(--odw-primary);
  animation: v2-spin 1s linear infinite;
}

.v2-session-spinner-track {
  stroke: rgba(0, 0, 0, 0.05);
}

.v2-session-spinner-head {
  stroke: currentColor;
}

@keyframes v2-spin {
  to { transform: rotate(360deg); }
}

.v2-empty-sessions { padding: 16px 10px; font-size: 13px; color: #8C8C8C; text-align: center; }

/* ── Main ────────────────────────────────────────────────────────────────── */
.v2-main {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #ffffff;
  position: relative;
}

.v2-main-top-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 24px 12px;
  border-bottom: 1px solid #eef1f5;
  flex-shrink: 0;
}

.v2-topic-title {
  flex: 1;
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #162131;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── Messages ────────────────────────────────────────────────────────────── */
.v2-messages { flex: 1; min-height: 0; }

.v2-messages-inner {
  padding-top: 24px;
  padding-bottom: 180px;
  padding-inline: clamp(40px, 5%, 64px);
  max-width: 1280px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* ── Landing (new chat) ──────────────────────────────────────────────────── */
.v2-landing-greeting {
  font-size: 26px;
  font-weight: 600;
  color: #1e293b;
  text-align: center;
  letter-spacing: -0.2px;
  margin-bottom: 32px;
}

.v2-landing-suggestions-title {
  text-align: center;
  color: #64748b;
  font-size: 13px;
  margin-top: 24px;
  margin-bottom: 8px;
}

.v2-landing-suggestions {
  width: 100%;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.v2-suggestion-card {
  text-align: left;
  padding: 14px 16px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #ffffff;
  color: #334155;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.55;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease, color 0.15s ease;
}

.v2-suggestion-card:hover:not(:disabled) {
  border-color: var(--odw-primary);
  background: color-mix(in srgb, var(--odw-primary) 4%, #fff);
  color: #162131;
}

.v2-suggestion-card:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.v2-config-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 0 0 24px;
  gap: 8px;
}

.v2-config-empty-title { font-size: 15px; font-weight: 600; color: #334155; }
.v2-config-empty-text { font-size: 13px; color: #8C8C8C; }

/* ── Message rows ────────────────────────────────────────────────────────── */
.v2-msg-row { display: flex; }

.v2-msg-user { justify-content: flex-end; }

.v2-user-shell { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; max-width: 72%; }

.v2-user-bubble {
  padding: 10px 16px;
  border-radius: 16px 16px 4px 16px;
  background: linear-gradient(135deg, var(--odw-primary) 0%, var(--odw-primary-dark) 100%);
  color: #fff;
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.v2-msg-assistant { justify-content: flex-start; }

.v2-assistant-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 88%;
}

.v2-msg-footer { display: flex; gap: 8px; align-items: center; padding: 2px 0; }
.v2-msg-time { font-size: 11px; color: #A0AABF; }

/* ── Message tools ───────────────────────────────────────────────────────── */
.v2-message-tool {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: #A0AABF;
  cursor: pointer;
  transition: background-color 0.16s ease, border-color 0.16s ease, color 0.16s ease;
}

.v2-message-tool svg {
  width: 13px;
  height: 13px;
  stroke-width: 1.8;
}

.v2-message-tool:hover, .v2-message-tool.active {
  border-color: #D9E2F2;
  background: #ffffff;
  color: var(--odw-primary);
}

.v2-message-tool.v2-message-feedback-dislike.active {
  color: #e63946;
}

/* ── Thinking (深度思考) panel ────────────────────────────────────────────── */
.v2-process-panel {
  border: 1px solid #dbe3ef;
  border-radius: 10px;
  overflow: hidden;
  background: #f9fafc;
}

.v2-process-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 14px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  color: #334155;
  font-weight: 500;
  text-align: left;
  transition: background var(--odw-transition);
}

.v2-process-summary:hover { background: #eff1f5; }

.v2-process-label {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  font-weight: 600;
}

.v2-badge-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--odw-primary);
  animation: v2-pulse 1.4s ease-in-out infinite;
}

@keyframes v2-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(0.85); }
}

.v2-process-preview {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: #8C8C8C;
  font-weight: 400;
}

.v2-chevron {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  color: #8C8C8C;
  transition: transform var(--odw-transition);
}

.v2-chevron.expanded { transform: rotate(180deg); }

.v2-process-content { max-height: min(360px, 50vh); }

.v2-process-thought {
  padding: 12px 16px;
  font-size: 13px;
  line-height: 1.65;
  color: #595959;
}

.v2-process-thought :deep(p) { margin: 0 0 8px; }
.v2-process-thought :deep(p:last-child) { margin: 0; }

/* ── Tool output row ─────────────────────────────────────────────────────── */
.v2-tool-row { border-radius: 10px; overflow: hidden; }

/* ── Text block ──────────────────────────────────────────────────────────── */
.v2-text-block {
  font-size: 14px;
  line-height: 1.65;
  color: #162131;
}

.v2-text-block :deep(p) { margin: 0 0 10px; }
.v2-text-block :deep(p:last-child) { margin: 0; }
.v2-text-block :deep(pre) {
  background: #f3f7fb;
  border-radius: 8px;
  padding: 12px 16px;
  overflow-x: auto;
  font-size: 13px;
}
.v2-text-block :deep(code) { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 13px; }
.v2-text-block :deep(table) { border-collapse: collapse; width: 100%; margin: 10px 0; }
.v2-text-block :deep(th), .v2-text-block :deep(td) { border: 1px solid #dbe3ef; padding: 6px 12px; font-size: 13px; }
.v2-text-block :deep(th) { background: #f4f7fb; font-weight: 600; }

/* ── Error card ──────────────────────────────────────────────────────────── */
.v2-error-card {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 14px;
  border-radius: 8px;
  background: rgba(254, 242, 242, 0.9);
  border: 1px solid rgba(252, 165, 165, 0.5);
  font-size: 13px;
  color: #7f1d1d;
}

.v2-error-label {
  flex-shrink: 0;
  font-weight: 600;
  color: #b91c1c;
}

/* ── Cursor ──────────────────────────────────────────────────────────────── */
.v2-cursor {
  display: inline-block;
  color: var(--odw-primary);
  font-weight: 700;
  animation: v2-blink 1s step-end infinite;
  margin-left: 2px;
}

@keyframes v2-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

/* ── Composer ────────────────────────────────────────────────────────────── */
.v2-composer-bar {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 100%;
  z-index: 10;
  padding-top: 32px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0) 0%, rgba(255, 255, 255, 0.85) 30%, #ffffff 50%);
  transition: background 0.3s ease;
  border-top: none;
}

.v2-composer-bar.is-landing {
  top: 0;
  padding-top: 0;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
}

.v2-composer-wrap {
  max-width: 1280px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
  padding-block: 10px 12px;
  padding-inline: clamp(40px, 5%, 64px);
  transition: max-width 0.3s ease;
}

.v2-composer-bar.is-landing .v2-composer-wrap {
  max-width: 860px;
}

.v2-composer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 10px 10px 18px;
  border: 1px solid #dde2ea;
  border-radius: 16px;
  background: #ffffff;
  transition: border-color var(--odw-transition), box-shadow var(--odw-transition);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.04);
}

.v2-composer:focus-within {
  border-color: #b0bbcc;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08);
}

.v2-textarea {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  resize: none;
  font-size: 14px;
  line-height: 1.55;
  color: #162131;
  font-family: inherit;
  min-height: 22px;
  max-height: 160px;
  overflow-y: auto;
}

.v2-textarea::placeholder { color: #A0AABF; }

.v2-composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  padding-inline: 4px;
}

.v2-composer-toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.v2-composer-toolbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.v2-model-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #8a96a6;
  font-size: 12px;
  cursor: pointer;
  transition: color var(--odw-transition), background var(--odw-transition);
  max-width: 160px;
}

.v2-model-btn:hover { color: #4a5568; background: #eef1f5; }

.v2-model-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.v2-send-btn {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 8px;
  background: #e8eaed;
  color: #606878;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: background var(--odw-transition), color var(--odw-transition), transform var(--odw-transition);
}

.v2-send-btn:not(:disabled):not(.v2-cancel-btn) {
  background: linear-gradient(135deg, var(--odw-primary) 0%, var(--odw-primary-dark) 100%);
  color: #fff;
}

.v2-send-btn:disabled { opacity: 0.4; cursor: default; }
.v2-send-btn:not(:disabled):hover { transform: scale(1.06); }
.v2-send-btn:active { transform: scale(0.94); }

.v2-cancel-btn {
  background: linear-gradient(135deg, #7f1d1d 0%, #b91c1c 100%) !important;
  color: #fff !important;
}

/* ── Typing indicator ────────────────────────────────────────────────────── */
.v2-typing-indicator {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 8px 2px;
}

.v2-typing-indicator span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--odw-primary);
  opacity: 0.35;
  animation: v2-typing 1.2s ease-in-out infinite;
}

.v2-typing-indicator span:nth-child(2) { animation-delay: 0.2s; }
.v2-typing-indicator span:nth-child(3) { animation-delay: 0.4s; }

@keyframes v2-typing {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.35; }
  30% { transform: translateY(-5px); opacity: 1; }
}

/* ── Responsive ──────────────────────────────────────────────────────────── */
@media (max-width: 960px) {
  .v2-workbench { grid-template-columns: 1fr; }
  .v2-sidebar { display: none; }
}

@media (max-width: 640px) {
  .v2-messages-inner { padding-top: 16px; padding-bottom: 160px; padding-inline: 16px; }
  .v2-composer-wrap { padding-block: 10px 14px; padding-inline: 16px; }
  .v2-user-shell { max-width: 90%; }
  .v2-assistant-body { max-width: 100%; }
  .v2-landing-greeting { font-size: 18px; }
  .v2-landing-suggestions { grid-template-columns: 1fr; }
}
</style>
