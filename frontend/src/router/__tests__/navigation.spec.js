import { describe, it, expect } from 'vitest'
import { navigationModules, getModuleMenus, resolveNavigation } from '../navigation'
describe('门户导航', () => {
  it('保留顶部智能问数独立入口，没有空侧栏', () => {
    expect(navigationModules.map(m => m.label)).toEqual(['数据开发', '数据治理', '智能问数', '管理中心'])
    expect(getModuleMenus('query')).toEqual([])
    expect(resolveNavigation({ path: '/intelligent-query' }).moduleKey).toBe('query')
  })
  it('工作流详情仍属于任务调度，管理子页由 query 高亮', () => {
    expect(resolveNavigation({ path: '/workflows/42' })).toMatchObject({ moduleKey: 'development', key: 'scheduling' })
    expect(resolveNavigation({ path: '/settings', query: { tab: 'minio' } })).toMatchObject({ moduleKey: 'management', key: 'minio' })
  })
  it('采集只对管理员显示，不显示未实现功能', () => {
    expect(getModuleMenus('governance')[0].children.map(n => n.key)).toEqual(['lineage'])
    expect(getModuleMenus('governance', { isAdmin: true })[0].children.map(n => n.key)).toEqual(['metadata', 'lineage'])
  })
  it('管理中心左侧账号管理入口仅管理员可见', () => {
    expect(getModuleMenus('management', { isAdmin: false }).map(n => n.key)).toEqual(['datasource', 'dolphin', 'minio', 'agent'])
    expect(getModuleMenus('management', { isAdmin: true }).map(n => n.key)).toEqual(['datasource', 'users', 'dolphin', 'minio', 'agent'])
    expect(resolveNavigation({ path: '/settings', query: { tab: 'users' } })).toMatchObject({
      moduleKey: 'management',
      key: 'users',
      title: '账号管理'
    })
  })
  it('演示模式不显示管理和采集', () => {
    expect(getModuleMenus('management', { demo: true })).toEqual([])
    expect(getModuleMenus('governance', { demo: true, isAdmin: true })[0].children.map(n => n.key)).toEqual(['lineage'])
  })
})
