// Live-transport regression test: drives the real streamSdkEvents implementation
// against a real HTTP server that emits SSE records slowly, pinning the contract
// that records are delivered incrementally (not buffered until the stream ends).
// Guards the chat/widget streaming UX against transport-level regressions, e.g.
// replacing the fetch reader with a buffering client.
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import http from 'node:http'
import { createNl2SqlApiClient } from '../nl2sql'

let server
let baseURL

beforeAll(async () => {
  server = http.createServer((req, res) => {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
    })
    let i = 0
    const timer = setInterval(() => {
      i += 1
      res.write(`data: {"seq_id":${i},"record_type":"stream","data":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"t${i}"}}}\n\n`)
      if (i >= 5) {
        clearInterval(timer)
        res.end()
      }
    }, 200)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  baseURL = `http://127.0.0.1:${server.address().port}`
})

afterAll(() => new Promise((resolve) => server.close(resolve)))

describe('streamSdkEvents live transport', () => {
  it('delivers records incrementally as they arrive', async () => {
    const api = createNl2SqlApiClient({ baseURL })
    const arrivals = []
    const start = Date.now()
    await api.taskApi.streamSdkEvents('t1', {
      onRecord: (record) => arrivals.push({ seq: record.seq_id, at: Date.now() - start }),
    })
    expect(arrivals.map((a) => a.seq)).toEqual([1, 2, 3, 4, 5])
    // First record must arrive well before the stream finishes (~1000ms total).
    expect(arrivals[0].at).toBeLessThan(600)
    // Records must be spread over time, not delivered in one burst at the end.
    expect(arrivals[4].at - arrivals[0].at).toBeGreaterThan(400)
  }, 10000)
})
