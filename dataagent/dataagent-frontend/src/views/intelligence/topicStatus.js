/**
 * Map a topic's persisted current_task_status (da_agent_topic.current_task_status,
 * mirrored from da_agent_task.task_status) to a UI badge kind for the session /
 * conversation record list.
 *
 *   waiting | running  -> 'running'    (in-progress; shown as the loading spinner)
 *   error              -> 'error'      (failed; red dot)
 *   suspended          -> 'suspended'  (cancelled; grey dot)
 *   finished | (none)  -> ''           (no badge; timestamp only)
 *
 * Returns '' for any unknown / terminal-success value so callers can treat the
 * empty string as "render nothing extra".
 */
export function topicStatusKind(currentTaskStatus) {
  const status = String(currentTaskStatus || '').trim()
  if (status === 'waiting' || status === 'running') return 'running'
  if (status === 'error') return 'error'
  if (status === 'suspended') return 'suspended'
  return ''
}
