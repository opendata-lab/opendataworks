<template>
  <div class="inspection-view">
    <el-card class="header-card">
      <template #header>
        <div class="card-header">
          <div class="header-actions" style="margin-left: auto">
            <el-button type="primary" :icon="VideoPlay" @click="handleRunInspection" :loading="runningInspection">
              执行巡检
            </el-button>
            <el-button :icon="Refresh" @click="refreshData">刷新</el-button>
          </div>
        </div>
      </template>

      <!-- 统计卡片 -->
      <el-row :gutter="20" class="stats-row">
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon total">
              <el-icon><Warning /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ overview.totalOpenIssues || 0 }}</div>
              <div class="stat-label">待处理问题</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon critical">
              <el-icon><CircleClose /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ overview.severityDistribution?.critical || 0 }}</div>
              <div class="stat-label">严重问题</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon high">
              <el-icon><WarningFilled /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ overview.severityDistribution?.high || 0 }}</div>
              <div class="stat-label">高危问题</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon medium">
              <el-icon><InfoFilled /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ overview.severityDistribution?.medium || 0 }}</div>
              <div class="stat-label">中等问题</div>
            </div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- 巡检规则列表 -->
    <el-card class="rules-card">
      <template #header>
        <div class="card-header">
          <span>巡检规则</span>
          <div class="header-actions">
            <el-button :icon="Refresh" @click="loadRules">刷新规则</el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        style="margin-bottom: 12px"
        title="提示：默认规则为停用状态，启用后才会在“执行巡检”中生效。"
      />

      <el-table
        v-loading="rulesLoading"
        :data="rulesList"
        stripe
        style="width: 100%"
      >
        <el-table-column prop="ruleName" label="规则名称" min-width="180" />
        <el-table-column prop="ruleType" label="规则类型" width="160">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">
              {{ getIssueTypeText(row.ruleType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="severity" label="默认严重程度" width="140">
          <template #default="{ row }">
            <el-tag :type="getSeverityType(row.severity)" size="small">
              {{ getSeverityText(row.severity) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="规则说明" min-width="260" show-overflow-tooltip />
        <el-table-column prop="enabled" label="启用" width="120" fixed="right">
          <template #default="{ row }">
            <el-switch
              v-model="row.enabled"
              :loading="!!ruleUpdating[row.id]"
              :disabled="!!ruleUpdating[row.id]"
              @change="(val) => handleRuleEnabledChange(row, val)"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 巡检记录列表 -->
    <el-card class="records-card">
      <template #header>
        <div class="card-header">
          <span>巡检历史</span>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="recordsList"
        stripe
        style="width: 100%"
        @row-click="handleViewIssues"
        :row-style="{ cursor: 'pointer' }"
      >
        <el-table-column prop="id" label="巡检ID" width="100" />
        <el-table-column prop="inspectionType" label="巡检类型" width="120">
          <template #default="{ row }">
            <el-tag>{{ getInspectionTypeText(row.inspectionType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="inspectionTime" label="巡检时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.inspectionTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="triggerType" label="触发方式" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">
              {{ getTriggerTypeText(row.triggerType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="issueCount" label="问题数量" width="100">
          <template #default="{ row }">
            <span :class="row.issueCount > 0 ? 'issue-count-warning' : ''">
              {{ row.issueCount || 0 }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="durationSeconds" label="执行时长" width="120">
          <template #default="{ row }">
            {{ formatDuration(row.durationSeconds) }}
          </template>
        </el-table-column>
        <el-table-column prop="createdBy" label="创建人" width="120" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              @click.stop="handleViewIssues(row)"
            >
              查看问题
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 问题列表对话框 -->
    <el-dialog
      v-model="issuesDialogVisible"
      title="巡检问题详情"
      width="1200px"
      top="5vh"
    >
      <div v-if="currentRecordId">
        <!-- 问题统计 -->
        <el-row :gutter="20" style="margin-bottom: 20px">
          <el-col :span="6">
            <div class="mini-stat">
              <span class="label">严重:</span>
              <span class="value critical">{{ severitySummary.critical }}</span>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="mini-stat">
              <span class="label">高危:</span>
              <span class="value high">{{ severitySummary.high }}</span>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="mini-stat">
              <span class="label">中等:</span>
              <span class="value medium">{{ severitySummary.medium }}</span>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="mini-stat">
              <span class="label">低危:</span>
              <span class="value low">{{ severitySummary.low }}</span>
            </div>
          </el-col>
        </el-row>

        <!-- 筛选器 -->
        <el-form :inline="true" class="filter-form">
          <el-form-item label="严重程度">
            <el-select v-model="issueFilter.severity" placeholder="全部" clearable style="width: 120px">
              <el-option label="严重" value="critical" />
              <el-option label="高危" value="high" />
              <el-option label="中等" value="medium" />
              <el-option label="低危" value="low" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="issueFilter.status" placeholder="全部" clearable style="width: 120px">
              <el-option label="待处理" value="open" />
              <el-option label="已确认" value="acknowledged" />
              <el-option label="已解决" value="resolved" />
              <el-option label="已忽略" value="ignored" />
            </el-select>
          </el-form-item>
          <el-form-item label="数据源">
            <el-select
              v-model="issueFilter.clusterId"
              placeholder="全部"
              clearable
              filterable
              style="width: 160px"
            >
              <el-option
                v-for="c in clustersList"
                :key="c.id"
                :label="c.clusterName"
                :value="c.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="Schema">
            <el-select
              v-model="issueFilter.dbName"
              placeholder="全部"
              clearable
              filterable
              style="width: 180px"
            >
              <el-option
                v-for="schema in schemaOptions"
                :key="schema"
                :label="schema"
                :value="schema"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="表">
            <el-select
              v-model="issueFilter.tableName"
              placeholder="全部"
              clearable
              filterable
              :disabled="!issueFilter.dbName"
              style="width: 200px"
            >
              <el-option
                v-for="tableName in tableOptions"
                :key="tableName"
                :label="tableName"
                :value="tableName"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="applyIssueFilter">筛选</el-button>
          </el-form-item>
        </el-form>

        <!-- 问题表格 -->
        <el-table
          v-loading="issuesLoading"
          :data="sortedIssuesList"
          stripe
          max-height="500"
          style="width: 100%"
          @sort-change="handleIssueSortChange"
        >
          <el-table-column prop="issueType" label="问题类型" width="140" sortable="custom">
            <template #default="{ row }">
              {{ getIssueTypeText(row.issueType) }}
            </template>
          </el-table-column>
          <el-table-column prop="severity" label="严重程度" width="100" sortable="custom">
            <template #default="{ row }">
              <el-tag :type="getSeverityType(row.severity)" size="small">
                {{ getSeverityText(row.severity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="clusterId" label="数据源" width="160" show-overflow-tooltip sortable="custom">
            <template #default="{ row }">
              {{ getClusterName(row.clusterId) }}
            </template>
          </el-table-column>
          <el-table-column prop="dbName" label="Schema" width="140" show-overflow-tooltip sortable="custom" />
          <el-table-column prop="resourceName" label="资源名称" width="180" show-overflow-tooltip sortable="custom" />
          <el-table-column prop="issueDescription" label="问题描述" min-width="200" show-overflow-tooltip />
          <el-table-column prop="currentValue" label="当前值" width="120" show-overflow-tooltip sortable="custom" />
          <el-table-column prop="expectedValue" label="期望值" width="130" show-overflow-tooltip sortable="custom" />
          <el-table-column prop="status" label="状态" width="100" sortable="custom">
            <template #default="{ row }">
              <el-tag :type="getIssueStatusType(row.status)" size="small">
                {{ getIssueStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdTime" label="发现时间" width="170" sortable="custom">
            <template #default="{ row }">
              {{ formatDateTime(row.createdTime) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="280" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                size="small"
                @click="handleViewIssueSuggestion(row)"
              >
                查看建议
              </el-button>
              <el-button
                link
                type="primary"
                size="small"
                v-if="canViewFixPlan(row)"
                @click="handleViewFixPlan(row)"
              >
                查看修复方案
              </el-button>
              <el-button
                link
                type="success"
                size="small"
                v-if="row.status === 'open'"
                @click="handleUpdateIssueStatus(row, 'resolved')"
              >
                标记已解决
              </el-button>
              <el-button
                link
                type="warning"
                size="small"
                v-if="canAutoFix(row)"
                :loading="!!fixingIssueMap[row.id]"
                @click="handleFixIssue(row)"
              >
                一键修复
              </el-button>
              <el-button
                link
                type="info"
                size="small"
                v-if="row.status === 'open'"
                @click="handleUpdateIssueStatus(row, 'ignored')"
              >
                忽略
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <template #footer>
        <el-button @click="issuesDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 问题建议对话框 -->
    <el-dialog
      v-model="suggestionDialogVisible"
      title="修复建议"
      width="600px"
    >
      <div v-if="currentIssue">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="资源名称">{{ currentIssue.resourceName }}</el-descriptions-item>
          <el-descriptions-item label="问题描述">{{ currentIssue.issueDescription }}</el-descriptions-item>
          <el-descriptions-item label="当前值">{{ currentIssue.currentValue }}</el-descriptions-item>
          <el-descriptions-item label="期望值">{{ currentIssue.expectedValue }}</el-descriptions-item>
          <el-descriptions-item label="修复建议">
            <el-alert type="info" :closable="false" style="margin: 0">
              {{ currentIssue.suggestion }}
            </el-alert>
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <template #footer>
        <el-button @click="suggestionDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="fixPlanDialogVisible"
      title="修复方案"
      width="820px"
    >
      <div v-loading="fixPlanLoading">
        <div v-if="currentFixPlan">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="策略">{{ currentFixPlan.strategy || '-' }}</el-descriptions-item>
            <el-descriptions-item label="可自动修复">{{ currentFixPlan.autoFixable ? '是' : '否' }}</el-descriptions-item>
            <el-descriptions-item label="分区模式">{{ isTabletFixPlan ? (currentFixPlan.current?.partitionMode || '-') : '-' }}</el-descriptions-item>
            <el-descriptions-item label="模式建议">{{ isTabletFixPlan ? (currentFixPlan.modeRecommendation || '-') : '-' }}</el-descriptions-item>
          </el-descriptions>

          <el-alert
            type="info"
            :closable="false"
            style="margin-top: 12px"
            title="官方建议"
            :description="(currentFixPlan.officialRecommendations || []).join('；')"
          />

          <el-descriptions :column="2" border style="margin-top: 12px">
            <template v-if="isReplicaFixPlan">
              <el-descriptions-item label="当前副本数">{{ currentFixPlan.current?.replicaNum ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="目标副本数">{{ currentFixPlan.target?.replicaNum ?? '-' }}</el-descriptions-item>
            </template>
            <template v-else>
              <el-descriptions-item label="当前 Tablet 数">{{ currentFixPlan.current?.tabletCount ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="目标 Tablet 数">{{ currentFixPlan.target?.targetTabletCount ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="当前 Bucket">{{ currentFixPlan.current?.bucketNum ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="目标 Bucket">{{ currentFixPlan.target?.targetBucketNum ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="当前平均 Tablet 大小">{{ currentFixPlan.current?.avgTabletSizeReadable ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="推荐大小区间">{{ currentFixPlan.target?.tabletSizeRange ?? '-' }}</el-descriptions-item>
            </template>
          </el-descriptions>

          <el-alert
            type="warning"
            :closable="false"
            style="margin-top: 12px"
            title="解决方案"
            :description="(currentFixPlan.solutions || []).join('；')"
          />

          <el-input
            style="margin-top: 12px"
            type="textarea"
            :rows="Math.min(10, Math.max(3, (currentFixPlan.sqls || []).length + 1))"
            readonly
            :model-value="(currentFixPlan.sqls || []).join('\n')"
          />
        </div>
      </div>

      <template #footer>
        <el-button @click="fixPlanDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Refresh,
  VideoPlay,
  Warning,
  CircleClose,
  WarningFilled,
  InfoFilled
} from '@element-plus/icons-vue'
import {
  runInspection,
  getInspectionRecords,
  getInspectionOverview,
  getInspectionRules,
  getInspectionIssues,
  getIssueFixPlan,
  fixInspectionIssue,
  updateRuleEnabled,
  updateIssueStatus
} from '@/api/inspection'
import { dorisClusterApi } from '@/api/doris'
import { tableApi } from '@/api/table'

// 数据定义
const loading = ref(false)
const runningInspection = ref(false)
const recordsList = ref([])
const overview = ref({})
const rulesLoading = ref(false)
const rulesList = ref([])
const ruleUpdating = reactive({})
const clustersList = ref([])

// 对话框控制
const issuesDialogVisible = ref(false)
const suggestionDialogVisible = ref(false)
const fixPlanDialogVisible = ref(false)
const currentRecordId = ref(null)
const issuesLoading = ref(false)
const issuesList = ref([])
const currentIssue = ref(null)
const currentFixPlan = ref(null)
const fixPlanLoading = ref(false)
const schemaOptions = ref([])
const tableOptions = ref([])
const fixingIssueMap = reactive({})
const issueSort = reactive({
  prop: 'createdTime',
  order: 'descending'
})

// 问题筛选
const issueFilter = reactive({
  severity: null,
  status: null,
  clusterId: null,
  dbName: '',
  tableName: ''
})

const severitySummary = computed(() => {
  const summary = { critical: 0, high: 0, medium: 0, low: 0 }
  for (const issue of issuesList.value || []) {
    if (!issue?.severity) continue
    if (summary[issue.severity] !== undefined) {
      summary[issue.severity] += 1
    }
  }
  return summary
})

const severityRank = {
  critical: 4,
  high: 3,
  medium: 2,
  low: 1
}

const issueStatusRank = {
  open: 4,
  acknowledged: 3,
  resolved: 2,
  ignored: 1
}

const sortedIssuesList = computed(() => {
  const list = [...(issuesList.value || [])]
  const prop = issueSort.prop
  const order = issueSort.order
  if (!prop || !order) return list

  const factor = order === 'ascending' ? 1 : -1
  return list.sort((a, b) => {
    const av = normalizeSortValue(getIssueSortValue(a, prop))
    const bv = normalizeSortValue(getIssueSortValue(b, prop))
    if (av < bv) return -1 * factor
    if (av > bv) return 1 * factor
    return 0
  })
})

const isReplicaFixPlan = computed(() => currentFixPlan.value?.issueType === 'replica_count')
const isTabletFixPlan = computed(() => ['tablet_count', 'tablet_size'].includes(currentFixPlan.value?.issueType))

// 页面加载
onMounted(() => {
  loadOverview()
  loadRecords()
  loadRules()
  loadClusters()
})

const loadClusters = async () => {
  try {
    const res = await dorisClusterApi.list()
    clustersList.value = res || []
  } catch (error) {
    console.error('Failed to load clusters:', error)
    clustersList.value = []
  }
}

const loadSchemaOptions = async () => {
  try {
    const res = await tableApi.listDatabases(issueFilter.clusterId || null)
    schemaOptions.value = (res || []).filter(Boolean)
  } catch (error) {
    console.error('Failed to load schema options:', error)
    schemaOptions.value = []
  }
}

const loadTableOptions = async () => {
  if (!issueFilter.dbName) {
    tableOptions.value = []
    return
  }
  try {
    const res = await tableApi.listByDatabase(
      issueFilter.dbName,
      'tableName',
      'asc',
      issueFilter.clusterId || null
    )
    tableOptions.value = Array.from(
      new Set(
        (res || [])
          .map(item => item?.tableName)
          .filter(name => !!name)
      )
    )
  } catch (error) {
    console.error('Failed to load table options:', error)
    tableOptions.value = []
  }
}

watch(
  () => issueFilter.clusterId,
  async () => {
    issueFilter.dbName = ''
    issueFilter.tableName = ''
    tableOptions.value = []
    await loadSchemaOptions()
  }
)

watch(
  () => issueFilter.dbName,
  async () => {
    issueFilter.tableName = ''
    await loadTableOptions()
  }
)

// 加载概览
const loadOverview = async () => {
  try {
    const res = await getInspectionOverview()
    overview.value = res
  } catch (error) {
    console.error('Failed to load overview:', error)
  }
}

// 加载巡检规则
const loadRules = async () => {
  rulesLoading.value = true
  try {
    const res = await getInspectionRules()
    rulesList.value = res || []
  } catch (error) {
    ElMessage.error('加载巡检规则失败: ' + error.message)
  } finally {
    rulesLoading.value = false
  }
}

// 加载巡检记录
const loadRecords = async () => {
  loading.value = true
  try {
    const res = await getInspectionRecords(20)
    recordsList.value = res || []
  } catch (error) {
    ElMessage.error('加载巡检记录失败: ' + error.message)
  } finally {
    loading.value = false
  }
}

// 加载问题列表
const loadIssues = async () => {
  if (!currentRecordId.value) return
  issuesLoading.value = true
  try {
    const params = {
      recordId: currentRecordId.value,
      severity: issueFilter.severity || undefined,
      status: issueFilter.status || undefined,
      clusterId: issueFilter.clusterId || undefined,
      dbName: issueFilter.dbName?.trim() || undefined,
      tableName: issueFilter.tableName?.trim() || undefined
    }
    const res = await getInspectionIssues(params)
    issuesList.value = res || []
  } catch (error) {
    ElMessage.error('加载问题列表失败: ' + error.message)
    issuesList.value = []
  } finally {
    issuesLoading.value = false
  }
}

// 启用/停用规则
const handleRuleEnabledChange = async (rule, enabled) => {
  if (!rule?.id) return
  if (ruleUpdating[rule.id]) return

  ruleUpdating[rule.id] = true
  try {
    await updateRuleEnabled(rule.id, { enabled })
    ElMessage.success(enabled ? '规则已启用' : '规则已停用')
  } catch (error) {
    rule.enabled = !enabled
    ElMessage.error('更新规则状态失败: ' + error.message)
  } finally {
    ruleUpdating[rule.id] = false
  }
}

// 执行巡检
const handleRunInspection = async () => {
  try {
    await ElMessageBox.confirm(
      '执行全量巡检将检查所有表和任务的合规性,可能需要较长时间,是否继续?',
      '确认执行',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    runningInspection.value = true
    await runInspection({ createdBy: 'admin' })
    ElMessage.success('巡检已启动,请稍后查看结果')

    // 3秒后刷新数据
    setTimeout(() => {
      refreshData()
    }, 3000)
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('执行巡检失败: ' + error.message)
    }
  } finally {
    runningInspection.value = false
  }
}

// 查看问题详情
const handleViewIssues = async (row) => {
  try {
    currentRecordId.value = row.id
    issueFilter.severity = null
    issueFilter.status = null
    issueFilter.clusterId = null
    issueFilter.dbName = ''
    issueFilter.tableName = ''
    schemaOptions.value = []
    tableOptions.value = []
    issuesDialogVisible.value = true
    await loadSchemaOptions()
    await loadIssues()
  } catch (error) {
    ElMessage.error('加载问题详情失败: ' + error.message)
  }
}

// 应用问题筛选
const applyIssueFilter = () => {
  loadIssues()
}

// 查看问题建议
const handleViewIssueSuggestion = (issue) => {
  currentIssue.value = issue
  suggestionDialogVisible.value = true
}

const canViewFixPlan = (issue) => {
  const fixableTypes = ['replica_count', 'tablet_count', 'tablet_size']
  return !!issue?.id && fixableTypes.includes(issue.issueType)
}

const handleViewFixPlan = async (issue) => {
  if (!issue?.id) return
  fixPlanLoading.value = true
  currentFixPlan.value = null
  fixPlanDialogVisible.value = true
  try {
    const result = await getIssueFixPlan(issue.id)
    currentFixPlan.value = result || null
  } catch (error) {
    ElMessage.error('加载修复方案失败: ' + error.message)
  } finally {
    fixPlanLoading.value = false
  }
}

// 更新问题状态
const handleUpdateIssueStatus = async (issue, newStatus) => {
  try {
    await updateIssueStatus(issue.id, {
      status: newStatus,
      resolvedBy: 'admin',
      resolutionNote: newStatus === 'resolved' ? '问题已解决' : '问题已忽略'
    })

    ElMessage.success('状态更新成功')

    // 重新加载问题列表（保留当前筛选）
    await loadIssues()

    // 刷新概览
    await loadOverview()
  } catch (error) {
    ElMessage.error('更新状态失败: ' + error.message)
  }
}

const canAutoFix = (issue) => {
  return issue?.status === 'open' && issue?.issueType === 'replica_count'
}

const handleFixIssue = async (issue) => {
  if (!issue?.id || !canAutoFix(issue)) return
  if (fixingIssueMap[issue.id]) return

  try {
    await ElMessageBox.confirm(
      `将对 ${issue.dbName || '-'}.${
        issue.resourceName || '-'
      } 执行副本数自动修复，是否继续？`,
      '确认一键修复',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    return
  }

  fixingIssueMap[issue.id] = true
  try {
    const result = await fixInspectionIssue(issue.id, { fixedBy: 'admin' })
    if (issue.issueType === 'replica_count') {
      ElMessage.success(`修复成功，目标副本数 ${result?.targetReplicaNum ?? '-'}`)
    } else {
      ElMessage.success(`修复成功，策略 ${result?.strategy || '-'}`)
    }
    await loadIssues()
    await loadOverview()
  } catch (error) {
    ElMessage.error('一键修复失败: ' + error.message)
  } finally {
    fixingIssueMap[issue.id] = false
  }
}

const handleIssueSortChange = ({ prop, order }) => {
  issueSort.prop = prop
  issueSort.order = order
}

// 刷新数据
const refreshData = () => {
  loadOverview()
  loadRecords()
  loadRules()
}

// 工具函数
const getIssueSortValue = (issue, prop) => {
  if (!issue) return ''
  switch (prop) {
    case 'issueType':
      return getIssueTypeText(issue.issueType || '')
    case 'severity':
      return severityRank[issue.severity] || 0
    case 'clusterId':
      return getClusterName(issue.clusterId)
    case 'status':
      return issueStatusRank[issue.status] || 0
    case 'createdTime':
      return issue.createdTime || ''
    default:
      return issue[prop] ?? ''
  }
}

const normalizeSortValue = (value) => {
  if (value === null || value === undefined) return ''
  if (typeof value === 'number') return value
  const numeric = Number(value)
  if (!Number.isNaN(numeric) && String(value).trim() !== '') {
    return numeric
  }
  return String(value).toLowerCase()
}

const getInspectionTypeText = (type) => {
  const typeMap = {
    'full': '全量巡检',
    'quick': '快速巡检',
    'custom': '自定义'
  }
  return typeMap[type] || type
}

const getTriggerTypeText = (type) => {
  const typeMap = {
    'manual': '手动触发',
    'schedule': '定时调度'
  }
  return typeMap[type] || type
}

const getStatusType = (status) => {
  const statusMap = {
    'running': 'primary',
    'completed': 'success',
    'failed': 'danger'
  }
  return statusMap[status] || 'info'
}

const getStatusText = (status) => {
  const statusMap = {
    'running': '运行中',
    'completed': '已完成',
    'failed': '失败'
  }
  return statusMap[status] || status
}

const getSeverityType = (severity) => {
  const severityMap = {
    'critical': 'danger',
    'high': 'warning',
    'medium': 'info',
    'low': ''
  }
  return severityMap[severity] || ''
}

const getSeverityText = (severity) => {
  const severityMap = {
    'critical': '严重',
    'high': '高危',
    'medium': '中等',
    'low': '低危'
  }
  return severityMap[severity] || severity
}

const getIssueTypeText = (type) => {
  const typeMap = {
    'table_naming': '表命名规范',
    'replica_count': '副本数检查',
    'tablet_count': 'Tablet数量',
    'tablet_size': 'Tablet大小',
    'table_owner': '表负责人',
    'table_comment': '表注释',
    'task_failure': '任务失败',
    'task_schedule': '任务调度',
    'table_layer': '数据层级',
    'data_freshness': '数据新鲜度',
    'data_volume_spike': '数据量异常',
    'service_health': '服务健康检查',
    'doris_node_resources': 'Doris节点资源',
    'orphan_tables': '孤立表检查',
    'deprecated_tables': '废弃表检查'
  }
  return typeMap[type] || type
}

const getIssueStatusType = (status) => {
  const statusMap = {
    'open': 'danger',
    'acknowledged': 'warning',
    'resolved': 'success',
    'ignored': 'info'
  }
  return statusMap[status] || ''
}

const getIssueStatusText = (status) => {
  const statusMap = {
    'open': '待处理',
    'acknowledged': '已确认',
    'resolved': '已解决',
    'ignored': '已忽略'
  }
  return statusMap[status] || status
}

const formatDateTime = (datetime) => {
  if (!datetime) return '-'
  return datetime
}

const formatDuration = (seconds) => {
  if (!seconds || seconds === 0) return '-'
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  const remainingSeconds = seconds % 60
  return `${minutes}m ${remainingSeconds}s`
}

const getClusterName = (clusterId) => {
  if (!clusterId) return '-'
  const cluster = clustersList.value?.find(c => c.id === clusterId)
  return cluster?.clusterName || String(clusterId)
}
</script>

<style scoped>
.inspection-view {
  padding: 6px;
}

.header-card,
.rules-card,
.records-card {
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  gap: 10px;
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
}

.stat-icon.total {
  background: #fff7e6;
  color: #fa8c16;
}

.stat-icon.critical {
  background: #fff1f0;
  color: #f5222d;
}

.stat-icon.high {
  background: #fff7e6;
  color: #fa8c16;
}

.stat-icon.medium {
  background: #e6f7ff;
  color: #1890ff;
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 28px;
  font-weight: bold;
  line-height: 1;
  margin-bottom: 5px;
}

.stat-label {
  font-size: 14px;
  color: #666;
}

.records-card {
  margin-top: 20px;
}

.rules-card {
  margin-top: 20px;
}

.issue-count-warning {
  color: #f5222d;
  font-weight: bold;
}

.mini-stat {
  padding: 10px;
  background: #f8fafc;
  border-radius: 8px;
  text-align: center;
}

.mini-stat .label {
  font-size: 14px;
  color: #666;
  margin-right: 5px;
}

.mini-stat .value {
  font-size: 20px;
  font-weight: bold;
}

.mini-stat .value.critical {
  color: #f5222d;
}

.mini-stat .value.high {
  color: #fa8c16;
}

.mini-stat .value.medium {
  color: #1890ff;
}

.mini-stat .value.low {
  color: #52c41a;
}

.filter-form {
  margin-bottom: 15px;
}
</style>
