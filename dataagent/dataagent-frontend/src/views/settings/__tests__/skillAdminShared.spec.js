import { describe, expect, it } from 'vitest'

import { buildSkillItems } from '../skillAdminShared'

const doc = (overrides = {}) => ({
  id: 1,
  folder: 'chart-visualization',
  relative_path: 'SKILL.md',
  file_name: 'SKILL.md',
  source: 'bundled',
  enabled: true,
  description: '',
  version_count: 1,
  updated_at: '2026-09-15T00:00:00',
  ...overrides
})

describe('buildSkillItems', () => {
  it('carries the skill description through to the list card', () => {
    // 后端每条文档都带 description（SkillDocumentSummary.description），
    // 但只有 SKILL.md 那条非空。之前这里没有取出来，列表页 skill.description
    // 永远是 undefined，于是每个 Skill 都显示兜底的「未填写描述」。
    const items = buildSkillItems([
      doc({ id: 1, description: '把一组行数据转成图表契约' }),
      doc({ id: 2, relative_path: 'reference/10-chart-spec-contract.md', file_name: '10-chart-spec-contract.md' })
    ])

    expect(items).toHaveLength(1)
    expect(items[0].description).toBe('把一组行数据转成图表契约')
  })

  it('does not let a later empty document blank out the description', () => {
    const items = buildSkillItems([
      doc({ id: 1, relative_path: 'reference/a.md', file_name: 'a.md', description: '' }),
      doc({ id: 2, description: '真正的描述' })
    ])

    expect(items[0].description).toBe('真正的描述')
  })

  it('leaves the description empty when no document declares one', () => {
    const items = buildSkillItems([doc({ description: '' })])

    // 兜底文案由页面决定，这里只保证不伪造内容
    expect(items[0].description).toBe('')
  })
})
