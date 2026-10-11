// Blank-page production Web Component lifecycle benchmark in real Chromium.
// Run after bench/build.mjs; --dist selects the identically instrumented bundle.
import { createServer } from 'node:http'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve, dirname, basename } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createHash } from 'node:crypto'
import { strict as assert } from 'node:assert'

const args = process.argv.slice(2)
const option = (name, fallback) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dist = resolve(option('--dist', resolve(root, 'bench/.scratch/current')))
const fixtures = option('--snapshot') ? [resolve(option('--snapshot'))] :
  ['ontofoundry-long-conversation.json', 'ontofoundry-modeling-run.json'].map(name => resolve(root, 'bench/fixtures', name))
const runs = Number(option('--runs', 5))
const bundle = await readFile(resolve(dist, 'index.js'))
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE
  ? pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE)).href : 'playwright')
let raw
const html = `<!DOCTYPE html><html><head><meta charset="utf-8"></head>
<body style="margin:0"><div id="mount" style="height:800px;width:1000px"></div>
<script>
window.__dacBench = {
  mounts: {}, updates: {}, logs: [], tasks: [], errors: [],
  count(kind, name) {
    this[kind][name] = (this[kind][name] || 0) + 1;
    if (kind === 'updates') this.logs.push({ name, time: performance.now() });
  }
};
new PerformanceObserver(list => window.__dacBench.tasks.push(...list.getEntries().map(e => ({ start: e.startTime, duration: e.duration })))).observe({ type: 'longtask', buffered: true });
</script>
<script type="module">
import { defineAgentConversation, createHttpTransport } from '/index.js';
const bench = window.__dacBench;
const frame = () => new Promise(requestAnimationFrame);
bench.mountStart = performance.now();
defineAgentConversation();
const el = document.createElement('dataagent-conversation');
// Real HTTP adapter, same-origin fixture response; no run/null overrides.
el.endpoint = '/snapshot';
el.transportFactory = address => {
  const transport = createHttpTransport(address);
  const load = transport.loadConversation;
  transport.loadConversation = async () => {
    bench.loads = (bench.loads || 0) + 1;
    const value = await load();
    bench.snapshotLoaded = performance.now();
    return value;
  };
  return transport;
};
window.benchElement = el;
const appeared = new Promise(resolve => {
  const observer = new MutationObserver(() => {
    if (el.shadowRoot.querySelector('.dac-message')) {
      observer.disconnect();
      bench.domCommitted = performance.now();
      resolve();
    }
  });
  observer.observe(el.shadowRoot, { subtree: true, childList: true });
});
document.getElementById('mount').append(el);
await appeared;
await frame(); await frame();
bench.firstAppearanceMs = performance.now();
bench.atAppearance = { ...bench.updates };
bench.appeared = true;
await new Promise(resolve => setTimeout(resolve, 5000));
bench.observationEnd = performance.now();
bench.atIdle = { ...bench.updates };
bench.idle = true;
</script></body></html>`
const server = createServer(async (req, res) => {
  try {
    res.setHeader('Cache-Control', 'no-store')
    res.setHeader('Content-Type', req.url === '/snapshot' ? 'application/json' : req.url === '/' ? 'text/html; charset=utf-8' : 'text/javascript')
    res.end(req.url === '/snapshot' ? raw : req.url === '/' ? html : await readFile(resolve(dist, basename(req.url))))
  } catch { res.writeHead(404).end() }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const browser = await chromium.launch({ headless: !args.includes('--headed'), ...(option('--channel') ? { channel: option('--channel') } : {}) })
const results = []
try {
  for (const fixture of fixtures) {
    raw = await readFile(fixture)
    const snapshot = JSON.parse(raw)
    const samples = []
    for (let i = 0; i <= runs; i++) {
      const page = await browser.newPage({ viewport: { width: 1100, height: 900 } })
      const errors = []
      const cdp = await page.context().newCDPSession(page)
      await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 })
      page.on('pageerror', error => errors.push(error.message))
      const profiler = option('--profile') && i === 1 ? await page.context().newCDPSession(page) : null
      if (profiler) { await profiler.send('Profiler.enable'); await profiler.send('Profiler.start') }
      await page.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: 'commit' })
      await page.waitForFunction(() => window.__dacBench?.idle, null, { timeout: 120000 })
      const sample = await page.evaluate(async () => {
        const b = window.__dacBench, el = window.benchElement, dom = el.shadowRoot;
        const counts = () => ({ ...b.updates });
        const diff = (a, c) => Object.fromEntries(Object.entries(a).map(([name, n]) => [name, n - (c[name] || 0)]).filter(([, n]) => n));
        const tasks = b.tasks.filter(t => t.start < b.observationEnd && t.start + t.duration > b.mountStart);
        const result = {
          firstAppearanceMs: b.firstAppearanceMs,
          mountToAppearanceMs: b.firstAppearanceMs - b.mountStart,
          snapshotLoadedMs: b.snapshotLoaded,
          observationMs: b.observationEnd - b.mountStart,
          longTaskTotalMs: tasks.reduce((n, t) => n + t.duration, 0),
          longestTaskMs: Math.max(0, ...tasks.map(t => t.duration)),
          longTasks: tasks,
          mounts: b.mounts, loadUpdates: b.atIdle,
          postAppearanceUpdates: diff(b.atIdle, b.atAppearance),
          settledUpdates: b.logs.filter(e => e.time >= b.firstAppearanceMs + 1000),
          loads: b.loads,
          messages: dom.querySelectorAll('.dac-message').length,
          thinking: dom.querySelectorAll('.dac-thinking').length,
          tools: dom.querySelectorAll('.tool-output').length,
          nodes: dom.querySelectorAll('*').length,
        };
        // Open the same middle thinking block in every revision; scroll first,
        // then idle two frames so this is a visible, settled interaction.
        const toggle = dom.querySelectorAll('.dac-thinking-summary')[20] || dom.querySelector('.dac-thinking-summary');
        toggle.scrollIntoView({ block: 'center' });
        await new Promise(requestAnimationFrame); await new Promise(requestAnimationFrame);
        await new Promise(resolve => setTimeout(resolve, 100));
        const before = counts();
        const start = performance.now();
        toggle.click();
        await new Promise(requestAnimationFrame);
        result.thinkingOpenMs = performance.now() - start;
        await new Promise(requestAnimationFrame);
        result.thinkingPaintMs = performance.now() - start;
        result.thinkingUpdates = diff(counts(), before);
        result.thinkingExpandable = toggle.getAttribute('aria-expanded') === 'true' && Boolean(dom.querySelector('.dac-thinking-content'));
        await new Promise(resolve => setTimeout(resolve, 200));
        result.thinkingSettledUpdates = diff(counts(), before);
        toggle.click();
        await new Promise(requestAnimationFrame);
        result.thinkingClosed = toggle.getAttribute('aria-expanded') === 'false';
        const tool = dom.querySelector('.shell-trace-summary') || dom.querySelector('.tool-output-head');
        tool?.click();
        await new Promise(requestAnimationFrame);
        result.toolExpandable = Boolean(dom.querySelector('.tool-output-panel'));
        return result;
      })
      if (profiler) {
        const { profile } = await profiler.send('Profiler.stop')
        await writeFile(resolve(option('--profile')), JSON.stringify(profile))
      }
      assert.deepEqual(errors, [])
      if (!args.includes('--plain')) assert.ok(Object.keys(sample.mounts).length, 'Use bench/build.mjs instrumentation')
      assert.equal(sample.messages, snapshot.messages.length)
      assert.ok(sample.thinkingExpandable && sample.thinkingClosed && sample.toolExpandable)
      if (i) samples.push(sample) // discard warmup, fresh page per sample
      await page.close()
    }
    const median = key => [...samples].sort((a, b) => a[key] - b[key])[Math.floor(samples.length / 2)][key]
    const metrics = Object.fromEntries(['firstAppearanceMs', 'mountToAppearanceMs', 'longTaskTotalMs', 'longestTaskMs', 'thinkingOpenMs', 'thinkingPaintMs'].map(key => [key, median(key)]))
    if (args.includes('--check')) {
      assert.ok(samples.every(s => !Object.keys(s.postAppearanceUpdates).length), 'Completed conversation keeps updating after first appearance')
      assert.ok(samples.every(s => JSON.stringify(s.thinkingUpdates) === '{"ThinkingBlock":1}'), 'Thinking expansion updated unrelated components')
      if (basename(fixture) === 'ontofoundry-modeling-run.json') {
        assert.ok(metrics.firstAppearanceMs < 1000, JSON.stringify(metrics))
        assert.ok(metrics.longTaskTotalMs < 300, JSON.stringify(metrics))
      }
      assert.ok(metrics.thinkingOpenMs < 50 && metrics.thinkingPaintMs < 50, JSON.stringify(metrics))
    }
    results.push({ fixture: basename(fixture), snapshotBytes: raw.length, snapshotSha256: createHash('sha256').update(raw).digest('hex'), metrics, samples })
  }
  const result = {
    browser: browser.version(), channel: option('--channel', 'chromium'), headed: args.includes('--headed'), node: process.version, platform: `${process.platform}/${process.arch}`,
    viewport: { width: 1100, height: 900 }, cpuThrottling: 1, warmup: 1, measuredRuns: runs,
    bundleSha256: createHash('sha256').update(bundle).digest('hex'), bundleBytes: bundle.length,
    method: 'Cold SDK navigation + same-origin fake BFF via createHttpTransport. First appearance: message DOM committed then two rAFs; observe until appearance +5s. Long Task API full durations from mount through observation end. Click to next rAF and following paint opportunity. Benchmark-only Vue beforeMount/beforeUpdate counters. No profiler for timing results.',
    results,
  }
  if (option('--out')) await writeFile(resolve(option('--out')), JSON.stringify(result, null, 2) + '\n')
  console.log(JSON.stringify(result, null, 2))
} finally { await browser.close(); server.close() }
