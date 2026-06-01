<template>
  <div ref="rootRef" class="sql-editor-root"></div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, placeholder, Decoration } from '@codemirror/view'
import { EditorState, Compartment } from '@codemirror/state'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { autocompletion, acceptCompletion } from '@codemirror/autocomplete'
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { sql, MySQL } from '@codemirror/lang-sql'
import { createSqlCompletionSource } from './sqlCompletion'

const props = defineProps({
  modelValue: {
    type: String,
    default: ''
  },
  placeholder: {
    type: String,
    default: ''
  },
  readOnly: {
    type: Boolean,
    default: false
  },
  tableNames: {
    type: Array,
    default: () => []
  },
  completionContext: {
    type: Object,
    default: null
  },
  highlights: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['update:modelValue', 'selection-change'])

const rootRef = ref(null)
let view = null
let suppressEmit = false

const languageCompartment = new Compartment()
const editableCompartment = new Compartment()
const placeholderCompartment = new Compartment()
const completionCompartment = new Compartment()
const highlightCompartment = new Compartment()

const normalizeHighlights = (highlights = [], docLength = null) => {
  const safeDocLength = Number.isFinite(docLength) && docLength >= 0 ? Number(docLength) : null
  return (highlights || [])
    .map((item) => {
      let from = Number(item?.from)
      let to = Number(item?.to)
      const status = String(item?.status || 'matched').toLowerCase()
      if (safeDocLength !== null) {
        from = Math.max(0, Math.min(from, safeDocLength))
        to = Math.max(0, Math.min(to, safeDocLength))
      }
      if (!Number.isFinite(from) || !Number.isFinite(to) || from < 0 || to <= from) {
        return null
      }
      return { from, to, status }
    })
    .filter(Boolean)
    .sort((a, b) => {
      if (a.from !== b.from) return a.from - b.from
      if (a.to !== b.to) return a.to - b.to
      return String(a.status).localeCompare(String(b.status))
    })
}

const decorationClassByStatus = (status) => {
  if (status === 'ambiguous') return 'cm-sql-table-hit-ambiguous'
  if (status === 'unmatched') return 'cm-sql-table-hit-unmatched'
  return 'cm-sql-table-hit-matched'
}

const buildHighlightExtension = (highlights = [], docLength = null) => {
  const ranges = normalizeHighlights(highlights, docLength).map((item) =>
    Decoration.mark({ class: decorationClassByStatus(item.status) }).range(item.from, item.to)
  )
  return EditorView.decorations.of(Decoration.set(ranges, true))
}

const completionSource = createSqlCompletionSource({
  getCompletionContext: () => props.completionContext,
  getTableNames: () => props.tableNames
})

const buildExtensions = () => {
  const extensions = [
    lineNumbers(),
    history(),
    highlightActiveLineGutter(),
    highlightActiveLine(),
    syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
    EditorView.lineWrapping,
    EditorView.updateListener.of((update) => {
      if (update.docChanged && view && !suppressEmit) {
        emit('update:modelValue', view.state.doc.toString())
      }
      if (update.selectionSet && view) {
        const text = getSelectionText()
        emit('selection-change', {
          hasSelection: !!text.trim(),
          text
        })
      }
    }),
    keymap.of([
      {
        key: 'Tab',
        run: acceptCompletion
      },
      indentWithTab,
      ...defaultKeymap,
      ...historyKeymap
    ])
  ]

  extensions.push(
    languageCompartment.of(sql({ dialect: MySQL })),
    editableCompartment.of(EditorView.editable.of(!props.readOnly)),
    placeholderCompartment.of(props.placeholder ? placeholder(props.placeholder) : []),
    completionCompartment.of(
      autocompletion({
        override: [completionSource],
        activateOnTyping: true
      })
    ),
    highlightCompartment.of(buildHighlightExtension(props.highlights, String(props.modelValue || '').length))
  )

  extensions.push(
    EditorView.theme(
      {
        '&': {
          height: '100%',
          fontSize: '13px'
        },
        '.cm-scroller': {
          fontFamily: "'JetBrains Mono', Menlo, Consolas, monospace",
          lineHeight: '1.55',
          overflowX: 'auto',
          overflowY: 'auto',
          scrollbarWidth: 'thin',
          scrollbarColor: 'var(--el-scrollbar-bg-color, #a8abb2) transparent'
        },
        '.cm-scroller::-webkit-scrollbar': {
          width: '10px',
          height: '10px'
        },
        '.cm-scroller::-webkit-scrollbar-track': {
          backgroundColor: 'transparent'
        },
        '.cm-scroller::-webkit-scrollbar-thumb': {
          backgroundColor: 'var(--el-scrollbar-bg-color, #a8abb2)',
          borderRadius: '10px'
        },
        '.cm-scroller::-webkit-scrollbar-thumb:hover': {
          backgroundColor: 'var(--el-scrollbar-hover-bg-color, #909399)'
        },
        '.cm-scroller::-webkit-scrollbar-corner': {
          backgroundColor: 'transparent'
        },
        '.cm-gutters': {
          backgroundColor: '#f8fafc',
          color: '#64748b',
          borderRight: '1px solid #e2e8f0'
        },
        '.cm-activeLineGutter': {
          backgroundColor: '#eef2ff',
          color: '#1f2f3d'
        },
        '.cm-activeLine': {
          backgroundColor: '#f8fafc'
        },
        '.cm-sql-table-hit-matched': {
          backgroundColor: 'rgba(34, 197, 94, 0.2)',
          textDecoration: 'underline 1px #16a34a',
          textUnderlineOffset: '2px'
        },
        '.cm-sql-table-hit-ambiguous': {
          backgroundColor: 'rgba(245, 158, 11, 0.2)',
          textDecoration: 'underline 1px #d97706',
          textUnderlineOffset: '2px'
        },
        '.cm-sql-table-hit-unmatched': {
          backgroundColor: 'rgba(239, 68, 68, 0.2)',
          textDecoration: 'underline 1px #dc2626',
          textUnderlineOffset: '2px'
        }
      },
      { dark: false }
    )
  )

  return extensions
}

const getSelectionText = () => {
  if (!view) return ''
  const range = view.state.selection.main
  if (!range || range.empty) return ''
  return view.state.doc.sliceString(range.from, range.to)
}

const getDocText = () => {
  if (!view) return props.modelValue || ''
  return view.state.doc.toString()
}

const scrollToRange = (from, to) => {
  if (!view) return
  const docLength = view.state.doc.length
  const start = Math.max(0, Math.min(Number(from) || 0, docLength))
  const end = Math.max(start, Math.min(Number(to) || start, docLength))
  view.dispatch({
    selection: { anchor: start, head: end },
    scrollIntoView: true
  })
  view.focus()
}

defineExpose({
  getSelectionText,
  getDocText,
  scrollToRange,
  focus: () => view?.focus()
})

const reconfigure = (compartment, extension) => {
  if (!view) return
  view.dispatch({
    effects: compartment.reconfigure(extension)
  })
}

onMounted(() => {
  const root = rootRef.value
  if (!root) return

  view = new EditorView({
    state: EditorState.create({
      doc: props.modelValue || '',
      extensions: buildExtensions()
    }),
    parent: root
  })
})

onBeforeUnmount(() => {
  view?.destroy()
  view = null
})

watch(
  () => props.modelValue,
  (next) => {
    if (!view) return
    const current = view.state.doc.toString()
    const value = next == null ? '' : String(next)
    if (current === value) return
    suppressEmit = true
    view.dispatch({
      changes: { from: 0, to: current.length, insert: value }
    })
    suppressEmit = false
  }
)

watch(
  () => props.readOnly,
  (next) => {
    reconfigure(editableCompartment, EditorView.editable.of(!next))
  }
)

watch(
  () => props.placeholder,
  (next) => {
    reconfigure(placeholderCompartment, next ? placeholder(String(next)) : [])
  }
)

// NOTE: props.tableNames / props.completionContext are read lazily by the
// completion source (see getTableNames / getCompletionContext closures above),
// so the autocompletion extension never needs reconfiguring when they change.
// A previous deep watcher reconfigured the extension on every keystroke (the
// parent passes a fresh array each render), which caused severe input lag.

watch(
  () => props.highlights,
  (next) => {
    const docLength = view ? view.state.doc.length : String(props.modelValue || '').length
    try {
      reconfigure(highlightCompartment, buildHighlightExtension(next, docLength))
    } catch (error) {
      console.error('更新 SQL 高亮失败，已降级为空高亮:', error)
      reconfigure(highlightCompartment, buildHighlightExtension([], docLength))
    }
  },
  { deep: true }
)
</script>

<style scoped>
.sql-editor-root {
  height: 100%;
  min-height: 0;
}
</style>
