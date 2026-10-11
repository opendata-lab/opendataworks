<template>
  <div class="query-panel">
    <div class="query-topbar">
      <div class="query-topbar__actions">
        <el-button type="primary" size="small" :loading="tabStates[tab.id].queryLoading" :disabled="tabStates[tab.id].queryLoading || tabStates[tab.id].queryStopping" @click="executeQuery(tab.id)">
          <el-icon><CaretRight /></el-icon>{{ tabStates[tab.id].query.hasSelection ? '执行选中' : '执行' }}
        </el-button>
        <el-button size="small" text aria-label="停止查询" title="停止查询" :loading="tabStates[tab.id].queryStopping" :disabled="!tabStates[tab.id].queryCancelable || tabStates[tab.id].queryStopping" @click="stopQuery(tab.id)"><el-icon><VideoPause /></el-icon>停止</el-button>
        <el-button size="small" text title="查看 SQL 执行计划" :disabled="tabStates[tab.id].queryLoading || tabStates[tab.id].queryStopping" @click="executeQuery(tab.id, { explain: true })"><el-icon><DataAnalysis /></el-icon>解释</el-button>
        <span class="query-divider"></span>
        <el-button size="small" text :disabled="tabStates[tab.id].queryLoading || tabStates[tab.id].queryStopping || isDemoMode" @click="saveAsTask(tab.id)"><el-icon><FolderAdd /></el-icon>存为任务</el-button>
        <el-dropdown trigger="click" @command="resetQuery(tab.id)">
          <el-button size="small" text aria-label="更多查询操作" title="更多查询操作"><el-icon><MoreFilled /></el-icon></el-button>
          <template #dropdown><el-dropdown-menu><el-dropdown-item :disabled="tabStates[tab.id].queryLoading || tabStates[tab.id].queryStopping" command="reset">重置 SQL</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown>
      </div>
      <div class="query-topbar__context">
        <div class="query-context">
          <el-select :model-value="tabStates[tab.id].query.sourceId ?? tabStates[tab.id].table.sourceId" size="small" filterable class="query-select query-select--source" placeholder="选择连接" aria-label="查询连接" :disabled="tabStates[tab.id].queryLoading || tabStates[tab.id].queryStopping" @change="value => handleQuerySourceSelect(tab.id, value)">
            <template #prefix><el-icon><Link /></el-icon></template>
            <el-option v-for="source in dataSources" :key="String(source.id)" :label="source.clusterName || source.name || `DataSource ${source.id}`" :value="String(source.id)" />
          </el-select>
          <el-select :model-value="tabStates[tab.id].query.dbName ?? tabStates[tab.id].table.dbName" size="small" filterable class="query-select query-select--db" placeholder="选择 Schema" aria-label="查询 Schema" :disabled="tabStates[tab.id].queryLoading || tabStates[tab.id].queryStopping || !(tabStates[tab.id].query.sourceId ?? tabStates[tab.id].table.sourceId)" @change="value => handleQueryDatabaseSelect(tab.id, value)">
            <template #prefix><el-icon><Coin /></el-icon></template>
            <el-option v-for="db in getSchemaOptions(tabStates[tab.id].query.sourceId ?? tabStates[tab.id].table.sourceId)" :key="db" :label="db" :value="db" />
          </el-select>

        </div>
        <el-popover trigger="click" placement="bottom-end" :width="240">
          <template #reference><el-button size="small" text class="query-limit" aria-label="设置结果行数上限" :title="`结果行数上限：${tabStates[tab.id].query.limit}`">{{ tabStates[tab.id].query.limit }} 行<el-icon><ArrowDown /></el-icon></el-button></template>
          <div class="limit-settings"><span>结果行数上限</span><el-input-number v-model="tabStates[tab.id].query.limit" :min="1" :max="5000" :step="100" size="small" controls-position="right" class="limit-input" /></div>
          <p class="limit-hint">最多返回 5000 行，调整后重新运行。</p>
        </el-popover>
      </div>
    </div>
    <SqlEditor v-model="tabStates[tab.id].query.sql" class="sql-editor" placeholder="-- 输入 SQL，点击执行查看结果" :completion-context="getSqlCompletionContext(tab.id)" @selection-change="(payload) => handleSqlSelectionChange(tab.id, payload)" />
  </div>
