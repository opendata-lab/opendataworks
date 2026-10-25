<template>
  <div class="datasource-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div>
            <span class="sub-title">统一配置数据源，支持连通性检测、默认数据源与元数据同步</span>
          </div>
          <div class="actions">
            <el-button type="primary" :disabled="!typeDefinitions.length" @click="openCreate">
              <el-icon><Plus /></el-icon>
              新增数据源
            </el-button>
          </div>
        </div>
      </template>

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
        <el-table-column label="自动同步" min-width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.autoSync === 1 ? 'success' : 'info'">
              {{ row.autoSync === 1 ? '开启' : '关闭' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="syncCron" label="同步 Cron" min-width="220">
          <template #default="{ row }">
            <span>{{ row.syncCron || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastSyncTime" label="最近同步" min-width="180">
          <template #default="{ row }">
            <span>{{ formatDateTime(row.lastSyncTime) }}</span>
          </template>
        </el-table-column>
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
        <el-table-column label="操作" fixed="right" width="500">
          <template #default="{ row }">
            <el-button type="primary" text size="small" @click="openEdit(row.id)">编辑</el-button>
            <el-button type="success" text size="small" :disabled="row.typeDefinition?.driverStatus !== 'AVAILABLE'" :title="row.typeDefinition?.driverStatus !== 'AVAILABLE' ? '所需 JDBC 驱动不可用' : ''" @click="handleTestConnection(row.id)">测试连接</el-button>
            <el-button type="warning" text size="small" :disabled="row.isDefault === 1" @click="handleSetDefault(row.id)"
              >设为默认</el-button
            >
            <el-button type="info" text size="small" @click="openMetadata(row)">元数据</el-button>
            <el-button type="primary" text size="small" @click="openSyncHistory(row)">同步历史</el-button>
            <el-button type="danger" text size="small" @click="openPendingDeletion(row)">待删除表</el-button>
            <el-button type="danger" text size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="数据源名称" prop="clusterName">
          <el-input v-model="form.clusterName" placeholder="例如：生产 Doris / 本地 PostgreSQL" />
        </el-form-item>
        <el-form-item label="类型" prop="sourceType">
          <el-select v-model="form.sourceType" @change="handleTypeChange">
            <el-option v-for="definition in typeDefinitions" :key="definition.type" :label="definition.label" :value="definition.type" />
          </el-select>
        </el-form-item>
        <el-form-item v-for="field in selectedDefinition?.connectionFields || []" :key="field.key" :label="field.label" :prop="field.key">
          <el-input-number v-if="field.control === 'number'" v-model="form[field.key]" :min="1" :max="65535" />
          <el-select v-else-if="field.control === 'select'" v-model="form[field.key]">
            <el-option v-for="option in field.options || []" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-switch v-else-if="field.control === 'boolean'" v-model="form[field.key]" />
          <el-input v-else v-model="form[field.key]" :type="field.control === 'password' ? 'password' : 'text'" :show-password="field.control === 'password'" />
          <div v-if="field.help" class="cron-tip">{{ field.help }}</div>
        </el-form-item>
        <el-alert v-if="selectedDefinition && selectedDefinition.driverStatus !== 'AVAILABLE'" title="所需 JDBC 驱动不可用，请联系管理员" type="error" :closable="false" />
        <el-form-item label="自动同步" prop="autoSync">
          <el-switch v-model="form.autoSync" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="同步 Cron" prop="syncCron">
          <el-input
            v-model="form.syncCron"
            placeholder="例如：0 0 2 * * ?（每天 02:00）"
            :disabled="form.autoSync !== 1"
          />
          <div class="cron-tip">cron 格式：秒 分 时 日 月 周（Spring 6段）</div>
        </el-form-item>
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

    <el-dialog
      v-model="metadataDialogVisible"
      :title="`元数据比对与同步 - ${selectedCluster?.clusterName || ''}`"
      width="75%"
      :close-on-click-modal="false"
    >
      <div class="metadata-panel-header">
        <div class="meta-left">
          <el-tag size="small" :type="getSourceTypeTag(selectedCluster?.sourceType)">
            {{ getSourceTypeLabel(selectedCluster?.sourceType) }}
          </el-tag>
          <span class="meta-addr">{{ selectedCluster?.feHost }}:{{ selectedCluster?.fePort }}</span>
        </div>
        <div class="meta-actions">
          <el-button type="warning" size="small" @click="auditMetadata" :loading="auditLoading">
            <el-icon><Refresh /></el-icon>
            比对元数据
          </el-button>
          <el-button type="success" size="small" @click="confirmSync" :loading="syncLoading" :disabled="!auditResult?.totalDifferences">
            确认同步
          </el-button>
        </div>
      </div>

      <div v-if="auditResult" class="audit-info">
        <el-icon :color="auditResult.hasDifferences ? '#E6A23C' : '#67C23A'">
          <Warning v-if="auditResult.hasDifferences" />
          <CircleCheck v-else />
        </el-icon>
        <span class="audit-text">
          最近比对: {{ auditResult.time }}
          <template v-if="auditResult.statisticsSynced > 0"> | 已自动同步 {{ auditResult.statisticsSynced }} 张表的统计信息</template>
          <template v-if="auditResult.hasDifferences">
            | 发现 {{ auditResult.totalDifferences }} 处结构差异
            <el-button type="primary" size="small" @click="differenceDialogVisible = true" style="margin-left: 6px">
              查看详情
            </el-button>
          </template>
          <template v-else> | 结构一致，无需同步</template>
        </span>
      </div>
      <el-empty v-else description="请点击“比对元数据”获取差异" :image-size="100" />

      <el-dialog v-model="differenceDialogVisible" title="元数据差异详情" width="70%" :close-on-click-modal="false">
        <div v-if="auditResult && auditResult.differences" class="difference-container">
          <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 16px">
            <template #title>共发现 {{ auditResult.totalDifferences }} 处差异，请仔细检查后决定是否同步</template>
          </el-alert>

          <el-collapse>
            <el-collapse-item v-for="(diff, index) in auditResult.differences" :key="index" :name="index">
              <template #title>
                <div class="diff-title">
                  <el-tag :type="diff.type === 'NEW' ? 'success' : diff.type === 'REMOVED' ? 'danger' : 'warning'" size="small">
                    {{ diff.type === 'NEW' ? '新表' : diff.type === 'REMOVED' ? '已删除' : '已更新' }}
                  </el-tag>
                  <span class="table-name-diff">{{ diff.database }}.{{ diff.tableName }}</span>
                </div>
              </template>

              <div v-if="diff.changes && diff.changes.length > 0" class="changes-section">
                <h4>表信息变更:</h4>
                <ul>
                  <li v-for="(change, idx) in diff.changes" :key="idx">{{ change }}</li>
                </ul>
              </div>

              <div v-if="diff.fieldDifferences && diff.fieldDifferences.length > 0" class="field-diff-section">
                <h4>字段变更:</h4>
                <el-table :data="diff.fieldDifferences" border size="small" style="margin-top: 8px">
                  <el-table-column prop="fieldName" label="字段名" width="200" />
                  <el-table-column prop="type" label="变更类型" width="120">
                    <template #default="{ row }">
                      <el-tag :type="row.type === 'NEW' ? 'success' : row.type === 'REMOVED' ? 'danger' : 'warning'" size="small">
                        {{ row.type === 'NEW' ? '新增' : row.type === 'REMOVED' ? '删除' : '更新' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="变更详情">
                    <template #default="{ row }">
                      <div v-if="row.changes">
                        <div v-for="(value, key) in row.changes" :key="key" class="field-change-item">
                          <strong>{{ key }}:</strong> {{ value }}
                        </div>
                      </div>
                      <div v-else>-</div>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>

        <template #footer>
          <el-button @click="differenceDialogVisible = false">关闭</el-button>
          <el-button type="success" @click="confirmSyncFromDialog" :loading="syncLoading">确认同步</el-button>
        </template>
      </el-dialog>

      <template #footer>
        <el-button @click="closeMetadata">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="syncHistoryDialogVisible"
      :title="`同步历史 - ${syncHistoryCluster?.clusterName || ''}`"
      width="78%"
      :close-on-click-modal="false"
    >
      <el-table v-loading="syncHistoryLoading" :data="syncHistoryList" border style="width: 100%">
        <el-table-column prop="startedAt" label="时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column prop="triggerType" label="触发方式" min-width="100" />
        <el-table-column prop="scopeType" label="范围" min-width="90" />
        <el-table-column prop="scopeTarget" label="目标" min-width="180" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" min-width="100">
          <template #default="{ row }">
            <el-tag :type="getSyncStatusTagType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" min-width="90">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="变更统计" min-width="240">
          <template #default="{ row }">
            +表{{ row.newTables || 0 }} / ~表{{ row.updatedTables || 0 }} / -表{{ row.deletedTables || 0 }}
            / 阻断{{ row.blockedDeletedTables || 0 }} / +字段{{ row.newFields || 0 }} / ~字段{{ row.updatedFields || 0 }} /
            -字段{{ row.deletedFields || 0 }}
          </template>
        </el-table-column>
        <el-table-column label="错误摘要" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.errorSummary || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewSyncHistoryDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="history-pagination">
        <el-pagination
          background
          layout="total, prev, pager, next, sizes"
          :total="syncHistoryPager.total"
          :current-page="syncHistoryPager.pageNum"
          :page-size="syncHistoryPager.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="handleSyncHistoryPageChange"
          @size-change="handleSyncHistorySizeChange"
        />
      </div>

      <template #footer>
        <el-button @click="syncHistoryDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="syncHistoryDetailVisible"
      title="同步历史详情"
      width="72%"
      :close-on-click-modal="false"
    >
      <el-descriptions v-if="syncHistoryDetail" :column="1" border>
        <el-descriptions-item label="运行ID">{{ syncHistoryDetail.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getSyncStatusTagType(syncHistoryDetail.status)" size="small">{{ syncHistoryDetail.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="触发">{{ syncHistoryDetail.triggerType }}</el-descriptions-item>
        <el-descriptions-item label="范围">{{ syncHistoryDetail.scopeType }} {{ syncHistoryDetail.scopeTarget || '-' }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ formatDateTime(syncHistoryDetail.startedAt) }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ formatDateTime(syncHistoryDetail.finishedAt) }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ formatDuration(syncHistoryDetail.durationMs) }}</el-descriptions-item>
        <el-descriptions-item label="错误数">{{ syncHistoryDetail.errorCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="错误摘要">{{ syncHistoryDetail.errorSummary || '-' }}</el-descriptions-item>
        <el-descriptions-item label="错误明细">
          <pre class="error-detail">{{ formatErrorDetails(syncHistoryDetail.errorDetails) }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="变更详情">
          <div class="change-detail-section">
            <el-empty
              v-if="!syncHistoryChangeGroups.length"
              description="无新增、修改或删除明细"
              :image-size="72"
            />
            <div v-else class="change-groups">
              <div v-for="group in syncHistoryChangeGroups" :key="group.key" class="change-group">
                <div class="change-group-title">
                  <el-tag size="small" :type="group.tagType">{{ group.label }} {{ group.items.length }}</el-tag>
                </div>
                <div class="change-item-list">
                  <div v-for="(item, index) in group.items" :key="`${group.key}-${index}`" class="change-item">
                    <div class="change-item-header">
                      <span class="change-item-location">{{ formatChangeLocation(item) }}</span>
                      <el-tag size="small" type="info">{{ item.objectType || '-' }}</el-tag>
                    </div>
                    <div class="change-item-summary">{{ item.summary || '-' }}</div>
                    <div v-if="formatChangePairs(item.changes).length" class="change-item-pairs">
                      <div
                        v-for="pair in formatChangePairs(item.changes)"
                        :key="pair.key"
                        class="change-pair"
                      >
                        <span class="pair-key">{{ pair.key }}</span>
                        <span class="pair-value">{{ pair.oldValue }} -> {{ pair.newValue }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="syncHistoryDetailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="pendingDeletionDialogVisible"
      :title="`待删除表管理 - ${pendingDeletionCluster?.clusterName || ''}`"
      width="74%"
      :close-on-click-modal="false"
    >
      <el-table v-loading="pendingDeletionLoading" :data="pendingDeletionList" border style="width: 100%">
        <el-table-column prop="dbName" label="数据库" min-width="130" />
        <el-table-column prop="tableName" label="当前表名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="originTableName" label="原始表名" min-width="160" show-overflow-tooltip />
        <el-table-column prop="deprecatedAt" label="废弃时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.deprecatedAt) }}</template>
        </el-table-column>
        <el-table-column prop="purgeAt" label="预计删除时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.purgeAt) }}</template>
        </el-table-column>
        <el-table-column prop="remainingDays" label="剩余天数" min-width="90" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleRestoreTable(row)">恢复</el-button>
            <el-button link type="danger" @click="handlePurgeNow(row)">立即删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="pendingDeletionDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { capabilityReason, connectionRules, connectionDefaults, validateDefinitions } from '@/utils/datasourceTypes'

import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Warning, CircleCheck } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { dorisClusterApi } from '@/api/doris'
import { tableApi } from '@/api/table'
import SchemaBackupManager from '@/views/settings/components/SchemaBackupManager.vue'

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
  autoSync: 0,
  syncCron: '',
  isDefault: 0,
  status: 'active'
})

const metadataDialogVisible = ref(false)
const differenceDialogVisible = ref(false)
const selectedCluster = ref(null)
const auditLoading = ref(false)
const syncLoading = ref(false)
const auditResult = ref(null)
const syncHistoryDialogVisible = ref(false)
const syncHistoryDetailVisible = ref(false)
const syncHistoryLoading = ref(false)
const syncHistoryCluster = ref(null)
const syncHistoryList = ref([])
const syncHistoryDetail = ref(null)
const syncHistoryPager = reactive({
  pageNum: 1,
  pageSize: 20,
  total: 0
})
const pendingDeletionDialogVisible = ref(false)
const pendingDeletionLoading = ref(false)
const pendingDeletionCluster = ref(null)
const pendingDeletionList = ref([])

const typeDefinitions = ref([])
const typeError = ref('')
const selectedDefinition = computed(() => typeDefinitions.value.find(d => d.type === form.sourceType))
const rules = computed(() => ({
  clusterName: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
  sourceType: [{ required: true, message: '请选择类型', trigger: 'change' }],
  ...connectionRules(selectedDefinition.value),
  syncCron: [
    {
      validator: (_, value, callback) => {
        if (form.autoSync === 1 && !value) {
          callback(new Error('开启自动同步时必须填写同步 Cron'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}))

const dialogTitle = computed(() => (isEdit.value ? '编辑数据源' : '新增数据源'))

const formatDateTime = value => {
  if (!value) return '-'
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss')
}

const formatDuration = value => {
  const ms = Number(value || 0)
  if (!Number.isFinite(ms) || ms < 0) return '-'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)}s`
  const seconds = Math.floor(ms / 1000)
  const mins = Math.floor(seconds / 60)
  const remain = seconds % 60
  return `${mins}m ${remain}s`
}

const getSyncStatusTagType = status => {
  const normalized = String(status || '').toUpperCase()
  if (normalized === 'SUCCESS') return 'success'
  if (normalized === 'PARTIAL') return 'warning'
  return 'danger'
}

const formatErrorDetails = value => {
  if (!value) return '-'
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value
    if (Array.isArray(parsed)) {
      return parsed.length ? parsed.join('\n') : '-'
    }
    return JSON.stringify(parsed, null, 2)
  } catch {
    return String(value)
  }
}

const parseChangeDetails = value => {
  if (!value) return { added: [], updated: [], deleted: [] }
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value
    return {
      added: Array.isArray(parsed?.added) ? parsed.added : [],
      updated: Array.isArray(parsed?.updated) ? parsed.updated : [],
      deleted: Array.isArray(parsed?.deleted) ? parsed.deleted : []
    }
  } catch {
    return { added: [], updated: [], deleted: [] }
  }
}

const syncHistoryChangeGroups = computed(() => {
  const detail = parseChangeDetails(syncHistoryDetail.value?.changeDetails)
  return [
    { key: 'added', label: '新增', tagType: 'success', items: detail.added },
    { key: 'updated', label: '修改', tagType: 'warning', items: detail.updated },
    { key: 'deleted', label: '删除', tagType: 'danger', items: detail.deleted }
  ].filter(group => group.items.length > 0)
})

const toDisplayText = value => {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return String(value)
    }
  }
  return String(value)
}

const formatChangeLocation = item => {
  const database = item?.database ? String(item.database) : '-'
  const tableName = item?.tableName ? String(item.tableName) : ''
  const fieldName = item?.fieldName ? String(item.fieldName) : ''
  if (!tableName) return database
  if (!fieldName) return `${database}.${tableName}`
  return `${database}.${tableName}.${fieldName}`
}

const formatChangePairs = changes => {
  if (!changes || typeof changes !== 'object') return []
  return Object.entries(changes).map(([key, value]) => {
    if (value && typeof value === 'object' && ('old' in value || 'new' in value)) {
      return {
        key,
        oldValue: toDisplayText(value.old),
        newValue: toDisplayText(value.new)
      }
    }
    return {
      key,
      oldValue: '-',
      newValue: toDisplayText(value)
    }
  })
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
  form.clusterName = ''
  form.sourceType = typeDefinitions.value[0]?.type || ''
  previousSourceType = form.sourceType
  form.databaseName = ''
  form.feHost = ''
  Object.assign(form, connectionDefaults(selectedDefinition.value))
  form.username = ''
  form.password = ''
  form.autoSync = 0
  form.syncCron = ''
  form.isDefault = 0
  form.status = 'active'
}

let previousSourceType = ''
const getSourceTypeLabel = type => typeDefinitions.value.find(d => d.type === type)?.label || type || '未知类型'
const getSourceTypeTag = () => 'info'
function handleTypeChange(val) {
  const previous = typeDefinitions.value.find(d => d.type === previousSourceType)
  const current = typeDefinitions.value.find(d => d.type === val)
  if (form.fePort === previous?.defaultPort) form.fePort = current?.defaultPort
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
    form.autoSync = data.autoSync || 0
    form.syncCron = data.syncCron || ''
    form.isDefault = data.isDefault
    form.status = data.status || 'active'
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
  const payload = {
    clusterName: form.clusterName,
    sourceType: form.sourceType,
    ...Object.fromEntries((selectedDefinition.value?.connectionFields || []).filter(f => f.key !== 'password').map(f => [f.key, typeof form[f.key] === 'string' ? form[f.key].trim() : form[f.key]])),
    feHost: form.feHost,
    fePort: form.fePort,
    username: form.username,
    autoSync: form.autoSync,
    syncCron: form.syncCron || null,
    isDefault: form.isDefault,
    status: form.status
  }
  if (form.password) {
    payload.password = form.password
  }

  try {
    let createdCluster = null
    if (isEdit.value && currentId.value) {
      await dorisClusterApi.update(currentId.value, payload)
      ElMessage.success('更新成功')
    } else {
      payload.password = form.password
      createdCluster = await dorisClusterApi.create(payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await loadClusters()
    if (createdCluster?.id) {
      await promptSyncAfterCreate(createdCluster)
    }
  } finally {
    saving.value = false
  }
}

async function promptSyncAfterCreate(cluster) {
  if (!cluster?.id) return
  const clusterName = cluster.clusterName || form.clusterName || '当前数据源'
  try {
    await ElMessageBox.confirm(
      `数据源「${clusterName}」创建成功。建议立即同步一次元数据，是否现在执行？`,
      '立即同步元数据',
      {
        type: 'info',
        confirmButtonText: '立即同步',
        cancelButtonText: '稍后再说',
        distinguishCancelAndClose: true
      }
    )
    await performSync(cluster)
  } catch {
    // cancel or close
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

function openMetadata(row) {
  selectedCluster.value = row
  auditResult.value = null
  differenceDialogVisible.value = false
  metadataDialogVisible.value = true
}

function closeMetadata() {
  metadataDialogVisible.value = false
  selectedCluster.value = null
  auditResult.value = null
  differenceDialogVisible.value = false
}

function openSyncHistory(row) {
  syncHistoryCluster.value = row
  syncHistoryPager.pageNum = 1
  syncHistoryPager.pageSize = 20
  syncHistoryPager.total = 0
  syncHistoryList.value = []
  syncHistoryDetail.value = null
  syncHistoryDetailVisible.value = false
  syncHistoryDialogVisible.value = true
  loadSyncHistory()
}

async function loadSyncHistory() {
  if (!syncHistoryCluster.value?.id) return
  syncHistoryLoading.value = true
  try {
    const data = await dorisClusterApi.getSyncHistory(syncHistoryCluster.value.id, {
      pageNum: syncHistoryPager.pageNum,
      pageSize: syncHistoryPager.pageSize
    })
    syncHistoryList.value = Array.isArray(data?.records) ? data.records : []
    syncHistoryPager.total = Number(data?.total || 0)
  } catch (error) {
    console.error('加载同步历史失败:', error)
    ElMessage.error('加载同步历史失败: ' + (error.message || '未知错误'))
  } finally {
    syncHistoryLoading.value = false
  }
}

function handleSyncHistoryPageChange(pageNum) {
  syncHistoryPager.pageNum = pageNum
  loadSyncHistory()
}

function handleSyncHistorySizeChange(pageSize) {
  syncHistoryPager.pageSize = pageSize
  syncHistoryPager.pageNum = 1
  loadSyncHistory()
}

async function viewSyncHistoryDetail(row) {
  if (!syncHistoryCluster.value?.id || !row?.id) return
  try {
    syncHistoryDetail.value = await dorisClusterApi.getSyncHistoryDetail(syncHistoryCluster.value.id, row.id)
    syncHistoryDetailVisible.value = true
  } catch (error) {
    console.error('加载同步历史详情失败:', error)
    ElMessage.error('加载同步历史详情失败: ' + (error.message || '未知错误'))
  }
}

function openPendingDeletion(row) {
  pendingDeletionCluster.value = row
  pendingDeletionList.value = []
  pendingDeletionDialogVisible.value = true
  loadPendingDeletion()
}

async function loadPendingDeletion() {
  if (!pendingDeletionCluster.value?.id) return
  pendingDeletionLoading.value = true
  try {
    const data = await tableApi.listPendingDeletion(pendingDeletionCluster.value.id)
    pendingDeletionList.value = Array.isArray(data) ? data : []
  } catch (error) {
    console.error('加载待删除表失败:', error)
    ElMessage.error('加载待删除表失败: ' + (error.message || '未知错误'))
  } finally {
    pendingDeletionLoading.value = false
  }
}

const handleRestoreTable = async row => {
  if (!row?.id || !pendingDeletionCluster.value?.id) return
  try {
    await ElMessageBox.confirm(`确认恢复表「${row.originTableName || row.tableName}」吗？`, '恢复确认', {
      confirmButtonText: '确认恢复',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }

  try {
    await tableApi.restore(row.id, pendingDeletionCluster.value.id)
    ElMessage.success('恢复成功')
    await loadPendingDeletion()
  } catch (error) {
    console.error('恢复表失败:', error)
    ElMessage.error('恢复表失败: ' + (error.message || '未知错误'))
  }
}

const handlePurgeNow = async row => {
  if (!row?.id || !pendingDeletionCluster.value?.id) return
  const rawName = String(row.tableName || '').trim()
  const expectedName = rawName.includes('.') ? rawName.split('.').pop() : rawName
  try {
    await ElMessageBox.confirm(
      `确认立即删除表「${row.tableName}」吗？该操作不可恢复。`,
      '立即删除确认',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    return
  }

  try {
    await tableApi.purgeNow(row.id, pendingDeletionCluster.value.id, expectedName)
    ElMessage.success('已立即删除')
    await loadPendingDeletion()
  } catch (error) {
    console.error('立即删除失败:', error)
    ElMessage.error('立即删除失败: ' + (error.message || '未知错误'))
  }
}

const auditMetadata = async () => {
  if (!selectedCluster.value?.id) return
  auditLoading.value = true
  try {
    const response = await tableApi.auditMetadata(selectedCluster.value.id)
    auditResult.value = {
      hasDifferences: response.hasDifferences,
      totalDifferences: response.totalDifferences,
      differences: response.differences,
      errors: response.errors,
      statisticsSynced: response.statisticsSynced || 0,
      time: new Date().toLocaleString()
    }

    let message = ''
    if (response.statisticsSynced > 0) {
      message += `已自动同步 ${response.statisticsSynced} 张表的统计信息。`
    }
    if (response.hasDifferences) {
      message += `发现 ${response.totalDifferences} 处结构差异，请查看详情并确认是否同步。`
      ElMessage.warning({ message, duration: 5000 })
    } else {
      message += '结构一致，无需同步。'
      ElMessage.success({ message, duration: 3000 })
    }
  } catch (error) {
    console.error('比对元数据失败:', error)
    ElMessage.error('比对元数据失败: ' + (error.message || '未知错误'))
  } finally {
    auditLoading.value = false
  }
}

const confirmSync = async () => {
  if (!auditResult.value?.totalDifferences) return
  try {
    await ElMessageBox.confirm(`确认要同步 ${auditResult.value.totalDifferences} 处差异吗？`, '确认同步', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await performSync()
  } catch {
    // cancel
  }
}

const confirmSyncFromDialog = async () => {
  differenceDialogVisible.value = false
  await performSync()
}

const performSync = async (cluster = selectedCluster.value) => {
  if (!cluster?.id) return
  syncLoading.value = true
  try {
    const response = await tableApi.syncMetadata(cluster.id)
    const status = String(response?.status || '').toUpperCase()
    const blocked = Number(response?.blockedDeletedTables || 0)
    const runId = response?.syncRunId
    if (status === 'SUCCESS') {
      ElMessage.success(runId ? `元数据同步成功（Run #${runId}）` : '元数据同步成功')
    } else if (status === 'PARTIAL') {
      ElMessage.warning(
        `元数据同步部分成功${blocked > 0 ? `，阻断删除 ${blocked} 张表` : ''}${runId ? `（Run #${runId}）` : ''}`
      )
    } else {
      ElMessage.error(runId ? `元数据同步失败（Run #${runId}）` : '元数据同步失败')
    }
    auditResult.value = null
    await loadClusters()
    await loadSyncHistory()
  } catch (error) {
    console.error('同步元数据失败:', error)
    ElMessage.error('同步元数据失败: ' + (error.message || '未知错误'))
  } finally {
    syncLoading.value = false
  }
}

async function initialize() {
  try { typeDefinitions.value = validateDefinitions(await dorisClusterApi.types()) }
  catch (error) { typeError.value = error.message || '数据源类型描述加载失败' }
  await loadClusters()
}
initialize()
</script>

<style scoped>
.datasource-page {
  /* padding removed for tab integration */
}

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

.metadata-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.meta-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.meta-addr {
  color: #64748b;
  font-size: 13px;
}

.meta-actions {
  display: flex;
  gap: 10px;
}

.audit-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background-color: #fef0e6;
  border-radius: 6px;
  font-size: 13px;
  color: #555;
}

.audit-info .audit-text {
  flex: 1;
}

.difference-container {
  max-height: 600px;
  overflow-y: auto;
}

.diff-title {
  display: flex;
  align-items: center;
  gap: 12px;
  font-weight: 600;
}

.table-name-diff {
  color: #333;
}

.changes-section {
  padding: 12px;
  background-color: #f8fafc;
  border-radius: 6px;
  margin-bottom: 12px;
}

.changes-section h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #666;
}

.changes-section ul {
  margin: 0;
  padding-left: 20px;
}

.changes-section li {
  margin: 4px 0;
  color: #555;
  font-size: 13px;
}

.field-diff-section {
  padding: 12px;
  margin-top: 12px;
}

.field-diff-section h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #666;
}

.field-change-item {
  padding: 2px 0;
  font-size: 13px;
}

.field-change-item strong {
  color: #666;
  font-weight: 600;
}

.change-detail-section {
  padding: 4px 0;
}

.change-groups {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.change-group-title {
  margin-bottom: 8px;
}

.change-item-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.change-item {
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 10px;
  background: #f8fafc;
}

.change-item-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.change-item-location {
  font-weight: 600;
  color: #334155;
  word-break: break-all;
}

.change-item-summary {
  margin-top: 6px;
  color: #475569;
  font-size: 13px;
  word-break: break-word;
}

.change-item-pairs {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.change-pair {
  font-size: 12px;
  color: #475569;
  line-height: 1.5;
}

.pair-key {
  display: inline-block;
  min-width: 116px;
  color: #64748b;
}

.pair-value {
  word-break: break-word;
}

.history-pagination {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

.error-detail {
  margin: 0;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.5;
}
</style>
