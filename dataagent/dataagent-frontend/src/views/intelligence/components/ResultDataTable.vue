<template>
  <div class="result-table">
    <div class="result-table-toolbar">
      <div class="result-table-meta">
        <span v-if="metaRowCount">{{ metaRowCount }} 行</span>
        <span v-if="metaDuration"> · {{ metaDuration }} ms</span>
        <span v-if="filterActive" class="result-table-meta-filtered"> · 筛选后 {{ filteredRows.length }} 行</span>
      </div>
      <div class="result-table-actions">
        <input
          v-model.trim="keyword"
          type="text"
          class="result-table-search"
          placeholder="搜索…"
          aria-label="搜索表格"
        >
        <button type="button" class="result-table-btn" data-action="copy-markdown" @click="copyAs('markdown')">
          {{ copiedAction === 'markdown' ? '已复制' : '复制MD' }}
        </button>
        <button type="button" class="result-table-btn" data-action="copy-tsv" @click="copyAs('tsv')">
          {{ copiedAction === 'tsv' ? '已复制' : '复制TSV' }}
        </button>
        <button type="button" class="result-table-btn result-table-btn-primary" data-action="export-csv" @click="exportCsv">
          导出CSV
        </button>
      </div>
    </div>

    <div v-if="truncatedNotice" class="result-table-notice">{{ truncatedNotice }}</div>

    <div class="tool-table-wrap result-table-scroll">
      <table class="tool-table">
        <thead>
          <tr>
            <th class="result-table-index-col">#</th>
            <th
              v-for="column in columns"
              :key="column"
              class="result-table-th"
              :class="{ 'is-sorted': sortColumn === column }"
              @click="cycleSort(column)"
            >
              <span class="result-table-th-label">{{ column }}</span>
              <span class="result-table-sort-icon">{{ sortIcon(column) }}</span>
              <button
                type="button"
                class="result-table-filter-btn"
                :class="{ 'is-active': isColumnFiltered(column) }"
                :data-filter-column="column"
                @click.stop="toggleFilterPanel(column)"
              >▼</button>
              <div v-if="openFilterColumn === column" class="result-table-filter-panel" @click.stop>
                <template v-if="distinctValues(column).length <= DISTINCT_LIMIT">
                  <label
                    v-for="value in distinctValues(column)"
                    :key="value"
                    class="result-table-filter-option"
                  >
                    <input
                      type="checkbox"
                      :checked="isValueChecked(column, value)"
                      @change="toggleFilterValue(column, value)"
                    >
                    <span>{{ value === '' ? '(空)' : value }}</span>
                  </label>
                </template>
                <template v-else>
                  <input
                    :value="columnTextFilters[column] || ''"
                    type="text"
                    class="result-table-search result-table-filter-text"
                    placeholder="列内过滤…"
                    @input="setColumnTextFilter(column, $event.target.value)"
                  >
                </template>
                <button type="button" class="result-table-btn result-table-filter-clear" @click="clearColumnFilter(column)">清除</button>
              </div>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, rowIndex) in pagedRows" :key="rowIndex">
            <td class="result-table-index-col">{{ pageStart + rowIndex + 1 }}</td>
            <td v-for="column in columns" :key="column">
              <span v-if="row[column] === null || row[column] === undefined" class="result-table-null">NULL</span>
              <template v-else>{{ row[column] }}</template>
            </td>
          </tr>
          <tr v-if="!pagedRows.length">
            <td :colspan="columns.length + 1" class="result-table-empty">无匹配数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="paginationEnabled" class="result-table-pager">
      <button type="button" class="result-table-btn" :disabled="page <= 1" @click="page -= 1">上一页</button>
      <span class="result-table-pager-info">{{ page }} / {{ pageCount }}</span>
      <button type="button" class="result-table-btn" :disabled="page >= pageCount" @click="page += 1">下一页</button>
      <select v-model.number="pageSize" class="result-table-page-size" aria-label="每页行数">
        <option :value="20">20 条/页</option>
        <option :value="50">50 条/页</option>
        <option :value="100">100 条/页</option>
      </select>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { copyText } from '@/utils/clipboard'
import { buildMarkdownTable, buildTsvContent, downloadCsv } from '@/utils/tableExport'

const props = defineProps({
  columns: { type: Array, default: () => [] },
  rows: { type: Array, default: () => [] },
  title: { type: String, default: '' },
  meta: { type: Object, default: () => ({}) }
})

const DISTINCT_LIMIT = 50
const PAGINATION_THRESHOLD = 20

const keyword = ref('')
const sortColumn = ref('')
const sortDirection = ref('')
const openFilterColumn = ref('')
const columnValueFilters = reactive({})
const columnTextFilters = reactive({})
const page = ref(1)
const pageSize = ref(20)
const copiedAction = ref('')
let copiedTimer = 0

const cellText = (value) => (value === null || value === undefined ? '' : String(value))

