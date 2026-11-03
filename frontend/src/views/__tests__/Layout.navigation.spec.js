import { it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ isAdmin: true, currentUser: { username: 'admin' } }) }))
vi.mock('@/demo/runtime', () => ({ isDemoMode: false }))
vi.mock('@/router/routeWarmup', () => ({ preloadRouteComponents: vi.fn(), scheduleRouteWarmup: vi.fn() }))
vi.mock('@/utils/dataagentWidgetLoader', () => ({
  loadDataAgentWidgetScript: () => Promise.resolve({ installWidget: () => ({ destroy() {}, on() {}, getPanelLayout: () => ({ side: null, reservedWidth: 0 }) }) }),
  loadQueryAgentId: () => Promise.resolve('test'), portalWidgetIdentity: () => ({})
}))
import Layout from '../Layout.vue'
it('顶部和侧栏真实菜单切换路由，问数不显示侧栏，管理 query 正确高亮', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: ['/dashboard', '/settings', '/datastudio', '/lineage', '/metadata-sync', '/intelligent-query', '/integration', '/domains', '/workflows'].map(path => ({ path, component: { template: '<div>content</div>' } })) })
  await router.push('/settings?tab=datasource')
  const wrapper = mount(Layout, { global: { plugins: [router], stubs: { AiAssistantTrigger: true } } })
  await wrapper.findAll('.menu > .el-menu-item').find(item => item.text().includes('数据开发')).trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/datastudio')
  expect(wrapper.find('.module-menu .el-menu-item.is-active').text()).toBe('数据开发')
  await wrapper.findAll('.menu > .el-menu-item').find(item => item.text().includes('智能问数')).trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/intelligent-query')
  expect(wrapper.find('.module-sidebar').exists()).toBe(false)
  await router.push('/settings?tab=minio')
  await flushPromises()
  expect(wrapper.find('.module-menu .el-menu-item.is-active').text()).toBe('MinIO 管理')
  await wrapper.findAll('.module-menu .el-menu-item').find(item => item.text().includes('调度引擎')).trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query.tab).toBe('dolphin')
  wrapper.unmount()
})
