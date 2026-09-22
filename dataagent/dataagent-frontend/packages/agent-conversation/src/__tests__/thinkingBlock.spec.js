import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ThinkingBlock from '../ui/ThinkingBlock.vue'

const mountThinking = (overrides = {}) => mount(ThinkingBlock, {
  props: {
    block: {
      type: 'thinking',
      content: '先分析业务目标，再确认数据范围。',
      status: 'done',
      ...overrides,
    },
  },
})

describe('ThinkingBlock', () => {
  it('keeps completed reasoning collapsed until the user expands it', async () => {
    const wrapper = mountThinking()
    const toggle = wrapper.get('button')

    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.dac-thinking-content').exists()).toBe(false)
    expect(wrapper.get('.dac-thinking-preview').text()).toContain('先分析业务目标')

    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('.dac-thinking-content').text()).toContain('再确认数据范围')
  })

  it('is also collapsed while streaming and marks live progress', async () => {
    const wrapper = mountThinking({ status: 'streaming' })

    expect(wrapper.get('button').attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.dac-thinking-dot').exists()).toBe(true)

    await wrapper.get('button').trigger('click')

    expect(wrapper.find('.dac-thinking-cursor').exists()).toBe(true)
  })

  it('keeps separate thinking blocks independently expandable', async () => {
    const first = mountThinking({ content: '第一段思考' })
    const second = mountThinking({ content: '第二段思考' })

    await first.get('button').trigger('click')

    expect(first.get('button').attributes('aria-expanded')).toBe('true')
    expect(second.get('button').attributes('aria-expanded')).toBe('false')
  })
})
