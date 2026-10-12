<template>
  <div class="meta-section meta-section-fill">
    <section class="section-block section-fill">
      <div class="section-header">
        <div class="section-title">字段定义 <span class="field-count">{{ fieldRows.length }} 个字段</span></div>
        <div class="section-actions">
          <el-tag
            v-if="state.fieldsEditing && isAggregateTable(state.table)"
            type="warning"
            size="small"
            effect="plain"
          >
            AGGREGATE 表仅支持修改注释
          </el-tag>
          <el-tag
            v-if="state.fieldsEditing && fieldRows.some(isPrimaryKeyLocked)"
            type="warning"
            size="small"
            effect="plain"
          >
            主键列不可在线修改
          </el-tag>
        </div>
      </div>

      <div v-if="fieldRows.length" class="meta-table">
        <el-table :data="fieldRows" border size="small" :height="state.fieldsEditing ? '100%' : undefined" class="columns-table">
          <el-table-column label="字段名" width="134" fixed="left" show-overflow-tooltip>
            <template #default="{ row }">
              <el-input
                v-if="state.fieldsEditing"
                v-model="row.fieldName"
                size="small"
                placeholder="字段名"
                :disabled="isAggregateTable(state.table)"
              />
              <span v-else class="field-name"><el-icon v-if="row.isPrimary" class="field-key"><Key /></el-icon>{{ row.fieldName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="102" show-overflow-tooltip>
            <template #default="{ row }">
              <el-input
                v-if="state.fieldsEditing"
                v-model="row.fieldType"
                size="small"
                placeholder="VARCHAR(255)"
                :disabled="isAggregateTable(state.table)"
              />
              <span v-else>{{ row.fieldType }}</span>
            </template>
          </el-table-column>
          <el-table-column label="注释" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">
              <el-input v-if="state.fieldsEditing" v-model="row.fieldComment" size="small" placeholder="字段注释" />
              <span v-else>{{ row.fieldComment || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="可为空" width="84">
            <template #default="{ row }">
              <el-switch
                v-if="state.fieldsEditing"
                v-model="row.isNullable"
                :active-value="1"
                :inactive-value="0"
                size="small"
                :disabled="isAggregateTable(state.table)"
              />
              <span v-else>{{ row.isNullable ? '是' : '否' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="主键" width="84">
            <template #default="{ row }">
              <template v-if="state.fieldsEditing">
                <el-tooltip v-if="isPrimaryKeyLocked(row)"
                  :content="isDorisTable(state.table) ? 'Doris 不支持在线修改主键列' : 'MySQL 暂不支持在线修改已有字段的主键'" placement="top">
                  <span>
                    <el-switch v-model="row.isPrimary" :active-value="1" :inactive-value="0" size="small" disabled />
                  </span>
                </el-tooltip>
                <el-switch
                  v-else
                  v-model="row.isPrimary"
                  :active-value="1"
                  :inactive-value="0"
                  size="small"
                  :disabled="isAggregateTable(state.table)"
                />
              </template>
              <span v-else>{{ row.isPrimary ? '是' : '否' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="默认值" width="120">
            <template #default="{ row }">
              <el-input
                v-if="state.fieldsEditing"
                v-model="row.defaultValue"
                size="small"
                placeholder="可选"
                :disabled="isAggregateTable(state.table)"
              />
              <span v-else>{{ row.defaultValue || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            v-if="!state.fieldsEditing && state.metaSuggestion"
            label="智能描述"
            min-width="200"
            show-overflow-tooltip
          >
            <template #header>
              <span class="smart-col-header">
                智能描述
                <el-tooltip
                  content="基于建表语句、上下游血缘与关联任务代码生成，枚举取值取自该表真实数据，请复核后采纳"
                  placement="top"
                >
                  <el-icon class="smart-col-info"><InfoFilled /></el-icon>
                </el-tooltip>
                <el-button link type="primary" size="small" @click="generateMetadata(activeTabId)">采纳描述</el-button>
              </span>
            </template>
            <template #default="{ row }">
              <span v-if="isAdopted(row)" class="col-adopted">已采纳</span>
              <span v-else class="col-suggest">{{ suggestFor(row.fieldName) || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="state.fieldsEditing" label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-tooltip
                v-if="isAggregateTable(state.table)"
                content="AGGREGATE 表不支持新增字段"
                placement="top"
              >
                <span>
                  <el-button link type="primary" size="small" disabled>新增</el-button>
                </span>
              </el-tooltip>
              <el-button v-else link type="primary" size="small" @click="addField(activeTabId, row)">新增</el-button>
              <el-popconfirm
                width="240"
                confirm-button-text="确定"
                cancel-button-text="取消"
                :title="`确定删除字段「${row.fieldName || '未命名'}」吗？`"
                @confirm="removeField(activeTabId, row)"
              >
                <template #reference>
                  <el-tooltip
                    v-if="isAggregateTable(state.table)"
                    content="AGGREGATE 表不支持删除字段"
                    placement="top"
                  >
                    <span>
                      <el-button link type="danger" size="small" disabled>删除</el-button>
                    </span>
                  </el-tooltip>
                  <el-button v-else link type="danger" size="small">删除</el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <el-empty v-else description="暂无字段" :image-size="60">
        <template #default>
          <el-button
            v-if="state.fieldsEditing"
            type="primary"
            size="small"
            :disabled="isDemoMode || isAggregateTable(state.table)"
            @click="addField(activeTabId)"
          >
            新增字段
          </el-button>
        </template>
      </el-empty>
    </section>
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'
import { InfoFilled, Key } from '@element-plus/icons-vue'
import { isDemoMode } from '@/demo/runtime'
import { isMysqlPrimaryKeyLocked } from '../fieldEdit'

// P2-2 F17d：右侧面板「列信息」tab pane 从 DataStudioRightPanel.vue 抽出。
// 编辑/智能元数据等操作已上移到面板头部，这里只负责字段表本身。
// 共享脚手架样式由父组件的 .meta-tabs :deep() 提供。
const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanelColumns 需要 dataStudioCtx')
}

const {
  activeTab,
  tabStates,
  isDorisTable,
  getTableSourceType,
  isAggregateTable,
  getFieldRows,
  addField,
  removeField,
  generateMetadata,
} = ctx

const activeTabId = computed(() => String(activeTab.value || ''))
const state = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return tabStates[id] || null
})
const isPrimaryKeyLocked = (row) => isDorisTable(state.value?.table) ||
  isMysqlPrimaryKeyLocked(state.value?.table, row, getTableSourceType?.(state.value?.table))
const fieldRows = computed(() => getFieldRows(activeTabId.value))

const suggestFor = (fieldName) =>
  (state.value?.metaSuggestion?.fields || []).find((item) => item.fieldName === fieldName)?.suggestedComment || ''

const isAdopted = (row) => {
  const suggested = suggestFor(row.fieldName)
  return !!suggested && suggested === (row.fieldComment || '')
}
</script>

<style scoped>
.meta-table {
  overflow: auto;
  flex: 1;
  min-height: 0;
}
:deep(.columns-table th.el-table__cell) {
  background: #f2f7ff;
  color: var(--text-sub);
}
.smart-col-header {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.smart-col-info {
  color: var(--text-muted);
}
.col-suggest {
  color: #7c3aed;
}
.col-adopted {
  color: #16a34a;
}

.field-count {
  margin-left: 6px;
  font-weight: 400;
  font-size: var(--studio-font-detail);
  color: #94a3b8;
}
.field-name {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  font-family: Menlo, Consolas, monospace;
  font-size: var(--studio-font-detail);
  letter-spacing: 0;
}
.field-key {
  color: #ad8743;
  font-size: var(--studio-font-detail);
}
.columns-table :deep(.el-table__cell) {
  height: 30px;
  padding: 4px 0;
}
.columns-table :deep(.cell) {
  padding: 0 7px;
  font-size: var(--studio-font-detail);
  line-height: 20px;
}
.columns-table :deep(th.el-table__cell) {
  background: #f6f8fa;
  font-weight: 500;
}
</style>
