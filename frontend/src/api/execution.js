import request from '../utils/request'

/**
 * 查询统一工作流实例监控数据，列表与统计来自同一响应快照。
 */
export function getWorkflowExecutionInstances(params) {
  return request({
    url: '/v1/executions/workflow-instances',
    method: 'get',
    params
  })
}

/**
 * 懒加载一次工作流运行中的 Dolphin 任务实例。
 */
export function getWorkflowExecutionTasks(workflowId, instanceId) {
  return request({
    url: `/v1/executions/workflows/${workflowId}/instances/${instanceId}/tasks`,
    method: 'get'
  })
}
