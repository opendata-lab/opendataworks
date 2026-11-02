import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const permissionsApiMock = vi.hoisted(() => ({
  getOverview: vi.fn(),
  createRole: vi.fn(),
  updateRole: vi.fn(),
  deleteRole: vi.fn(),
  previewRoleGrants: vi.fn(),
  applyRoleGrants: vi.fn(),
  assignMemberRoles: vi.fn(),
  bindMemberAccount: vi.fn(),
  removeMember: vi.fn(),
  inspectMember: vi.fn(),
  saveManagementAccount: vi.fn(),
  listResources: vi.fn(),
  listOperations: vi.fn()
}))

const usersApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn()
}))

const nl2sqlApiMock = vi.hoisted(() => ({
  listAgents: vi.fn()
}))

const messagesMock = vi.hoisted(() => ({
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn()
}))
const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/permissions', () => ({
  permissionsApi: permissionsApiMock,
  default: permissionsApiMock
}))

vi.mock('@/api/users', () => ({
  usersApi: usersApiMock,
  default: usersApiMock
}))

vi.mock('@/api/nl2sql', () => ({
  nl2sqlApi: nl2sqlApiMock,
  default: nl2sqlApiMock
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal()),
  ElMessage: messagesMock,
  ElMessageBox: { confirm: confirmMock }
}))

import DatasourcePermissionCenter from '../components/DatasourcePermissionCenter.vue'

const makeOverview = (sourceId = 10) => ({
  sourceId,
  engine: 'POSTGRESQL',
  roles: [
    {
      id: 101,
      name: '销售只读',
      nativeName: 'odw_sales_ro',
      template: 'READONLY',
      revision: 3,
      status: 'ACTIVE',
      grants: [
        { resourceType: 'SCHEMA', database: 'public', tableName: '', actions: ['QUERY'] },
        { resourceType: 'TABLE', database: 'public', tableName: 'orders', actions: ['QUERY'] }
      ]
    },
    {
      id: 102,
      name: '客户只读',
      nativeName: 'odw_crm_ro',
      template: 'READONLY',
      revision: 1,
      status: 'ACTIVE',
      grants: [{ resourceType: 'TABLE', database: 'public', tableName: 'customers', actions: ['QUERY'] }]
    },
    {
      id: 103,
      name: '财务读写',
      nativeName: 'odw_fin_rw',
      template: 'READWRITE',
      revision: 2,
      status: 'ACTIVE',
      grants: [{ resourceType: 'TABLE', database: 'public', tableName: 'orders', actions: ['QUERY', 'WRITE'] }]
    }
  ],
  members: [
    {
      subjectType: 'USER',
      subjectId: '2',
      name: '张明',
      roleIds: [101],
      account: { username: 'db_zhangming', configured: true },
      status: 'ACTIVE',
      revision: 4
    },
    {
      subjectType: 'SERVICE',
      subjectId: '7',
      name: '零售问数助手',
      roleIds: [101],
      account: { username: 'db_retail_bot', configured: true },
      status: 'ACTIVE',
      revision: 1
    }
  ],
  management: { username: 'pg_grant_admin', configured: true },
  capabilities: {
    resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'],
    actions: [
      {
        key: 'QUERY',
        label: '查询数据',
        resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'],
        nativeActions: ['SELECT'],
        note: 'PG Schema 查询仅展开当前表，未来新表不自动授权'
      },
      {
        key: 'WRITE',
        label: '写入数据',
        resourceTypes: ['TABLE', 'VIEW'],
        nativeActions: ['INSERT', 'UPDATE', 'DELETE'],
        note: '可细分 INSERT / UPDATE / DELETE'
      },
      {
        key: 'ALTER',
        label: '修改结构',
        resourceTypes: [],
        nativeActions: [],
        supported: false,
        note: 'PostgreSQL ALTER 由对象所有权控制，不支持独立 GRANT'
      }
    ]
  }
})

