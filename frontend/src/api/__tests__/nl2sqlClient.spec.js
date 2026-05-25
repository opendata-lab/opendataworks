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
})
