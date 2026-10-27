<template>
    <div
      :ref="(el) => (data.type === 'table' ? setTableRef(data.nodeKey, el, data.table?.id) : null)"
      class="catalog-node"
      :class="[`catalog-node--${data.type}`, { 'is-detailed': displayMode === 'detailed' }]"
      :title="getNodeTitle(data)"
    >
    <div v-if="data.type === 'table' && displayMode === 'detailed'" class="table-progress-bg" :style="{ width: getProgressWidth(data.sourceId, data.schemaName, data.table) }" title="当前表行数在数据库中的相对比例"></div>
    <div class="catalog-node-row">
      <el-icon v-if="data.type === 'datasource'" :class="['node-icon', 'datasource', { 'is-inactive': isDatasourceIconInactive(data), 'is-active': isCatalogNodeActive(data) }]" :aria-label="isCatalogNodeActive(data) ? '数据源连接已激活' : '数据源连接未激活'"><Link /></el-icon>
      <el-icon v-else-if="data.type === 'schema'" :class="['node-icon', 'schema', { 'is-active': isCatalogNodeActive(data) }]" :aria-label="isCatalogNodeActive(data) ? 'Schema已激活' : 'Schema未激活'"><Coin /></el-icon>
      <el-icon
        v-else-if="data.type === 'object_group'"
        :class="['node-icon', data.objectType === 'view' ? 'view-folder' : 'table-folder']"
      >
        <View v-if="data.objectType === 'view'" />
        <Grid v-else />
      </el-icon>
      <el-icon v-else :class="['node-icon', isViewTable(data.table) ? 'view' : 'table']">
        <View v-if="isViewTable(data.table)" />
        <Grid v-else />
      </el-icon>

        <div v-if="data.type === 'table'" class="table-main">
          <div class="table-title">
            <span class="table-name" :title="data.table?.tableName">
              {{ data.table?.tableName }}
            </span>
            <el-tooltip
              v-if="isPlatformMetadataMissing(data.table)"
              content="平台元数据不存在，请先同步"
              placement="top"
            >
              <el-icon class="metadata-warning-icon"><Warning /></el-icon>
            </el-tooltip>
          </div>
          <div v-if="displayMode === 'detailed'" class="table-detail-line">
            <span class="table-comment" :title="data.table?.tableComment || '暂无注释'">{{ data.table?.tableComment || '—' }}</span>
            <div class="table-meta-tags">
              <span class="row-count" :title="`行数：${formatNumber(getTableRowCount(data.table))}`">{{ formatRowCount(getTableRowCount(data.table)) }}行</span>
              <span class="storage-size" :title="`存储：${formatStorageSize(getTableStorageSize(data.table))}`">{{ formatStorageSize(getTableStorageSize(data.table)) }}</span>
              <span :class="['lineage-count', 'upstream', { 'is-zero': getUpstreamCount(data.table?.id) === 0 }]" :title="`上游表：${getUpstreamCount(data.table?.id)} 个`">↑{{ getUpstreamCount(data.table?.id) }}</span>
              <span :class="['lineage-count', 'downstream', { 'is-zero': getDownstreamCount(data.table?.id) === 0 }]" :title="`下游表：${getDownstreamCount(data.table?.id)} 个`">↓{{ getDownstreamCount(data.table?.id) }}</span>
            </div>
          </div>
        </div>
      <span v-else class="node-name">{{ data.name }}</span>

      <div v-if="data.type === 'datasource'" class="node-right">
        <el-tag size="small" class="source-type" type="info">
          {{ data.typeDefinition?.label || data.sourceType || '未知类型' }}
        </el-tag>
        <el-tooltip content="刷新数据源" placement="top">
          <el-icon
            :class="['refresh-icon', { 'is-disabled': dbLoading || schemaLoading[String(data.sourceId)] }]"
            @click.stop="refreshDatasourceNode(data)"
          >
            <Refresh />
          </el-icon>
        </el-tooltip>
        <el-icon v-if="schemaLoading[String(data.sourceId)]" class="is-loading loading-icon"><Loading /></el-icon>
      </div>

      <div v-else-if="data.type === 'schema'" class="node-right">
        <span class="db-count">{{ getTableCount(data.sourceId, data.schemaName) }}</span>
        <el-tooltip content="刷新数据库" placement="top">
          <el-icon
            :class="[
              'refresh-icon',
              {
                'is-disabled':
                  dbLoading ||
                  schemaCountLoading[String(data.sourceId)] ||
                  tableLoading[`${String(data.sourceId)}::${data.schemaName}`]
              }
            ]"
            @click.stop="refreshSchemaNode(data)"
          >
            <Refresh />
          </el-icon>
        </el-tooltip>
        <el-icon
          v-if="schemaCountLoading[String(data.sourceId)] || tableLoading[`${String(data.sourceId)}::${data.schemaName}`]"
          class="is-loading loading-icon"
        >
          <Loading />
        </el-icon>
      </div>

      <div v-else-if="data.type === 'object_group'" class="node-right">
        <span class="db-count">{{ getTableCountByType(data.sourceId, data.schemaName, data.objectType) }}</span>
      </div>


    </div>
  </div>
</template>

<script setup>
import { inject } from 'vue'
import { Coin, Grid, Link, Loading, Refresh, View, Warning } from '@element-plus/icons-vue'
import { formatNumber, formatRowCount, formatStorageSize } from '../tableFormat'

