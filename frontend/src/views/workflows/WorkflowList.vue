<template>
  <div class="workflow-list">
    <el-card>
      <div class="toolbar">
        <div class="filters">
          <el-input
            v-model="query.keyword"
            placeholder="按名称搜索"
            clearable
            class="filter-item"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-select
            v-model="query.status"
            placeholder="状态"
            clearable
            class="filter-item"
          >
            <el-option
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>
        <div class="toolbar-actions">
          <el-button :disabled="isDemoMode" @click="openImportDialog">导入工作流</el-button>
          <el-button type="primary" :icon="Plus" plain :disabled="isDemoMode" @click="openCreateDrawer">
            新建工作流
          </el-button>
        </div>
      </div>

      <el-table
        v-loading="loading"
        :data="workflows"
        row-key="id"
        style="width: 100%"
      >
        <el-table-column label="工作流" min-width="260">
          <template #default="{ row }">
            <div class="workflow-name">
              <span
                class="name-link"
                :class="{ 'is-disabled': isDemoMode }"
                @click="handleViewDetail(row)"
              >
                {{ row.workflowName }}
              </span>
              <el-tag size="small" :type="getWorkflowStatusType(row.status)">
                {{ getWorkflowStatusText(row.status) }}
              </el-tag>
              <el-tag
                v-if="pendingApprovalFlags[row.id]"
                size="small"
                type="warning"
                effect="plain"
              >
                待审批
              </el-tag>
            </div>
            <div class="workflow-meta">
              <span>ID: {{ row.id }}</span>
              <span>项目: {{ row.projectCode || '-' }}</span>
              <span>DS编码: {{ row.workflowCode || '-' }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="最近实例" min-width="220">
          <template #default="{ row }">
            <div v-if="row.latestInstanceId" class="latest-instance">
              <el-link
                type="primary"
                :disabled="!buildRowInstanceUrl(row)"
                @click="openRowInstance(row)"
              >
                #{{ row.latestInstanceId }}
              </el-link>
              <el-tag size="small" :type="getInstanceStateType(row.latestInstanceState)">
                {{ getInstanceStateText(row.latestInstanceState) }}
              </el-tag>
              <div class="instance-time">
                {{ formatDateTime(row.latestInstanceStartTime) || '-' }}
              </div>
            </div>
            <span v-else class="text-gray">-</span>
          </template>
        </el-table-column>

        <el-table-column label="发布状态" width="140">
          <template #default="{ row }">
            <el-tag size="small" :type="getPublishStateType(row)">
              {{ getPublishStateText(row) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="定时调度" width="220">
          <template #default="{ row }">
            <div v-if="row.dolphinScheduleId" class="latest-instance">
              <el-tag
                size="small"
                :type="(row.scheduleState || '').toUpperCase() === 'ONLINE' ? 'success' : 'warning'"
              >
                {{ row.scheduleState || 'OFFLINE' }}
              </el-tag>
              <div class="instance-time">
                {{ row.scheduleCron || '-' }}
              </div>
            </div>
            <span v-else class="text-gray">-</span>
          </template>
        </el-table-column>

        <el-table-column label="当前版本" width="120">
          <template #default="{ row }">
            {{ row.currentVersionNo ? `v${row.currentVersionNo}` : '-' }}
          </template>
        </el-table-column>

        <el-table-column prop="updatedAt" label="更新时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.updatedAt) }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="460" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :loading="getActionLoading(row.id, 'deploy')"
              :disabled="isDeployDisabled(row)"
              @click="handleDeploy(row)"
            >
              发布
            </el-button>
            <el-button
              link
              type="primary"
              :loading="getActionLoading(row.id, 'execute')"
              :disabled="isExecuteDisabled(row)"
              @click="handleExecute(row)"
            >
              执行
            </el-button>
            <el-button
              link
              type="primary"
              :disabled="isBackfillDisabled(row)"
              @click="openBackfill(row)"
            >
              补数
            </el-button>
            <el-button
              link
              type="success"
              :loading="getActionLoading(row.id, 'online')"
              :disabled="isOnlineDisabled(row)"
              @click="handleOnline(row)"
            >
              上线
            </el-button>
            <el-button
              link
              type="warning"
              :loading="getActionLoading(row.id, 'offline')"
              :disabled="isOfflineDisabled(row)"
              @click="handleOffline(row)"
            >
              下线
            </el-button>
            <el-button
              link
              type="info"
              :icon="Link"
              @click="openDolphin(row)"
              :disabled="!canJumpToDolphin(row)"
            >
              Dolphin
            </el-button>
            <el-button
              link
              type="primary"
              :disabled="isDemoMode"
              @click="handleViewDetail(row)"
            >
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pagination"
        v-model:current-page="pagination.pageNum"
        v-model:page-size="pagination.pageSize"
        :total="pagination.total"
        layout="total, sizes, prev, pager, next, jumper"
        :page-sizes="[10, 20, 50]"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </el-card>

    <!-- Detail Drawer Removed -->

    <WorkflowCreateDrawer
      v-model="createDrawerVisible"
      :workflow-id="editingWorkflowId"
      @created="handleCreateSuccess"
      @updated="handleUpdateSuccess"
    />

    <WorkflowBackfillDialog
      v-model="backfillDialogVisible"
      :workflow="backfillTarget"
      @submitted="handleBackfillSubmitted"
    />

    <WorkflowImportDialog
      v-model="importDialogVisible"
      @imported="handleImported"
    />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch, h } from 'vue'
import dayjs from 'dayjs'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Link, Plus } from '@element-plus/icons-vue'
import { workflowApi } from '@/api/workflow'
import { taskApi } from '@/api/task'
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'
import WorkflowPublishPreviewDialog from './WorkflowPublishPreviewDialog.vue'
import {
  buildPublishRepairHtml,
  firstPreviewErrorMessage,
  isDialogCancel,
  resolvePublishVersionId,
  shouldPromptOnlineAfterDeploy
} from './publishPreviewHelper'
import WorkflowCreateDrawer from './WorkflowCreateDrawer.vue'
import WorkflowBackfillDialog from './WorkflowBackfillDialog.vue'
import WorkflowImportDialog from './WorkflowImportDialog.vue'

const router = useRouter()
const loading = ref(false)
const workflows = ref([])
const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})
const query = reactive({
  keyword: '',
  status: ''
})
const statusOptions = [
  { label: '草稿', value: 'draft' },
  { label: '在线', value: 'online' },
  { label: '下线', value: 'offline' },
  { label: '失败', value: 'failed' }
]

