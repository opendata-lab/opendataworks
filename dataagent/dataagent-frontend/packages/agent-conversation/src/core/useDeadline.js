import { computed, onBeforeUnmount, ref, watch } from 'vue'

/**
 * Seconds left until a card's `deadline_at`, ticking once a second while the
 * card is still open. Null when there is no deadline or the card is settled.
 *
 * The control plane owns the deadline and the timeout policy; this only shows
 * the countdown, it never answers on the user's behalf.
 */
export function useDeadline(deadlineAt, active) {
  const now = ref(Date.now())
  let timer = null

  const stop = () => {
    if (timer) clearInterval(timer)
    timer = null
  }
  const at = computed(() => {
    const value = Date.parse(String(deadlineAt() || ''))
    return Number.isFinite(value) ? value : null
  })
  watch(
    () => active() && at.value !== null,
    (ticking) => {
      stop()
      if (ticking) {
        now.value = Date.now()
        timer = setInterval(() => { now.value = Date.now() }, 1000)
      }
    },
    { immediate: true },
  )
  onBeforeUnmount(stop)

  const secondsLeft = computed(() => {
    if (!active() || at.value === null) return null
    return Math.max(0, Math.ceil((at.value - now.value) / 1000))
  })
  const label = computed(() => {
    const left = secondsLeft.value
    if (left === null) return ''
    const minutes = Math.floor(left / 60)
    const seconds = String(left % 60).padStart(2, '0')
    return `${minutes}:${seconds}`
  })
  return { secondsLeft, label }
}
