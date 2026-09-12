import { beforeEach, describe, expect, it, vi } from 'vitest'

// 守卫经动态 import 读取 auth store；用可控的假 store 替换。
const authState = vi.hoisted(() => ({
  enabled: false,
  currentUser: null,
  isAdmin: false,
  bootstrap: vi.fn(async () => {})
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => authState,
  sanitizeRedirectPath: (raw, fallback = '/chat') => {
    const value = String(raw || '').trim()
    if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')) return fallback
    return value
  }
}))

// 路由组件本身与守卫无关，替换为轻量桩，避免加载重型视图。
vi.mock('@/views/LoginView.vue', () => ({ default: { template: '<div>login</div>' } }))
vi.mock('@/views/intelligence/IntelligentQueryView.vue', () => ({ default: { template: '<div><router-view /></div>' } }))
vi.mock('@/views/intelligence/NL2SqlChatV2.vue', () => ({ default: { template: '<div>chat</div>' } }))
vi.mock('@/views/settings/SettingsLayout.vue', () => ({ default: { template: '<div><router-view /></div>' } }))
vi.mock('@/views/settings/SkillStudio.vue', () => ({ default: { template: '<div>skills</div>' } }))
vi.mock('@/views/settings/SkillDetailView.vue', () => ({ default: { template: '<div>skill</div>' } }))
vi.mock('@/views/intelligence/AgentStudio.vue', () => ({ default: { template: '<div>agents</div>' } }))
vi.mock('@/views/intelligence/AgentDetailView.vue', () => ({ default: { template: '<div>agent</div>' } }))
vi.mock('@/views/settings/DataAgentConfig.vue', () => ({ default: { template: '<div>models</div>' } }))
vi.mock('@/views/settings/WidgetAccessConfig.vue', () => ({ default: { template: '<div>widget</div>' } }))

import router from '../index'

// 同一 location 的重复 push 会被 vue-router 判定为冗余导航而不重跑守卫；
// 每次导航附带唯一 query 保证守卫真正执行。
let navSeq = 0
const navigate = async (location) => {
  const separator = String(location).includes('?') ? '&' : '?'
  await router.push(`${location}${separator}_t=${navSeq++}`).catch(() => {})
  await router.isReady()
  return router.currentRoute.value
}

describe('auth router guard', () => {
  beforeEach(() => {
    authState.enabled = false
    authState.currentUser = null
    authState.isAdmin = false
    authState.bootstrap.mockClear()
  })

  it('is transparent when auth is disabled', async () => {
    const route = await navigate('/chat')
    expect(route.path).toBe('/chat')
  })

  it('redirects unauthenticated users to /login with redirect', async () => {
    authState.enabled = true
    const route = await navigate('/chat?agent_id=agent_sales')
    expect(route.path).toBe('/login')
    expect(route.query.redirect).toContain('/chat')
    expect(route.query.redirect).toContain('agent_id=agent_sales')
  })

  it('lets authenticated users through', async () => {
    authState.enabled = true
    authState.currentUser = { display_name: 'alice', role: 'user' }
    const route = await navigate('/chat')
    expect(route.path).toBe('/chat')
  })

  it('blocks non-admin users from admin-only routes', async () => {
    authState.enabled = true
    authState.currentUser = { display_name: 'alice', role: 'user' }
    authState.isAdmin = false
    const route = await navigate('/settings/models')
    expect(route.path).toBe('/chat')
  })

  it('keeps agent_id when a non-admin user falls back from an admin page', async () => {
    authState.enabled = true
    authState.currentUser = { display_name: 'alice', role: 'user' }
    authState.isAdmin = false
    const route = await navigate('/settings/models?agent_id=agent_sales')
    expect(route.path).toBe('/chat')
    expect(route.query.agent_id).toBe('agent_sales')
  })

  it.each(['/settings/skills', '/settings/skills/demo', '/agents', '/agents/agent_1'])(
    'lets non-admin users access readable route %s',
    async (path) => {
      authState.enabled = true
      authState.currentUser = { display_name: 'alice', role: 'user' }
      authState.isAdmin = false
      const route = await navigate(path)
      expect(route.path).toBe(path)
    }
  )

  it('applies the canonical admin guard after a legacy route redirect', async () => {
    authState.enabled = true
    authState.currentUser = { display_name: 'alice', role: 'user' }
    authState.isAdmin = false
    const route = await navigate('/intelligent-query/models')
    expect(route.path).toBe('/chat')
  })

  it('lets admins into admin-only routes', async () => {
    authState.enabled = true
    authState.currentUser = { display_name: 'admin', role: 'admin' }
    authState.isAdmin = true
    const route = await navigate('/settings/models')
    expect(route.path).toBe('/settings/models')
  })

  it('bounces a logged-in user away from /login', async () => {
    authState.enabled = true
    authState.currentUser = { display_name: 'alice', role: 'user' }
    const route = await navigate('/login')
    expect(route.path).toBe('/chat')
  })

  it('returns a logged-in user to the full redirect including agent_id', async () => {
    authState.enabled = true
    authState.currentUser = { display_name: 'alice', role: 'user' }
    const route = await navigate('/login?redirect=%2Fsettings%2Fskills%3Fagent_id%3Dagent_sales')
    expect(route.path).toBe('/settings/skills')
    expect(route.query.agent_id).toBe('agent_sales')
  })
})
