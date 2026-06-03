import { describe, expect, it, vi } from 'vitest'

vi.mock('@/demo/runtime', () => ({
  isDemoMode: true
}))

import { createNl2SqlApiClient } from '../nl2sql'

describe('demo nl2sql client integration', () => {
  it('returns mocked settings and agents through real axios adapter flow', async () => {
    const client = createNl2SqlApiClient()

    await expect(client.adminApi.getSettings()).resolves.toMatchObject({
      provider_id: 'demo-provider',
      providers: [
        expect.objectContaining({
          provider_id: 'demo-provider',
          models: ['demo-nl2sql']
        })
      ]
    })
    await expect(client.agentApi.listAgents()).resolves.toEqual(expect.arrayContaining([
      expect.objectContaining({
        agent_id: 'agent_default',
        name: '通用数据问答'
      })
    ]))
    await expect(client.agentApi.listAgents()).resolves.toHaveLength(1)
  })
})
