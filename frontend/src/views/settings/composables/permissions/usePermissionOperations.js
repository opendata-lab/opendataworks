import { reactive, ref, computed } from 'vue'
import { permissionsApi } from '@/api/permissions'

export function usePermissionOperations({
  captureRequest,
  activeSourceId,
  getRoleName,
  getPlatformUserName,
}) {
  const operationsState = reactive({
    loading: false,
    error: '',
    list: [],
  })

  const opFilters = reactive({
    keyword: '',
    target: 'all',
    state: 'all',
  })

  const operationDetailVisible = ref(false)

  const operationDetail = ref(null)

  let opsLoadSeq = 0

  const filteredOperations = computed(() => {
    const kw = String(opFilters.keyword || '')
      .trim()
      .toLowerCase()
    return operationsState.list.filter((op) => {
      if (opFilters.state !== 'all') {
        const s = String(op.state || '').toUpperCase()
        if (s !== String(opFilters.state).toUpperCase()) return false
      }
      if (opFilters.target !== 'all') {
        if (opFilters.target.startsWith('role:')) {
          const roleId = opFilters.target.slice(5)
          if (String(op.roleId) !== roleId) return false
        } else if (opFilters.target.startsWith('member:')) {
          const memberId = opFilters.target.slice(7)
          if (String(op.subjectId) !== memberId) return false
        }
      }
      if (!kw) return true
      const idMatch = String(op.id || op.operationId || '')
        .toLowerCase()
        .includes(kw)
      const msgMatch = String(op.message || '')
        .toLowerCase()
        .includes(kw)
      const roleName = op.roleId ? String(getRoleName?.(op.roleId) || '') : ''
      const memberName = op.subjectId ? String(getPlatformUserName?.(op.subjectId) || '') : ''
      return (
        idMatch ||
        msgMatch ||
        roleName.toLowerCase().includes(kw) ||
        memberName.toLowerCase().includes(kw)
      )
    })
  })

  async function loadOperations(targetSourceId = activeSourceId.value) {
    const request = captureRequest()
    if (!targetSourceId) return
    const seq = ++opsLoadSeq
    operationsState.loading = true
    operationsState.error = ''
    try {
      const res = await permissionsApi.listOperations(targetSourceId)
      if (!request.isCurrent()) return
      if (seq !== opsLoadSeq || String(request.sourceId) !== String(targetSourceId)) {
        return
      }
      operationsState.list = Array.isArray(res) ? res : []
    } catch (error) {
      if (!request.isCurrent()) return
      if (seq !== opsLoadSeq || String(request.sourceId) !== String(targetSourceId)) {
        return
      }
      operationsState.error = error?.message || '加载授权记录失败'
    } finally {
      if (request.isCurrent()) {
        if (seq === opsLoadSeq && String(request.sourceId) === String(targetSourceId)) {
          operationsState.loading = false
        }
      }
    }
  }

  function openOperationDetail(row) {
    operationDetail.value = {
      ...row,
      operationId: row.operationId || row.id,
    }
    operationDetailVisible.value = true
  }

  function reset() {
    opsLoadSeq += 1
    Object.assign(operationsState, { loading: false, error: '', list: [] })
    Object.assign(opFilters, { keyword: '', target: 'all', state: 'all' })
    operationDetailVisible.value = false
    operationDetail.value = null
  }

  return {
    reset,
    operationsState,
    opFilters,
    filteredOperations,
    operationDetailVisible,
    operationDetail,
    loadOperations,
    openOperationDetail,
  }
}
