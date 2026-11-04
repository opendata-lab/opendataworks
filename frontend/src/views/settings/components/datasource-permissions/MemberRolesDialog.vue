<template>
  <el-dialog v-model="roleAssignDialogVisible" title="分配角色" width="460px" append-to-body>
    <div v-if="roleAssignTarget" class="dialog-source-strip">
      <strong>{{ getMemberDisplayName(roleAssignTarget) }}</strong>
      <span>{{ activeClusterName }}</span>
    </div>
    <div class="role-checkbox-list" role="group" aria-label="可分配角色">
      <label
        v-for="r in overview.roles"
        :key="r.id"
        class="role-check-item"
      >
        <input
          type="checkbox"
          :data-testid="`member-role-check-${r.id}`"
          :checked="roleAssignForm.roleIds.includes(r.id)"
          @change="toggleMemberRole(r.id, $event.target.checked)"
        />
        <div class="role-check-meta">
          <strong>{{ r.name }}</strong>
          <small>
            {{ summarizeRoleGrants(r) }}
          </small>
        </div>
      </label>
    </div>
    <div class="picker-count">已选 {{ roleAssignForm.roleIds.length }} 个角色</div>
    <p v-if="roleAssignError" role="alert" class="form-error">{{ roleAssignError }}</p>
    <template #footer>
      <el-button :disabled="roleAssignSaving" @click="roleAssignDialogVisible = false"
        >取消</el-button
      >
      <el-button
        type="primary"
        data-testid="save-member-roles-btn"
        :loading="roleAssignSaving"
        :disabled="roleAssignSaving"
        @click="submitRoleAssign"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { toRefs } from 'vue'

const props = defineProps({ model: { type: Object, required: true } })
// The feature composable owns these drafts and operations; this view only renders its contract.
const {
  overview,
  activeClusterName,
  getMemberDisplayName,
  summarizeRoleGrants,
  roleAssignDialogVisible,
  roleAssignSaving,
  roleAssignError,
  roleAssignTarget,
  roleAssignForm,
  toggleMemberRole,
  submitRoleAssign,
} = toRefs(props.model)
</script>

<style scoped lang="scss">
@use './permission-shared';

.picker-count {
  font-size: 12px;
  color: #667085;
  padding-top: 10px;
}
</style>
