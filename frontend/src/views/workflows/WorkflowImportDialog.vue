<template>
  <el-dialog
    :model-value="modelValue"
    title="导入工作流"
    width="980px"
    @close="handleClose"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="130px"
      class="import-panel"
    >
      <el-form-item label="导入来源">
        <el-radio-group v-model="form.importMode" @change="handleModeChange">
          <el-radio-button label="json">JSON 导入</el-radio-button>
          <el-radio-button label="dolphin">从 Dolphin 导入</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="目标 Dolphin" prop="dolphinConfigId">
        <div class="inline-field">
          <el-select
            v-model="form.dolphinConfigId"
            placeholder="请选择目标 Dolphin 环境"
            filterable
            :loading="dolphinConfigsLoading"
            class="grow"
            @change="handleDolphinConfigChange"
          >
            <el-option
              v-for="item in dolphinConfigs"
              :key="item.id"
              :label="formatDolphinConfigLabel(item)"
              :value="item.id"
              :disabled="item.isActive === false"
            />
          </el-select>
          <el-button link type="primary" @click="goToDolphinSettings">管理 Dolphin</el-button>
        </div>
        <div v-if="!dolphinConfigsLoading && !selectableConfigCount" class="field-hint is-warning">
          还没有可用的 Dolphin 连接，请先前往「管理 Dolphin」完成配置
        </div>
      </el-form-item>

      <template v-if="form.importMode === 'json'">
        <el-form-item label="定义文件" prop="definitionJson">
          <div class="import-toolbar">
            <input
              ref="fileInputRef"
              type="file"
              accept=".json,application/json"
              class="hidden-file-input"
              @change="handleFileSelected"
            >
            <el-button @click="openFilePicker">选择文件</el-button>
            <span class="file-name">{{ fileName || '未选择文件' }}</span>
          </div>
          <el-input
            v-model="form.definitionJson"
            type="textarea"
            :rows="8"
            placeholder="可直接粘贴 workflow JSON 文本，或选择 .json 文件"
            @change="handleDefinitionJsonChange"
          />
        </el-form-item>

        <el-form-item label="关联运行态">
          <div class="inline-field">
            <el-select
              v-model="form.linkedWorkflowCode"
              placeholder="不关联，作为全新工作流导入"
              clearable
              filterable
              remote
              :remote-method="handleRuntimeSearch"
              :loading="runtimeLoading"
              :disabled="!form.dolphinConfigId"
              class="grow"
              @change="handleLinkedWorkflowChange"
            >
              <el-option
                v-for="item in runtimeWorkflows"
                :key="item.workflowCode"
                :label="formatRuntimeWorkflowLabel(item)"
                :value="item.workflowCode"
                :disabled="Boolean(item.localWorkflowId)"
              />
            </el-select>
          </div>
          <div v-if="runtimeConflictText" class="field-hint is-error">{{ runtimeConflictText }}</div>
          <div v-else class="field-hint">
            留空则发布时在目标 Dolphin 创建新的工作流定义；选中则发布时更新该定义
          </div>
        </el-form-item>
      </template>

      <template v-else>
        <el-form-item label="运行态工作流" prop="dolphinWorkflow">
          <el-table
            v-loading="dolphinLoading"
            :data="dolphinWorkflows"
            row-key="workflowCode"
            highlight-current-row
            max-height="240"
            @current-change="handleDolphinCurrentChange"
          >
            <el-table-column label="工作流" min-width="280">
              <template #default="{ row }">
                <div class="name-line">
                  <span>{{ row.workflowName || '-' }}</span>
                  <el-tag size="small" :type="row.releaseState === 'ONLINE' ? 'success' : 'info'">
                    {{ row.releaseState || '-' }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="projectCode" label="项目编码" width="140" />
            <el-table-column prop="workflowCode" label="工作流编码" width="170" />
          </el-table>
          <el-pagination
            class="pagination"
            v-model:current-page="dolphinPagination.pageNum"
            v-model:page-size="dolphinPagination.pageSize"
            :total="dolphinPagination.total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @size-change="handleDolphinSizeChange"
            @current-change="loadDolphinWorkflows"
          />
        </el-form-item>
      </template>

      <el-form-item label="新工作流名称" prop="workflowName">
        <el-input
          v-model="form.workflowName"
          placeholder="默认取定义中的名称，可编辑"
          maxlength="100"
          show-word-limit
        />
      </el-form-item>

      <el-form-item label=" ">
        <el-button
          type="primary"
          :loading="previewLoading"
          @click="handlePreview"
        >
          预检
        </el-button>
      </el-form-item>

      <div v-if="previewResult" class="preview-panel">
        <div class="preview-header">
          <el-tag :type="previewResult.canImport ? 'success' : 'danger'">
            {{ previewResult.canImport ? '可导入' : '不可导入' }}
          </el-tag>
          <span>工作流：{{ previewResult.workflowName || '-' }}</span>
          <span>任务数：{{ previewResult.taskCount || 0 }}</span>
        </div>

        <el-alert
          v-if="runtimeBindingHint"
          :type="runtimeBindingHint.type"
          :title="runtimeBindingHint.text"
          :closable="false"
          show-icon
        />

        <el-alert
          v-if="previewResult.errors?.length"
          type="error"
          :closable="false"
          show-icon
          title="预检错误"
        >
          <template #default>
            <div
              v-for="(item, idx) in previewResult.errors"
              :key="`error-${idx}`"
              class="issue-line"
            >
              {{ item }}
            </div>
          </template>
        </el-alert>

        <el-alert
          v-if="previewResult.warnings?.length"
          type="warning"
          :closable="false"
          show-icon
          title="预检告警"
        >
          <template #default>
            <div
              v-for="(item, idx) in previewResult.warnings"
              :key="`warning-${idx}`"
              class="issue-line"
            >
              {{ item }}
            </div>
          </template>
        </el-alert>

        <div v-if="previewResult.relationDecisionRequired" class="relation-decision">
          <div class="relation-title">关系差异存在，请选择导入轨道</div>
          <el-radio-group v-model="form.relationDecision">
            <el-radio label="INFERRED">SQL 推断关系（推荐）</el-radio>
            <el-radio label="DECLARED">文件声明关系</el-radio>
          </el-radio-group>
          <div class="relation-hint">
            不选轨道将无法提交导入。
          </div>
        </div>

        <div
          v-if="previewResult.relationCompareDetail && (
            previewResult.relationCompareDetail.onlyInDeclared?.length
              || previewResult.relationCompareDetail.onlyInInferred?.length
          )"
          class="relation-diff"
        >
          <div class="relation-col">
            <div class="relation-col-title">仅声明关系</div>
            <div
              v-for="(edge, idx) in previewResult.relationCompareDetail.onlyInDeclared"
              :key="`declared-${idx}`"
              class="edge-line"
            >
              {{ formatEdge(edge) }}
            </div>
            <div v-if="!previewResult.relationCompareDetail.onlyInDeclared?.length" class="empty-line">-</div>
          </div>
          <div class="relation-col">
            <div class="relation-col-title">仅 SQL 推断</div>
            <div
              v-for="(edge, idx) in previewResult.relationCompareDetail.onlyInInferred"
              :key="`inferred-${idx}`"
              class="edge-line"
            >
              {{ formatEdge(edge) }}
            </div>
            <div v-if="!previewResult.relationCompareDetail.onlyInInferred?.length" class="empty-line">-</div>
          </div>
        </div>
      </div>
    </el-form>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button
        type="primary"
        :disabled="!canCommit"
        :loading="commitLoading"
        @click="handleCommit"
      >
        确认导入
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { workflowApi } from '@/api/workflow'
import { settingsApi } from '@/api/settings'
import {
  buildImportPayload,
  describeRuntimeBinding,
  describeRuntimeConflict,
  formatDolphinConfigLabel,
  formatRuntimeWorkflowLabel,
  parseDefinitionHints,
  resolveDefaultDolphinConfigId
} from './importFormHelper'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:modelValue', 'imported'])

