import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

// Routing tests only exercise URL contracts. Keep lazy page imports lightweight
// so component transform time cannot consume the per-test timeout.
vi.mock('@/views/LoginView.vue', () => ({ default: { template: '<div>login</div>' } }))
vi.mock('@/views/intelligence/IntelligentQueryView.vue', () => ({ default: { template: '<div><router-view /></div>' } }))
vi.mock('@/views/intelligence/NL2SqlChatV2.vue', () => ({ default: { template: '<div>chat</div>' } }))
vi.mock('@/views/intelligence/AgentStudio.vue', () => ({ default: { template: '<div>agents</div>' } }))
vi.mock('@/views/intelligence/AgentDetailView.vue', () => ({ default: { template: '<div>agent</div>' } }))
vi.mock('@/views/settings/SettingsLayout.vue', () => ({ default: { template: '<div><router-view /></div>' } }))
vi.mock('@/views/settings/SkillStudio.vue', () => ({ default: { template: '<div>skills</div>' } }))
vi.mock('@/views/settings/SkillDetailView.vue', () => ({ default: { template: '<div>skill</div>' } }))
vi.mock('@/views/settings/McpConfig.vue', () => ({ default: { template: '<div>mcp</div>' } }))
vi.mock('@/views/settings/DataAgentConfig.vue', () => ({ default: { template: '<div>models</div>' } }))
vi.mock('@/views/settings/WidgetAccessConfig.vue', () => ({ default: { template: '<div>widget</div>' } }))
vi.mock('@/views/evaluation/EvaluationSetsView.vue', () => ({ default: { template: '<div>evaluations</div>' } }))
vi.mock('@/views/evaluation/EvaluationSetDetailView.vue', () => ({ default: { template: '<div>evaluation</div>' } }))
vi.mock('@/views/evaluation/EvaluationResultsView.vue', () => ({ default: { template: '<div>results</div>' } }))
vi.mock('@/views/evaluation/EvaluationRunDetailView.vue', () => ({ default: { template: '<div>result</div>' } }))

import { routes } from '../index'

const buildRouter = () => createRouter({
  history: createMemoryHistory(),
  routes
})

const resolveTo = async (location) => {
  const router = buildRouter()
  await router.push(location)
  await router.isReady()
  const current = router.currentRoute.value
  return {
    path: current.path,
    query: current.query,
    hash: current.hash,
    name: current.name,
    params: current.params
  }
}

describe('DataAgent page routing', () => {
  it('redirects the root path to the readable chat URL and preserves context', async () => {
    const route = await resolveTo('/?topic_id=topic-1#message-2')
    expect(route.path).toBe('/chat')
    expect(route.name).toBe('IntelligentQueryChat')
    expect(route.query).toEqual({ topic_id: 'topic-1' })
    expect(route.hash).toBe('#message-2')
  })

  it.each([
    ['/chat', 'IntelligentQueryChat'],
    ['/settings/skills', 'IntelligentQuerySkills'],
    ['/agents', 'IntelligentQueryAgents'],
    ['/settings/models', 'IntelligentQueryModels'],
    ['/settings/widget-access', 'IntelligentQueryWidget']
  ])('resolves canonical route %s', async (path, name) => {
    const route = await resolveTo(path)
    expect(route.path).toBe(path)
    expect(route.name).toBe(name)
  })

  it('keeps canonical business paths independent from the deployment base', () => {
    const router = createRouter({
      history: createMemoryHistory('/dataagent/'),
      routes
    })

    expect(router.resolve('/chat').href).toBe('/dataagent/chat')
    expect(router.resolve('/settings/skills/marketing-insights').href).toBe('/dataagent/settings/skills/marketing-insights')
  })

  it('migrates a legacy ?tab= link and drops only the tab param', async () => {
    const route = await resolveTo('/intelligent-query?tab=skills&source=bookmark#recent')
    expect(route.path).toBe('/settings/skills')
    expect(route.query.tab).toBeUndefined()
    expect(route.query).toEqual({ source: 'bookmark' })
    expect(route.hash).toBe('#recent')
  })

  it('migrates the bare legacy section path to chat', async () => {
    const route = await resolveTo('/intelligent-query?topic_id=topic-1')
    expect(route.path).toBe('/chat')
    expect(route.query).toEqual({ topic_id: 'topic-1' })
  })

  it.each([
    ['/intelligent-query/chat', '/chat', 'IntelligentQueryChat'],
    ['/intelligent-query/skills', '/settings/skills', 'IntelligentQuerySkills'],
    ['/intelligent-query/agents', '/agents', 'IntelligentQueryAgents'],
    ['/intelligent-query/models', '/settings/models', 'IntelligentQueryModels']
  ])('migrates legacy page %s to %s', async (legacyPath, canonicalPath, name) => {
    const route = await resolveTo(legacyPath)
    expect(route.path).toBe(canonicalPath)
    expect(route.name).toBe(name)
  })

  it('removes the legacy /nl2sql implementation term from the final URL', async () => {
    const route = await resolveTo('/nl2sql?tab=skills')
    expect(route.path).toBe('/settings/skills')
    expect(route.query.tab).toBeUndefined()
  })

  it('routes /nl2sql without a tab to chat while keeping other params', async () => {
    const route = await resolveTo('/nl2sql?topic_id=topic-1')
    expect(route.path).toBe('/chat')
    expect(route.query).toEqual({ topic_id: 'topic-1' })
  })

  it('resolves canonical detail routes', async () => {
    const route = await resolveTo('/settings/skills/marketing-insights')
    expect(route.name).toBe('IntelligentQuerySkillDetail')
    expect(route.path).toBe('/settings/skills/marketing-insights')
    expect(route.params.folder).toBe('marketing-insights')
  })

  it('migrates a legacy detail link while preserving its query and hash', async () => {
    const route = await resolveTo('/intelligent-query/agents/agent_1?mode=edit#prompt')
    expect(route.name).toBe('IntelligentQueryAgentDetail')
    expect(route.path).toBe('/agents/agent_1')
    expect(route.params.agentId).toBe('agent_1')
    expect(route.query).toEqual({ mode: 'edit' })
    expect(route.hash).toBe('#prompt')
  })

  it('maps the legacy widget page to the non-conflicting readable route', async () => {
    const route = await resolveTo('/intelligent-query/widget')
    expect(route.path).toBe('/settings/widget-access')
    expect(route.name).toBe('IntelligentQueryWidget')
  })

  it.each([
    ['/skills', '/settings/skills'],
    ['/skills/marketing-insights', '/settings/skills/marketing-insights'],
    ['/models', '/settings/models'],
    ['/widget-access', '/settings/widget-access']
  ])('keeps the old settings URL %s working via %s', async (legacyPath, canonicalPath) => {
    const route = await resolveTo(legacyPath)
    expect(route.path).toBe(canonicalPath)
  })

  it('falls back safely when an unknown legacy child route is requested', async () => {
    const route = await resolveTo('/intelligent-query/internal-name?source=old')
    expect(route.path).toBe('/chat')
    expect(route.query).toEqual({ source: 'old' })
  })
})