const dolphinWebuiUrl = ref('')
const dolphinWebuiUrlByConfigId = reactive({})
const pendingApprovalFlags = reactive({})
const createDrawerVisible = ref(false)
const editingWorkflowId = ref(null)
const actionLoading = reactive({})
const backfillDialogVisible = ref(false)
const backfillTarget = ref(null)
const importDialogVisible = ref(false)

const loadWorkflows = async () => {
  loading.value = true
  try {
    const res = await workflowApi.list({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      keyword: query.keyword || undefined,
      status: query.status || undefined
    })
    workflows.value = res.records || []
    pagination.total = res.total || 0
    prefetchDolphinWebuiUrls(workflows.value)
  } catch (error) {
    console.error('加载工作流失败', error)
    ElMessage.error('加载工作流失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  pagination.pageNum = 1
  loadWorkflows()
}

const handleReset = () => {
  query.keyword = ''
  query.status = ''
  handleSearch()
}

const openCreateDrawer = () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('新建工作流')
    return
  }
  editingWorkflowId.value = null
  createDrawerVisible.value = true
}


const closeWorkflowDrawer = () => {
  createDrawerVisible.value = false
}

const handleCreateSuccess = () => {
  closeWorkflowDrawer()
  pagination.pageNum = 1
  loadWorkflows()
}

const openImportDialog = () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('导入工作流')
    return
  }
  importDialogVisible.value = true
}

const handleImported = () => {
  pagination.pageNum = 1
  loadWorkflows()
}

const handleUpdateSuccess = (workflowId) => {
  closeWorkflowDrawer()
  loadWorkflows()
}

const handleSizeChange = (size) => {
  pagination.pageSize = size
  pagination.pageNum = 1
  loadWorkflows()
}

