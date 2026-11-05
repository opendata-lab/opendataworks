import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const usersApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  resetPassword: vi.fn(),
  remove: vi.fn(),
  listTokens: vi.fn(), createToken: vi.fn(), revokeToken: vi.fn(),
  datasourceAccess: vi.fn()
}))

const permissionsApiMock = vi.hoisted(() => ({ removeMember: vi.fn() }))
vi.mock('@/api/permissions', () => ({ permissionsApi: permissionsApiMock }))

const messagesMock = vi.hoisted(() => ({
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn()
}))

const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/users', () => ({
  usersApi: usersApiMock,
  default: usersApiMock
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal()),
  ElMessage: messagesMock,
  ElMessageBox: { confirm: confirmMock }
}))

import { useAuthStore } from '@/stores/auth'
import UserManagement from '../UserManagement.vue'

const sampleUsers = [
  {
    id: 1,
    username: 'admin',
    nickname: '系统管理员',
    email: 'admin@example.com',
    role: 'admin',
    enabled: true,
    authSource: 'local',
    lastLoginAt: '2026-11-01T10:00:00'
  },
  {
    id: 2,
    username: 'zhangming',
    nickname: '张明',
    email: 'zhangming@example.com',
    role: 'user',
    enabled: true,
    authSource: 'local',
    lastLoginAt: '2026-11-01T09:00:00'
  },
  {
    id: 3,
    username: 'oauth_user',
    nickname: '王敏',
    email: 'wangmin@example.com',
    role: 'user',
    enabled: true,
    authSource: 'github',
    lastLoginAt: null
  }
]

