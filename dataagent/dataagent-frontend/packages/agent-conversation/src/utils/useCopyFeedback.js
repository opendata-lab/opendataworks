import { getCurrentScope, onScopeDispose, ref } from 'vue'

import { copyText } from './clipboard.js'

/**
 * Transient "copied" indicator shared by copy buttons: `markCopied(key)` sets
 * `copiedKey` to `key` and clears it after `duration` ms (re-clicking resets the
 * timer). SSR/test-safe — guards `window`. For boolean buttons use any stable
 * key (e.g. 'copied') and compare against it in the template.
 *
 * @param {number} duration reset delay in milliseconds
 */
export const useCopyFeedback = (duration = 1500) => {
  const copiedKey = ref('')
  let timer = 0

  const markCopied = (key = 'copied') => {
    copiedKey.value = key
    if (timer && typeof window !== 'undefined') window.clearTimeout(timer)
    if (typeof window !== 'undefined') {
      timer = window.setTimeout(() => {
        copiedKey.value = ''
      }, duration)
    }
  }

  const copyWithFeedback = async (text, key = 'copied') => {
    await copyText(text)
    markCopied(key)
  }

  if (getCurrentScope()) {
    onScopeDispose(() => {
      if (timer && typeof window !== 'undefined') window.clearTimeout(timer)
    })
  }

  return { copiedKey, markCopied, copyWithFeedback }
}
