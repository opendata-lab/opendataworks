<template>
  <div ref="basicRootRef" class="meta-section meta-section-fill inspector-overview">
    <el-scrollbar class="meta-scroll">
      <section v-if="state.metaEditing" class="overview-section">
        <h3>对象定义</h3>
        <el-descriptions :column="1" size="small" class="meta-descriptions overview-descriptions">
        <el-descriptions-item label="表名">
          <el-input
            v-if="state.metaEditing"
            v-model="state.metaForm.tableName"
            size="small"
            class="meta-input"
            :disabled="!!physicalEditBlockedReason"
            :title="physicalEditBlockedReason"
          />
          <span v-else>{{ state.table.tableName || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="数据库">
          <span>{{ state.table.dbName || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="表注释" :span="descColumn">
          <el-input
            v-if="state.metaEditing"
            v-model="state.metaForm.tableComment"
            size="small"
            class="meta-input"
            :disabled="!!physicalEditBlockedReason"
            :title="physicalEditBlockedReason"
          />
          <span v-else>{{ state.table.tableComment || '-' }}</span>
        </el-descriptions-item>
        </el-descriptions>
      </section>
      <section class="overview-section">
        <h3>归属信息</h3>
        <el-descriptions :column="descColumn" size="small" class="meta-descriptions overview-descriptions">
        <el-descriptions-item label="分层">
          <el-select
            v-if="state.metaEditing"
            v-model="state.metaForm.layer"
            size="small"
            placeholder="选择分层（必填）"
            class="meta-input"
          >
            <el-option v-for="item in layerOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <span v-else>{{ state.table.layer || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="业务域">
          <el-select
            v-if="state.metaEditing"
            v-model="state.metaForm.businessDomain"
            size="small"
            placeholder="选择业务域"
            class="meta-input"
            @change="handleMetaBusinessDomainChange(activeTabId)"
          >
            <el-option
              v-for="item in businessDomainOptions"
              :key="item.domainCode"
              :label="`${item.domainCode} - ${item.domainName}`"
              :value="item.domainCode"
            />
          </el-select>
          <span v-else>{{ state.table.businessDomain || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="数据域">
          <el-select
            v-if="state.metaEditing"
            v-model="state.metaForm.dataDomain"
            size="small"
            placeholder="选择数据域"
            class="meta-input"
            :disabled="!state.metaForm.businessDomain"
          >
            <el-option
              v-for="item in dataDomainOptions"
              :key="item.domainCode"
              :label="`${item.domainCode} - ${item.domainName}`"
              :value="item.domainCode"
            />
          </el-select>
          <span v-else>{{ state.table.dataDomain || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="负责人">
          <el-input v-if="state.metaEditing" v-model="state.metaForm.owner" size="small" class="meta-input" />
          <span v-else>{{ state.table.owner || '-' }}</span>
        </el-descriptions-item>
        </el-descriptions>
      </section>
      <section class="overview-section">
        <h3>数据规模 <span>点击指标查看趋势</span></h3>
        <el-descriptions :column="descColumn" size="small" class="meta-descriptions overview-descriptions scale-descriptions">
        <el-descriptions-item label="行数">
          <el-button
            link
            type="primary"
            class="metric-link"
            :disabled="!state.table?.id"
            @click="trendDialogRef?.open('rowCount')"
          >
            {{ formatRowCountDisplay(resolveTableRowCount(state.table)) }}
          </el-button>
        </el-descriptions-item>
        <el-descriptions-item label="数据量">
          <el-button
            link
            type="primary"
            class="metric-link"
            :disabled="!state.table?.id"
            @click="trendDialogRef?.open('dataSize')"
          >
            {{ formatStorageSizeDisplay(resolveTableStorageSize(state.table)) }}
          </el-button>
        </el-descriptions-item>
        </el-descriptions>
      </section>
      <section class="overview-section">
        <h3>更新时间</h3>
        <el-descriptions :column="descColumn" size="small" class="meta-descriptions overview-descriptions time-descriptions">
        <el-descriptions-item label="创建时间" :span="descColumn">
          <span>{{ formatDateTime(resolveTableDorisCreateTime(state.table)) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="更新时间" :span="descColumn">
          <span>{{ formatDateTime(resolveTableDorisUpdateTime(state.table)) }}</span>
        </el-descriptions-item>
        </el-descriptions>
      </section>
    </el-scrollbar>
  </div>
  <TableTrendDialog ref="trendDialogRef" :table="state?.table" />
</template>
<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import TableTrendDialog from './TableTrendDialog.vue'
import {
  resolveTableRowCount,
  resolveTableStorageSize,
  resolveTableDorisCreateTime,
  resolveTableDorisUpdateTime,
  formatRowCountDisplay,
  formatStorageSizeDisplay,
} from '../tableFormat'

// P2-2 F17d：右侧面板「基本信息」tab pane 从 DataStudioRightPanel.vue 抽出。
// 共享脚手架样式（.meta-section/.section-* 等）由父组件的 .meta-tabs :deep() 提供。
const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanelBasic 需要 dataStudioCtx')
}

const {
  activeTab,
  tabStates,
  layerOptions,
  businessDomainOptions,
  getMetaDataDomainOptions,
  handleMetaBusinessDomainChange,
  structureEditBlockedReason,
  formatDateTime,
} = ctx

const activeTabId = computed(() => String(activeTab.value || ''))
const state = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return tabStates[id] || null
})
const dataDomainOptions = computed(() => getMetaDataDomainOptions(activeTabId.value))
// 表名和表注释会改物理表；不支持时只读，分层、业务域等平台属性仍可编辑
const physicalEditBlockedReason = computed(() => structureEditBlockedReason(state.value?.table))
const trendDialogRef = ref(null)

// 根据面板实际宽度决定列数；无边框布局为短属性留出两列空间。
const TWO_COLUMN_MIN_WIDTH = 360
const basicRootRef = ref(null)
const descColumn = ref(2)
let resizeObserver = null

const syncDescColumn = (width) => {
  if (!width) return
  descColumn.value = width >= TWO_COLUMN_MIN_WIDTH ? 2 : 1
}

onMounted(() => {
  const el = basicRootRef.value
  if (!el) return
  syncDescColumn(el.getBoundingClientRect().width)
  if (typeof ResizeObserver === 'undefined') return
  resizeObserver = new ResizeObserver((entries) => {
    syncDescColumn(entries[0]?.contentRect?.width)
  })
  resizeObserver.observe(el)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})
</script>

<style scoped>
/* 96px 刚好放下最长的「Doris创建时间」，两列时能多留 24px 给值列 */
.meta-descriptions :deep(.el-descriptions__label.is-bordered-label) {
  width: 96px;
  min-width: 96px;
  white-space: nowrap;
  color: var(--text-sub);
}

.meta-descriptions :deep(.el-descriptions__content.is-bordered-content) {
  white-space: normal;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.meta-input {
  width: 100%;
}

.metric-link {
  padding: 0;
}

.overview-section {
  padding: 4px 0 0;
  margin-bottom: 22px;
}
.overview-section h3 {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 0 0 12px;
  font-size: var(--studio-font-ui);
  font-weight: 500;
  color: #56677b;
}
.overview-section h3 span {
  font-size: var(--studio-font-detail);
  color: #98a3b2;
  font-weight: 400;
}
.overview-descriptions :deep(.el-descriptions__body) {
  background: transparent;
}
.overview-descriptions :deep(.el-descriptions__cell) {
  padding-bottom: 11px;
  line-height: 18px;
  font-size: var(--studio-font-ui);
}
.overview-descriptions :deep(.el-descriptions__label) {
  color: #8894a4;
  font-size: var(--studio-font-detail);
  font-weight: 400;
  margin-right: 12px;
}
.overview-descriptions :deep(.el-descriptions__content) {
  color: #334155;
  font-size: var(--studio-font-ui);
  overflow-wrap: anywhere;
}
.metric-link {
  font-size: 14px;
  font-weight: 500;
  color: #3f6fa7;
  line-height: 24px;
}
.time-descriptions :deep(.el-descriptions__content) {
  font-size: var(--studio-font-detail);
  font-variant-numeric: tabular-nums;
}
</style>