const handleCurrentChange = () => {
  loadWorkflows()
}

const handleViewDetail = (row) => {
  if (!row?.id) return
  if (isDemoMode) {
    showDemoReadonlyMessage('工作流详情')
    return
  }
  router.push(`/workflows/${row.id}`)
}

const syncPendingFlag = (workflowId, records) => {
  if (!workflowId) {
    return
  }
  const hasPending = Array.isArray(records)
    && records.some((record) => record.status === 'pending_approval')
  if (hasPending) {
    pendingApprovalFlags[workflowId] = true
  } else {
    delete pendingApprovalFlags[workflowId]
  }
}

const canJumpToDolphin = (workflow) => {
  const webuiUrl = resolveDolphinWebuiUrl(workflow)
  return Boolean(
    webuiUrl
    && workflow?.workflowCode
    && workflow?.projectCode
  )
}

const buildDolphinWorkflowUrl = (workflow) => {
  const webuiUrl = resolveDolphinWebuiUrl(workflow)
  if (!webuiUrl || !workflow?.projectCode || !workflow?.workflowCode) {
    return ''
  }
  const base = webuiUrl.replace(/\/+$/, '')
  return `${base}/ui/projects/${workflow.projectCode}/workflow/definitions/${workflow.workflowCode}`
}

const openDolphin = (workflow) => {
  const url = buildDolphinWorkflowUrl(workflow)
  if (!url) {
    ElMessage.warning('尚未配置 Dolphin WebUI 地址')
    return
  }
  window.open(url, '_blank')
}


const buildRowInstanceUrl = (row) => {
  const webuiUrl = resolveDolphinWebuiUrl(row)
  if (!row || !webuiUrl || !row.projectCode || !row.workflowCode || !row.latestInstanceId) {
    return ''
  }
  const base = webuiUrl.replace(/\/+$/, '')
  return `${base}/ui/projects/${row.projectCode}/workflow/instances/${row.latestInstanceId}?code=${row.workflowCode}`
}

const openRowInstance = (row) => {
  const url = buildRowInstanceUrl(row)
  if (!url) {
    ElMessage.warning('无法跳转到实例详情')
    return
  }
  window.open(url, '_blank')
}

watch(createDrawerVisible, (visible) => {
  if (!visible) {
    editingWorkflowId.value = null
  }
})

const formatDateTime = (value) => {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

const formatDuration = (durationMs, startTime, endTime) => {
  let duration = durationMs
  if (!duration && startTime && endTime) {
    duration = dayjs(endTime).diff(dayjs(startTime))
  }
  if (!duration) {
    return '-'
  }
  const seconds = Math.floor(duration / 1000)
  const minutes = Math.floor(seconds / 60)
  const remainSeconds = seconds % 60
  return minutes ? `${minutes}分${remainSeconds}秒` : `${remainSeconds}秒`
}

const formatLog = (log) => {
  if (!log) {
    return '-'
  }
  try {
    const parsed = JSON.parse(log)
    return Object.entries(parsed)
      .map(([key, value]) => `${key}: ${value}`)
      .join(', ')
  } catch (error) {
    return log
  }
}

const getErrorMessage = (error) => {
  return error?.response?.data?.message || error?.message || '操作失败，请稍后重试'
}

const setActionLoading = (workflowId, action, value) => {
  if (!workflowId) return
  if (!actionLoading[workflowId]) {
    actionLoading[workflowId] = {}
  }
  if (value) {
    actionLoading[workflowId][action] = true
  } else {
    delete actionLoading[workflowId][action]
    if (Object.keys(actionLoading[workflowId]).length === 0) {
      delete actionLoading[workflowId]
    }
  }
}

const getActionLoading = (workflowId, action) => {
  return Boolean(actionLoading[workflowId]?.[action])
}

const isOnlineDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  if (getActionLoading(row.id, 'online')) return true
  return row.status === 'online'
}

const isOfflineDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  if (getActionLoading(row.id, 'offline')) return true
  return row.status !== 'online'
}

const updatePendingFlag = (workflowId, status) => {
  if (!workflowId) return
  if (status === 'pending_approval') {
    pendingApprovalFlags[workflowId] = true
  } else {
    delete pendingApprovalFlags[workflowId]
  }
}

