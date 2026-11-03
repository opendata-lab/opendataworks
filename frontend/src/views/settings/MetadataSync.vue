<template>
  <div class="metadata-sync">
    <el-alert v-if="sourceLoadError" :title="sourceLoadError" type="error" :closable="false"><el-button text @click="initialize">重新加载数据源</el-button></el-alert>
    <template v-if="!showDetail">
      <div class="collection-list-heading">
        <div>
          <h2>采集管理</h2>
          <p class="section-description">管理各数据源的采集计划、可见范围与运行记录。</p>
        </div>
        <span class="list-count">共 {{ clusters.length }} 个数据源</span>
      </div>
      <div class="collection-toolbar">
        <el-input v-model="searchKeyword" clearable placeholder="搜索数据源名称 / 采集账号" aria-label="搜索采集数据源" />
        <el-select v-model="sourceTypeFilter" clearable placeholder="全部数据源类型" aria-label="数据源类型">
          <el-option v-for="type in sourceTypes" :key="type" :label="type" :value="type" />
        </el-select>
        <el-button :loading="loading" @click="initialize">刷新列表</el-button>
      </div>
      <el-table v-loading="loading" :data="filteredSources" :empty-text="clusters.length ? '没有匹配的数据源' : '尚无数据源，请先在管理中心创建'" class="collection-source-table">
        <el-table-column label="数据源" min-width="210">
          <template #default="{ row }">
            <el-button link type="primary" @click="openCollection(row)">{{ row.clusterName }}</el-button>
            <div class="source-address">{{ row.feHost }}:{{ row.fePort }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="sourceType" label="类型" min-width="100" />
        <el-table-column prop="username" label="采集账号" min-width="160" show-overflow-tooltip />
        <el-table-column label="采集计划" min-width="200">
          <template #default="{ row }">
            <span>{{ row.autoSync === 1 ? '周期采集' : '按需执行' }}</span>
            <div v-if="row.autoSync === 1" class="source-address">{{ row.syncCron }}</div>
          </template>
        </el-table-column>
        <el-table-column label="数据源状态" width="110">
          <template #default="{ row }"><el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">{{ row.status === 'active' ? '已启用' : '已停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openCollection(row)">配置采集</el-button>
            <el-button link type="primary" @click="openSyncHistory(row)">运行记录</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template v-else>
      <div class="collection-detail-heading">
        <el-button text :icon="ArrowLeft" :disabled="busy" @click="backToList">返回列表</el-button>
        <div v-if="source" class="collection-identity">
          <h2>{{ source.clusterName }}</h2>
          <el-tag size="small" type="info">{{ source.sourceType }}</el-tag>
          <el-tag v-if="source.status !== 'active'" size="small" type="warning">数据源已停用</el-tag>
        </div>
        <el-button v-if="source" class="pending-deletion-action" :disabled="busy" @click="openPendingDeletion(source)">待删除表</el-button>
      </div>
      <el-alert v-if="!source && !loading && !sourceLoadError" title="未找到该数据源，请返回列表重新选择" type="warning" :closable="false" />
      <el-tabs v-if="source" v-model="activeTab" class="collection-tabs">
        <el-tab-pane label="基本信息" name="basic" :disabled="busy">
          <section aria-labelledby="source-title" class="collection-section">
            <div class="section-heading">
              <h3 id="source-title">数据源信息</h3>
              <router-link :to="{ path: '/settings', query: { tab: 'datasource', sourceId: source.id } }">修改数据源</router-link>
            </div>
            <el-descriptions :column="2" class="source-summary">
              <el-descriptions-item label="数据源名称">{{ source.clusterName }}</el-descriptions-item>
              <el-descriptions-item label="数据源类型">{{ source.sourceType }}</el-descriptions-item>
              <el-descriptions-item label="连接地址">{{ source.feHost }}:{{ source.fePort }}</el-descriptions-item>
              <el-descriptions-item label="采集账号">{{ source.username }}</el-descriptions-item>
            </el-descriptions>
            <p class="scope-note">预览、比对和采集均使用数据源配置账号。SQL 工作区使用你的个人映射账号，可见范围可能不同。</p>
          </section>
          <section class="collection-section" aria-labelledby="schedule-title">
            <h3 id="schedule-title">采集计划</h3>
            <el-form label-width="100px" :disabled="busy" @submit.prevent="submitConfig">
              <el-form-item label="触发方式"><el-radio-group v-model="config.autoSync"><el-radio :label="0" :value="0">按需执行</el-radio><el-radio :label="1" :value="1">周期采集</el-radio></el-radio-group></el-form-item>
              <el-form-item label="采集 Cron"><el-input v-model="config.syncCron" :disabled="config.autoSync !== 1" placeholder="0 0 2 * * ?" /><span class="scope-note cron-help">秒 分 时 日 月 周，每次采集全部可见范围</span></el-form-item>
              <el-form-item><el-button type="primary" :loading="saving" :disabled="busy" @click="submitConfig">保存采集配置</el-button></el-form-item>
            </el-form>
          </section>
        </el-tab-pane>
        <el-tab-pane label="数据范围" name="scope" :disabled="busy">
          <div class="scope-toolbar">
            <span>采集账号 <strong>{{ source.username }}</strong></span>
            <el-button :disabled="busy" @click="refreshScope">刷新可见范围</el-button>
          </div>
          <section class="collection-section" aria-labelledby="scope-title">
            <h3 id="scope-title">本次采集范围</h3>
            <p class="section-description">选择本次手动采集的对象。此处的库表选择不会修改周期采集范围。</p>
            <el-form label-width="100px" :disabled="busy" @submit.prevent>
              <el-form-item label="采集范围">
                <el-radio-group v-model="scopeType" @change="auditResult = null">
                  <el-radio value="all" label="all">全部可见数据库</el-radio>
                  <el-radio value="database" label="database">指定数据库</el-radio>
                  <el-radio value="table" label="table">指定表</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="scopeType !== 'all'" label="数据库">
                <el-select :model-value="database" filterable placeholder="选择采集账号可见数据库" @change="changeDatabase">
                  <el-option v-for="name in databases" :key="name" :label="name" :value="name" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="scopeType === 'table'" label="表">
                <el-select v-model="tableName" filterable placeholder="选择采集账号可见表" @change="auditResult = null">
                  <el-option v-for="item in tables" :key="item.tableName" :label="item.tableName" :value="item.tableName" />
                </el-select>
              </el-form-item>
            </el-form>
            <el-alert v-if="unavailable" :title="unavailable" :type="scopeLoading ? 'info' : 'warning'" :closable="false" show-icon />
            <p v-else class="scope-note">可见 {{ databases.length }} 个数据库<span v-if="database">，当前库可见 {{ tables.length }} 张表</span>。自动采集始终覆盖全部可见范围。</p>
            <div class="collection-actions">
              <el-button type="primary" :loading="running" :disabled="!!unavailable || busy" @click="confirmSync">开始采集</el-button>
              <el-button :loading="auditLoading" :disabled="!!unavailable || busy || scopeType !== 'all'" @click="auditMetadata">比对全源元数据</el-button>
            </div>
            <el-alert v-if="result" :type="result.status === 'SUCCESS' ? 'success' : result.status === 'FAILED' ? 'error' : 'warning'" :title="resultTitle" :closable="false" show-icon class="collection-result">
              <p v-if="result.message">{{ result.message }}</p>
              <el-button text @click="openSyncHistory(source)">查看采集历史</el-button>
            </el-alert>
            <p v-if="auditResult" class="audit-summary">最近比对 {{ auditResult.time }}：{{ auditResult.totalDifferences || 0 }} 处结构差异。
              <el-button text @click="differenceDialogVisible = true">查看比对详情</el-button>
              <span v-if="auditResult.errors?.length">{{ auditResult.errors.join('；') }}</span>
            </p>
          </section>
        </el-tab-pane>
        <el-tab-pane label="运行记录" name="history" :disabled="busy">
          <div class="history-toolbar">
            <span class="section-description">查看每次采集的状态、范围、耗时和变更明细。</span>
            <el-button :loading="syncHistoryLoading" @click="loadSyncHistory">刷新记录</el-button>
          </div>
          <el-alert v-if="syncHistoryError" :title="syncHistoryError" type="error" :closable="false" show-icon />
          <el-table v-if="!syncHistoryError" v-loading="syncHistoryLoading" :data="syncHistoryList" border style="width: 100%">
            <el-table-column prop="startedAt" label="时间" min-width="170">
              <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
            </el-table-column>
            <el-table-column label="触发方式" min-width="100"><template #default="{ row }">{{ triggerLabels[row.triggerType] || row.triggerType }}</template></el-table-column>
            <el-table-column label="范围" min-width="90"><template #default="{ row }">{{ scopeLabels[row.scopeType] || row.scopeType }}</template></el-table-column>
            <el-table-column prop="scopeTarget" label="目标" min-width="180" show-overflow-tooltip />
            <el-table-column prop="status" label="状态" min-width="100">
              <template #default="{ row }">
                <el-tag :type="getSyncStatusTagType(row.status)" size="small">{{ statusLabels[row.status] || row.status }}</el-tag>
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

          <div v-if="!syncHistoryError" class="history-pagination">
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
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-dialog
      v-model="differenceDialogVisible"
      title="元数据差异详情"
      width="70%"
      :close-on-click-modal="false"
    >
      <div v-if="auditResult && auditResult.differences" class="difference-container">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 16px"
        >
          <template #title>
            共发现 {{ auditResult.totalDifferences }} 处差异，请仔细检查后决定是否同步
          </template>
        </el-alert>

        <el-collapse>
          <el-collapse-item
            v-for="(diff, index) in auditResult.differences"
            :key="index"
            :name="index"
          >
            <template #title>
              <div class="diff-title">
                <el-tag
                  :type="diff.type === 'NEW' ? 'success' : diff.type === 'REMOVED' ? 'danger' : 'warning'"
                  size="small"
                >
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
                    <el-tag
                      :type="row.type === 'NEW' ? 'success' : row.type === 'REMOVED' ? 'danger' : 'warning'"
                      size="small"
                    >
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
        <el-button type="success" @click="confirmSync" :loading="syncLoading">
          按当前范围采集
        </el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="syncHistoryDetailVisible"
      title="采集历史详情"
      width="72%"
      :close-on-click-modal="false"
    >
      <el-descriptions v-if="syncHistoryDetail" :column="2" border>
        <el-descriptions-item label="运行ID">{{ syncHistoryDetail.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getSyncStatusTagType(syncHistoryDetail.status)" size="small">{{ statusLabels[syncHistoryDetail.status] || syncHistoryDetail.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="触发">{{ triggerLabels[syncHistoryDetail.triggerType] || syncHistoryDetail.triggerType }}</el-descriptions-item>
        <el-descriptions-item label="范围">{{ scopeLabels[syncHistoryDetail.scopeType] || syncHistoryDetail.scopeType }} {{ syncHistoryDetail.scopeTarget || '-' }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ formatDateTime(syncHistoryDetail.startedAt) }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ formatDateTime(syncHistoryDetail.finishedAt) }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ formatDuration(syncHistoryDetail.durationMs) }}</el-descriptions-item>
        <el-descriptions-item label="错误数">{{ syncHistoryDetail.errorCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="错误摘要" :span="2">{{ syncHistoryDetail.errorSummary || '-' }}</el-descriptions-item>
        <el-descriptions-item label="错误明细" :span="2">
          <pre class="error-detail">{{ formatErrorDetails(syncHistoryDetail.errorDetails) }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="变更详情" :span="2">
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
import { computed, reactive, ref, watch } from 'vue'
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { dorisClusterApi } from '@/api/doris'
import { tableApi } from '@/api/table'
import { useMetadataCollection } from './composables/useMetadataCollection'

const route = useRoute()
const router = useRouter()
const showDetail = computed(() => Boolean(route.query.sourceId))
const activeTab = computed({
  get: () => ['basic', 'scope', 'history'].includes(route.query.tab)
    ? route.query.tab : route.query.database || route.query.tableName ? 'scope' : 'basic',
  set: tab => router.replace({ path: '/metadata-sync', query: { ...route.query, tab } })
})
const searchKeyword = ref('')
const sourceTypeFilter = ref('')
const clusters = ref([])
const sourceTypes = computed(() => [...new Set(clusters.value.map(item => item.sourceType).filter(Boolean))])
const filteredSources = computed(() => clusters.value.filter(item => {
  const keyword = searchKeyword.value.trim().toLowerCase()
  return (!sourceTypeFilter.value || item.sourceType === sourceTypeFilter.value)
    && (!keyword || `${item.clusterName} ${item.username}`.toLowerCase().includes(keyword))
}))
function openCollection(row, tab = 'basic') {
  const target = String(route.query.sourceId) === String(row.id) ? route.query : {}
  return router.push({ path: '/metadata-sync', query: { ...target, sourceId: row.id, tab } })
}
function backToList() { return router.push('/metadata-sync') }
const loading = ref(true)
const sourceLoadError = ref('')
const auditResult = ref(null)
const auditLoading = ref(false)
const differenceDialogVisible = ref(false)
const { source, database, tableName, scopeType, databases, tables, scopeLoading, unavailable, running, saving, result, config, selectSource, selectDatabase, refreshScope, saveConfig, run } = useMetadataCollection()
onBeforeRouteUpdate(() => !running.value && !saving.value && !auditLoading.value)
const busy = computed(() => running.value || saving.value || auditLoading.value || scopeLoading.value)
const resultTitle = computed(() => {
  const titles = { SUCCESS: '元数据采集成功', PARTIAL: '元数据采集部分成功', FAILED: '元数据采集失败', UNKNOWN: '采集结果待确认', UNVERIFIED: '采集结果待核实' }
  return `${titles[result.value?.status] || '采集结果待确认'}${result.value?.syncRunId ? `（运行 #${result.value.syncRunId}）` : ''}`
})
async function changeSource(id, target = {}) {
  auditResult.value = null
  await selectSource(clusters.value.find(s => String(s.id) === String(id)), target)
}
async function changeDatabase(value) { auditResult.value = null; await selectDatabase(value) }
async function confirmSync() {
  if (unavailable.value || busy.value) return
  const target = [source.value.id, scopeType.value, database.value, tableName.value].join('\u0000')
  const scope = scopeType.value === 'all' ? '全部可见数据库' : scopeType.value === 'table' ? `${database.value}.${tableName.value}` : database.value
  try { await ElMessageBox.confirm(`使用账号「${source.value.username}」采集「${scope}」的元数据？`, '确认采集', { type: 'warning', confirmButtonText: '开始采集', cancelButtonText: '取消' }) }
  catch { return }
  if (target !== [source.value?.id, scopeType.value, database.value, tableName.value].join('\u0000')) {
    ElMessage.warning('采集目标已变化，请重新确认')
    return
  }
  auditResult.value = null
  differenceDialogVisible.value = false
  await run()
}
async function submitConfig() {
  if (busy.value) return
  if (config.autoSync === 1 && !config.syncCron.trim()) { ElMessage.warning('开启自动采集时请填写 Cron'); return }
  try { await saveConfig(); ElMessage.success('采集配置已保存') } catch { /* request interceptor reports error */ }
}
async function auditMetadata() {
  if (unavailable.value || busy.value || scopeType.value !== 'all') return
  auditLoading.value = true
  try { auditResult.value = { ...await tableApi.auditMetadata(source.value.id), time: new Date().toLocaleString() } }
  catch { /* request interceptor reports error */ }
  finally { auditLoading.value = false }
}
const syncHistoryDetailVisible = ref(false)
const syncHistoryLoading = ref(false)
const syncHistoryError = ref('')
let historyRequest = 0
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

const triggerLabels = { manual: '手动', auto: '周期' }
const scopeLabels = { all: '全源', database: '数据库', table: '数据表' }
const statusLabels = { SUCCESS: '采集成功', PARTIAL: '部分成功', FAILED: '采集失败' }

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

function openSyncHistory(row) { return openCollection(row, 'history') }

watch([() => source.value?.id, activeTab], () => {
  historyRequest += 1
  syncHistoryList.value = []
  syncHistoryPager.pageNum = 1
  syncHistoryPager.total = 0
  syncHistoryDetailVisible.value = false
  syncHistoryError.value = ''
  syncHistoryLoading.value = false
  if (source.value && activeTab.value === 'history') loadSyncHistory()
})

async function loadSyncHistory() {
  const sourceId = source.value?.id
  if (!sourceId || activeTab.value !== 'history') return
  const request = ++historyRequest
  syncHistoryLoading.value = true
  syncHistoryError.value = ''
  try {
    const data = await dorisClusterApi.getSyncHistory(sourceId, {
      pageNum: syncHistoryPager.pageNum,
      pageSize: syncHistoryPager.pageSize
    })
    if (request !== historyRequest) return
    syncHistoryList.value = Array.isArray(data?.records) ? data.records : []
    syncHistoryPager.total = Number(data?.total || 0)
  } catch (error) {
    if (request !== historyRequest) return
    syncHistoryList.value = []
    syncHistoryPager.total = 0
    syncHistoryError.value = `加载运行记录失败：${error.message || '请稍后重试'}`
  } finally {
    if (request === historyRequest) syncHistoryLoading.value = false
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
  const sourceId = source.value?.id
  if (!sourceId || !row?.id) return
  const request = historyRequest
  try {
    const detail = await dorisClusterApi.getSyncHistoryDetail(sourceId, row.id)
    if (request !== historyRequest) return
    syncHistoryDetail.value = detail
    syncHistoryDetailVisible.value = true
  } catch (error) {
    if (request !== historyRequest) return
    console.error('加载采集历史详情失败:', error)
    ElMessage.error('加载采集历史详情失败: ' + (error.message || '未知错误'))
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


async function applyRoute() {
  if (running.value || saving.value || auditLoading.value) return
  const id = route.query.sourceId
  await changeSource(id, { database: route.query.database, tableName: route.query.tableName })
}
watch([() => route.query.sourceId, () => route.query.database, () => route.query.tableName], applyRoute)
async function initialize() {
  loading.value = true
  sourceLoadError.value = ''
  try { clusters.value = await dorisClusterApi.list(); await applyRoute() }
  catch (error) { sourceLoadError.value = `加载数据源失败：${error.message || '请稍后重试'}` }
  finally { loading.value = false }
}
initialize()
</script>
<style scoped>
.metadata-sync { background: var(--odw-bg-card); padding: 20px 24px; overflow: auto; height: 100%; box-sizing: border-box; container-type: inline-size; }
.collection-list-heading, .collection-detail-heading, .collection-toolbar, .collection-actions, .section-heading, .scope-toolbar, .history-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.collection-list-heading { justify-content: space-between; margin-bottom: 20px; }
.collection-toolbar { margin-bottom: 20px; }
.collection-toolbar :deep(.el-input) { width: 280px; max-width: 100%; }
.collection-toolbar :deep(.el-select) { width: 180px; max-width: 100%; }
.collection-toolbar .el-button + .el-button, .collection-actions .el-button + .el-button { margin-left: 0; }
.list-count, .source-address, .section-description, .scope-note { color: var(--el-text-color-regular); font-size: 13px; line-height: 1.7; }
.source-address { margin-top: 4px; }
.section-description { margin: 6px 0 0; }
.collection-detail-heading { margin-bottom: 16px; }
.collection-detail-heading > .el-button:first-child { padding-left: 0; }
.collection-identity { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; min-width: 0; }
.collection-identity h2 { overflow-wrap: anywhere; }
.pending-deletion-action { margin-left: auto; }
.collection-tabs :deep(.el-tabs__header) { margin-bottom: 24px; }
.collection-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; }
.collection-section + .collection-section { margin-top: 32px; padding-top: 24px; border-top: 1px solid var(--el-border-color-lighter); }
.section-heading { justify-content: space-between; margin-bottom: 20px; }
.section-heading h3 { margin-bottom: 0; }
.section-heading a { color: var(--el-color-primary); font-size: 13px; text-decoration: none; }
.section-heading a:hover { text-decoration: underline; }
h2 { font-size: 16px; font-weight: 600; margin: 0; }
h3 { font-size: 14px; font-weight: 600; margin: 0 0 16px; }
.source-summary { width: 100%; }
.scope-note { margin: 8px 0; }
.scope-toolbar { justify-content: space-between; margin-bottom: 24px; font-size: 13px; }
.scope-toolbar strong { margin-left: 8px; font-weight: 500; }
.collection-section > .section-description { margin: -8px 0 20px; }
.el-form :deep(.el-select), .el-form :deep(.el-input) { width: 360px; max-width: 100%; }
.collection-actions { margin-top: 24px; }
.collection-result { margin-top: 20px; }
.cron-help { margin-left: 12px; }
.history-toolbar { justify-content: space-between; margin-bottom: 16px; }
.history-toolbar .section-description { margin: 0; }
.error-detail { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; }
.history-pagination { margin-top: 16px; display: flex; justify-content: flex-end; overflow-x: auto; }
.change-group { margin: 16px 0; }
.change-item { margin-top: 12px; }
.change-item-header { display: flex; align-items: center; gap: 8px; }
.change-item-summary { margin: 6px 0; }
.pair-key { margin-right: 12px; color: var(--el-text-color-secondary); }
.change-pair { line-height: 1.7; }
.diff-title { display: flex; gap: 10px; align-items: center; }
.audit-summary { font-size: 13px; }
@container (max-width: 640px) {
  .source-summary :deep(.el-descriptions__table), .source-summary :deep(tbody), .source-summary :deep(tr), .source-summary :deep(td) { display: block; width: 100%; }
  .cron-help { display: block; margin: 8px 0 0; }
  .collection-identity { flex: 1; }
  .pending-deletion-action { margin-left: 0; }
}
</style>
