<template>
  <el-drawer
    v-model="visible"
    :title="isEdit ? '编辑任务' : '创建任务'"
    size="50%"
    :close-on-click-modal="false"
    :before-close="handleBeforeClose"
    @closed="handleDrawerClosed"
    destroy-on-close
  >
    <el-form
      v-if="visible"
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="120px"
      style="padding-right: 20px"
    >
      <el-form-item label="任务名称" prop="task.taskName">
        <el-input
          v-model="form.task.taskName"
          placeholder="请输入任务名称"
          :disabled="isWriteTask"
          @input="handleTaskNameInput"
          @blur="handleTaskNameBlur"
        >
          <template #suffix>
            <el-icon v-if="taskNameChecking" class="is-loading">
              <Loading />
            </el-icon>
            <el-icon v-else-if="taskNameError" class="error-icon">
              <CircleClose />
            </el-icon>
            <el-icon v-else-if="form.task.taskName && !taskNameError" class="success-icon">
              <CircleCheck />
            </el-icon>
          </template>
        </el-input>
        <div v-if="isWriteTask" class="hint-text">
          写入任务名称自动使用目标表名
        </div>
        <div v-if="taskNameError" class="error-text">
          {{ taskNameError }}
        </div>
      </el-form-item>

      <el-form-item label="任务描述" prop="task.taskDesc">
        <el-input v-model="form.task.taskDesc" type="textarea" :rows="3" placeholder="请输入任务描述" />
      </el-form-item>

      <el-form-item label="所属工作流">
        <el-select
          v-model="form.task.workflowId"
          placeholder="可选择工作流（可选）"
          filterable
          :disabled="!!lockedWorkflowId"
          style="width: 100%"
          clearable
        >
          <el-option
            v-for="item in workflowOptions"
            :key="item.id"
            :label="item.workflowName"
            :value="item.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="任务类型" prop="task.dolphinNodeType">
        <el-select
          v-model="form.task.dolphinNodeType"
          placeholder="请选择任务类型"
          @change="handleNodeTypeChange"
          style="width: 100%"
        >
          <el-option label="SQL" value="SQL" />
          <el-option label="DataX" value="DATAX" />
          <el-option label="Shell" value="SHELL" />
          <el-option label="Python" value="PYTHON" />
        </el-select>
      </el-form-item>

      <el-form-item label="执行状态" prop="task.dolphinFlag">
        <el-radio-group v-model="form.task.dolphinFlag">
          <el-radio label="YES">正常执行</el-radio>
          <el-radio label="NO">禁止执行</el-radio>
        </el-radio-group>
      </el-form-item>

      <template v-if="form.task.dolphinNodeType === 'SQL'">
        <el-form-item label="数据源" prop="task.datasourceName">
          <el-select
            v-model="form.task.datasourceName"
            placeholder="请选择数据源"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="item in datasourceOptions"
              :key="item.name"
              :label="item.name"
              :value="item.name"
            >
              <span>{{ item.name }}</span>
              <span style="float: right; color: #8492a6; font-size: 13px; margin-left: 10px">{{ item.dbName }}</span>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item label="SQL 脚本" prop="task.taskSql" class="sql-editor-item">
          <div class="sql-workbench">
            <SqlEditor
              ref="sqlEditorRef"
              v-model="form.task.taskSql"
              class="sql-codemirror"
              placeholder="请输入 SQL 脚本"
              :table-names="[]"
              :highlights="sqlHighlights"
            />

            <aside class="sql-analysis-panel">
              <div class="sql-analysis-toolbar">
                <div class="analysis-heading">表解析</div>
                <div class="sql-analysis-actions">
                  <el-button
                    type="primary"
                    link
                    :disabled="!canReAnalyze"
                    :loading="analysisLoading"
                    @click="handleReAnalyzeClick"
                  >
                    重新解析
                  </el-button>
                  <el-button
                    type="primary"
                    link
                    :disabled="!hasMatchedSuggestions"
                    @click="applyMatchedSuggestions()"
                  >
                    应用建议
                  </el-button>
                  <el-button
                    v-if="analysisSummary.unmatched > 0"
                    type="warning"
                    link
                    @click="goToMetadataSync"
                  >
                    去元数据同步
                  </el-button>
                </div>
              </div>

              <div class="sql-analysis-stats">
                <span>命中 <strong>{{ analysisSummary.matched }}</strong></span>
                <span>歧义 <strong>{{ analysisSummary.ambiguous }}</strong></span>
                <span>未识别 <strong>{{ analysisSummary.unmatched }}</strong></span>
                <span v-if="analysisLoading">解析中...</span>
              </div>

              <div v-if="analysisError" class="analysis-error">{{ analysisError }}</div>
              <el-alert
                v-if="analysisSummary.unmatched > 0 || analysisSummary.ambiguous > 0"
                :closable="false"
                show-icon
                type="warning"
                title="请人工核对解析结果；自动解析不会覆盖你已手工选择的输入/输出表。"
                class="analysis-alert"
              />

              <div class="analysis-grid">
                <section class="analysis-group">
                  <div class="analysis-title">输入表建议</div>
                  <el-empty v-if="!sqlAnalysis.inputRefs.length" description="暂无输入表建议" :image-size="54" />
                  <div
                    v-for="(item, idx) in sqlAnalysis.inputRefs"
                    :key="`in-${idx}-${item.rawName}`"
                    class="analysis-item"
                    :class="`is-${item.matchStatus || 'unknown'}`"
                    @click="focusAnalyzeRef(item)"
                  >
                    <span class="analysis-state">{{ analysisStatusText(item.matchStatus) }}</span>
                    <span class="analysis-raw">{{ item.rawName }}</span>
                    <span class="analysis-target">{{ formatAnalyzeTarget(item) }}</span>
                  </div>
                </section>

                <section class="analysis-group">
                  <div class="analysis-title">输出表建议</div>
                  <el-empty v-if="!sqlAnalysis.outputRefs.length" description="暂无输出表建议" :image-size="54" />
                  <div
                    v-for="(item, idx) in sqlAnalysis.outputRefs"
                    :key="`out-${idx}-${item.rawName}`"
                    class="analysis-item"
                    :class="`is-${item.matchStatus || 'unknown'}`"
                    @click="focusAnalyzeRef(item)"
                  >
                    <span class="analysis-state">{{ analysisStatusText(item.matchStatus) }}</span>
                    <span class="analysis-raw">{{ item.rawName }}</span>
                    <span class="analysis-target">{{ formatAnalyzeTarget(item) }}</span>
                  </div>
                </section>
              </div>
            </aside>
          </div>
        </el-form-item>
      </template>

      <template v-if="form.task.dolphinNodeType === 'DATAX'">
        <el-form-item label="源数据源" prop="task.datasourceName">
          <el-select
            v-model="form.task.datasourceName"
            placeholder="请选择源数据源"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="item in datasourceOptions"
              :key="item.name"
              :label="item.name"
              :value="item.name"
            >
              <span>{{ item.name }}</span>
              <span style="float: right; color: #8492a6; font-size: 13px; margin-left: 10px">{{ item.dbName }}</span>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item label="源表名" prop="task.sourceTable">
          <el-input v-model="form.task.sourceTable" placeholder="例如: user_info" />
        </el-form-item>

        <el-form-item label="目标数据源" prop="task.targetDatasourceName">
          <el-select
            v-model="form.task.targetDatasourceName"
            placeholder="请选择目标数据源"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="item in datasourceOptions"
              :key="item.name"
              :label="item.name"
              :value="item.name"
            >
              <span>{{ item.name }}</span>
              <span style="float: right; color: #8492a6; font-size: 13px; margin-left: 10px">{{ item.dbName }}</span>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item label="目标表名" prop="task.targetTable">
          <el-input v-model="form.task.targetTable" placeholder="例如: user_info_copy" />
        </el-form-item>

        <el-form-item label="列映射（可选）" prop="task.columnMapping">
          <el-input
            v-model="form.task.columnMapping"
            type="textarea"
            :rows="3"
            placeholder="留空表示全部列同步，或输入JSON格式的列映射配置"
          />
        </el-form-item>
      </template>

      <template v-if="['SHELL', 'PYTHON'].includes(form.task.dolphinNodeType)">
        <el-form-item label="脚本内容" prop="task.taskSql" class="sql-editor-item">
          <el-input
            v-model="form.task.taskSql"
            type="textarea"
            :rows="15"
            placeholder="请输入脚本内容"
            class="sql-input"
            resize="none"
            spellcheck="false"
          />
        </el-form-item>
      </template>

      <el-form-item label="输入表" prop="inputTableIds">
        <el-select
          v-model="form.inputTableIds"
          multiple
          filterable
          remote
          reserve-keyword
          placeholder="搜索输入表"
          style="width: 100%"
          :remote-method="handleTableSearch"
          :loading="tableLoading"
        >
          <el-option
            v-for="option in availableTableOptions"
            :key="option.id"
            :label="formatTableOptionLabel(option)"
            :value="option.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="输出表" prop="outputTableIds">
        <el-select
          v-model="form.outputTableIds"
          multiple
          filterable
          remote
          reserve-keyword
          placeholder="搜索输出表"
          style="width: 100%"
          :remote-method="handleTableSearch"
          :loading="tableLoading"
        >
          <el-option
            v-for="option in availableTableOptions"
            :key="option.id"
            :label="formatTableOptionLabel(option)"
            :value="option.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="优先级" prop="task.priority">
        <el-slider v-model="form.task.priority" :min="1" :max="10" show-stops />
      </el-form-item>

      <el-form-item label="负责任" prop="task.owner">
        <el-input v-model="form.task.owner" placeholder="请输入负责人" />
      </el-form-item>
    </el-form>

    <template #footer>
      <div style="flex: auto">
        <el-button @click="handleClose">取消</el-button>
        <el-button type="primary" @click="handleSave" :loading="loading">保存</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { ref, reactive, computed, watch, onBeforeUnmount, nextTick, defineAsyncComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '@/api/task'
import { workflowApi } from '@/api/workflow'
import { tableApi } from '@/api/table'
import {
  buildTaskPayload,
  buildTaskSaveSuccessMessage,
  createDefaultTaskModel,
  normalizeDolphinFlag,
  resolveTaskWorkflowId,
  shouldPromptUnboundWorkflowGuidance,
  syncTaskDatasourceType
} from './taskEditForm'

const SqlEditor = defineAsyncComponent({
  loader: () => import('@/components/SqlEditor.vue'),
  suspensible: false
})

const props = defineProps({
  dolphinConfigId: {
    type: Number,
    default: null
  }
})

const emit = defineEmits(['saved', 'success', 'close'])

const route = useRoute()
const router = useRouter()

const visible = ref(false)
const isEdit = ref(false)
const loading = ref(false)
const formRef = ref(null)
const sqlEditorRef = ref(null)
const lockedWorkflowId = ref(null)
const taskNameError = ref('')
const taskNameChecking = ref(false)
const originalTaskName = ref('')
const workflowOptions = ref([])
const isWriteTask = ref(false)
const contextDolphinConfigId = ref(null)
const openedFromRelatedTask = ref(false)

const datasourceOptions = ref([])
const tableOptions = ref([])
const tableLoading = ref(false)
const tableOptionCache = reactive({})

const analysisLoading = ref(false)
const analysisError = ref('')
const sqlAnalysis = reactive({
  inputRefs: [],
  outputRefs: [],
  unmatched: [],
  ambiguous: []
})

const inputSelectionTouched = ref(false)
const outputSelectionTouched = ref(false)
const isSyncingSelection = ref(false)

let tableSearchTimer = null
let taskNameCheckTimer = null
let sqlAnalyzeTimer = null
let analyzeSeq = 0

const form = reactive({
  task: createDefaultTaskModel(),
  inputTableIds: [],
  outputTableIds: []
})

const fetchWorkflowOptions = async () => {
  try {
    const res = await workflowApi.list({ pageNum: 1, pageSize: 200 })
    workflowOptions.value = res.records || []
  } catch (error) {
    console.error('获取工作流列表失败:', error)
  }
}

const fetchDatasourceOptions = async () => {
  try {
    const params = effectiveDolphinConfigId.value ? { dolphinConfigId: effectiveDolphinConfigId.value } : {}
    const res = await taskApi.fetchDatasources(params)
    datasourceOptions.value = res || []
    syncSqlDatasourceType()
  } catch (error) {
    console.error('获取数据源失败:', error)
    ElMessage.warning('数据源目录加载失败，可继续编辑并保存')
  }
}

const syncSqlDatasourceType = () => {
  if (form.task.dolphinNodeType !== 'SQL') {
    if (!form.task.datasourceName) {
      form.task.datasourceType = null
    }
    return null
  }
  return syncTaskDatasourceType(form.task, datasourceOptions.value)
}

const outputTableRule = [{ type: 'array', required: true, min: 1, message: '请选择至少一个输出表', trigger: 'change' }]

const rules = computed(() => {
  const baseRules = {
    'task.taskName': [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
    outputTableIds: outputTableRule
  }

  if (form.task.dolphinNodeType === 'SQL') {
    return {
      ...baseRules,
      'task.taskSql': [{ required: true, message: 'SQL 脚本不能为空', trigger: 'blur' }]
    }
  }

  if (form.task.dolphinNodeType === 'DATAX') {
    return {
      ...baseRules,
      'task.sourceTable': [{ required: true, message: '请输入源表名', trigger: 'blur' }],
      'task.targetTable': [{ required: true, message: '请输入目标表名', trigger: 'blur' }]
    }
  }

  if (['SHELL', 'PYTHON'].includes(form.task.dolphinNodeType)) {
    return {
      ...baseRules,
      'task.taskSql': [{ required: true, message: '脚本内容不能为空', trigger: 'blur' }]
    }
  }

  return baseRules
})

const availableTableOptions = computed(() => {
  const seen = new Set()
  const result = []

  const append = (option) => {
    if (!option || !option.id || seen.has(option.id)) return
    result.push(option)
    seen.add(option.id)
  }

  tableOptions.value.forEach(append)
  ;[...(form.inputTableIds || []), ...(form.outputTableIds || [])].forEach((id) => {
    const cached = tableOptionCache[id]
    if (cached) append(cached)
  })

  return result
})

const analysisSummary = computed(() => {
  const refs = [...(sqlAnalysis.inputRefs || []), ...(sqlAnalysis.outputRefs || [])]
  let matched = 0
  let ambiguous = 0
  let unmatched = 0

  refs.forEach((item) => {
    const status = String(item?.matchStatus || '')
    if (status === 'matched') matched += 1
    else if (status === 'ambiguous') ambiguous += 1
    else if (status === 'unmatched') unmatched += 1
  })

  return { matched, ambiguous, unmatched }
})

const hasMatchedSuggestions = computed(() => {
  const inCount = (sqlAnalysis.inputRefs || []).filter((item) => item?.matchStatus === 'matched' && item?.chosenTable?.tableId).length
  const outCount = (sqlAnalysis.outputRefs || []).filter((item) => item?.matchStatus === 'matched' && item?.chosenTable?.tableId).length
  return inCount > 0 || outCount > 0
})

const canReAnalyze = computed(() => {
  const sqlText = String(form.task.taskSql || '').trim()
  return visible.value && form.task.dolphinNodeType === 'SQL' && !!sqlText && !analysisLoading.value
})

const sqlHighlights = computed(() => {
  const highlights = []
  const collect = (items = []) => {
    items.forEach((item) => {
      ;(item?.spans || []).forEach((span) => {
        const from = Number(span?.from)
        const to = Number(span?.to)
        if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from) return
        highlights.push({ from, to, status: item?.matchStatus || 'matched' })
      })
    })
  }

  collect(sqlAnalysis.inputRefs)
  collect(sqlAnalysis.outputRefs)
  return highlights
})

const formatTableOptionLabel = (option) => {
  if (!option) return ''
  const pieces = [option.tableName || '']
  const meta = []

  const sourceMeta = [option.clusterName, option.sourceType].filter(Boolean).join('/')
  if (sourceMeta) meta.push(sourceMeta)
  if (option.layer) meta.push(option.layer)
  if (option.dbName) meta.push(option.dbName)

  if (meta.length) pieces.push(`(${meta.join(' / ')})`)
  return pieces.join(' ')
}

const upsertTableOptions = (items = []) => {
  items.forEach((item) => {
    if (item && item.id) {
      tableOptionCache[item.id] = item
    }
  })
}

const fetchTableOptions = async (keyword) => {
  if (!keyword) {
    tableOptions.value = []
    return
  }

  tableLoading.value = true
  try {
    const result = await tableApi.searchOptions({ keyword, limit: 20 })
    const list = Array.isArray(result) ? result : []
    tableOptions.value = list
    upsertTableOptions(list)
  } catch (error) {
    console.error('远程搜索表失败:', error)
  } finally {
    tableLoading.value = false
  }
}

const handleTableSearch = (query) => {
  const keyword = query ? query.trim() : ''
  if (tableSearchTimer) clearTimeout(tableSearchTimer)
  if (!keyword) {
    tableOptions.value = []
    return
  }
  tableSearchTimer = setTimeout(() => {
    fetchTableOptions(keyword)
  }, 300)
}

const ensureTableOptionsLoaded = async (ids = []) => {
  const uniqueIds = [...new Set(ids)].filter((id) => id && !tableOptionCache[id])
  if (!uniqueIds.length) return

  try {
    const tables = await Promise.all(uniqueIds.map((id) => tableApi.getById(id)))
    const options = tables.filter(Boolean).map((table) => ({
      id: table.id,
      tableName: table.tableName,
      tableComment: table.tableComment,
      layer: table.layer,
      dbName: table.dbName,
      clusterId: table.clusterId,
      qualifiedName: table.dbName && table.tableName ? `${table.dbName}.${table.tableName}` : table.tableName
    }))
    upsertTableOptions(options)
  } catch (error) {
    console.error('加载表选项失败:', error)
  }
}

const setTableSelections = (inputIds = [], outputIds = []) => {
  isSyncingSelection.value = true
  form.inputTableIds = [...new Set((inputIds || []).filter(Boolean))]
  form.outputTableIds = [...new Set((outputIds || []).filter(Boolean))]
  nextTick(() => {
    isSyncingSelection.value = false
  })
}

const clearSqlAnalysis = () => {
  analysisLoading.value = false
  analysisError.value = ''
  sqlAnalysis.inputRefs = []
  sqlAnalysis.outputRefs = []
  sqlAnalysis.unmatched = []
  sqlAnalysis.ambiguous = []
}

const cancelPendingAnalyze = () => {
  analyzeSeq += 1
  if (sqlAnalyzeTimer) {
    clearTimeout(sqlAnalyzeTimer)
    sqlAnalyzeTimer = null
  }
  analysisLoading.value = false
}

const runSqlAnalyze = async (sqlText, options = {}) => {
  const { autoApply = true } = options
  const seq = ++analyzeSeq
  analysisLoading.value = true
  analysisError.value = ''

  try {
    const result = await taskApi.analyzeSqlTables({
      sql: sqlText,
      nodeType: form.task.dolphinNodeType
    })

    if (seq !== analyzeSeq || !visible.value) return

    sqlAnalysis.inputRefs = Array.isArray(result?.inputRefs) ? result.inputRefs : []
    sqlAnalysis.outputRefs = Array.isArray(result?.outputRefs) ? result.outputRefs : []
    sqlAnalysis.unmatched = Array.isArray(result?.unmatched) ? result.unmatched : []
    sqlAnalysis.ambiguous = Array.isArray(result?.ambiguous) ? result.ambiguous : []

    if (autoApply && !inputSelectionTouched.value && !outputSelectionTouched.value) {
      await applyMatchedSuggestions({ silent: true, mode: 'replace' })
    }
  } catch (error) {
    if (seq !== analyzeSeq || !visible.value) return
    console.error('SQL 解析失败:', error)
    analysisError.value = error?.message || 'SQL 解析失败'
    sqlAnalysis.inputRefs = []
    sqlAnalysis.outputRefs = []
    sqlAnalysis.unmatched = []
    sqlAnalysis.ambiguous = []
  } finally {
    if (seq === analyzeSeq) {
      analysisLoading.value = false
    }
  }
}

const scheduleSqlAnalyze = () => {
  if (sqlAnalyzeTimer) {
    clearTimeout(sqlAnalyzeTimer)
    sqlAnalyzeTimer = null
  }

  if (!visible.value || form.task.dolphinNodeType !== 'SQL') {
    clearSqlAnalysis()
    return
  }

  const sqlText = String(form.task.taskSql || '').trim()
  if (!sqlText) {
    clearSqlAnalysis()
    return
  }

  sqlAnalyzeTimer = setTimeout(() => {
    runSqlAnalyze(sqlText, { autoApply: true })
  }, 600)
}

const handleReAnalyzeClick = () => {
  cancelPendingAnalyze()

  const sqlText = String(form.task.taskSql || '').trim()
  if (!visible.value || form.task.dolphinNodeType !== 'SQL' || !sqlText || analysisLoading.value) {
    return
  }

  runSqlAnalyze(sqlText, { autoApply: false })
}

const applyMatchedSuggestions = async ({ silent = false, mode = 'merge' } = {}) => {
  const inputIds = (sqlAnalysis.inputRefs || [])
    .filter((item) => item?.matchStatus === 'matched' && item?.chosenTable?.tableId)
    .map((item) => item.chosenTable.tableId)

  const outputIds = (sqlAnalysis.outputRefs || [])
    .filter((item) => item?.matchStatus === 'matched' && item?.chosenTable?.tableId)
    .map((item) => item.chosenTable.tableId)

  const uniqInputs = [...new Set(inputIds.filter(Boolean))]
  const uniqOutputs = [...new Set(outputIds.filter(Boolean))]

  const mergedInputs =
    mode === 'replace'
      ? (uniqInputs.length ? uniqInputs : [...(form.inputTableIds || [])])
      : [...new Set([...(form.inputTableIds || []), ...uniqInputs])]
  const mergedOutputs =
    mode === 'replace'
      ? (uniqOutputs.length ? uniqOutputs : [...(form.outputTableIds || [])])
      : [...new Set([...(form.outputTableIds || []), ...uniqOutputs])]

  const changed =
    mergedInputs.length !== (form.inputTableIds || []).length ||
    mergedOutputs.length !== (form.outputTableIds || []).length

  if (!changed) {
    if (!silent) ElMessage.info('没有可应用的新建议')
    return
  }

  setTableSelections(mergedInputs, mergedOutputs)
  await ensureTableOptionsLoaded([...mergedInputs, ...mergedOutputs])

  if (!silent) {
    ElMessage.success('已应用 SQL 解析建议')
  }
}

const analysisStatusText = (status) => {
  if (status === 'matched') return '命中'
  if (status === 'ambiguous') return '歧义'
  if (status === 'unmatched') return '未识别'
  return '未知'
}

const formatAnalyzeTarget = (item) => {
  if (!item) return '-'
  if (item.matchStatus === 'matched' && item.chosenTable) {
    const c = item.chosenTable
    const source = [c.clusterName, c.sourceType].filter(Boolean).join('/')
    const dbTable = [c.dbName, c.tableName].filter(Boolean).join('.')
    return [source, dbTable].filter(Boolean).join(' / ')
  }
  if (item.matchStatus === 'ambiguous') {
    return `候选 ${Array.isArray(item.candidates) ? item.candidates.length : 0} 个`
  }
  return '未匹配到平台元数据'
}

const focusAnalyzeRef = (item) => {
  const first = item?.spans?.[0]
  if (!first) return
  sqlEditorRef.value?.scrollToRange?.(first.from, first.to)
}

const goToMetadataSync = () => {
  router.push({
    path: '/integration',
    query: {
      redirect: route.fullPath
    }
  })
}

const activeWorkflowDolphinConfigId = computed(() => {
  const workflowId = Number(form.task.workflowId)
  if (!Number.isFinite(workflowId)) {
    return null
  }
  const workflow = workflowOptions.value.find(item => Number(item?.id) === workflowId)
  return workflow?.dolphinConfigId || null
})

const effectiveDolphinConfigId = computed(() => (
  props.dolphinConfigId
  || contextDolphinConfigId.value
  || activeWorkflowDolphinConfigId.value
  || null
))

const open = async (id = null, initialData = {}) => {
  visible.value = true
  loading.value = false
  isEdit.value = !!id
  contextDolphinConfigId.value = initialData.dolphinConfigId || null

  resetForm()
  openedFromRelatedTask.value = Boolean(initialData.relation && initialData.tableId)
  lockedWorkflowId.value = null
  if (initialData.workflowId) {
    form.task.workflowId = initialData.workflowId
    lockedWorkflowId.value = initialData.workflowId
  }
  await fetchWorkflowOptions()
  await fetchDatasourceOptions()

  if (id) {
    try {
      const taskData = (await taskApi.getById(id)) || {}
      Object.assign(form.task, taskData)
      form.task.dolphinFlag = normalizeDolphinFlag(taskData.dolphinFlag)
      syncSqlDatasourceType()
      originalTaskName.value = form.task.taskName

      const lineage = await taskApi.getTaskLineage(id)
      const inputIds = Array.isArray(lineage.inputTableIds) ? lineage.inputTableIds : []
      const outputIds = Array.isArray(lineage.outputTableIds) ? lineage.outputTableIds : []
      setTableSelections(inputIds, outputIds)

      await ensureTableOptionsLoaded([...inputIds, ...outputIds])
    } catch (error) {
      console.error(error)
      ElMessage.error('加载任务详情失败')
    }
  } else {
    if (initialData.taskSql) form.task.taskSql = initialData.taskSql
    if (initialData.taskName) form.task.taskName = initialData.taskName
    if (initialData.taskDesc) form.task.taskDesc = initialData.taskDesc

    if (initialData.relation && initialData.tableId) {
      const tableId = Number(initialData.tableId)
      if (Number.isFinite(tableId)) {
        await ensureTableOptionsLoaded([tableId])
        if (initialData.relation === 'write') {
          setTableSelections([], [tableId])
          isWriteTask.value = true
          const tableInfo = tableOptionCache[tableId]
          if (tableInfo?.tableName) {
            form.task.taskName = tableInfo.tableName
          }
        } else if (initialData.relation === 'read') {
          setTableSelections([tableId], [])
        }
      }
    }
  }

  inputSelectionTouched.value = false
  outputSelectionTouched.value = false
  scheduleSqlAnalyze()
}

const safeResetOnClose = () => {
  cancelPendingAnalyze()
  if (tableSearchTimer) {
    clearTimeout(tableSearchTimer)
    tableSearchTimer = null
  }
  if (taskNameCheckTimer) {
    clearTimeout(taskNameCheckTimer)
    taskNameCheckTimer = null
  }
  taskNameChecking.value = false
  loading.value = false
  clearSqlAnalysis()
}

const handleBeforeClose = (done) => {
  try {
    safeResetOnClose()
  } catch (error) {
    console.error('关闭任务抽屉时发生异常，已强制关闭:', error)
  } finally {
    done()
  }
}

const handleClose = () => {
  try {
    safeResetOnClose()
  } catch (error) {
    console.error('点击取消时重置状态失败:', error)
  }
  visible.value = false
}

const handleDrawerClosed = () => {
  emit('close')
}

const checkTaskName = async (taskName) => {
  if (!taskName) {
    taskNameError.value = ''
    return
  }

  try {
    taskNameChecking.value = true
    const excludeId = isEdit.value ? form.task.id : null
    const exists = await taskApi.checkTaskName(taskName, excludeId)
    taskNameError.value = exists ? '任务名称已存在' : ''
  } catch (error) {
    console.error('检查任务名称失败:', error)
    taskNameError.value = '检查失败，请稍后重试'
  } finally {
    taskNameChecking.value = false
  }
}

const handleTaskNameInput = () => {
  taskNameError.value = ''
  if (isEdit.value && form.task.taskName === originalTaskName.value) {
    return
  }

  if (taskNameCheckTimer) clearTimeout(taskNameCheckTimer)
  taskNameCheckTimer = setTimeout(async () => {
    await checkTaskName(form.task.taskName)
  }, 500)
}

const handleTaskNameBlur = () => {
  if (taskNameCheckTimer) {
    clearTimeout(taskNameCheckTimer)
    taskNameCheckTimer = null
  }
  checkTaskName(form.task.taskName)
}

const promptWorkflowPublishAfterSave = async (workflowId, options = {}) => {
  if (!workflowId) {
    if (!shouldPromptUnboundWorkflowGuidance(workflowId, options)) return
    try {
      await ElMessageBox.confirm(
        '任务已创建，但尚未绑定工作流。如需进入 Dolphin 调度，请到任务调度页面将任务加入工作流，发布后再上线。',
        '任务尚未进入工作流',
        {
          type: 'warning',
          confirmButtonText: '去任务调度',
          cancelButtonText: '稍后处理'
        }
      )
      router.push({ path: '/workflows', query: { tab: 'tasks' } })
    } catch (error) {
      // 用户选择稍后处理。
    }
    return
  }

  try {
    await ElMessageBox.confirm(
      '请跳转到任务调度页面，将工作流发布到 Dolphin。',
      '工作流有变化',
      {
        type: 'warning',
        confirmButtonText: '去发布',
        cancelButtonText: '稍后处理'
      }
    )
    router.push({ path: `/workflows/${workflowId}`, query: { publishHint: '1' } })
  } catch (error) {
    // 用户选择稍后处理。
  }
}

const handleSave = async () => {
  if (!formRef.value) return

  if (taskNameError.value) {
    ElMessage.error('请解决任务名称重复问题')
    return
  }

  try {
    await formRef.value.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    const taskPayload = buildTaskPayload(form.task)
    const payload = {
      task: taskPayload,
      inputTableIds: form.inputTableIds,
      outputTableIds: form.outputTableIds
    }

    let savedTask = null
    if (isEdit.value) {
      savedTask = await taskApi.update(form.task.id, payload)
    } else {
      savedTask = await taskApi.create(payload)
    }
    const workflowId = resolveTaskWorkflowId(savedTask, payload, form.task)
    const saveOptions = { fromRelatedTask: openedFromRelatedTask.value }
    ElMessage.success(buildTaskSaveSuccessMessage(isEdit.value, workflowId, saveOptions))

    visible.value = false
    emit('saved')
    emit('success')
    await promptWorkflowPublishAfterSave(workflowId, saveOptions)
  } catch (error) {
    console.error(error)
    ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  form.task = createDefaultTaskModel()

  setTableSelections([], [])
  tableOptions.value = []
  taskNameError.value = ''
  originalTaskName.value = ''
  isWriteTask.value = false
  openedFromRelatedTask.value = false
  inputSelectionTouched.value = false
  outputSelectionTouched.value = false
  clearSqlAnalysis()
}

const handleNodeTypeChange = (newType) => {
  if (newType !== 'SQL') {
    form.task.datasourceName = ''
    form.task.datasourceType = null
    clearSqlAnalysis()
  }
  if (newType !== 'DATAX') {
    form.task.targetDatasourceName = ''
    form.task.sourceTable = ''
    form.task.targetTable = ''
    form.task.columnMapping = ''
  }
  if (!['SQL', 'SHELL', 'PYTHON'].includes(newType)) {
    form.task.taskSql = ''
  }

  scheduleSqlAnalyze()
}

watch(
  () => form.task.datasourceName,
  () => {
    if (form.task.dolphinNodeType === 'SQL') {
      syncSqlDatasourceType()
    } else if (!form.task.datasourceName) {
      form.task.datasourceType = null
    }
  }
)

watch(
  () => [...(form.inputTableIds || [])],
  () => {
    if (!isSyncingSelection.value) {
      inputSelectionTouched.value = true
    }
  }
)

watch(
  () => [...(form.outputTableIds || [])],
  () => {
    if (!isSyncingSelection.value) {
      outputSelectionTouched.value = true
    }
  }
)

watch(
  () => form.task.taskSql,
  () => {
    scheduleSqlAnalyze()
  }
)

watch(
  () => form.task.dolphinNodeType,
  () => {
    scheduleSqlAnalyze()
  }
)

watch(effectiveDolphinConfigId, async (nextId, prevId) => {
  if (!visible.value || nextId === prevId) {
    return
  }
  await fetchDatasourceOptions()
})

onBeforeUnmount(() => {
  cancelPendingAnalyze()
  if (tableSearchTimer) clearTimeout(tableSearchTimer)
  if (taskNameCheckTimer) clearTimeout(taskNameCheckTimer)
})

defineExpose({
  open
})
</script>

<style scoped>
.sql-input :deep(.el-textarea__inner) {
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  background-color: #fafafa;
  color: #333;
  padding: 12px;
}

.sql-input :deep(.el-textarea__inner):focus {
  background-color: #fff;
  border-color: #409eff;
}

.sql-workbench {
  width: 100%;
  height: 392px;
  display: flex;
  gap: 12px;
  align-items: stretch;
}

.sql-codemirror {
  flex: 1;
  min-width: 0;
  height: 100%;
  min-height: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  overflow: hidden;
  background: #fff;
}

.sql-analysis-panel {
  width: 332px;
  flex: 0 0 332px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: #fff;
  padding: 10px;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.sql-analysis-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.analysis-heading {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.sql-analysis-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.sql-analysis-stats {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.sql-analysis-stats strong {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.analysis-alert {
  margin-top: 8px;
}

.analysis-error {
  margin-top: 8px;
  color: var(--el-color-danger);
  font-size: 12px;
}

.analysis-grid {
  margin-top: 10px;
  display: grid;
  gap: 10px;
  flex: 1;
  min-height: 0;
  max-height: 390px;
  overflow: auto;
  padding-right: 2px;
  scrollbar-width: thin;
  scrollbar-color: var(--el-scrollbar-bg-color, #a8abb2) transparent;
}

.analysis-grid::-webkit-scrollbar {
  width: 10px;
  height: 10px;
}

.analysis-grid::-webkit-scrollbar-track {
  background-color: transparent;
}

.analysis-grid::-webkit-scrollbar-thumb {
  background-color: var(--el-scrollbar-bg-color, #a8abb2);
  border-radius: 10px;
}

.analysis-grid::-webkit-scrollbar-thumb:hover {
  background-color: var(--el-scrollbar-hover-bg-color, #909399);
}

.analysis-grid::-webkit-scrollbar-corner {
  background-color: transparent;
}

.analysis-group {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 8px;
  background: #fff;
}

.analysis-group :deep(.el-empty) {
  padding: 6px 0;
}

.analysis-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 6px;
}

.analysis-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px;
  margin-bottom: 6px;
  cursor: pointer;
  background: #fff;
  transition: border-color 0.16s ease;
}

.analysis-item:last-child {
  margin-bottom: 0;
}

.analysis-item:hover {
  border-color: var(--el-border-color);
}

.analysis-state {
  display: inline-block;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

.analysis-raw {
  display: block;
  color: var(--el-text-color-primary);
  font-family: 'JetBrains Mono', 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.45;
  word-break: break-all;
}

.analysis-target {
  display: block;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.35;
  word-break: break-all;
}

@media (max-width: 1520px) {
  .sql-workbench {
    height: auto;
    flex-direction: column;
  }

  .sql-codemirror {
    height: 320px;
    min-height: 320px;
  }

  .sql-analysis-panel {
    width: 100%;
    flex: 1 1 auto;
    height: auto;
    min-height: 300px;
  }

  .analysis-grid {
    max-height: 320px;
  }
}

.error-text {
  color: #f56c6c;
  font-size: 12px;
  margin-top: 4px;
}

.hint-text {
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
}

.error-icon {
  color: #f56c6c;
}

.success-icon {
  color: #67c23a;
}

.is-loading {
  animation: rotating 2s linear infinite;
}

@keyframes rotating {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}
</style>
