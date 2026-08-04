import {
  buildImportPayload,
  describeRuntimeBinding,
  describeRuntimeConflict,
  formatRuntimeWorkflowLabel,
  parseDefinitionHints,
  resolveDefaultDolphinConfigId,
  selectableDolphinConfigs
} from '../importFormHelper'

describe('importFormHelper', () => {
  describe('parseDefinitionHints', () => {
    it('reads the source runtime code and name from an exported definition', () => {
      const json = JSON.stringify({
        processDefinition: { code: 1001, projectCode: 7, name: ' wf_demo ' },
        taskDefinitionList: []
      })
      expect(parseDefinitionHints(json)).toEqual({ workflowCode: 1001, workflowName: 'wf_demo' })
    })

    it('falls back to workflowCode and a top-level definition shape', () => {
      const json = JSON.stringify({ workflowCode: 2002, workflowName: 'wf_flat' })
      expect(parseDefinitionHints(json)).toEqual({ workflowCode: 2002, workflowName: 'wf_flat' })
    })

    it('returns empty hints for blank, malformed or code-less input', () => {
      const empty = { workflowCode: null, workflowName: '' }
      expect(parseDefinitionHints('')).toEqual(empty)
      expect(parseDefinitionHints('not json')).toEqual(empty)
      expect(parseDefinitionHints(JSON.stringify({ processDefinition: { code: 0 } })))
        .toEqual(empty)
    })
  })

  describe('dolphin config selection', () => {
    it('excludes disabled configs', () => {
      const configs = [{ id: 1, isActive: true }, { id: 2, isActive: false }, { id: 3 }]
      expect(selectableDolphinConfigs(configs).map((item) => item.id)).toEqual([1, 3])
    })

    it('prefers the default config, then a lone enabled config', () => {
      expect(resolveDefaultDolphinConfigId([
        { id: 1, isActive: true },
        { id: 2, isActive: true, isDefault: true }
      ])).toBe(2)
      expect(resolveDefaultDolphinConfigId([{ id: 9, isActive: true }])).toBe(9)
    })

    it('leaves the choice to the user when several enabled configs exist', () => {
      expect(resolveDefaultDolphinConfigId([
        { id: 1, isActive: true },
        { id: 2, isActive: true }
      ])).toBeNull()
      expect(resolveDefaultDolphinConfigId([])).toBeNull()
    })
  })

  describe('buildImportPayload', () => {
    it('sends the linked runtime code for json imports', () => {
      const payload = buildImportPayload({
        importMode: 'json',
        dolphinConfigId: 3,
        definitionJson: '{"a":1}',
        linkedWorkflowCode: 8888,
        workflowName: '  wf_target  '
      })
      expect(payload).toMatchObject({
        sourceType: 'json',
        dolphinConfigId: 3,
        definitionJson: '{"a":1}',
        linkedWorkflowCode: 8888,
        workflowName: 'wf_target'
      })
      expect(payload.projectCode).toBeUndefined()
    })

    it('omits the linked code when the user cleared the association', () => {
      const payload = buildImportPayload({
        importMode: 'json',
        dolphinConfigId: 3,
        definitionJson: '{"a":1}',
        linkedWorkflowCode: null,
        workflowName: 'wf_new'
      })
      expect(payload.linkedWorkflowCode).toBeUndefined()
    })

    it('sends the selected runtime workflow for dolphin imports', () => {
      const payload = buildImportPayload({
        importMode: 'dolphin',
        dolphinConfigId: 4,
        dolphinWorkflow: { projectCode: 11, workflowCode: 22 },
        workflowName: 'wf_from_dolphin'
      })
      expect(payload).toMatchObject({ sourceType: 'dolphin', projectCode: 11, workflowCode: 22 })
      expect(payload.definitionJson).toBeUndefined()
    })

    it('passes the relation decision only when one was made', () => {
      const base = { importMode: 'json', dolphinConfigId: 1, definitionJson: '{}' }
      expect(buildImportPayload(base).relationDecision).toBeUndefined()
      expect(buildImportPayload({ ...base, relationDecision: 'DECLARED' }).relationDecision)
        .toBe('DECLARED')
    })
  })

  describe('runtime binding messaging', () => {
    it('marks an adopted binding as success and a reset as info', () => {
      expect(describeRuntimeBinding({ decision: 'ADOPT', message: '将关联 X' }))
        .toEqual({ type: 'success', text: '将关联 X' })
      expect(describeRuntimeBinding({ decision: 'RESET', message: '将新建' }))
        .toEqual({ type: 'info', text: '将新建' })
      expect(describeRuntimeBinding(null)).toBeNull()
      expect(describeRuntimeBinding({})).toBeNull()
    })

    it('flags a runtime already bound to another platform workflow', () => {
      expect(describeRuntimeConflict({ localWorkflowId: 5, localWorkflowName: 'wf_owner' }))
        .toContain('wf_owner')
      expect(describeRuntimeConflict({ workflowCode: 1 })).toBeNull()
      expect(describeRuntimeConflict(null)).toBeNull()
    })

    it('shows release state and occupancy in the runtime option label', () => {
      expect(formatRuntimeWorkflowLabel({
        workflowCode: 8888,
        workflowName: 'wf_target',
        releaseState: 'ONLINE',
        localWorkflowId: 5,
        localWorkflowName: 'wf_owner'
      })).toBe('wf_target (8888) · ONLINE · 已被「wf_owner」关联')
    })
  })
})
