import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { isReactive, nextTick, reactive, ref, shallowRef } from 'vue'
import { marked } from 'marked'
import { clearMarkdownCache, normalizeConversationMessage, renderMarkdown } from '../core/message.js'
import { useConversation } from '../core/useConversation.js'
import MessageList from '../ui/MessageList.vue'
import Composer from '../ui/Composer.vue'
import ThinkingBlock from '../ui/ThinkingBlock.vue'
import ToolOutput from '../ui/ToolOutput.vue'
import LimitedText from '../ui/LimitedText.vue'

let frames, wrappers, frameId
const renderFrame = () => {
  const pending = [...frames.values()]
  frames.clear()
  pending.forEach(fn => fn(16))
}
const mounted = (component, props = {}) => {
  const wrapper = mount(component, { props })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  frames = new Map()
  wrappers = []
  frameId = 0
  vi.stubGlobal('requestAnimationFrame', fn => { frames.set(++frameId, fn); return frameId })
  vi.stubGlobal('cancelAnimationFrame', id => frames.delete(id))
  clearMarkdownCache()
})
afterEach(() => {
  wrappers.forEach(w => w.unmount())
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('render work is bounded', () => {
  it('never traverses stored records and coalesces streaming scrolls per frame', async () => {
    const readRecords = vi.fn(() => { throw new Error('records must not be traversed') })
    const message = reactive({ id: 'a', role: 'assistant', content: 'answer', renderVersion: 0 })
    const wrapper = mounted(MessageList, { messages: [message] })
    // Install after mount: Vue Test Utils itself recursively inspects props.
    Object.defineProperty(message, 'records', { enumerable: true, get: readRecords })
    const el = wrapper.element
    const height = vi.fn(() => 800)
    Object.defineProperty(el, 'scrollHeight', { get: height })
    renderFrame()
    height.mockClear()
    for (let i = 0; i < 8; i++) { message.renderVersion++; await nextTick() }
    expect(frames.size).toBe(1)
    expect(height).not.toHaveBeenCalled()
    renderFrame()
    expect(height).toHaveBeenCalledTimes(1)
    expect(el.scrollTop).toBe(800)
    expect(readRecords).not.toHaveBeenCalled()

    // User scrolls up after scheduling: the pending frame must respect it.
    message.renderVersion++
    await nextTick()
    el.scrollTop = 0
    await wrapper.trigger('scroll')
    renderFrame()
    expect(el.scrollTop).toBe(0)
    wrapper.vm.scrollToBottom(true)
    wrapper.unmount()
    expect(frames.size).toBe(0)
  })

  it('merges input/prop/focus resize and skips unchanged text/width', async () => {
    let resized
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback) { resized = callback }
      observe() {}
      disconnect() {}
    })
    const wrapper = mounted(Composer)
    const el = wrapper.get('textarea').element
    let width = 400, height = 38
    const measure = vi.fn(() => height)
    Object.defineProperty(el, 'clientWidth', { get: () => width })
    Object.defineProperty(el, 'scrollHeight', { get: measure })
    await nextTick()
    renderFrame()
    measure.mockClear()
    await wrapper.get('textarea').setValue('line 1\nline 2')
    await wrapper.setProps({ modelValue: 'line 1\nline 2' })
    wrapper.vm.focus()
    await nextTick()
    expect(frames.size).toBe(1)
    height = 120
    renderFrame()
    expect(measure).toHaveBeenCalledTimes(1)
    expect(el.style.height).toBe('120px')
    wrapper.vm.focus()
    await nextTick()
    renderFrame()
    expect(measure).toHaveBeenCalledTimes(1)
    width = 200
    height = 300
    resized()
    renderFrame()
    expect(el.style.height).toBe('160px')
    await wrapper.get('textarea').setValue('')
    height = 38
    renderFrame()
    expect(el.style.height).toBe('38px')
    wrapper.vm.focus()
    await nextTick()
    wrapper.unmount()
    expect(frames.size).toBe(0)
  })

  it('parses neither folded thinking nor folded tool output; limits expanded markdown', async () => {
    const parse = vi.spyOn(marked, 'parse')
    const source = '# title\n' + 'x'.repeat(6000) + 'END-OF-OUTPUT'
    const thinking = mounted(ThinkingBlock, { block: { content: source, status: 'streaming' } })
    const tool = mounted(ToolOutput, { tool: { id: 'r1', name: 'Read', input: { file_path: 'notes.md' }, output: source, status: 'streaming' } })
    expect(parse).not.toHaveBeenCalled()
    expect(tool.find('.tool-output-panel').exists()).toBe(false)
    await thinking.get('button').trigger('click')
    await tool.get('button.shell-trace-summary').trigger('click')
    expect(parse.mock.calls.every(([text]) => text.length <= 4000)).toBe(true)
    expect(thinking.text()).not.toContain('END-OF-OUTPUT')
    await thinking.get('button.tool-markdown-toggle').trigger('click')
    expect(thinking.text()).toContain('END-OF-OUTPUT')
    await tool.get('button.tool-markdown-toggle').trigger('click')
    expect(tool.text()).toContain('END-OF-OUTPUT')
    await tool.setProps({ tool: { id: 'r1', name: 'Read', input: { file_path: 'notes.md' }, output: source, status: 'success', _callComplete: true, _runtimeStarted: true } })
    expect(tool.find('.tool-output-panel').exists()).toBe(true)
    await tool.get('button.shell-trace-summary').trigger('click')
    expect(tool.find('.tool-output-panel').exists()).toBe(false)
  })

  it('bounds plain output even when it is one long line and restores full output on demand', async () => {
    const wrapper = mounted(LimitedText, { text: 'x'.repeat(20_000) + 'TAIL' })
    expect(wrapper.get('code').text().length).toBe(4000)
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('code').text()).toContain('TAIL')
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('code').text().length).toBe(4000)
  })

  it('keeps structured SQL output folded and preserves chart visibility', async () => {
    const sql = mounted(ToolOutput, { tool: { id: 'sql', name: 'execute_sql', output: {
      kind: 'sql_execution', sql: 'select 1', columns: ['one'], rows: [{ one: 1 }],
    }, status: 'success', _callComplete: true } })
    expect(sql.find('.tool-output-panel').exists()).toBe(false)
    const chart = mounted(ToolOutput, { tool: { id: 'chart', name: 'chart', output: {
      kind: 'chart_spec', version: 1, chart_type: 'table', title: 'Table',
      dataset: [{ one: 1 }], columns: ['one'],
    }, status: 'success', _callComplete: true } })
    expect(chart.findComponent({ name: 'ChartSpecView' }).exists()).toBe(true)
  })

  it('runs elapsed timers only for live shell calls and stops them when complete', async () => {
    const start = vi.spyOn(globalThis, 'setInterval')
    const stop = vi.spyOn(globalThis, 'clearInterval')
    const wrapper = mounted(ToolOutput, { tool: { id: 'shell', name: 'Bash', input: { command: 'pwd' }, status: 'success', output: '/tmp', _callComplete: true } })
    expect(start).not.toHaveBeenCalled()
    await wrapper.setProps({ tool: { id: 'shell', name: 'Bash', input: { command: 'pwd' }, status: 'streaming' } })
    expect(start).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ tool: { id: 'shell', name: 'Bash', input: { command: 'pwd' }, status: 'success', output: '/tmp', _callComplete: true } })
    expect(stop).toHaveBeenCalledTimes(1)
  })

  it('bounds a collapsed shell summary without losing the complete command', async () => {
    const command = 'echo ' + 'x'.repeat(9000) + 'COMMAND-END'
    const wrapper = mounted(ToolOutput, { tool: { id: 'large-shell', name: 'Bash', input: { command }, status: 'success', output: 'ok', _callComplete: true } })
    expect(wrapper.get('.shell-trace-summary-text').text().length).toBe(161)
    expect(wrapper.text()).not.toContain('COMMAND-END')
    await wrapper.get('button.shell-trace-summary').trigger('click')
    expect(wrapper.get('.shell-trace-command code').text().length).toBe(4000)
    await wrapper.get('.shell-trace-command button').trigger('click')
    expect(wrapper.get('.shell-trace-command code').text()).toContain('COMMAND-END')
  })

  it('caches markdown by exact source while resolving current topic links on every use', () => {
    const parse = vi.spyOn(marked, 'parse')
    const source = '**bold** [file](output/report.md) <script>alert(1)</script>'
    let topic = 'one'
    const resolveFileHref = path => `/${topic}/${path}`
    expect(renderMarkdown(source, { resolveFileHref })).toContain('/one/output/report.md')
    topic = 'two'
    const html = renderMarkdown(source, { resolveFileHref })
    expect(html).toContain('/two/output/report.md')
    expect(html).not.toContain('<script>')
    expect(parse).toHaveBeenCalledTimes(1)
    renderMarkdown(source + '!')
    expect(parse).toHaveBeenCalledTimes(2)
    for (let i = 0; i < 128; i++) renderMarkdown(`entry-${i}`)
    renderMarkdown(source)
    expect(parse).toHaveBeenCalledTimes(131)
    const huge = 'x'.repeat(150_000)
    renderMarkdown(huge)
    renderMarkdown(huge)
    expect(parse).toHaveBeenCalledTimes(133)
  })

  it('keeps historical blocks raw and feedback reactive, but active blocks reactive', () => {
    const done = normalizeConversationMessage({ id: 'a', role: 'assistant', content: 'done', status: 'finished' })
    expect(isReactive(done)).toBe(true)
    expect(isReactive(done._v2state)).toBe(false)
    expect(isReactive(done.blocks[0])).toBe(false)
    const live = normalizeConversationMessage({ id: 'b', role: 'assistant', content: 'live', status: 'running' })
    expect(isReactive(live._v2state)).toBe(true)
    expect(isReactive(live.blocks[0])).toBe(true)
  })

  it('promotes a stale finished snapshot when the run resumes and bumps the render version', async () => {
    let release
    const conversation = useConversation({ generation: ref(0), emit: vi.fn(), transport: shallowRef({
      loadConversation: async () => ({ messages: [{ id: 'a', role: 'assistant', taskId: 't', status: 'finished', content: 'existing' }], run: { taskId: 't', status: 'running' } }),
      streamEvents: async function* () {
        await new Promise(resolve => { release = resolve })
        yield { type: 'event', seqId: 1, event: { record_type: 'stream', data: { type: 'message_start' } } }
      },
    }) })
    await conversation.load()
    expect(isReactive(conversation.messages.value[0]._v2state)).toBe(true)
    release()
    for (let i = 0; i < 8; i++) await Promise.resolve()
    expect(conversation.messages.value[0].renderVersion).toBe(1)
    conversation.stopStream()
  })
})
