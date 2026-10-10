<template>
  <div ref="engineRoot" class="meta-section meta-section-fill">
    <el-scrollbar class="meta-scroll">
      <section class="section-block">
        <div class="section-header">
          <div class="section-title">Doris 配置</div>
          <el-tag size="small" type="warning" effect="plain">DORIS</el-tag>
        </div>

        <el-descriptions :column="engineColumns" size="small" class="meta-descriptions">
          <el-descriptions-item label="表模型">
            <span>{{ state.table.tableModel || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="主键列">
            <span>{{ state.table.keyColumns || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="分区字段">
            <span>{{ state.table.partitionColumn || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="分桶字段">
            <span>{{ state.table.distributionColumn || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="分桶数">
            <el-input-number
              v-if="state.metaEditing"
              v-model="state.metaForm.bucketNum"
              :min="1"
              size="small"
              controls-position="right"
              class="meta-input"
            />
            <span v-else>{{ state.table.bucketNum || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="副本数">
            <template v-if="state.metaEditing">
              <div class="replica-edit">
                <el-input-number
                  v-model="state.metaForm.replicaNum"
                  :min="1"
                  size="small"
                  controls-position="right"
                  class="meta-input"
                />
                <span v-if="isReplicaWarning(state.metaForm.replicaNum)" class="replica-warning">
                  <el-icon><Warning /></el-icon>
                  建议≥3
                </span>
              </div>
            </template>
            <span v-else :class="['replica-value', { 'replica-danger': isReplicaWarning(state.table.replicaNum) }]">
              <el-icon v-if="isReplicaWarning(state.table.replicaNum)" class="warning-icon"><Warning /></el-icon>
              {{ state.table.replicaNum || '-' }}
            </span>
          </el-descriptions-item>
        </el-descriptions>
      </section>

      <section v-loading="partitionsLoading" class="section-block partition-block">
        <div class="section-header">
          <div class="section-title">分区列表</div>
          <div class="section-actions">
            <span v-if="partitions.length" class="partition-count">共 {{ partitions.length }} 个分区</span>
            <el-button link type="primary" size="small" :disabled="partitionsLoading" @click="refreshPartitions">
              刷新
            </el-button>
          </div>
        </div>

        <template v-if="partitions.length">
          <el-table :data="pagedPartitions" border size="small">
            <el-table-column type="expand" width="28"><template #default="{ row }"><dl class="partition-detail"><dt>范围</dt><dd>{{ row.range || '-' }}</dd><dt>分桶 / 副本</dt><dd>{{ row.buckets ?? '-' }} / {{ row.replicationNum ?? '-' }}</dd><dt>状态</dt><dd>{{ row.state || '-' }}</dd></dl></template></el-table-column>
            <el-table-column prop="partitionName" label="分区" min-width="105" show-overflow-tooltip />
            <el-table-column label="行数" width="70" align="right"><template #default="{ row }">{{ formatRowCount(row.rowCount) }}</template></el-table-column>
            <el-table-column label="大小" width="78" align="right"><template #default="{ row }">{{ row.dataSize || '-' }}</template></el-table-column>
          </el-table>
          <el-pagination
            v-if="partitions.length > pageSize"
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            class="partition-pager"
            size="small"
            background
            layout="prev, pager, next"
            :page-sizes="[5, 10, 15]"
            :total="partitions.length"
          />
        </template>
        <el-empty v-else-if="!partitionsLoading" :description="partitionsError || '暂无分区'" :image-size="60" />
      </section>
    </el-scrollbar>
  </div>
</template>

<script setup>
import { computed, inject, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { Warning } from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'
import { paginate } from '../partitionInfo'

// 右侧面板「Doris信息」tab：Doris 配置 + 分区列表。
// 分区列表异步按需拉取 GET /v1/tables/{id}/partitions（Doris SHOW PARTITIONS），
// 结果缓存在 tab state 上，tab 来回切换不重复请求。
const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanelDoris 需要 dataStudioCtx')
}

const { activeTab, tabStates, clusterId, isReplicaWarning } = ctx

const activeTabId = computed(() => String(activeTab.value || ''))
const state = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return tabStates[id] || null
})

const engineRoot = ref(null)
const engineWidth = ref(0)
const engineColumns = computed(() => engineWidth.value >= 360 ? 2 : 1)
let engineObserver
onMounted(() => { engineObserver = new ResizeObserver(([entry]) => { engineWidth.value = entry.contentRect.width }); if (engineRoot.value) engineObserver.observe(engineRoot.value) })
onBeforeUnmount(() => engineObserver?.disconnect())
const partitionsLoading = ref(false)
const partitionsError = ref('')
const currentPage = ref(1)
const pageSize = ref(5)

const partitions = computed(() => state.value?.partitionList || [])
const pagedPartitions = computed(() => paginate(partitions.value, currentPage.value, pageSize.value))

const formatRowCount = (value) =>
  value === null || value === undefined ? '-' : Number(value).toLocaleString('en-US')

const loadPartitions = async ({ force = false } = {}) => {
  const target = state.value
  const tableId = target?.table?.id
  if (!tableId) return
  if (!force && Array.isArray(target.partitionList)) return
  if (partitionsLoading.value) return

  partitionsLoading.value = true
  partitionsError.value = ''
  try {
    // 分区查询依赖 Doris SHOW PARTITIONS；失败时就地提示，不弹全局错误
    const list = await tableApi.listPartitions(tableId, clusterId?.value ?? null, { skipErrorMessage: true })
    target.partitionList = Array.isArray(list) ? list : []
  } catch (error) {
    target.partitionList = []
    partitionsError.value = error?.message || '获取分区列表失败'
  } finally {
    partitionsLoading.value = false
  }
}

const refreshPartitions = () => loadPartitions({ force: true })

watch(
  () => state.value?.table?.id,
  () => {
    currentPage.value = 1
    partitionsError.value = ''
    loadPartitions()
  },
  { immediate: true }
)

// 页大小变化后回到第一页，避免停留在越界页
watch(pageSize, () => {
  currentPage.value = 1
})
</script>

<style scoped>
.meta-descriptions :deep(.el-descriptions__label.is-bordered-label) {
  width: 88px;
  background: #f7faff;
  color: var(--text-sub);
}
.meta-input {
  width: 130px;
}
.replica-edit {
  display: flex;
  align-items: center;
  gap: 8px;
}
.replica-warning {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  color: #e6a23c;
}
.replica-value {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.replica-danger {
  color: #f56c6c;
}
.warning-icon {
  font-size: 13px;
}
/* 区块不再有卡片边框，两块之间靠留白分隔，间距比原来大一点 */
.partition-block {
  margin-top: 16px;
}
.partition-count {
  color: var(--text-muted);
  font-size: 12px;
}
.partition-pager {
  margin-top: 10px;
  justify-content: flex-end;
}

.meta-descriptions :deep(.el-descriptions__cell) {
  padding: 6px 0 10px;
}
.meta-descriptions :deep(.el-descriptions__label) {
  display: block;
  font-size: 10px;
  color: #8a96a5;
  font-weight: 400;
  margin-bottom: 4px;
}
.meta-descriptions :deep(.el-descriptions__content) {
  font-size: 11px;
  color: #41536a;
}
.meta-descriptions :deep(.el-descriptions__body) {
  background: transparent;
}
.partition-detail {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: 6px;
  margin: 10px;
  font-size: 11px;
}
.partition-detail dt {
  color: #8a96a5;
}
.partition-detail dd {
  margin: 0;
  overflow-wrap: anywhere;
}
</style>
