import { describe, expect, it } from 'vitest'
import { demoAdapter } from '../mockServer'

const request = async (method, url, options = {}) => {
  const response = await demoAdapter({
    method,
    url,
    baseURL: options.baseURL ?? '',
    params: options.params || {},
    data: options.data
  })
  return response.data
}

describe('demoAdapter intelligent query admin endpoints', () => {
  it('keeps the agent list focused on the default demo agent', async () => {
    await expect(request('get', '/api/v1/dataagent/agents')).resolves.toMatchObject({
      code: 200,
      data: [
        expect.objectContaining({
          agent_id: 'agent_default',
          name: '通用数据问答',
          is_default: true
        })
      ]
    })
  })

  it('returns complete Skill document detail data for the detail editor', async () => {
    await expect(request('get', '/api/v1/dataagent/skills/documents/skill-doc-1')).resolves.toMatchObject({
      code: 200,
      data: expect.objectContaining({
        id: 'skill-doc-1',
        content_type: 'markdown',
        current_content: expect.stringContaining('dataagent-nl2sql'),
        versions: [
          expect.objectContaining({
            is_current: true,
            version_no: 1
          })
        ]
      })
    })
  })

  it('returns a visible demo model provider for model management', async () => {
    await expect(request('get', '/api/v1/nl2sql-admin/settings')).resolves.toMatchObject({
      code: 200,
      data: expect.objectContaining({
        provider_id: 'demo-provider',
        model: 'demo-nl2sql',
        providers: [
          expect.objectContaining({
            provider_id: 'demo-provider',
            display_name: 'Demo 模型',
            models: ['demo-nl2sql'],
            supported_models: ['demo-nl2sql']
          })
        ]
      })
    })
  })
})
