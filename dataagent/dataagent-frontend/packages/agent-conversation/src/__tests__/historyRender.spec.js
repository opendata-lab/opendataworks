import fixture from '../../bench/fixtures/ontofoundry-long-conversation.json'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { marked } from 'marked'
import * as chartSpec from '../core/chartSpec.js'
import * as toolPresentation from '../core/toolPresentation.js'
import { clearMarkdownCache, normalizeConversationMessage, renderMarkdown } from '../core/message.js'
import MessageList from '../ui/MessageList.vue'
import MessageItem from '../ui/MessageItem.vue'
import ThinkingBlock from '../ui/ThinkingBlock.vue'
import MarkdownText from '../ui/MarkdownText.vue'
import ToolOutput from '../ui/ToolOutput.vue'
import LimitedText from '../ui/LimitedText.vue'

let wrappers, frames, frameId
const mounted = (component, props) => {
  const wrapper = mount(component, { props })
  wrappers.push(wrapper)
  return wrapper
}
const frame = () => {
  const callbacks = [...frames.values()]
  frames.clear()
  callbacks.forEach(callback => callback())
}
beforeEach(() => {
  wrappers = []
  frames = new Map()
  frameId = 0
  vi.stubGlobal('requestAnimationFrame', callback => { frames.set(++frameId, callback); return frameId })
  vi.stubGlobal('cancelAnimationFrame', id => frames.delete(id))
  clearMarkdownCache()
})
afterEach(() => {
  wrappers.forEach(wrapper => wrapper.unmount())
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('completed conversation rendering', () => {
  it('renders the real fixture once and isolates thinking expansion to one block', async () => {
    const renders = Object.fromEntries(Object.entries({ MessageList, MessageItem, ThinkingBlock, MarkdownText, ToolOutput })
      .map(([name, component]) => [name, vi.spyOn(component, 'render')]))
    const parse = vi.spyOn(marked, 'parse')
    const action = vi.spyOn(toolPresentation, 'describeToolAction')
    const start = performance.now()
    const messages = fixture.messages.map(normalizeConversationMessage)
    const wrapper = mounted(MessageList, { messages })
    await nextTick()
    const mountMs = performance.now() - start
    const initialCounts = Object.fromEntries(Object.entries(renders).map(([name, spy]) => [name, spy.mock.calls.length]))
    expect(initialCounts.MessageItem).toBe(10)
    expect(initialCounts.ThinkingBlock).toBe(42)
    expect(initialCounts.ToolOutput).toBe(38)
    expect(initialCounts.MarkdownText).toBe(messages.reduce((n, m) => n + (m._v2state?.blocks.filter(b => b.type === 'text').length || 0), 0))
    // Every visible text source parses once; folded thinking/output never parses.
    expect(parse.mock.calls.map(([text]) => text).length).toBe(new Set(parse.mock.calls.map(([text]) => text)).size)
    expect(wrapper.findAll('.dac-thinking-content')).toHaveLength(0)
    const initialParses = parse.mock.calls.length
    Object.values(renders).forEach(spy => spy.mockClear())
    action.mockClear()
    await wrapper.findAll('.dac-thinking-summary')[20].trigger('click')
    const openCounts = Object.fromEntries(Object.entries(renders).map(([name, spy]) => [name, spy.mock.calls.length]))
    expect(openCounts).toEqual({ MessageList: 0, MessageItem: 0, ThinkingBlock: 1, MarkdownText: 0, ToolOutput: 0 })
    expect(parse).toHaveBeenCalledTimes(initialParses + 1)
    expect(action).not.toHaveBeenCalled()
    expect(wrapper.findAll('.dac-thinking-content')).toHaveLength(1)
    Object.values(renders).forEach(spy => spy.mockClear())
    await wrapper.findAll('.dac-thinking-summary')[20].trigger('click')
    expect(renders.ThinkingBlock).toHaveBeenCalledTimes(1)
    for (const name of ['MessageList', 'MessageItem', 'MarkdownText', 'ToolOutput']) expect(renders[name]).not.toHaveBeenCalled()
    expect(parse).toHaveBeenCalledTimes(initialParses + 1)
    if (process.env.DAC_RENDER_BENCH) console.info('DAC_RENDER_COUNTS', JSON.stringify({ mountMs, initialCounts, initialParses, openCounts }))
  })

  it('retains oversized markdown when props change or the shared cache is evicted', async () => {
    const parse = vi.spyOn(marked, 'parse')
    const text = '**answer** [file](output/report.md)\n\n' + 'long '.repeat(30_000)
    const wrapper = mounted(MarkdownText, { text, fileUrl: path => `/first/${path}` })
    expect(parse).toHaveBeenCalledTimes(1)
    for (let i = 0; i < 140; i++) renderMarkdown(`eviction-${i}`)
    parse.mockClear()
    await wrapper.setProps({ fileUrl: path => `/second/${path}`, streaming: true })
    await wrapper.setProps({ streaming: false })
    expect(wrapper.get('a').attributes('href')).toBe('/second/output/report.md')
    expect(wrapper.text()).toContain('long '.repeat(100).trim())
    expect(parse).not.toHaveBeenCalled()
  })

  it('does not reparse expanded oversized LimitedText on status-only updates', async () => {
    const parse = vi.spyOn(marked, 'parse')
    const wrapper = mounted(LimitedText, { text: 'large '.repeat(30_000), markdown: true })
    await wrapper.get('button').trigger('click')
    expect(parse).toHaveBeenCalledTimes(2)
    await wrapper.setProps({ streaming: true })
    await wrapper.setProps({ streaming: false })
    expect(parse).toHaveBeenCalledTimes(2)
  })

  it('reuses tool props while another block streams, but updates changed output', async () => {
    const render = vi.spyOn(ToolOutput, 'render')
    const action = vi.spyOn(toolPresentation, 'describeToolAction')
    const message = reactive({ id: 'live', role: 'assistant', renderVersion: 0, _v2state: { status: 'streaming', blocks: [
      { id: 'tool', type: 'tool_use', name: 'Bash', input: { command: 'pwd' }, status: 'done', output: '/tmp' },
      { id: 'text', type: 'text', content: 'start', status: 'streaming' },
    ] } })
    const wrapper = mounted(MessageList, { messages: [message] })
    render.mockClear()
    action.mockClear()
    for (let i = 0; i < 5; i++) {
      message._v2state.blocks[1].content += ' delta'
      message.renderVersion++
      await nextTick()
    }
    expect(render).not.toHaveBeenCalled()
    expect(action).not.toHaveBeenCalled()
    message._v2state.blocks[0].output = '/changed'
    message.renderVersion++
    await nextTick()
    expect(render).toHaveBeenCalledTimes(1)
    expect(action).not.toHaveBeenCalled()
    await wrapper.get('.shell-trace-summary').trigger('click')
    expect(wrapper.text()).toContain('/changed')
  })

  it('does not normalize unchanged tool output on status/input updates', async () => {
    const normalizeChart = vi.spyOn(chartSpec, 'extractChartSpec')
    const output = { kind: 'sql_execution', sql: 'select 1', rows: [{ one: 1 }], columns: ['one'] }
    const tool = { id: 'query', name: 'execute_sql', input: { sql: 'select 1' }, status: 'streaming', output }
    const wrapper = mounted(ToolOutput, { tool })
    expect(normalizeChart).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ tool: { ...tool, status: 'success', _callComplete: true } })
    await wrapper.setProps({ tool: { ...tool, input: { sql: 'select 2' } } })
    expect(normalizeChart).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ tool: { ...tool, output: { ...output, sql: 'select 2' } } })
    expect(normalizeChart).toHaveBeenCalledTimes(2)
    await wrapper.get('.tool-output-head').trigger('click')
    expect(wrapper.getComponent({ name: 'SqlCodePanel' }).props('sql')).toBe('select 2')
  })

  it('keeps following when content grows between a near-bottom scroll and its frame', async () => {
    const wrapper = mounted(MessageList, { messages: [] })
    const el = wrapper.element
    let height = 600
    Object.defineProperties(el, {
      scrollHeight: { get: () => height },
      clientHeight: { get: () => 300 },
    })
    frame()
    el.scrollTop = 250
    await wrapper.trigger('scroll')
    height = 800
    wrapper.vm.scrollToBottom()
    frame()
    expect(el.scrollTop).toBe(500)
    el.scrollTop = 100
    await wrapper.trigger('scroll')
    height = 1000
    wrapper.vm.scrollToBottom()
    frame()
    expect(el.scrollTop).toBe(100)
  })

  it('reads layout only in rAF, writes last, and never loops on programmatic scroll', async () => {
    const listen = vi.spyOn(HTMLElement.prototype, 'addEventListener')
    const wrapper = mounted(MessageList, { messages: [] })
    expect(listen.mock.calls.some(([type, , options]) => type === 'scroll' && options?.passive)).toBe(true)
    const el = wrapper.element
    const work = []
    let top = 0
    Object.defineProperties(el, {
      scrollHeight: { get: () => { work.push('height'); return 800 } },
      clientHeight: { get: () => { work.push('viewport'); return 300 } },
      scrollTop: { get: () => { work.push('top'); return top }, set: value => { work.push('write'); top = value } },
    })
    frame()
    expect(work).toEqual(['height', 'top', 'viewport', 'write'])
    work.length = 0
    await wrapper.trigger('scroll')
    expect(work).toEqual([])
    expect(frames.size).toBe(1)
    frame()
    expect(work).toEqual(['height', 'top', 'viewport'])
    expect(frames.size).toBe(0)
    work.length = 0
    top = 0
    wrapper.vm.scrollToBottom()
    await wrapper.trigger('scroll')
    frame()
    expect(work).toEqual(['height', 'top', 'viewport'])
    expect(top).toBe(0)
    wrapper.vm.scrollToBottom(true)
    frame()
    expect(top).toBe(500)
    work.length = 0
    for (let i = 0; i < 10; i++) await wrapper.trigger('scroll')
    expect(work).toEqual([])
    frame()
    expect(work).toEqual(['height', 'top', 'viewport'])
  })
})
