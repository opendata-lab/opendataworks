import { describe, expect, it } from 'vitest'
import { demoAdapter } from '../mockServer'
import { buildChartRenderModel } from '../../views/intelligence/chartSpec'

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

  it('serves agent detail dependencies without treating capabilities as an agent id', async () => {
    await expect(request('get', '/api/v1/dataagent/agents/agent_default')).resolves.toMatchObject({
      code: 200,
      data: expect.objectContaining({
        agent_id: 'agent_default',
        name: '通用数据问答'
      })
    })

    await expect(request('get', '/api/v1/dataagent/agents/capabilities')).resolves.toMatchObject({
      code: 200,
      data: expect.objectContaining({
        tools: expect.arrayContaining(['Skill', 'Read']),
        skills: [
          expect.objectContaining({
            folder: 'dataagent-nl2sql'
          })
        ]
      })
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

  it('seeds demo topics covering running / error / suspended task statuses for the session list', async () => {
    const result = await request('get', '/api/v1/nl2sql/topics')
    const topics = result.data
    const byStatus = Object.fromEntries(topics.map((t) => [t.topic_id, t.current_task_status]))
    expect(byStatus['demo-topic-running']).toBe('running')
    expect(byStatus['demo-topic-error']).toBe('error')
    expect(byStatus['demo-topic-suspended']).toBe('suspended')
  })

  it('exposes the failed demo conversation with an error message for the chat error card', async () => {
    const result = await request('get', '/api/v1/nl2sql/topics/demo-topic-error/messages')
    const assistant = result.data.items.find((m) => m.sender_type === 'assistant')
    expect(assistant.status).toBe('error')
    expect(assistant.error?.message).toContain('SQL 执行失败')
  })

  it('ships a finished demo conversation with a renderable SQL table and chart', async () => {
    const result = await request('get', '/api/v1/nl2sql/topics/demo-topic-chart/messages')
    const assistant = result.data.items.find((m) => m.sender_type === 'assistant')
    expect(assistant.status).toBe('finished')

    const sqlBlock = assistant.blocks.find((b) => b.output?.kind === 'sql_execution')
    expect(sqlBlock.output.columns).toContain('success_rate')
    expect(sqlBlock.output.rows.length).toBeGreaterThan(0)

    const chartBlock = assistant.blocks.find((b) => b.output?.kind === 'chart_spec')
    // The real chart validator must accept the spec (no error) so it renders.
    const model = buildChartRenderModel(chartBlock.output)
    expect(model.errorText).toBeFalsy()
    expect(model.state).toBe('renderable')
    expect(model.spec.chart_type).toBe('line')
  })
})