const resolveDeployOnlineVersionId = (row, record) => {
  return record?.versionId || record?.workflowVersionId || record?.targetVersionId || resolvePublishVersionId(row)
}

const promptOnlineAfterDeploy = async (row, record) => {
  if (!shouldPromptOnlineAfterDeploy(row, record)) return
  try {
    await ElMessageBox.confirm(
      '发布已成功，是否立即上线该工作流？',
      '立即上线',
      {
        type: 'warning',
        confirmButtonText: '立即上线',
        cancelButtonText: '稍后处理'
      }
    )
  } catch (error) {
    return
  }

  setActionLoading(row.id, 'online', true)
  try {
    const onlineRecord = await workflowApi.publish(row.id, {
      operation: 'online',
      versionId: resolveDeployOnlineVersionId(row, record),
      requireApproval: false,
      operator: 'portal-ui'
    })
    updatePendingFlag(row.id, onlineRecord?.status)
    if (onlineRecord?.status === 'pending_approval') {
      ElMessage.warning('上线已提交审批，等待审批通过')
    } else {
      ElMessage.success('上线成功')
    }
  } catch (error) {
    console.error('上线失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'online', false)
  }
}

const isDeployDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  return getActionLoading(row.id, 'deploy')
}

const isExecuteDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  if (getActionLoading(row.id, 'execute')) return true
  return !row.workflowCode
}

const isBackfillDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  return !row.workflowCode
}

const refreshAfterAction = (workflowId) => {
  loadWorkflows()
}

const previewPublishAndConfirm = async (row) => {
  if (!row?.id) {
    return false
  }
  let preview = await workflowApi.previewPublish(row.id)
  if (!preview?.canPublish) {
    ElMessage.error(firstPreviewErrorMessage(preview))
    return false
  }
  const repairIssues = Array.isArray(preview?.repairIssues)
    ? preview.repairIssues.filter(issue => issue?.repairable !== false)
    : []
  if (repairIssues.length) {
    try {
      await ElMessageBox.confirm(
        buildPublishRepairHtml(preview),
        '检测到可修复元数据问题',
        {
          type: 'warning',
          customClass: 'workflow-publish-message-box',
          confirmButtonText: '修复元数据并重试',
          cancelButtonText: '继续发布',
          distinguishCancelAndClose: true,
          dangerouslyUseHTMLString: true
        }
      )
      const repaired = await workflowApi.repairPublishMetadata(row.id, {
        operator: 'portal-ui'
      })
      const changedTaskCount = repaired?.updatedTaskCount ?? 0
      const changedWorkflowCount = Array.isArray(repaired?.updatedWorkflowFields)
        ? repaired.updatedWorkflowFields.length
        : 0
      ElMessage.success(`元数据修复完成：工作流字段 ${changedWorkflowCount} 项，任务 ${changedTaskCount} 个`)
      preview = await workflowApi.previewPublish(row.id)
      if (!preview?.canPublish) {
        ElMessage.error(firstPreviewErrorMessage(preview))
        return false
      }
      const unresolvedRepairIssues = Array.isArray(preview?.repairIssues)
        ? preview.repairIssues.filter(issue => issue?.repairable !== false)
        : []
      if (unresolvedRepairIssues.length) {
        const unresolvedFields = unresolvedRepairIssues
          .map(issue => issue?.field)
          .filter(Boolean)
          .slice(0, 3)
        const fieldTip = unresolvedFields.length
          ? `（${unresolvedFields.join(', ')}${unresolvedRepairIssues.length > 3 ? ' 等' : ''}）`
          : ''
        ElMessage.error(`元数据修复未完成，仍有 ${unresolvedRepairIssues.length} 项问题${fieldTip}，请先补全并保存后重试发布`)
        return false
      }
    } catch (error) {
      if (error === 'cancel') {
        // Continue publish without repair
      } else if (error === 'close') {
        return false
      } else {
        throw error
      }
    }
  }
  if (!preview?.requireConfirm) {
    return true
  }
  try {
    await ElMessageBox.confirm(
      h(WorkflowPublishPreviewDialog, { preview }),
      '发布变更确认',
      {
        type: 'warning',
        customClass: 'workflow-publish-message-box workflow-publish-message-box--preview',
        confirmButtonText: '确认发布',
        cancelButtonText: '取消'
      }
    )
    return true
  } catch (error) {
    if (isDialogCancel(error)) {
      return false
    }
    throw error
  }
}

