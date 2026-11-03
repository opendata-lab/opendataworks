<template>
  <el-dialog
    v-model="managementDialogVisible"
    title="授权管理连接配置"
    width="460px"
    append-to-body
    @closed="clearManagementDialog"
  >
    <p class="field-hint">
      用于当前数据源执行角色创建、GRANT / REVOKE
      与原生权限回读。不配置时默认复用数据源连接账号；绝不作为业务查询回退。
    </p>
    <el-form label-width="100px" @submit.prevent="submitManagementAccount">
      <el-form-item label="管理账号" required>
        <el-input
          v-model="managementForm.username"
          autocomplete="off"
          placeholder="输入具备 GRANT 权限的数据库账号"
          aria-label="授权管理账号"
        />
      </el-form-item>
      <el-form-item label="管理密码" :required="!isKeepingSameManagementAccount">
        <el-input
          v-model="managementForm.password"
          type="password"
          show-password
          autocomplete="new-password"
          :placeholder="isKeepingSameManagementAccount ? '留空保留原密码' : '输入管理账号密码'"
          aria-label="授权管理密码"
        />
      </el-form-item>
    </el-form>
    <p v-if="managementError" role="alert" class="form-error">{{ managementError }}</p>
    <template #footer>
      <el-button :disabled="managementSaving" @click="managementDialogVisible = false"
        >取消</el-button
      >
      <el-button
        type="primary"
        data-testid="save-management-btn"
        :loading="managementSaving"
        :disabled="managementSaving"
        @click="submitManagementAccount"
      >
        测试并保存
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { toRefs } from 'vue'

const props = defineProps({ model: { type: Object, required: true } })
// The feature composable owns these drafts and operations; this view only renders its contract.
const {
  managementDialogVisible,
  managementSaving,
  managementError,
  managementForm,
  isKeepingSameManagementAccount,
  clearManagementDialog,
  submitManagementAccount,
} = toRefs(props.model)
</script>

<style scoped lang="scss">
@use './permission-shared';
</style>
