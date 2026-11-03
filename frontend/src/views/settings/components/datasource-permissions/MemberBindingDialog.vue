<template>
  <el-dialog
    v-model="accountDialogVisible"
    title="绑定数据库账号"
    width="440px"
    append-to-body
    @closed="clearAccountDialog"
  >
    <div v-if="accountTarget" class="dialog-source-strip">
      <strong>{{ getMemberDisplayName(accountTarget) }}</strong>
      <span>{{ activeClusterName }}</span>
    </div>
    <el-form label-width="96px" @submit.prevent="submitAccountBind">
      <el-form-item label="数据库账号" required>
        <el-input
          v-model="accountForm.username"
          autocomplete="off"
          placeholder="输入数据库登录账号"
          aria-label="成员数据库账号"
        />
      </el-form-item>
      <el-form-item label="数据库密码" :required="!isKeepingSameMemberAccount">
        <el-input
          v-model="accountForm.password"
          type="password"
          show-password
          autocomplete="new-password"
          :placeholder="isKeepingSameMemberAccount ? '留空保留原密码' : '输入数据库密码'"
          aria-label="成员数据库密码"
        />
      </el-form-item>
    </el-form>
    <p class="field-hint">
      每个成员在当前数据源绑定唯一登录账号，连接时自动激活其所属平台角色集合。
    </p>
    <p v-if="accountError" role="alert" class="form-error">{{ accountError }}</p>
    <template #footer>
      <el-button :disabled="accountSaving" @click="accountDialogVisible = false">取消</el-button>
      <el-button
        type="primary"
        data-testid="save-member-account-btn"
        :loading="accountSaving"
        :disabled="accountSaving"
        @click="submitAccountBind"
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
  activeClusterName,
  getMemberDisplayName,
  accountDialogVisible,
  accountSaving,
  accountError,
  accountTarget,
  accountForm,
  isKeepingSameMemberAccount,
  clearAccountDialog,
  submitAccountBind,
} = toRefs(props.model)
</script>

<style scoped lang="scss">
@use './permission-shared';
</style>