const handleDeploy = async (row) => {
  if (!row?.id) return
  if (isDemoMode) {
    showDemoReadonlyMessage('发布工作流')
    return
  }
  setActionLoading(row.id, 'deploy', true)
  try {
    const canPublish = await previewPublishAndConfirm(row)
    if (!canPublish) {
      return
    }
    const record = await workflowApi.publish(row.id, {
      operation: 'deploy',
      requireApproval: false,
      operator: 'portal-ui',
      confirmDiff: true
    })
    updatePendingFlag(row.id, record?.status)
    if (record?.status === 'pending_approval') {
      ElMessage.warning('发布已提交审批，等待审批通过')
    } else {
      ElMessage.success('发布成功')
      await promptOnlineAfterDeploy(row, record)
    }
    refreshAfterAction(row.id)
  } catch (error) {
    console.error('发布失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'deploy', false)
  }
}

const handleExecute = async (row) => {
  if (!row?.id) return
  if (isDemoMode) {
    showDemoReadonlyMessage('执行工作流')
    return
  }
  if (row.status !== 'online') {
    ElMessage.warning('工作流未上线，请先上线后再执行')
    return
  }
  setActionLoading(row.id, 'execute', true)
  try {
    const executionId = await workflowApi.execute(row.id)
    ElMessage.success(`已触发执行，实例ID：${executionId || '-'}`)
    refreshAfterAction(row.id)
  } catch (error) {
    console.error('执行失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'execute', false)
  }
}

const openBackfill = (row) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('工作流补数')
    return
  }
  if (row?.status !== 'online') {
    ElMessage.warning('工作流未上线，请先上线后再补数')
    return
  }
  backfillTarget.value = row || null
  backfillDialogVisible.value = true
}

const handleBackfillSubmitted = () => {
  refreshAfterAction(backfillTarget.value?.id)
}

