// Production Web Component benchmark. No BFF trimming, no mocked DOM/layout.
// Usage: node bench/render.mjs --snapshot /path/raw.json [--dist /path/dist]
// Install playwright outside the repo or supply PLAYWRIGHT_MODULE=/path/index.mjs.
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { resolve, dirname, basename } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createHash } from 'node:crypto'
import { strict as assert } from 'node:assert'

const args = process.argv.slice(2)
const option = (name, fallback) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dist = resolve(option('--dist', resolve(root, 'dist')))
const raw = await readFile(resolve(option('--snapshot')))
const snapshot = JSON.parse(raw)
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE
  ? pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE)).href : 'playwright')
const server = createServer(async (req, res) => {
  try {
    res.setHeader('Content-Type', req.url === '/snapshot' ? 'application/json' : req.url === '/' ? 'text/html' : 'text/javascript')
    res.end(req.url === '/snapshot' ? raw : req.url === '/' ? '<body style="margin:0"><div id="mount" style="height:800px;width:1000px"></div></body>' : await readFile(resolve(dist, basename(req.url))))
  } catch { res.writeHead(404).end() }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const browser = await chromium.launch({ headless: true })
const samples = []
try {
  for (let i = 0; i < 6; i++) {
    const page = await browser.newPage({ viewport: { width: 1100, height: 900 } })
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    await page.goto(`http://127.0.0.1:${server.address().port}/`)
    const profiler = option('--profile') && i === 1 ? await page.context().newCDPSession(page) : null
    if (profiler) { await profiler.send('Profiler.enable'); await profiler.send('Profiler.start') }
    const sample = await page.evaluate(async () => {
      const { defineAgentConversation } = await import('/index.js')
      defineAgentConversation()
      const raw = await (await fetch('/snapshot')).text()
      const frame = () => new Promise(requestAnimationFrame)
      await frame()
      const tasks = []
      const observer = new PerformanceObserver(list => tasks.push(...list.getEntries()))
      observer.observe({ type: 'longtask' })
      let maxTimerGapMs = 0, previous = performance.now()
      const timer = setInterval(() => {
        const now = performance.now()
        maxTimerGapMs = Math.max(maxTimerGapMs, now - previous)
        previous = now
      }, 0)
      const start = performance.now()
      const snapshot = JSON.parse(raw)
      const el = document.createElement('dataagent-conversation')
      const ready = new Promise(resolve => el.addEventListener('dataagent-ready', resolve, { once: true }))
      Object.assign(el, {
        endpoint: '/bench',
        transportFactory: () => ({ loadConversation: async () => ({ ...snapshot, run: null }), fileUrl: p => `/files/${p}` })
      })
      document.getElementById('mount').append(el)
      await ready
      const readyMs = performance.now() - start
      // First rAF: Vue has committed DOM; second rAF: layout/paint had a chance.
      await frame()
      await frame()
      const end = performance.now()
      clearInterval(timer)
      await new Promise(resolve => setTimeout(resolve, 30)) // deliver observer entries
      observer.disconnect()
      const dom = el.shadowRoot
      const result = {
        renderMs: end - start,
        readyMs,
        longestTaskMs: Math.max(0, ...tasks.filter(t => t.startTime + t.duration > start && t.startTime < end).map(t => t.duration)),
        maxTimerGapMs,
        messages: dom.querySelectorAll('.dac-message').length,
        tools: dom.querySelectorAll('.tool-output').length,
        thinking: dom.querySelectorAll('.dac-thinking').length,
        nodes: dom.querySelectorAll('*').length,
        answerVisible: dom.textContent.includes('已完成建模分析'),
        openPanels: dom.querySelectorAll('.tool-output-panel').length,
      }
      // Interaction smoke: deferred content really becomes available on demand.
      dom.querySelector('.shell-trace-summary')?.click()
      dom.querySelector('.dac-thinking-summary')?.click()
      await frame()
      result.toolExpandable = Boolean(dom.querySelector('.tool-output-panel'))
      result.thinkingExpandable = Boolean(dom.querySelector('.dac-thinking-content'))
      el.remove()
      return result
    })
    if (profiler) {
      const { writeFile } = await import('node:fs/promises')
      const { profile } = await profiler.send('Profiler.stop')
      await writeFile(resolve(option('--profile')), JSON.stringify(profile))
    }
    assert.deepEqual(errors, [])
    assert.equal(sample.messages, snapshot.messages.length)
    assert.ok(sample.answerVisible && sample.toolExpandable && sample.thinkingExpandable)
    if (i) samples.push(sample) // discard warmup, fresh page each time
    await page.close()
  }
  const median = key => [...samples].sort((a, b) => a[key] - b[key])[2][key]
  console.log(JSON.stringify({
    browser: browser.version(), snapshotBytes: raw.length,
    snapshotSha256: createHash('sha256').update(raw).digest('hex'),
    records: snapshot.messages.reduce((n, m) => n + (m.records?.length || 0), 0),
    samples, medianRenderMs: median('renderMs'),
    medianLongestTaskMs: median('longestTaskMs'),
    worstLongestTaskMs: Math.max(...samples.map(s => s.longestTaskMs)),
    medianMaxTimerGapMs: median('maxTimerGapMs'),
    note: 'Long Task API measures synchronous main-thread tasks >=50ms; 0 means <50ms. Timer gap also includes scheduling/layout. No CPU throttling.'
  }, null, 2))
} finally { await browser.close(); server.close() }