describe('DatasourcePermissionCenter 组件测试', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    permissionsApiMock.getOverview.mockResolvedValue(makeOverview(10))
    permissionsApiMock.listResources.mockResolvedValue({
      databases: ['public', 'crm'],
      objects: [
        { database: 'public', tableName: 'orders', resourceType: 'TABLE', comment: '订单表' },
        { database: 'public', tableName: 'customers', resourceType: 'TABLE', comment: '客户表' }
      ]
    })
    permissionsApiMock.listOperations.mockResolvedValue([
      {
        id: 'OP-9001',
        roleId: 101,
        operatorId: 'admin',
        state: 'PARTIAL',
        message: '部分步骤待核对',
        createdAt: '2026-11-01T10:00:00',
        steps: [{ sql: 'GRANT SELECT ON TABLE "public"."orders" TO odw_sales_ro', state: 'SUCCESS' }]
      }
    ])
    usersApiMock.list.mockResolvedValue([
      { id: 2, username: 'zhangming', nickname: '张明', enabled: true },
      { id: 3, username: 'liyue', nickname: '李悦', enabled: true },
      { id: 7, username: 'svc_retail', nickname: '零售问数服务', accountType: 'SERVICE', enabled: true },
      { id: 8, username: 'svc_disabled', nickname: '停用服务', accountType: 'SERVICE', enabled: false }
    ])
    nl2sqlApiMock.listAgents.mockResolvedValue([{ agent_id: 'retail_bot', display_name: '零售问数助手' }])
  })

  it('角色删除部分成功时展示操作记录，不能提示已删除', async () => {
    confirmMock.mockResolvedValue('confirm')
    permissionsApiMock.deleteRole.mockResolvedValue({ operationId: 99, state: 'PARTIAL', message: '原生删除已执行，平台保存失败', steps: [] })
    const wrapper = await mountCenter()
    await wrapper.vm.handleDeleteRole({ id: 999, name: '未分配角色' })
    await flushPromises()
    expect(messagesMock.success).not.toHaveBeenCalled()
    expect(messagesMock.error).toHaveBeenCalledWith(expect.stringContaining('99'))
    expect(wrapper.vm.operationDetailVisible).toBe(true)
    expect(wrapper.vm.operationDetail.state).toBe('PARTIAL')
  })

  it('打开及切换角色时定位已授权库和资源类型，刷新后仍展示对象', async () => {
    const wrapper = await mountCenter()
    expect(wrapper.vm.selectedDatabase).toBe('public')
    expect(permissionsApiMock.listResources).toHaveBeenCalledWith(10, { database: 'public' })
    wrapper.vm.selectRole(102)
    await flushPromises()
    expect(wrapper.vm.currentResourceType).toBe('TABLE')
    expect(wrapper.vm.resourcesState.objects.some((item) => item.tableName === 'customers')).toBe(true)
    await wrapper.vm.loadAllForSource(10)
    await flushPromises()
    expect(wrapper.vm.resourcesState.objects.some((item) => item.tableName === 'customers')).toBe(true)
  })

  const mountCenter = async (cluster = { id: 10, clusterName: '分析 PG', sourceType: 'POSTGRESQL' }, clusters = []) => {
    const wrapper = mount(DatasourcePermissionCenter, {
      props: {
        modelValue: true,
        cluster,
        clusters
      },
      global: {
        plugins: [ElementPlus],
        stubs: {
          'el-drawer': {
            props: ['modelValue'],
            template: '<div v-if="modelValue" class="drawer-stub"><slot name="header" /><slot /></div>'
          },
          'el-dialog': {
            props: ['modelValue', 'title'],
            template: '<div v-if="modelValue" class="dialog-stub" :data-title="title"><slot /><slot name="footer" /></div>'
          }
        }
      }
    })
    await flushPromises()
    return wrapper
  }

  it('默认打开成员页签，支持成员分配多个角色，且禁止服务账号选择含写入的角色', async () => {
    const wrapper = await mountCenter()
    const vm = wrapper.vm

    expect(vm.activeTab).toBe('members')
    expect(wrapper.find('[data-testid="tab-members"]').classes()).toContain('active')

    // 为张明分配多个只读+读写角色
    const userMember = vm.overview.members[0]
    vm.openRoleAssignDialog(userMember)
    await flushPromises()
    vm.toggleMemberRole(102, true)
    vm.toggleMemberRole(103, true)
    expect(vm.roleAssignForm.roleIds).toEqual([101, 102, 103])

    permissionsApiMock.assignMemberRoles.mockResolvedValueOnce({})
    await vm.submitRoleAssign()
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenCalledWith(10, 'USER', '2', {
      roleIds: [101, 102, 103],
      expectedRevision: 4
    })

    // 服务账号尝试选择读写角色应被拦截
    const agentMember = vm.overview.members[1]
    vm.openRoleAssignDialog(agentMember)
    vm.roleAssignForm.roleIds = [101, 103]
    await vm.submitRoleAssign()
    expect(vm.roleAssignError).toContain('服务账号只能分配只读角色')
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenCalledTimes(1)
  })

  it('授权用户支持新建用户两步提交：创建用户成功但分配角色失败时明确提示已创建用户并切回已有用户', async () => {
    const wrapper = await mountCenter()
    const vm = wrapper.vm

    vm.openAuthorizeDialog('new')
    vm.authorizeForm.username = 'wangning'
    vm.authorizeForm.nickname = '王宁'
    vm.authorizeForm.password = 'initPass123'
    vm.authorizeForm.roleIds = [101, 102]

    usersApiMock.create.mockResolvedValueOnce({
      id: 99,
      username: 'wangning',
      nickname: '王宁',
      enabled: true
    })
    permissionsApiMock.assignMemberRoles.mockRejectedValueOnce(new Error('原生 ROLE 授权超时'))

    await vm.submitAuthorize()
    expect(usersApiMock.create).toHaveBeenCalledWith(
      expect.objectContaining({
        username: 'wangning',
        nickname: '王宁',
        password: 'initPass123'
      })
    )
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenCalledWith(10, 'USER', 99, {
      roleIds: [101, 102],
      expectedRevision: 0
    })
    // 密码已清空不回显，且明确说明用户已创建并切换至已有用户模式
    expect(vm.authorizeForm.password).toBe('')
    expect(vm.authorizeForm.mode).toBe('existing')
    expect(vm.authorizeForm.userId).toBe('99')
    expect(vm.authorizeError).toContain('已创建平台用户「王宁」（wangning）')
    expect(vm.authorizeError).toContain('原生 ROLE 授权超时')
    expect(messagesMock.warning).toHaveBeenCalled()
  })

  it('授权服务账号使用平台数值 ID，不访问 DataAgent，拒绝停用账号及用户类型混用', async () => {
    const wrapper = await mountCenter()
    const vm = wrapper.vm
    expect(nl2sqlApiMock.listAgents).not.toHaveBeenCalled()
    vm.openAuthorizeDialog('service')
    vm.authorizeForm.roleIds = [101]
    for (const id of ['external_bot', '2', '8']) {
      vm.authorizeForm.serviceAccountId = id
      await vm.submitAuthorize()
      expect(vm.authorizeError).toContain('请选择启用的平台服务账号')
      expect(permissionsApiMock.assignMemberRoles).not.toHaveBeenCalled()
    }
    vm.authorizeForm.serviceAccountId = '7'
    await vm.submitAuthorize()
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenCalledWith(10, 'SERVICE', '7', { roleIds: [101], expectedRevision: 1 })
    vm.openAuthorizeDialog('existing')
    vm.authorizeForm.userId = '7'
    vm.authorizeForm.roleIds = [101]
    await vm.submitAuthorize()
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenCalledTimes(1)
  })

  it('平台账号加载失败明确显示失败，不能静默回退至外部智能体目录', async () => {
    usersApiMock.list.mockRejectedValueOnce(new Error('账号目录不可用'))
    const wrapper = await mountCenter()
    expect(wrapper.vm.loadError).toContain('账号目录不可用')
    expect(nl2sqlApiMock.listAgents).not.toHaveBeenCalled()
  })

  it('账号绑定与管理连接：保留原账号允许空密码，更换账号或首次绑定必须填写密码', async () => {
    const wrapper = await mountCenter()
    const vm = wrapper.vm

    const userMember = vm.overview.members[0]
    vm.openAccountDialog(userMember)
    expect(vm.accountForm.username).toBe('db_zhangming')
    expect(vm.accountForm.password).toBe('')

    // 原账号留空密码可直接保存
    permissionsApiMock.bindMemberAccount.mockResolvedValueOnce({})
    await vm.submitAccountBind()
    expect(permissionsApiMock.bindMemberAccount).toHaveBeenCalledWith(10, 'USER', '2', {
      username: 'db_zhangming',
      password: '',
      expectedRevision: 4
    })

    // 更换账号但未填密码必须拒绝
    vm.openAccountDialog(userMember)
    vm.accountForm.username = 'db_zhangming_new'
    vm.accountForm.password = ''
    await vm.submitAccountBind()
    expect(vm.accountError).toContain('首次绑定或更换账号时，请输入密码')
    expect(permissionsApiMock.bindMemberAccount).toHaveBeenCalledTimes(1)

    // 管理连接同理
    vm.openManagementDialog()
    vm.managementForm.username = 'new_grant_admin'
    vm.managementForm.password = ''
    await vm.submitManagementAccount()
    expect(vm.managementError).toContain('首次配置或更换管理账号时，请输入密码')
    expect(permissionsApiMock.saveManagementAccount).not.toHaveBeenCalled()
  })

  it('角色资源矩阵预览 SQL 与 warnings 后 apply：仅 state=SUCCESS 提示成功，非 SUCCESS 明确展示操作 ID 与状态', async () => {
    const wrapper = await mountCenter()
    const vm = wrapper.vm

    vm.switchTab('roles')
    await flushPromises()
    vm.switchResourceType('TABLE')
    await flushPromises()

    // 勾选 customers 表的 QUERY 权限
    const customerRow = vm.currentResourceRows.find((r) => r.tableName === 'customers')
    const queryCol = vm.matrixActionColumns.find((c) => c.key === 'QUERY')
    vm.toggleMatrixAction(customerRow, queryCol, true)
    expect(vm.hasDraftChanges).toBe(true)

    permissionsApiMock.previewRoleGrants.mockResolvedValueOnce({
      previewId: 'prev-2026-01',
      sql: ['GRANT SELECT ON TABLE "public"."customers" TO odw_sales_ro;'],
      warnings: ['PG Schema 查询不自动覆盖未来新增表'],
      memberCount: 2,
      expiresAt: '2026-11-01T12:10:00'
    })

    await vm.handlePreviewGrants()
    await flushPromises()
    expect(vm.previewDialogVisible).toBe(true)
    expect(wrapper.find('[data-testid="preview-sql"]').text()).toContain(
      'GRANT SELECT ON TABLE "public"."customers" TO odw_sales_ro;'
    )
    expect(wrapper.find('[data-testid="preview-warnings"]').text()).toContain(
      'PG Schema 查询不自动覆盖未来新增表'
    )

    // 场景 1：返回 PARTIAL（非 SUCCESS），绝不能弹 success，必须明确展示 operationId 和 state
    permissionsApiMock.applyRoleGrants.mockResolvedValueOnce({
      operationId: 'OP-PARTIAL-77',
      state: 'PARTIAL',
      message: '远端 GRANT 已执行，平台复核发现外部权限冲突',
      steps: [{ sql: 'GRANT SELECT ON TABLE "public"."customers" TO odw_sales_ro;', state: 'PARTIAL' }]
    })

    await vm.handleApplyGrants()
    await flushPromises()
    expect(messagesMock.success).not.toHaveBeenCalled()
    expect(messagesMock.error).toHaveBeenCalledWith(expect.stringContaining('OP-PARTIAL-77'))
    expect(messagesMock.error).toHaveBeenCalledWith(expect.stringContaining('PARTIAL'))
    expect(vm.operationDetailVisible).toBe(true)
    expect(wrapper.find('[data-testid="operation-result-id"]').text()).toBe('OP-PARTIAL-77')
    expect(wrapper.find('[data-testid="operation-result-banner"]').text()).toContain('PARTIAL')

    // 场景 2：返回 SUCCESS，才允许提示成功
    messagesMock.error.mockClear()
    vm.previewResult = { previewId: 'prev-2026-02', sql: [], warnings: [] }
    permissionsApiMock.applyRoleGrants.mockResolvedValueOnce({
      operationId: 'OP-OK-88',
      state: 'SUCCESS',
      message: '权限变更已生效',
      steps: []
    })
    await vm.handleApplyGrants()
    await flushPromises()
    expect(messagesMock.success).toHaveBeenCalledWith('权限变更已生效')
    expect(messagesMock.error).not.toHaveBeenCalled()
  })

  it('切换数据源时隔离迟到的旧数据源响应', async () => {
    let resolveSource10
    permissionsApiMock.getOverview.mockImplementation((sourceId) => {
      if (Number(sourceId) === 10) {
        return new Promise((resolve) => {
          resolveSource10 = resolve
        })
      }
      return Promise.resolve({
        ...makeOverview(20),
        engine: 'DORIS',
        roles: [{ id: 201, name: '数仓专属角色', nativeName: 'odw_dw_role', template: 'READONLY', grants: [] }],
        members: []
      })
    })

    const clusters = [
      { id: 10, clusterName: '旧源 PG', sourceType: 'POSTGRESQL' },
      { id: 20, clusterName: '新源 Doris', sourceType: 'DORIS' }
    ]
    const wrapper = await mountCenter(clusters[0], clusters)
    const vm = wrapper.vm

    // 切换到新数据源 20
    vm.handleSourceSwitch(20)
    await flushPromises()
    expect(vm.overview.roles.map((r) => r.name)).toEqual(['数仓专属角色'])

    // 此时旧数据源 10 的响应才返回，应当被丢弃
    resolveSource10(makeOverview(10))
    await flushPromises()
    expect(vm.overview.sourceId).toBe(20)
    expect(vm.overview.roles.map((r) => r.name)).toEqual(['数仓专属角色'])
  })

  it('支持成员原生权限 inspect 与操作记录查看', async () => {
    permissionsApiMock.inspectMember.mockResolvedValueOnce({
      username: 'db_zhangming',
      nativeIdentity: "'db_zhangming'@'%'",
      notice: '该账号还存在 DBA 直接授予的外部权限',
      grants: [{ database: 'public', tableName: 'orders', actions: ['SELECT'], source: 'odw_sales_ro' }]
    })

    const wrapper = await mountCenter()
    const vm = wrapper.vm

    await vm.openInspectDialog(vm.overview.members[0])
    await flushPromises()
    expect(permissionsApiMock.inspectMember).toHaveBeenCalledWith(10, 'USER', '2')
    expect(wrapper.find('[data-testid="inspect-notice"]').text()).toContain('DBA 直接授予的外部权限')

    vm.switchTab('operations')
    await flushPromises()
    expect(permissionsApiMock.listOperations).toHaveBeenCalledWith(10)
    expect(vm.operationsState.list[0].id).toBe('OP-9001')
  })
})
