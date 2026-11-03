<template>
  <el-dialog
    v-model="authorizeDialogVisible"
    title="授权成员"
    width="480px"
    append-to-body
    @closed="clearAuthorizeDialog"
  >
    <div class="dialog-source-strip">
      <span
        >数据源：<strong>{{ activeClusterName }}</strong></span
      >
    </div>

    <el-radio-group
      v-model="authorizeForm.mode"
      class="mode-radio-group"
      @change="authorizeError = ''"
    >
      <el-radio value="existing" label="existing">已有用户</el-radio>
      <el-radio value="new" label="new">新建用户</el-radio>
      <el-radio value="service" label="service">服务账号</el-radio>
    </el-radio-group>

    <el-form label-width="100px" @submit.prevent="submitAuthorize">
      <template v-if="authorizeForm.mode === 'existing'">
        <el-form-item label="平台用户" required>
          <el-select
            v-model="authorizeForm.userId"
            filterable
            placeholder="请选择平台用户"
            style="width: 100%"
            aria-label="选择已有用户"
          >
            <el-option
              v-for="u in platformUsers.filter((u) => u.accountType !== 'SERVICE')"
              :key="u.id"
              :label="`${u.nickname || u.username}（${u.username}）${u.enabled === false ? ' [已停用]' : ''}`"
              :value="String(u.id)"
            />
          </el-select>
        </el-form-item>
      </template>

      <template v-else-if="authorizeForm.mode === 'new'">
        <el-form-item label="用户名" required>
          <el-input
            v-model="authorizeForm.username"
            autocomplete="off"
            placeholder="用于登录平台"
            aria-label="新建平台用户名"
          />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input
            v-model="authorizeForm.nickname"
            placeholder="输入姓名"
            aria-label="新建用户姓名"
          />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="authorizeForm.email" placeholder="选填" aria-label="新建用户邮箱" />
        </el-form-item>
        <el-form-item label="初始登录密码" required>
          <el-input
            v-model="authorizeForm.password"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="至少 8 位，最多 72 字节初始登录密码"
            aria-label="新建用户初始密码"
          />
        </el-form-item>
      </template>

      <template v-else>
        <el-form-item label="服务账号" required>
          <el-select
            v-model="authorizeForm.serviceAccountId"
            filterable
            placeholder="选择平台服务账号"
            aria-label="选择服务账号"
            style="width: 100%"
          >
            <el-option
              v-for="u in platformUsers.filter((u) => u.accountType === 'SERVICE')"
              :key="u.id"
              :value="String(u.id)"
              :label="u.nickname || u.username"
              :disabled="u.enabled === false"
            />
          </el-select>
          <p v-if="!platformUsers.some((u) => u.accountType === 'SERVICE')" class="sub-cell-text">
            暂无服务账号，请先在管理中心的账号管理中创建。
          </p>
        </el-form-item>
      </template>

      <el-form-item label="分配角色" required>
        <div class="role-checkbox-list" role="group" aria-label="授权角色列表">
          <label
            v-for="r in overview.roles"
            :key="r.id"
            :class="[
              'role-check-item',
              { disabled: authorizeForm.mode === 'service' && isWriteRole(r) },
            ]"
          >
            <input
              type="checkbox"
              :data-testid="`auth-role-${r.id}`"
              :checked="authorizeForm.roleIds.includes(r.id)"
              :disabled="authorizeForm.mode === 'service' && isWriteRole(r)"
              @change="toggleAuthorizeRole(r.id, $event.target.checked)"
            />
            <div class="role-check-meta">
              <strong>{{ r.name }}</strong>
              <small>
                {{
                  authorizeForm.mode === 'service' && isWriteRole(r)
                    ? '服务账号仅可分配只读角色'
                    : summarizeRoleGrants(r)
                }}
              </small>
            </div>
          </label>
          <div v-if="!overview.roles.length" class="muted-text">
            当前数据源尚无角色，请先切到「角色」页签新建角色
          </div>
        </div>
      </el-form-item>
    </el-form>

    <p v-if="authorizeError" role="alert" class="form-error">{{ authorizeError }}</p>

    <template #footer>
      <el-button :disabled="authorizing" @click="authorizeDialogVisible = false">取消</el-button>
      <el-button
        type="primary"
        data-testid="confirm-authorize-btn"
        :loading="authorizing"
        :disabled="authorizing"
        @click="submitAuthorize"
      >
        {{ authorizeForm.mode === 'new' ? '创建并授权' : '确认授权' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { toRefs } from 'vue'

const props = defineProps({ model: { type: Object, required: true } })
// The feature composable owns these drafts and operations; this view only renders its contract.
const {
  isWriteRole,
  overview,
  platformUsers,
  activeClusterName,
  summarizeRoleGrants,
  authorizeDialogVisible,
  authorizing,
  authorizeError,
  authorizeForm,
  clearAuthorizeDialog,
  toggleAuthorizeRole,
  submitAuthorize,
} = toRefs(props.model)
</script>

<style scoped lang="scss">
@use './permission-shared';

.mode-radio-group {
  margin-bottom: 16px;
}
</style>
