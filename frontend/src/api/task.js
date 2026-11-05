import request from '@/utils/request'

export const taskApi = {
  // 获取任务列表
  list(params) {
    return request.get('/v1/tasks', { params })
  },

  // 获取任务详情
  getById(id) {
    return request.get(`/v1/tasks/${id}`)
  },

  // 创建任务
  create(data) {
    return request.post('/v1/tasks', data)
  },

  // 更新任务
  update(id, data) {
    return request.put(`/v1/tasks/${id}`, data)
  },

  // 执行任务（单任务测试执行）


  // 执行工作流（完整工作流执行）


  // 删除任务
  delete(id) {
    return request.delete(`/v1/tasks/${id}`)
  },

  // 获取任务执行状态


  // 获取 DolphinScheduler WebUI 配置
  getDolphinWebuiConfig(params = {}) {
    return request.get('/v1/tasks/config/dolphin-webui', { params })
  },

  // 获取任务血缘关系
  getTaskLineage(id) {
    return request.get(`/v1/tasks/${id}/lineage`)
  },

  // 检查任务名称是否存在
  checkTaskName(taskName, excludeId) {
    const params = { taskName }
    if (excludeId) params.excludeId = excludeId
    return request.get('/v1/tasks/check-task-name', { params })
  },

  // 获取 Dolphin 数据源列表
  fetchDatasources(params = {}) {
    return request.get('/v1/dolphin/datasources', { params })
  },

  // 获取 Dolphin 任务组列表
  fetchTaskGroups(params = {}) {
    return request.get('/v1/dolphin/task-groups', { params })
  },

  // SQL 表依赖解析（增强版）
  analyzeSqlTables(payload) {
    return request.post('/v1/sql-table-matcher/analyze', payload, { skipErrorMessage: true })
  },

  // 获取 Dolphin worker group 列表（项目级）
  fetchWorkerGroups(params = {}) {
    return request.get('/v1/dolphin/worker-groups', { params })
  },

  // 获取 Dolphin tenant 列表
  fetchTenants(params = {}) {
    return request.get('/v1/dolphin/tenants', { params })
  },

  // 获取 Dolphin 告警组列表
  fetchAlertGroups(params = {}) {
    return request.get('/v1/dolphin/alert-groups', { params })
  },

  // 获取 Dolphin 环境列表
  fetchEnvironments(params = {}) {
    return request.get('/v1/dolphin/environments', { params })
  },

  // 预览调度未来触发时间
  previewSchedule(payload, params = {}) {
    return request.post('/v1/dolphin/schedules/preview', payload, { params })
  },

}
