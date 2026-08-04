import request from '@/utils/request'

export const workflowApi = {
  list(params = {}) {
    return request.get('/v1/workflows', { params })
  },

  detail(id) {
    return request.get(`/v1/workflows/${id}`)
  },

  create(data) {
    return request.post('/v1/workflows', data)
  },

  previewImportDefinition(data) {
    return request.post('/v1/workflows/import/preview', data)
  },

  listImportDolphinWorkflows(params = {}) {
    return request.get('/v1/workflows/import/dolphin', { params })
  },

  findImportDolphinWorkflow(workflowCode, params = {}) {
    return request.get(`/v1/workflows/import/dolphin/${workflowCode}`, { params })
  },

  commitImportDefinition(data) {
    return request.post('/v1/workflows/import/commit', data)
  },

  update(id, data) {
    return request.put(`/v1/workflows/${id}`, data)
  },

  switchSchedulerEngine(id, payload) {
    return request.put(`/v1/workflows/${id}/scheduler-engine`, payload)
  },

  exportJson(id) {
    return request.get(`/v1/workflows/${id}/export-json`)
  },

  publish(id, payload) {
    return request.post(`/v1/workflows/${id}/publish`, payload)
  },

  previewPublish(id) {
    return request.get(`/v1/workflows/${id}/publish/preview`)
  },

  repairPublishMetadata(id, payload = {}) {
    return request.post(`/v1/workflows/${id}/publish/repair-metadata`, payload)
  },

  approve(id, recordId, payload) {
    return request.post(`/v1/workflows/${id}/publish/${recordId}/approve`, payload)
  },

  execute(id) {
    return request.post(`/v1/workflows/${id}/execute`)
  },

  backfill(id, payload) {
    return request.post(`/v1/workflows/${id}/backfill`, payload)
  },

  updateSchedule(id, payload) {
    return request.put(`/v1/workflows/${id}/schedule`, payload)
  },

  onlineSchedule(id) {
    return request.post(`/v1/workflows/${id}/schedule/online`)
  },

  offlineSchedule(id) {
    return request.post(`/v1/workflows/${id}/schedule/offline`)
  },

  compareVersions(id, payload) {
    return request.post(`/v1/workflows/${id}/versions/compare`, payload)
  },

  rollbackVersion(id, versionId, payload) {
    return request.post(`/v1/workflows/${id}/versions/${versionId}/rollback`, payload)
  },

  deleteVersion(id, versionId) {
    return request.delete(`/v1/workflows/${id}/versions/${versionId}`)
  },

  delete(id, cascadeDeleteTasks = false) {
    return request.delete(`/v1/workflows/${id}`, {
      params: {
        cascadeDeleteTasks
      }
    })
  }
}