describe('UserManagement 用户管理组件测试', () => {
  let pinia

  beforeEach(() => {
    vi.clearAllMocks()
    pinia = createPinia()
    setActivePinia(pinia)
    const authStore = useAuthStore()
    authStore.currentUser = { id: 1, username: 'admin', role: 'admin' }
    authStore.initialized = true

    usersApiMock.list.mockResolvedValue(sampleUsers.map((u) => ({ ...u })))
    usersApiMock.create.mockResolvedValue({ id: 4, username: 'newuser', nickname: '新用户', role: 'user', enabled: true })
    usersApiMock.update.mockResolvedValue({})
    usersApiMock.resetPassword.mockResolvedValue({})
    usersApiMock.remove.mockResolvedValue({})
    usersApiMock.listTokens.mockResolvedValue([])
  })

  const mountView = async () => {
    const wrapper = mount(UserManagement, {
      global: {
        plugins: [pinia, ElementPlus],
        stubs: {
          'el-dialog': {
            props: ['modelValue', 'title'],
            template: '<div v-if="modelValue" class="dialog-stub"><slot /><slot name="footer" /></div>'
          }
        }
      }
    })
    await flushPromises()
    return wrapper
  }

  it('校验新建用户表单：必填项、重复用户名、密码 至少 8 位，最多 72 字节限制，成功后清空密码不回显', async () => {
    const wrapper = await mountView()
    const vm = wrapper.vm

    vm.openCreate()
    await flushPromises()

    await vm.submitUser()
    expect(vm.formError).toContain('请输入平台用户名')
    expect(usersApiMock.create).not.toHaveBeenCalled()

    vm.form.username = 'zhangming'
    vm.form.nickname = '重复用户'
    vm.form.password = '12345678'
    await vm.submitUser()
    expect(vm.formError).toContain('用户名已存在')

    vm.form.username = 'lihua'
    vm.form.nickname = ''
    await vm.submitUser()
    expect(vm.formError).toContain('请输入姓名')

    vm.form.nickname = '李华'
    vm.form.password = 'short7!'
    await vm.submitUser()
    expect(vm.formError).toContain('至少 8 位，最多 72 字节')
    expect(usersApiMock.create).not.toHaveBeenCalled()

    vm.form.password = '三个字'
    await vm.submitUser()
    expect(vm.formError).toContain('至少 8 位，最多 72 字节')
    expect(usersApiMock.create).not.toHaveBeenCalled()

    vm.form.password = 'a'.repeat(73)
    await vm.submitUser()
    expect(vm.formError).toContain('至少 8 位，最多 72 字节')
    expect(usersApiMock.create).not.toHaveBeenCalled()

    vm.form.password = 'validPass123'
    await vm.submitUser()
    expect(usersApiMock.create).toHaveBeenCalledWith({
      accountType: 'USER',
      username: 'lihua',
      nickname: '李华',
      email: '',
      password: 'validPass123',
      role: 'user',
      enabled: true
    })
    expect(vm.form.password).toBe('')
    expect(messagesMock.success).toHaveBeenCalledWith('用户已创建')
  })

  it('编辑用户时不回显密码、本地用户名只读，且禁止停用/删除/降级自己或最后一个管理员', async () => {
    const wrapper = await mountView()
    const vm = wrapper.vm

    // 当前登录管理员自己（同时也是唯一启用管理员）
    const adminRow = sampleUsers[0]
    expect(vm.getProtectionReason(adminRow, 'disable')).toContain('不能停用')
    expect(vm.getProtectionReason(adminRow, 'delete')).toContain('不能删除')
    expect(vm.getProtectionReason(adminRow, 'demote')).toContain('不能降级')

    vm.openEdit(sampleUsers[1])
    await flushPromises()
    expect(vm.isEdit).toBe(true)
    expect(vm.form.username).toBe('zhangming')
    expect(vm.form.password).toBe('')

    vm.form.nickname = '张明-更新'
    vm.form.role = 'admin'
    await vm.submitUser()
    expect(usersApiMock.update).toHaveBeenCalledWith(2, {
      nickname: '张明-更新',
      email: 'zhangming@example.com',
      role: 'admin',
      enabled: true
    })
  })

  it('重置密码仅允许本地账号并校验 至少 8 位，最多 72 字节，第三方账号拒绝设置本地密码', async () => {
    const wrapper = await mountView()
    const vm = wrapper.vm

    // 第三方账号不允许打开重置密码
    vm.openResetPassword(sampleUsers[2])
    expect(vm.resetDialogVisible).toBe(false)
    expect(messagesMock.warning).toHaveBeenCalledWith('第三方账号不支持设置本地密码')

    // 本地账号可重置密码
    vm.openResetPassword(sampleUsers[1])
    expect(vm.resetDialogVisible).toBe(true)

    vm.resetForm.password = '12345'
    await vm.submitResetPassword()
    expect(vm.resetError).toContain('至少 8 位，最多 72 字节')
    expect(usersApiMock.resetPassword).not.toHaveBeenCalled()

    vm.resetForm.password = 'newStrongPass88'
    await vm.submitResetPassword()
    expect(usersApiMock.resetPassword).toHaveBeenCalledWith(2, { password: 'newStrongPass88' })
    expect(vm.resetForm.password).toBe('')
    expect(messagesMock.success).toHaveBeenCalledWith('密码已重置')
  })

  it('新建服务账号只提交名称用途，隐藏密码和管理员选项，成功后打开 Token 管理', async () => {
    const wrapper = await mountView()
    const vm = wrapper.vm
    vm.openCreate('SERVICE')
    await flushPromises()
    const dialog = wrapper.find('.dialog-stub')
    expect(dialog.find('[aria-label="平台用户名"]').exists()).toBe(false)
    expect(dialog.find('[aria-label="初始登录密码"]').exists()).toBe(false)
    expect(dialog.find('[aria-label="平台角色"]').exists()).toBe(false)
    usersApiMock.create.mockResolvedValueOnce({ id: 7, accountType: 'SERVICE', nickname: '问数服务', enabled: true })
    vm.form.nickname = '问数服务'
    vm.form.description = 'DataAgent 查询'
    await vm.submitUser()
    expect(usersApiMock.create).toHaveBeenCalledWith({ accountType: 'SERVICE', nickname: '问数服务', description: 'DataAgent 查询', enabled: true })
    expect(vm.tokensVisible).toBe(true)
    expect(vm.tokenTarget.id).toBe(7)
    vm.openResetPassword(vm.tokenTarget)
    expect(vm.resetDialogVisible).toBe(false)
    expect(usersApiMock.resetPassword).not.toHaveBeenCalled()
  })

  it('服务账号编辑保持类型和系统标识不变，启停只提交状态', async () => {
    const wrapper = await mountView()
    const vm = wrapper.vm
    const service = { id: 7, accountType: 'SERVICE', username: 'svc_generated', nickname: '问数', enabled: true, authSource: 'service' }
    vm.openEdit(service)
    vm.form.nickname = '问数生产'
    await vm.submitUser()
    expect(usersApiMock.update).toHaveBeenCalledWith(7, { nickname: '问数生产', description: '', enabled: true })
    usersApiMock.datasourceAccess.mockResolvedValueOnce({ userId: 7, accountType: 'SERVICE', sources: [] })
    await vm.handleToggleEnabled(service)
    expect(vm.accessAction.visible).toBe(true)
    await vm.confirmAccessAction()
    expect(usersApiMock.update).toHaveBeenLastCalledWith(7, { enabled: false })
  })

  it('读取数据源权限失败时不能确认删除，可重试', async () => {
    const wrapper = await mountView()
    const vm = wrapper.vm
    usersApiMock.datasourceAccess.mockRejectedValueOnce(new Error('网络错误'))
    await vm.handleDelete(sampleUsers[1])
    expect(vm.accessAction.loadError).toContain('网络错误')
    await vm.confirmAccessAction()
    expect(usersApiMock.remove).not.toHaveBeenCalled()
    usersApiMock.datasourceAccess.mockResolvedValueOnce({ userId: 2, accountType: 'USER', sources: [] })
    await vm.openAccessAction(sampleUsers[1], 'delete')
    await vm.confirmAccessAction()
    expect(usersApiMock.remove).toHaveBeenCalledWith(2)
  })

  it('删除账号前逐源回收数据源权限，任一源失败则不删除账号', async () => {
    const wrapper = await mountView()
    const vm = wrapper.vm
    const access = {
      userId: 2,
      accountType: 'USER',
      sources: [
        { sourceId: 10, clusterName: 'MySQL A', member: { roleIds: [1] } },
        { sourceId: 11, clusterName: 'PG B', member: { roleIds: [2] } },
        { sourceId: 12, clusterName: 'Doris C', member: null }
      ]
    }
    usersApiMock.datasourceAccess.mockResolvedValue(access)
    permissionsApiMock.removeMember.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('管理连接不可用'))

    await vm.handleDelete(sampleUsers[1])
    expect(vm.accessActionSources.map((x) => x.sourceId)).toEqual([10, 11])
    expect(vm.accessAction.revoke).toBe(true)
    await vm.confirmAccessAction()
    expect(permissionsApiMock.removeMember).toHaveBeenCalledWith(10, 'USER', '2')
    expect(permissionsApiMock.removeMember).toHaveBeenCalledWith(11, 'USER', '2')
    expect(usersApiMock.remove).not.toHaveBeenCalled()
    expect(vm.accessAction.failures).toEqual([{ sourceId: 11, clusterName: 'PG B', message: '管理连接不可用' }])
    expect(vm.accessAction.visible).toBe(true)
    // 部分失败后重新读取权限，重试时只处理仍有权限的源
    expect(usersApiMock.datasourceAccess).toHaveBeenCalledTimes(2)

    // 不勾选回收：只删除平台账号，原生权限不变
    permissionsApiMock.removeMember.mockClear()
    vm.accessAction.revoke = false
    await vm.confirmAccessAction()
    expect(permissionsApiMock.removeMember).not.toHaveBeenCalled()
    expect(usersApiMock.remove).toHaveBeenCalledWith(2)
    expect(vm.accessAction.visible).toBe(false)
  })
})
