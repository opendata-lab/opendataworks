import { createRouter, createWebHistory } from 'vue-router'
import { isDemoMode } from '@/demo/runtime'
import { lazyView } from '@/router/lazyView'
import { useAuthStore } from '@/stores/auth'

const demoHomePath = '/dashboard'
const defaultHomePath = isDemoMode ? demoHomePath : '/dashboard'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: lazyView(() => import('@/views/LoginView.vue')),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: lazyView(() => import('@/views/Layout.vue')),
    redirect: defaultHomePath,
    children: [
      {
        path: '/dashboard',
        name: 'Dashboard',
        component: lazyView(() => import('@/views/dashboard/Dashboard.vue')),
        meta: { title: '控制台' }
      },
      {
        path: '/datastudio',
        name: 'DataStudio',
        component: lazyView(() => import('@/views/datastudio/DataStudioNew.vue')),
        meta: { title: '数据开发' }
      },
      {
        path: '/datastudio-new',
        redirect: (to) => ({ path: '/datastudio', query: to.query, hash: to.hash })
      },
      {
        path: '/domains',
        name: 'Domains',
        component: lazyView(() => import('@/views/domains/DomainManagement.vue')),
        meta: { title: '数据建模' }
      },
      {
        path: '/workflows',
        name: 'Workflows',
        component: lazyView(() => import('@/views/workflows/WorkflowManagement.vue')),
        meta: { title: '工作流' }
      },
      {
        path: '/workflows/:id(\\d+)',
        name: 'WorkflowDetail',
        component: lazyView(() => import('@/views/workflows/WorkflowDetail.vue')),
        meta: { title: '工作流详情' }
      },
      {
        path: '/tasks',
        redirect: { path: '/workflows', query: { tab: 'tasks' } }
      },

      {
        path: '/lineage',
        name: 'Lineage',
        component: lazyView(() => import('@/views/lineage/LineageView.vue')),
        meta: { title: '数据血缘' }
      },
      {
        path: '/monitor',
        redirect: { path: '/workflows', query: { tab: 'monitor' } }
      },
      {
        path: '/inspection',
        name: 'Inspection',
        component: lazyView(() => import('@/views/inspection/InspectionView.vue')),
        meta: { title: '数据质量' }
      },
      {
        path: '/integration',
        name: 'Integration',
        component: lazyView(() => import('@/views/integration/DataIntegration.vue')),
        meta: { title: '数据集成' }
      },
      {
        path: '/intelligent-query',
        name: 'IntelligentQuery',
        component: lazyView(() => import('@/views/IntelligentQueryRemoteEmbed.vue')),
        meta: { title: '智能问数' }
      },
      {
        path: '/intelligent-query/skills/:folder',
        redirect: (to) => ({ path: '/intelligent-query', query: to.query, hash: to.hash })
      },
      {
        path: '/intelligent-query/agents/:agentId',
        redirect: (to) => ({ path: '/intelligent-query', query: to.query, hash: to.hash })
      },
      {
        path: '/nl2sql',
        redirect: (to) => ({ path: '/intelligent-query', query: to.query, hash: to.hash })
      },
      {
        path: '/settings',
        name: 'Settings',
        component: lazyView(() => import('@/views/settings/ConfigurationManagement.vue')),
        meta: { title: '设置' }
      },
      {
        path: '/settings/skills',
        redirect: (to) => ({ path: '/intelligent-query', query: to.query, hash: to.hash })
      },
      {
        path: '/settings/skills/:folder',
        redirect: (to) => ({ path: '/intelligent-query', query: to.query, hash: to.hash })
      },
      {
        path: '/playground',
        redirect: '/playground/tabs'
      },
      {
        path: '/playground/tabs',
        name: 'TabPlayground',
        component: lazyView(() => import('@/views/playground/TabPlayground.vue')),
        meta: { title: 'Tab Playground' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

if (!isDemoMode) {
  router.beforeEach(async (to) => {
    if (to.meta.public) {
      return true
    }
    const authStore = useAuthStore()
    if (!authStore.initialized) {
      await authStore.fetchMe()
    }
    if (!authStore.isLoggedIn) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    return true
  })
}

if (isDemoMode) {
  const allowedPrefixes = [
    '/dashboard',
    '/domains',
    '/workflows',
    '/tasks',
    '/monitor',
    '/lineage',
    '/datastudio',
    '/datastudio-new',
    '/intelligent-query',
    '/nl2sql'
  ]

  router.beforeEach((to) => {
    if (to.path === '/') {
      return true
    }
    if (allowedPrefixes.some((prefix) => to.path.startsWith(prefix))) {
      return true
    }
    return { path: demoHomePath }
  })
}

export default router