</template>
<script setup>
import { defineAsyncComponent, inject } from 'vue'
import { CaretRight, VideoPause, MoreFilled, Link, Coin, ArrowDown, DataAnalysis, FolderAdd } from '@element-plus/icons-vue'
import { isDemoMode } from '@/demo/runtime'

const SqlEditor = defineAsyncComponent({
  loader: () => import('@/components/SqlEditor.vue'),
  suspensible: false
})

// P2-2 F16b：查询工具栏 + SQL 编辑器从 DataStudioNew.vue 抽出。
// 数据与行为通过 dataStudioQueryCtx 注入，键集合见 DataStudioNew.vue 的 provide。
defineProps({
  tab: {
    type: Object,
    required: true,
  },
})

const ctx = inject('dataStudioQueryCtx')
if (!ctx) {
  throw new Error('DataStudioQueryPanel 需要 dataStudioQueryCtx')
}

const {
  tabStates,
  dataSources,
  getSchemaOptions,
  handleQuerySourceSelect,
  handleQueryDatabaseSelect,
  executeQuery,
  stopQuery,
  resetQuery,
  saveAsTask,
  getSqlCompletionContext,
  handleSqlSelectionChange,
} = ctx
</script>

<style scoped>
.query-panel {
  background: #fff;
  min-height: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}
.query-topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 5px 8px;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
  gap: 6px 12px;
  flex-wrap: wrap;
}
.query-topbar__actions,
.query-topbar__context {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
.query-topbar__context {
  margin-left: auto;
  max-width: 100%;
}
.query-topbar__actions :deep(.el-button) {
  margin-left: 0;
  font-size: 11px;
  padding: 4px 7px;
  height: 26px;
}
.query-topbar__actions :deep(.el-button--primary) {
  padding: 4px 10px;
}
.query-divider {
  width: 1px;
  height: 14px;
  background: #dce2e9;
  margin: 0 3px;
  flex-shrink: 0;
}
.query-context {
  display: flex;
  gap: 5px;
  align-items: center;
  min-width: 0;
  font-size: 11px;
  color: #64748b;
}
.context-name {
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.connection-icon {
  color: #718096;
  flex-shrink: 0;
}
.context-separator {
  font-size: 9px;
  color: #a0aec0;
  flex-shrink: 0;
}
.query-select--source {
  width: 156px;
  min-width: 110px;
}
.query-select--db {
  width: 140px;
  min-width: 100px;
}
.query-select :deep(.el-select__wrapper) {
  font-size: 11px;
  min-height: 26px;
}
.query-limit {
  color: #64748b;
  font-size: 11px;
  padding: 4px 6px;
  height: 24px;
  flex-shrink: 0;
}
.query-limit .el-icon {
  font-size: 9px;
  margin-left: 4px;
}
.limit-settings {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
}
.limit-input {
  width: 100px;
}
.limit-hint {
  margin: 10px 0 0;
  font-size: 11px;
  color: #94a3b8;
}
.sql-editor {
  flex: 1;
  min-height: 0;
}
.sql-editor :deep(.cm-editor) {
  height: 100%;
  font-size: var(--studio-font-reading);
}
.sql-editor :deep(.cm-scroller) {
  line-height: 20px;
}
.sql-editor :deep(.cm-content) {
  padding-top: 8px;
}
.sql-editor :deep(.cm-gutters) {
  background: #f8fafc;
  color: #94a3b8;
  border-right: 1px solid #edf0f4;
}

.query-topbar__actions :deep(.el-icon) {
  margin-right: 4px;
}
.query-topbar__context {
  flex-wrap: wrap;
}
.query-select :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px #dce2e9 inset;
  background: #fff;
}
</style>
