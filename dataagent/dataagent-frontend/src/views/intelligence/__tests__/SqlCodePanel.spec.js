import { vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const apiMocks = vi.hoisted(() => ({
  executeSql: vi.fn()
}))

const clipboardMocks = vi.hoisted(() => ({
  copyText: vi.fn(() => Promise.resolve())
}))

vi.mock('../../../../packages/agent-conversation/src/utils/clipboard.js', () => ({
  copyText: clipboardMocks.copyText
}))

import SqlCodePanel from '../../../../packages/agent-conversation/src/ui/components/SqlCodePanel.vue'

const successResult = {
  kind: 'sql_execution',
  columns: ['cnt'],
  rows: [{ cnt: 7 }],
  row_count: 1,
  has_more: false,
  truncated_by_size: false,
  duration_ms: 9,
  result_state: 'success',
  error: null
}

const mountPanel = (props = {}, options = {}) => mount(SqlCodePanel, {
  props: {
    sql: 'SELECT COUNT(*) AS cnt FROM demo.t',
    database: 'demo',
    engine: 'mysql',
    ...props
  },
  global: {
    // The panel reaches the network only through the injected transport. A
    // transport without executeSql is the read-only case, covered below.
    provide: options.provide || { agentConversationTransport: { executeSql: apiMocks.executeSql } },
    stubs: {
      ResultDataTable: {
        props: ['columns', 'rows', 'title', 'meta'],
        template: '<div class="result-table-stub">{{ rows.length }}</div>'
      }
    }
  }
})

describe('SqlCodePanel', () => {
  beforeEach(() => {
    apiMocks.executeSql.mockReset()
    clipboardMocks.copyText.mockClear()
  })

  it('copies the current sql', async () => {
    const wrapper = mountPanel()
    await wrapper.find('[data-action="copy"]').trigger('click')
    expect(clipboardMocks.copyText).toHaveBeenCalledWith('SELECT COUNT(*) AS cnt FROM demo.t')
    expect(wrapper.find('[data-action="copy"]').text()).toBe('已复制')
  })

  it('toggles edit and revert state', async () => {
    const wrapper = mountPanel()
    expect(wrapper.find('[data-action="edit"]').exists()).toBe(true)
    await wrapper.find('[data-action="edit"]').trigger('click')
    expect(wrapper.find('[data-action="revert"]').exists()).toBe(true)
    await wrapper.find('[data-action="revert"]').trigger('click')
    expect(wrapper.find('[data-action="edit"]').exists()).toBe(true)
  })

  it('disables execute controls without a database and explains why', () => {
    const wrapper = mountPanel({ database: '' })
    const execute = wrapper.find('[data-action="execute"]')
    expect(execute.exists()).toBe(true)
    expect(execute.attributes('disabled')).toBeDefined()
    expect(execute.attributes('title')).toBe('缺少 database，无法执行')
    expect(wrapper.find('.sql-panel-limit').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('缺少 database')
  })

  it('executes sql and renders the result table', async () => {
    apiMocks.executeSql.mockResolvedValue(successResult)
    const wrapper = mountPanel()
    await wrapper.find('[data-action="execute"]').trigger('click')
    await flushPromises()

    expect(apiMocks.executeSql).toHaveBeenCalledWith({
      sql: 'SELECT COUNT(*) AS cnt FROM demo.t',
      database: 'demo',
      engine: 'mysql',
      limit: 100
    })
    expect(wrapper.find('.result-table-stub').text()).toBe('1')
    expect(wrapper.find('.sql-panel-error').exists()).toBe(false)
  })

  it('uses the injected transport and topic id when provided', async () => {
    const injectedExecuteSql = vi.fn().mockResolvedValue(successResult)
    const wrapper = mountPanel({}, {
      provide: {
        agentConversationTransport: { executeSql: injectedExecuteSql },
        nl2sqlTopicId: { value: 'topic-42' }
      }
    })

    await wrapper.find('[data-action="execute"]').trigger('click')
    await flushPromises()

    expect(injectedExecuteSql).toHaveBeenCalledWith({
      sql: 'SELECT COUNT(*) AS cnt FROM demo.t',
      database: 'demo',
      engine: 'mysql',
      limit: 100,
      topicId: 'topic-42'
    })
    expect(apiMocks.executeSql).not.toHaveBeenCalled()
  })

  it('passes the selected limit', async () => {
    apiMocks.executeSql.mockResolvedValue(successResult)
    const wrapper = mountPanel()
    await wrapper.find('.sql-panel-limit').setValue('500')
    await wrapper.find('[data-action="execute"]').trigger('click')
    await flushPromises()
    expect(apiMocks.executeSql.mock.calls[0][0].limit).toBe(500)
  })

  it('resets execution state when the query context changes', async () => {
    apiMocks.executeSql.mockResolvedValue(successResult)
    const wrapper = mountPanel()
    await wrapper.find('[data-action="execute"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.result-table-stub').exists()).toBe(true)

    await wrapper.setProps({ database: 'demo_2' })
    await flushPromises()
    expect(wrapper.find('.result-table-stub').exists()).toBe(false)
  })

  it('shows the error message when execution fails', async () => {
    apiMocks.executeSql.mockRejectedValue(new Error('仅允许只读 SQL'))
    const wrapper = mountPanel()
    await wrapper.find('[data-action="execute"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.sql-panel-error').text()).toBe('仅允许只读 SQL')
    expect(wrapper.find('.result-table-stub').exists()).toBe(false)
  })
})
