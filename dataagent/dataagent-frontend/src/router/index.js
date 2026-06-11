import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/intelligent-query'
  },
  {
    path: '/intelligent-query',
    name: 'IntelligentQuery',
    component: () => import('@/views/intelligence/IntelligentQueryView.vue'),
    meta: { title: '智能问数' }
  },
  {
    path: '/intelligent-query/skills/:folder',
    name: 'IntelligentQuerySkillDetail',
    component: () => import('@/views/intelligence/IntelligentQueryView.vue'),
    meta: { title: 'Skill 详情' }
  },
  {
    path: '/intelligent-query/agents/:agentId',
    name: 'IntelligentQueryAgentDetail',
    component: () => import('@/views/intelligence/IntelligentQueryView.vue'),
    meta: { title: '智能体详情' }
  },
  {
    path: '/nl2sql',
    redirect: (to) => ({ path: '/intelligent-query', query: to.query, hash: to.hash })
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
