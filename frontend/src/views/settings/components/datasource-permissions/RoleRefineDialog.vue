<template>
  <el-dialog
    v-model="refineDialogVisible"
    :title="`权限细分 · ${refineTarget?.displayName || ''}`"
    width="480px"
    append-to-body
  >
    <template v-if="refineTarget">
      <div class="dialog-source-strip">
        <code>{{ refineTarget.subLabel }}</code>
      </div>
      <div v-for="col in getRefinableColumns(refineTarget)" :key="col.key" class="refine-group">
        <div class="refine-group-head">
          <strong>{{ col.label || col.key }}</strong>
          <span v-if="col.note" class="muted-text">{{ col.note }}</span>
        </div>
        <div class="native-checkbox-grid">
          <label v-for="na in col.nativeActions" :key="na" class="native-option-label">
            <input
              type="checkbox"
              :checked="isNativeSubActionChecked(refineTarget, col, na)"
              :disabled="isActionDisabledForRole(col)"
              @change="toggleNativeSubAction(refineTarget, col, na, $event.target.checked)"
            />
            <code>{{ na }}</code>
          </label>
        </div>
      </div>
    </template>
    <template #footer>
      <el-button type="primary" @click="refineDialogVisible = false">完成</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { toRefs } from 'vue'

const props = defineProps({ model: { type: Object, required: true } })
// The feature composable owns these drafts and operations; this view only renders its contract.
const {
  refineDialogVisible,
  refineTarget,
  isActionDisabledForRole,
  getRefinableColumns,
  isNativeSubActionChecked,
  toggleNativeSubAction,
} = toRefs(props.model)
</script>

<style scoped lang="scss">
@use './permission-shared';

.refine-group {
  margin-bottom: 14px;
}

.refine-group-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.native-checkbox-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.native-option-label {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
</style>
