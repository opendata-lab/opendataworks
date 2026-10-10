<template>
  <div v-loading="state.accessLoading" class="meta-section meta-section-fill">
    <section class="section-block section-fill">
      <div class="section-header">
        <div class="access-title-row">
          <div class="section-title">访问概况</div>
          <el-tag
            v-if="accessSyncMeta.label"
            :type="accessSyncMeta.type"
            size="small"
            effect="plain"
          >
            {{ accessSyncMeta.label }}
          </el-tag>
        </div>
        <div class="section-actions">
          <el-button size="small" :disabled="!state.table?.id || state.accessLoading" @click="refreshAccess">
            刷新
          </el-button>
        </div>
      </div>

      <el-scrollbar class="meta-scroll access-scroll">
        <template v-if="state.accessStats">
          <el-alert
            v-if="state.accessStats.note"
            :title="state.accessStats.note"
            type="warning"
            show-icon
            :closable="false"
            class="access-note"
          />

          <div class="metrics-grid">
            <div v-for="metric in accessMetrics.slice(0, 4)" :key="metric.label" class="metric-card">
              <div class="metric-label">{{ metric.label }}</div>
              <div class="metric-value">{{ metric.value }}</div>
            </div>
          </div>

          <dl class="audit-properties"><div v-for="metric in accessMetrics.slice(4)" :key="metric.label"><dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd></div></dl>
          <div class="section-divider"></div>

          <div class="section-header small">
            <span>近{{ state.accessStats.trendDays || 14 }}天访问趋势</span>
          </div>
          <el-table :data="state.accessStats.trend || []" border size="small" class="access-table">
            <el-table-column prop="date" label="日期" min-width="120" />
            <el-table-column prop="accessCount" label="访问次数" width="120" />
          </el-table>

          <div class="section-divider"></div>

          <div class="section-header small">
            <span>活跃用户 Top{{ (state.accessStats.topUsers || []).length }}</span>
          </div>
          <el-table :data="state.accessStats.topUsers || []" border size="small" class="access-table">
            <el-table-column prop="userId" label="用户" min-width="80" show-overflow-tooltip />
            <el-table-column prop="accessCount" label="次数" width="55" />
            <el-table-column label="最近访问" min-width="145">
              <template #default="{ row }">
                {{ formatDateTime(row.lastAccessTime) }}
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else :description="state.accessError || '暂无访问数据'" :image-size="60" />
      </el-scrollbar>
    </section>
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'

// P2-2 F17d：右侧面板「访问情况」tab pane 从 DataStudioRightPanel.vue 抽出。
// 共享脚手架样式由父组件的 .meta-tabs :deep() 提供。
const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanelAccess 需要 dataStudioCtx')
}

const {
  activeTab,
  tabStates,
  loadAccessStats,
  formatDuration,
  formatDateTime,
} = ctx

const activeTabId = computed(() => String(activeTab.value || ''))
const state = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return tabStates[id] || null
})

const formatAccessDuration = (value) => {
  if (value === null || value === undefined || value === '') return '-'
  return formatDuration(Number(value))
}

const accessMetrics = computed(() => {
  const stats = state.value?.accessStats
  if (!stats) return []
  return [
    { label: '总访问次数', value: stats.totalAccessCount ?? 0 },
    { label: `最近${stats.recentDays || 30}天`, value: stats.recentAccessCount ?? 0 },
    { label: '访问用户数', value: stats.distinctUserCount ?? 0 },
    { label: '平均耗时', value: formatAccessDuration(stats.averageDurationMs) },
    { label: '最近访问', value: formatDateTime(stats.lastAccessTime) },
    { label: '审计来源', value: stats.dorisAuditEnabled ? (stats.dorisAuditSource || '已启用') : '未启用' },
    { label: '数据截至', value: formatDateTime(stats.tableAccessLastSyncedAt) },
    {
      label: '历史覆盖',
      value: stats.tableAccessCoverageComplete
        ? '完整'
        : (stats.tableAccessCoverageStart ? `自 ${formatDateTime(stats.tableAccessCoverageStart)}` : '不足')
    }
  ]
})

const accessSyncMeta = computed(() => {
  const status = state.value?.accessStats?.tableAccessSyncStatus
  const mapping = {
    READY: { label: '已就绪', type: 'success' },
    BACKFILLING: { label: '历史回填中', type: 'warning' },
    DEGRADED: { label: '同步异常', type: 'danger' },
    DISABLED: { label: '同步已关闭', type: 'info' },
    UNAVAILABLE: { label: '统计不可用', type: 'danger' }
  }
  return mapping[status] || { label: '', type: 'info' }
})

const refreshAccess = () => {
  const tabId = activeTabId.value
  if (!tabId) return
  loadAccessStats(tabId, true)
}
</script>

<style scoped>
.section-header.small {
  font-size: 12px;
  color: var(--text-sub);
}

.access-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-divider {
  height: 1px;
  margin: 10px 0;
  background: var(--line);
}

:deep(.access-table th.el-table__cell) {
  background: #f2f7ff;
  color: var(--text-sub);
}

.access-note {
  margin-bottom: 10px;
}

.access-scroll {
  flex: 1;
  min-height: 0;
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.metric-card {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
}

.metric-label {
  font-size: 11px;
  color: var(--text-sub);
}

.metric-value {
  margin-top: 4px;
  color: var(--text);
  word-break: break-word;
}
@media (max-width: 1320px) {
  .metrics-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .metrics-grid {
    grid-template-columns: 1fr;
  }
}

.audit-properties {
  margin: 16px 0 0;
  font-size: 11px;
}
.audit-properties > div {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: 10px;
  padding: 5px 0;
}
.audit-properties dt {
  color: #8a96a5;
}
.audit-properties dd {
  margin: 0;
  color: #526174;
  overflow-wrap: anywhere;
}
.metric-value {
  font-size: 16px;
  font-weight: 500;
}
.metric-card {
  padding: 8px 0;
}
</style>
