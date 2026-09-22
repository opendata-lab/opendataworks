import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PermissionConfirmationCard from '../../../../packages/agent-conversation/src/ui/PermissionCard.vue'

const planBlock = (overrides = {}) => ({
  type: 'permission_request',
  requestId: 'req-1',
  tool_name: 'ExitPlanMode',
  risk_level: 'plan',
  title: '请确认执行计划',
  summary: [
    '## 目标',
    '新建订单明细表并接入日增量加工。',
    '',
    '### 步骤',
    '1. 建表 `dwd_user_order_di`',
    '2. 建 DML 任务',
    '',
    '- 分桶 10',
    '- 副本 3',
  ].join('\n'),
  payload_preview: { plan: 'x' },
  decision: 'pending',
  ...overrides,
})

const writeBlock = (overrides = {}) => ({
  type: 'permission_request',
  requestId: 'req-2',
  tool_name: 'mcp__portal__portal_create_table',
  risk_level: 'critical',
  title: '请确认操作',
  summary: '新建表 dwd_user_order_di',
  payload_preview: { dbName: 'dwd' },
  decision: 'pending',
  ...overrides,
})

describe('PermissionConfirmationCard plan rendering', () => {
  it('renders the plan body as markdown instead of raw text', () => {
    const wrapper = mount(PermissionConfirmationCard, { props: { block: planBlock() } })
    const body = wrapper.get('.v2-perm-plan')

    // Markdown structure is realized as elements, so the plan reads as a document.
    expect(body.findAll('h2').length).toBeGreaterThan(0)
    expect(body.findAll('li').length).toBeGreaterThan(0)
    expect(body.find('code').exists()).toBe(true)
    // ...and the raw markup is not shown to the user.
    expect(body.text()).not.toContain('## 目标')
    expect(body.text()).not.toContain('- 分桶 10')
    expect(body.text()).toContain('目标')
  })

  it('escapes HTML embedded in a model-authored plan', () => {
    const wrapper = mount(PermissionConfirmationCard, {
      props: { block: planBlock({ summary: 'before <img src=x onerror=alert(1)> after' }) },
    })
    const body = wrapper.get('.v2-perm-plan')
    expect(body.find('img').exists()).toBe(false)
    expect(body.text()).toContain('<img src=x onerror=alert(1)>')
  })

  it('labels the plan actions as approve/refine and keeps the plan after a decision', async () => {
    const wrapper = mount(PermissionConfirmationCard, { props: { block: planBlock() } })
    const buttons = wrapper.findAll('.v2-perm-btn').map((b) => b.text())
    expect(buttons).toContain('批准并执行')
    expect(buttons).toContain('继续完善')

    await wrapper.setProps({ block: planBlock({ decision: 'allow' }) })
    // The decided plan stays readable in the transcript; only the actions collapse.
    expect(wrapper.find('.v2-perm-plan').exists()).toBe(true)
    expect(wrapper.findAll('.v2-perm-btn')).toHaveLength(0)
    expect(wrapper.get('.v2-perm-result').text()).toContain('计划已批准')
  })

  it('emits the decision with the request id', async () => {
    const wrapper = mount(PermissionConfirmationCard, { props: { block: planBlock() } })
    const approve = wrapper.findAll('.v2-perm-btn').find((b) => b.text() === '批准并执行')
    await approve.trigger('click')
    expect(wrapper.emitted('decide')[0]).toEqual([{ requestId: 'req-1', decision: 'allow' }])
  })

  it('leaves non-plan confirmation cards as plain-text summaries with tool details', () => {
    const wrapper = mount(PermissionConfirmationCard, { props: { block: writeBlock() } })
    expect(wrapper.find('.v2-perm-plan').exists()).toBe(false)
    expect(wrapper.get('.v2-perm-summary').text()).toBe('新建表 dwd_user_order_di')
    expect(wrapper.get('.v2-perm-tool').text()).toContain('portal_create_table')
    const buttons = wrapper.findAll('.v2-perm-btn').map((b) => b.text())
    expect(buttons).toContain('允许')
    expect(buttons).toContain('拒绝')
  })
})
