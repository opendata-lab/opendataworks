import { flushPromises, mount, enableAutoUnmount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

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
  listOperations: vi.fn(),
}))

const usersApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
}))

const nl2sqlApiMock = vi.hoisted(() => ({
  listAgents: vi.fn(),
}))

const messagesMock = vi.hoisted(() => ({
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
}))
const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/permissions', () => ({
  permissionsApi: permissionsApiMock,
  default: permissionsApiMock,
}))

vi.mock('@/api/users', () => ({
  usersApi: usersApiMock,
  default: usersApiMock,
}))

vi.mock('@/api/nl2sql', () => ({
  nl2sqlApi: nl2sqlApiMock,
  default: nl2sqlApiMock,
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal()),
  ElMessage: messagesMock,
  ElMessageBox: { confirm: confirmMock },
}))

import DatasourcePermissionCenter from '../components/DatasourcePermissionCenter.vue'

enableAutoUnmount(afterEach)

const feature = (wrapper, name) => reactive(wrapper.vm[name])

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
        { resourceType: 'TABLE', database: 'public', tableName: 'orders', actions: ['QUERY'] },
      ],
    },
    {
      id: 102,
      name: '客户只读',
      nativeName: 'odw_crm_ro',
      template: 'READONLY',
      revision: 1,
      status: 'ACTIVE',
      grants: [
        { resourceType: 'TABLE', database: 'public', tableName: 'customers', actions: ['QUERY'] },
      ],
    },
    {
      id: 103,
      name: '财务读写',
      nativeName: 'odw_fin_rw',
      template: 'READWRITE',
      revision: 2,
      status: 'ACTIVE',
      grants: [
        {
          resourceType: 'TABLE',
          database: 'public',
          tableName: 'orders',
          actions: ['QUERY', 'WRITE'],
        },
      ],
    },
  ],
  members: [
    {
      subjectType: 'USER',
      subjectId: '2',
      name: '张明',
      roleIds: [101],
      account: { username: 'db_zhangming', configured: true },
      status: 'ACTIVE',
      revision: 4,
    },
    {
      subjectType: 'SERVICE',
      subjectId: '7',
      name: '零售问数助手',
      roleIds: [101],
      account: { username: 'db_retail_bot', configured: true },
      status: 'ACTIVE',
      revision: 1,
    },
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
        note: 'PG Schema 查询仅展开当前表，未来新表不自动授权',
      },
      {
        key: 'WRITE',
        label: '写入数据',
        resourceTypes: ['TABLE', 'VIEW'],
        nativeActions: ['INSERT', 'UPDATE', 'DELETE'],
        note: '可细分 INSERT / UPDATE / DELETE',
      },
      {
        key: 'ALTER',
        label: '修改结构',
        resourceTypes: [],
        nativeActions: [],
        supported: false,
        note: 'PostgreSQL ALTER 由对象所有权控制，不支持独立 GRANT',
      },
    ],
  },
})

