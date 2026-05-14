export const DEFAULT_DOLPHIN_FLAG = 'YES'

export const normalizeDolphinFlag = (value) => {
  const normalized = typeof value === 'string' ? value.trim().toUpperCase() : ''
  return normalized === 'NO' ? 'NO' : 'YES'
}

const normalizeOptionalText = (value) => {
  if (typeof value !== 'string') return value ?? null
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

export const createDefaultTaskModel = () => ({
  id: null,
  taskName: '',
  taskDesc: '',
  taskCode: '',
  taskType: 'batch',
  engine: 'dolphin',
  dolphinNodeType: 'SQL',
  dolphinFlag: DEFAULT_DOLPHIN_FLAG,
  datasourceName: '',
  datasourceType: null,
  taskSql: '',
  scheduleCron: '',
  priority: 5,
  owner: '',
  clusterId: null,
  database: '',
  workflowId: null,
  targetDatasourceName: '',
  sourceTable: '',
  targetTable: '',
  columnMapping: ''
})

export const findDatasourceOption = (options, datasourceName) => {
  const normalizedName = normalizeOptionalText(datasourceName)
  if (!normalizedName || !Array.isArray(options)) return null

  return options.find((item) => normalizeOptionalText(item?.name) === normalizedName) || null
}

export const syncTaskDatasourceType = (task, options = []) => {
  if (!task || typeof task !== 'object') return null

  const normalizedName = normalizeOptionalText(task.datasourceName)
  task.datasourceName = normalizedName || ''
  if (!normalizedName) {
    task.datasourceType = null
    return null
  }

  const option = findDatasourceOption(options, normalizedName)
  if (option?.type != null) {
    task.datasourceType = normalizeOptionalText(option.type)
  } else {
    task.datasourceType = normalizeOptionalText(task.datasourceType)
  }
  return option
}

export const buildTaskPayload = (task) => {
  const { taskGroupName, ...rawTaskPayload } = task || {}
  const payload = {
    ...rawTaskPayload,
    dolphinFlag: normalizeDolphinFlag(rawTaskPayload.dolphinFlag),
    datasourceName: normalizeOptionalText(rawTaskPayload.datasourceName),
    datasourceType: normalizeOptionalText(rawTaskPayload.datasourceType),
    targetDatasourceName: normalizeOptionalText(rawTaskPayload.targetDatasourceName),
    sourceTable: normalizeOptionalText(rawTaskPayload.sourceTable),
    targetTable: normalizeOptionalText(rawTaskPayload.targetTable),
    columnMapping: normalizeOptionalText(rawTaskPayload.columnMapping)
  }
  if (!payload.datasourceName) {
    payload.datasourceType = null
  }
  return payload
}

export const resolveTaskWorkflowId = (savedTask, payload, draftTask) => {
  const candidates = [
    savedTask?.workflowId,
    savedTask?.task?.workflowId,
    payload?.task?.workflowId,
    draftTask?.workflowId
  ]
  for (const value of candidates) {
    const id = Number(value)
    if (Number.isFinite(id) && id > 0) return id
  }
  return null
}

export const shouldPromptUnboundWorkflowGuidance = (workflowId, options = {}) => {
  return !workflowId && options.fromRelatedTask === true
}

export const buildTaskSaveSuccessMessage = (isEdit, workflowId, options = {}) => {
  const action = isEdit ? '更新' : '创建'
  if (workflowId) {
    return `${action}成功，工作流有变化`
  }
  if (shouldPromptUnboundWorkflowGuidance(workflowId, options)) {
    return `${action}成功，请绑定工作流后发布上线`
  }
  return `${action}成功`
}
