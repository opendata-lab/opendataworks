<template>
  <div class="dashboard">
    <el-card class="header-card">
      <template #header>
        <div class="card-header">
          <div class="header-actions" style="margin-left: auto">
            <el-button type="primary" :icon="Refresh" @click="refreshData">刷新</el-button>
          </div>
        </div>
      </template>

      <div class="section-header-row">
        <div class="section-title">资产统计</div>
        <div class="asset-filter">
          <span class="filter-label">数据源</span>
          <el-select
            v-model="assetClusterId"
            size="small"
            class="asset-source-select"
            @change="handleAssetClusterChange"
          >
            <el-option value="all" label="全部数据源" />
            <el-option
              v-for="item in clusters"
              :key="item.id"
              :value="String(item.id)"
              :label="item.clusterName || item.name || `数据源 ${item.id}`"
            />
          </el-select>
        </div>
      </div>
      <el-row :gutter="20" class="stats-row">
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-icon sources">
              <el-icon><Coin /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ assetSourceCount }}</div>
              <div class="stat-label">数据源数</div>
            </div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-icon tables">
              <el-icon><Grid /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.totalTables || 0 }}</div>
              <div class="stat-label">数据表</div>
            </div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-icon domains">
              <el-icon><Folder /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.totalDomains || 0 }}</div>
              <div class="stat-label">数据域</div>
            </div>
          </div>
        </el-col>
      </el-row>

      <div class="access-section-header" style="margin-top: 30px;">
        <div class="section-title">表访问总览</div>
        <el-tag
          v-if="accessSyncMeta.label"
          :type="accessSyncMeta.type"
          size="small"
          effect="plain"
        >
          {{ accessSyncMeta.label }}
        </el-tag>
        <span v-if="statistics.tableAccessLastSyncedAt" class="access-sync-time">
          数据截至 {{ formatDateTime(statistics.tableAccessLastSyncedAt) }}
        </span>
      </div>
      <el-alert
        v-if="statistics.tableAccessNote"
        :title="statistics.tableAccessNote"
        :type="accessSyncMeta.alertType"
        show-icon
        :closable="false"
        class="access-alert"
      />
      <el-row :gutter="20" class="stats-row">
        <el-col :span="12">
          <el-card class="list-card" shadow="never">
            <template #header>
              <div class="list-card-header">
                <span>热点表 Top{{ (statistics.hotTables || []).length }}</span>
                <span class="list-card-sub">近 {{ statistics.hotWindowDays || 30 }} 天</span>
              </div>
            </template>
            <el-table :data="statistics.hotTables || []" size="small" border height="320">
              <el-table-column label="表名" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ formatTableName(row) }}
                </template>
              </el-table-column>
              <el-table-column prop="accessCount" label="访问次数" width="100" />
              <el-table-column label="最近访问" min-width="150">
                <template #default="{ row }">
                  {{ formatDateTime(row.lastAccessTime) }}
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card class="list-card" shadow="never">
            <template #header>
              <div class="list-card-header">
                <span>长期未用表 Top{{ visibleLongUnusedTables.length }}</span>
                <span class="list-card-sub">超过 {{ statistics.coldWindowDays || 90 }} 天</span>
              </div>
            </template>
            <el-table :data="visibleLongUnusedTables" size="small" border height="320">
              <el-table-column label="表名" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ formatTableName(row) }}
                </template>
              </el-table-column>
              <el-table-column label="最近访问" min-width="150">
                <template #default="{ row }">
                  {{ formatDateTime(row.lastAccessTime) }}
                </template>
              </el-table-column>
              <el-table-column label="距今天数" width="100">
                <template #default="{ row }">
                  {{ formatDays(row.daysSinceLastAccess) }}
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>

      <div class="section-title" style="margin-top: 30px;">任务统计</div>
      <el-row :gutter="20" class="stats-row">
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon tasks">
              <el-icon><List /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.totalTasks || 0 }}</div>
              <div class="stat-label">数据任务</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon executions">
              <el-icon><Document /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.totalExecutions || 0 }}</div>
              <div class="stat-label">总执行次数</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon running">
              <el-icon><Loading /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.runningExecutions || 0 }}</div>
              <div class="stat-label">运行中</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon issues">
              <el-icon><WarningFilled /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.openIssues || 0 }}</div>
              <div class="stat-label">待解决问题</div>
              <div class="stat-rate critical-rate" v-if="statistics.criticalIssues > 0">
                严重 {{ statistics.criticalIssues }}
              </div>
            </div>
          </div>
        </el-col>
      </el-row>

      <div class="section-title" style="margin-top: 30px;">任务执行结果</div>
      <el-row :gutter="20" class="stats-row">
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-icon success">
              <el-icon><CircleCheck /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.successExecutions || 0 }}</div>
              <div class="stat-label">成功次数</div>
              <div class="stat-rate success-rate">
                成功率 {{ statistics.executionSuccessRate || 0 }}%
              </div>
            </div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-icon failed">
              <el-icon><CircleClose /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.failedExecutions || 0 }}</div>
              <div class="stat-label">失败次数</div>
            </div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-icon today-total">
              <el-icon><Calendar /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.todayExecutions || 0 }}</div>
              <div class="stat-label">今日执行次数</div>
            </div>
          </div>
        </el-col>
      </el-row>

      <div class="section-title" style="margin-top: 30px;">今日任务执行</div>
      <el-row :gutter="20" class="stats-row">
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-icon today-success">
              <el-icon><Select /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.todaySuccessExecutions || 0 }}</div>
              <div class="stat-label">今日成功</div>
            </div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-icon today-failed">
              <el-icon><CloseBold /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.todayFailedExecutions || 0 }}</div>
              <div class="stat-label">今日失败</div>
            </div>
          </div>
        </el-col>
        <el-col :span="8">
          <el-empty description="按数据源切换仅影响资产统计" :image-size="60" />
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Refresh,
  Coin,
  Grid,
  List,
  Folder,
  Document,
  CircleCheck,
  CircleClose,
  Loading,
  WarningFilled,
  Calendar,
  Select,
  CloseBold
} from '@element-plus/icons-vue'
import { getDashboardStatistics } from '@/api/dashboard'
import { dorisClusterApi } from '@/api/doris'

