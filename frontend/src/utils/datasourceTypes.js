// Descriptors come from the backend; unknown/missing types never imply a database engine.
export const capabilityReason = (definition, capability) => {
  if (!definition) return '数据源类型描述未加载，请刷新数据源'
  const value = definition.capabilities?.[capability]
  return value?.supported ? '' : (value?.reason || '当前数据源暂不支持此操作')
}
export const connectionRules = definition => Object.fromEntries(
  (definition?.connectionFields || []).map(field => [field.key, field.required
    ? [{ required: true, message: `请输入${field.label}`, trigger: field.control === 'number' ? 'change' : 'blur' }]
    : []])
)
export const connectionDefaults = definition => Object.fromEntries(
  (definition?.connectionFields || []).map(field => [field.key, field.defaultValue])
)
export const validateDefinitions = definitions => {
  if (!Array.isArray(definitions) || !definitions.length || definitions.some(d =>
    d.schemaVersion !== 1 || !d.type || !d.label || !d.defaultPort || !Array.isArray(d.connectionFields) || !d.capabilities || !d.driverStatus)) {
    throw new Error('数据源类型描述加载失败或版本不兼容')
  }
  return definitions
}
