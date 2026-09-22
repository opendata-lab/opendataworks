// The SDK's run vocabulary, and how a DataAgent task_status maps onto it.
//
// These are deliberately different sets. DataAgent says waiting/error/suspended;
// the SDK says queued/failed/cancelled. Translating in one place stops each
// consumer from inventing its own mapping — and from mistaking a parked run for
// a finished one.

/** A run in one of these may still produce more events. */
export const ACTIVE_RUN_STATUSES = new Set([
  'queued',
  'running',
  'waiting_input',
  'waiting_permission'
])

/** A run in one of these will produce nothing further. */
export const TERMINAL_RUN_STATUSES = new Set(['finished', 'cancelled', 'failed'])

const FROM_TASK_STATUS = {
  waiting: 'queued',
  queued: 'queued',
  running: 'running',
  waiting_input: 'waiting_input',
  waiting_permission: 'waiting_permission',
  finished: 'finished',
  success: 'finished',
  completed: 'finished',
  error: 'failed',
  failed: 'failed',
  suspended: 'cancelled',
  cancelled: 'cancelled',
  canceled: 'cancelled'
}

/**
 * Translate a DataAgent task_status. An unrecognised value maps to `failed`
 * rather than to an active state: leaving a client subscribed forever to a
 * status nobody handles is the worse failure.
 */
export function toRunStatus(taskStatus) {
  return FROM_TASK_STATUS[String(taskStatus || '').trim().toLowerCase()] || 'failed'
}

export function isActive(status) {
  return ACTIVE_RUN_STATUSES.has(status)
}

export function isTerminal(status) {
  return TERMINAL_RUN_STATUSES.has(status)
}
