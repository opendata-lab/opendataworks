import { ref, reactive, computed, watch } from 'vue'
import { ROLE_TEMPLATES } from './permissionLabels'
import { permissionsApi } from '@/api/permissions'
import { ElMessage, ElMessageBox } from 'element-plus'

export function usePermissionRoleActions({
  captureRequest,
  overview,
  loadAllForSource,
  selectedRoleId,
  syncDraftFromSelectedRole,
  countRoleMembers,
  operationDetail,
  operationDetailVisible,
  selectedRole,
  buildDraftGrantsPayload,
}) {
  const createRoleDialogVisible = ref(false)

  const creatingRole = ref(false)

  const createRoleError = ref('')

  const createRoleForm = reactive({
    name: '',
    template: 'READONLY',
  })

  const renameRoleDialogVisible = ref(false)

  const renamingRole = ref(false)

  const renameRoleError = ref('')

  const renameRoleTarget = ref(null)

  const renameRoleForm = reactive({
    name: '',
    template: 'CUSTOM',
  })

  const previewing = ref(false)

  const applying = ref(false)

  const previewDialogVisible = ref(false)

  const previewResult = ref(null)

  let previewSeq = 0

  watch(
    selectedRoleId,
    () => {
      previewSeq += 1
      previewing.value = false
      previewDialogVisible.value = false
      previewResult.value = null
    },
    { flush: 'sync' }
  )

  const currentTemplateDescription = computed(() => {
    const item = ROLE_TEMPLATES.find((t) => t.value === createRoleForm.template)
    return item?.desc || ''
  })

  function openCreateRoleDialog() {
    createRoleForm.name = ''
    createRoleForm.template = 'READONLY'
    createRoleError.value = ''
    createRoleDialogVisible.value = true
  }

  async function submitCreateRole() {
    const request = captureRequest()
    if (creatingRole.value) return
    createRoleError.value = ''
    const name = createRoleForm.name.trim()
    if (!name) {
      createRoleError.value = '请输入角色名称'
      return
    }
    if (overview.roles.some((r) => r.name === name)) {
      createRoleError.value = '角色名称已存在，请使用其他名称'
      return
    }

    creatingRole.value = true
    try {
      const created = await permissionsApi.createRole(request.sourceId, {
        name,
        template: createRoleForm.template,
      })
      if (!request.isCurrent()) return
      createRoleDialogVisible.value = false
      await loadAllForSource(request.sourceId)
      if (!request.isCurrent()) return
      if (created?.id !== undefined) {
        selectedRoleId.value = created.id
        syncDraftFromSelectedRole()
      }
      ElMessage.success('角色已创建，请勾选资源后预览保存')
    } catch (error) {
      if (!request.isCurrent()) return
      createRoleError.value = error?.message || '创建角色失败'
    } finally {
      if (request.isCurrent()) {
        creatingRole.value = false
      }
    }
  }

  function openRenameRoleDialog(role) {
    renameRoleTarget.value = role
    renameRoleForm.name = role.name || ''
    renameRoleForm.template = role.template || 'CUSTOM'
    renameRoleError.value = ''
    renameRoleDialogVisible.value = true
  }

  async function submitRenameRole() {
    const request = captureRequest()
    if (renamingRole.value || !renameRoleTarget.value) return
    renameRoleError.value = ''
    const name = renameRoleForm.name.trim()
    if (!name) {
      renameRoleError.value = '请输入角色名称'
      return
    }

    renamingRole.value = true
    try {
      await permissionsApi.updateRole(request.sourceId, renameRoleTarget.value.id, { name, template: renameRoleForm.template })
      if (!request.isCurrent()) return
      renameRoleDialogVisible.value = false
      ElMessage.success('角色已更新')
      await loadAllForSource(request.sourceId)
      if (!request.isCurrent()) return
    } catch (error) {
      if (!request.isCurrent()) return
      renameRoleError.value = error?.message || '编辑角色失败'
    } finally {
      if (request.isCurrent()) {
        renamingRole.value = false
      }
    }
  }

  async function handleDeleteRole(role) {
    const request = captureRequest()
    if (countRoleMembers(role.id) > 0) {
      ElMessage.warning('请先移除该角色下的成员后再删除')
      return
    }
    try {
      await ElMessageBox.confirm(
        `确认删除角色「${role.name}」吗？将即时删除数据库托管原生角色（不删除成员登录账号）。`,
        '删除角色',
        {
          type: 'warning',
          confirmButtonText: '确认删除',
          cancelButtonText: '取消',
        }
      )
      if (!request.isCurrent()) return
    } catch {
      return
    }

    const result = await permissionsApi.deleteRole(request.sourceId, role.id)
    if (!request.isCurrent()) return
    if (result?.state !== 'SUCCESS') {
      operationDetail.value = result || { state: 'UNKNOWN', message: '未获取到角色删除结果' }
      operationDetailVisible.value = true
      ElMessage.error(
        `角色删除未完成，操作 ID：${result?.operationId || result?.id || '未知'}，请核对授权记录`
      )
      await loadAllForSource(request.sourceId)
      if (!request.isCurrent()) return
      return
    }
    ElMessage.success('角色已删除')
    if (String(selectedRoleId.value) === String(role.id)) {
      selectedRoleId.value = null
    }
    await loadAllForSource(request.sourceId)
    if (!request.isCurrent()) return
  }

  async function handlePreviewGrants() {
    const request = captureRequest()
    if (previewing.value || !selectedRole.value) return
    const seq = ++previewSeq
    const roleId = selectedRole.value.id
    previewing.value = true
    try {
      const payload = {
        expectedRevision: selectedRole.value.revision ?? 0,
        grants: buildDraftGrantsPayload(),
      }
      const res = await permissionsApi.previewRoleGrants(
        request.sourceId,
        selectedRole.value.id,
        payload
      )
      if (
        !request.isCurrent() ||
        seq !== previewSeq ||
        String(selectedRoleId.value) !== String(roleId)
      )
        return
      previewResult.value = res || { previewId: '', sql: [], warnings: [], memberCount: 0 }
      previewDialogVisible.value = true
    } finally {
      if (request.isCurrent() && seq === previewSeq) {
        previewing.value = false
      }
    }
  }

  async function handleApplyGrants() {
    const request = captureRequest()
    if (applying.value || !selectedRole.value || !previewResult.value?.previewId) return
    applying.value = true
    try {
      const res = await permissionsApi.applyRoleGrants(request.sourceId, selectedRole.value.id, {
        previewId: previewResult.value.previewId,
      })
      if (!request.isCurrent()) return
      previewDialogVisible.value = false
      const state = String(res?.state || 'UNKNOWN').toUpperCase()
      const opId = res?.operationId || res?.id || '-'

      if (state === 'SUCCESS') {
        ElMessage.success(res?.message || '权限变更已生效')
      } else {
        const statusMsg = `权限执行未完全成功（状态：${state}，操作ID：${opId}）${res?.message ? `：${res.message}` : ''}`
        ElMessage.error(statusMsg)
        operationDetail.value = {
          ...res,
          operationId: opId,
          state,
        }
        operationDetailVisible.value = true
      }

      await loadAllForSource(request.sourceId)
      if (!request.isCurrent()) return
    } finally {
      if (request.isCurrent()) {
        applying.value = false
      }
    }
  }

  function reset() {
    previewSeq += 1
    createRoleDialogVisible.value = false
    creatingRole.value = false
    createRoleError.value = ''
    createRoleForm.name = ''
    createRoleForm.template = 'READONLY'
    renameRoleDialogVisible.value = false
    renamingRole.value = false
    renameRoleError.value = ''
    renameRoleTarget.value = null
    renameRoleForm.name = ''
    previewing.value = false
    applying.value = false
    previewDialogVisible.value = false
    previewResult.value = null
  }

  return {
    reset,
    createRoleDialogVisible,
    creatingRole,
    createRoleError,
    createRoleForm,
    renameRoleDialogVisible,
    renamingRole,
    renameRoleError,
    renameRoleTarget,
    renameRoleForm,
    previewing,
    applying,
    previewDialogVisible,
    previewResult,
    currentTemplateDescription,
    openCreateRoleDialog,
    submitCreateRole,
    openRenameRoleDialog,
    submitRenameRole,
    handleDeleteRole,
    handlePreviewGrants,
    handleApplyGrants,
  }
}
