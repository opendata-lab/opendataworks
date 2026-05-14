export const MAX_RENDER_COUNT = 20

const escapeHtml = (text) => {
  const raw = String(text ?? '')
  return raw
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

export const formatFieldValue = (value, options = {}) => {
  const preserveEmpty = options?.preserveEmpty === true
  const prettyObject = options?.prettyObject !== false

  if (value === null || value === undefined || value === '') {
    return preserveEmpty ? '' : '-'
  }
  if (typeof value === 'object') {
    return prettyObject ? JSON.stringify(value, null, 2) : JSON.stringify(value)
  }
  return String(value)
}

const renderValueCell = (value) => {
  return `
    <div style="white-space: pre-wrap; word-break: break-word; font-family: Menlo, Monaco, Consolas, monospace; line-height: 1.5; max-height: 180px; overflow: auto;">
      ${escapeHtml(formatFieldValue(value))}
    </div>
  `
}

export const formatTask = (task) => {
  if (!task) return '-'
  const code = task.taskCode ?? '-'
  const name = task.taskName || '-'
  return `${name} (${code})`
}

export const formatRelation = (relation) => {
  if (!relation) return '-'
  const pre = relation.entryEdge || relation.preTaskCode === 0
    ? '入口'
    : (relation.preTaskName || relation.preTaskCode || '-')
  const post = relation.postTaskName || relation.postTaskCode || '-'
  return `${pre} -> ${post}`
}

const renderIssue = (issue) => {
  if (!issue) return ''
  const parts = []
  if (issue.code) parts.push(`<strong>${escapeHtml(issue.code)}</strong>`)
  if (issue.taskName) parts.push(`任务: ${escapeHtml(issue.taskName)}`)
  if (issue.message) parts.push(escapeHtml(issue.message))
  return `<li>${parts.join(' | ')}</li>`
}

const renderFieldChanges = (title, changes = []) => {
  if (!Array.isArray(changes) || !changes.length) {
    return ''
  }
  const rows = changes.slice(0, MAX_RENDER_COUNT)
    .map((item) => `
      <tr>
        <td style="padding: 6px 8px; border: 1px solid #ebeef5; vertical-align: top;">${escapeHtml(item?.field || '-')}</td>
        <td style="padding: 6px 8px; border: 1px solid #ebeef5; vertical-align: top;">${renderValueCell(item?.before)}</td>
        <td style="padding: 6px 8px; border: 1px solid #ebeef5; vertical-align: top;">${renderValueCell(item?.after)}</td>
      </tr>
    `)
    .join('')
  const remain = changes.length - MAX_RENDER_COUNT
  const more = remain > 0
    ? `<div style="margin-top: 4px; color: #909399;">... 另有 ${remain} 项</div>`
    : ''
  return `
    <div style="margin-top: 10px;">
      <div style="font-weight: 600; margin-bottom: 4px;">${escapeHtml(title)}（${changes.length}）</div>
      <div style="overflow:auto; max-height: 180px;">
        <table style="border-collapse: collapse; width: 100%; font-size: 12px; color: #606266;">
          <thead>
            <tr>
              <th style="padding: 6px 8px; border: 1px solid #ebeef5; text-align: left;">字段</th>
              <th style="padding: 6px 8px; border: 1px solid #ebeef5; text-align: left;">变更前（运行态）</th>
              <th style="padding: 6px 8px; border: 1px solid #ebeef5; text-align: left;">变更后（平台）</th>
            </tr>
          </thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
      ${more}
    </div>
  `
}

const renderTaskChanges = (title, tasks = [], tone = 'normal') => {
  if (!Array.isArray(tasks) || !tasks.length) {
    return ''
  }
  const color = tone === 'add' ? '#67c23a' : (tone === 'remove' ? '#f56c6c' : '#606266')
  const rendered = tasks.slice(0, MAX_RENDER_COUNT)
    .map((task) => `<li style="color: ${color};">${escapeHtml(formatTask(task))}</li>`)
    .join('')
  const remain = tasks.length - MAX_RENDER_COUNT
  const more = remain > 0 ? `<li>... 另有 ${remain} 项</li>` : ''
  return `
    <div style="margin-top: 10px;">
      <div style="font-weight: 600; margin-bottom: 4px;">${escapeHtml(title)}（${tasks.length}）</div>
      <ul style="margin: 0 0 0 16px; max-height: 140px; overflow: auto; line-height: 1.6;">${rendered}${more}</ul>
    </div>
  `
}

const renderTaskModified = (changes = []) => {
  if (!Array.isArray(changes) || !changes.length) {
    return ''
  }
  const rendered = changes.slice(0, MAX_RENDER_COUNT)
    .map((item) => {
      const rows = Array.isArray(item?.fieldChanges)
        ? item.fieldChanges.slice(0, 10)
          .map((change) => `
            <tr>
              <td style="padding: 6px 8px; border: 1px solid #ebeef5; vertical-align: top;">${escapeHtml(change?.field || '-')}</td>
              <td style="padding: 6px 8px; border: 1px solid #ebeef5; vertical-align: top;">${renderValueCell(change?.before)}</td>
              <td style="padding: 6px 8px; border: 1px solid #ebeef5; vertical-align: top;">${renderValueCell(change?.after)}</td>
            </tr>
          `)
          .join('')
        : ''
      return `
        <div style="border: 1px solid #ebeef5; border-radius: 4px; padding: 8px; margin-bottom: 6px;">
          <div style="font-weight: 600; margin-bottom: 4px;">${escapeHtml(formatTask(item))}</div>
          <div style="overflow:auto; max-height: 260px;">
            <table style="border-collapse: collapse; width: 100%; font-size: 12px; color: #606266;">
              <thead>
                <tr>
                  <th style="padding: 6px 8px; border: 1px solid #ebeef5; text-align: left;">字段</th>
                  <th style="padding: 6px 8px; border: 1px solid #ebeef5; text-align: left;">变更前（运行态）</th>
                  <th style="padding: 6px 8px; border: 1px solid #ebeef5; text-align: left;">变更后（平台）</th>
                </tr>
              </thead>
              <tbody>${rows || `
                <tr>
                  <td colspan="3" style="padding: 6px 8px; border: 1px solid #ebeef5; color: #909399;">-</td>
                </tr>
              `}</tbody>
            </table>
          </div>
        </div>
      `
    })
    .join('')
  const remain = changes.length - MAX_RENDER_COUNT
  const more = remain > 0
    ? `<div style="margin-top: 4px; color: #909399;">... 另有 ${remain} 个任务修改</div>`
    : ''
  return `
    <div style="margin-top: 10px;">
      <div style="font-weight: 600; margin-bottom: 4px;">任务修改（${changes.length}）</div>
      <div style="max-height: 220px; overflow: auto;">${rendered}</div>
      ${more}
    </div>
  `
}

const renderEdgeChanges = (added = [], removed = []) => {
  if ((!Array.isArray(added) || !added.length) && (!Array.isArray(removed) || !removed.length)) {
    return ''
  }
  const renderList = (items, prefix, color) => items.slice(0, MAX_RENDER_COUNT)
    .map((item) => `<li style="color:${color};">${prefix} ${escapeHtml(formatRelation(item))}</li>`)
    .join('')

  const addedHtml = Array.isArray(added) && added.length
    ? `
      <div style="margin-bottom: 6px;">
        <div style="font-weight: 600; color: #67c23a;">边新增（${added.length}）</div>
        <ul style="margin: 2px 0 0 16px; line-height: 1.6;">${renderList(added, '+', '#67c23a')}</ul>
      </div>
    `
    : ''

  const removedHtml = Array.isArray(removed) && removed.length
    ? `
      <div>
        <div style="font-weight: 600; color: #f56c6c;">边删除（${removed.length}）</div>
        <ul style="margin: 2px 0 0 16px; line-height: 1.6;">${renderList(removed, '-', '#f56c6c')}</ul>
      </div>
    `
    : ''

  return `
    <div style="margin-top: 10px;">
      <div style="font-weight: 600; margin-bottom: 4px;">边变更</div>
      <div style="max-height: 180px; overflow: auto;">${addedHtml}${removedHtml}</div>
    </div>
  `
}

export const buildPublishPreviewHtml = (preview) => {
  const summary = preview?.diffSummary || {}

  const warnings = Array.isArray(preview?.warnings) && preview.warnings.length
    ? `
      <div style="margin-top: 8px; color: #e6a23c;">
        <div style="font-weight: 600; margin-bottom: 4px;">预检告警</div>
        <ul style="margin: 0 0 0 16px; line-height: 1.5;">${preview.warnings.map(renderIssue).join('')}</ul>
      </div>
    `
    : ''

  const sections = [
    renderFieldChanges('Workflow 字段变更', summary.workflowFieldChanges || []),
    renderTaskChanges('任务新增', summary.taskAdded || [], 'add'),
    renderTaskChanges('任务删除', summary.taskRemoved || [], 'remove'),
    renderTaskModified(summary.taskModified || []),
    renderEdgeChanges(summary.edgeAdded || [], summary.edgeRemoved || []),
    renderFieldChanges('调度变更', summary.scheduleChanges || [])
  ].join('')

  return `
    <div style="max-height: 65vh; overflow: auto; padding-right: 8px;">
      <div>检测到平台定义与 Dolphin 运行态存在差异，确认后将按平台定义发布。</div>
      <div style="margin-top: 4px; color: #909399;">变更前为 Dolphin 运行态当前值，变更后为平台本次发布目标值。</div>
      ${warnings}
      ${sections}
    </div>
  `
}

export const resolvePublishVersionId = (workflow) => {
  const lastPublishedVersionId = Number(workflow?.lastPublishedVersionId)
  if (Number.isFinite(lastPublishedVersionId) && lastPublishedVersionId > 0) {
    return lastPublishedVersionId
  }
  const currentVersionId = Number(workflow?.currentVersionId)
  if (Number.isFinite(currentVersionId) && currentVersionId > 0) {
    return currentVersionId
  }
  return undefined
}

export const shouldPromptOnlineAfterDeploy = (row, record) => {
  if (!row?.id) return false
  return record?.status !== 'pending_approval'
}

export const buildPublishRepairHtml = (preview) => {
  const repairIssues = Array.isArray(preview?.repairIssues) ? preview.repairIssues : []
  const warnings = Array.isArray(preview?.warnings) ? preview.warnings : []
  const rows = repairIssues.slice(0, MAX_RENDER_COUNT)
    .map((issue) => {
      const task = issue?.taskName || issue?.taskCode
        ? `${issue?.taskName || '-'} (${issue?.taskCode ?? '-'})`
        : '-'
      return `
        <tr>
          <td style="padding: 6px 8px; border: 1px solid #ebeef5;">${escapeHtml(issue?.field || '-')}</td>
          <td style="padding: 6px 8px; border: 1px solid #ebeef5;">${escapeHtml(task)}</td>
          <td style="padding: 6px 8px; border: 1px solid #ebeef5;">${escapeHtml(issue?.message || '-')}</td>
        </tr>
      `
    })
    .join('')
  const remain = repairIssues.length - MAX_RENDER_COUNT
  const more = remain > 0
    ? `<div style="margin-top: 4px; color: #909399;">... 另有 ${remain} 项</div>`
    : ''
  const warn = warnings.length
    ? `
      <div style="margin-top: 8px; color: #e6a23c;">
        <div style="font-weight: 600; margin-bottom: 4px;">预检告警</div>
        <ul style="margin: 0 0 0 16px; line-height: 1.5;">${warnings.map(renderIssue).join('')}</ul>
      </div>
    `
    : ''
  return `
    <div style="max-height: 60vh; overflow: auto; padding-right: 8px;">
      <div>检测到可修复的元数据漂移。建议先修复元数据，再重新发布。</div>
      ${warn}
      <div style="margin-top: 10px;">
        <table style="border-collapse: collapse; width: 100%; font-size: 12px; color: #606266;">
          <thead>
            <tr>
              <th style="padding: 6px 8px; border: 1px solid #ebeef5; text-align: left;">字段</th>
              <th style="padding: 6px 8px; border: 1px solid #ebeef5; text-align: left;">任务</th>
              <th style="padding: 6px 8px; border: 1px solid #ebeef5; text-align: left;">问题</th>
            </tr>
          </thead>
          <tbody>${rows}</tbody>
        </table>
        ${more}
      </div>
    </div>
  `
}

export const firstPreviewErrorMessage = (preview) => {
  const first = Array.isArray(preview?.errors) ? preview.errors[0] : null
  return first?.message || '发布预检未通过'
}

export const isDialogCancel = (error) => {
  return error === 'cancel' || error === 'close'
}