describe('DatasourcePermissionCenter 组件测试', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    permissionsApiMock.getOverview.mockResolvedValue(makeOverview(10))
    permissionsApiMock.listResources.mockResolvedValue({
      databases: ['public', 'crm'],
      objects: [
        { database: 'public', tableName: 'orders', resourceType: 'TABLE', comment: '订单表' },
        { database: 'public', tableName: 'customers', resourceType: 'TABLE', comment: '客户表' },
      ],
    })
    permissionsApiMock.listOperations.mockResolvedValue([
      {
        id: 'OP-9001',
        roleId: 101,
        operatorId: 'admin',
        state: 'PARTIAL',
        message: '部分步骤待核对',
        createdAt: '2026-11-01T10:00:00',
        steps: [
          { sql: 'GRANT SELECT ON TABLE "public"."orders" TO odw_sales_ro', state: 'SUCCESS' },
        ],
      },
    ])
    usersApiMock.list.mockResolvedValue([
      { id: 2, username: 'zhangming', nickname: '张明', enabled: true },
      { id: 3, username: 'liyue', nickname: '李悦', enabled: true },
      {
        id: 7,
        username: 'svc_retail',
        nickname: '零售问数服务',
        accountType: 'SERVICE',
        enabled: true,
      },
      {
        id: 8,
        username: 'svc_disabled',
        nickname: '停用服务',
        accountType: 'SERVICE',
        enabled: false,
      },
    ])
    nl2sqlApiMock.listAgents.mockResolvedValue([
      { agent_id: 'retail_bot', display_name: '零售问数助手' },
    ])
  })

  it('角色删除部分成功时展示操作记录，不能提示已删除', async () => {
    confirmMock.mockResolvedValue('confirm')
    permissionsApiMock.deleteRole.mockResolvedValue({
      operationId: 99,
      state: 'PARTIAL',
      message: '原生删除已执行，平台保存失败',
      steps: [],
    })
    const wrapper = await mountCenter()
    await feature(wrapper, 'roleState').handleDeleteRole({ id: 999, name: '未分配角色' })
    await flushPromises()
    expect(messagesMock.success).not.toHaveBeenCalled()
    expect(messagesMock.error).toHaveBeenCalledWith(expect.stringContaining('99'))
    expect(feature(wrapper, 'operationState').operationDetailVisible).toBe(true)
    expect(feature(wrapper, 'operationState').operationDetail.state).toBe('PARTIAL')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查看授权记录')
      .trigger('click')
    await flushPromises()
    expect(wrapper.vm.activeTab).toBe('operations')
    expect(feature(wrapper, 'operationState').operationDetailVisible).toBe(false)
    expect(permissionsApiMock.listOperations).toHaveBeenCalledWith(10)
  })

  it('打开及切换角色时定位已授权库和资源类型，刷新后仍展示对象', async () => {
    const wrapper = await mountCenter()
    expect(feature(wrapper, 'matrixState').selectedDatabase).toBe('public')
    expect(permissionsApiMock.listResources).toHaveBeenCalledWith(10, { database: 'public' })
    feature(wrapper, 'matrixState').selectRole(102)
    await flushPromises()
    expect(feature(wrapper, 'matrixState').currentResourceType).toBe('TABLE')
    expect(
      feature(wrapper, 'matrixState').resourcesState.objects.some(
        (item) => item.tableName === 'customers'
      )
    ).toBe(true)
    await wrapper.vm.loadAllForSource(10)
    await flushPromises()
    expect(
      feature(wrapper, 'matrixState').resourcesState.objects.some(
        (item) => item.tableName === 'customers'
      )
    ).toBe(true)
  })

  const mountCenter = async (
    cluster = { id: 10, clusterName: '分析 PG', sourceType: 'POSTGRESQL' },
    clusters = []
  ) => {
    const wrapper = mount(DatasourcePermissionCenter, {
      props: {
        modelValue: true,
        cluster,
        clusters,
      },
      global: {
        plugins: [ElementPlus],
        stubs: {
          'el-drawer': {
            props: ['modelValue'],
            template:
              '<div v-if="modelValue" class="drawer-stub"><slot name="header" /><slot /></div>',
          },
          'el-dialog': {
            props: ['modelValue', 'title'],
            template:
              '<div v-if="modelValue" class="dialog-stub" :data-title="title"><slot /><slot name="footer" /></div>',
          },
        },
      },
    })
    await flushPromises()
    return wrapper
  }

  it('默认打开成员页签，支持成员分配多个角色，服务账号也可选择获授权的读写角色', async () => {
    const wrapper = await mountCenter()
    const vm = feature(wrapper, 'memberState')

    expect(wrapper.vm.activeTab).toBe('members')
    expect(wrapper.find('[data-testid="tab-members"]').classes()).toContain('active')

    // 为张明分配多个只读+读写角色
    const userMember = wrapper.vm.overview.members[0]
    vm.openRoleAssignDialog(userMember)
    await flushPromises()
    vm.toggleMemberRole(102, true)
    vm.toggleMemberRole(103, true)
    expect(vm.roleAssignForm.roleIds).toEqual([101, 102, 103])

    permissionsApiMock.assignMemberRoles.mockResolvedValueOnce({})
    await vm.submitRoleAssign()
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenCalledWith(10, 'USER', '2', {
      roleIds: [101, 102, 103],
      expectedRevision: 4,
    })

    // 服务账号写权限由所分配的原生角色决定
    const agentMember = wrapper.vm.overview.members[1]
    vm.openRoleAssignDialog(agentMember)
    vm.roleAssignForm.roleIds = [101, 103]
    await vm.submitRoleAssign()
    expect(vm.roleAssignError).toBe('')
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenCalledTimes(2)
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenLastCalledWith(10, 'SERVICE', agentMember.subjectId, { roleIds: [101, 103], expectedRevision: agentMember.revision ?? 0 })
  })

  it('授权用户支持新建用户两步提交：创建用户成功但分配角色失败时明确提示已创建用户并切回已有用户', async () => {
    const wrapper = await mountCenter()
    const vm = feature(wrapper, 'memberState')

    vm.openAuthorizeDialog('new')
    vm.authorizeForm.username = 'wangning'
    vm.authorizeForm.nickname = '王宁'
    vm.authorizeForm.password = 'initPass123'
    vm.authorizeForm.roleIds = [101, 102]

    usersApiMock.create.mockResolvedValueOnce({
      id: 99,
      username: 'wangning',
      nickname: '王宁',
      enabled: true,
    })
    permissionsApiMock.assignMemberRoles.mockRejectedValueOnce(new Error('原生 ROLE 授权超时'))

    await vm.submitAuthorize()
    expect(usersApiMock.create).toHaveBeenCalledWith(
      expect.objectContaining({
        username: 'wangning',
        nickname: '王宁',
        password: 'initPass123',
      })
    )
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenCalledWith(10, 'USER', 99, {
      roleIds: [101, 102],
      expectedRevision: 0,
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
    const vm = feature(wrapper, 'memberState')
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
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenCalledWith(10, 'SERVICE', '7', {
      roleIds: [101],
      expectedRevision: 1,
    })
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
    const vm = feature(wrapper, 'memberState')

    const userMember = wrapper.vm.overview.members[0]
    vm.openAccountDialog(userMember)
    expect(vm.accountForm.username).toBe('db_zhangming')
    expect(vm.accountForm.password).toBe('')

    // 原账号留空密码可直接保存
    permissionsApiMock.bindMemberAccount.mockResolvedValueOnce({})
    await vm.submitAccountBind()
    expect(permissionsApiMock.bindMemberAccount).toHaveBeenCalledWith(10, 'USER', '2', {
      username: 'db_zhangming',
      password: '',
      expectedRevision: 4,
    })

    // 更换账号但未填密码必须拒绝
    vm.openAccountDialog(userMember)
    vm.accountForm.username = 'db_zhangming_new'
    vm.accountForm.password = ''
    await vm.submitAccountBind()
    expect(vm.accountError).toContain('首次绑定或更换账号时，请输入密码')
    expect(permissionsApiMock.bindMemberAccount).toHaveBeenCalledTimes(1)

    // 管理连接同理
    feature(wrapper, 'managementState').openManagementDialog()
    feature(wrapper, 'managementState').managementForm.username = 'new_grant_admin'
    feature(wrapper, 'managementState').managementForm.password = ''
    await feature(wrapper, 'managementState').submitManagementAccount()
    expect(feature(wrapper, 'managementState').managementError).toContain(
      '首次配置或更换管理账号时，请输入密码'
    )
    expect(permissionsApiMock.saveManagementAccount).not.toHaveBeenCalled()
  })

  it('角色资源矩阵预览 SQL 与 warnings 后 apply：仅 state=SUCCESS 提示成功，非 SUCCESS 明确展示操作 ID 与状态', async () => {
    const wrapper = await mountCenter()
    const vm = feature(wrapper, 'roleState')
    const matrix = feature(wrapper, 'matrixState')

    wrapper.vm.switchTab('roles')
    await flushPromises()
    matrix.switchResourceType('TABLE')
    await flushPromises()

    // 勾选 customers 表的 QUERY 权限
    const customerRow = matrix.currentResourceRows.find((r) => r.tableName === 'customers')
    const queryCol = matrix.matrixActionColumns.find((c) => c.key === 'QUERY')
    matrix.toggleMatrixAction(customerRow, queryCol, true)
    expect(matrix.hasDraftChanges).toBe(true)

    permissionsApiMock.previewRoleGrants.mockResolvedValueOnce({
      previewId: 'prev-2026-01',
      sql: ['GRANT SELECT ON TABLE "public"."customers" TO odw_sales_ro;'],
      warnings: ['PG Schema 查询不自动覆盖未来新增表'],
      memberCount: 2,
      expiresAt: '2026-11-01T12:10:00',
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
      steps: [
        { sql: 'GRANT SELECT ON TABLE "public"."customers" TO odw_sales_ro;', state: 'PARTIAL' },
      ],
    })

    await vm.handleApplyGrants()
    await flushPromises()
    expect(messagesMock.success).not.toHaveBeenCalled()
    expect(messagesMock.error).toHaveBeenCalledWith(expect.stringContaining('OP-PARTIAL-77'))
    expect(messagesMock.error).toHaveBeenCalledWith(expect.stringContaining('PARTIAL'))
    expect(feature(wrapper, 'operationState').operationDetailVisible).toBe(true)
    expect(wrapper.find('[data-testid="operation-result-id"]').text()).toBe('OP-PARTIAL-77')
    expect(wrapper.find('[data-testid="operation-result-banner"]').text()).toContain('PARTIAL')

    // 场景 2：返回 SUCCESS，才允许提示成功
    messagesMock.error.mockClear()
    vm.previewResult = { previewId: 'prev-2026-02', sql: [], warnings: [] }
    permissionsApiMock.applyRoleGrants.mockResolvedValueOnce({
      operationId: 'OP-OK-88',
      state: 'SUCCESS',
      message: '权限变更已生效',
      steps: [],
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
        roles: [
          {
            id: 201,
            name: '数仓专属角色',
            nativeName: 'odw_dw_role',
            template: 'READONLY',
            grants: [],
          },
        ],
        members: [],
      })
    })

    const clusters = [
      { id: 10, clusterName: '旧源 PG', sourceType: 'POSTGRESQL' },
      { id: 20, clusterName: '新源 Doris', sourceType: 'DORIS' },
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
      grants: [
        { database: 'public', tableName: 'orders', actions: ['SELECT'], source: 'odw_sales_ro' },
      ],
    })

    const wrapper = await mountCenter()
    const vm = feature(wrapper, 'memberState')

    await vm.openInspectDialog(wrapper.vm.overview.members[0])
    await flushPromises()
    expect(permissionsApiMock.inspectMember).toHaveBeenCalledWith(10, 'USER', '2')
    expect(wrapper.find('[data-testid="inspect-notice"]').text()).toContain(
      'DBA 直接授予的外部权限'
    )

    wrapper.vm.switchTab('operations')
    await flushPromises()
    expect(permissionsApiMock.listOperations).toHaveBeenCalledWith(10)
    expect(feature(wrapper, 'operationState').operationsState.list[0].id).toBe('OP-9001')
  })
  it('拆分后的成员表单通过实际控件编辑并保存，筛选仍作用于列表', async () => {
    const wrapper = await mountCenter()
    await wrapper.find('[data-testid="assign-role-btn"]').trigger('click')
    await wrapper.find('[data-testid="member-role-check-102"]').setValue(true)
    await wrapper.find('[data-testid="save-member-roles-btn"]').trigger('click')
    await flushPromises()
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenCalledWith(10, 'USER', '2', {
      roleIds: [101, 102],
      expectedRevision: 4,
    })
    await wrapper.find('[data-testid="bind-account-link"]').trigger('click')
    await wrapper.find('input[aria-label="成员数据库账号"]').setValue('db_new_reader')
    await wrapper.find('input[aria-label="成员数据库密码"]').setValue('synthetic-password')
    await wrapper.find('[data-testid="save-member-account-btn"]').trigger('click')
    await flushPromises()
    expect(permissionsApiMock.bindMemberAccount).toHaveBeenCalledWith(10, 'USER', '2', {
      username: 'db_new_reader',
      password: 'synthetic-password',
      expectedRevision: 4,
    })
    expect(feature(wrapper, 'memberState').accountForm.password).toBe('')
    await wrapper.find('input[aria-label="搜索成员"]').setValue('零售')
    expect(wrapper.find('.members-panel .el-table').text()).toContain('零售问数助手')
    expect(wrapper.find('.members-panel .el-table').text()).not.toContain('张明')
  })

  it('拆分后的权限矩阵点击生成草稿并打开预览', async () => {
    permissionsApiMock.previewRoleGrants.mockResolvedValueOnce({
      previewId: 'DOM-PREVIEW',
      sql: ['GRANT SELECT'],
      warnings: [],
    })
    const wrapper = await mountCenter()
    await wrapper.find('[data-testid="tab-roles"]').trigger('click')
    await wrapper.find('[data-testid="resource-type-TABLE"]').trigger('click')
    await wrapper.find('[data-testid="grant-check-customers-QUERY"]').setValue(true)
    await wrapper.find('[data-testid="preview-grants-btn"]').trigger('click')
    await flushPromises()
    expect(permissionsApiMock.previewRoleGrants).toHaveBeenCalledWith(10, 101, {
      expectedRevision: 3,
      grants: expect.arrayContaining([
        { resourceType: 'TABLE', database: 'public', tableName: 'customers', actions: ['QUERY'] },
      ]),
    })
    expect(wrapper.find('[data-testid="preview-sql"]').text()).toContain('GRANT SELECT')
  })

  it('关闭并重开同一数据源时，迟到的资源、记录及预览不能覆盖新页面', async () => {
    const wrapper = await mountCenter()
    let resolveResources, resolveOperations, resolvePreview
    permissionsApiMock.listResources.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveResources = resolve
        })
    )
    permissionsApiMock.listOperations.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOperations = resolve
        })
    )
    permissionsApiMock.previewRoleGrants.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolvePreview = resolve
        })
    )
    const matrix = feature(wrapper, 'matrixState')
    const operations = feature(wrapper, 'operationState')
    const roles = feature(wrapper, 'roleState')
    const pending = [
      matrix.loadResources('public'),
      operations.loadOperations(),
      roles.handlePreviewGrants(),
    ]
    const members = feature(wrapper, 'memberState')
    members.openAuthorizeDialog('new')
    members.authorizeForm.password = 'synthetic-secret'
    members.openAccountDialog(wrapper.vm.overview.members[0])
    members.accountForm.password = 'synthetic-secret'
    const management = feature(wrapper, 'managementState')
    management.openManagementDialog()
    management.managementForm.password = 'synthetic-secret'
    await wrapper.setProps({ modelValue: false })
    expect(members.authorizeForm.password).toBe('')
    expect(members.accountForm.password).toBe('')
    expect(management.managementForm.password).toBe('')
    expect(members.authorizeDialogVisible).toBe(false)
    expect(members.accountDialogVisible).toBe(false)
    expect(management.managementDialogVisible).toBe(false)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    resolveResources({ databases: ['stale'], objects: [{ tableName: 'stale' }] })
    resolveOperations([{ id: 'STALE-OP' }])
    resolvePreview({ previewId: 'STALE-PREVIEW', sql: [] })
    await Promise.all(pending)
    await flushPromises()
    expect(matrix.resourcesState.databases).toEqual(['public', 'crm'])
    expect(operations.operationsState.list).toEqual([])
    expect(roles.previewDialogVisible).toBe(false)
    expect(roles.previewResult).toBe(null)
    expect(roles.previewing).toBe(false)
  })

  it('切源后不把旧管理连接保存结果写入新源，也不清除新表单', async () => {
    const wrapper = await mountCenter()
    const management = feature(wrapper, 'managementState')
    let resolveSave
    permissionsApiMock.saveManagementAccount.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveSave = resolve
        })
    )
    management.openManagementDialog()
    management.managementForm.password = 'synthetic-secret'
    const pending = management.submitManagementAccount()
    wrapper.vm.handleSourceSwitch(20)
    await flushPromises()
    management.openManagementDialog()
    management.managementForm.username = 'new_source_admin'
    resolveSave({ username: 'old_source_admin', configured: true })
    await pending
    expect(wrapper.vm.overview.management.username).toBe('pg_grant_admin')
    expect(management.managementForm.username).toBe('new_source_admin')
    expect(management.managementDialogVisible).toBe(true)
    expect(messagesMock.success).not.toHaveBeenCalled()
  })

  it('连续核查两个成员时保留最后所选成员的原生权限', async () => {
    const wrapper = await mountCenter()
    const members = feature(wrapper, 'memberState')
    let resolveFirst
    permissionsApiMock.inspectMember.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveFirst = resolve
        })
    )
    permissionsApiMock.inspectMember.mockResolvedValueOnce({
      username: 'second_reader',
      grants: [],
    })
    const first = members.openInspectDialog(wrapper.vm.overview.members[0])
    await members.openInspectDialog(wrapper.vm.overview.members[1])
    resolveFirst({ username: 'stale_reader', grants: [] })
    await first
    expect(members.inspectTarget.subjectId).toBe('7')
    expect(members.inspectState.data.username).toBe('second_reader')
    expect(members.inspectState.loading).toBe(false)
  })

  it('删除确认期间切源，不向新源或旧源提交删除', async () => {
    const wrapper = await mountCenter()
    let resolveConfirm
    confirmMock.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveConfirm = resolve
        })
    )
    const pending = feature(wrapper, 'roleState').handleDeleteRole({ id: 999, name: '未分配角色' })
    wrapper.vm.handleSourceSwitch(20)
    resolveConfirm('confirm')
    await pending
    expect(permissionsApiMock.deleteRole).not.toHaveBeenCalled()
  })

  it('新建用户请求期间切源，不继续向其他数据源分配角色', async () => {
    const wrapper = await mountCenter()
    const members = feature(wrapper, 'memberState')
    let resolveCreate
    usersApiMock.create.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveCreate = resolve
        })
    )
    members.openAuthorizeDialog('new')
    Object.assign(members.authorizeForm, {
      username: 'test_reader',
      nickname: '测试',
      email: '',
      password: 'TestPass123!',
      roleIds: [101],
    })
    const pending = members.submitAuthorize()
    expect(usersApiMock.create).toHaveBeenCalledOnce()
    wrapper.vm.handleSourceSwitch(20)
    resolveCreate({ id: 999, username: 'test_reader' })
    await pending
    expect(permissionsApiMock.assignMemberRoles).not.toHaveBeenCalled()
    expect(members.authorizeForm.password).toBe('')
    expect(members.authorizing).toBe(false)
  })

  it('切换角色后丢弃旧角色的预览，不能应用到新角色', async () => {
    const wrapper = await mountCenter()
    let resolvePreview
    permissionsApiMock.previewRoleGrants.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolvePreview = resolve
        })
    )
    const roles = feature(wrapper, 'roleState')
    const pending = roles.handlePreviewGrants()
    feature(wrapper, 'matrixState').selectRole(102)
    feature(wrapper, 'matrixState').selectRole(101)
    resolvePreview({ previewId: 'OLD-ROLE', sql: [] })
    await pending
    expect(roles.previewDialogVisible).toBe(false)
    expect(roles.previewResult).toBe(null)
    await roles.handleApplyGrants()
    expect(permissionsApiMock.applyRoleGrants).not.toHaveBeenCalled()
  })
  it('托管账号显示自动创建且不提供手工绑定入口，移除时明确删除数据库登录', async () => {
    const data = makeOverview()
    data.members = [{ ...data.members[0], account: { username: 'odw_managed', configured: true, managed: true } }]
    permissionsApiMock.getOverview.mockResolvedValue(data)
    const wrapper = await mountCenter()
    expect(wrapper.text()).toContain('平台自动创建')
    expect(wrapper.find('[data-testid="bind-account-link"]').exists()).toBe(false)
    await feature(wrapper, 'memberState').handleRemoveMember(data.members[0])
    expect(confirmMock.mock.calls.at(-1)[0]).toContain('删除平台自动创建的数据库登录账号')
  })

})
