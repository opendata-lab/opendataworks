import request from '@/utils/request'

export const usersApi = {
  list(params = {}, config = {}) {
    const query = {}
    if (params.keyword !== undefined && params.keyword !== null && params.keyword !== '') {
      query.keyword = params.keyword
    }
    if (params.enabled !== undefined && params.enabled !== null && params.enabled !== '') {
      query.enabled = params.enabled
    }
    if (params.accountType) query.accountType = params.accountType
    return request.get('/v1/users', { ...config, params: query })
  },

  create(data, config = {}) {
    return request.post('/v1/users', data, config)
  },

  update(id, data, config = {}) {
    return request.patch(`/v1/users/${id}`, data, config)
  },

  resetPassword(id, data, config = {}) {
    const payload = typeof data === 'string' ? { password: data } : data
    return request.post(`/v1/users/${id}/reset-password`, payload, config)
  },

  datasourceAccess(id) { return request.get(`/v1/users/${id}/datasource-access`) },
  listTokens(id) { return request.get(`/v1/users/${id}/tokens`) },
  createToken(id, data) { return request.post(`/v1/users/${id}/tokens`, data) },
  revokeToken(id, tokenId) { return request.delete(`/v1/users/${id}/tokens/${tokenId}`) },

  remove(id, config = {}) {
    return request.delete(`/v1/users/${id}`, config)
  }
}

export default usersApi