const metaRowCount = computed(() => {
  const value = Number(props.meta?.rowCount)
  if (Number.isFinite(value) && value > 0) return value
  return props.rows.length || 0
})
const metaDuration = computed(() => {
  const value = Number(props.meta?.durationMs)
  return Number.isFinite(value) && value >= 0 ? value : null
})
const truncatedNotice = computed(() => {
  const notice = String(props.meta?.notice || '').trim()
  if (notice) return notice
  if (props.meta?.hasMore || props.meta?.truncatedBySize) {
    return '结果已被截断，完整数据请使用导出，或让助手生成导出文件'
  }
  return ''
})

const searchedRows = computed(() => {
  const text = keyword.value.toLowerCase()
  if (!text) return props.rows
  return props.rows.filter((row) => props.columns.some((column) => cellText(row?.[column]).toLowerCase().includes(text)))
})

const filteredRows = computed(() => {
  let rows = searchedRows.value
  for (const column of props.columns) {
    const checked = columnValueFilters[column]
    if (checked && checked.size) {
      rows = rows.filter((row) => checked.has(cellText(row?.[column])))
    }
    const textFilter = String(columnTextFilters[column] || '').toLowerCase()
    if (textFilter) {
      rows = rows.filter((row) => cellText(row?.[column]).toLowerCase().includes(textFilter))
    }
  }
  return rows
})

const isNumericColumn = (column) => {
  let hasNumber = false
  for (const row of props.rows) {
    const value = row?.[column]
    if (value === null || value === undefined || value === '') continue
    if (typeof value === 'number') {
      hasNumber = true
      continue
    }
    if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) {
      hasNumber = true
      continue
    }
    return false
  }
  return hasNumber
}

const sortedRows = computed(() => {
  if (!sortColumn.value || !sortDirection.value) return filteredRows.value
  const column = sortColumn.value
  const factor = sortDirection.value === 'desc' ? -1 : 1
  const numeric = isNumericColumn(column)
  return [...filteredRows.value].sort((a, b) => {
    const left = a?.[column]
    const right = b?.[column]
    const leftMissing = left === null || left === undefined || left === ''
    const rightMissing = right === null || right === undefined || right === ''
    if (leftMissing && rightMissing) return 0
    if (leftMissing) return 1
    if (rightMissing) return -1
    if (numeric) return (Number(left) - Number(right)) * factor
    return String(left).localeCompare(String(right), 'zh-Hans-CN') * factor
  })
})

const paginationEnabled = computed(() => filteredRows.value.length > PAGINATION_THRESHOLD)
const pageCount = computed(() => Math.max(1, Math.ceil(sortedRows.value.length / pageSize.value)))
const pageStart = computed(() => (paginationEnabled.value ? (page.value - 1) * pageSize.value : 0))
const pagedRows = computed(() => {
  if (!paginationEnabled.value) return sortedRows.value
  return sortedRows.value.slice(pageStart.value, pageStart.value + pageSize.value)
})

const filterActive = computed(() => {
  if (keyword.value) return true
  if (Object.values(columnTextFilters).some((value) => String(value || '').trim())) return true
  return Object.values(columnValueFilters).some((checked) => checked && checked.size)
})

watch([filteredRows, pageSize], () => {
  if (page.value > pageCount.value) page.value = pageCount.value
})
watch(keyword, () => {
  page.value = 1
})

const cycleSort = (column) => {
  if (sortColumn.value !== column) {
    sortColumn.value = column
    sortDirection.value = 'asc'
    return
  }
  if (sortDirection.value === 'asc') {
    sortDirection.value = 'desc'
    return
  }
  sortColumn.value = ''
  sortDirection.value = ''
}

const sortIcon = (column) => {
  if (sortColumn.value !== column) return ''
  return sortDirection.value === 'asc' ? '▲' : '▼'
}

const distinctValueCache = computed(() => {
  const cache = {}
  for (const column of props.columns) {
    const values = new Set()
    for (const row of props.rows) {
      values.add(cellText(row?.[column]))
      if (values.size > DISTINCT_LIMIT) break
    }
    cache[column] = [...values].sort((a, b) => a.localeCompare(b, 'zh-Hans-CN'))
  }
  return cache
})

const distinctValues = (column) => distinctValueCache.value[column] || []

const toggleFilterPanel = (column) => {
  openFilterColumn.value = openFilterColumn.value === column ? '' : column
}

const isColumnFiltered = (column) => {
  if (columnValueFilters[column]?.size) return true
  return Boolean(String(columnTextFilters[column] || '').trim())
}

const isValueChecked = (column, value) => Boolean(columnValueFilters[column]?.has(value))

const toggleFilterValue = (column, value) => {
  const current = columnValueFilters[column] || new Set()
  const next = new Set(current)
  if (next.has(value)) next.delete(value)
  else next.add(value)
  columnValueFilters[column] = next
  page.value = 1
}

const setColumnTextFilter = (column, value) => {
  columnTextFilters[column] = String(value || '')
  page.value = 1
}

const clearColumnFilter = (column) => {
  delete columnValueFilters[column]
  delete columnTextFilters[column]
  openFilterColumn.value = ''
  page.value = 1
}

