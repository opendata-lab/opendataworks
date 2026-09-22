// A minimal implementation of the host BFF protocol, for trying the element
// without a DataAgent behind it. It is a reference for implementers and the
// fixture the manual smoke test runs against — not something to deploy.
//
//   node examples/mock-bff/server.mjs
//   open http://127.0.0.1:8787/
//
// See ../../docs/bff-protocol.md for the contract this satisfies.

import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const PORT = Number(process.env.PORT || 8787)
const BASE = '/api/conversation'

let seq = 0
const messages = []
let run = null

const json = (res, body, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(body))
}

const readBody = async (req) => {
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  if (!chunks.length) return {}
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    return {}
  }
}

const say = (role, content) => {
  messages.push({ id: `m-${messages.length + 1}`, role, content, createdAt: new Date().toISOString() })
}

/**
 * Stream a scripted run.
 *
 * The shape matters more than the content: named agent-event frames, a
 * keep-alive, and a terminal done frame. A client that never receives `done`
 * treats the stream as interrupted and reconnects, which is the behaviour this
 * lets you exercise — kill the process mid-run to see it.
 */
const stream = (res) => {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no'
  })

  const frames = [
    { delay: 150, body: `event: agent-event\ndata: ${JSON.stringify({ seq_id: ++seq, kind: 'thinking', text: '正在理解需求…' })}\n\n` },
    { delay: 400, body: ': ping\n\n' },
    { delay: 700, body: `event: agent-event\ndata: ${JSON.stringify({ seq_id: ++seq, kind: 'text', text: '已经分析完材料。' })}\n\n` }
  ]

  for (const frame of frames) setTimeout(() => res.write(frame.body), frame.delay)

  setTimeout(() => {
    say('assistant', '这是 mock BFF 的回答。真实场景下这段来自 DataAgent。')
    run = { task_id: run?.task_id || 't-mock', status: 'finished', detail: '处理完成', metadata: run?.metadata }
    // done goes out only after the state a host would reload is committed —
    // otherwise the client refreshes before the write lands.
    res.write(`event: done\ndata: ${JSON.stringify(run)}\n\n`)
    res.end()
  }, 1100)
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`)
  const path = url.pathname

  if (path === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    res.end(readFileSync(join(HERE, 'index.html'), 'utf8'))
    return
  }
  if (path === '/sdk.js') {
    res.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8' })
    res.end(readFileSync(join(HERE, '..', '..', 'dist', 'index.js'), 'utf8'))
    return
  }

  if (path === BASE && req.method === 'GET') return json(res, { messages, run })

  if (path === `${BASE}/messages` && req.method === 'POST') {
    const body = await readBody(req)
    say('user', String(body.content || ''))
    run = { task_id: `t-${Date.now()}`, status: 'queued', detail: '等待调度', metadata: body.metadata }
    return json(res, run)
  }

  if (path === `${BASE}/events` && req.method === 'GET') return stream(res)

  if (path === `${BASE}/cancel` && req.method === 'POST') {
    run = { ...(run || {}), status: 'cancelled', detail: '已取消' }
    return json(res, run)
  }

  if (path === `${BASE}/interactions` && req.method === 'POST') {
    await readBody(req)
    return json(res, { ok: true })
  }

  if (path.startsWith(`${BASE}/files/`)) {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' })
    res.end('mock file body')
    return
  }

  // Errors carry a hint, which is what the element shows next to the message.
  json(res, { message: '未实现的接口', hint: '参见 docs/bff-protocol.md' }, 404)
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`mock BFF on http://127.0.0.1:${PORT}/  (build the SDK first: npm run build:sdk)`)
})
