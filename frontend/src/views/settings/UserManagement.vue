<template>
  <div class="user-management-page">
    <div class="page-heading-bar">
      <div>
        <span class="sub-title">管理普通用户与服务账号；数据访问范围在对应数据源「权限配置」中分配</span>
      </div>
      <div class="actions">
        <el-button type="primary" data-testid="new-user-btn" @click="openCreate">
          <el-icon><Plus /></el-icon>
          新增账号
        </el-button>
      </div>
    </div>

    <div class="list-toolbar">
      <div class="filter-controls">
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="搜索用户名、姓名、邮箱"
          aria-label="搜索用户"
          class="search-input"
          @clear="loadUsers"
          @keyup.enter="loadUsers"
        />
        <el-select v-model="filters.accountType" placeholder="全部类型" aria-label="账号类型筛选" class="status-select" @change="loadUsers">
          <el-option label="全部类型" value="" /><el-option label="普通用户" value="USER" /><el-option label="服务账号" value="SERVICE" />
        </el-select>
        <el-select
          v-model="filters.enabled"
          placeholder="全部状态"
          aria-label="状态筛选"
          class="status-select"
          @change="loadUsers"
        >
          <el-option label="全部状态" value="" />
          <el-option label="正常" :value="true" />
          <el-option label="已停用" :value="false" />
        </el-select>
        <el-button :loading="loading" @click="loadUsers">查询</el-button>
      </div>
      <span class="user-count">共 {{ displayedUsers.length }} 个账号</span>
    </div>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      show-icon
      class="load-alert"
    >
      <el-button text type="primary" @click="loadUsers">重试</el-button>
    </el-alert>

    <el-table
      v-else
      v-loading="loading"
      :data="displayedUsers"
      border
      empty-text="没有匹配的账号"
      style="width: 100%"
    >
      <el-table-column prop="username" label="账号标识" min-width="180" show-overflow-tooltip />
      <el-table-column label="名称" min-width="130">
        <template #default="{ row }">
          <span>{{ row.nickname || row.name || '-' }}</span>
          <el-tag v-if="isSelf(row)" size="small" type="info" class="self-tag">当前登录</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="110"><template #default="{ row }"><el-tag :type="isService(row) ? 'primary' : 'info'" size="small">{{ isService(row) ? '服务账号' : '普通用户' }}</el-tag></template></el-table-column>
      <el-table-column label="邮箱" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.email || '-' }}</template>
      </el-table-column>
      <el-table-column label="平台角色" width="120">
        <template #default="{ row }">
          <el-tag :type="row.role === 'admin' ? 'warning' : 'info'" size="small">
            {{ isService(row) ? '无管理权限' : row.role === 'admin' ? '管理员' : '普通用户' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="登录方式" width="120">
        <template #default="{ row }">
          {{ isService(row) ? 'Token' : isLocalUser(row) ? '本地账号' : row.authSource }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.enabled !== false ? 'success' : 'danger'" size="small">
            {{ row.enabled !== false ? '正常' : '已停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" min-width="165">
        <template #default="{ row }">{{ formatDateTime(row.lastLoginAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="250" class-name="user-actions">
        <template #default="{ row }">
          <el-button type="primary" text size="small" @click="openEdit(row)">编辑</el-button>
          <el-button
            :type="row.enabled !== false ? 'warning' : 'success'"
            text
            size="small"
            :disabled="saving || (row.enabled !== false && !! getProtectionReason(row, 'disable'))"
            :title="row.enabled !== false ? getProtectionReason(row, 'disable') : ''"
            @click="handleToggleEnabled(row)"
          >
            {{ row.enabled !== false ? '停用' : '启用' }}
          </el-button>
          <el-button v-if="isService(row)" type="primary" text size="small" @click="openTokens(row)">管理 Token</el-button>
          <el-button
            v-else
            type="primary"
            text
            size="small"
            :disabled="!isLocalUser(row)"
            :title="!isLocalUser(row) ? '第三方账号不支持设置本地密码' : ''"
            @click="openResetPassword(row)"
          >
            重置密码
          </el-button>
          <el-button
            type="danger"
            text
            size="small"
            :disabled="saving || !!getProtectionReason(row, 'delete')"
            :title="getProtectionReason(row, 'delete')"
            @click="handleDelete(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑账号' : '新增账号'"
      width="480px"
      @closed="clearDialogState"
    >
      <el-form ref="formRef" :model="form" label-width="100px" @submit.prevent="submitUser">
        <el-form-item label="账号类型">
          <el-radio-group v-if="!isEdit" v-model="form.accountType" aria-label="新建账号类型" @change="handleAccountTypeChange">
            <el-radio value="USER" label="USER">普通用户</el-radio><el-radio value="SERVICE" label="SERVICE">服务账号</el-radio>
          </el-radio-group>
          <el-text v-else>{{ form.accountType === 'SERVICE' ? '服务账号' : '普通用户' }}（不可修改）</el-text>
        </el-form-item>
        <p v-if="!isEdit" class="account-type-hint">{{ form.accountType === 'SERVICE' ? '供 DataAgent 等应用通过 Token 调用，不能登录平台。' : '供人员登录平台，使用用户名和密码。' }}</p>
        <el-form-item v-if="form.accountType !== 'SERVICE'" label="用户名" required>
          <el-input
            v-model="form.username"
            :disabled="isEdit"
            autocomplete="off"
            placeholder="用于登录平台，创建后不可修改"
            aria-label="平台用户名"
          />
        </el-form-item>
        <el-form-item :label="form.accountType === 'SERVICE' ? '账号名称' : '姓名'" required>
          <el-input
            v-model="form.nickname"
            :placeholder="form.accountType === 'SERVICE' ? '例如：智能问数服务' : '请输入姓名或昵称'"
            :aria-label="form.accountType === 'SERVICE' ? '账号名称' : '姓名'"
          />
        </el-form-item>
        <el-form-item v-if="form.accountType === 'SERVICE'" label="用途">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="例如：DataAgent 访问生产数据" aria-label="服务账号用途" />
        </el-form-item>
        <el-form-item v-if="form.accountType !== 'SERVICE'" label="邮箱">
          <el-input
            v-model="form.email"
            placeholder="选填，例如 user@example.com"
            aria-label="邮箱"
          />
        </el-form-item>
        <el-form-item v-if="form.accountType !== 'SERVICE'" label="平台角色">
          <el-select
            v-model="form.role"
            :disabled="isEdit && !!getProtectionReason(editingUser, 'demote')"
            style="width: 100%"
            aria-label="平台角色"
          >
            <el-option label="普通用户" value="user" />
            <el-option label="管理员" value="admin" />
          </el-select>
          <div v-if="isEdit && getProtectionReason(editingUser, 'demote')" class="field-hint">
            {{ getProtectionReason(editingUser, 'demote') }}
          </div>
        </el-form-item>
        <el-form-item v-if="!isEdit && form.accountType !== 'SERVICE'" label="初始登录密码" required>
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="至少 8 位，最多 72 字节初始登录密码"
            aria-label="初始登录密码"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="form.enabled"
            :disabled="isEdit && !!getProtectionReason(editingUser, 'disable')"
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
      </el-form>
      <p v-if="formError" role="alert" class="form-error">{{ formError }}</p>
      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="saving" @click="submitUser">
          {{ isEdit ? '保存' : '创建账号' }}
        </el-button>
      </template>
    </el-dialog>

    <ServiceAccountTokens v-model="tokensVisible" :account="tokenTarget" />

    <el-dialog
      v-model="resetDialogVisible"
      title="重置登录密码"
      width="440px"
      @closed="clearResetState"
    >
      <div v-if="resetTarget" class="dialog-context">
        为用户 <strong>{{ resetTarget.nickname || resetTarget.username }}</strong>（{{ resetTarget.username }}）设置新密码。重置后其现有会话将立即失效。
      </div>
      <el-form label-width="96px" @submit.prevent="submitResetPassword">
        <el-form-item label="新密码" required>
          <el-input
            v-model="resetForm.password"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="请输入 至少 8 位，最多 72 字节新密码"
            aria-label="重置新密码"
          />
        </el-form-item>
      </el-form>
      <p v-if="resetError" role="alert" class="form-error">{{ resetError }}</p>
      <template #footer>
        <el-button :disabled="resetting" @click="resetDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetting" :disabled="resetting" @click="submitResetPassword">
          确认重置
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { getActivePinia } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { usersApi } from '@/api/users'
import ServiceAccountTokens from './components/ServiceAccountTokens.vue'
import { useAuthStore } from '@/stores/auth'
import { validateNewUserDraft, validatePasswordBytes } from '@/utils/userValidation'

const authStore = getActivePinia() ? useAuthStore() : { currentUser: null, isAdmin: true }

const loading = ref(false)
const saving = ref(false)
const resetting = ref(false)
const loadError = ref('')
const users = ref([])

const filters = reactive({
  keyword: '',
  enabled: '',
  accountType: ''
})

const dialogVisible = ref(false)
const isEdit = ref(false)
const editingUser = ref(null)
const formRef = ref(null)
const formError = ref('')
const form = reactive({
  accountType: 'USER',
  description: '',
  username: '',
  nickname: '',
  email: '',
  role: 'user',
  password: '',
  enabled: true
})

const resetDialogVisible = ref(false)
const resetTarget = ref(null)
const resetError = ref('')
const resetForm = reactive({
  password: ''
})

const tokensVisible = ref(false)
const tokenTarget = ref(null)
const isService = user => user?.accountType === 'SERVICE'
function openTokens(row) { tokenTarget.value = row; tokensVisible.value = true }
function handleAccountTypeChange() { form.password = ''; form.email = ''; form.role = 'user'; formError.value = '' }

const isLocalUser = (user) => !user?.authSource || String(user.authSource).toLowerCase() === 'local'

const isSelf = (user) => {
  if (!user || !authStore.currentUser) return false
  if (user.id !== undefined && authStore.currentUser.id !== undefined) {
    return String(user.id) === String(authStore.currentUser.id)
  }
  return Boolean(user.username && user.username === authStore.currentUser.username)
}

const enabledAdminCount = computed(() =>
  users.value.filter((u) => u.role === 'admin' && u.enabled !== false).length
)

const isLastEnabledAdmin = (user) =>
  Boolean(user && user.role === 'admin' && user.enabled !== false && !filters.keyword && filters.enabled === '' && !filters.accountType && enabledAdminCount.value <= 1)

const getProtectionReason = (user, action) => {
  if (!user) return ''
  if (isSelf(user)) {
    if (action === 'disable') return '不能停用当前登录账号'
    if (action === 'delete') return '不能删除当前登录账号'
    if (action === 'demote') return '不能降级当前登录管理员角色'
  }
  if (isLastEnabledAdmin(user)) {
    if (action === 'disable') return '不能停用最后一个启用中的管理员'
    if (action === 'delete') return '不能删除最后一个启用中的管理员'
    if (action === 'demote') return '不能移除最后一个启用中的管理员'
  }
  return ''
}

const displayedUsers = computed(() => {
  const kw = String(filters.keyword ?? '').trim().toLowerCase()
  return users.value.filter((user) => {
    if (filters.accountType && (user.accountType || 'USER') !== filters.accountType) return false
    if (filters.enabled !== '' && Boolean(user.enabled !== false) !== Boolean(filters.enabled)) {
      return false
    }
    if (!kw) return true
    const haystack = [user.username, user.nickname, user.name, user.email]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return haystack.includes(kw)
  })
})

const formatDateTime = (value) => {
  if (!value) return '-'
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss')
}

let loadSeq = 0
async function loadUsers() {
  const seq = ++loadSeq
  loading.value = true
  loadError.value = ''
  try {
    const res = await usersApi.list({
      keyword: filters.keyword?.trim() || '',
      enabled: filters.enabled,
      accountType: filters.accountType
    })
    if (seq !== loadSeq) return
    users.value = Array.isArray(res) ? res : []
  } catch (error) {
    if (seq !== loadSeq) return
    loadError.value = error?.message || '加载用户列表失败'
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

function clearDialogState() {
  form.password = ''
  formError.value = ''
}

function openCreate() {
  isEdit.value = false
  editingUser.value = null
  form.accountType = 'USER'
  form.description = ''
  form.username = ''
  form.nickname = ''
  form.email = ''
  form.role = 'user'
  form.password = ''
  form.enabled = true
  formError.value = ''
  dialogVisible.value = true
}

function openEdit(row) {
  isEdit.value = true
  editingUser.value = row
  form.accountType = row.accountType || 'USER'
  form.description = row.description || ''
  form.username = row.username || ''
  form.nickname = row.nickname || row.name || ''
  form.email = row.email || ''
  form.role = row.role || 'user'
  form.password = ''
  form.enabled = row.enabled !== false
  formError.value = ''
  dialogVisible.value = true
}

async function submitUser() {
  if (saving.value) return
  formError.value = ''

  if (form.accountType === 'SERVICE') {
    const name = form.nickname.trim()
    if (!name || name.length > 100) { formError.value = '账号名称不能为空且最多100字'; return }
    if (form.description.length > 500) { formError.value = '用途最多500字'; return }
    saving.value = true
    try {
      const payload = { nickname: name, description: form.description.trim(), enabled: form.enabled }
      const account = isEdit.value ? await usersApi.update(editingUser.value.id, payload)
        : await usersApi.create({ accountType: 'SERVICE', ...payload })
      dialogVisible.value = false
      ElMessage.success(isEdit.value ? '账号资料已更新' : '服务账号已创建')
      if (!isEdit.value && account?.id) openTokens(account)
      await loadUsers()
    } catch (error) { formError.value = error?.message || '保存服务账号失败' }
    finally { saving.value = false }
    return
  }

  if (isEdit.value) {
    const nickname = String(form.nickname ?? '').trim()
    const email = String(form.email ?? '').trim()
    if (!nickname) {
      formError.value = '请输入姓名'
      return
    }
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      formError.value = '请输入有效的邮箱地址'
      return
    }
    if (!form.enabled && getProtectionReason(editingUser.value, 'disable')) {
      formError.value = getProtectionReason(editingUser.value, 'disable')
      return
    }
    if (form.role !== 'admin' && editingUser.value?.role === 'admin' && getProtectionReason(editingUser.value, 'demote')) {
      formError.value = getProtectionReason(editingUser.value, 'demote')
      return
    }

    saving.value = true
    try {
      await usersApi.update(editingUser.value.id, {
        nickname,
        email,
        role: form.role,
        enabled: form.enabled
      })
      dialogVisible.value = false
      ElMessage.success('用户资料已更新')
      await loadUsers()
    } catch (error) {
      formError.value = error?.message || '更新用户失败'
    } finally {
      saving.value = false
    }
    return
  }

  const check = validateNewUserDraft(form, users.value)
  if (!check.valid) {
    formError.value = check.message
    return
  }

  saving.value = true
  try {
    await usersApi.create({
      accountType: 'USER',
      username: form.username.trim(),
      nickname: form.nickname.trim(),
      email: form.email.trim(),
      password: form.password,
      role: form.role,
      enabled: form.enabled
    })
    form.password = ''
    dialogVisible.value = false
    ElMessage.success('用户已创建')
    await loadUsers()
  } catch (error) {
    formError.value = error?.message || '创建用户失败'
  } finally {
    saving.value = false
  }
}

async function handleToggleEnabled(row) {
  if (saving.value) return
  const targetEnabled = row.enabled === false
  if (!targetEnabled) {
    const reason = getProtectionReason(row, 'disable')
    if (reason) {
      ElMessage.warning(reason)
      return
    }
  }
  saving.value = true
  try {
    await usersApi.update(row.id, { enabled: targetEnabled })
    ElMessage.success(targetEnabled ? '已启用用户' : '已停用用户')
    await loadUsers()
  } finally {
    saving.value = false
  }
}

function clearResetState() {
  resetForm.password = ''
  resetError.value = ''
}

function openResetPassword(row) {
  if (isService(row)) { ElMessage.warning('服务账号请管理 Token'); return }
  if (!isLocalUser(row)) {
    ElMessage.warning('第三方账号不支持设置本地密码')
    return
  }
  resetTarget.value = row
  resetForm.password = ''
  resetError.value = ''
  resetDialogVisible.value = true
}

async function submitResetPassword() {
  if (resetting.value || !resetTarget.value) return
  resetError.value = ''
  const check = validatePasswordBytes(resetForm.password, '新密码')
  if (!check.valid) {
    resetError.value = check.message
    return
  }

  resetting.value = true
  try {
    await usersApi.resetPassword(resetTarget.value.id, {
      password: resetForm.password
    })
    resetForm.password = ''
    resetDialogVisible.value = false
    ElMessage.success('密码已重置')
  } catch (error) {
    resetError.value = error?.message || '重置密码失败'
  } finally {
    resetting.value = false
  }
}

async function handleDelete(row) {
  const reason = getProtectionReason(row, 'delete')
  if (reason) {
    ElMessage.warning(reason)
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除用户「${row.nickname || row.username}」吗？删除后该用户将无法登录平台。`,
      '删除用户',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  saving.value = true
  try {
    await usersApi.remove(row.id)
    ElMessage.success('用户已删除')
    await loadUsers()
  } finally {
    saving.value = false
  }
}

onMounted(loadUsers)
</script>

<style scoped>
.account-type-hint { margin: -8px 0 18px 100px; color: var(--el-text-color-secondary); line-height: 1.6; font-size: 12px; }
.user-management-page {
  min-width: 0;
}

.page-heading-bar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
}

.sub-title {
  display: block;
  margin-top: 4px;
  color: #6b7280;
  font-size: 13px;
}

:deep(.user-actions .cell) {
  display: flex;
  align-items: center;
  white-space: nowrap;
}

:deep(.user-actions .el-button + .el-button) {
  margin-left: 2px;
}

.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.filter-controls {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.search-input {
  width: 240px;
}

.status-select {
  width: 140px;
}

.user-count {
  font-size: 12px;
  color: #667085;
}

.load-alert {
  margin-bottom: 14px;
}

.self-tag {
  margin-left: 6px;
}

.dialog-context {
  font-size: 13px;
  color: #4b5563;
  line-height: 1.6;
  margin-bottom: 16px;
}

.field-hint {
  font-size: 12px;
  color: #8e620d;
  margin-top: 4px;
  line-height: 1.4;
}

.form-error {
  color: #b63737;
  font-size: 12px;
  margin: 8px 0 0;
}
</style>
