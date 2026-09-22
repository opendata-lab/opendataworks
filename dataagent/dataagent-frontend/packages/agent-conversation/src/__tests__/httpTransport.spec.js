import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createHttpTransport } from '../transport/http.js'
import { ErrorCode, StreamInterrupted } from '../transport/errors.js'

const ENDPOINT = '/api/v1/workspaces/w-1/sessions/s-1/agent-conversation'

const jsonResponse = (body, init = {}) => new Response(JSON.stringify(body), {
  status: 200,
  headers: { 'Content-Type': 'application/json' },
  ...init
})

/** Build an SSE response body out of raw frames. */
const sseResponse = (frames) => new Response(
  new ReadableStream({
    start(controller) {
      const encoder = new TextEncoder()
      for (const frame of frames) controller.enqueue(encoder.encode(frame))
      controller.close()
    }
  }),
  { status: 200, headers: { 'Content-Type': 'text/event-stream' } }
)

let fetchMock

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const transport = (endpoint = ENDPOINT) =>
  createHttpTransport({ getEndpoint: () => endpoint })

describe('request shaping', () => {
  it('loads the conversation snapshot and normalises the run', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      messages: [{ id: 'm1', role: 'user', content: 'hi' }],
      run: { task_id: 't-1', status: 'running', detail: '处理中', metadata: { mode: 'model' } }
    }))

    const snapshot = await transport().loadConversation()

    expect(fetchMock.mock.calls[0][0]).toBe(ENDPOINT)
    expect(snapshot.messages).toHaveLength(1)
    expect(snapshot.run).toEqual({
      taskId: 't-1',
      status: 'running',
      detail: '处理中',
      metadata: { mode: 'model' }
    })
  })

  it('passes metadata through on send', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ task_id: 't-2', status: 'queued', detail: '' }))

    await transport().sendMessage({ content: '开始建模', metadata: { mode: 'model' } })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(`${ENDPOINT}/messages`)
    expect(JSON.parse(init.body)).toEqual({ content: '开始建模', metadata: { mode: 'model' } })
  })

  it('sends interactions with snake_case keys the BFF expects', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }))

    await transport().submitInteraction({
      taskId: 't-3', kind: 'permission', requestId: 'r-1', payload: { decision: 'allow' }
    })

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      task_id: 't-3', kind: 'permission', request_id: 'r-1', payload: { decision: 'allow' }
    })
  })

  it('builds file urls under the resolved endpoint', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ messages: [], run: null }))
    const t = transport()
    await t.loadConversation()

    expect(t.fileUrl('output/result.json')).toBe(`${ENDPOINT}/files/output/result.json`)
  })

  it('attaches custom headers and credentials when provided', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ messages: [], run: null }))
    const customT = createHttpTransport({
      getEndpoint: () => ENDPOINT,
      headers: () => ({ Authorization: 'Bearer test-token', 'X-Custom-Tenant': 'tenant-42' }),
      credentials: 'include'
    })

    await customT.loadConversation()

    const [, init] = fetchMock.mock.calls[0]
    expect(init.credentials).toBe('include')
    expect(init.headers.Authorization).toBe('Bearer test-token')
    expect(init.headers['X-Custom-Tenant']).toBe('tenant-42')
  })

  it('accepts endpoint directly as a string or static endpoint property', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ messages: [], run: null }))
    const t1 = createHttpTransport(ENDPOINT, {
      headers: { Authorization: 'Bearer string-signature' }
    })
    await t1.loadConversation()
    expect(fetchMock.mock.calls[0][0]).toBe(ENDPOINT)
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer string-signature')

    fetchMock.mockClear()
    fetchMock.mockResolvedValue(jsonResponse({ messages: [], run: null }))
    const t2 = createHttpTransport({
      endpoint: ENDPOINT,
      headers: { Authorization: 'Bearer obj-signature' }
    })
    await t2.loadConversation()
    expect(fetchMock.mock.calls[0][0]).toBe(ENDPOINT)
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer obj-signature')
  })
})

describe('error mapping', () => {
  it('surfaces the hint the BFF supplies', async () => {
    fetchMock.mockResolvedValue(jsonResponse(
      { message: 'DataAgent 拒绝了本站点', hint: '在管理端放行 website_id=ontofoundry' },
      { status: 403 }
    ))

    await expect(transport().loadConversation()).rejects.toMatchObject({
      code: ErrorCode.CONVERSATION_UNAVAILABLE,
      message: 'DataAgent 拒绝了本站点',
      hint: '在管理端放行 website_id=ontofoundry'
    })
  })

  it('reports a network failure as unreachable rather than a protocol fault', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    await expect(transport().loadConversation()).rejects.toMatchObject({
      code: ErrorCode.TRANSPORT_UNREACHABLE
    })
  })

  it('rejects a snapshot without messages as a protocol error', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ run: null }))

    await expect(transport().loadConversation()).rejects.toMatchObject({
      code: ErrorCode.PROTOCOL_ERROR
    })
  })
})

