import { buildPublishPreviewHtml, resolvePublishVersionId, shouldPromptOnlineAfterDeploy } from '../publishPreviewHelper'
import { buildTaskFieldDiffRows } from '../publishPreviewDiffHelper'

describe('publishPreviewHelper', () => {
  it('renders explicit before and after values for task field changes', () => {
    const html = buildPublishPreviewHtml({
      diffSummary: {
        taskModified: [
          {
            taskCode: 1001,
            taskName: 'sql_task',
            fieldChanges: [
              {
                field: 'task.sql',
                before: 'select *\nfrom ods.user_old',
                after: 'select *\nfrom ods.user_new'
              }
            ]
          }
        ]
      }
    })

    expect(html).toContain('变更前（运行态）')
    expect(html).toContain('变更后（平台）')
    expect(html).toContain('变更前为 Dolphin 运行态当前值，变更后为平台本次发布目标值。')
    expect(html).toContain('sql_task (1001)')
    expect(html).toContain('from ods.user_old')
    expect(html).toContain('from ods.user_new')
  })

  it('classifies added removed and modified task diff rows', () => {
    const rows = buildTaskFieldDiffRows(
      'select id\nfrom ods.user_old\nwhere dt = ${bizdate}',
      'select user_id\nfrom ods.user_new\nwhere dt = ${bizdate}\nlimit 10'
    )

    expect(rows.map(row => row.type)).toEqual(['modified', 'modified', 'added'])
    expect(rows[0].left.lineNumber).toBe(1)
    expect(rows[0].right.lineNumber).toBe(1)
    expect(rows.some((row) => (
      row.type === 'modified'
      && (
        row.left.segments.some(segment => segment.changed)
        || row.right.segments.some(segment => segment.changed)
      )
    ))).toBe(true)
    expect(rows[2].right.text).toBe('limit 10')
  })

  it('prefers last published version when resolving publish version id', () => {
    expect(resolvePublishVersionId({
      currentVersionId: 101,
      lastPublishedVersionId: 88
    })).toBe(88)

    expect(resolvePublishVersionId({
      currentVersionId: 101,
      lastPublishedVersionId: null
    })).toBe(101)

    expect(resolvePublishVersionId({})).toBeUndefined()
  })

  it('prompts for online after successful deploy even when stale row was already online', () => {
    expect(shouldPromptOnlineAfterDeploy({ id: 1, status: 'online' }, { status: 'success' })).toBe(true)
    expect(shouldPromptOnlineAfterDeploy({ id: 1, status: 'offline' }, { status: 'success' })).toBe(true)
    expect(shouldPromptOnlineAfterDeploy({ id: 1, status: 'offline' }, { status: 'pending_approval' })).toBe(false)
    expect(shouldPromptOnlineAfterDeploy(null, { status: 'success' })).toBe(false)
  })
})