// 数据定义
const statistics = ref({})
const loading = ref(false)
const clusters = ref([])
const assetClusterId = ref('all')

const assetSourceCount = computed(() => {
  if (assetClusterId.value === 'all') {
    return (clusters.value || []).length
  }
  return assetClusterId.value ? 1 : 0
})

const accessSyncMeta = computed(() => {
  const status = statistics.value.tableAccessSyncStatus
  const mapping = {
    READY: { label: '已就绪', type: 'success', alertType: 'success' },
    BACKFILLING: { label: '历史回填中', type: 'warning', alertType: 'warning' },
    DEGRADED: { label: '同步异常', type: 'danger', alertType: 'error' },
    DISABLED: { label: '同步已关闭', type: 'info', alertType: 'warning' },
    UNAVAILABLE: { label: '统计不可用', type: 'danger', alertType: 'error' }
  }
  return mapping[status] || { label: '', type: 'info', alertType: 'warning' }
})

const visibleLongUnusedTables = computed(() => {
  const status = statistics.value.tableAccessSyncStatus
  const isLegacyResponse = !status
  const canShowConclusion = isLegacyResponse
    || (status === 'READY' && statistics.value.tableAccessCoverageComplete === true)
  return canShowConclusion ? (statistics.value.longUnusedTables || []) : []
})

// 页面加载
onMounted(async () => {
  await loadClusters()
  await loadStatistics()
})

// 加载统计信息
const loadStatistics = async () => {
  loading.value = true
  try {
    const params = assetClusterId.value === 'all'
      ? {}
      : { clusterId: Number(assetClusterId.value) }
    const res = await getDashboardStatistics(params)
    statistics.value = res
  } catch (error) {
    console.error('Failed to load statistics:', error)
    ElMessage.error('加载统计数据失败: ' + error.message)
  } finally {
    loading.value = false
  }
}

