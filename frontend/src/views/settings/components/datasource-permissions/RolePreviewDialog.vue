<template>
  <!-- 弹窗 8: 权限变更预览确认 (Preview -> Apply) -->
  <el-dialog v-model="previewDialogVisible" title="确认权限变更" width="680px" append-to-body>
    <template v-if="previewResult">
      <div class="preview-summary-bar">
        <div>
          <strong>{{ selectedRole?.name }}</strong>
          <span class="muted-text">
            影响 {{ previewResult.memberCount ?? countRoleMembers(selectedRole?.id) }} 位成员
          </span>
        </div>
        <span v-if="previewResult.expiresAt" class="muted-text">
          预览有效期至：{{ formatDateTime(previewResult.expiresAt) }}
        </span>
      </div>

      <div
        v-if="previewResult.warnings && previewResult.warnings.length"
        class="warnings-block"
        data-testid="preview-warnings"
      >
        <p v-for="(w, idx) in previewResult.warnings" :key="idx" class="state-note warning-note">
          {{ w }}
        </p>
      </div>

      <p>本次权限变更清单（{{ (previewResult.changes || []).length }} 项资源变更）</p>
      <el-table :data="previewResult.changes || []" border data-testid="preview-changes" empty-text="无资源动作变更；请核对原生 SQL 与警告后激活角色">
        <el-table-column label="变更类型" width="90">
          <template #default="{ row }">{{ { ADD: '新增', REMOVE: '撤销', UPDATE: '调整' }[row.changeType] }}</template>
        </el-table-column>
        <el-table-column label="资源对象">
          <template #default="{ row }">{{ getResourceTypeLabel(row.resourceType) }} {{ row.database }}{{ row.tableName ? `.${row.tableName}` : '.*' }}</template>
        </el-table-column>
        <el-table-column label="权限动作变化">
          <template #default="{ row }">
            <div>变更前：{{ actionLabels(row.beforeActions) }}</div>
            <div>变更后：{{ actionLabels(row.afterActions) }}</div>
            <div v-if="row.addedActions.length">+ {{ actionLabels(row.addedActions) }}</div>
            <div v-if="row.removedActions.length">− {{ actionLabels(row.removedActions) }}</div>
          </template>
        </el-table-column>
      </el-table>
      <details class="sql-preview-section">
        <summary>查看待执行原生 SQL（{{ (previewResult.sql || []).length }} 条）</summary>
        <pre class="native-sql" data-testid="preview-sql">{{
          (previewResult.sql || []).join('\n') || '-- 无 SQL 变更'
        }}</pre>
      </details>
    </template>
    <template #footer>
      <el-button :disabled="applying" @click="previewDialogVisible = false">返回修改</el-button>
      <el-button
        type="primary"
        data-testid="apply-grants-btn"
        :loading="applying"
        :disabled="applying"
        @click="handleApplyGrants"
      >
        确认执行
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { toRefs } from 'vue'
import { DEFAULT_ACTIONS } from '../../composables/permissions/permissionLabels'

const actionLabels = (actions) => (actions || []).map((key) =>
  DEFAULT_ACTIONS.find((a) => a.key === key)?.label || key).join('、') || '无'

const props = defineProps({ model: { type: Object, required: true } })
// The feature composable owns these drafts and operations; this view only renders its contract.
const {
  formatDateTime,
  getResourceTypeLabel,
  countRoleMembers,
  selectedRole,
  applying,
  previewDialogVisible,
  previewResult,
  handleApplyGrants,
} = toRefs(props.model)
</script>

<style scoped lang="scss">
@use './permission-shared';

.preview-summary-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.preview-summary-bar strong {
  margin-right: 10px;
}

.section-mini-label {
  display: block;
  font-size: 12px;
  color: #667085;
  margin-bottom: 6px;
}

.native-sql {
  background: #f6f8fb;
  padding: 12px;
  font:
    12px/1.7 'SFMono-Regular',
    Consolas,
    monospace;
  white-space: pre-wrap;
  overflow: auto;
  color: #344054;
  border-radius: 4px;
  max-height: 220px;
  margin: 0;
}
</style>
