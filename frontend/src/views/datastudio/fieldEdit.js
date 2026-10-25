// Data Studio 字段编辑的纯比较工具（P2-2 F10）。
// 纯函数、无 Vue 依赖、无副作用，便于单测。从 DataStudioNew.vue 逐字抽出。
// 有状态的字段/元数据编辑流程（draft、保存、API 调用）仍留在组件。

export const buildFieldPayload = (row) => ({
  fieldName: (row.fieldName || '').trim(),
  fieldType: (row.fieldType || '').trim(),
  fieldComment: row.fieldComment || '',
  isNullable: row.isNullable ?? 1,
  isPrimary: row.isPrimary ?? 0,
  defaultValue: row.defaultValue ?? null,
  fieldOrder: row.fieldOrder || 0
})

export const isFieldChanged = (next, original) => {
  if (!original) return true
  const payload = buildFieldPayload(next)
  return (
    payload.fieldName !== (original.fieldName || '') ||
    payload.fieldType !== (original.fieldType || '') ||
    payload.fieldComment !== (original.fieldComment || '') ||
    Number(payload.isNullable ?? 1) !== Number(original.isNullable ?? 1) ||
    Number(payload.isPrimary ?? 0) !== Number(original.isPrimary ?? 0) ||
    (payload.defaultValue ?? '') !== (original.defaultValue || '') ||
    Number(payload.fieldOrder || 0) !== Number(original.fieldOrder || 0)
  )
}

export const isOnlyCommentChanged = (next, original) => {
  if (!original) return false
  const payload = buildFieldPayload(next)
  return (
    payload.fieldName === (original.fieldName || '') &&
    payload.fieldType === (original.fieldType || '') &&
    Number(payload.isNullable ?? 1) === Number(original.isNullable ?? 1) &&
    Number(payload.isPrimary ?? 0) === Number(original.isPrimary ?? 0) &&
    (payload.defaultValue ?? '') === (original.defaultValue || '') &&
    Number(payload.fieldOrder || 0) === Number(original.fieldOrder || 0) &&
    payload.fieldComment !== (original.fieldComment || '')
  )
}

// Existing physical columns require the capability even when they are currently non-key columns.
export const isMysqlPrimaryKeyLocked = (table, row) =>
  Number(table?.isSynced) === 1 && !!row?.id &&
  !table?.typeDefinition?.capabilities?.existingPrimaryKeyEdit?.supported
