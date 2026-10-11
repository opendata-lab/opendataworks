import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { clusterId: '3', database: 'db', tableId: '20' } }),
  useRouter: () => ({ replace: vi.fn() }),
}))
vi.mock('@/api/table', () => ({ tableApi: { getById: vi.fn() } }))
import { useTabRouting } from '../composables/useTabRouting'

function setup(suppression, openTableTab) {
  const suppressRouteSync = ref(suppression)
  return { suppressRouteSync, api: useTabRouting({
    suppressRouteSync, tabStates: {}, openTabs: ref([]), activeTab: ref(''),
    activeSource: ref(''), activeSchema: {}, tableStore: { 3: { db: [{ id: 20 }] } },
    loadSchemas: vi.fn(), loadTables: vi.fn(), openTableTab,
  }) }
}

describe('nested route suppression', () => {
  it('preserves the initialization guard after opening a route target', async () => {
    const { suppressRouteSync, api } = setup(true, vi.fn())
    await api.syncFromRoute()
    expect(suppressRouteSync.value).toBe(true)
  })
  it('restores normal route synchronization even when opening fails', async () => {
    const { suppressRouteSync, api } = setup(false, vi.fn().mockRejectedValue(new Error('load failed')))
    await expect(api.syncFromRoute()).rejects.toThrow('load failed')
    expect(suppressRouteSync.value).toBe(false)
  })
})
