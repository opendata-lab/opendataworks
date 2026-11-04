import { reactive, ref, computed } from 'vue'
import { permissionsApi } from '@/api/permissions'
import { ElMessage, ElMessageBox } from 'element-plus'
import { validateNewUserDraft } from '@/utils/userValidation'
import { usersApi } from '@/api/users'

export function usePermissionMembers({
  captureRequest,
  overview,
  getMemberStatus,
  getMemberDisplayName,
  getRoleName,
  platformUsers,
  loadAllForSource,
}) {
  const memberFilters = reactive({
    keyword: '',
    roleId: 'all',
    status: 'all',
  })

  const authorizeDialogVisible = ref(false)

  const authorizing = ref(false)

  const authorizeError = ref('')

  const authorizeForm = reactive({
    mode: 'existing',
    userId: '',
    username: '',
    nickname: '',
    email: '',
    password: '',
    serviceAccountId: '',
    roleIds: [],
  })

  const roleAssignDialogVisible = ref(false)

  const roleAssignSaving = ref(false)

  const roleAssignError = ref('')

  const roleAssignTarget = ref(null)

  const roleAssignForm = reactive({
    roleIds: [],
  })

  const accountDialogVisible = ref(false)

  const accountSaving = ref(false)

  const accountError = ref('')

  const accountTarget = ref(null)

  const accountForm = reactive({
    username: '',
    password: '',
  })

  const inspectDialogVisible = ref(false)

  const inspectTarget = ref(null)

  const inspectState = reactive({
    loading: false,
    error: '',
    data: null,
  })

  const filteredMembers = computed(() => {
    const kw = String(memberFilters.keyword ?? '')
      .trim()
      .toLowerCase()
    return overview.members.filter((m) => {
      if (
        memberFilters.roleId !== 'all' &&
        !(m.roleIds || []).some((id) => String(id) === String(memberFilters.roleId))
      ) {
        return false
      }
      const st = getMemberStatus(m)
      if (memberFilters.status !== 'all' && st.key !== memberFilters.status) {
        return false
      }
      if (!kw) return true
      const haystack = [
        getMemberDisplayName(m),
        m.subjectId,
        m.account?.username,
        ...(m.roleIds || []).map(getRoleName),
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
      return haystack.includes(kw)
    })
  })

  function clearAuthorizeDialog() {
    authorizeForm.password = ''
    authorizeError.value = ''
  }

  function openAuthorizeDialog(defaultMode = 'existing') {
    authorizeForm.mode = defaultMode
    authorizeForm.userId = ''
    authorizeForm.username = ''
    authorizeForm.nickname = ''
    authorizeForm.email = ''
    authorizeForm.password = ''
    authorizeForm.serviceAccountId = ''
    authorizeForm.roleIds = []
    authorizeError.value = ''
    authorizeDialogVisible.value = true
  }

  function toggleAuthorizeRole(roleId, checked) {
    const set = new Set(authorizeForm.roleIds)
    if (checked) set.add(roleId)
    else set.delete(roleId)
    authorizeForm.roleIds = [...set]
  }

  async function submitAuthorize() {
    const request = captureRequest()
    if (authorizing.value) return
    authorizeError.value = ''

    if (!authorizeForm.roleIds.length) {
      authorizeError.value = '请至少选择一个角色'
      return
    }

    if (authorizeForm.mode === 'service') {
      const serviceAccountId = String(authorizeForm.serviceAccountId ?? '').trim()
      if (!serviceAccountId) {
        authorizeError.value = '请选择平台服务账号'
        return
      }
      const account = platformUsers.value.find(
        (u) => String(u.id) === serviceAccountId && u.accountType === 'SERVICE'
      )
      if (!account || account.enabled === false) {
        authorizeError.value = '请选择启用的平台服务账号'
        return
      }

      authorizing.value = true
      try {
        const existingMember = overview.members.find(
          (m) => m.subjectType === 'SERVICE' && String(m.subjectId) === serviceAccountId
        )
        await permissionsApi.assignMemberRoles(request.sourceId, 'SERVICE', serviceAccountId, {
          roleIds: authorizeForm.roleIds,
          expectedRevision: existingMember?.revision ?? 0,
        })
        if (!request.isCurrent()) return
        authorizeDialogVisible.value = false
        ElMessage.success('服务账号角色已保存')
        await loadAllForSource(request.sourceId)
        if (!request.isCurrent()) return
      } catch (error) {
        if (!request.isCurrent()) return
        authorizeError.value = error?.message || '服务账号授权失败'
      } finally {
        if (request.isCurrent()) {
          authorizing.value = false
        }
      }
      return
    }

    if (authorizeForm.mode === 'existing') {
      const userId = String(authorizeForm.userId ?? '').trim()
      if (!userId) {
        authorizeError.value = '请选择平台用户'
        return
      }
      const targetUser = platformUsers.value.find((u) => String(u.id) === userId)
      if (!targetUser || targetUser.accountType === 'SERVICE' || targetUser.enabled === false) {
        authorizeError.value = '请选择启用的普通用户'
        return
      }

      authorizing.value = true
      try {
        const existingMember = overview.members.find(
          (m) => m.subjectType === 'USER' && String(m.subjectId) === userId
        )
        await permissionsApi.assignMemberRoles(request.sourceId, 'USER', userId, {
          roleIds: authorizeForm.roleIds,
          expectedRevision: existingMember?.revision ?? 0,
        })
        if (!request.isCurrent()) return
        authorizeDialogVisible.value = false
        ElMessage.success('用户授权已保存')
        await loadAllForSource(request.sourceId)
        if (!request.isCurrent()) return
      } catch (error) {
        if (!request.isCurrent()) return
        authorizeError.value = error?.message || '用户授权失败'
      } finally {
        if (request.isCurrent()) {
          authorizing.value = false
        }
      }
      return
    }

    // 新建用户并分配角色（两步：先调用用户 API，再分配角色；后一步失败必须明确说明已创建用户）
    const check = validateNewUserDraft(authorizeForm, platformUsers.value)
    if (!check.valid) {
      authorizeError.value = check.message
      return
    }

    authorizing.value = true
    let createdUser = null
    try {
      createdUser = await usersApi.create({
        username: authorizeForm.username.trim(),
        nickname: authorizeForm.nickname.trim(),
        email: authorizeForm.email.trim(),
        password: authorizeForm.password,
        role: 'user',
        enabled: true,
      })
      if (!request.isCurrent()) return
      authorizeForm.password = ''
      if (createdUser && createdUser.id !== undefined) {
        platformUsers.value = [createdUser, ...platformUsers.value]
      }
    } catch (error) {
      if (!request.isCurrent()) return
      authorizeError.value = error?.message || '创建平台用户失败'
      authorizing.value = false
      return
    }

    try {
      await permissionsApi.assignMemberRoles(request.sourceId, 'USER', createdUser.id, {
        roleIds: authorizeForm.roleIds,
        expectedRevision: 0,
      })
      if (!request.isCurrent()) return
      authorizeDialogVisible.value = false
      ElMessage.success('用户已创建并完成角色授权')
      await loadAllForSource(request.sourceId)
      if (!request.isCurrent()) return
    } catch (error) {
      if (!request.isCurrent()) return
      // 后一步失败：明确说明平台用户已创建，并切换为已有用户模式避免重复创建
      authorizeForm.mode = 'existing'
      authorizeForm.userId = String(createdUser.id)
      const msg = `已创建平台用户「${createdUser.nickname || createdUser.username}」（${createdUser.username}），但分配数据源角色失败：${error?.message || '请求失败'}。已为您切换至已有用户模式，可直接重试确认授权。`
      authorizeError.value = msg
      ElMessage.warning(msg)
    } finally {
      if (request.isCurrent()) {
        authorizing.value = false
      }
    }
  }

  function openRoleAssignDialog(member) {
    roleAssignTarget.value = member
    roleAssignForm.roleIds = [...(member.roleIds || [])]
    roleAssignError.value = ''
    roleAssignDialogVisible.value = true
  }

  function toggleMemberRole(roleId, checked) {
    const set = new Set(roleAssignForm.roleIds)
    if (checked) set.add(roleId)
    else set.delete(roleId)
    roleAssignForm.roleIds = [...set]
  }

  async function submitRoleAssign() {
    const request = captureRequest()
    if (roleAssignSaving.value || !roleAssignTarget.value) return
    roleAssignError.value = ''
    const target = roleAssignTarget.value

    roleAssignSaving.value = true
    try {
      await permissionsApi.assignMemberRoles(
        request.sourceId,
        target.subjectType,
        target.subjectId,
        {
          roleIds: roleAssignForm.roleIds,
          expectedRevision: target.revision ?? 0,
        }
      )
      if (!request.isCurrent()) return
      roleAssignDialogVisible.value = false
      ElMessage.success('角色分配已保存')
      await loadAllForSource(request.sourceId)
      if (!request.isCurrent()) return
    } catch (error) {
      if (!request.isCurrent()) return
      roleAssignError.value = error?.message || '保存角色分配失败'
    } finally {
      if (request.isCurrent()) {
        roleAssignSaving.value = false
      }
    }
  }

  const isKeepingSameMemberAccount = computed(() => {
    const orig = accountTarget.value?.account
    if (!orig?.configured || !orig?.username) return false
    return accountForm.username.trim() === orig.username
  })

  function clearAccountDialog() {
    accountForm.password = ''
    accountError.value = ''
  }

  function openAccountDialog(member) {
    accountTarget.value = member
    accountForm.username = member.account?.username || ''
    accountForm.password = ''
    accountError.value = ''
    accountDialogVisible.value = true
  }

  async function submitAccountBind() {
    const request = captureRequest()
    if (accountSaving.value || !accountTarget.value) return
    accountError.value = ''
    const username = accountForm.username.trim()
    const password = accountForm.password

    if (!username) {
      accountError.value = '请输入数据库账号'
      return
    }
    if (!password && !isKeepingSameMemberAccount.value) {
      accountError.value = '首次绑定或更换账号时，请输入密码'
      return
    }

    accountSaving.value = true
    try {
      const target = accountTarget.value
      await permissionsApi.bindMemberAccount(
        request.sourceId,
        target.subjectType,
        target.subjectId,
        {
          username,
          password: password || '',
          expectedRevision: target.revision ?? 0,
        }
      )
      if (!request.isCurrent()) return
      accountForm.password = ''
      accountDialogVisible.value = false
      ElMessage.success('数据库账号已绑定')
      await loadAllForSource(request.sourceId)
      if (!request.isCurrent()) return
    } catch (error) {
      if (!request.isCurrent()) return
      accountError.value = error?.message || '绑定数据库账号失败'
    } finally {
      if (request.isCurrent()) {
        accountSaving.value = false
      }
    }
  }

  async function handleRemoveMember(member) {
    const request = captureRequest()
    try {
      await ElMessageBox.confirm(
        `确认移除成员「${getMemberDisplayName(member)}」吗？将撤销其托管角色并删除该数据源账号映射（不删除平台用户与数据库账号）。`,
        '移除成员',
        {
          type: 'warning',
          confirmButtonText: '确认移除',
          cancelButtonText: '取消',
        }
      )
      if (!request.isCurrent()) return
    } catch {
      return
    }

    await permissionsApi.removeMember(request.sourceId, member.subjectType, member.subjectId)
    if (!request.isCurrent()) return
    ElMessage.success('成员已移除')
    await loadAllForSource(request.sourceId)
    if (!request.isCurrent()) return
  }

  const normalizedInspectGrants = computed(() => {
    const raw = inspectState.data?.grants
    if (!Array.isArray(raw)) return []
    return raw.map((item) => {
      if (typeof item === 'string') {
        return { scope: item, actionsText: '-', sourceText: '原生回读' }
      }
      const scope =
        item.scope ||
        item.object ||
        [item.database, item.tableName || (item.resourceType === 'SCHEMA' ? '*' : '')]
          .filter(Boolean)
          .join('.') ||
        item.resourceType ||
        '-'
      const actionsText = Array.isArray(item.actions)
        ? item.actions.join(', ')
        : item.privilege || item.action || '-'
      const sourceText = item.source || item.roleName || item.grantedBy || '数据库原生'
      return { scope, actionsText, sourceText }
    })
  })

  let inspectionSeq = 0

  async function openInspectDialog(member) {
    const seq = ++inspectionSeq
    const request = captureRequest()
    inspectTarget.value = member
    inspectState.loading = true
    inspectState.error = ''
    inspectState.data = null
    inspectDialogVisible.value = true
    try {
      const data = await permissionsApi.inspectMember(
        request.sourceId,
        member.subjectType,
        member.subjectId
      )
      if (!request.isCurrent() || seq !== inspectionSeq) return
      inspectState.data = data
    } catch (error) {
      if (!request.isCurrent() || seq !== inspectionSeq) return
      inspectState.error = error?.message || '核查数据库实际权限失败'
    } finally {
      if (request.isCurrent() && seq === inspectionSeq) {
        inspectState.loading = false
      }
    }
  }

  function reset() {
    inspectionSeq += 1
    Object.assign(memberFilters, { keyword: '', roleId: 'all', status: 'all' })
    authorizeDialogVisible.value = false
    authorizing.value = false
    Object.assign(authorizeForm, {
      mode: 'existing',
      userId: '',
      username: '',
      nickname: '',
      email: '',
      password: '',
      serviceAccountId: '',
      roleIds: [],
    })
    authorizeError.value = ''
    roleAssignDialogVisible.value = false
    roleAssignSaving.value = false
    roleAssignError.value = ''
    roleAssignTarget.value = null
    roleAssignForm.roleIds = []
    accountDialogVisible.value = false
    accountSaving.value = false
    accountError.value = ''
    accountTarget.value = null
    Object.assign(accountForm, { username: '', password: '' })
    inspectDialogVisible.value = false
    inspectTarget.value = null
    Object.assign(inspectState, { loading: false, error: '', data: null })
  }

  return {
    reset,
    memberFilters,
    authorizeDialogVisible,
    authorizing,
    authorizeError,
    authorizeForm,
    roleAssignDialogVisible,
    roleAssignSaving,
    roleAssignError,
    roleAssignTarget,
    roleAssignForm,
    accountDialogVisible,
    accountSaving,
    accountError,
    accountTarget,
    accountForm,
    inspectDialogVisible,
    inspectTarget,
    inspectState,
    filteredMembers,
    clearAuthorizeDialog,
    openAuthorizeDialog,
    toggleAuthorizeRole,
    submitAuthorize,
    openRoleAssignDialog,
    toggleMemberRole,
    submitRoleAssign,
    isKeepingSameMemberAccount,
    clearAccountDialog,
    openAccountDialog,
    submitAccountBind,
    handleRemoveMember,
    normalizedInspectGrants,
    openInspectDialog,
  }
}
