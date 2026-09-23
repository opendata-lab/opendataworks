// Slash-command support shared by the NL2SQL chat input surfaces (the portal
// NL2SqlChatV2.vue and the embeddable WidgetChat.vue).
//
// The command list is authoritative: the backend reports the SDK's real
// slash-command names (built-ins like /compact + skills like
// /opendataworks-platform-tools + custom commands) via
// GET /agents/{id}/slash-commands. The Agent SDK invokes a slash command simply
// by receiving a prompt that starts with "/<name>", so selecting a command
// autocompletes the "/<name> " token into the input and the user sends it
// (optionally with arguments) like any other message.

import { computed, ref } from 'vue'

// Friendly hints for the SDK's well-known built-in commands. Any other name is a
// skill or custom command and falls back to a generic label.
const BUILTIN_COMMAND_HINTS = {
  clear: '清空对话上下文',
  compact: '压缩对话历史',
  context: '查看上下文用量',
  usage: '查看用量',
}

// Command mode is entered only when the entire input is a single leading-slash
// token with no whitespace, e.g. "/", "/clear", "/opendataworks-platform-tools".
// This avoids clashing with slashes inside normal prose (paths, dates, fractions).
const SLASH_QUERY_RE = /^\/(\S*)$/

// Return the query after the leading "/" when the text is in command mode,
// otherwise null. "/" yields "" (show every command).
export function parseSlashQuery(text) {
  const match = SLASH_QUERY_RE.exec(String(text ?? ''))
  return match ? match[1] : null
}

// Case-insensitive substring match against the command id and label.
export function filterCommands(commands, query) {
  const list = Array.isArray(commands) ? commands : []
  const kw = String(query ?? '').trim().toLowerCase()
  if (!kw) return list.slice()
  return list.filter((cmd) => {
    const id = String(cmd?.id || '').toLowerCase()
    const label = String(cmd?.label || '').toLowerCase()
    return id.includes(kw) || label.includes(kw)
  })
}

// Build a command descriptor from an SDK slash-command name. The name is shown
// as-is (e.g. "/compact", "/opendataworks-platform-tools"); selecting it
// autocompletes the "/<name> " token so the user can append arguments and send.
export function buildCommand(name) {
  const cmd = String(name || '').trim().replace(/^\/+/, '')
  if (!cmd) return null
  return {
    id: '/' + cmd,
    label: '',
    hint: BUILTIN_COMMAND_HINTS[cmd] || '技能',
    insertText: '/' + cmd + ' ',
  }
}

export function buildCommands(names) {
  const seen = new Set()
  const result = []
  for (const name of Array.isArray(names) ? names : []) {
    const cmd = buildCommand(name)
    if (cmd && !seen.has(cmd.id)) {
      seen.add(cmd.id)
      result.push(cmd)
    }
  }
  return result
}

// Composable wiring the menu state to a textarea. `getCommands` returns the live
// command list, `inputText` is the shared engine ref, `focusInput` re-focuses the
// textarea after a skill directive is inserted.
export function useSlashCommands({ getCommands, inputText, focusInput }) {
  const resolveCommands = () => (typeof getCommands === 'function' ? getCommands() || [] : [])

  const visible = ref(false)
  const query = ref('')
  const activeIndex = ref(0)

  const filtered = computed(() => filterCommands(resolveCommands(), query.value))

  function close() {
    visible.value = false
    query.value = ''
    activeIndex.value = 0
  }

  // Recompute menu state from the current input. Call on every input event.
  function syncFromInput() {
    const q = parseSlashQuery(inputText.value)
    if (q === null) {
      close()
      return
    }
    query.value = q
    const list = filterCommands(resolveCommands(), q)
    visible.value = list.length > 0
    if (activeIndex.value >= list.length) activeIndex.value = 0
  }

  function setActive(index) {
    const n = filtered.value.length
    if (!n) return
    activeIndex.value = ((index % n) + n) % n
  }

  function move(delta) {
    setActive(activeIndex.value + delta)
  }

  function select(cmd) {
    if (!cmd) return
    close()
    // Autocomplete the command token; the user adds any arguments and sends it.
    inputText.value = cmd.insertText != null ? cmd.insertText : cmd.id + ' '
    if (typeof focusInput === 'function') focusInput()
  }

  function selectActive() {
    select(filtered.value[activeIndex.value])
  }

  // Returns true when the keystroke was consumed by the menu, so the caller can
  // skip its own Enter-to-send handling.
  function handleKeydown(event) {
    if (!visible.value || !filtered.value.length) return false
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        move(1)
        return true
      case 'ArrowUp':
        event.preventDefault()
        move(-1)
        return true
      case 'Enter':
      case 'Tab':
        if (event.isComposing || event.keyCode === 229) return false
        event.preventDefault()
        selectActive()
        return true
      case 'Escape':
        event.preventDefault()
        close()
        return true
      default:
        return false
    }
  }

  return { visible, query, activeIndex, filtered, syncFromInput, handleKeydown, select, selectActive, setActive, close }
}
