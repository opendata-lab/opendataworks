import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn()
}))

vi.mock('@/utils/request', () => ({
  default: requestMock
}))

import { usersApi } from '../users'
import { permissionsApi } from '../permissions'

describe('usersApi & permissionsApi 契约测试', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('服务账号筛选与 Token 生命周期使用账号 API', async () => {
    await usersApi.list({ accountType: 'SERVICE', enabled: true })
    expect(requestMock.get).toHaveBeenLastCalledWith('/v1/users', { params: { accountType: 'SERVICE', enabled: true } })
    await usersApi.listTokens(7)
    expect(requestMock.get).toHaveBeenLastCalledWith('/v1/users/7/tokens')
    await usersApi.createToken(7, { name: '问数', expiresInDays: 90 })
    expect(requestMock.post).toHaveBeenLastCalledWith('/v1/users/7/tokens', { name: '问数', expiresInDays: 90 })
    await usersApi.revokeToken(7, 19)
    expect(requestMock.delete).toHaveBeenLastCalledWith('/v1/users/7/tokens/19')
  })

  it('usersApi 按设计 10.1 调用 /v1/users 各端点', async () => {
    await usersApi.list({ keyword: 'alice', enabled: true })
    expect(requestMock.get).toHaveBeenCalledWith('/v1/users', {
      params: { keyword: 'alice', enabled: true }
    })

    await usersApi.list({ keyword: '', enabled: '' })
    expect(requestMock.get).toHaveBeenLastCalledWith('/v1/users', {
      params: {}
    })

    await usersApi.create({
      username: 'alice',
      nickname: 'Alice',
      email: 'alice@example.com',
      password: 'password123',
      role: 'user',
      enabled: true
    })
    expect(requestMock.post).toHaveBeenCalledWith(
      '/v1/users',
      {
        username: 'alice',
        nickname: 'Alice',
        email: 'alice@example.com',
        password: 'password123',
        role: 'user',
        enabled: true
      },
      {}
    )

    await usersApi.update(12, { nickname: 'Alice2', email: '', role: 'admin', enabled: false })
    expect(requestMock.patch).toHaveBeenCalledWith(
      '/v1/users/12',
      { nickname: 'Alice2', email: '', role: 'admin', enabled: false },
      {}
    )

    await usersApi.resetPassword(12, { password: 'newpassword123' })
    expect(requestMock.post).toHaveBeenCalledWith(
      '/v1/users/12/reset-password',
      { password: 'newpassword123' },
      {}
    )

    await usersApi.remove(12)
    expect(requestMock.delete).toHaveBeenCalledWith('/v1/users/12', {})
  })

  it('permissionsApi 按设计 10.3 调用 /v1/doris-clusters/{sourceId}/permissions 各端点', async () => {
    await permissionsApi.getOverview(5)
    expect(requestMock.get).toHaveBeenCalledWith('/v1/doris-clusters/5/permissions', {})

    await permissionsApi.createRole(5, { name: '销售分析', template: 'READONLY' })
    expect(requestMock.post).toHaveBeenCalledWith(
      '/v1/doris-clusters/5/permissions/roles',
      { name: '销售分析', template: 'READONLY' },
      {}
    )

    await permissionsApi.updateRole(5, 101, { name: '新角色名' })
    expect(requestMock.patch).toHaveBeenCalledWith(
      '/v1/doris-clusters/5/permissions/roles/101',
      { name: '新角色名' },
      {}
    )

    await permissionsApi.deleteRole(5, 101)
    expect(requestMock.delete).toHaveBeenCalledWith(
      '/v1/doris-clusters/5/permissions/roles/101',
      {}
    )

    await permissionsApi.previewRoleGrants(5, 101, {
      expectedRevision: 2,
      grants: [{ resourceType: 'TABLE', database: 'sales', tableName: 'orders', actions: ['QUERY'] }]
    })
    expect(requestMock.post).toHaveBeenCalledWith(
      '/v1/doris-clusters/5/permissions/roles/101/preview',
      {
        expectedRevision: 2,
        grants: [{ resourceType: 'TABLE', database: 'sales', tableName: 'orders', actions: ['QUERY'] }]
      },
      {}
    )

    await permissionsApi.applyRoleGrants(5, 101, { previewId: 'prev-1' })
    expect(requestMock.post).toHaveBeenCalledWith(
      '/v1/doris-clusters/5/permissions/roles/101/apply',
      { previewId: 'prev-1' },
      {}
    )

    await permissionsApi.assignMemberRoles(5, 'USER', 88, {
      roleIds: [101, 102],
      expectedRevision: 0
    })
    expect(requestMock.put).toHaveBeenCalledWith(
      '/v1/doris-clusters/5/permissions/members/USER/88',
      { roleIds: [101, 102], expectedRevision: 0 },
      {}
    )

    await permissionsApi.bindMemberAccount(5, 'USER', 88, {
      username: 'db_user',
      password: '',
      expectedRevision: 1
    })
    expect(requestMock.post).toHaveBeenCalledWith(
      '/v1/doris-clusters/5/permissions/members/USER/88/account',
      { username: 'db_user', password: '', expectedRevision: 1 },
      {}
    )

    await permissionsApi.removeMember(5, 'SERVICE', 7)
    expect(requestMock.delete).toHaveBeenCalledWith(
      '/v1/doris-clusters/5/permissions/members/SERVICE/7',
      {}
    )

    await permissionsApi.inspectMember(5, 'USER', 88)
    expect(requestMock.get).toHaveBeenCalledWith(
      '/v1/doris-clusters/5/permissions/members/USER/88/inspect',
      {}
    )

    await permissionsApi.saveManagementAccount(5, { username: 'grant_admin', password: 'secret' })
    expect(requestMock.put).toHaveBeenCalledWith(
      '/v1/doris-clusters/5/permissions/management',
      { username: 'grant_admin', password: 'secret' },
      {}
    )

    await permissionsApi.listResources(5, { database: 'sales' })
    expect(requestMock.get).toHaveBeenCalledWith('/v1/doris-clusters/5/permissions/resources', {
      params: { database: 'sales' }
    })

    await permissionsApi.listOperations(5)
    expect(requestMock.get).toHaveBeenCalledWith('/v1/doris-clusters/5/permissions/operations', {})
  })
})
