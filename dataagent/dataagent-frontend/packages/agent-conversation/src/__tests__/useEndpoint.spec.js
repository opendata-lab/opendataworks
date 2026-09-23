import { describe, it, expect, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import { useEndpoint } from '../core/useEndpoint.js'

const A = '/api/v1/workspaces/w-1/sessions/s-1/agent-conversation'
const B = '/api/v1/workspaces/w-1/sessions/s-2/agent-conversation'

const setup = (initial = '') => {
  const endpoint = ref(initial)
  const endpointResolver = ref(null)
  const transportFactory = ref(null)
  const resets = []
  const api = useEndpoint({
    endpoint,
    endpointResolver,
    transportFactory,
    onReset: (reason) => resets.push(reason)
  })
  return { endpoint, endpointResolver, transportFactory, resets, api }
}

describe('an existing conversation', () => {
  it('is ready immediately and builds a transport', () => {
    const { api } = setup(A)
    expect(api.ready.value).toBe(true)
    expect(api.resolved.value).toBe(A)
    expect(api.transport.value).toBeTruthy()
  })

  it('does not consult the resolver', async () => {
    const { api, endpointResolver } = setup(A)
    endpointResolver.value = vi.fn()

    await api.ensure()

    expect(endpointResolver.value).not.toHaveBeenCalled()
  })
})

describe('a conversation that does not exist yet', () => {
  it('stays idle on mount and asks nobody', () => {
    const { api, endpointResolver } = setup('')
    endpointResolver.value = vi.fn()

    expect(api.ready.value).toBe(false)
    expect(api.transport.value).toBeNull()
    expect(endpointResolver.value).not.toHaveBeenCalled()
  })

  it('resolves once on first send and reuses the result after', async () => {
    const { api, endpointResolver } = setup('')
    endpointResolver.value = vi.fn(async () => A)

    expect(await api.ensure()).toBe(A)
    expect(await api.ensure()).toBe(A)

    expect(endpointResolver.value).toHaveBeenCalledTimes(1)
    expect(api.ready.value).toBe(true)
  })

  it('passes the real triggering operation to the resolver', async () => {
    const { api, endpointResolver } = setup('')
    endpointResolver.value = vi.fn(async () => A)
    const context = {
      reason: 'send',
      content: '最近 30 天工作流发布次数趋势',
      settings: { provider_id: 'openai', model: 'gpt-4o' }
    }

    await api.ensure(context)

    expect(endpointResolver.value).toHaveBeenCalledWith(context)
  })
})

describe('switching conversations', () => {
  it('resets and rebuilds when the endpoint changes', async () => {
    const { api, endpoint, resets } = setup(A)
    const before = api.transport.value

    endpoint.value = B
    await nextTick()

    expect(api.resolved.value).toBe(B)
    expect(api.transport.value).not.toBe(before)
    expect(resets).toEqual(['switch'])
  })

  it('bumps the generation so in-flight work from the old conversation can be dropped', async () => {
    const { api, endpoint } = setup(A)
    const before = api.generation.value

    endpoint.value = B
    await nextTick()

    expect(api.generation.value).toBeGreaterThan(before)
  })

  it('ignores a write of the same address', async () => {
    const { api, endpoint, resets } = setup(A)

    endpoint.value = A
    await nextTick()

    expect(resets).toEqual([])
    expect(api.generation.value).toBe(0)
  })

  it('does not require the host to call reload — that is the race the plan hit', async () => {
    // The host only assigns `endpoint`. Nothing here depends on the host
    // sequencing a reload() call against its own state updates, which is where
    // the React version raced and reloaded the conversation it had just left.
    const { api, endpoint, resets } = setup(A)

    endpoint.value = B
    await nextTick()

    expect(resets).toEqual(['switch'])
    expect(api.resolved.value).toBe(B)
  })
})

describe('reload', () => {
  it('re-runs the same conversation without clearing the address', () => {
    const { api, resets } = setup(A)

    api.reload()

    expect(api.resolved.value).toBe(A)
    expect(resets).toEqual(['reload'])
  })
})

describe('custom transports', () => {
  it('are built from the endpoint, keeping it the single conversation key', async () => {
    const { api, endpoint, transportFactory } = setup(A)
    const made = []
    transportFactory.value = vi.fn((address) => {
      const t = { address }
      made.push(t)
      return t
    })

    endpoint.value = B
    await nextTick()

    expect(transportFactory.value).toHaveBeenCalledWith(B)
    expect(api.transport.value).toBe(made[0])
  })
})
