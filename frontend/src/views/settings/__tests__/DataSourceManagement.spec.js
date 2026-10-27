import { builtinDefinitions, definition } from '@/utils/__tests__/datasourceFixtures'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  types: vi.fn(), list: vi.fn(), getById: vi.fn(), create: vi.fn(), update: vi.fn(), testConnection: vi.fn()
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
    api.types.mockResolvedValue(builtinDefinitions)
    api.list.mockResolvedValue([])
    api.create.mockResolvedValue({})
    api.update.mockResolvedValue({})
  })

  it('offers PostgreSQL and switches default ports while retaining custom ports', async () => {
    const wrapper = mountView()
    await flushPromises()
    wrapper.vm.openCreate()
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

  it('requires a database only when declared by the descriptor', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm
    vm.form.sourceType = 'POSTGRESQL'
    expect(vm.rules.databaseName[0].required).toBe(true)
    vm.form.sourceType = 'MYSQL'
    expect(vm.rules.databaseName).toEqual([])
  })

  it('renders a newly registered type and uses its fields and port without engine branches', async () => {
    api.types.mockResolvedValue([definition('TEST_DB', 15400)])
    const wrapper = mountView()
    await flushPromises()
    wrapper.vm.openCreate()
    expect(wrapper.vm.form.sourceType).toBe('TEST_DB')
    expect(wrapper.vm.form.fePort).toBe(15400)
    expect(wrapper.vm.rules.feHost[0].required).toBe(true)
    expect(wrapper.findAllComponents({ name: 'ElOption' }).some(o => o.attributes('value') === 'TEST_DB')).toBe(true)
    await wrapper.vm.handleSubmit()
    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ sourceType: 'TEST_DB', fePort: 15400 }))
  })

  it('saves and restores adapter-declared extra fields under connectionOptions', async () => {
    const descriptor = definition('TEST_DB', 15400)
    descriptor.connectionFields = [...descriptor.connectionFields,
      { key: 'serviceName', label: '服务名', control: 'text', required: true, defaultValue: 'sales' },
      { key: 'accessToken', label: '令牌', control: 'password', required: false, defaultValue: '' }]
    api.types.mockResolvedValue([descriptor])
    const wrapper = mountView()
    await flushPromises()
    wrapper.vm.openCreate()
    expect(wrapper.vm.rules.serviceName[0].required).toBe(true)
    wrapper.vm.form.accessToken = 'secret'
    await wrapper.vm.handleSubmit()
    expect(api.create.mock.calls[0][0].connectionOptions).toEqual({ serviceName: 'sales', accessToken: 'secret' })
    expect(api.create.mock.calls[0][0]).not.toHaveProperty('serviceName')
    api.getById.mockResolvedValue({ sourceType: 'TEST_DB', connectionOptions: { serviceName: 'other' } })
    await wrapper.vm.openEdit(9)
    expect(wrapper.vm.form.serviceName).toBe('other')
    expect(wrapper.vm.form.accessToken).toBe('')
  })

  it('reports descriptor failure and disables creating a guessed type', async () => {
    api.types.mockRejectedValue(new Error('描述不可用'))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.vm.typeError).toBe('描述不可用')
    expect(wrapper.vm.typeDefinitions).toEqual([])
  })

  it('creates a PostgreSQL datasource with its database', async () => {
    const vm = mountView().vm
    await flushPromises()
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
    await flushPromises()
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
