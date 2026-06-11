<template>
  <div class="task-table">
    <div class="toolbar" v-if="showToolbar">
      <div class="filters">
        <el-select v-model="filters.taskType" size="small" placeholder="任务类型" clearable style="width: 150px; margin-right: 10px">
          <el-option label="批任务" value="batch" />
          <el-option label="流任务" value="stream" />
        </el-select>
        <el-select v-model="filters.status" size="small" placeholder="状态" clearable style="width: 150px; margin-right: 10px">
          <el-option label="草稿" value="draft" />
          <el-option label="已发布" value="published" />
          <el-option label="运行中" value="running" />
        </el-select>
        <el-select
          v-if="!workflowId"
          v-model="filters.workflowId"
          size="small"
          placeholder="所属工作流"
          clearable
          filterable
          :loading="workflowOptionsLoading"
          style="width: 180px; margin-right: 10px"
        >
          <el-option
            v-for="workflow in workflowOptions"
            :key="workflow.id"
            :label="workflow.workflowName"
            :value="workflow.id"
          />
        </el-select>
        <el-input
          v-model.trim="filters.taskName"
          size="small"
          placeholder="任务名称"
          clearable
          style="width: 180px; margin-right: 10px"
        />
        <el-input
          v-model.trim="filters.upstreamTaskId"
          size="small"
          placeholder="上游任务ID"
          clearable
          style="width: 150px; margin-right: 10px"
        />
        <el-input
          v-model.trim="filters.downstreamTaskId"
          size="small"
          placeholder="下游任务ID"
          clearable
          style="width: 150px; margin-right: 10px"
        />
        <el-button type="primary" size="small" @click="handleFilter">查询</el-button>
        <el-button size="small" @click="resetFilters">重置</el-button>
      </div>
      <el-button type="primary" size="small" :disabled="isDemoMode" @click="openCreateDrawer">
        <el-icon><Plus /></el-icon>
        新建任务
      </el-button>
    </div>



    <el-table 
      :data="filteredTableData" 
      style="width: 100%" 
      :style="{ marginTop: (embedded || workflowId) ? '0' : '20px' }"
      v-loading="loading"
      :border="embedded"
      :size="embedded ? 'small' : 'default'"
    >
      <el-table-column prop="id" label="任务ID" width="90" />
      <el-table-column prop="taskName" label="任务名称" width="200" />
      <el-table-column prop="taskCode" label="任务编码" width="150" />
      <el-table-column prop="taskType" label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="row.taskType === 'batch' ? 'primary' : 'success'">
            {{ row.taskType === 'batch' ? '批任务' : '流任务' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="engine" label="引擎" width="120" />
      <el-table-column prop="dolphinNodeType" label="节点类型" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.dolphinNodeType" :type="getNodeTypeColor(row.dolphinNodeType)">
            {{ row.dolphinNodeType }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="数据源" width="150">
        <template #default="{ row }">
          <span v-if="row.datasourceName">
            {{ row.datasourceName }}
            <el-tag size="small" v-if="row.datasourceType">{{ row.datasourceType }}</el-tag>
          </span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="最近执行" width="200">
        <template #default="{ row }">
          <div v-if="row.executionStatus" class="execution-status">
            <div>
              <el-tag :type="getExecutionStatusType(row.executionStatus.status)" size="small">
                {{ getExecutionStatusText(row.executionStatus.status) }}
              </el-tag>
            </div>
            <div class="execution-time" v-if="row.executionStatus.startTime">
              {{ formatTime(row.executionStatus.startTime) }}
            </div>
          </div>
          <span v-else class="text-gray">未执行</span>
        </template>
      </el-table-column>
      <el-table-column prop="scheduleCron" label="调度配置" width="150" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="getStatusType(row.status)">{{ getStatusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="workflowName" label="所属工作流" width="180">
        <template #default="{ row }">
          <span v-if="row.workflowName">{{ row.workflowName }}</span>
          <span v-else class="text-gray">未关联</span>
        </template>
      </el-table-column>
      <el-table-column label="上游任务数" width="120">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" v-if="row.upstreamTaskCount !== undefined && row.upstreamTaskCount !== null">
            {{ row.upstreamTaskCount }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="下游任务数" width="120">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" v-if="row.downstreamTaskCount !== undefined && row.downstreamTaskCount !== null">
            {{ row.downstreamTaskCount }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="owner" label="负责人" width="120" />
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="isDemoMode" @click="openEditDrawer(row)">编辑</el-button>
          <el-button link type="info" @click="openDolphinTask(row)" v-if="row.executionStatus && row.executionStatus.dolphinTaskUrl">
            查看任务
          </el-button>
          <el-button 
            v-if="showRemoveAction" 
            link 
            type="warning"
            @click="handleRemove(row.id)"
          >
            移除
          </el-button>
          <el-popconfirm v-if="!hideDeleteAction" title="确定删除吗?" @confirm="handleDelete(row.id)">
            <template #reference>
              <el-button link type="danger" :disabled="isDemoMode">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="pagination.pageNum"
      v-model:page-size="pagination.pageSize"
      :total="pagination.total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      @size-change="loadData"
      @current-change="loadData"
      style="margin-top: 20px; justify-content: flex-end"
    />

    <TaskEditDrawer ref="drawerRef" :dolphin-config-id="dolphinConfigId" @success="handleDrawerSuccess" />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { taskApi } from '@/api/task'
import { workflowApi } from '@/api/workflow'
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'
import TaskEditDrawer from './TaskEditDrawer.vue'

const props = defineProps({
  workflowId: {
    type: Number,
    default: null
  },
  nodeType: {
    type: String,
    default: null
  },
  showToolbar: {
    type: Boolean,
    default: true
  },
  embedded: {
    type: Boolean,
    default: false
  },
  externalData: {
    type: Array,
    default: null
  },
  additionalData: {
    type: Array,
    default: () => []
  },
  showRemoveAction: {
    type: Boolean,
    default: false
  },
  hideDeleteAction: {
    type: Boolean,
    default: false
  },
  dolphinConfigId: {
    type: Number,
    default: null
  }
})

const emit = defineEmits(['remove'])

const loading = ref(false)
const tableData = ref([])
const pagination = reactive({
  pageNum: 1,
  pageSize: 20,
  total: 0
})

const drawerRef = ref(null)

const filters = reactive({
  taskType: '',
  status: '',
  workflowId: null,
  taskName: '',
  upstreamTaskId: '',
  downstreamTaskId: ''
})

const dolphinWebuiUrl = ref('')
const workflowOptions = ref([])
const workflowOptionsLoading = ref(false)

// Computed filtered data for local filtering (when using externalData)
const filteredTableData = computed(() => {
  // If using full external data mode, apply local filters
  if (props.externalData !== null) {
    let data = tableData.value
    
    // Filter by task type
    if (filters.taskType) {
      data = data.filter(task => task.taskType === filters.taskType)
    }
    // Filter by status
    if (filters.status) {
      data = data.filter(task => task.status === filters.status)
    }
    // Filter by task name (partial match)
    if (filters.taskName) {
      const searchName = filters.taskName.toLowerCase()
      data = data.filter(task => task.taskName?.toLowerCase().includes(searchName))
    }
    
    return data
  }
  
  // Normal mode: prepend additionalData to tableData
  if (props.additionalData.length > 0) {
    // Mark additional data rows and prepend to API data
    const additionalWithFlag = props.additionalData.map(item => ({
      ...item,
      _isNew: true
    }))
    return [...additionalWithFlag, ...tableData.value]
  }
  
  return tableData.value
})

const loadData = async () => {
  // If external data is provided, use it directly
  if (props.externalData !== null) {
    tableData.value = props.externalData
    pagination.total = props.externalData.length
    return
  }
  
  loading.value = true
  try {
    const res = await taskApi.list({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      ...buildListFilters()
    })
    tableData.value = res.records
    pagination.total = res.total

  } catch (error) {
    console.error('加载数据失败:', error)
  } finally {
    loading.value = false
  }
}

const buildListFilters = () => {
  const params = {}
  if (filters.taskType) {
    params.taskType = filters.taskType
  }
  if (filters.status) {
    params.status = filters.status
  }
  if (filters.taskName) {
    params.taskName = filters.taskName
  }
  const workflowId = props.workflowId || parseNumericFilter(filters.workflowId)
  if (workflowId) {
    params.workflowId = workflowId
  }
  const upstreamId = parseNumericFilter(filters.upstreamTaskId)
  if (upstreamId) {
    params.upstreamTaskId = upstreamId
  }
  const downstreamId = parseNumericFilter(filters.downstreamTaskId)
  if (downstreamId) {
    params.downstreamTaskId = downstreamId
  }
  if (props.nodeType) {
    params.dolphinNodeType = props.nodeType
  }
  return params
}

const parseNumericFilter = (value) => {
  if (value === null || value === undefined || value === '') {
    return null
  }
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}

const loadDolphinConfig = async () => {
  try {
    const config = await taskApi.getDolphinWebuiConfig(buildDolphinConfigParams())
    if (config?.webuiUrl) {
      dolphinWebuiUrl.value = config.webuiUrl.replace(/\/+$/, '')
    } else {
      dolphinWebuiUrl.value = ''
    }
  } catch (error) {
    console.error('加载 DolphinScheduler 配置失败:', error)
    dolphinWebuiUrl.value = ''
  }
}

const buildDolphinConfigParams = () => {
  return props.dolphinConfigId
    ? { dolphinConfigId: props.dolphinConfigId }
    : {}
}

const loadWorkflowOptions = async () => {
  workflowOptionsLoading.value = true
  try {
    const res = await workflowApi.list({
      pageNum: 1,
      pageSize: 200
    })
    workflowOptions.value = res.records || []
  } catch (error) {
    console.error('加载工作流选项失败:', error)
  } finally {
    workflowOptionsLoading.value = false
  }
}

const handleFilter = () => {
  pagination.pageNum = 1
  loadData()
}

const resetFilters = () => {
  filters.taskType = ''
  filters.status = ''
  filters.taskName = ''
  filters.workflowId = null
  filters.upstreamTaskId = ''
  filters.downstreamTaskId = ''
  handleFilter()
}

const openCreateDrawer = () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('新建任务')
    return
  }
  const initialData = {}
  const workflowId = props.workflowId || parseNumericFilter(filters.workflowId)
  if (workflowId) {
    initialData.workflowId = workflowId
  }
  if (props.dolphinConfigId) {
    initialData.dolphinConfigId = props.dolphinConfigId
  }
  drawerRef.value?.open(null, initialData)
}

const openEditDrawer = (row) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('编辑任务')
    return
  }
  drawerRef.value?.open(row.id, props.dolphinConfigId ? { dolphinConfigId: props.dolphinConfigId } : {})
}

const handleDrawerSuccess = () => {
  loadData()
}

const handleDelete = async (id) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('删除任务')
    return
  }
  try {
    await taskApi.delete(id)
    ElMessage.success('删除成功')
    loadData()
  } catch (error) {
    console.error('删除失败:', error)
    const errorMessage = error.response?.data?.message || error.message || '删除失败，请稍后重试'
    ElMessage.error({
      message: errorMessage,
      duration: 6000,
      showClose: true
    })
  }
}

const handleRemove = (taskId) => {
  emit('remove', taskId)
}

const getStatusType = (status) => {
  const types = {
    draft: 'info',
    published: 'success',
    running: 'warning',
    paused: 'info',
    failed: 'danger'
  }
  return types[status] || 'info'
}

const getNodeTypeColor = (nodeType) => {
  const colors = {
    'SQL': 'success',
    'SHELL': 'warning',
    'PYTHON': 'primary'
  }
  return colors[nodeType] || 'info'
}

const getStatusText = (status) => {
  const texts = {
    draft: '草稿',
    published: '已发布',
    running: '运行中',
    paused: '已暂停',
    failed: '失败'
  }
  return texts[status] || status
}

const getExecutionStatusType = (status) => {
  const types = {
    pending: 'info',
    running: 'warning',
    success: 'success',
    failed: 'danger',
    killed: 'info'
  }
  return types[status] || 'info'
}

const getExecutionStatusText = (status) => {
  const texts = {
    pending: '等待中',
    running: '运行中',
    success: '成功',
    failed: '失败',
    killed: '已终止'
  }
  return texts[status] || status
}

const formatTime = (timeStr) => {
  if (!timeStr) return '-'
  const date = new Date(timeStr)
  const now = new Date()
  const diff = now - date
  const hours = Math.floor(diff / (1000 * 60 * 60))
  const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60))

  if (hours < 1) {
    return `${minutes}分钟前`
  } else if (hours < 24) {
    return `${hours}小时前`
  } else {
    return date.toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  }
}

const openDolphinTask = (row) => {
  if (row.executionStatus && row.executionStatus.dolphinTaskUrl) {
    window.open(row.executionStatus.dolphinTaskUrl, '_blank')
    return
  }
  if (dolphinWebuiUrl.value) {
    window.open(dolphinWebuiUrl.value, '_blank')
  }
}

onMounted(async () => {
  await Promise.all([loadDolphinConfig(), loadWorkflowOptions()])
  loadData()
})

watch(() => props.workflowId, () => {
  loadData()
})

watch(() => props.nodeType, () => {
  loadData()
})

watch(() => props.dolphinConfigId, () => {
  loadDolphinConfig()
})

watch(() => props.externalData, (newData) => {
  if (newData !== null) {
    tableData.value = newData
    pagination.total = newData.length
  }
}, { deep: true })

// Expose refresh method for parent component
defineExpose({
  refresh: loadData
})
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.filters {
  display: flex;
  align-items: center;
}

.execution-status {
  font-size: 12px;
  line-height: 1.5;
}

.execution-time {
  color: #909399;
  margin-top: 4px;
}

.text-gray {
  color: #909399;
}


</style>