const router = useRouter()
const formRef = ref(null)
const fileInputRef = ref(null)
const fileName = ref('')

const form = reactive({
  importMode: 'json',
  dolphinConfigId: null,
  definitionJson: '',
  linkedWorkflowCode: null,
  dolphinWorkflow: null,
  workflowName: '',
  relationDecision: ''
})

const rules = {
  dolphinConfigId: [{ required: true, message: '请选择目标 Dolphin 环境', trigger: 'change' }],
  definitionJson: [{ required: true, message: '请选择文件或粘贴 JSON', trigger: 'blur' }],
  workflowName: [{ required: true, message: '请填写新工作流名称', trigger: 'blur' }],
  dolphinWorkflow: [{
    // 选中的是整行对象，用自定义校验避免 required 按字符串规则判空
    validator: (rule, value, callback) => {
      callback(value?.workflowCode ? undefined : new Error('请选择要导入的 Dolphin 工作流'))
    },
    trigger: 'change'
  }]
}

const dolphinConfigs = ref([])
const dolphinConfigsLoading = ref(false)

const runtimeWorkflows = ref([])
const runtimeLoading = ref(false)

const dolphinLoading = ref(false)
const dolphinWorkflows = ref([])
const dolphinPagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

const previewLoading = ref(false)
const commitLoading = ref(false)
const previewResult = ref(null)

