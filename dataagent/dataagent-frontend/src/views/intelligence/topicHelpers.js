// Topic-list concerns. These stay in the application shell on purpose: the SDK
// package owns a single conversation, not the list of them (see the SDK design,
// "拆分边界"). Only NL2SqlChatV2 and useNl2SqlChat — both topic-list code — use
// them.
export function normalizeTopic(topic) {
  const rawAgent = topic?.agent && typeof topic.agent === 'object' ? topic.agent : null
  return {
    topic_id: String(topic?.topic_id || ''),
    title: String(topic?.title || '新话题'),
    agent_id: String(topic?.agent_id || rawAgent?.agent_id || ''),
    agent: rawAgent
      ? {
          agent_id: String(rawAgent.agent_id || topic?.agent_id || ''),
          name: String(rawAgent.name || ''),
          description: String(rawAgent.description || ''),
          is_default: Boolean(rawAgent.is_default),
          is_builtin: Boolean(rawAgent.is_builtin),
        }
      : null,
    message_count: Number(topic?.message_count || 0),
    last_message_preview: String(topic?.last_message_preview || ''),
    current_task_id: String(topic?.current_task_id || ''),
    current_task_status: String(topic?.current_task_status || ''),
    created_at: String(topic?.created_at || new Date().toISOString()),
    updated_at: String(topic?.updated_at || new Date().toISOString()),
  }
}

// Descending by recency: updated_at, falling back to created_at.
export function compareTopicsByRecency(a, b) {
  return String(b?.updated_at || b?.created_at || '').localeCompare(String(a?.updated_at || a?.created_at || ''))
}
