import { describe, expect, it, vi } from 'vitest'

const demoAdapter = vi.hoisted(() => vi.fn())
const axiosCreate = vi.hoisted(() => vi.fn((config) => ({
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
})))

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

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn()
  }
}))

describe('dataagentApi', () => {
  it('uses the demo adapter in demo mode', async () => {
    await import('../dataagent')

    expect(axiosCreate).toHaveBeenCalledWith(expect.objectContaining({
      baseURL: '/api',
      adapter: demoAdapter
    }))
  })
})
