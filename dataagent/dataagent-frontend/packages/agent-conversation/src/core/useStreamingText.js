import { onBeforeUnmount, shallowRef, watch } from 'vue'

const PARSE_INTERVAL_MS = 250

/** Sample before parsing/splitting, with one trailing update and terminal flush. */
export function useStreamingText(source, streaming, force = () => false) {
  const sampled = shallowRef('')
  let timer = null
  let lastSample = -Infinity
  const cancel = () => {
    if (timer !== null) clearTimeout(timer)
    timer = null
  }
  const publish = () => {
    cancel()
    sampled.value = source()
    lastSample = Date.now()
  }
  watch([source, streaming, force], ([text, active, forced], previous) => {
    if (text === sampled.value) {
      cancel()
      return
    }
    const remaining = PARSE_INTERVAL_MS - (Date.now() - lastSample)
    if (!active || forced !== previous?.[2] || remaining <= 0) publish()
    else if (timer === null) timer = setTimeout(publish, remaining)
  }, { immediate: true, flush: 'sync' })
  onBeforeUnmount(cancel)
  return sampled
}
