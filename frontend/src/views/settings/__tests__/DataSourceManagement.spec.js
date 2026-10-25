import { shallowMount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  list: vi.fn(), getById: vi.fn(), create: vi.fn(), update: vi.fn(), testConnection: vi.fn()
}))
const messages = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/api/doris', () => ({ dorisClusterApi: api }))
vi.mock('@/api/table', () => ({ tableApi: {} }))
vi.mock('element-plus', async importOriginal => ({
  ...(await importOriginal()), ElMessage: messages, ElMessageBox: { confirm: vi.fn() }
}))

import DataSourceManagement from '../DataSourceManagement.vue'

const mountView = () => shallowMount(DataSourceManagement, {
  global: {
    stubs: {
      'el-card': { template: '<div><slot name="header" /><slot /></div>' },
      'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
      'el-form': { template: '<form><slot /></form>', methods: { validate: () => Promise.resolve() } },
      'el-form-item': { template: '<div><slot /></div>' },
      'el-select': { template: '<div><slot /></div>' }
    }
  }
})

describe('DataSourceManagement PostgreSQL', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.list.mockResolvedValue([])
    api.create.mockResolvedValue({})
    api.update.mockResolvedValue({})
  })

  it('offers PostgreSQL and switches default ports while retaining custom ports', () => {
    const wrapper = mountView()
    expect(wrapper.findAllComponents({ name: 'ElOption' }).some(o => o.attributes('value') === 'POSTGRESQL')).toBe(true)
    const vm = wrapper.vm
    vm.handleTypeChange('POSTGRESQL')
    expect(vm.form.fePort).toBe(5432)
    vm.handleTypeChange('MYSQL')
    expect(vm.form.fePort).toBe(3306)
    vm.form.fePort = 15432
    vm.handleTypeChange('POSTGRESQL')
    expect(vm.form.fePort).toBe(15432)
  })

  it('requires a database only for PostgreSQL', () => {
    const vm = mountView().vm
    const callback = vi.fn()
    vm.form.sourceType = 'POSTGRESQL'
    vm.rules.databaseName[0].validator(null, '  ', callback)
    expect(callback).toHaveBeenLastCalledWith(expect.any(Error))
    vm.rules.databaseName[0].validator(null, 'business', callback)
    expect(callback).toHaveBeenLastCalledWith(undefined)
    vm.form.sourceType = 'MYSQL'
    vm.rules.databaseName[0].validator(null, '', callback)
    expect(callback).toHaveBeenLastCalledWith(undefined)
  })

  it('creates a PostgreSQL datasource with its database', async () => {
    const vm = mountView().vm
    Object.assign(vm.form, {
      clusterName: 'pgsql', sourceType: 'POSTGRESQL', databaseName: ' business ',
      feHost: '127.0.0.1', fePort: 5432, username: 'reader', password: 'secret'
    })
    await vm.handleSubmit()
    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({
      sourceType: 'POSTGRESQL', databaseName: 'business', fePort: 5432, password: 'secret'
    }))
  })

  it('restores the database when editing and omits a blank password', async () => {
    api.getById.mockResolvedValue({
      id: 9, clusterName: 'pgsql', sourceType: 'POSTGRESQL', databaseName: 'business',
      feHost: '127.0.0.1', fePort: 5432, username: 'reader', isDefault: 0
    })
    const vm = mountView().vm
    await vm.openEdit(9)
    expect(vm.form.databaseName).toBe('business')
    await vm.handleSubmit()
    const payload = api.update.mock.calls[0][1]
    expect(payload.databaseName).toBe('business')
    expect(payload).not.toHaveProperty('password')
    vm.handleTypeChange('DORIS')
    expect(vm.form.fePort).toBe(9030)
    vm.openCreate()
    expect(vm.form.databaseName).toBe('')
    await flushPromises()
  })

  it('reports a failed connection instead of showing success for false', async () => {
    api.testConnection.mockResolvedValue(false)
    await mountView().vm.handleTestConnection(9)
    expect(messages.error).toHaveBeenCalled()
    expect(messages.success).not.toHaveBeenCalled()
  })
})
