import { beforeEach, describe, expect, it, vi } from 'vitest'

const clients = vi.hoisted(() => [])
const demoAdapter = vi.hoisted(() => vi.fn())
const axiosCreate = vi.hoisted(() => vi.fn((config) => {
  const client = {
    config,
    interceptors: {
      response: {
        use: vi.fn()
      }
    },
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
  clients.push(client)
  return client
}))

vi.mock('axios', () => ({
  default: {
    create: axiosCreate
  }
}))

vi.mock('@/demo/runtime', () => ({
  isDemoMode: true
}))

vi.mock('@/demo/mockServer', () => ({
  demoAdapter
}))

import { createNl2SqlApiClient } from '../nl2sql'

describe('createNl2SqlApiClient', () => {
  beforeEach(() => {
    clients.length = 0
    axiosCreate.mockClear()
  })

  it('applies default headers to runtime and admin clients', () => {
    createNl2SqlApiClient({
      baseURL: 'https://odw.example.com',
      defaultHeaders: {
        'X-ODW-Client': 'widget',
        'X-ODW-Website-Id': 'demo',
        'X-ODW-User-Id': 'user-123'
      }
    })

    expect(axiosCreate).toHaveBeenCalledTimes(3)
    expect(clients[0].config.headers).toMatchObject({
      'X-ODW-Client': 'widget',
      'X-ODW-Website-Id': 'demo',
      'X-ODW-User-Id': 'user-123'
    })
    expect(clients[1].config.headers).toMatchObject({
      'X-ODW-Client': 'widget',
      'X-ODW-Website-Id': 'demo',
      'X-ODW-User-Id': 'user-123'
    })
    expect(clients[2].config.headers).toMatchObject({
      'X-ODW-Client': 'widget',
      'X-ODW-Website-Id': 'demo',
      'X-ODW-User-Id': 'user-123'
    })
  })

  it('uses the demo adapter for every nl2sql axios client in demo mode', () => {
    createNl2SqlApiClient()

    expect(clients).toHaveLength(3)
    expect(clients.map((client) => client.config.adapter)).toEqual([
      demoAdapter,
      demoAdapter,
      demoAdapter
    ])
  })

  it('builds workspace file urls and routes uploads/listing to the runtime client', () => {
    const client = createNl2SqlApiClient({ baseURL: 'https://odw.example.com' })

    expect(client.topicApi.fileUrl('t1', 'uploads/a b.csv', { download: true })).toBe(
      'https://odw.example.com/api/v1/nl2sql/topics/t1/files/uploads/a%20b.csv?download=1'
    )
    expect(client.topicApi.fileUrl('t1', 'report.html')).toBe(
      'https://odw.example.com/api/v1/nl2sql/topics/t1/files/report.html'
    )

    client.topicApi.listFiles('t1')
    expect(clients[0].get).toHaveBeenCalledWith('/topics/t1/files')

    client.topicApi.uploadFile('t1', new Blob(['x']))
    expect(clients[0].post).toHaveBeenCalledWith(
      '/topics/t1/files',
      expect.any(FormData),
      expect.objectContaining({ headers: { 'Content-Type': 'multipart/form-data' } })
    )
  })

  it('exposes safe runtime config through the unified runtime API', () => {
    const client = createNl2SqlApiClient()

    client.runtimeApi.getConfig()

    expect(clients[0].get).toHaveBeenCalledWith('/runtime-config')
  })

  it('updates assistant message feedback through the topic API', () => {
    const client = createNl2SqlApiClient()

    client.topicApi.updateMessageFeedback('topic-1', 'message-1', 'like')

    expect(clients[0].put).toHaveBeenCalledWith(
      '/topics/topic-1/messages/message-1/feedback',
      { feedback: 'like' }
    )
  })

  it('requests follow-up suggestions through the additive topic API', () => {
    const client = createNl2SqlApiClient()

    client.topicApi.generateFollowupSuggestions('topic-1', 'message-1')

    expect(clients[0].post).toHaveBeenCalledWith(
      '/topics/topic-1/messages/message-1/followup-suggestions',
      {}
    )
  })
})