// P2-2 F16c：目录树节点从 DataStudioNew.vue 的 el-tree scoped slot 抽出。
// 展示函数与状态通过 dataStudioCatalogCtx 注入，键集合见 DataStudioNew.vue 的 provide。
// 注意：悬停/选中态样式锚定在父组件的 .catalog-tree 上，保留在父组件的 :deep() 规则里。
defineProps({
  displayMode: { type: String, default: 'compact', validator: value => ['compact', 'detailed'].includes(value) },
  data: {
    type: Object,
    required: true,
  },
})

const ctx = inject('dataStudioCatalogCtx')
if (!ctx) {
  throw new Error('DataStudioCatalogNode 需要 dataStudioCatalogCtx')
}

const {
  dbLoading,
  schemaLoading,
  schemaCountLoading,
  tableLoading,
  setTableRef,
  getProgressWidth,
  isDatasourceIconInactive,
  isCatalogNodeActive,
  isViewTable,
  getTableCount,
  getTableCountByType,
  getTableRowCount,
  getTableStorageSize,
  refreshDatasourceNode,
  refreshSchemaNode,
  isPlatformMetadataMissing,
  getUpstreamCount,
  getDownstreamCount,
} = ctx
const getNodeTitle = (data) => {
  if (data.type === 'datasource') return `数据源连接：${data.name}（${data.typeDefinition?.label || data.sourceType || '未知类型'}）`
  if (data.type !== 'table') return data.name
  const table = data.table
  return [
    table?.tableName,
    table?.tableComment,
    `行数：${formatNumber(getTableRowCount(table))} · 存储：${formatStorageSize(getTableStorageSize(table))}`,
    `上游 ${getUpstreamCount(table?.id)} · 下游 ${getDownstreamCount(table?.id)}`,
  ].filter(Boolean).join('\n')
}
</script>

<style scoped>
.catalog-node {
  flex: 1;
  min-width: 0;
  font-size: var(--studio-font-ui);
  color: var(--catalog-text);
  position: relative;
}
.catalog-node-row {
  display: flex;
  align-items: center;
  gap: 5px;
  width: 100%;
  height: 28px;
  position: relative;
}
.node-icon {
  flex-shrink: 0;
  font-size: var(--studio-font-ui);
  color: var(--catalog-muted);
}
.datasource-logo {
  width: 12px;
  height: 12px;
  display: block;
}
.datasource-logo.is-inactive {
  filter: grayscale(1) saturate(0) opacity(0.55);
}
.node-icon.schema,
.node-icon.table,
.node-icon.table-folder {
  color: var(--catalog-icon);
}
.node-icon.view,
.node-icon.view-folder {
  color: var(--catalog-icon);
}
.node-name {
  font-size: var(--studio-font-ui);
  font-weight: 500;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
}
.source-type {
  font-size: var(--studio-font-detail);
  height: 16px;
  padding: 0 3px;
  border: 0;
  border-radius: 2px;
  background: transparent;
  color: var(--catalog-muted);
}
.db-count {
  color: var(--catalog-muted);
  font-size: var(--studio-font-detail);
  font-variant-numeric: tabular-nums;
}
.refresh-icon {
  cursor: pointer;
  color: var(--catalog-muted);
  opacity: 1;
  font-size: var(--studio-font-detail);
}
.catalog-node:hover .refresh-icon,
.catalog-node:focus-within .refresh-icon {
  opacity: 1;
}
.refresh-icon:hover {
  color: #2c5282;
}
.refresh-icon.is-disabled {
  pointer-events: none;
  color: #cbd5e1;
}
.loading-icon {
  font-size: var(--studio-font-detail);
}
.table-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}
.table-title {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  flex: 1;
}
.table-name {
  font-size: var(--studio-font-ui);
  font-weight: 400;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
.metadata-warning-icon {
  color: #dc2626;
  flex-shrink: 0;
  font-size: var(--studio-font-detail);
}
.catalog-node--datasource .node-name {
  font-weight: 600;
}
.node-icon.datasource {
  color: var(--catalog-icon);
}
.node-icon.datasource.is-active,
.node-icon.schema.is-active {
  color: #2f9167;
}
.node-icon.datasource.is-inactive {
  color: var(--catalog-faint);
}
.catalog-node--table .catalog-node-row {
  height: 28px;
  align-items: center;
}

.table-title {
  line-height: 18px;
}
.table-meta-tags {
  display: flex;
  flex: none;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
  font-size: var(--studio-font-detail);
  font-variant-numeric: tabular-nums;
}
.row-count,
.storage-size {
  color: var(--catalog-muted);
}
.lineage-count.upstream {
  color: var(--catalog-muted);
}
.lineage-count.downstream {
  color: var(--catalog-muted);
}
.lineage-count.is-zero {
  color: var(--catalog-faint);
}
.table-progress-bg {
  position: absolute;
  left: 0;
  bottom: 0;
  height: 1px;
  background: var(--catalog-line);
  pointer-events: none;
}
.catalog-node--table.is-detailed .catalog-node-row {
  height: 44px;
  align-items: flex-start;
  padding: 3px 0;
  box-sizing: border-box;
}
.catalog-node--table.is-detailed .node-icon {
  margin-top: 2px;
}
.is-detailed .table-main {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 2px;
}
.is-detailed .table-title {
  flex: none;
}
.table-detail-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  height: 18px;
}
.table-comment {
  flex: 1;
  min-width: 0;
  font-size: var(--studio-font-detail);
  line-height: 18px;
  color: var(--catalog-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.is-detailed .table-meta-tags {
  margin-left: auto;
  line-height: 18px;
  gap: 5px;
}
</style>
