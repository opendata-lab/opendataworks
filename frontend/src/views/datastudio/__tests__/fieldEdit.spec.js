import { definition } from '@/utils/__tests__/datasourceFixtures'
import { describe, it, expect } from 'vitest'
import { buildFieldPayload, isFieldChanged, isOnlyCommentChanged, isMysqlPrimaryKeyLocked } from '../fieldEdit'

describe('buildFieldPayload', () => {
  it('trims name/type and applies defaults', () => {
    expect(buildFieldPayload({ fieldName: '  a ', fieldType: ' int ' })).toEqual({
      fieldName: 'a',
      fieldType: 'int',
      fieldComment: '',
      isNullable: 1,
      isPrimary: 0,
      defaultValue: null,
      fieldOrder: 0,
    })
  })
  it('preserves provided nullable/primary/order', () => {
    const p = buildFieldPayload({ fieldName: 'x', fieldType: 'int', isNullable: 0, isPrimary: 1, fieldOrder: 5 })
    expect(p).toMatchObject({ isNullable: 0, isPrimary: 1, fieldOrder: 5 })
  })
})

describe('isFieldChanged', () => {
  const base = { fieldName: 'a', fieldType: 'int', fieldComment: 'c', isNullable: 1, isPrimary: 0, defaultValue: '', fieldOrder: 1 }
  it('treats a missing original as changed (new row)', () => {
    expect(isFieldChanged({ fieldName: 'a', fieldType: 'int' }, null)).toBe(true)
  })
  it('returns false when nothing changed', () => {
    expect(isFieldChanged({ ...base }, base)).toBe(false)
  })
  it('detects type/name/comment/flag/order changes', () => {
    expect(isFieldChanged({ ...base, fieldType: 'bigint' }, base)).toBe(true)
    expect(isFieldChanged({ ...base, fieldComment: 'd' }, base)).toBe(true)
    expect(isFieldChanged({ ...base, isPrimary: 1 }, base)).toBe(true)
    expect(isFieldChanged({ ...base, fieldOrder: 2 }, base)).toBe(true)
  })
})

describe('isOnlyCommentChanged', () => {
  const base = { fieldName: 'a', fieldType: 'int', fieldComment: 'c', isNullable: 1, isPrimary: 0, defaultValue: '', fieldOrder: 1 }
  it('is true when only the comment differs', () => {
    expect(isOnlyCommentChanged({ ...base, fieldComment: 'new' }, base)).toBe(true)
  })
  it('is false when a non-comment attribute also differs', () => {
    expect(isOnlyCommentChanged({ ...base, fieldComment: 'new', fieldType: 'bigint' }, base)).toBe(false)
  })
  it('is false when nothing (incl. comment) differs, and false for missing original', () => {
    expect(isOnlyCommentChanged({ ...base }, base)).toBe(false)
    expect(isOnlyCommentChanged({ ...base }, null)).toBe(false)
  })
})

describe('MySQL primary key editing', () => {
  it('locks existing physical fields, but permits draft and new fields', () => {
    expect(isMysqlPrimaryKeyLocked({ sourceType: 'MYSQL', typeDefinition: definition('MYSQL'), isSynced: 1 }, { id: 1 })).toBe(true)
    expect(isMysqlPrimaryKeyLocked({ sourceType: 'MYSQL', typeDefinition: definition('MYSQL'), isSynced: 0 }, { id: 1 })).toBe(false)
    expect(isMysqlPrimaryKeyLocked({ sourceType: 'MYSQL', typeDefinition: definition('MYSQL'), isSynced: 1 }, { id: null })).toBe(false)
    expect(isMysqlPrimaryKeyLocked({ sourceType: 'DORIS', typeDefinition: definition('DORIS'), isSynced: 1 }, { id: 1 })).toBe(true)
  })
})

it('preserves absent defaults on comment saves while detecting explicit clears', () => {
  const original = { fieldName: 'a', fieldType: 'int', fieldComment: 'old', defaultValue: null }
  expect(buildFieldPayload(original).defaultValue).toBeNull()
  expect(isFieldChanged({ ...original }, original)).toBe(false)
  expect(isOnlyCommentChanged({ ...original, fieldComment: 'new' }, original)).toBe(true)
  expect(buildFieldPayload({ ...original, defaultValue: '' }).defaultValue).toBe('')
  expect(isFieldChanged({ ...original, defaultValue: '' }, { ...original, defaultValue: '42' })).toBe(true)
})