const selectableConfigCount = computed(
  () => dolphinConfigs.value.filter((item) => item.isActive !== false).length
)

const selectedRuntimeWorkflow = computed(
  () => runtimeWorkflows.value.find((item) => item.workflowCode === form.linkedWorkflowCode) || null
)

const runtimeConflictText = computed(() => describeRuntimeConflict(selectedRuntimeWorkflow.value))

const runtimeBindingHint = computed(() => describeRuntimeBinding(previewResult.value?.runtimeBinding))

const canCommit = computed(() => {
  if (!previewResult.value?.canImport) return false
  if (runtimeConflictText.value) return false
  if (!previewResult.value?.relationDecisionRequired) return true
  return Boolean(form.relationDecision)
})

watch(() => props.modelValue, (visible) => {
  if (!visible) return
  loadDolphinConfigs()
})

const loadDolphinConfigs = async () => {
  dolphinConfigsLoading.value = true
  try {
    const list = await settingsApi.listDolphinConfigs()
    dolphinConfigs.value = Array.isArray(list) ? list : (list?.records || [])
    if (!form.dolphinConfigId) {
      form.dolphinConfigId = resolveDefaultDolphinConfigId(dolphinConfigs.value)
    }
    if (form.dolphinConfigId) {
      refreshRuntimeSources()
    }
  } catch (error) {
    console.error('加载 Dolphin 配置失败', error)
    ElMessage.error('加载 Dolphin 配置失败')
  } finally {
    dolphinConfigsLoading.value = false
  }
}

const goToDolphinSettings = () => {
  router.push({ path: '/settings', query: { tab: 'dolphin' } })
}

const handleDolphinConfigChange = () => {
  previewResult.value = null
  form.linkedWorkflowCode = null
  form.dolphinWorkflow = null
  dolphinPagination.pageNum = 1
  refreshRuntimeSources()
}

const refreshRuntimeSources = async () => {
  if (form.importMode === 'dolphin') {
    await loadDolphinWorkflows()
    return
  }
  // 先把列表拉回来再补预选项，否则列表结果会把预选项覆盖掉
  await loadRuntimeWorkflows()
  await autoLinkFromDefinition()
}

const openFilePicker = () => {
  fileInputRef.value?.click()
}

const handleFileSelected = async (event) => {
  const file = event?.target?.files?.[0]
  if (!file) return
  fileName.value = file.name
  try {
    form.definitionJson = await file.text()
    handleDefinitionJsonChange()
  } catch (error) {
    console.error('读取文件失败', error)
    ElMessage.error('读取文件失败')
  }
}

/**
 * 文件换了就重新猜一次名称与关联项，但不覆盖用户已经手工改过的名称。
 */
const handleDefinitionJsonChange = () => {
  previewResult.value = null
  const hints = parseDefinitionHints(form.definitionJson)
  if (hints.workflowName && !form.workflowName) {
    form.workflowName = hints.workflowName
  }
  autoLinkFromDefinition()
}

/**
 * 用文件里携带的来源编码在目标 Dolphin 里探一次：命中就默认关联上，
 * 没命中就保持"不关联"，用户不需要理解 workflowCode 这些内部概念。
 */
const autoLinkFromDefinition = async () => {
  const hints = parseDefinitionHints(form.definitionJson)
  if (!hints.workflowCode || !form.dolphinConfigId) return
  try {
    const found = await workflowApi.findImportDolphinWorkflow(hints.workflowCode, {
      dolphinConfigId: form.dolphinConfigId
    })
    if (!found?.workflowCode) return
    if (!runtimeWorkflows.value.some((item) => item.workflowCode === found.workflowCode)) {
      runtimeWorkflows.value = [found, ...runtimeWorkflows.value]
    }
    if (!found.localWorkflowId) {
      form.linkedWorkflowCode = found.workflowCode
    }
  } catch (error) {
    console.error('探测目标 Dolphin 运行态失败', error)
  }
}

const loadRuntimeWorkflows = async (keyword) => {
  if (!form.dolphinConfigId) return
  runtimeLoading.value = true
  try {
    const page = await workflowApi.listImportDolphinWorkflows({
      dolphinConfigId: form.dolphinConfigId,
      pageNum: 1,
      pageSize: 50,
      keyword: keyword || undefined
    })
    runtimeWorkflows.value = page?.records || []
  } catch (error) {
    console.error('加载目标 Dolphin 工作流失败', error)
    runtimeWorkflows.value = []
  } finally {
    runtimeLoading.value = false
  }
}

