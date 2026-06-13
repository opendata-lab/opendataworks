<template>
  <div class="sql-panel">
    <div class="sql-panel-toolbar">
      <span class="sql-panel-label">SQL</span>
      <span v-if="database" class="sql-panel-db">{{ engine ? `${engine} · ` : '' }}{{ database }}</span>
      <div class="sql-panel-actions">
        <button type="button" class="sql-panel-btn" data-action="copy" @click="copySql">
          {{ copied ? '已复制' : '复制' }}
        </button>
        <button
          v-if="!editing"
          type="button"
          class="sql-panel-btn"
          data-action="edit"
          @click="startEditing"
        >编辑</button>
        <button
          v-else
          type="button"
          class="sql-panel-btn"
          data-action="revert"
          @click="revertSql"
        >还原</button>
        <template v-if="executable">
          <select v-model.number="limit" class="sql-panel-limit" aria-label="返回行数上限">
            <option :value="100">100 行</option>
            <option :value="500">500 行</option>
            <option :value="1000">1000 行</option>
          </select>
          <button
            type="button"
            class="sql-panel-btn sql-panel-btn-primary"
            data-action="execute"
            :disabled="running || !currentSql.trim()"
            @click="executeSql"
          >{{ running ? '执行中…' : '执行' }}</button>
        </template>
      </div>
    </div>

    <div ref="editorRef" class="sql-panel-editor" :class="{ 'is-editing': editing }"></div>

    <div v-if="executeError" class="sql-panel-error">{{ executeError }}</div>

    <ResultDataTable
      v-if="executeResult"
      :columns="executeResult.columns"
      :rows="executeResult.rows"
      :title="exportTitle"
      :meta="executeResultMeta"
    />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorView, keymap, placeholder } from '@codemirror/view'
import { Compartment, EditorState } from '@codemirror/state'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { MySQL, sql } from '@codemirror/lang-sql'
import { copyText } from '@/utils/clipboard'
import { createNl2SqlApiClient } from '@/api/nl2sql'
import ResultDataTable from './ResultDataTable.vue'

const props = defineProps({
  sql: { type: String, default: '' },
  database: { type: String, default: '' },
  engine: { type: String, default: '' },
  title: { type: String, default: '' }
})

const editorRef = ref(null)
const editing = ref(false)
const copied = ref(false)
const running = ref(false)
const limit = ref(100)
const currentSql = ref(String(props.sql || ''))
const executeResult = ref(null)
const executeError = ref('')

let view = null
let copiedTimer = 0
let apiClient = null
const editableCompartment = new Compartment()

const executable = computed(() => Boolean(String(props.database || '').trim()))
const exportTitle = computed(() => props.title || props.database || 'query_result')
const executeResultMeta = computed(() => ({
  rowCount: executeResult.value?.row_count,
  durationMs: executeResult.value?.duration_ms,
  hasMore: executeResult.value?.has_more,
  truncatedBySize: executeResult.value?.truncated_by_size,
  notice: executeResult.value?.notice
}))

const getApi = () => {
  if (!apiClient) apiClient = createNl2SqlApiClient({ timeout: 150000 })
  return apiClient
}

const setEditorDoc = (value) => {
  if (!view) return
  const current = view.state.doc.toString()
  if (current === value) return
  view.dispatch({ changes: { from: 0, to: current.length, insert: value } })
}

const createEditor = () => {
  if (!editorRef.value || typeof window === 'undefined') return
  try {
    view = new EditorView({
      state: EditorState.create({
        doc: currentSql.value,
        extensions: [
          history(),
          syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
          sql({ dialect: MySQL }),
          EditorView.lineWrapping,
          placeholder('SQL'),
          editableCompartment.of(EditorView.editable.of(false)),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) {
              currentSql.value = update.state.doc.toString()
            }
          }),
          keymap.of([...defaultKeymap, ...historyKeymap]),
          EditorView.theme({
            '&': { fontSize: '12px', backgroundColor: 'transparent' },
            '.cm-scroller': {
              fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace",
              lineHeight: '1.7'
            },
            '.cm-content': { padding: '12px 14px' },
            '&.cm-focused': { outline: 'none' }
          })
        ]
      }),
      parent: editorRef.value
    })
  } catch (_error) {
    // CodeMirror 初始化失败时保底用纯文本展示
    if (editorRef.value) editorRef.value.textContent = currentSql.value
  }
}

const setEditable = (value) => {
  if (!view) return
  view.dispatch({ effects: editableCompartment.reconfigure(EditorView.editable.of(value)) })
}

const startEditing = () => {
  editing.value = true
  setEditable(true)
}

const revertSql = () => {
  currentSql.value = String(props.sql || '')
  setEditorDoc(currentSql.value)
  editing.value = false
  setEditable(false)
}

const copySql = async () => {
  try {
    await copyText(currentSql.value)
    copied.value = true
    if (copiedTimer && typeof window !== 'undefined') window.clearTimeout(copiedTimer)
    if (typeof window !== 'undefined') {
      copiedTimer = window.setTimeout(() => {
        copied.value = false
      }, 1500)
    }
  } catch (_error) {
    // 剪贴板不可用时静默失败
  }
}

const executeSql = async () => {
  if (running.value || !currentSql.value.trim() || !executable.value) return
  running.value = true
  executeError.value = ''
  try {
    const result = await getApi().queryApi.executeSql({
      sql: currentSql.value,
      database: props.database,
      engine: props.engine || undefined,
      limit: limit.value
    })
    executeResult.value = result
    if (result?.error) {
      executeError.value = String(result.error)
    }
  } catch (error) {
    executeResult.value = null
    executeError.value = error?.message || '执行失败'
  } finally {
    running.value = false
  }
}

watch(
  () => props.sql,
  (value) => {
    if (editing.value) return
    currentSql.value = String(value || '')
    setEditorDoc(currentSql.value)
    executeResult.value = null
    executeError.value = ''
  }
)

onMounted(() => {
  createEditor()
})

onBeforeUnmount(() => {
  if (copiedTimer && typeof window !== 'undefined') window.clearTimeout(copiedTimer)
  if (view) {
    view.destroy()
    view = null
  }
})
</script>

<style scoped>
.sql-panel {
  margin-top: 14px;
}

.sql-panel-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sql-panel-label {
  font-size: 12px;
  font-weight: 700;
  color: #607185;
}

.sql-panel-db {
  font-size: 12px;
  color: #8da0b3;
}

.sql-panel-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
}

.sql-panel-btn {
  padding: 4px 10px;
  border: 1px solid #dbe3ec;
  border-radius: 8px;
  background: #fff;
  color: #31567a;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}

.sql-panel-btn:hover:not(:disabled) {
  border-color: #4f81ff;
  color: #1d3f5e;
}

.sql-panel-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.sql-panel-btn-primary {
  background: #eef6ff;
  border-color: #cfe2ff;
}

.sql-panel-limit {
  padding: 3px 6px;
  border: 1px solid #dbe3ec;
  border-radius: 8px;
  font-size: 12px;
  color: #31567a;
  background: #fff;
}

.sql-panel-editor {
  margin-top: 8px;
  border-radius: 14px;
  border: 1px solid #e1e8f0;
  background: #f8fbff;
  overflow: hidden;
}

.sql-panel-editor.is-editing {
  border-color: #4f81ff;
  background: #fff;
}

.sql-panel-error {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(190, 24, 93, 0.08);
  color: #9f1239;
  font-size: 13px;
  line-height: 1.6;
}
</style>
