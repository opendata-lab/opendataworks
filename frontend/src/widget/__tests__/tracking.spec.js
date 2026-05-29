import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createWidgetTracker } from '../tracking'

// Flush microtask queue without relying on setTimeout (avoids fake-timer interference)
const flushMicrotasks = () => new Promise((r) => queueMicrotask(r))

describe('createWidgetTracker', () => {
  let fetchCalls
  let originalFetch

  beforeEach(() => {
    fetchCalls = []
    originalFetch = globalThis.fetch
    globalThis.fetch = vi.fn((_url, opts) => {
      fetchCalls.push(JSON.parse(opts.body))
      return Promise.resolve({ ok: true })
    })
    vi.useFakeTimers()
  })

  afterEach(async () => {
    vi.useRealTimers()
    globalThis.fetch = originalFetch
  })

  it('debounces and flushes queued events after delay', async () => {
    const tracker = createWidgetTracker({ apiBaseUrl: '', headers: {} })

    tracker.track('widget_open')
    tracker.track('widget_close')

    expect(fetchCalls).toHaveLength(0)

    await vi.runAllTimersAsync()

    expect(fetchCalls).toHaveLength(1)
    expect(fetchCalls[0].events).toHaveLength(2)
    expect(fetchCalls[0].events[0].event_type).toBe('widget_open')
    expect(fetchCalls[0].events[1].event_type).toBe('widget_close')

    tracker.destroy()
  })

  it('includes payload when provided', async () => {
    const tracker = createWidgetTracker({ apiBaseUrl: '', headers: {} })

    tracker.track('message_send', { input_source: 'typed', length: 42 })

    await vi.runAllTimersAsync()

    expect(fetchCalls[0].events[0].payload).toEqual({ input_source: 'typed', length: 42 })

    tracker.destroy()
  })

  it('flush on destroy sends remaining events with keepalive', async () => {
    const tracker = createWidgetTracker({ apiBaseUrl: '', headers: {} })

    tracker.track('conversation_new')

    vi.clearAllTimers()
    tracker.destroy()
    await flushMicrotasks()

    expect(fetchCalls).toHaveLength(1)
    const fetchOpts = globalThis.fetch.mock.calls[0][1]
    expect(fetchOpts.keepalive).toBe(true)
  })

  it('silently ignores fetch errors', async () => {
    globalThis.fetch = vi.fn(() => Promise.reject(new Error('network error')))
    const tracker = createWidgetTracker({ apiBaseUrl: '', headers: {} })

    tracker.track('widget_open')

    await vi.runAllTimersAsync()

    // No exception propagated — test completes without throwing
    expect(true).toBe(true)

    tracker.destroy()
  })

  it('does not track after destroy', async () => {
    const tracker = createWidgetTracker({ apiBaseUrl: '', headers: {} })
    tracker.destroy()
    await flushMicrotasks()
    fetchCalls.length = 0

    tracker.track('widget_open')
    await vi.runAllTimersAsync()

    expect(fetchCalls).toHaveLength(0)
  })

  it('sends correct headers from config', async () => {
    const headers = { 'X-ODW-Client': 'widget', 'X-ODW-Website-Id': 'site1' }
    const tracker = createWidgetTracker({ apiBaseUrl: 'https://api.example.com', headers })

    tracker.track('widget_open')
    await vi.runAllTimersAsync()

    const fetchOpts = globalThis.fetch.mock.calls[0][1]
    expect(fetchOpts.headers['X-ODW-Client']).toBe('widget')
    expect(fetchOpts.headers['X-ODW-Website-Id']).toBe('site1')
    expect(globalThis.fetch.mock.calls[0][0]).toBe('https://api.example.com/api/v1/nl2sql/widget-events')

    tracker.destroy()
  })
})
