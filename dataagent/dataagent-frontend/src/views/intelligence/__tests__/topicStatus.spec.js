import { describe, expect, it } from 'vitest'
import { topicStatusKind } from '../topicStatus'

describe('topicStatusKind', () => {
  it('maps in-progress statuses to running', () => {
    expect(topicStatusKind('waiting')).toBe('running')
    expect(topicStatusKind('running')).toBe('running')
  })

  it('maps terminal failure/cancel to their own kinds', () => {
    expect(topicStatusKind('error')).toBe('error')
    expect(topicStatusKind('suspended')).toBe('suspended')
  })

  it('returns empty string for finished, unknown, or missing status', () => {
    expect(topicStatusKind('finished')).toBe('')
    expect(topicStatusKind('')).toBe('')
    expect(topicStatusKind(null)).toBe('')
    expect(topicStatusKind(undefined)).toBe('')
    expect(topicStatusKind('something-else')).toBe('')
  })
})
