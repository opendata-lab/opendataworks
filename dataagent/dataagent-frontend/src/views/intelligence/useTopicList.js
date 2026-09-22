// Topic list: loading, ordering, search and the per-topic status badges.
//
// Split out of useNl2SqlChat so the conversation kernel could move into
// packages/agent-conversation. The SDK owns a single conversation; the list of
// conversations is a product-shell concern, and OntoFoundry brings its own
// session switcher rather than reusing this one.
//
// The conversation's `topicId` and `activeTaskId` come in as refs. The badges
// genuinely need them — a topic is "working" when it is the open one and a task
// is live — but the dependency only points this way: nothing here writes
// conversation state.

import { computed, ref } from 'vue'
import { topicStatusKind, isActiveStatusKind } from './topicStatus'
import { compareTopicsByRecency, normalizeTopic } from './topicHelpers.js'

export function useTopicList({ api, topicId, activeTaskId, listTopicsParams }) {
  const topics = ref([])
  const searchKeyword = ref('')

  const activeTopic = computed(() => topics.value.find((t) => t.topic_id === topicId.value) || null)

  const filteredTopics = computed(() => {
    const keyword = searchKeyword.value.trim().toLowerCase()
    if (!keyword) return topics.value
    return topics.value.filter((t) => String(t.title || '').toLowerCase().includes(keyword))
  })

  // ── Session-list status badges ───────────────────────────────────────────
  const isTopicWorking = (topic) =>
    (topic?.topic_id === topicId.value && Boolean(activeTaskId.value)) ||
    topicStatusKind(topic?.current_task_status) === 'running'
  const topicBadgeKind = (topic) => topicStatusKind(topic?.current_task_status)
  // A run parked at waiting_input ('awaiting') is still live, so it counts as
  // active — re-selecting the topic must resume its stream to deliver the answer.
  const isTopicTaskActive = (topic) => isActiveStatusKind(topicStatusKind(topic?.current_task_status))

  // Reflect a task's terminal/active status onto its topic so the badge stays
  // accurate without reloading the list.
  const setTopicTaskStatus = (targetTopicId, status) => {
    const target = topics.value.find((t) => t.topic_id === targetTopicId)
    if (target) target.current_task_status = String(status || '')
  }

  // Recency comes from the server's updated_at only: the backend bumps it when
  // messages persist, when a task starts running, and when a run reaches a
  // terminal state, so refreshing the list (working-topic poll / afterRun) is
  // what keeps the order current. No local timestamps are mixed in, avoiding
  // client/server clock skew and timestamp-format mismatches.
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

  const refreshTopics = async () => {
    const data = await api.topicApi.listTopics(listTopicsParams())
    const currentTopic = activeTopic.value ? { ...activeTopic.value } : null
    const list = Array.isArray(data?.list) ? data.list : (Array.isArray(data) ? data : [])
    const nextTopics = list.map(normalizeTopic).filter((t) => t.topic_id)
    if (currentTopic?.topic_id && !nextTopics.some((t) => t.topic_id === currentTopic.topic_id)) {
      nextTopics.unshift(currentTopic)
    }
    topics.value = nextTopics
    sortTopics()
    if (currentTopic?.topic_id && currentTopic.topic_id === topicId.value) {
      moveTopicToTop(currentTopic.topic_id)
    }
    return topics.value
  }


  return {
    topics, searchKeyword, filteredTopics, activeTopic,
    isTopicWorking, topicBadgeKind, isTopicTaskActive, setTopicTaskStatus,
    sortTopics, moveTopicToTop, upsertTopicAtTop,
    refreshTopics,
  }
}
