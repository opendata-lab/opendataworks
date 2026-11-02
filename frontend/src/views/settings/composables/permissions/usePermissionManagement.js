import { ref, reactive, computed } from 'vue'
import { permissionsApi } from '@/api/permissions'
import { ElMessage } from 'element-plus'

export function usePermissionManagement({
  captureRequest,
  overview,
  loadResources,
  selectedDatabase,
}) {
  const managementDialogVisible = ref(false)

  const managementSaving = ref(false)

  const managementError = ref('')

  const managementForm = reactive({
    username: '',
    password: '',
  })

  const isKeepingSameManagementAccount = computed(() => {
    const mgmt = overview.management
    if (!mgmt?.configured || !mgmt?.username) return false
    return managementForm.username.trim() === mgmt.username
  })

  function clearManagementDialog() {
    managementForm.password = ''
    managementError.value = ''
  }

  function openManagementDialog() {
    managementForm.username = overview.management?.username || ''
    managementForm.password = ''
    managementError.value = ''
    managementDialogVisible.value = true
  }

  async function submitManagementAccount() {
    const request = captureRequest()
    if (managementSaving.value) return
    managementError.value = ''
    const username = managementForm.username.trim()
    const password = managementForm.password

    if (!username) {
      managementError.value = '请输入授权管理账号'
      return
    }
    if (!password && !isKeepingSameManagementAccount.value) {
      managementError.value = '首次配置或更换管理账号时，请输入密码'
      return
    }

    managementSaving.value = true
    try {
      const res = await permissionsApi.saveManagementAccount(request.sourceId, {
        username,
        password: password || '',
      })
      if (!request.isCurrent()) return
      overview.management = res || { username, configured: true }
      managementForm.password = ''
      managementDialogVisible.value = false
      ElMessage.success('授权管理连接已保存')
      await loadResources(selectedDatabase.value, request.sourceId)
      if (!request.isCurrent()) return
    } catch (error) {
      if (!request.isCurrent()) return
      managementError.value = error?.message || '保存授权管理连接失败'
    } finally {
      if (request.isCurrent()) {
        managementSaving.value = false
      }
    }
  }

  function reset() {
    managementDialogVisible.value = false
    managementSaving.value = false
    managementForm.username = ''
    clearManagementDialog()
  }

  return {
    reset,
    managementDialogVisible,
    managementSaving,
    managementError,
    managementForm,
    isKeepingSameManagementAccount,
    clearManagementDialog,
    openManagementDialog,
    submitManagementAccount,
  }
}
