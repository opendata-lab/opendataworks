<template>
  <div v-loading="loading" class="version-panel">
    <div class="version-toolbar">
      <div class="version-toolbar-left">
        <el-tag v-if="compareLeft" size="small" closable @close="compareLeft = null">
          左: v{{ compareLeft.versionNo }}
        </el-tag>
        <el-tag v-if="compareRight" size="small" type="warning" closable @close="compareRight = null">
          右: v{{ compareRight.versionNo }}
        </el-tag>
        <span v-if="!compareLeft && !compareRight" class="version-hint">选择两个版本进行对比</span>
      </div>
      <div class="version-toolbar-right">
        <el-button size="small" :disabled="!canCompare" type="primary" @click="compareVisible = true">
          对比
        </el-button>
        <el-button size="small" :disabled="loading" @click="loadVersions">刷新</el-button>
      </div>
    </div>

    <template v-if="versions.length">
      <div class="version-timeline">
        <article v-for="row in versions" :key="row.id" class="version-entry">
          <header><strong>v{{ row.versionNo }}</strong><time>{{ formatDateTime(row.createdAt) }}</time></header>
          <p>{{ row.changeSummary || '元数据更新' }}</p>
          <div class="version-entry-footer"><span>{{ triggerLabel(row.triggerSource) }} · {{ row.createdBy || '-' }}</span><div>
            <el-button link type="primary" size="small" @click="showSnapshot(row)">快照</el-button>
            <el-button link size="small" :type="compareLeft?.id === row.id ? 'primary' : ''" @click="selectLeft(row)">设为左</el-button>
            <el-button link size="small" :type="compareRight?.id === row.id ? 'primary' : ''" @click="selectRight(row)">设为右</el-button>
          </div></div>
        </article>
      </div>
      <el-pagination
        v-if="total > pageSize"
        v-model:current-page="pageNum"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next, total"
        small
        class="version-pagination"
        @current-change="loadVersions"
      />
    </template>
    <el-empty
      v-else-if="!loading"
      description="暂无版本记录（首次元数据变更后开始记录）"
      :image-size="60"
    />

    <el-dialog
      v-model="snapshotVisible"
      :title="snapshotTitle"
      width="640px"
      append-to-body
      destroy-on-close
    >
      <el-scrollbar max-height="480px">
        <pre v-loading="snapshotLoading" class="snapshot-json">{{ snapshotJson }}</pre>
      </el-scrollbar>
    </el-dialog>

    <TableVersionCompareDialog
      v-model="compareVisible"
      :table-id="tableId"
      :left-version-id="compareLeft?.id"
      :right-version-id="compareRight?.id"
    />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { tableApi } from '@/api/table'
import TableVersionCompareDialog from './TableVersionCompareDialog.vue'

const props = defineProps({
  tableId: {
    type: [Number, String],
    default: null
  },
  active: {
    type: Boolean,
    default: false
  }
})

const loading = ref(false)
const versions = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 20
const loadedTableId = ref(null)

const compareLeft = ref(null)
const compareRight = ref(null)
const compareVisible = ref(false)

const snapshotVisible = ref(false)
const snapshotLoading = ref(false)
const snapshotJson = ref('')
const snapshotTitle = ref('版本快照')

const canCompare = computed(
  () => compareLeft.value && compareRight.value && compareLeft.value.id !== compareRight.value.id
)

const TRIGGER_LABELS = {
  table_create: { label: '建表', type: 'info' },
  manual_edit: { label: '手动编辑', type: 'primary' },
  metadata_sync: { label: '元数据同步', type: 'success' },
  inspection_fix: { label: '巡检修复', type: 'warning' }
}

const triggerLabel = (source) => TRIGGER_LABELS[source]?.label || source || '未知'

const formatDateTime = (value) => {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}

const loadVersions = async () => {
  if (!props.tableId) return
  loading.value = true
  try {
    const data = await tableApi.listVersions(props.tableId, {
      pageNum: pageNum.value,
      pageSize
    })
    versions.value = data?.records || []
    total.value = Number(data?.total || 0)
    loadedTableId.value = props.tableId
  } catch (e) {
    versions.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const resetState = () => {
  versions.value = []
  total.value = 0
  pageNum.value = 1
  compareLeft.value = null
  compareRight.value = null
  loadedTableId.value = null
}

const selectLeft = (row) => {
  compareLeft.value = compareLeft.value?.id === row.id ? null : row
}

const selectRight = (row) => {
  compareRight.value = compareRight.value?.id === row.id ? null : row
}

const showSnapshot = async (row) => {
  snapshotTitle.value = `版本快照 v${row.versionNo}`
  snapshotVisible.value = true
  snapshotLoading.value = true
  snapshotJson.value = ''
  try {
    const detail = await tableApi.getVersion(props.tableId, row.id)
    const raw = detail?.metadataSnapshot
    try {
      snapshotJson.value = JSON.stringify(JSON.parse(raw), null, 2)
    } catch {
      snapshotJson.value = raw || '（无快照内容）'
    }
  } catch (e) {
    snapshotJson.value = e?.message || '加载快照失败'
  } finally {
    snapshotLoading.value = false
  }
}

watch(
  () => props.tableId,
  () => {
    resetState()
    if (props.active && props.tableId) loadVersions()
  }
)

watch(
  () => props.active,
  (active) => {
    if (active && props.tableId && loadedTableId.value !== props.tableId) loadVersions()
  },
  { immediate: true }
)
</script>

<style scoped>
.version-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 160px;
}

.version-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.version-toolbar-left {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.version-hint {
  font-size: 12px;
  color: #909399;
}

.version-pagination {
  justify-content: flex-end;
}

.snapshot-json {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-all;
}

.version-timeline {
  margin-top: 8px;
}
.version-entry {
  position: relative;
  padding: 0 0 18px 16px;
  border-left: 1px solid #e2e8f0;
}
.version-entry::before {
  content: '';
  position: absolute;
  width: 5px;
  height: 5px;
  border: 2px solid #fff;
  background: #6c89ac;
  border-radius: 50%;
  left: -5px;
  top: 3px;
}
.version-entry header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 11px;
}
.version-entry strong {
  font-size: var(--studio-font-ui);
  font-weight: 500;
  color: #41536a;
}
.version-entry time {
  font-size: 10px;
  color: #94a3b8;
}
.version-entry p {
  margin: 8px 0;
  font-size: var(--studio-font-ui);
  color: #526174;
  line-height: 20px;
}
.version-entry-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 10px;
  color: #94a3b8;
}
.version-entry-footer :deep(.el-button) {
  font-size: 10px;
  padding: 0;
  margin-left: 7px;
}

.version-entry-footer > div {
  display: flex;
  gap: 8px;
}
</style>
