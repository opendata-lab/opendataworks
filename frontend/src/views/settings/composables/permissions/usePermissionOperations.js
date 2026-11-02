import { reactive, ref } from 'vue'
import { permissionsApi } from '@/api/permissions'

export function usePermissionOperations({ captureRequest, activeSourceId }) {
  const operationsState = reactive({
    loading: false,
    error: '',
    list: [],
  })

  const operationDetailVisible = ref(false)

  const operationDetail = ref(null)

  let opsLoadSeq = 0

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
    operationDetailVisible.value = false
    operationDetail.value = null
  }

  return {
    reset,
    operationsState,
    operationDetailVisible,
    operationDetail,
    loadOperations,
    openOperationDetail,
  }
}
