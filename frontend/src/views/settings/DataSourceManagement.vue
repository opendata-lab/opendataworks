<template>
  <div class="datasource-page">
    <div class="source-heading">
        <div class="card-header">
          <div>
            <span class="sub-title">统一配置数据源，维护平台连接、数据权限配置与结构备份</span>
          </div>
          <div v-if="authStore.isAdmin" class="actions">
            <el-button type="primary" :disabled="!typeDefinitions.length" @click="openCreate">
              <el-icon><Plus /></el-icon>
              新增数据源
            </el-button>
          </div>
        </div>
    </div>

      <el-alert v-if="typeError" :title="typeError" type="error" :closable="false" />
      <el-table v-loading="loading" :data="clusters" border style="width: 100%">
        <el-table-column type="expand" width="42">
          <template #default="{ row }">
            <SchemaBackupManager v-if="!capabilityReason(row.typeDefinition, 'backup')" :cluster="row" />
            <el-text v-else type="info">{{ capabilityReason(row.typeDefinition, 'backup') }}</el-text>
          </template>
        </el-table-column>
        <el-table-column prop="clusterName" label="数据源名称" min-width="160" />
        <el-table-column label="类型" min-width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="getSourceTypeTag(row.sourceType)">
              {{ getSourceTypeLabel(row.sourceType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="连接信息" min-width="220">
          <template #default="{ row }">
            <div class="host-info">
              <el-tag size="small" type="info">{{ row.feHost }}</el-tag>
              <span class="divider">:</span>
              <span>{{ row.fePort }}</span>
              <span v-if="row.databaseName">/ {{ row.databaseName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户名" min-width="100" />
        <el-table-column label="状态" min-width="120">
          <template #default="{ row }">
            <el-tag type="success" v-if="row.status === 'active'">启用</el-tag>
            <el-tag type="info" v-else>停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="默认" min-width="90">
          <template #default="{ row }">
            <el-tag type="warning" v-if="row.isDefault === 1">默认</el-tag>
            <el-tag type="info" v-else>否</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="最近更新" min-width="180">
          <template #default="{ row }">
            <span>{{ formatDateTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="authStore.isAdmin" label="操作" fixed="right" width="430">
          <template #default="{ row }">
            <el-button type="primary" text size="small" @click="openEdit(row.id)">编辑</el-button>
            <el-button type="success" text size="small" :disabled="row.typeDefinition?.driverStatus !== 'AVAILABLE'" :title="row.typeDefinition?.driverStatus !== 'AVAILABLE' ? '所需 JDBC 驱动不可用' : ''" @click="handleTestConnection(row.id)">测试连接</el-button>
            <el-button type="warning" text size="small" :disabled="row.isDefault === 1" @click="handleSetDefault(row.id)"
              >设为默认</el-button
            >
            <el-button text size="small" data-testid="open-permissions-btn" @click="openPermissions(row)">权限配置</el-button>
            <el-button v-if="authStore.isAdmin" type="info" text size="small" @click="router?.push({ path: '/metadata-sync', query: { sourceId: row.id } })">元数据采集</el-button>
            <el-button type="danger" text size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>


    <DatasourcePermissionCenter
      v-if="authStore.isAdmin"
      v-model="permissionVisible"
      :cluster="permissionCluster"
      :clusters="clusters"
      @source-change="handlePermissionSourceChange"
    />
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px">
      <!-- 规则随类型描述变化，切换类型时不应把尚未填写的字段立即标红 -->
      <el-form ref="formRef" :model="form" :rules="rules" :validate-on-rule-change="false" label-width="120px">
        <el-form-item label="数据源名称" prop="clusterName">
          <el-input v-model="form.clusterName" placeholder="例如：生产 Doris / 本地 PostgreSQL" />
        </el-form-item>
        <el-form-item label="类型" prop="sourceType">
          <el-select v-model="form.sourceType" @change="handleTypeChange">
            <el-option v-for="definition in typeDefinitions" :key="definition.type" :label="definition.label" :value="definition.type" />
          </el-select>
        </el-form-item>
        <el-form-item v-for="field in selectedDefinition?.connectionFields || []" :key="field.key" :label="field.label" :prop="field.key">
          <el-input-number v-if="field.control === 'number'" v-model="form[field.key]" :min="field.key === 'fePort' ? 1 : undefined" :max="field.key === 'fePort' ? 65535 : undefined" />
          <el-select v-else-if="field.control === 'select'" v-model="form[field.key]">
            <el-option v-for="option in field.options || []" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-switch v-else-if="field.control === 'switch'" v-model="form[field.key]" />
          <el-input v-else v-model="form[field.key]" :type="field.control === 'password' ? 'password' : 'text'" :show-password="field.control === 'password'" />
          <div v-if="field.help" class="cron-tip">{{ field.help }}</div>
        </el-form-item>
        <p class="account-tip">连接账号用于平台元数据采集与后台检查；个人访问在权限配置中分配角色并绑定账号。</p>
        <el-alert v-if="selectedDefinition && selectedDefinition.driverStatus !== 'AVAILABLE'" title="所需 JDBC 驱动不可用，请联系管理员" type="error" :closable="false" />
        <el-form-item label="默认数据源" prop="isDefault">
          <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="form.status">
            <el-option label="启用" value="active" />
            <el-option label="停用" value="inactive" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" :disabled="selectedDefinition?.driverStatus !== 'AVAILABLE'" @click="handleSubmit">保存</el-button>
        </div>
      </template>
    </el-dialog>


  </div>
</template>
<script setup>
import { capabilityReason, connectionRules, connectionDefaults, validateDefinitions } from '@/utils/datasourceTypes'

import { ref, reactive, computed, watch } from 'vue'
import { getActivePinia } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { dorisClusterApi } from '@/api/doris'
import DatasourcePermissionCenter from './components/DatasourcePermissionCenter.vue'
import SchemaBackupManager from '@/views/settings/components/SchemaBackupManager.vue'

const route = useRoute()
const router = useRouter()
const authStore = getActivePinia() ? useAuthStore() : { isAdmin: false }
const permissionVisible = ref(false)
const permissionCluster = ref(null)

function openPermissions(row) {
  permissionCluster.value = row
  permissionVisible.value = true
}

function handlePermissionSourceChange(newSourceId) {
  const found = clusters.value.find(c => String(c.id) === String(newSourceId))
  if (found) permissionCluster.value = found
}
const loading = ref(false)
const saving = ref(false)
const clusters = ref([])

const dialogVisible = ref(false)
const isEdit = ref(false)
const currentId = ref(null)
const formRef = ref(null)
const form = reactive({
  clusterName: '',
  sourceType: '',
  databaseName: '',
  feHost: '',
  fePort: null,
  username: '',
  password: '',
  isDefault: 0,
  status: 'active'
})

const typeDefinitions = ref([])
const typeError = ref('')
const selectedDefinition = computed(() => typeDefinitions.value.find(d => d.type === form.sourceType))
const rules = computed(() => ({
  clusterName: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
  sourceType: [{ required: true, message: '请选择类型', trigger: 'change' }],
  ...connectionRules(selectedDefinition.value)
}))

const dialogTitle = computed(() => (isEdit.value ? '编辑数据源' : '新增数据源'))

const formatDateTime = value => {
  if (!value) return '-'
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss')
}

async function loadClusters() {
  loading.value = true
  try {
    clusters.value = await dorisClusterApi.list()
  } finally {
    loading.value = false
  }
}

function resetForm() {
  for (const definition of typeDefinitions.value) {
    for (const field of definition.connectionFields || []) delete form[field.key]
  }
  form.clusterName = ''
  form.sourceType = typeDefinitions.value[0]?.type || ''
  previousSourceType = form.sourceType
  form.databaseName = ''
  form.feHost = ''
  Object.assign(form, connectionDefaults(selectedDefinition.value))
  form.username = ''
  form.password = ''
  form.isDefault = 0
  form.status = 'active'
}

const baseConnectionKeys = new Set(['feHost', 'fePort', 'databaseName', 'username', 'password'])
let previousSourceType = ''
const getSourceTypeLabel = type => typeDefinitions.value.find(d => d.type === type)?.label || type || '未知类型'
const getSourceTypeTag = () => 'info'
function handleTypeChange(val) {
  const previous = typeDefinitions.value.find(d => d.type === previousSourceType)
  const current = typeDefinitions.value.find(d => d.type === val)
  if (form.fePort === previous?.defaultPort) form.fePort = current?.defaultPort
  for (const field of previous?.connectionFields || []) {
    if (!baseConnectionKeys.has(field.key)) delete form[field.key]
  }
  for (const field of current?.connectionFields || []) {
    if (!baseConnectionKeys.has(field.key)) form[field.key] = field.defaultValue
  }
  previousSourceType = val
}

function openCreate() {
  resetForm()
  isEdit.value = false
  currentId.value = null
  dialogVisible.value = true
}

async function openEdit(id) {
  isEdit.value = true
  currentId.value = id
  try {
    const data = await dorisClusterApi.getById(id)
    resetForm()
    form.clusterName = data.clusterName
    form.sourceType = data.sourceType || ''
    previousSourceType = form.sourceType
    form.databaseName = data.databaseName || ''
    form.feHost = data.feHost
    form.fePort = data.fePort
    form.username = data.username
    form.isDefault = data.isDefault
    form.status = data.status || 'active'
    form.password = ''
    Object.assign(form, connectionDefaults(selectedDefinition.value), data, data.connectionOptions || {})
    form.password = ''
    dialogVisible.value = true
  } catch (error) {
    console.error(error)
  }
}

async function handleSubmit() {
  if (selectedDefinition.value?.driverStatus !== 'AVAILABLE') return
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  saving.value = true
  const connectionOptions = Object.fromEntries((selectedDefinition.value?.connectionFields || [])
    .filter(field => !baseConnectionKeys.has(field.key))
    .map(field => [field.key, form[field.key]]))
  const payload = {
    connectionOptions,
    clusterName: form.clusterName,
    sourceType: form.sourceType,
    ...Object.fromEntries((selectedDefinition.value?.connectionFields || []).filter(f => baseConnectionKeys.has(f.key) && f.key !== 'password').map(f => [f.key, typeof form[f.key] === 'string' ? form[f.key].trim() : form[f.key]])),
    feHost: form.feHost,
    fePort: form.fePort,
    username: form.username,
    isDefault: form.isDefault,
    status: form.status
  }
  if (form.password) {
    payload.password = form.password
  }

  try {
    if (isEdit.value && currentId.value) {
      await dorisClusterApi.update(currentId.value, payload)
      ElMessage.success('更新成功')
    } else {
      payload.password = form.password
      await dorisClusterApi.create(payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await loadClusters()
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除数据源「${row.clusterName}」吗？该操作不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }

  await dorisClusterApi.remove(row.id)
  ElMessage.success('删除成功')
  loadClusters()
}

async function handleSetDefault(id) {
  await dorisClusterApi.setDefault(id)
  ElMessage.success('已设为默认数据源')
  loadClusters()
}

async function handleTestConnection(id) {
  try {
    const connected = await dorisClusterApi.testConnection(id)
    if (connected) {
      ElMessage.success('连接成功')
    } else {
      ElMessage.error('连接失败，请检查数据源地址、端口、数据库及凭据')
    }
  } catch (error) {
    console.error(error)
  }
}

async function initialize() {
  try { typeDefinitions.value = validateDefinitions(await dorisClusterApi.types()) }
  catch (error) { typeError.value = error.message || '数据源类型描述加载失败' }
  await loadClusters()
  if (route?.query?.sourceId) await openEdit(route.query.sourceId)
}
watch(() => route?.query?.sourceId, id => { if (id && typeDefinitions.value.length) openEdit(id) })
initialize()
</script>

<style scoped>
.source-heading { margin-bottom: 16px; }
.account-tip { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; margin: 12px 0; }

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.sub-title {
  display: block;
  margin-top: 4px;
  color: #6b7280;
  font-size: 13px;
}

.actions {
  display: flex;
  gap: 8px;
}

.host-info {
  display: flex;
  align-items: center;
  gap: 6px;
}

.divider {
  color: #94a3b8;
}

.dialog-footer {
  text-align: right;
}

.cron-tip {
  margin-top: 4px;
  color: #94a3b8;
  font-size: 12px;
}

</style>