const exportBaseName = computed(() => props.title || 'query_result')

const exportCsv = () => {
  downloadCsv(exportBaseName.value, props.columns, filteredRows.value)
}

const markCopied = (action) => {
  copiedAction.value = action
  if (copiedTimer && typeof window !== 'undefined') window.clearTimeout(copiedTimer)
  if (typeof window !== 'undefined') {
    copiedTimer = window.setTimeout(() => {
      copiedAction.value = ''
    }, 1500)
  }
}

const copyAs = async (format) => {
  const content = format === 'markdown'
    ? buildMarkdownTable(props.columns, filteredRows.value)
    : buildTsvContent(props.columns, filteredRows.value)
  try {
    await copyText(content)
    markCopied(format)
  } catch (_error) {
    // 剪贴板不可用时静默失败，按钮状态不变化即可感知
  }
}

const handleDocumentClick = () => {
  openFilterColumn.value = ''
}

onMounted(() => {
  if (typeof document !== 'undefined') {
    document.addEventListener('click', handleDocumentClick)
  }
})

onBeforeUnmount(() => {
  if (typeof document !== 'undefined') {
    document.removeEventListener('click', handleDocumentClick)
  }
  if (copiedTimer && typeof window !== 'undefined') window.clearTimeout(copiedTimer)
})
</script>

<style scoped>
.result-table {
  margin-top: 14px;
}

.result-table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.result-table-meta {
  font-size: 12px;
  color: #607185;
}

.result-table-meta-filtered {
  color: #31567a;
  font-weight: 600;
}

.result-table-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.result-table-search {
  width: 130px;
  padding: 4px 8px;
  border: 1px solid #dbe3ec;
  border-radius: 8px;
  font-size: 12px;
  color: #233142;
  outline: none;
  background: #fff;
}

.result-table-search:focus {
  border-color: #4f81ff;
}

.result-table-btn {
  padding: 4px 10px;
  border: 1px solid #dbe3ec;
  border-radius: 8px;
  background: #fff;
  color: #31567a;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}

.result-table-btn:hover:not(:disabled) {
  border-color: #4f81ff;
  color: #1d3f5e;
}

.result-table-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.result-table-btn-primary {
  background: #eef6ff;
  border-color: #cfe2ff;
}

.result-table-notice {
  margin-top: 8px;
  padding: 6px 10px;
  border-radius: 8px;
  background: #fff8e6;
  color: #9a6700;
  font-size: 12px;
  line-height: 1.6;
}

.result-table-scroll {
  margin-top: 10px;
  max-height: 420px;
  overflow: auto;
}

.tool-table-wrap {
  border: 1px solid #e1e8f0;
  border-radius: 14px;
  background: #fff;
  overscroll-behavior: contain;
}

.tool-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 480px;
}

.tool-table th,
.tool-table td {
  padding: 10px 12px;
  border-bottom: 1px solid #edf2f7;
  text-align: left;
  font-size: 12px;
  color: #233142;
  white-space: pre-wrap;
  word-break: break-word;
  vertical-align: top;
}

.tool-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #f8fbff;
  color: #607185;
  font-weight: 700;
  white-space: nowrap;
}

.result-table-th {
  cursor: pointer;
  user-select: none;
  position: sticky;
}

.result-table-th.is-sorted .result-table-th-label {
  color: #1d3f5e;
}

.result-table-sort-icon {
  margin-left: 2px;
  font-size: 10px;
  color: #4f81ff;
}

.result-table-index-col {
  width: 40px;
  color: #8da0b3;
  text-align: right;
}

.result-table-filter-btn {
  margin-left: 4px;
  padding: 0 3px;
  border: none;
  background: transparent;
  color: #c0ccd9;
  font-size: 9px;
  cursor: pointer;
}

.result-table-filter-btn:hover,
.result-table-filter-btn.is-active {
  color: #4f81ff;
}

.result-table-filter-panel {
  position: absolute;
  margin-top: 6px;
  z-index: 5;
  min-width: 150px;
  max-height: 240px;
  overflow-y: auto;
  padding: 8px;
  border: 1px solid #dbe3ec;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(22, 33, 49, 0.12);
  font-weight: 400;
}

.result-table-filter-option {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 2px;
  font-size: 12px;
  color: #233142;
  cursor: pointer;
  white-space: nowrap;
}

.result-table-filter-text {
  width: 100%;
  box-sizing: border-box;
}

.result-table-filter-clear {
  margin-top: 6px;
  width: 100%;
}

.result-table-null {
  color: #b7c3cf;
  font-style: italic;
}

.result-table-empty {
  text-align: center;
  color: #8da0b3;
}

.result-table-pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.result-table-pager-info {
  font-size: 12px;
  color: #607185;
}

.result-table-page-size {
  padding: 3px 6px;
  border: 1px solid #dbe3ec;
  border-radius: 8px;
  font-size: 12px;
  color: #31567a;
  background: #fff;
}
</style>
