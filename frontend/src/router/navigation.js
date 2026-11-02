// 模块仅组织现有路由；不存在的资产功能不生成入口。
export const navigationModules = [
  { key: 'development', label: '数据开发', icon: 'DataLine', target: '/datastudio' },
  { key: 'governance', label: '数据治理', icon: 'Connection', target: '/lineage' },
  { key: 'query', label: '智能问数', icon: 'ChatDotRound', target: '/intelligent-query' },
  { key: 'management', label: '管理中心', icon: 'Setting', target: '/settings?tab=datasource' }
]

export const moduleMenus = {
  development: [
    { key: 'studio', label: '数据开发', icon: 'DataLine', target: '/datastudio' },
    { key: 'integration', label: '数据集成', icon: 'Link', target: '/integration', demo: false },
    { key: 'modeling', label: '数据建模', icon: 'Collection', target: '/domains' },
    { key: 'scheduling', label: '任务调度', icon: 'Share', target: '/workflows' }
  ],
  governance: [
    { key: 'map', label: '数据地图', icon: 'Connection', children: [
      { key: 'metadata', label: '元数据采集', icon: 'Download', target: '/metadata-sync', admin: true, demo: false },
      { key: 'lineage', label: '数据血缘', icon: 'Connection', target: '/lineage' }
    ] },
    { key: 'quality', label: '数据质量', icon: 'Warning', target: '/inspection', demo: false }
  ],
  management: [
    { key: 'datasource', label: '数据源', icon: 'Coin', target: '/settings?tab=datasource' },
    { key: 'users', label: '账号管理', icon: 'User', target: '/settings?tab=users', admin: true },
    { key: 'dolphin', label: '调度引擎', icon: 'Cpu', target: '/settings?tab=dolphin' },
    { key: 'minio', label: 'MinIO 管理', icon: 'FolderOpened', target: '/settings?tab=minio' },
    { key: 'agent', label: '智能助手', icon: 'MagicStick', target: '/settings?tab=agent' }
  ]
}

export function getModuleMenus(moduleKey, { isAdmin = false, demo = false } = {}) {
  return (moduleMenus[moduleKey] || []).flatMap(item => {
    if ((item.admin && !isAdmin) || (demo && (item.demo === false || moduleKey === 'management'))) return []
    if (!item.children) return [item]
    const children = item.children.filter(child => (!child.admin || isAdmin) && (!demo || child.demo !== false))
    return children.length ? [{ ...item, children }] : []
  })
}

export function resolveNavigation(route) {
  const path = route.path || ''
  let moduleKey = '', key = ''
  if (path === '/datastudio' || path === '/datastudio-new') { moduleKey = 'development'; key = 'studio' }
  else if (path === '/integration') { moduleKey = 'development'; key = 'integration' }
  else if (path === '/domains') { moduleKey = 'development'; key = 'modeling' }
  else if (/^\/(workflows|tasks|monitor)(\/|$)/.test(path)) { moduleKey = 'development'; key = 'scheduling' }
  else if (path === '/metadata-sync') { moduleKey = 'governance'; key = 'metadata' }
  else if (path === '/lineage') { moduleKey = 'governance'; key = 'lineage' }
  else if (path === '/inspection') { moduleKey = 'governance'; key = 'quality' }
  else if (/^\/(intelligent-query|nl2sql)(\/|$)/.test(path)) { moduleKey = 'query'; key = 'query' }
  else if (path === '/settings') {
    moduleKey = 'management'
    key = ['datasource', 'users', 'dolphin', 'minio', 'agent'].includes(route.query?.tab) ? route.query.tab : 'datasource'
  }
  const leaves = Object.values(moduleMenus).flatMap(items => items.flatMap(item => item.children || [item]))
  const title = key === 'query' ? '智能问数' : leaves.find(item => item.key === key)?.label
  return { moduleKey, key, title: path.startsWith('/workflows/') ? '工作流详情' : title || route.meta?.title || '控制台' }
}