const handleRuntimeSearch = (keyword) => {
  loadRuntimeWorkflows(keyword)
}

const handleLinkedWorkflowChange = () => {
  previewResult.value = null
}

const handleModeChange = () => {
  previewResult.value = null
  form.relationDecision = ''
  form.linkedWorkflowCode = null
  form.dolphinWorkflow = null
  refreshRuntimeSources()
}

const handleDolphinSizeChange = () => {
  dolphinPagination.pageNum = 1
  loadDolphinWorkflows()
}

const loadDolphinWorkflows = async () => {
  if (!form.dolphinConfigId) return
  dolphinLoading.value = true
  try {
    const page = await workflowApi.listImportDolphinWorkflows({
      dolphinConfigId: form.dolphinConfigId,
      pageNum: dolphinPagination.pageNum,
      pageSize: dolphinPagination.pageSize
    })
    dolphinWorkflows.value = page?.records || []
    dolphinPagination.total = page?.total || 0
  } catch (error) {
    console.error('加载 Dolphin 工作流失败', error)
    ElMessage.error('加载 Dolphin 工作流失败')
  } finally {
    dolphinLoading.value = false
  }
}

const handleDolphinCurrentChange = (row) => {
  form.dolphinWorkflow = row || null
  previewResult.value = null
  form.relationDecision = ''
  if (row) {
    form.workflowName = String(row.workflowName || `workflow_${row.workflowCode}`).trim()
  }
}

const handlePreview = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  previewLoading.value = true
  try {
    const result = await workflowApi.previewImportDefinition(buildImportPayload(form))
    previewResult.value = result
    form.relationDecision = result?.suggestedRelationDecision || 'INFERRED'
  } catch (error) {
    console.error('导入预检失败', error)
  } finally {
    previewLoading.value = false
  }
}

const handleCommit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!canCommit.value) {
    ElMessage.warning('当前预检未通过，无法导入')
    return
  }
  commitLoading.value = true
  try {
    const payload = buildImportPayload(form)
    if (!previewResult.value?.relationDecisionRequired) {
      delete payload.relationDecision
    }
    const result = await workflowApi.commitImportDefinition(payload)
    ElMessage.success(`导入成功：${result?.workflowName || ''}`)
    emit('imported', result)
    handleClose()
  } catch (error) {
    console.error('导入提交失败', error)
  } finally {
    commitLoading.value = false
  }
}

const formatEdge = (edge) => {
  if (!edge) return '-'
  const pre = edge.preTaskCode === 0 ? '入口' : (edge.preTaskName || edge.preTaskCode || '-')
  const post = edge.postTaskName || edge.postTaskCode || '-'
  return `${pre} -> ${post}`
}

const resetState = () => {
  form.importMode = 'json'
  form.dolphinConfigId = null
  form.definitionJson = ''
  form.linkedWorkflowCode = null
  form.dolphinWorkflow = null
  form.workflowName = ''
  form.relationDecision = ''
  fileName.value = ''
  previewResult.value = null
  runtimeWorkflows.value = []
  dolphinWorkflows.value = []
  dolphinPagination.pageNum = 1
  dolphinPagination.pageSize = 10
  dolphinPagination.total = 0
  formRef.value?.clearValidate()
  if (fileInputRef.value) {
    fileInputRef.value.value = ''
  }
}

const handleClose = () => {
  resetState()
  emit('update:modelValue', false)
}
</script>

<style scoped>
.import-panel {
  display: flex;
  flex-direction: column;
}

.inline-field {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}

.inline-field .grow {
  flex: 1;
}

.import-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  margin-bottom: 8px;
}

.hidden-file-input {
  display: none;
}

.file-name {
  color: #606266;
  font-size: 13px;
  flex: 1;
}

.field-hint {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: #909399;
}

.field-hint.is-warning {
  color: #e6a23c;
}

.field-hint.is-error {
  color: #f56c6c;
}

.name-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  width: 100%;
  margin-top: 8px;
}

.preview-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 4px;
}

.preview-header {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 13px;
  color: #303133;
}

.issue-line {
  line-height: 1.6;
  font-size: 13px;
}

.relation-decision {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.relation-title {
  font-weight: 600;
}

.relation-hint {
  font-size: 12px;
  color: #909399;
}

.relation-diff {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.relation-col {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px;
  min-height: 90px;
}

.relation-col-title {
  font-weight: 600;
  margin-bottom: 6px;
}

.edge-line {
  font-size: 12px;
  line-height: 1.5;
  color: #606266;
}

.empty-line {
  color: #c0c4cc;
  font-size: 12px;
}
</style>
