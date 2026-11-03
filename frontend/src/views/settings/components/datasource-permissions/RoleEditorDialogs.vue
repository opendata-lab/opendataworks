<template>
  <el-dialog v-model="createRoleDialogVisible" title="新建角色" width="460px" append-to-body>
    <el-form label-width="96px" @submit.prevent="submitCreateRole">
      <el-form-item label="角色名称" required>
        <el-input
          v-model="createRoleForm.name"
          placeholder="例如：销售分析员"
          aria-label="角色名称"
        />
      </el-form-item>
      <el-form-item label="权限模板" required>
        <el-select v-model="createRoleForm.template" style="width: 100%" aria-label="权限模板">
          <el-option
            v-for="tpl in ROLE_TEMPLATES"
            :key="tpl.value"
            :label="tpl.label"
            :value="tpl.value"
          />
        </el-select>
        <div class="field-hint">{{ currentTemplateDescription }}</div>
      </el-form-item>
    </el-form>
    <p class="field-hint">
      角色映射数据库无登录 ROLE，不保存数据库账号密码；创建后请在资源矩阵中勾选范围并预览生效。
    </p>
    <p v-if="createRoleError" role="alert" class="form-error">{{ createRoleError }}</p>
    <template #footer>
      <el-button :disabled="creatingRole" @click="createRoleDialogVisible = false">取消</el-button>
      <el-button
        type="primary"
        data-testid="confirm-create-role-btn"
        :loading="creatingRole"
        :disabled="creatingRole"
        @click="submitCreateRole"
      >
        创建
      </el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="renameRoleDialogVisible" title="修改角色名称" width="420px" append-to-body>
    <el-form label-width="90px" @submit.prevent="submitRenameRole">
      <el-form-item label="角色名称" required>
        <el-input v-model="renameRoleForm.name" placeholder="输入新的角色显示名" />
      </el-form-item>
    </el-form>
    <p v-if="renameRoleError" role="alert" class="form-error">{{ renameRoleError }}</p>
    <template #footer>
      <el-button :disabled="renamingRole" @click="renameRoleDialogVisible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="renamingRole"
        :disabled="renamingRole"
        @click="submitRenameRole"
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
  ROLE_TEMPLATES,
  createRoleDialogVisible,
  creatingRole,
  createRoleError,
  createRoleForm,
  renameRoleDialogVisible,
  renamingRole,
  renameRoleError,
  renameRoleForm,
  currentTemplateDescription,
  submitCreateRole,
  submitRenameRole,
} = toRefs(props.model)
</script>

<style scoped lang="scss">
@use './permission-shared';
</style>
