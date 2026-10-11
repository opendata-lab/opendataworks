import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, reactive, ref, shallowRef } from 'vue'
import { marked } from 'marked'
import { clearMarkdownCache, normalizeConversationMessage } from '../core/message.js'
import { createChatState, processV2Record } from '../core/streamParser.js'
import { useConversation } from '../core/useConversation.js'
import MessageList from '../ui/MessageList.vue'
import MessageItem from '../ui/MessageItem.vue'
import MarkdownText from '../ui/MarkdownText.vue'
import ThinkingBlock from '../ui/ThinkingBlock.vue'

let wrappers
const mounted = (component, props = {}) => {
  const wrapper = mount(component, { props })
  wrappers.push(wrapper)
  return wrapper
}
const stream = data => ({ record_type: 'stream', data })
const settle = async () => {
  await new Promise(resolve => setTimeout(resolve, 40))
  await nextTick()
}
beforeEach(() => {
  wrappers = []
  clearMarkdownCache()
  vi.stubGlobal('requestAnimationFrame', () => 1)
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
})
afterEach(() => {
  wrappers.forEach(wrapper => wrapper.unmount())
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('streaming rendering', () => {
  it('scrolls again when the throttled tail is rendered after the last token', async () => {
    vi.useFakeTimers()
    let callback
    const schedule = vi.fn(fn => { callback = fn; return 1 })
    vi.stubGlobal('requestAnimationFrame', schedule)
    const message = reactive({ id: 'a', role: 'assistant', _v2state: {
      status: 'streaming', blocks: [{ type: 'text', id: 'text', content: 'first', status: 'streaming' }],
    } })
    mounted(MessageList, { messages: [message] })
    callback()
    message._v2state.blocks[0].content = 'latest tail'
    await nextTick()
    callback()
    schedule.mockClear()
    vi.advanceTimersByTime(250)
    await nextTick()
    expect(schedule).toHaveBeenCalledTimes(1)
  })

  it('retains thinking identity in coordinate-bearing projected snapshots', async () => {
    const message = reactive({ id: 'a', role: 'assistant', _v2state: { status: 'streaming', blocks: [
      { type: 'thinking', id: 'reasoning-id', turnIndex: 3, blockIndex: 7, content: 'before', status: 'streaming' },
    ] } })
    const wrapper = mounted(MessageList, { messages: [message] })
    const original = wrapper.getComponent(ThinkingBlock)
    await original.get('button').trigger('click')
    const snapshot = normalizeConversationMessage({ id: 'a', role: 'assistant', blocks: [
      { type: 'thinking', id: 'reasoning-id', turnIndex: 3, blockIndex: 7, text: 'after' },
    ] })
    expect(snapshot._v2state.blocks[0]).toMatchObject({ id: 'reasoning-id', turnIndex: 3, blockIndex: 7 })
    await wrapper.setProps({ messages: [snapshot] })
    expect(wrapper.getComponent(ThinkingBlock).vm.$).toBe(original.vm.$)
    expect(wrapper.get('.dac-thinking-summary').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('.dac-thinking-content').text()).toContain('after')
  })

  it('rerenders only the changed message, including raw blocks updated by renderVersion', async () => {
    const render = vi.spyOn(MessageItem, 'render')
    const messages = ['history', 'changed'].map(id => normalizeConversationMessage({
      id, role: 'assistant', blocks: [{ type: 'main_text', text: id }],
    }))
    const wrapper = mounted(MessageList, { messages })
    const history = wrapper.findAllComponents(MessageItem)[0].vm.$
    expect(render).toHaveBeenCalledTimes(2)
    render.mockClear()
    for (let i = 0; i < 10; i++) {
      messages[1]._v2state.blocks[0].content = `updated ${i}`
      messages[1].renderVersion++
      await nextTick()
    }
    expect(render).toHaveBeenCalledTimes(10)
    expect(render.mock.calls.every(([context]) => context.message.id === 'changed')).toBe(true)
    expect(wrapper.findAllComponents(MessageItem)[0].vm.$).toBe(history)
    expect(wrapper.get('[data-message-id="changed"]').text()).toContain('updated 9')
    expect(wrapper.get('[data-message-id="history"]').text()).toContain('history')
  })

  it('bounds marked parsing for 1000 text deltas and flushes at block completion', async () => {
    vi.useFakeTimers()
    const parse = vi.spyOn(marked, 'parse')
    const state = reactive(createChatState())
    const event = data => processV2Record(state, stream(data))
    event({ type: 'message_start' })
    event({ type: 'content_block_start', index: 0, content_block: { type: 'text' } })
    const message = reactive({ id: 'live', role: 'assistant', _v2state: state, renderVersion: 0 })
    const wrapper = mounted(MessageList, { messages: [message] })
    for (let i = 0; i < 1000; i++) {
      vi.advanceTimersByTime(1)
      event({ type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'x' } })
      message.renderVersion++
      await nextTick()
    }
    expect(parse).toHaveBeenCalledTimes(4)
    expect(wrapper.get('.dac-bubble').text().length).toBeLessThan(1000)
    event({ type: 'content_block_stop', index: 0 })
    message.renderVersion++
    await nextTick()
    expect(wrapper.get('.dac-bubble').text()).toBe('x'.repeat(1000))
    expect(parse).toHaveBeenCalledTimes(5)
    expect(wrapper.find('.dac-cursor').exists()).toBe(false)
    vi.advanceTimersByTime(250)
    await nextTick()
    expect(parse).toHaveBeenCalledTimes(5)
  })

  it('throttles expanded reasoning, updates its tail, and flushes the final text', async () => {
    vi.useFakeTimers()
    const parse = vi.spyOn(marked, 'parse')
    const block = reactive({ type: 'thinking', content: '', status: 'streaming' })
    const wrapper = mounted(ThinkingBlock, { block })
    await wrapper.get('button').trigger('click')
    for (let i = 0; i < 1000; i++) {
      vi.advanceTimersByTime(1)
      block.content += 'r'
      await nextTick()
    }
    expect(parse).toHaveBeenCalledTimes(4)
    vi.advanceTimersByTime(250)
    await nextTick()
    expect(wrapper.get('.dac-thinking-content').text()).toContain('r'.repeat(1000))
    expect(parse).toHaveBeenCalledTimes(5)
    block.content += 'FINAL'
    block.status = 'done'
    await nextTick()
    expect(wrapper.get('.dac-thinking-content').text()).toContain('FINAL')
    expect(parse).toHaveBeenCalledTimes(6)
  })

  it('cancels a pending markdown update on unmount', async () => {
    vi.useFakeTimers()
    const parse = vi.spyOn(marked, 'parse')
    const wrapper = mounted(MarkdownText, { text: 'first', streaming: true })
    await wrapper.setProps({ text: 'pending' })
    expect(parse).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    vi.advanceTimersByTime(500)
    await nextTick()
    expect(parse).toHaveBeenCalledTimes(1)
  })

  it('keeps thinking instances after insertion and distinguishes identical block indices across turns', async () => {
    const first = reactive({ type: 'thinking', turnIndex: 0, blockIndex: 0, content: 'first', status: 'streaming' })
    const second = reactive({ type: 'thinking', turnIndex: 1, blockIndex: 0, content: 'second', status: 'streaming' })
    const message = reactive({ id: 'a', role: 'assistant', renderVersion: 0, _v2state: { status: 'streaming', blocks: [first, second] } })
    const wrapper = mounted(MessageList, { messages: [message] })
    const panels = wrapper.findAllComponents(ThinkingBlock)
    await panels[1].get('button').trigger('click')
    message._v2state.blocks.unshift({ type: 'text', id: 'inserted', content: 'new', status: 'done' })
    first.content += ' delta'
    second.content += ' delta'
    message.renderVersion++
    await nextTick()
    const updated = wrapper.findAllComponents(ThinkingBlock)
    expect(updated[0].vm.$).toBe(panels[0].vm.$)
    expect(updated[1].vm.$).toBe(panels[1].vm.$)
    expect(updated[0].get('button').attributes('aria-expanded')).toBe('false')
    expect(updated[1].get('button').attributes('aria-expanded')).toBe('true')
  })

  it.each(['records', 'legacy blocks', 'new send'])('keeps thinking expanded through stream completion and %s refresh', async (shape) => {
    let resume, finish
    const start = [
      stream({ type: 'message_start' }),
      stream({ type: 'content_block_start', index: 0, content_block: { type: 'thinking' } }),
      stream({ type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: 'reasoning' } }),
    ]
    const delta = stream({ type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: ' tail' } })
    const end = stream({ type: 'content_block_stop', index: 0 })
    const snapshot = { id: 'a', role: 'assistant', taskId: 't', status: 'finished', ...(shape !== 'legacy blocks'
      ? { records: [...start, delta, end] }
      : { blocks: [{ type: 'thinking', text: 'reasoning tail' }] }) }
    const transport = {
      loadConversation: vi.fn()
        .mockResolvedValueOnce(shape === 'new send' ? { messages: [], run: null } : { messages: [{ id: 'a', role: 'assistant', taskId: 't', status: 'running' }], run: { taskId: 't', status: 'running' } })
        .mockResolvedValue({ messages: [...(shape === 'new send' ? [{ id: 'u', role: 'user', content: 'hello' }] : []), snapshot], run: { taskId: 't', status: 'success' } }),
      sendMessage: vi.fn(async () => ({ taskId: 't', status: 'running' })),
      streamEvents: async function* () {
        for (const [seqId, event] of start.entries()) yield { type: 'event', seqId: seqId + 1, event }
        await new Promise(resolve => { resume = resolve })
        yield { type: 'event', seqId: 4, event: delta }
        await new Promise(resolve => { finish = resolve })
        yield { type: 'event', seqId: 5, event: end }
        yield { type: 'terminal', run: { taskId: 't', status: 'success' } }
      },
    }
    const conversation = useConversation({ generation: ref(0), emit: vi.fn(), transport: shallowRef(transport) })
    const wrapper = mounted(defineComponent({ setup: () => () => h(MessageList, { messages: conversation.messages.value }) }))
    try {
      await conversation.load()
      if (shape === 'new send') await conversation.send('hello')
      await settle()
      const thinking = wrapper.getComponent(ThinkingBlock)
      const original = conversation.messages.value.at(-1)
      await thinking.get('button').trigger('click')
      resume()
      await settle()
      expect(thinking.get('button').attributes('aria-expanded')).toBe('true')
      finish()
      await settle()
      expect(transport.loadConversation).toHaveBeenCalledTimes(2)
      expect(conversation.messages.value.at(-1)).not.toBe(original)
      const refreshed = wrapper.getComponent(ThinkingBlock)
      if (shape !== 'new send') expect(refreshed.vm.$).toBe(thinking.vm.$)
      expect(refreshed.get('button').attributes('aria-expanded')).toBe('true')
      expect(refreshed.get('.dac-thinking-content').text()).toContain('reasoning tail')
      expect(refreshed.find('.dac-thinking-cursor').exists()).toBe(false)
    } finally {
      conversation.stopStream()
    }
  })

  it('updates expanded raw thinking with renderVersion and forgets removed messages', async () => {
    const snapshot = () => normalizeConversationMessage({ id: 'raw', role: 'assistant', blocks: [{ type: 'thinking', text: 'before' }] })
    const message = snapshot()
    const wrapper = mounted(MessageList, { messages: [message] })
    await wrapper.get('.dac-thinking-summary').trigger('click')
    message._v2state.blocks[0].content = 'after'
    message.renderVersion++
    await nextTick()
    expect(wrapper.get('.dac-thinking-content').text()).toContain('after')
    await wrapper.setProps({ messages: [] })
    await wrapper.setProps({ messages: [snapshot()] })
    expect(wrapper.get('.dac-thinking-summary').attributes('aria-expanded')).toBe('false')
  })
})
