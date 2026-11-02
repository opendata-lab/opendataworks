import request from '@/utils/request'

const basePath = (sourceId) => `/v1/doris-clusters/${sourceId}/permissions`
const memberPath = (sourceId, subjectType, subjectId) =>
  `${basePath(sourceId)}/members/${encodeURIComponent(subjectType)}/${encodeURIComponent(subjectId)}`

export const permissionsApi = {
  getOverview(sourceId, config = {}) {
    return request.get(basePath(sourceId), config)
  },

  createRole(sourceId, data, config = {}) {
    return request.post(`${basePath(sourceId)}/roles`, data, config)
  },

  updateRole(sourceId, roleId, data, config = {}) {
    return request.patch(`${basePath(sourceId)}/roles/${encodeURIComponent(roleId)}`, data, config)
  },

  deleteRole(sourceId, roleId, config = {}) {
    return request.delete(`${basePath(sourceId)}/roles/${encodeURIComponent(roleId)}`, config)
  },

  previewRoleGrants(sourceId, roleId, data, config = {}) {
    return request.post(`${basePath(sourceId)}/roles/${encodeURIComponent(roleId)}/preview`, data, config)
  },

  applyRoleGrants(sourceId, roleId, data, config = {}) {
    const payload = typeof data === 'string' ? { previewId: data } : data
    return request.post(`${basePath(sourceId)}/roles/${encodeURIComponent(roleId)}/apply`, payload, config)
  },

  assignMemberRoles(sourceId, subjectType, subjectId, data, config = {}) {
    return request.put(memberPath(sourceId, subjectType, subjectId), data, config)
  },

  bindMemberAccount(sourceId, subjectType, subjectId, data, config = {}) {
    return request.post(`${memberPath(sourceId, subjectType, subjectId)}/account`, data, config)
  },

  removeMember(sourceId, subjectType, subjectId, config = {}) {
    return request.delete(memberPath(sourceId, subjectType, subjectId), config)
  },

  inspectMember(sourceId, subjectType, subjectId, config = {}) {
    return request.get(`${memberPath(sourceId, subjectType, subjectId)}/inspect`, config)
  },

  saveManagementAccount(sourceId, data, config = {}) {
    return request.put(`${basePath(sourceId)}/management`, data, config)
  },

  listResources(sourceId, params = {}, config = {}) {
    const database = typeof params === 'string' ? params : params?.database
    const query = database ? { database } : {}
    return request.get(`${basePath(sourceId)}/resources`, { ...config, params: query })
  },

  listOperations(sourceId, config = {}) {
    return request.get(`${basePath(sourceId)}/operations`, config)
  }
}

export default permissionsApi
