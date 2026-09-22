import { computed, ref, watch } from 'vue'

/**
 * The `/command` menu behind the composer input.
 *
 * Kept out of the component because the interesting part is not the markup, it
 * is when the menu is allowed to swallow a keystroke. Enter has three possible
 * meanings in this input — commit an IME candidate, accept a command, send the
 * message — and getting the order wrong makes the composer feel broken to
 * anyone typing Chinese.
 *
 * @param {import('vue').Ref<string>} draft the composer's current text
 * @param {import('vue').Ref<{name: string, description?: string}[]>} commands
 */
export function useSlashMenu(draft, commands) {
  const dismissed = ref(false)
  const activeIndex = ref(0)

  // Only a draft that *is* a command: text before the slash means the user is
  // writing prose that happens to contain one, and a menu over that is noise.
  const query = computed(() => {
    const text = String(draft.value ?? '')
    if (!text.startsWith('/')) return null
    const [first] = text.split(/\s/, 1)
    return first.length === text.length ? first.slice(1) : null
  })

  const matches = computed(() => {
    const needle = query.value
    if (needle === null) return []
    const list = Array.isArray(commands.value) ? commands.value : []
    const lowered = needle.toLowerCase()
    return list.filter((item) => String(item?.name || '').toLowerCase().startsWith(lowered))
  })

  const open = computed(() => !dismissed.value && matches.value.length > 0)

  // Typing again after Esc re-opens the menu; otherwise dismissing it once
  // would hide it for the rest of the command the user is still writing.
  watch(query, () => {
    dismissed.value = false
    activeIndex.value = 0
  })

  watch(matches, (list) => {
    if (activeIndex.value >= list.length) activeIndex.value = 0
  })

  const accept = (index = activeIndex.value) => {
    const chosen = matches.value[index]
    if (!chosen) return null
    dismissed.value = true
    return `/${chosen.name} `
  }

  /**
   * Returns the text the composer should adopt, `''` when the key was consumed
   * without changing the text, or null when the composer should handle it.
   */
  const handleKeydown = (event) => {
    if (!open.value) return null
    // An IME candidate list is on screen and owns these keys.
    if (event.isComposing || event.keyCode === 229) return null

    if (event.key === 'ArrowDown') {
      event.preventDefault()
      activeIndex.value = (activeIndex.value + 1) % matches.value.length
      return ''
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      activeIndex.value = (activeIndex.value - 1 + matches.value.length) % matches.value.length
      return ''
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      dismissed.value = true
      return ''
    }
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      return accept()
    }
    return null
  }

  return { open, matches, activeIndex, accept, handleKeydown }
}
