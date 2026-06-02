<template>
  <div class="result-grid">
    <div v-if="!gridColumns.length || !gridRows.length" class="result-grid__empty">
      {{ emptyText }}
    </div>
    <el-auto-resizer v-else>
      <template #default="{ height, width }">
        <el-table-v2
          v-if="safeHeight(height) > 0 && safeWidth(width) > 0"
          :columns="resolveColumns(safeWidth(width))"
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
import { computed, h, ref, watch } from 'vue'
import {
  MIN_COLUMN_WIDTH,
  RESULT_GRID_ROW_KEY,
  buildResultGridColumns,
  distributeColumnWidths,
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

const gridRows = computed(() => ensureResultGridRows(props.rows, props.rowKeyPrefix))
const gridColumns = computed(() => buildResultGridColumns(props.columns, gridRows.value))

// Widths the user set by dragging a column border. Reset whenever the result
// set changes so a new query starts from content-based auto sizing again.
const widthOverrides = ref({})
watch(gridColumns, () => {
  widthOverrides.value = {}
})

// el-table-v2 has no built-in column resizing, so render a drag handle in the
// header cell and update the column width live while the pointer moves.
const startColumnResize = (event, column) => {
  event.preventDefault()
  event.stopPropagation()
  const startX = event.clientX
  const startWidth = Number(column.width) || MIN_COLUMN_WIDTH
  const onMove = (moveEvent) => {
    const nextWidth = Math.max(MIN_COLUMN_WIDTH, Math.round(startWidth + (moveEvent.clientX - startX)))
    widthOverrides.value = { ...widthOverrides.value, [column.key]: nextWidth }
  }
  const onUp = () => {
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
    document.body.classList.remove('result-grid--resizing')
  }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
  document.body.classList.add('result-grid--resizing')
}

const renderHeaderCell = ({ column }) =>
  h('div', { class: 'result-grid__header' }, [
    h('span', { class: 'result-grid__header-title', title: column.title }, column.title),
    h('span', {
      class: 'result-grid__resizer',
      onMousedown: (event) => startColumnResize(event, column)
    })
  ])

const renderCell = ({ cellData }) => {
  const text = cellData == null ? '' : String(cellData)
  return h('span', { class: 'result-grid__cell-text', title: text }, text)
}

const resolveColumns = (availableWidth) => {
  const columns = gridColumns.value.map((column) => ({
    ...column,
    width: widthOverrides.value[column.key] ?? column.width,
    headerCellRenderer: renderHeaderCell,
    cellRenderer: renderCell
  }))
  // Respect manual sizing once the user has dragged a border; otherwise stretch
  // columns to fill the grid so a couple of short columns are not left narrow.
  if (Object.keys(widthOverrides.value).length) return columns
  return distributeColumnWidths(columns, availableWidth)
}

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
  padding: 0;
  color: #1f2f3d;
}

.result-grid :deep(.result-grid__header) {
  position: relative;
  display: flex;
  align-items: center;
  width: 100%;
  height: 100%;
  padding: 0 8px;
  box-sizing: border-box;
  font-weight: 600;
}

.result-grid :deep(.result-grid__header-title) {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.result-grid :deep(.result-grid__resizer) {
  position: absolute;
  top: 0;
  right: 0;
  width: 6px;
  height: 100%;
  cursor: col-resize;
  user-select: none;
}

.result-grid :deep(.result-grid__resizer:hover),
.result-grid :deep(.result-grid__resizer:active) {
  background: var(--el-color-primary, #409eff);
  opacity: 0.5;
}

.result-grid :deep(.result-grid__cell-text) {
  display: block;
  width: 100%;
  color: #334155;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>

<style>
/* Applied to document.body while a column border is being dragged. */
body.result-grid--resizing,
body.result-grid--resizing * {
  cursor: col-resize !important;
  user-select: none !important;
}
</style>
