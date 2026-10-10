<template>
  <div class="result-panel">
    <el-tabs v-model="tabStates[tab.id].resultTab" type="card" class="result-tabs" style="height: 100%;">
      <el-tab-pane name="info">
        <template #label>
          <span class="result-label"><el-icon><Document /></el-icon> 执行信息</span>
        </template>

        <div class="table-toolbar">
          <div class="meta-info">
            <span class="meta-item">
              <el-icon><Timer /></el-icon>
              {{ formatDuration(getLiveDurationMs(tab.id)) }}
            </span>
            <span class="meta-item">
              <el-tag v-if="tabStates[tab.id].queryLoading" size="small" type="info">运行中</el-tag>
              <el-tag v-else-if="tabStates[tab.id].queryResult.cancelled" size="small" type="warning">已停止</el-tag>
              <el-tag v-else size="small" type="success">已完成</el-tag>
            </span>
            <span v-if="tabStates[tab.id].queryResult.executedAt" class="meta-item">
              <el-icon><Clock /></el-icon>
              {{ formatDateTime(tabStates[tab.id].queryResult.executedAt) }}
            </span>
          </div>
        </div>

        <div class="result-view-container">
          <div class="table-wrapper">
            <el-empty
              v-if="!(tabStates[tab.id].queryResult.statementInfos || []).length"
              description="暂无执行信息"
              :image-size="80"
            />
            <el-table
              v-else
              :data="tabStates[tab.id].queryResult.statementInfos || []"
              stripe
              size="small"
              height="100%"
            >
              <el-table-column prop="statementIndex" label="#" width="70" />
              <el-table-column label="状态" width="120">
                <template #default="{ row }">
                  <el-tag size="small" :type="getStatementStatusTagType(row.status)">
                    {{ row.status || '-' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="110">
                <template #default="{ row }">
                  {{ formatDuration(row.durationMs || 0) }}
                </template>
              </el-table-column>
              <el-table-column prop="sqlSnippet" label="SQL 摘要" min-width="320" show-overflow-tooltip />
              <el-table-column prop="resultInfo" label="结果信息" min-width="220" show-overflow-tooltip />
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane
        v-for="(resultSet, idx) in getDisplayResultSets(tab.id)"
        :key="String(idx)"
        :name="`result-${idx}`"
      >
        <template #label>
          <span class="result-label"><el-icon><List /></el-icon> 结果 {{ idx + 1 }}</span>
        </template>

        <div class="table-toolbar">
          <div class="meta-info" :title="[tabStates[tab.id].queryResult.message, formatDateTime(tabStates[tab.id].queryResult.executedAt)].filter(Boolean).join(' · ')">
            <span class="meta-item execution-state" :class="{ 'is-running': tabStates[tab.id].queryLoading, 'is-stopped': tabStates[tab.id].queryResult.cancelled, 'is-error': tabStates[tab.id].queryResult.errorMessage, 'is-idle': !tabStates[tab.id].queryResult.executedAt && !tabStates[tab.id].queryLoading }"><span class="status-dot"></span>{{ tabStates[tab.id].queryLoading ? '查询中' : tabStates[tab.id].queryResult.cancelled ? '已停止' : tabStates[tab.id].queryResult.errorMessage ? '执行失败' : tabStates[tab.id].queryResult.executedAt ? '已完成' : '未执行' }}</span>
            <span class="meta-item">{{ getResultSetCountText(resultSet) }}</span>
            <span class="meta-item">{{ formatDuration(getLiveDurationMs(tab.id)) }}</span>
            <span v-if="resultSet.hasMore" class="meta-item truncate"><el-icon><Warning /></el-icon>已截断</span>
            <span v-if="tabStates[tab.id].queryCancelable && !tabStates[tab.id].queryLoading" class="meta-item truncate">仍可停止</span>
          </div>
          <div class="export-actions">
            <div v-if="isResultSetType(resultSet)" class="result-view-switch" role="group" aria-label="结果展示方式">
              <el-button size="small" text :class="{ 'is-selected': (tabStates[tab.id].resultViewTabs?.[idx] || 'table') === 'table' }" :aria-pressed="(tabStates[tab.id].resultViewTabs?.[idx] || 'table') === 'table'" @click="tabStates[tab.id].resultViewTabs[idx] = 'table'"><el-icon><Grid /></el-icon>表格</el-button>
              <el-button size="small" text :class="{ 'is-selected': tabStates[tab.id].resultViewTabs?.[idx] === 'chart' }" :aria-pressed="tabStates[tab.id].resultViewTabs?.[idx] === 'chart'" @click="tabStates[tab.id].resultViewTabs[idx] = 'chart'"><el-icon><TrendCharts /></el-icon>图表</el-button>
            </div>
            <span class="result-action-divider"></span>
            <el-button size="small" text aria-label="导出 CSV" title="导出 CSV" :disabled="!isResultSetType(resultSet) || !(resultSet.rows || []).length" @click="exportResult(tab.id, idx)"><el-icon><Download /></el-icon></el-button>
          </div>
        </div>

        <div v-if="tabStates[tab.id].queryResult.errorMessage" class="result-message">
          <el-alert
            type="error"
            :closable="false"
            show-icon
            :title="tabStates[tab.id].queryResult.errorMessage"
          />
        </div>
        <div
          v-else-if="tabStates[tab.id].queryResult.cancelled && tabStates[tab.id].queryResult.message"
          class="result-message"
        >
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            :title="tabStates[tab.id].queryResult.message"
          />
        </div>

          <div class="result-view-container">
          <div
            v-show="!isResultSetType(resultSet) || (tabStates[tab.id].resultViewTabs?.[idx] || 'table') === 'table'"
            class="table-wrapper"
          >
            <div v-if="!isResultSetType(resultSet)" class="statement-result-card">
              <el-alert
                :type="getResultSetAlertType(resultSet)"
                :closable="false"
                show-icon
                :title="resultSet.message || '语句执行完成'"
              />
            </div>
            <el-empty
              v-else-if="!(resultSet.rows || []).length && !tabStates[tab.id].queryLoading"
              description="暂无数据"
              :image-size="80"
            />
            <DataStudioResultGrid
              v-else
              :columns="resultSet.columns || []"
              :rows="resultSet.rows || []"
              :row-key-prefix="getResultRowKeyPrefix(tab.id, idx)"
            />
            </div>

          <div
            v-if="isResultSetType(resultSet)"
            v-show="(tabStates[tab.id].resultViewTabs?.[idx] || 'table') === 'chart'"
            class="result-chart"
          >
              <div class="chart-grid">
                <div class="chart-config">
                  <div class="config-title">图表类型</div>
                    <div class="chart-type">
                      <el-radio-group v-model="tabStates[tab.id].charts[idx].type" size="small">
                        <el-radio-button value="bar">柱状图</el-radio-button>
                        <el-radio-button value="line">折线图</el-radio-button>
                        <el-radio-button value="pie">饼图</el-radio-button>
                      </el-radio-group>
                    </div>
                  <div class="config-title">
                    {{ tabStates[tab.id].charts[idx].type === 'pie' ? '分类字段' : 'X 轴字段' }}
                  </div>
                  <el-select
                    v-model="tabStates[tab.id].charts[idx].xAxis"
                    size="small"
                    placeholder="选择字段"
                    class="config-select"
                    :disabled="!(resultSet.columns || []).length"
                  >
                    <el-option
                      v-for="col in (resultSet.columns || [])"
                      :key="col"
                      :label="col"
                      :value="col"
                    />
                  </el-select>
                  <div class="config-title">
                    {{ tabStates[tab.id].charts[idx].type === 'pie' ? '数值字段' : 'Y 轴字段' }}
                  </div>
                  <el-select
                    v-model="tabStates[tab.id].charts[idx].yAxis"
                    size="small"
                    multiple
                    collapse-tags
                    placeholder="选择数值字段"
                    class="config-select"
                    :disabled="!(resultSet.columns || []).length"
                  >
                    <el-option
                      v-for="col in getNumericColumns(tab.id, idx)"
                      :key="col"
                      :label="col"
                      :value="col"
                    />
                  </el-select>
                  <div class="hint">配置变更后自动刷新</div>
                </div>
                <div class="chart-canvas">
                  <div :ref="(el) => setChartRef(tab.id, idx, el)" class="chart-inner"></div>
                  <div v-if="!(resultSet.rows || []).length" class="chart-empty">暂无数据</div>
                  <div v-else-if="!canRenderChart(tab.id, idx)" class="chart-empty">
                    请选择字段并执行查询
                  </div>
                </div>
              </div>
            </div>
          </div>
      </el-tab-pane>

      <el-tab-pane name="history">
        <template #label>
          <span class="result-label"><el-icon><Clock /></el-icon> 查询历史</span>
        </template>
        <div class="history-panel">
          <el-table
            v-loading="historyLoading"
            :data="historyData"
            size="small"
            height="100%"
          >
            <el-table-column prop="sqlText" label="SQL" min-width="220" show-overflow-tooltip />
            <el-table-column prop="databaseName" label="数据库" width="120" />
            <el-table-column prop="clusterId" label="连接" width="100" />
            <el-table-column label="执行时间" width="160">
              <template #default="{ row }">
                {{ formatDateTime(row.executedAt || row.createdAt) }}
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="100">
              <template #default="{ row }">
                {{ formatDuration(row.durationMs) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="applyHistory(row, tab.id)">
                  填入
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div class="history-pagination">
          <el-pagination
            v-model:current-page="historyPager.pageNum"
            v-model:page-size="historyPager.pageSize"
            :page-sizes="[10, 15, 30, 50]"
            layout="total, sizes, prev, pager, next"
            :total="historyPager.total"
            background
            small
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { inject } from 'vue'
import {
  Document,
  Timer,
  Clock,
  List,
  Warning,
  Grid,
  TrendCharts, Download
} from '@element-plus/icons-vue'
import DataStudioResultGrid from './DataStudioResultGrid.vue'
import { formatDuration, formatDateTime } from '../tableFormat'

// P2-2 F16a：查询结果面板从 DataStudioNew.vue 抽出。
// 数据与行为通过 dataStudioQueryCtx 注入，键集合见 DataStudioNew.vue 的 provide。
defineProps({
  tab: {
    type: Object,
    required: true,
  },
})

const ctx = inject('dataStudioQueryCtx')
if (!ctx) {
  throw new Error('DataStudioResultPanel 需要 dataStudioQueryCtx')
}

const {
  tabStates,
  getLiveDurationMs,
  getStatementStatusTagType,
  getDisplayResultSets,
  isResultSetType,
  getResultSetCountText,
  getResultSetAlertType,
  exportResult,
  getResultRowKeyPrefix,
  applyHistory,
  historyData,
  historyPager,
  historyLoading,
  getNumericColumns,
  setChartRef,
  canRenderChart,
} = ctx
</script>

<style scoped>
.result-panel {
  height: 100%;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.result-tabs {
  flex: 1;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: none;
  box-shadow: none;
}

:deep(.result-tabs .el-tabs__content) {
  flex: 1;
  padding: 0 !important;
  overflow: hidden;
  position: relative;
  min-height: 0;
}

:deep(.result-tabs .el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.result-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.table-toolbar {
  background-color: #fff;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.result-message {
  padding: 8px 12px;
  background-color: #fff;
  border-bottom: 1px solid #ebeef5;
}

.meta-message {
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #606266;
}

.meta-info {
  display: flex;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.export-actions {
  display: flex;
  align-items: center;
}

.truncate {
  color: #e6a23c;
}

.result-view-container {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.result-view-switch {
  flex-shrink: 0;
}

.view-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.result-chart {
  flex: 1;
  min-height: 0;
  background: #fff;
}

.table-wrapper {
  flex: 1;
  min-height: 0;
  padding: 0;
  background: #fff;
  overflow: hidden;
}

.table-wrapper :deep(.el-table) {
  height: 100%;
}

.history-panel {
  flex: 1;
  min-height: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.history-panel :deep(.el-table) {
  height: 100%;
}

.history-pagination {
  padding: 8px 12px;
  border-top: 1px solid #eef1f6;
  display: flex;
  justify-content: flex-end;
}

.chart-grid {
  display: flex;
  height: 100%;
  min-height: 0;
}

.chart-config {
  border-right: 1px solid #eef1f6;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.config-title {
  font-size: 12px;
  font-weight: 600;
  color: #1f2f3d;
}

.config-select {
  width: 100%;
}

.hint {
  font-size: 12px;
  color: #94a3b8;
}

.chart-canvas {
  flex: 1;
  position: relative;
  min-height: 0;
}

.chart-inner {
  width: 100%;
  height: 100%;
}

.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
}
.table-toolbar {
  gap: 8px;
  flex-wrap: wrap;
}
.meta-info,
.export-actions {
  flex-wrap: wrap;
  gap: 8px;
}

.table-toolbar {
  min-height: 32px;
  box-sizing: border-box;
  padding: 4px 10px;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
}
.meta-info {
  font-size: 11px;
  color: #64748b;
  gap: 12px;
}
.execution-state {
  color: #475569;
}
.status-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #4e9e7c;
  margin-right: 2px;
}
.is-idle .status-dot {
  background: #94a3b8;
}
.is-running .status-dot {
  background: #4587d1;
}
.is-stopped .status-dot {
  background: #c99445;
}
.is-error .status-dot {
  background: #d45d5d;
}
.export-actions {
  gap: 5px;
  flex-shrink: 0;
}
.export-actions :deep(.el-button) {
  height: 24px;
  padding: 3px 6px;
  margin-left: 0;
  font-size: 11px;
  color: #64748b;
}
.result-view-switch {
  display: flex;
  gap: 2px;
}
.result-view-switch :deep(.is-selected) {
  color: #3f6fa7;
  background: #edf3fb;
}
.result-view-switch :deep(.el-icon) {
  margin-right: 4px;
}
.result-action-divider {
  height: 14px;
  width: 1px;
  background: #e2e8f0;
  margin: 0 3px;
}
.result-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: #e2e8f0;
}
.result-tabs :deep(.el-tabs__item) {
  font-size: 11px;
  font-weight: 400;
}
.result-tabs :deep(.el-tabs__item.is-active) {
  font-weight: 500;
  color: #3f6fa7;
}
.chart-config {
  width: 180px;
  background: #fafbfc;
}

.result-panel {
  border: 1px solid #dce2e9;
  border-left: 0;
  border-right: 0;
  box-sizing: border-box;
}
.result-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0;
  background: #f4f6f9;
  border-bottom: 1px solid #dce2e9;
}
.result-tabs :deep(.el-tabs__nav) {
  border: 0;
  border-radius: 0;
}
.result-tabs :deep(.el-tabs__nav-wrap) {
  margin-bottom: -1px;
}
.result-tabs :deep(.el-tabs__nav-wrap::after),
.result-tabs :deep(.el-tabs__active-bar) {
  display: none;
}
.result-tabs :deep(.el-tabs__item) {
  border-left: 0;
  border-right: 1px solid #dce2e9;
  border-top: 2px solid transparent;
  border-bottom: 1px solid #dce2e9;
  padding: 0 12px;
  height: 32px;
}
.result-tabs :deep(.el-tabs__item.is-active) {
  background: #fff;
  border-top-color: #517aa8;
  border-bottom-color: #fff;
}

.result-tabs {
  --el-tabs-header-height: 32px;
}
.result-tabs :deep(> .el-tabs__header) {
  height: 32px;
  min-height: 32px;
  flex: 0 0 32px;
  box-sizing: border-box;
  align-items: flex-start;
}
.result-tabs :deep(> .el-tabs__header .el-tabs__nav-wrap),
.result-tabs :deep(> .el-tabs__header .el-tabs__nav) {
  height: 32px;
  margin: 0;
}
.result-tabs :deep(> .el-tabs__header .el-tabs__item) {
  height: 32px;
  margin: 0;
}
.result-tabs :deep(> .el-tabs__header .el-tabs__item.is-active) {
  position: relative;
  z-index: 1;
  border-bottom-color: #fff;
}
</style>
