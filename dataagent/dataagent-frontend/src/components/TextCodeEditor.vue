<template>
  <div ref="rootRef" class="text-editor-root"></div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, placeholder } from '@codemirror/view'
import { Compartment, EditorState } from '@codemirror/state'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'

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
  }
})

const emit = defineEmits(['update:modelValue'])

const rootRef = ref(null)
let view = null
let suppressEmit = false
const editableCompartment = new Compartment()

const createEditor = () => {
  if (!rootRef.value) return
  view = new EditorView({
    state: EditorState.create({
      doc: props.modelValue || '',
      extensions: [
        lineNumbers(),
        history(),
        highlightActiveLineGutter(),
        highlightActiveLine(),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        EditorView.lineWrapping,
        props.placeholder ? placeholder(props.placeholder) : [],
        editableCompartment.of(EditorView.editable.of(!props.readOnly)),
        EditorView.updateListener.of((update) => {
          if (update.docChanged && !suppressEmit) {
            emit('update:modelValue', update.state.doc.toString())
          }
        }),
        keymap.of([indentWithTab, ...defaultKeymap, ...historyKeymap]),
        EditorView.theme({
          '&': {
            height: '100%',
            fontSize: '13px',
            backgroundColor: '#fff'
          },
          '.cm-scroller': {
            fontFamily: "'JetBrains Mono', Menlo, Consolas, monospace",
            lineHeight: '1.55',
            overflow: 'auto'
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
          '.cm-content': {
            padding: '12px 0'
          }
        })
      ]
    }),
    parent: rootRef.value
  })
}

onMounted(() => {
  createEditor()
})

onBeforeUnmount(() => {
  if (view) {
    view.destroy()
    view = null
  }
})

watch(
  () => props.modelValue,
  (value) => {
    if (!view) return
    const current = view.state.doc.toString()
    if (current === (value || '')) return
    suppressEmit = true
    view.dispatch({
      changes: {
        from: 0,
        to: current.length,
        insert: value || ''
      }
    })
    suppressEmit = false
  }
)

watch(
  () => props.readOnly,
  (value) => {
    if (!view) return
    view.dispatch({
      effects: editableCompartment.reconfigure(EditorView.editable.of(!value))
    })
  }
)
</script>

<style scoped>
.text-editor-root {
  height: 100%;
  min-height: 220px;
  border: 1px solid #dbe2ea;
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
}
</style>
