import request from '@/utils/request'

export const dorisClusterApi = {
  list() {
    return request.get('/v1/doris-clusters')
  },

  getById(id) {
    return request.get(`/v1/doris-clusters/${id}`)
  },

  create(data) {
    return request.post('/v1/doris-clusters', data)
  },

  update(id, data) {
    return request.put(`/v1/doris-clusters/${id}`, data)
  },

  remove(id) {
    return request.delete(`/v1/doris-clusters/${id}`)
  },

  setDefault(id) {
    return request.post(`/v1/doris-clusters/${id}/default`)
  },

  testConnection(id) {
    return request.post(`/v1/doris-clusters/${id}/test`)
  },

  getDatabases(id) {
    return request.get(`/v1/doris-clusters/${id}/databases`)
  },

  getTables(id, dbName, params = {}) {
    return request.get(`/v1/doris-clusters/${id}/databases/${dbName}/tables`, { params })
  },

  searchSchemaObjects(id, params = {}) {
    return request.get(`/v1/doris-clusters/${id}/schema-objects`, { params })
  },

  getColumns(id, dbName, tableName) {
    return request.get(
      `/v1/doris-clusters/${id}/databases/${encodeURIComponent(dbName)}/tables/${encodeURIComponent(tableName)}/columns`
    )
  },

  getSchemaObjectCounts(id, params = {}) {
    return request.get(`/v1/doris-clusters/${id}/schema-object-counts`, { params })
  },

  getSyncHistory(id, params = {}) {
    return request.get(`/v1/doris-clusters/${id}/sync-history`, { params })
  },

  getSyncHistoryDetail(id, runId) {
    return request.get(`/v1/doris-clusters/${id}/sync-history/${runId}`)
  },

  listSchemaBackups(id) {
    return request.get(`/v1/doris-clusters/${id}/schema-backups`)
  },

  getSchemaBackup(id, schema) {
    return request.get(`/v1/doris-clusters/${id}/schema-backups/${encodeURIComponent(schema)}`)
  },

  saveSchemaBackup(id, schema, data) {
    return request.put(`/v1/doris-clusters/${id}/schema-backups/${encodeURIComponent(schema)}`, data)
  },

  triggerSchemaBackup(id, schema) {
    return request.post(`/v1/doris-clusters/${id}/schema-backups/${encodeURIComponent(schema)}/backup`)
  },

  listSchemaSnapshots(id, schema) {
    return request.get(`/v1/doris-clusters/${id}/schema-backups/${encodeURIComponent(schema)}/snapshots`)
  },

  restoreSchemaSnapshot(id, schema, data) {
    return request.post(`/v1/doris-clusters/${id}/schema-backups/${encodeURIComponent(schema)}/restore`, data)
  }
}
