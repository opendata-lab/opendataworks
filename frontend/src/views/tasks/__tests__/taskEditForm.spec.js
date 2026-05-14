import { describe, expect, it } from 'vitest'
import {
  buildTaskPayload,
  buildTaskSaveSuccessMessage,
  createDefaultTaskModel,
  resolveTaskWorkflowId,
  shouldPromptUnboundWorkflowGuidance,
  syncTaskDatasourceType
} from '../taskEditForm'

describe('taskEditForm', () => {
  it('defaults dolphinFlag to YES and datasourceType to null for new tasks', () => {
    expect(createDefaultTaskModel().dolphinFlag).toBe('YES')
    expect(createDefaultTaskModel().datasourceType).toBeNull()
  })

  it('normalizes dolphinFlag and preserves it in save payload', () => {
    const payload = buildTaskPayload({
      ...createDefaultTaskModel(),
      taskName: 'task_demo',
      dolphinFlag: 'no',
      datasourceName: ' ',
      datasourceType: 'DORIS'
    })

    expect(payload.dolphinFlag).toBe('NO')
    expect(payload.datasourceName).toBeNull()
    expect(payload.datasourceType).toBeNull()
  })

  it('syncs datasourceType from matched dolphin datasource option', () => {
    const task = {
      ...createDefaultTaskModel(),
      datasourceName: 'oceanbase_prod',
      datasourceType: 'DORIS'
    }

    syncTaskDatasourceType(task, [
      { name: 'mysql_ds', type: 'MYSQL' },
      { name: 'oceanbase_prod', type: 'OCEANBASE' }
    ])

    expect(task.datasourceType).toBe('OCEANBASE')
    expect(buildTaskPayload(task).datasourceType).toBe('OCEANBASE')
  })

  it('clears datasourceType when datasourceName is empty', () => {
    const task = {
      ...createDefaultTaskModel(),
      datasourceName: '   ',
      datasourceType: 'OCEANBASE'
    }

    syncTaskDatasourceType(task, [{ name: 'oceanbase_prod', type: 'OCEANBASE' }])

    expect(task.datasourceName).toBe('')
    expect(task.datasourceType).toBeNull()
    expect(buildTaskPayload(task).datasourceType).toBeNull()
  })

  it('resolves workflowId from saved response, payload, or draft task', () => {
    expect(resolveTaskWorkflowId({ workflowId: 12 }, {}, {})).toBe(12)
    expect(resolveTaskWorkflowId({ task: { workflowId: 13 } }, {}, {})).toBe(13)
    expect(resolveTaskWorkflowId({}, { task: { workflowId: 14 } }, {})).toBe(14)
    expect(resolveTaskWorkflowId({}, {}, { workflowId: 15 })).toBe(15)
    expect(resolveTaskWorkflowId({}, { task: { workflowId: '' } }, { workflowId: null })).toBeNull()
  })

  it('marks related task saves without workflow as needing workflow guidance', () => {
    expect(shouldPromptUnboundWorkflowGuidance(null, { fromRelatedTask: true })).toBe(true)
    expect(shouldPromptUnboundWorkflowGuidance(12, { fromRelatedTask: true })).toBe(false)
    expect(shouldPromptUnboundWorkflowGuidance(null, { fromRelatedTask: false })).toBe(false)
  })

  it('uses workflow-aware success messages after related task saves', () => {
    expect(buildTaskSaveSuccessMessage(false, 12, { fromRelatedTask: true })).toBe('创建成功，工作流有变化')
    expect(buildTaskSaveSuccessMessage(false, null, { fromRelatedTask: true })).toBe('创建成功，请绑定工作流后发布上线')
    expect(buildTaskSaveSuccessMessage(true, null, { fromRelatedTask: false })).toBe('更新成功')
  })
})