const handleOnline = async (row) => {
  if (!row?.id) return
  if (isDemoMode) {
    showDemoReadonlyMessage('上线工作流')
    return
  }
  if (!row?.workflowCode) {
    ElMessage.warning('工作流尚未发布，请先执行发布后再上线')
    return
  }
  setActionLoading(row.id, 'online', true)
  try {
    const onlineRecord = await workflowApi.publish(row.id, {
      operation: 'online',
      versionId: resolvePublishVersionId(row),
      requireApproval: false,
      operator: 'portal-ui'
    })
    updatePendingFlag(row.id, onlineRecord?.status)
    if (onlineRecord?.status === 'pending_approval') {
      ElMessage.warning('上线已提交审批，等待审批通过')
    } else {
      ElMessage.success('上线成功')
    }
    refreshAfterAction(row.id)
  } catch (error) {
    console.error('上线失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'online', false)
  }
}

const handleOffline = async (row) => {
  if (!row?.id) return
  if (isDemoMode) {
    showDemoReadonlyMessage('下线工作流')
    return
  }
  setActionLoading(row.id, 'offline', true)
  try {
    const record = await workflowApi.publish(row.id, {
      operation: 'offline',
      versionId: resolvePublishVersionId(row),
      requireApproval: false,
      operator: 'portal-ui'
    })
    updatePendingFlag(row.id, record?.status)
    if (record?.status === 'pending_approval') {
      ElMessage.warning('下线已提交审批')
    } else {
      ElMessage.success('下线成功')
    }
    refreshAfterAction(row.id)
  } catch (error) {
    console.error('下线失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'offline', false)
  }
}

const getWorkflowStatusType = (status) => {
  const map = {
    draft: 'info',
    online: 'success',
    offline: 'warning',
    failed: 'danger'
  }
  return map[status] || 'info'
}

const getWorkflowStatusText = (status) => {
  const map = {
    draft: '草稿',
    online: '在线',
    offline: '下线',
    failed: '失败'
  }
  return map[status] || status || '-'
}

const getPublishStateText = (row) => {
  if (!row) return '-'
  if (row.status === 'online') {
    return '已上线'
  }
  if (row.status === 'offline') {
    return '已下线'
  }
  const map = {
    published: '已发布',
    never: '未发布',
    failed: '发布失败'
  }
  return map[row.publishStatus] || '待发布'
}

const getPublishStateType = (row) => {
  if (!row) return 'info'
  if (row.status === 'online') {
    return 'success'
  }
  if (row.status === 'offline') {
    return 'warning'
  }
  const map = {
    published: 'success',
    never: 'info',
    failed: 'danger'
  }
  return map[row.publishStatus] || 'info'
}

const getInstanceStateType = (state) => {
  const map = {
    SUCCESS: 'success',
    FAILED: 'danger',
    RUNNING: 'warning',
    STOP: 'info',
    KILL: 'info'
  }
  return map[state] || 'info'
}

const getInstanceStateText = (state) => {
  const map = {
    SUCCESS: '成功',
    FAILED: '失败',
    RUNNING: '运行中',
    STOP: '终止',
    KILL: '被终止'
  }
  return map[state] || state || '-'
}

const getTriggerText = (type) => {
  const map = {
    manual: '手动',
    schedule: '调度',
    api: 'API'
  }
  return map[type] || type || '-'
}

const getOperationText = (operation) => {
  const map = {
    deploy: '部署',
    online: '上线',
    offline: '下线'
  }
  return map[operation] || operation || '-'
}

const getPublishRecordStatusType = (status) => {
  const map = {
    success: 'success',
    failed: 'danger',
    pending: 'info',
    pending_approval: 'warning',
    rejected: 'danger'
  }
  return map[status] || 'info'
}

const getPublishRecordStatusText = (status) => {
  const map = {
    success: '成功',
    failed: '失败',
    pending: '进行中',
    pending_approval: '待审批',
    rejected: '已拒绝'
  }
  return map[status] || status || '-'
}

const loadDolphinConfig = async () => {
  try {
    const config = await taskApi.getDolphinWebuiConfig()
    dolphinWebuiUrl.value = config?.webuiUrl || ''
  } catch (error) {
    console.warn('加载 Dolphin 配置失败', error)
  }
}

const resolveDolphinWebuiUrl = (workflow) => {
  const configId = workflow?.dolphinConfigId
  if (configId && dolphinWebuiUrlByConfigId[configId]) {
    return dolphinWebuiUrlByConfigId[configId]
  }
  return dolphinWebuiUrl.value
}

const prefetchDolphinWebuiUrls = async (rows) => {
  const ids = Array.from(new Set((rows || [])
    .map(row => row?.dolphinConfigId)
    .filter(Boolean)))
    .filter(id => !dolphinWebuiUrlByConfigId[id])
  if (!ids.length) {
    return
  }
  await Promise.all(ids.map(async (id) => {
    try {
      const config = await taskApi.getDolphinWebuiConfig({ dolphinConfigId: id })
      dolphinWebuiUrlByConfigId[id] = config?.webuiUrl || ''
    } catch (error) {
      console.warn(`加载 Dolphin WebUI 地址失败: ${id}`, error)
      dolphinWebuiUrlByConfigId[id] = ''
    }
  }))
}

onMounted(() => {
  loadDolphinConfig()
  loadWorkflows()
})
</script>

<style scoped>
.workflow-list {
  height: 100%;
  padding: 6px;
}

.workflow-list :deep(.el-card) {
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.workflow-list :deep(.el-card__body) {
  padding: 16px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.filter-item {
  width: 220px;
}

.workflow-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.workflow-name .name-link {
  font-size: 15px;
  cursor: pointer;
  color: #409eff;
}

.workflow-name .name-link:hover {
  text-decoration: underline;
}

.workflow-name .name-link.is-disabled {
  cursor: default;
  color: inherit;
}

.workflow-name .name-link.is-disabled:hover {
  text-decoration: none;
}

.workflow-meta {
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
  display: flex;
  gap: 12px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

</style>
