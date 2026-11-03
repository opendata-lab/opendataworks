import { shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routerReplace = vi.hoisted(() => vi.fn())
const routeState = vi.hoisted(() => ({
  path: '/settings',
  query: {}
}))

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal()),
  useRoute: () => routeState,
  useRouter: () => ({
    replace: routerReplace
  })
}))

import { useAuthStore } from '@/stores/auth'
import ConfigurationManagement from '../ConfigurationManagement.vue'

const stubs = {
  DataSourceManagement: { template: '<div>数据源管理内容</div>' },
  UserManagement: { template: '<div>用户管理内容</div>' },
  DolphinConfig: { template: '<div>Dolphin 配置内容</div>' },
  MinioConfigManagement: { template: '<div>MinIO 环境内容</div>' },
  AgentSettings: { template: '<div>智能助手设置内容</div>' }
}

const mountView = (query = {}, pinia = null) => {
  routeState.query = query
  return shallowMount(ConfigurationManagement, {
    global: {
      plugins: pinia ? [pinia] : [],
      stubs
    }
  })
}

describe('ConfigurationManagement', () => {
  beforeEach(() => {
    routerReplace.mockReset()
    routeState.query = {}
    setActivePinia(undefined)
  })

  it('renders datasource, dolphin, minio, and agent tabs under settings', () => {
    expect(mountView().text()).toContain('数据源管理内容')
    expect(mountView({ tab: 'dolphin' }).text()).toContain('Dolphin 配置内容')
    expect(mountView({ tab: 'minio' }).text()).toContain('MinIO 环境内容')
    expect(mountView({ tab: 'agent' }).text()).toContain('智能助手设置内容')
  })

  it('renders users tab for admin and falls back to datasource for non-admin', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const authStore = useAuthStore()
    authStore.initialized = true
    authStore.currentUser = { id: 1, username: 'admin', role: 'admin' }

    expect(mountView({ tab: 'users' }, pinia).text()).toContain('用户管理内容')

    authStore.currentUser = { id: 2, username: 'viewer', role: 'user' }
    expect(mountView({ tab: 'users' }, pinia).text()).toContain('数据源管理内容')
  })

  it('redirects legacy Skill and model query tabs to intelligent query', () => {
    mountView({ tab: 'skills' })
    expect(routerReplace).toHaveBeenCalledWith('/intelligent-query')

    routerReplace.mockReset()
    mountView({ tab: 'dataagent' })
    expect(routerReplace).toHaveBeenCalledWith('/intelligent-query')
  })
})
