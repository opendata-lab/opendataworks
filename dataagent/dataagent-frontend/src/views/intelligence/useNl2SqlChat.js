// Shared NL2SQL conversation engine.
//
// Owns the stateful lifecycle that the portal chat (NL2SqlChatV2.vue) and the
// embeddable widget (WidgetChat.vue) had each reimplemented: provider/model
// config, the active conversation's messages, and the
// send -> deliver task -> stream SDK events -> reconcile -> detach/cancel flow,
// plus the leave-while-running idiom for switching / new / delete.
//
// The two components keep their own templates, routing, analytics, demo/mock
// mode, and (for the portal) session-audit facets. Divergent concerns are passed
// in as options; component-specific topic-list loading can bypass `loadTopics`
// and write the exposed `topics` ref directly.

import { computed, reactive, ref, triggerRef, watch } from 'vue'
import { topicStatusKind } from './topicStatus'
import {
  compareTopicsByRecency,
  extractErrorText,
  hydrateMessageFromApi,
  normalizeTopic,
} from './chatMessage'
import { createChatState, processV2Record } from './v2StreamParser'

const noop = () => {}
const uid = () => `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

const truncate = (value, max) => {
  const text = String(value || '新话题')
  return text.length > max ? `${text.slice(0, max)}...` : text
}

export function useNl2SqlChat(options) {
  const {
    api,
    getAgentId = () => '',
    messagePageSize = 200,
    topicTitleLength = 30,
    reloadTopicsAfterRun = false,
    // Params for the default `loadTopics`. Components with bespoke list loading
    // (portal audit facets) can ignore `loadTopics` and write `topics` directly.
    listTopicsParams = () => ({ agent_id: getAgentId() || undefined }),
    emitEvent = noop,   // ({ name, payload }) -> void
    notifyError = noop, // (message) -> void
  } = options

  // ── State ────────────────────────────────────────────────────────────────
  const topics = ref([])
  const topicId = ref('')
  const messages = ref([])
  const errorText = ref('')

  const providers = ref([])
  const defaultProviderId = ref('')
  const defaultModel = ref('')
  const selectedProvider = ref('')
  const selectedModel = ref('')

  const inputText = ref('')
  const searchKeyword = ref('')
  const thinkingExpanded = reactive({})

  const isSubmitting = ref(false)
  const activeTaskId = ref('')
  const activeAssistantId = ref('')
  const abortController = ref(null)

  const hydratedTopicIds = new Set()

  // ── Computed ───────────────────────────────────────────────────────────────
  const isBusy = computed(() => isSubmitting.value || Boolean(activeTaskId.value))
  const activeTopic = computed(() => topics.value.find((t) => t.topic_id === topicId.value) || null)
  const activeProviderConfig = computed(() => (
    providers.value.find((p) => p.provider_id === selectedProvider.value)
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
  // Base keyword filter; components layer their own status/user/sort facets.
  const filteredTopics = computed(() => {
    const keyword = searchKeyword.value.trim().toLowerCase()
    if (!keyword) return topics.value
    return topics.value.filter((t) => String(t.title || '').toLowerCase().includes(keyword))
  })

  // Keep the selected model valid as the provider/model set changes.
  watch(availableModels, (models) => {
    if (!models.length) {
      selectedModel.value = ''
      return
    }
    if (!models.includes(selectedModel.value)) selectedModel.value = models[0]
  })

  // ── Session-list status badges ───────────────────────────────────────────
  const isTopicWorking = (topic) =>
    (topic?.topic_id === topicId.value && Boolean(activeTaskId.value)) ||
    topicStatusKind(topic?.current_task_status) === 'running'
  const topicBadgeKind = (topic) => topicStatusKind(topic?.current_task_status)

  // Reflect a task's terminal/active status onto its topic so the badge stays
  // accurate without reloading the list.
  const setTopicTaskStatus = (targetTopicId, status) => {
    const target = topics.value.find((t) => t.topic_id === targetTopicId)
    if (target) target.current_task_status = String(status || '')
  }

  const sortTopics = () => {
    topics.value = [...topics.value].sort(compareTopicsByRecency)
  }
  const moveTopicToTop = (targetTopicId) => {
    const target = topics.value.find((t) => t.topic_id === targetTopicId)
    if (!target) return
    topics.value = [target, ...topics.value.filter((t) => t.topic_id !== targetTopicId)]
  }
  const upsertTopicAtTop = (topic) => {
    if (!topic?.topic_id) return
    topics.value = [topic, ...topics.value.filter((t) => t.topic_id !== topic.topic_id)]
  }

  // ── Messages ───────────────────────────────────────────────────────────────
  const appendUserMessage = (content) => {
    messages.value.push({ id: `u_${uid()}`, role: 'user', content, created_at: new Date().toISOString() })
  }
  const appendAssistantMessage = (taskId) => {
    const assistant = reactive({
      id: `a_${uid()}`,
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

  const toggleThinking = (key) => { thinkingExpanded[key] = !thinkingExpanded[key] }
  const isThinkingExpanded = (key) => Boolean(thinkingExpanded[key])
  const isActiveTask = (msg) => Boolean(activeTaskId.value && msg.task_id === activeTaskId.value)

  const loadTopicMessages = async (targetTopicId) => {
    if (!targetTopicId) {
      messages.value = []
      return
    }
    if (String(targetTopicId).startsWith('topic_mock_')) return
    try {
      const page = await api.topicApi.getTopicMessages(targetTopicId, { page: 1, page_size: messagePageSize, order: 'asc' })
      messages.value = (page?.items || [])
        .filter((item) => item?.sender_type === 'user' || item?.sender_type === 'assistant')
        .map(hydrateMessageFromApi)
      hydratedTopicIds.add(targetTopicId)
    } catch (error) {
      console.warn('[useNl2SqlChat] failed to load messages:', error)
      messages.value = []
    }
  }

  // ── Config / topics ────────────────────────────────────────────────────────
  const loadConfig = async () => {
    const config = await api.runtimeApi.getConfig()
    const enabledProviders = Array.isArray(config?.providers)
      ? config.providers.filter((p) => p?.enabled !== false && Array.isArray(p?.models) && p.models.length)
      : []
    providers.value = enabledProviders
    defaultProviderId.value = config?.default_provider_id || enabledProviders[0]?.provider_id || ''
    defaultModel.value = config?.default_model || enabledProviders[0]?.default_model || enabledProviders[0]?.models?.[0] || ''
    const resolved = enabledProviders.find((p) => p.provider_id === defaultProviderId.value) || enabledProviders[0] || null
    selectedProvider.value = resolved?.provider_id || ''
    selectedModel.value = resolved?.models?.includes(defaultModel.value)
      ? defaultModel.value
      : (resolved?.default_model || resolved?.models?.[0] || '')
    return config
  }

  const loadTopics = async () => {
    try {
      const list = await api.topicApi.listTopics(listTopicsParams())
      const currentTopic = activeTopic.value ? { ...activeTopic.value } : null
      const nextTopics = (Array.isArray(list) ? list : []).map(normalizeTopic).filter((t) => t.topic_id)
      if (currentTopic?.topic_id && !nextTopics.some((t) => t.topic_id === currentTopic.topic_id)) {
        nextTopics.unshift(currentTopic)
      }
      topics.value = nextTopics
      sortTopics()
      if (currentTopic?.topic_id && currentTopic.topic_id === topicId.value) {
        moveTopicToTop(currentTopic.topic_id)
      }
      // Default to a fresh conversation on open instead of auto-selecting the
      // latest topic, so a new question can be asked immediately.
      await loadTopicMessages(topicId.value || '')
    } catch {
      topics.value = []
      messages.value = []
    }
  }

  const ensureTopic = async (title) => {
    if (topicId.value) return topicId.value
    const topic = normalizeTopic(await api.topicApi.createTopic(title || '新会话', { agent_id: getAgentId() || undefined }))
    if (!topic.topic_id) return ''
    upsertTopicAtTop(topic)
    topicId.value = topic.topic_id
    hydratedTopicIds.add(topic.topic_id)
    return topic.topic_id
  }

  const updateActiveTopicAfterSend = (text, taskId) => {
    const target = topics.value.find((t) => t.topic_id === topicId.value)
    if (!target) return
    if (!target.title || target.title === '新话题') target.title = truncate(text, topicTitleLength)
    target.current_task_id = taskId
    target.current_task_status = 'waiting'
    target.last_message_preview = text
    target.message_count = Number(target.message_count || 0) + 1
    target.updated_at = new Date().toISOString()
    moveTopicToTop(target.topic_id)
  }

  // ── Run lifecycle ────────────────────────────────────────────────────────
  const subscribe = async (taskId, assistant) => {
    // The owning topic is captured up front because a new conversation can change
    // topicId mid-stream; the engine writes task status onto its topic directly.
    const runTopicId = topicId.value
    try {
      await api.taskApi.streamSdkEvents(taskId, {
        signal: abortController.value?.signal,
        afterId: 0,
        onRecord: (record) => {
          processV2Record(assistant._v2state, record)
          triggerRef(messages)
        },
      })
      // A hard backend failure can end the stream without a terminal done/error
      // record, leaving _v2state on 'streaming'. Reconcile against the task status.
      if (assistant._v2state.status !== 'error' && !abortController.value?.signal?.aborted) {
        try {
          const taskState = await api.taskApi.getTask(taskId)
          if (String(taskState?.task_status || '') === 'error') {
            assistant._v2state.status = 'error'
            assistant._v2state.errorText = extractErrorText(taskState?.error) || '会话执行失败'
            assistant.error = { message: assistant._v2state.errorText }
          } else if (assistant._v2state.status !== 'done') {
            assistant._v2state.status = 'done'
          }
        } catch { /* keep current state if status lookup fails */ }
      }
      assistant.status = assistant._v2state.status === 'error' ? 'failed' : 'success'
      setTopicTaskStatus(runTopicId, assistant._v2state.status === 'error' ? 'error' : 'finished')
      emitEvent({ name: 'message:done', payload: { taskId } })
    } catch (error) {
      // Detached / cancelled locally: the aborted fetch rejects with AbortError.
      if (error?.name === 'AbortError') return
      assistant.status = 'failed'
      assistant.error = { message: String(error?.message || '请求失败') }
      assistant._v2state.status = 'error'
      assistant._v2state.errorText = assistant.error.message
      setTopicTaskStatus(runTopicId, 'error')
      emitEvent({ name: 'error', payload: assistant.error.message })
    } finally {
      activeTaskId.value = ''
      activeAssistantId.value = ''
      abortController.value = null
    }
  }

  // Detach from the in-flight run by aborting the local stream (chat v2's
  // handleCancel idiom): the abort makes subscribe() bail via AbortError, the
  // backend task keeps running and stays recoverable from history. Unlike
  // cancel(), this does not hit the backend cancel API.
  const detach = () => {
    abortController.value?.abort()
    activeTaskId.value = ''
    activeAssistantId.value = ''
    isSubmitting.value = false
  }

  // Real backend send. Demo/mock flows live in the component and drive the
  // exposed message/state primitives directly.
  const send = async () => {
    const text = inputText.value.trim()
    if (!text || isBusy.value) return
    isSubmitting.value = true
    inputText.value = ''
    errorText.value = ''
    appendUserMessage(text)
    const assistant = appendAssistantMessage('')

    // Create the controller before the network round-trip so a mid-request
    // detach aborts the run via this same controller's signal.
    abortController.value = new AbortController()
    try {
      const currentTopicId = await ensureTopic(truncate(text, topicTitleLength))
      const response = await api.taskApi.deliverMessage({
        topic_id: currentTopicId,
        content: text,
        provider_id: selectedProvider.value,
        model: selectedModel.value,
        agent_id: getAgentId() || undefined,
        debug: false,
        execution_mode: 'auto',
      })
      const taskId = String(response?.task_id || '')
      // Detached while the request was in flight: leave the backend task running.
      if (!taskId || abortController.value?.signal?.aborted) return
      activeTaskId.value = taskId
      activeAssistantId.value = assistant.id
      assistant.task_id = taskId
      updateActiveTopicAfterSend(text, taskId)
      emitEvent({ name: 'message:sent', payload: { taskId, text } })
      await subscribe(taskId, assistant)
    } catch (error) {
      if (error?.name === 'AbortError') return
      assistant.status = 'failed'
      assistant.error = { message: String(error?.message || '请求失败') }
      assistant._v2state.status = 'error'
      assistant._v2state.errorText = assistant.error.message
      emitEvent({ name: 'error', payload: assistant.error.message })
      notifyError(assistant.error.message)
    } finally {
      isSubmitting.value = false
      if (reloadTopicsAfterRun) loadTopics()
    }
  }

  // Explicit stop: abort locally AND cancel the backend task (marks suspended).
  const cancel = async () => {
    const taskId = activeTaskId.value
    if (!taskId) return
    abortController.value?.abort()
    await api.taskApi.cancelTask(taskId)
    activeTaskId.value = ''
    setTopicTaskStatus(topicId.value, 'suspended')
    const assistant = messages.value.find((m) => m.id === activeAssistantId.value)
    if (assistant) {
      assistant.status = 'cancelled'
      assistant.error = assistant.error || { message: '任务已取消' }
    }
  }

  // ── Leave-while-running navigation ─────────────────────────────────────────
  const selectTopic = async (targetTopicId) => {
    if (!targetTopicId || targetTopicId === topicId.value) return
    if (isBusy.value) detach()
    topicId.value = targetTopicId
    errorText.value = ''
    await loadTopicMessages(targetTopicId)
  }

  const newConversation = async () => {
    if (isBusy.value) detach()
    errorText.value = ''
    searchKeyword.value = ''
    topicId.value = ''
    messages.value = []
  }

  const deleteConversation = async (targetTopicId) => {
    if (!targetTopicId) return
    if (isBusy.value && targetTopicId === topicId.value) detach()
    await api.topicApi.deleteTopic(targetTopicId)
    topics.value = topics.value.filter((t) => t.topic_id !== targetTopicId)
    hydratedTopicIds.delete(targetTopicId)
    if (topicId.value !== targetTopicId) return
    const nextTopicId = topics.value[0]?.topic_id || ''
    topicId.value = nextTopicId
    await loadTopicMessages(nextTopicId)
  }

  return {
    // state
    topics, topicId, messages, errorText,
    providers, defaultProviderId, defaultModel, selectedProvider, selectedModel,
    inputText, searchKeyword, thinkingExpanded,
    isSubmitting, activeTaskId, activeAssistantId, abortController,
    hydratedTopicIds,
    // computed
    isBusy, activeTopic, activeProviderConfig, availableModels, canSend, filteredTopics,
    // badges / helpers
    isTopicWorking, topicBadgeKind, setTopicTaskStatus,
    sortTopics, moveTopicToTop, upsertTopicAtTop,
    appendUserMessage, appendAssistantMessage,
    toggleThinking, isThinkingExpanded, isActiveTask,
    // actions
    loadConfig, loadTopics, loadTopicMessages, ensureTopic, updateActiveTopicAfterSend,
    subscribe, send, detach, cancel,
    selectTopic, newConversation, deleteConversation,
  }
}
