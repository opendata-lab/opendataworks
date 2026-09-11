<template>
  <div class="eval-sets">
    <div class="eval-sets__toolbar">
      <div>
        <div class="eval-sets__title">评测集</div>
        <div class="eval-sets__subtitle">共 {{ datasets.length }} 个评测集</div>
      </div>
      <div class="eval-sets__actions">
        <el-input
          v-model="searchKeyword"
          clearable
          placeholder="搜索名称或类别"
          class="eval-sets__search"
        />
        <el-upload
          accept=".jsonl,.json"
          :show-file-list="false"
          :disabled="importLoading"
          :before-upload="beforeUpload"
          :http-request="handleImport"
        >
          <el-button type="primary" :loading="importLoading">导入 JSONL</el-button>
        </el-upload>
        <el-button type="primary" @click="openCreateDialog">新建评测集</el-button>
      </div>
    </div>

    <el-table
      v-if="listLoading || filteredDatasets.length"
      v-loading="listLoading"
      :data="filteredDatasets"
      stripe
      class="eval-sets__table"
    >
      <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" @click="openDetail(row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="类别" width="120" show-overflow-tooltip />
      <el-table-column prop="case_count" label="用例数" width="90" align="center" />
      <el-table-column prop="dataset_hash" label="Hash" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="eval-sets__hash">{{ row.dataset_hash ? row.dataset_hash.slice(0, 12) + '…' : '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="标签" min-width="160">
        <template #default="{ row }">
          <el-tag
            v-for="tag in parseTags(row.suite_tags)"
            :key="tag"
            size="small"
            effect="plain"
            class="eval-sets__tag"
          >
            {{ tag }}
          </el-tag>
          <span v-if="!parseTags(row.suite_tags).length">-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">
            {{ row.status === 'active' ? '活跃' : '归档' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatTime(row.updated_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button text type="primary" @click="openDetail(row)">详情</el-button>
          <el-button text type="primary" :loading="exportingId === row.dataset_id" @click="handleExport(row)">导出</el-button>
          <el-button text type="primary" @click="openEditDialog(row)">编辑</el-button>
          <el-button text type="danger" @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- One empty state, not two: el-table renders its own "No Data" whenever
         it has no rows, so it only appears when there is something to show. -->
    <el-empty
      v-else
      :description="searchKeyword ? `没有匹配「${searchKeyword}」的评测集` : '还没有评测集'"
      :image-size="120"
    >
      <template v-if="!searchKeyword" #default>
        <p class="eval-sets__empty-hint">导入一份 JSONL，或新建一个评测集后在这里管理它的用例。</p>
        <el-button type="primary" @click="openCreateDialog">新建评测集</el-button>
      </template>
    </el-empty>

    <el-dialog
      v-model="dialogVisible"
      :title="editingDataset ? '编辑评测集' : '新建评测集'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form :model="dialogForm" label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="dialogForm.name" placeholder="评测集名称" maxlength="120" />
        </el-form-item>
        <el-form-item label="类别">
          <el-input v-model="dialogForm.category" placeholder="如：business, smoke" maxlength="60" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="dialogForm.description" type="textarea" :rows="3" placeholder="评测集描述" maxlength="500" />
        </el-form-item>
        <el-form-item label="标签">
          <el-select
            v-model="dialogForm.suite_tags"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入标签后回车"
          />
        </el-form-item>
        <el-form-item v-if="editingDataset" label="状态">
          <el-select v-model="dialogForm.status">
            <el-option label="活跃" value="active" />
            <el-option label="归档" value="archived" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialogLoading" @click="submitDialog">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { dataagentApi } from '@/api/dataagent'
import { withAgentContext } from '@/router/agentContext'

const route = useRoute()
const router = useRouter()

const listLoading = ref(false)
const importLoading = ref(false)
const exportingId = ref('')
const dialogVisible = ref(false)
const dialogLoading = ref(false)
const searchKeyword = ref('')
const datasets = ref([])
const editingDataset = ref(null)

const dialogForm = ref({ name: '', description: '', category: '', suite_tags: [], status: 'active' })

const parseTags = (tags) => {
  if (Array.isArray(tags)) return tags
  if (typeof tags === 'string') {
    try { return JSON.parse(tags) } catch { return [] }
  }
  return []
}

const filteredDatasets = computed(() => {
  const kw = String(searchKeyword.value || '').trim().toLowerCase()
  if (!kw) return datasets.value
  return datasets.value.filter((ds) =>
    String(ds.name || '').toLowerCase().includes(kw) ||
    String(ds.category || '').toLowerCase().includes(kw)
  )
})

const formatTime = (val) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-'

const notifyError = (error, fallback) => {
  if (!error?.__odwNotified) ElMessage.error(error?.message || fallback)
}

const loadDatasets = async () => {
  listLoading.value = true
  try {
    datasets.value = await dataagentApi.listEvalDatasets()
  } catch (e) {
    datasets.value = []
    notifyError(e, '加载评测集失败')
  } finally {
    listLoading.value = false
  }
}

const openDetail = (ds) => {
  router.push(withAgentContext({
    name: 'EvaluationSetDetail',
    params: { datasetId: ds.dataset_id }
  }, route.query))
}

const openCreateDialog = () => {
  editingDataset.value = null
  dialogForm.value = { name: '', description: '', category: '', suite_tags: [], status: 'active' }
  dialogVisible.value = true
}

const openEditDialog = (ds) => {
  editingDataset.value = ds
  dialogForm.value = {
    name: ds.name || '',
    description: ds.description || '',
    category: ds.category || '',
    suite_tags: parseTags(ds.suite_tags),
    status: ds.status || 'active'
  }
  dialogVisible.value = true
}

const submitDialog = async () => {
  if (!String(dialogForm.value.name).trim()) {
    ElMessage.warning('请输入评测集名称')
    return
  }
  dialogLoading.value = true
  try {
    if (editingDataset.value) {
      await dataagentApi.updateEvalDataset(editingDataset.value.dataset_id, dialogForm.value)
      ElMessage.success('评测集已更新')
    } else {
      await dataagentApi.createEvalDataset(dialogForm.value)
      ElMessage.success('评测集已创建')
    }
    dialogVisible.value = false
    await loadDatasets()
  } catch (e) {
    notifyError(e, '操作失败')
  } finally {
    dialogLoading.value = false
  }
}

const confirmDelete = async (ds) => {
  try {
    await ElMessageBox.confirm(
      `确认删除评测集「${ds.name}」？此操作不可恢复。`,
      '删除评测集',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch { return }
  try {
    await dataagentApi.deleteEvalDataset(ds.dataset_id)
    ElMessage.success('评测集已删除')
    await loadDatasets()
  } catch (e) {
    notifyError(e, '删除失败')
  }
}

const beforeUpload = (file) => {
  const name = String(file?.name || '').toLowerCase()
  if (!name.endsWith('.jsonl') && !name.endsWith('.json')) {
    ElMessage.error('请上传 JSONL 格式文件')
    return false
  }
  return true
}

const handleImport = async ({ file }) => {
  importLoading.value = true
  try {
    const result = await dataagentApi.importEvalDataset(file)
    ElMessage.success(`导入成功：${result.name}，共 ${result.case_count} 个用例`)
    await loadDatasets()
  } catch (e) {
    notifyError(e, '导入失败')
  } finally {
    importLoading.value = false
  }
}

const handleExport = async (ds) => {
  if (exportingId.value) return
  exportingId.value = ds.dataset_id
  try {
    const blob = await dataagentApi.exportEvalDataset(ds.dataset_id)
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${ds.name || ds.dataset_id}.jsonl`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e) {
    notifyError(e, '导出失败')
  } finally {
    exportingId.value = ''
  }
}

onMounted(() => { loadDatasets() })
</script>

<style scoped>
.eval-sets__empty-hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: #64748b;
}

.eval-sets {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.eval-sets__toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.eval-sets__title {
  font-size: 18px;
  font-weight: 600;
  color: #0f172a;
}

.eval-sets__subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: #64748b;
}

.eval-sets__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.eval-sets__search {
  width: 220px;
}

.eval-sets__table {
  min-width: 0;
}

.eval-sets__hash {
  font-family: monospace;
  font-size: 12px;
  color: #64748b;
}

.eval-sets__tag {
  margin-right: 4px;
  margin-bottom: 2px;
}

@media (max-width: 768px) {
  .eval-sets__toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .eval-sets__actions {
    flex-direction: column;
    align-items: stretch;
  }

  .eval-sets__search {
    width: 100%;
  }
}
</style>
