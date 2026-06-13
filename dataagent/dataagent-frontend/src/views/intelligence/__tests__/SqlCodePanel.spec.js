import { vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const apiMocks = vi.hoisted(() => ({
  executeSql: vi.fn()
}))

vi.mock('@/api/nl2sql', () => ({
  createNl2SqlApiClient: () => ({
    queryApi: { executeSql: apiMocks.executeSql }
  })
}))

const clipboardMocks = vi.hoisted(() => ({
  copyText: vi.fn(() => Promise.resolve())
}))

vi.mock('@/utils/clipboard', () => ({
  copyText: clipboardMocks.copyText
}))

import SqlCodePanel from '../components/SqlCodePanel.vue'

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

const mountPanel = (props = {}) => mount(SqlCodePanel, {
  props: {
    sql: 'SELECT COUNT(*) AS cnt FROM demo.t',
    database: 'demo',
    engine: 'mysql',
    ...props
  },
  global: {
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

  it('hides execute controls without a database', () => {
    const wrapper = mountPanel({ database: '' })
    expect(wrapper.find('[data-action="execute"]').exists()).toBe(false)
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

  it('passes the selected limit', async () => {
    apiMocks.executeSql.mockResolvedValue(successResult)
    const wrapper = mountPanel()
    await wrapper.find('.sql-panel-limit').setValue('500')
    await wrapper.find('[data-action="execute"]').trigger('click')
    await flushPromises()
    expect(apiMocks.executeSql.mock.calls[0][0].limit).toBe(500)
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
