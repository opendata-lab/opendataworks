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

      <div class="sql-preview-section">
        <span class="section-mini-label"
          >待执行原生 SQL ({{ (previewResult.sql || []).length }} 条)</span
        >
        <pre class="native-sql" data-testid="preview-sql">{{
          (previewResult.sql || []).join('\n') || '-- 无 SQL 变更'
        }}</pre>
      </div>
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

const props = defineProps({ model: { type: Object, required: true } })
// The feature composable owns these drafts and operations; this view only renders its contract.
const {
  formatDateTime,
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
