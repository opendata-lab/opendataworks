import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import QuestionCard from '../ui/QuestionCard.vue'

const QUESTIONS = [
  {
    header: '粒度',
    question: '订单事实按什么粒度？',
    options: [
      { label: '按订单行（推荐）', description: '一行一个商品，金额可加总' },
      { label: '按订单', description: '一行一单' },
    ],
  },
  {
    header: '口径',
    question: '退款是否扣减？',
    options: [{ label: '扣减', recommended: true }, { label: '不扣减' }],
  },
  {
    header: '维度',
    question: '关联哪些维度？',
    multiSelect: true,
    options: [{ label: '门店' }, { label: '商品' }],
  },
]

const block = (overrides = {}) => ({
  type: 'question_request',
  requestId: 'q-1',
  questions: QUESTIONS,
  answered: false,
  ...overrides,
})

afterEach(() => vi.useRealTimers())

describe('QuestionCard', () => {
  it('shows one question at a time with numbered options and the recommendation', () => {
    const wrapper = mount(QuestionCard, { props: { block: block() } })

    expect(wrapper.get('.dac-q-question').text()).toBe('订单事实按什么粒度？')
    expect(wrapper.get('.dac-q-page').text()).toBe('1 / 3')
    const options = wrapper.findAll('.dac-q-opt')
    expect(options[0].get('.dac-q-num').text()).toBe('1')
    expect(options[0].get('.dac-q-opt-label').text()).toBe('按订单行（推荐）')
    expect(options[0].find('.dac-q-rec').exists()).toBe(true)
    expect(options[0].get('.dac-q-opt-desc').text()).toBe('一行一个商品，金额可加总')
    expect(options[1].find('.dac-q-rec').exists()).toBe(false)
    expect(wrapper.get('.dac-q-other input').attributes('placeholder')).toBe('补充你的想法，或给出不同的答案…')
  })

  it('advances on a pick, skips, and submits after the last question', async () => {
    const wrapper = mount(QuestionCard, { props: { block: block() } })

    await wrapper.findAll('.dac-q-opt')[0].trigger('click')
    expect(wrapper.get('.dac-q-page').text()).toBe('2 / 3')

    await wrapper.get('.dac-q-skip').trigger('click')
    expect(wrapper.get('.dac-q-page').text()).toBe('3 / 3')

    // Multi-select needs an explicit submit.
    await wrapper.findAll('.dac-q-opt')[1].trigger('click')
    expect(wrapper.emitted('answer')).toBeUndefined()
    await wrapper.get('.dac-q-other input').setValue('还要会员')
    await wrapper.get('.dac-q-next').trigger('click')

    expect(wrapper.emitted('answer')[0][0]).toEqual({
      requestId: 'q-1',
      answers: [
        { header: '粒度', question: '订单事实按什么粒度？', selected: ['按订单行'], other: '', skipped: false },
        { header: '口径', question: '退款是否扣减？', selected: [], other: '', skipped: true },
        { header: '维度', question: '关联哪些维度？', selected: ['商品'], other: '还要会员', skipped: false },
      ],
    })
  })

  it('skips everything left with the close button', async () => {
    const wrapper = mount(QuestionCard, { props: { block: block() } })
    await wrapper.get('.dac-q-other input').setValue('按订单行，但退款单独成表')
    await wrapper.get('.dac-q-close').trigger('click')

    const answers = wrapper.emitted('answer')[0][0].answers
    expect(answers.map((a) => a.skipped)).toEqual([false, true, true])
    expect(answers[0].other).toBe('按订单行，但退款单独成表')
  })

  it('counts down to the deadline while open', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-26T08:00:00Z'))
    const wrapper = mount(QuestionCard, { props: { block: block({ deadline_at: '2026-10-26T08:10:00+00:00' }) } })
    expect(wrapper.get('.dac-q-countdown').text()).toBe('10:00 后按推荐自动选择')

    vi.advanceTimersByTime(61_000)
    await wrapper.vm.$nextTick()
    expect(wrapper.get('.dac-q-countdown').text()).toBe('8:59 后按推荐自动选择')
  })

  it('labels answers chosen by the timeout policy', () => {
    const wrapper = mount(QuestionCard, {
      props: {
        block: block({
          answered: true,
          source: 'auto_timeout',
          answers: [
            { header: '粒度', selected: ['按订单行（推荐）'], other: '', skipped: false },
            { header: '口径', selected: [], other: '', skipped: true },
          ],
        }),
      },
    })

    expect(wrapper.get('.dac-q-summary').text()).toBe('等待超时，已按推荐选择 1 项 · 按订单行')
    expect(wrapper.findAll('.dac-q-result li')).toHaveLength(0)
  })

  it('reads back the answers as one line and expands into details', async () => {
    const wrapper = mount(QuestionCard, {
      props: {
        block: block({
          answered: true,
          source: 'user',
          answers: [
            { header: '粒度', selected: ['按订单行（推荐）'], other: '', skipped: false },
            { header: '口径', selected: [], other: '', skipped: true },
            { header: '维度', selected: ['商品'], other: '还要会员', skipped: false },
          ],
        }),
      },
    })

    expect(wrapper.get('.dac-q-summary').text()).toBe('已补充 2 项 · 按订单行 · 商品、还要会员')
    await wrapper.get('.dac-q-result-head').trigger('click')
    const lines = wrapper.findAll('.dac-q-result li').map((li) => li.text())
    expect(lines).toEqual(['粒度按订单行', '口径已跳过', '维度商品、还要会员'])
  })
})
