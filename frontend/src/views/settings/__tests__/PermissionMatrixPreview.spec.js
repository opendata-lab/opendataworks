import { describe, expect, it, vi } from 'vitest'
import { reactive, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { usePermissionMatrix } from '../composables/permissions/usePermissionMatrix'
import { usePermissionRoleActions } from '../composables/permissions/usePermissionRoleActions'
import RolePreviewDialog from '../components/datasource-permissions/RolePreviewDialog.vue'
import RoleMatrixPanel from '../components/datasource-permissions/RoleMatrixPanel.vue'
import { permissionsApi } from '@/api/permissions'

vi.mock('@/api/permissions', () => ({ permissionsApi: { updateRole: vi.fn() } }))

function matrix(template = 'READONLY') {
  const overview = reactive({ roles: [{ id: 1, template, status: 'ACTIVE', grants: [
    { resourceType: 'TABLE', database: 'sales', tableName: 'orders', actions: ['QUERY'] },
    { resourceType: 'TABLE', database: 'archive', tableName: 'old', actions: ['QUERY'] },
  ] }] })
  const state = usePermissionMatrix({ overview, captureRequest: () => ({ sourceId: 1, isCurrent: () => true }), isPostgresEngine: ref(true), activeSourceId: ref(1) })
  state.selectedRoleId.value = 1
  state.syncDraftFromSelectedRole()
  state.currentResourceType.value = 'TABLE'
  state.selectedDatabase.value = 'sales'
  state.resourcesState.databases = ['sales']
  state.resourcesState.objects = [{ resourceType: 'TABLE', database: 'sales', tableName: 'orders' }]
  return { overview, state }
}

describe('PR 3 role matrix and preview', () => {
  it('counts saved and changed resources and edits unloaded databases without loading catalogs', () => {
    const { state } = matrix()
    state.roleDraftMap.value['TABLE|archive|old'] = []
    state.roleDraftMap.value['TABLE|analytics|new'] = ['QUERY']
    expect(state.grantCounts('TABLE')).toEqual({ granted: 2, changed: 2 })
    expect(state.grantCounts('TABLE', 'archive')).toEqual({ granted: 1, changed: 1 })
    expect(state.databaseOptions.value).toEqual(['analytics', 'archive', 'sales'])
    expect(state.currentResourceRows.value).toHaveLength(1)
    state.onlyGranted.value = true
    expect(state.currentResourceRows.value).toHaveLength(3)
    expect(state.draftChangeCounts.value).toEqual({ added: 1, removed: 1 })
    const old = state.currentResourceRows.value.find((r) => r.database === 'archive')
    state.toggleMatrixAction(old, { key: 'QUERY' }, true)
    expect(state.grantCounts('TABLE').changed).toBe(1)
    state.resourceSearch.value = 'analytics'
    expect(state.currentResourceRows.value.map((r) => r.database)).toEqual(['analytics'])
    state.reset()
    expect(state.onlyGranted.value).toBe(false)
  })

  it.each(['READONLY', 'CUSTOM', 'READWRITE', 'ADMIN'])('selects supported template actions for %s', (template) => {
    const { state } = matrix(template)
    state.batchSelectTemplate()
    const actions = state.roleDraftMap.value['TABLE|sales|orders']
    expect(actions).toContain('QUERY')
    expect(actions.includes('WRITE')).toBe(['READWRITE', 'ADMIN'].includes(template))
    expect(actions.includes('ALTER')).toBe(template === 'ADMIN')
    expect(actions).not.toContain('CREATE')
    expect(state.roleDraftMap.value['TABLE|archive|old']).toEqual(['QUERY'])
  })

  it('renders matrix counters, cross-database toggle and action counts', () => {
    const { state, overview } = matrix()
    state.roleDraftMap.value['TABLE|archive|old'] = []
    const wrapper = mount(RoleMatrixPanel, { props: { model: reactive({ ...state, overview, countRoleMembers: () => 0, getTemplateLabel: (v) => v, getResourceTypeLabel: (v) => v }) }, global: { plugins: [ElementPlus] } })
    expect(wrapper.get('[data-testid="resource-type-TABLE"]').text()).toContain('2 已授权 / 1 待变更')
    expect(wrapper.text()).toContain('仅看已授权/变更')
    expect(wrapper.get('[data-testid="role-savebar"]').text()).toContain('撤销 1 项动作')
    wrapper.unmount()
  })

  it('sends the selected template together with the edited name', async () => {
    const { state, overview } = matrix()
    const loadAllForSource = vi.fn()
    const roles = usePermissionRoleActions({ ...state, overview, captureRequest: () => ({ sourceId: 1, isCurrent: () => true }), loadAllForSource })
    roles.openRenameRoleDialog({ id: 1, name: 'Reader', template: 'READONLY' })
    roles.renameRoleForm.template = 'READWRITE'
    await roles.submitRenameRole()
    expect(permissionsApi.updateRole).toHaveBeenCalledWith(1, 1, { name: 'Reader', template: 'READWRITE' })
    expect(loadAllForSource).toHaveBeenCalledWith(1)
  })

  it('renders readable changes before collapsed native SQL', async () => {
    const wrapper = mount(RolePreviewDialog, { props: { model: reactive({ previewDialogVisible: true, selectedRole: { name: 'Reader' }, countRoleMembers: () => 2, getResourceTypeLabel: () => '表', formatDateTime: (v) => v, previewResult: { changes: [
      { changeType: 'UPDATE', resourceType: 'TABLE', database: 'sales', tableName: 'orders', beforeActions: ['QUERY'], afterActions: ['WRITE'], addedActions: ['WRITE'], removedActions: ['QUERY'] },
    ], sql: ['GRANT INSERT ...'] } }) }, global: { plugins: [ElementPlus], stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } } } })
    await flushPromises()
    const table = wrapper.get('[data-testid="preview-changes"]')
    expect(table.text()).toContain('调整')
    expect(table.text()).toContain('sales.orders')
    expect(table.text()).toContain('+ 写入数据')
    expect(table.text()).toContain('− 查询数据')
    expect(wrapper.get('details').attributes('open')).toBeUndefined()
    expect(wrapper.get('[data-testid="preview-sql"]').text()).toContain('GRANT INSERT')
    wrapper.unmount()
  })
})