// 加载数据源
const loadClusters = async () => {
  try {
    const list = await dorisClusterApi.list()
    clusters.value = Array.isArray(list) ? list : []
    const validValues = new Set(['all', ...clusters.value.map((item) => String(item.id))])
    if (!validValues.has(assetClusterId.value)) {
      assetClusterId.value = 'all'
    }
  } catch (error) {
    clusters.value = []
    assetClusterId.value = 'all'
    console.error('Failed to load clusters:', error)
  }
}

const handleAssetClusterChange = async () => {
  await loadStatistics()
}

// 刷新数据
const refreshData = async () => {
  await loadClusters()
  await loadStatistics()
}

const formatDateTime = (value) => {
  if (!value) return '-'
  return String(value).replace('T', ' ').split('.')[0]
}

const formatDays = (value) => {
  if (value === null || value === undefined) return '-'
  return `${value} 天`
}

const formatTableName = (row) => {
  if (!row) return '-'
  const db = row.dbName || ''
  const table = row.tableName || ''
  return db && table ? `${db}.${table}` : (table || '-')
}
</script>

<style scoped>
.dashboard {
  padding: 6px;
}

.header-card {
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 18px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 15px;
  padding-left: 10px;
  border-left: 4px solid #409eff;
}

.section-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.section-header-row .section-title {
  margin-bottom: 0;
}

.asset-filter {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-label {
  font-size: 13px;
  color: #606266;
}

.asset-source-select {
  width: 240px;
}

.stats-row {
  margin-top: 10px;
}

.stat-card {
  display: flex;
  align-items: center;
  padding: 20px;
  background: #f8fafc;
  border-radius: 12px;
  transition: all 0.3s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.12);
}

.stat-icon {
  width: 60px;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 28px;
  margin-right: 15px;
  flex-shrink: 0;
}

/* 资源统计图标颜色 */
.stat-icon.tables {
  background: #e8f4fd;
  color: #1890ff;
}

.stat-icon.sources {
  background: #f0f9ff;
  color: #0284c7;
}

.stat-icon.tasks {
  background: #f0f5ff;
  color: #597ef7;
}

.stat-icon.domains {
  background: #fff7e6;
  color: #fa8c16;
}

.stat-icon.executions {
  background: #f9f0ff;
  color: #9254de;
}

/* 执行统计图标颜色 */
.stat-icon.success {
  background: #f6ffed;
  color: #52c41a;
}

.stat-icon.failed {
  background: #fff1f0;
  color: #f5222d;
}

.stat-icon.running {
  background: #e6f7ff;
  color: #1890ff;
}

.stat-icon.issues {
  background: #fff7e6;
  color: #fa8c16;
}

/* 今日统计图标颜色 */
.stat-icon.today-total {
  background: #f0f5ff;
  color: #597ef7;
}

.stat-icon.today-success {
  background: #f6ffed;
  color: #52c41a;
}

.stat-icon.today-failed {
  background: #fff1f0;
  color: #f5222d;
}

.stat-info {
  flex: 1;
  min-width: 0;
}

.stat-value {
  font-size: 28px;
  font-weight: bold;
  line-height: 1;
  margin-bottom: 5px;
  color: #303133;
}

.stat-label {
  font-size: 14px;
  color: #606266;
}

.stat-rate {
  font-size: 12px;
  margin-top: 5px;
}

.stat-rate.success-rate {
  color: #52c41a;
}

.stat-rate.critical-rate {
  color: #f5222d;
  font-weight: 500;
}

.access-alert {
  margin-bottom: 16px;
}

.access-section-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.access-section-header .section-title {
  margin-bottom: 0;
}

.access-sync-time {
  font-size: 12px;
  color: #909399;
}

.list-card {
  border-radius: 10px;
}

.list-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.list-card-sub {
  font-size: 12px;
  color: #64748b;
}
</style>
