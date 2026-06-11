<template>
  <div class="schema-backup-manager">
    <div class="toolbar">
      <div class="left">
        <span class="title">Schema 备份管理</span>
        <span class="desc">基于 Doris Repository + MinIO，支持手动备份与每日定时备份</span>
      </div>
      <el-button size="small" @click="loadSchemaBackups" :loading="loading">刷新</el-button>
    </div>

    <el-alert
      v-if="cluster?.sourceType && String(cluster.sourceType).toUpperCase() !== 'DORIS'"
      type="warning"
      :closable="false"
      show-icon
      title="当前数据源不是 DORIS，暂不支持 schema 快照备份"
      style="margin-bottom: 12px"
    />

    <el-table
      v-loading="loading"
      :data="schemaBackups"
      border
      size="small"
      style="width: 100%"
      :empty-text="cluster?.sourceType === 'DORIS' ? '暂无 schema 或请刷新后重试' : '仅 DORIS 数据源支持备份管理'"
    >
      <el-table-column prop="schemaName" label="Schema" min-width="180" />

      <el-table-column label="备份配置" min-width="130">
        <template #default="{ row }">
          <el-tag :type="row.hasConfig === 1 ? 'success' : 'info'" size="small">
            {{ row.hasConfig === 1 ? '已配置' : '未配置' }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="Repository" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.repositoryName || '-' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="MinIO 环境" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <el-tag v-if="row.minioConfigName" type="success" size="small">{{ row.minioConfigName }}</el-tag>
          <el-tag v-else type="info" size="small">未关联</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="MinIO 路径" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ formatBucketPath(row) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="定时备份" min-width="140">
        <template #default="{ row }">
          <el-tag :type="row.backupEnabled === 1 ? 'success' : 'info'" size="small">
            {{ row.backupEnabled === 1 ? `每天 ${row.backupTime || '--:--'}` : '关闭' }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="最近备份" min-width="170">
        <template #default="{ row }">
          <span>{{ formatDateTime(row.lastBackupTime) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="380" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openConfigDialog(row)">
            {{ row.hasConfig === 1 ? '编辑配置' : '配置备份' }}
          </el-button>
          <el-button link type="success" :disabled="row.hasConfig !== 1" @click="handleTriggerBackup(row)">开始备份</el-button>
          <el-button link type="primary" :disabled="row.hasConfig !== 1" @click="openSnapshotDialog(row)">查看快照</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="configDialogVisible"
      :title="`备份配置 - ${activeSchema?.schemaName || ''}`"
      width="640px"
      :close-on-click-modal="false"
    >
      <el-form ref="configFormRef" :model="configForm" :rules="configRules" label-width="130px">
        <el-form-item label="Repository 名称" prop="repositoryName">
          <el-input v-model="configForm.repositoryName" placeholder="例如：repo_1_ods" />
        </el-form-item>

        <el-form-item label="MinIO 环境" prop="minioConfigId">
          <el-select v-model="configForm.minioConfigId" placeholder="请选择 MinIO 环境" :loading="minioLoading">
            <el-option v-for="item in minioOptions" :key="item.id" :label="item.configName" :value="item.id" />
          </el-select>
          <div class="tip">在“设置 / 配置管理 / MinIO 环境”中维护</div>
        </el-form-item>

        <el-form-item label="Bucket" prop="minioBucket">
          <el-input v-model="configForm.minioBucket" placeholder="Bucket 需提前在 MinIO 创建" />
        </el-form-item>

        <el-form-item label="Bucket 子路径" prop="minioBasePath">
          <el-input v-model="configForm.minioBasePath" placeholder="例如：backup/ods_schema" />
          <div class="tip">系统不会管理生命周期，仅写入该 bucket 路径</div>
        </el-form-item>

        <el-form-item label="每日定时备份" prop="backupEnabled">
          <el-switch v-model="configForm.backupEnabled" :active-value="1" :inactive-value="0" />
        </el-form-item>

        <el-form-item label="备份时间" prop="backupTime">
          <el-time-picker
            v-model="configForm.backupTime"
            value-format="HH:mm"
            format="HH:mm"
            placeholder="选择时间"
            :disabled="configForm.backupEnabled !== 1"
          />
          <div class="tip">按本地时区每天触发一次</div>
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-select v-model="configForm.status">
            <el-option label="启用" value="active" />
            <el-option label="停用" value="inactive" />
          </el-select>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="configDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="configSaving" @click="saveConfig">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="snapshotDialogVisible"
      :title="`快照列表 - ${activeSchema?.schemaName || ''}`"
      width="72%"
      :close-on-click-modal="false"
    >
      <div class="snapshot-toolbar">
        <el-button size="small" @click="loadSnapshots" :loading="snapshotLoading">刷新快照</el-button>
      </div>
      <el-table v-loading="snapshotLoading" :data="snapshots" border size="small" style="width: 100%" empty-text="暂无快照">
        <el-table-column prop="snapshotName" label="Snapshot" min-width="220" show-overflow-tooltip />
        <el-table-column prop="backupTimestamp" label="Backup Timestamp" min-width="180" />
        <el-table-column prop="status" label="状态" min-width="120" />
        <el-table-column prop="databaseName" label="Schema" min-width="140" />
        <el-table-column prop="details" label="详情" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="warning" :disabled="!row.backupTimestamp" @click="openRestoreDialog(row)">恢复</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="snapshotDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="restoreDialogVisible"
      :title="`恢复快照 - ${activeSnapshot?.snapshotName || ''}`"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="恢复会覆盖线上数据，请务必确认恢复范围与目标对象"
        style="margin-bottom: 14px"
      />

      <el-form :model="restoreForm" label-width="100px">
        <el-form-item label="恢复范围">
          <el-radio-group v-model="restoreForm.scope">
            <el-radio label="schema">整个 schema</el-radio>
            <el-radio label="table">指定表</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="表名" v-if="restoreForm.scope === 'table'">
          <el-input v-model="restoreForm.tableName" placeholder="请输入要恢复的表名" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="restoreDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="restoreLoading" @click="submitRestore">确认恢复</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { dorisClusterApi } from '@/api/doris'
import { settingsApi } from '@/api/settings'

const props = defineProps({
  cluster: {
    type: Object,
    required: true
  }
})

const loading = ref(false)
const schemaBackups = ref([])

const configDialogVisible = ref(false)
const configSaving = ref(false)
const activeSchema = ref(null)
const configFormRef = ref(null)
const minioLoading = ref(false)
const minioOptions = ref([])
const configForm = reactive({
  repositoryName: '',
  minioConfigId: null,
  minioBucket: '',
  minioBasePath: '',
  backupEnabled: 0,
  backupTime: '02:00',
  status: 'active'
})

const configRules = {
  repositoryName: [{ required: true, message: '请输入 repository 名称', trigger: 'blur' }],
  minioConfigId: [{ required: true, message: '请选择 MinIO 环境', trigger: 'change' }],
  minioBucket: [{ required: true, message: '请输入 MinIO Bucket', trigger: 'blur' }],
  minioBasePath: [{ required: true, message: '请输入 Bucket 子路径', trigger: 'blur' }],
  backupTime: [
    {
      validator: (_, value, callback) => {
        if (configForm.backupEnabled === 1 && !value) {
          callback(new Error('开启定时备份时必须设置时间'))
          return
        }
        callback()
      },
      trigger: 'change'
    }
  ]
}

const snapshotDialogVisible = ref(false)
const snapshotLoading = ref(false)
const snapshots = ref([])
const activeSnapshot = ref(null)

const restoreDialogVisible = ref(false)
const restoreLoading = ref(false)
const restoreForm = reactive({
  scope: 'schema',
  tableName: ''
})

const formatDateTime = value => {
  if (!value) return '-'
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss')
}

const formatBucketPath = row => {
  if (!row?.minioBucket || !row?.minioBasePath) return '-'
  return `s3://${row.minioBucket}/${row.minioBasePath}`
}

const loadSchemaBackups = async () => {
  if (!props.cluster?.id) return
  if (String(props.cluster?.sourceType || '').toUpperCase() !== 'DORIS') {
    schemaBackups.value = []
    return
  }
  loading.value = true
  try {
    const list = await dorisClusterApi.listSchemaBackups(props.cluster.id)
    schemaBackups.value = Array.isArray(list) ? list : []
  } catch (error) {
    console.error('加载 schema 备份配置失败:', error)
    ElMessage.error('加载 schema 备份配置失败: ' + (error.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

const loadMinioConfigs = async () => {
  minioLoading.value = true
  try {
    const list = await settingsApi.listMinioConfigs({ status: 'active' })
    minioOptions.value = Array.isArray(list) ? list : []
  } catch (error) {
    console.error('加载 MinIO 环境失败:', error)
    minioOptions.value = []
  } finally {
    minioLoading.value = false
  }
}

const resetConfigForm = () => {
  configForm.repositoryName = ''
  configForm.minioConfigId = null
  configForm.minioBucket = ''
  configForm.minioBasePath = ''
  configForm.backupEnabled = 0
  configForm.backupTime = '02:00'
  configForm.status = 'active'
}

const openConfigDialog = row => {
  activeSchema.value = row
  resetConfigForm()
  configForm.repositoryName = row.repositoryName || `repo_${props.cluster.id}_${row.schemaName}`
  configForm.minioConfigId = row.minioConfigId || null
  configForm.minioBucket = row.minioBucket || ''
  configForm.minioBasePath = row.minioBasePath || ''
  configForm.backupEnabled = row.backupEnabled === 1 ? 1 : 0
  configForm.backupTime = row.backupTime || '02:00'
  configForm.status = row.status || 'active'
  configDialogVisible.value = true
}

const saveConfig = async () => {
  if (!activeSchema.value?.schemaName) return

  if (!configFormRef.value) return
  try {
    await configFormRef.value.validate()
  } catch {
    return
  }

  configSaving.value = true
  try {
    await dorisClusterApi.saveSchemaBackup(props.cluster.id, activeSchema.value.schemaName, {
      repositoryName: configForm.repositoryName,
      minioConfigId: configForm.minioConfigId,
      minioBucket: configForm.minioBucket,
      minioBasePath: configForm.minioBasePath,
      backupEnabled: configForm.backupEnabled,
      backupTime: configForm.backupTime,
      status: configForm.status
    })
    ElMessage.success('备份配置已保存')
    configDialogVisible.value = false
    await loadSchemaBackups()
  } catch (error) {
    console.error('保存备份配置失败:', error)
    ElMessage.error('保存备份配置失败: ' + (error.message || '未知错误'))
  } finally {
    configSaving.value = false
  }
}

const handleTriggerBackup = async row => {
  if (row.hasConfig !== 1) {
    ElMessage.warning('请先配置该 schema 的备份参数')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认立即备份 schema「${row.schemaName}」吗？系统会创建/复用 Doris repository 并触发 BACKUP SNAPSHOT。`,
      '开始备份',
      {
        type: 'warning',
        confirmButtonText: '确认备份',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  try {
    const res = await dorisClusterApi.triggerSchemaBackup(props.cluster.id, row.schemaName)
    ElMessage.success(`备份任务已提交，快照: ${res?.snapshotName || '-'}`)
    await loadSchemaBackups()
    if (activeSchema.value?.schemaName === row.schemaName && snapshotDialogVisible.value) {
      await loadSnapshots()
    }
  } catch (error) {
    console.error('触发备份失败:', error)
    ElMessage.error('触发备份失败: ' + (error.message || '未知错误'))
  }
}

const openSnapshotDialog = row => {
  activeSchema.value = row
  snapshots.value = []
  snapshotDialogVisible.value = true
  loadSnapshots()
}

const loadSnapshots = async () => {
  if (!activeSchema.value?.schemaName) return
  snapshotLoading.value = true
  try {
    const list = await dorisClusterApi.listSchemaSnapshots(props.cluster.id, activeSchema.value.schemaName)
    snapshots.value = Array.isArray(list) ? list : []
  } catch (error) {
    console.error('加载快照失败:', error)
    ElMessage.error('加载快照失败: ' + (error.message || '未知错误'))
  } finally {
    snapshotLoading.value = false
  }
}

const openRestoreDialog = snapshot => {
  activeSnapshot.value = snapshot
  restoreForm.scope = 'schema'
  restoreForm.tableName = ''
  restoreDialogVisible.value = true
}

const submitRestore = async () => {
  if (!activeSchema.value?.schemaName || !activeSnapshot.value?.snapshotName) return
  if (restoreForm.scope === 'table' && !restoreForm.tableName) {
    ElMessage.warning('请输入要恢复的表名')
    return
  }

  const scopeText = restoreForm.scope === 'table'
    ? `表 ${restoreForm.tableName}`
    : `整个 schema ${activeSchema.value.schemaName}`

  try {
    await ElMessageBox.confirm(
      `二次确认：你将从快照 ${activeSnapshot.value.snapshotName} 恢复 ${scopeText}。该操作可能覆盖现有数据，是否继续？`,
      '恢复确认',
      {
        type: 'warning',
        confirmButtonText: '确认恢复',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  restoreLoading.value = true
  try {
    await dorisClusterApi.restoreSchemaSnapshot(props.cluster.id, activeSchema.value.schemaName, {
      snapshotName: activeSnapshot.value.snapshotName,
      backupTimestamp: activeSnapshot.value.backupTimestamp,
      tableName: restoreForm.scope === 'table' ? restoreForm.tableName : null
    })
    ElMessage.success('恢复任务已提交，请在 Doris 中跟踪恢复状态')
    restoreDialogVisible.value = false
  } catch (error) {
    console.error('恢复失败:', error)
    ElMessage.error('恢复失败: ' + (error.message || '未知错误'))
  } finally {
    restoreLoading.value = false
  }
}

watch(
  () => props.cluster?.id,
  () => {
    loadMinioConfigs()
    loadSchemaBackups()
  },
  { immediate: true }
)
</script>

<style scoped>
.schema-backup-manager {
  padding: 8px 0;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.left {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.title {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}

.desc {
  font-size: 12px;
  color: #64748b;
}

.tip {
  margin-top: 4px;
  font-size: 12px;
  color: #94a3b8;
}

.snapshot-toolbar {
  margin-bottom: 10px;
  display: flex;
  justify-content: flex-end;
}
</style>
