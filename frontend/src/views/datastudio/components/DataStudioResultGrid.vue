<template>
  <div class="result-grid">
    <div v-if="!gridColumns.length || !gridRows.length" class="result-grid__empty">
      {{ emptyText }}
    </div>
    <el-auto-resizer v-else>
      <template #default="{ height, width }">
        <el-table-v2
          v-if="safeHeight(height) > 0 && safeWidth(width) > 0"
          :columns="gridColumns"
          :data="gridRows"
          :width="safeWidth(width)"
          :height="safeHeight(height)"
          :row-height="ROW_HEIGHT"
          :header-height="HEADER_HEIGHT"
          :row-key="RESULT_GRID_ROW_KEY"
          :scrollbar-always-on="true"
          class="result-grid__table"
        />
      </template>
    </el-auto-resizer>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import {
  RESULT_GRID_ROW_KEY,
  buildResultGridColumns,
  ensureResultGridRows
} from './resultGridModel'

const ROW_HEIGHT = 32
const HEADER_HEIGHT = 36

const props = defineProps({
  columns: {
    type: Array,
    default: () => []
  },
  rows: {
    type: Array,
    default: () => []
  },
  rowKeyPrefix: {
    type: String,
    default: 'result'
  },
  emptyText: {
    type: String,
    default: '暂无数据'
  }
})

const gridColumns = computed(() => buildResultGridColumns(props.columns))
const gridRows = computed(() => ensureResultGridRows(props.rows, props.rowKeyPrefix))

const safeWidth = (value) => Math.max(0, Math.floor(Number(value) || 0))
const safeHeight = (value) => Math.max(0, Math.floor(Number(value) || 0))

defineExpose({
  doLayout: () => {}
})
</script>

<style scoped>
.result-grid {
  width: 100%;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.result-grid__table {
  font-size: 12px;
}

.result-grid__empty {
  height: 100%;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 13px;
}

.result-grid :deep(.el-table-v2__header-cell) {
  color: #1f2f3d;
  font-weight: 600;
}

.result-grid :deep(.el-table-v2__cell) {
  color: #334155;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
