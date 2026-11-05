import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const usersApiMock = vi.hoisted(() => ({ datasourceAccess: vi.fn() }))
const permissionsApiMock = vi.hoisted(() => ({ assignMemberRoles: vi.fn(), removeMember: vi.fn() }))
const confirmMock = vi.hoisted(() => vi.fn())
const messagesMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }))

vi.mock('@/api/users', () => ({ usersApi: usersApiMock }))
vi.mock('@/api/permissions', () => ({ permissionsApi: permissionsApiMock }))
vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal()),
  ElMessage: messagesMock,
  ElMessageBox: { confirm: confirmMock },
}))

import SubjectAccessDrawer from '../components/SubjectAccessDrawer.vue'

const access = () => ({
  userId: 2,
  accountType: 'USER',
  sources: [
    {
      sourceId: 10,
      clusterName: 'MySQL A',
      engine: 'MYSQL',
      managementConfigured: true,
      roles: [
        {
          id: 1,
          name: '销售只读',
          status: 'ACTIVE',
          grants: [
            { resourceType: 'TABLE', database: 'sales', tableName: 'orders', actions: ['QUERY'] },
          ],
        },
        { id: 2, name: '财务只读', status: 'DRAFT', grants: [] },
      ],
      member: {
        roleIds: [1],
        revision: 4,
        status: 'ACTIVE',
        account: { configured: true, managed: true, username: 'odw_m_1' },
      },
      lastFailedOperation: null,
    },
    {
      sourceId: 11,
      clusterName: 'PG B',
      engine: 'POSTGRESQL',
      managementConfigured: true,
      roles: [{ id: 5, name: '报表只读', grants: [] }],
      member: null,
    },
    {
      sourceId: 12,
      clusterName: 'Doris C',
      engine: 'DORIS',
      managementConfigured: false,
      roles: [{ id: 7, name: '只读', grants: [] }],
      member: { roleIds: [7], revision: 1, status: 'ERROR' },
      lastFailedOperation: { state: 'FAILED', message: '激活失败' },
    },
  ],
})

const mountDrawer = async () => {
  const wrapper = mount(SubjectAccessDrawer, {
    props: { modelValue: true, account: { id: 2, username: 'zhangming', nickname: '张明' } },
    global: { plugins: [ElementPlus], stubs: { 'el-drawer': { template: '<div><slot /></div>' } } },
  })
  await wrapper.vm.load()
  await flushPromises()
  return wrapper
}

describe('SubjectAccessDrawer 按人跨数据源权限', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    usersApiMock.datasourceAccess.mockResolvedValue(access())
    confirmMock.mockResolvedValue('confirm')
  })

  it('展示各源角色摘要、账号与最近失败，并按规则限制保存', async () => {
    const wrapper = await mountDrawer()
    const text = wrapper.text()
    expect(text).toContain('已在 2 / 3 个数据源有访问权限')
    expect(text).toContain('sales.orders (查询数据)')
    expect(text).toContain('odw_m_1')
    expect(text).toContain('最近失败：激活失败')
    const vm = wrapper.vm
    const [a, b, c] = access().sources
    expect(vm.saveBlockedReason(a)).toBe('角色未变化')
    vm.drafts[10] = []
    expect(vm.saveBlockedReason(a)).toContain('移除')
    expect(vm.saveBlockedReason(b)).toBe('角色未变化')
    // 未单独配置管理连接时后端回退使用数据源账号，不在前端拦截
    vm.drafts[12] = [7, 99]
    expect(vm.saveBlockedReason(c)).toBe('')
  })

  it('保存某个数据源时提交该源角色和当前版本号', async () => {
    const wrapper = await mountDrawer()
    const vm = wrapper.vm
    vm.drafts[10] = [1, 2]
    permissionsApiMock.assignMemberRoles.mockResolvedValueOnce({})
    await vm.save(access().sources[0])
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenCalledWith(10, 'USER', '2', {
      roleIds: [1, 2],
      expectedRevision: 4,
    })
    vm.drafts[11] = [5]
    permissionsApiMock.assignMemberRoles.mockResolvedValueOnce({})
    await vm.save(access().sources[1])
    expect(permissionsApiMock.assignMemberRoles).toHaveBeenLastCalledWith(11, 'USER', '2', {
      roleIds: [5],
      expectedRevision: 0,
    })
    expect(wrapper.emitted('changed')).toHaveLength(2)
  })

  it('回收全部逐源执行，失败源不影响其他源并列出失败原因', async () => {
    const wrapper = await mountDrawer()
    permissionsApiMock.removeMember
      .mockRejectedValueOnce(new Error('锁超时'))
      .mockResolvedValueOnce({})
    await wrapper.vm.revokeAll()
    await flushPromises()
    expect(permissionsApiMock.removeMember).toHaveBeenCalledWith(10, 'USER', '2')
    expect(permissionsApiMock.removeMember).toHaveBeenCalledWith(12, 'USER', '2')
    expect(permissionsApiMock.removeMember).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('MySQL A：锁超时')
  })
})