describe('event stream', () => {
  const drain = async (stream) => {
    const items = []
    for await (const item of stream) items.push(item)
    return items
  }

  it('parses agent-event frames and stops on done', async () => {
    fetchMock.mockResolvedValue(sseResponse([
      'event: agent-event\ndata: {"seq_id":1,"kind":"text"}\n\n',
      ': ping\n\n',
      'event: agent-event\ndata: {"seq_id":2,"kind":"text"}\n\n',
      'event: done\ndata: {"task_id":"t-1","status":"finished","detail":"","metadata":{"mode":"model"}}\n\n'
    ]))

    const items = await drain(transport().streamEvents({ afterId: 0 }))

    expect(items.map((i) => i.type)).toEqual(['event', 'event', 'terminal'])
    expect(items[0].seqId).toBe(1)
    expect(items[2].run).toMatchObject({ taskId: 't-1', status: 'finished', metadata: { mode: 'model' } })
  })

  it('handles CRLF line endings from servers that emit \\r\\n', async () => {
    fetchMock.mockResolvedValue(sseResponse([
      'event: agent-event\r\ndata: {"seq_id":1,"kind":"text"}\r\n\r\n',
      ': ping\r\n\r\n',
      'event: done\r\ndata: {"task_id":"t-1","status":"finished"}\r\n\r\n'
    ]))

    const items = await drain(transport().streamEvents({ afterId: 0 }))
    expect(items.map((i) => i.type)).toEqual(['event', 'terminal'])
    expect(items[0].seqId).toBe(1)
    expect(items[1].run.taskId).toBe('t-1')
  })

  it('treats EOF without a done frame as an interruption, not a finished run', async () => {
    // This is the distinction the whole named-frame protocol exists for: a
    // dropped connection looks exactly like a completed stream at the byte
    // level, and mistaking one for the other makes the host refresh business
    // data while the run is still going.
    fetchMock.mockResolvedValue(sseResponse([
      'event: agent-event\ndata: {"seq_id":1}\n\n'
    ]))

    await expect(drain(transport().streamEvents({ afterId: 0 }))).rejects.toBeInstanceOf(StreamInterrupted)
  })

  it('requests resumption from the supplied after_id', async () => {
    fetchMock.mockResolvedValue(sseResponse([
      'event: done\ndata: {"task_id":"t-1","status":"finished"}\n\n'
    ]))

    await drain(transport().streamEvents({ afterId: 42 }))

    expect(fetchMock.mock.calls[0][0]).toBe(`${ENDPOINT}/events?after_id=42`)
  })

  it('ignores keep-alive comments', async () => {
    fetchMock.mockResolvedValue(sseResponse([
      ': ping\n\n',
      ': ping\n\n',
      'event: done\ndata: {"task_id":"t-1","status":"finished"}\n\n'
    ]))

    const items = await drain(transport().streamEvents({ afterId: 0 }))
    expect(items).toHaveLength(1)
    expect(items[0].type).toBe('terminal')
  })

  it('reports a malformed data frame as a protocol error', async () => {
    fetchMock.mockResolvedValue(sseResponse(['event: agent-event\ndata: not-json\n\n']))

    await expect(drain(transport().streamEvents({ afterId: 0 }))).rejects.toMatchObject({
      code: ErrorCode.PROTOCOL_ERROR
    })
  })

  it('stays quiet when the caller aborts', async () => {
    const controller = new AbortController()
    fetchMock.mockImplementation(() => {
      controller.abort()
      return Promise.reject(new DOMException('Aborted', 'AbortError'))
    })

    const items = await drain(transport().streamEvents({ afterId: 0, signal: controller.signal }))
    expect(items).toEqual([])
  })
})

describe('endpoint resolution', () => {
  it('asks the resolver on every request so a lazily created session is picked up', async () => {
    const getEndpoint = vi.fn()
      .mockReturnValueOnce('')
      .mockReturnValue(ENDPOINT)
    const t = createHttpTransport({ getEndpoint })

    await expect(t.loadConversation()).rejects.toMatchObject({
      code: ErrorCode.CONVERSATION_UNAVAILABLE
    })

    fetchMock.mockResolvedValue(jsonResponse({ messages: [], run: null }))
    await expect(t.loadConversation()).resolves.toBeTruthy()
    expect(getEndpoint).toHaveBeenCalledTimes(2)
  })
})
