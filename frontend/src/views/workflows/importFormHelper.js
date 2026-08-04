/**
 * 导入表单的纯逻辑：从待导入 JSON 里读出运行态线索、构建请求体、
 * 把后端的运行态归属结论翻译成界面文案。抽出来是为了让弹窗组件之外也能单测。
 */

const readNumber = (value) => {
  if (value === null || value === undefined || value === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
}

const firstPresent = (node, keys) => {
  if (!node || typeof node !== 'object') return undefined
  for (const key of keys) {
    if (node[key] !== undefined && node[key] !== null) return node[key]
  }
  return undefined
}

/**
 * 解析待导入 JSON 中携带的来源运行态信息。
 * 这些值只用于「猜一个默认关联项」，最终归属由用户在表单上确认、后端复核。
 */
export const parseDefinitionHints = (definitionJson) => {
  const empty = { workflowCode: null, workflowName: '' }
  if (!definitionJson || !String(definitionJson).trim()) return empty

  let root
  try {
    root = JSON.parse(definitionJson)
  } catch (error) {
    return empty
  }
  if (!root || typeof root !== 'object') return empty

  const definition = firstPresent(root, ['processDefinition', 'workflowDefinition', 'workflow']) || root
  const rawName = firstPresent(definition, ['name', 'workflowName'])
  return {
    workflowCode: readNumber(firstPresent(definition, ['code', 'workflowCode', 'processDefinitionCode'])),
    workflowName: typeof rawName === 'string' ? rawName.trim() : ''
  }
}

/**
 * 只有启用中的 Dolphin 配置可以作为导入目标。
 */
export const selectableDolphinConfigs = (configs) =>
  (Array.isArray(configs) ? configs : []).filter((item) => item && item.isActive !== false)

/**
 * 默认目标环境：优先默认配置，其次唯一的启用配置；否则让用户自己选。
 */
export const resolveDefaultDolphinConfigId = (configs) => {
  const selectable = selectableDolphinConfigs(configs)
  if (!selectable.length) return null
  const preferred = selectable.find((item) => item.isDefault)
  if (preferred) return preferred.id
  return selectable.length === 1 ? selectable[0].id : null
}

export const formatDolphinConfigLabel = (config) => {
  if (!config) return ''
  const name = config.configName || config.url || `配置 ${config.id}`
  const suffix = config.projectName ? `（${config.projectName}）` : ''
  return `${name}${suffix}${config.isActive === false ? ' [已停用]' : ''}`
}

export const formatRuntimeWorkflowLabel = (workflow) => {
  if (!workflow) return ''
  const name = workflow.workflowName || `workflow_${workflow.workflowCode}`
  const state = workflow.releaseState ? ` · ${workflow.releaseState}` : ''
  const occupied = workflow.localWorkflowId ? ` · 已被「${workflow.localWorkflowName}」关联` : ''
  return `${name} (${workflow.workflowCode})${state}${occupied}`
}

export const buildImportPayload = (form) => {
  const payload = {
    sourceType: form.importMode,
    dolphinConfigId: form.dolphinConfigId,
    workflowName: form.workflowName ? form.workflowName.trim() : undefined,
    operator: 'portal-ui'
  }
  if (form.importMode === 'dolphin') {
    payload.projectCode = form.dolphinWorkflow?.projectCode
    payload.workflowCode = form.dolphinWorkflow?.workflowCode
  } else {
    payload.definitionJson = form.definitionJson
    payload.linkedWorkflowCode = form.linkedWorkflowCode || undefined
  }
  if (form.relationDecision) {
    payload.relationDecision = form.relationDecision
  }
  return payload
}

/**
 * 运行态归属结论 → 提示条。返回 null 表示无需展示。
 */
export const describeRuntimeBinding = (binding) => {
  if (!binding || !binding.decision) return null
  if (binding.decision === 'ADOPT') {
    return { type: 'success', text: binding.message || '将关联目标 Dolphin 中已有的工作流' }
  }
  return { type: 'info', text: binding.message || '将作为全新工作流导入' }
}

/**
 * 用户选中的运行态是否已被别的平台工作流占用 —— 用于表单内联报错。
 */
export const describeRuntimeConflict = (workflow) => {
  if (!workflow || !workflow.localWorkflowId) return null
  return `该运行态已被平台工作流「${workflow.localWorkflowName}」关联，请改选其他工作流或清空关联`
}
