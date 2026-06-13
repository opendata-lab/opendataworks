import { vi } from 'vitest'
import { mount } from '@vue/test-utils'

const exportMocks = vi.hoisted(() => ({
  downloadCsv: vi.fn()
}))

vi.mock('@/utils/tableExport', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, downloadCsv: exportMocks.downloadCsv }
})

const clipboardMocks = vi.hoisted(() => ({
  copyText: vi.fn(() => Promise.resolve())
}))

vi.mock('@/utils/clipboard', () => ({
  copyText: clipboardMocks.copyText
}))

import ResultDataTable from '../components/ResultDataTable.vue'

const columns = ['name', 'amount']
const rows = [
  { name: 'beta', amount: 30 },
  { name: 'alpha', amount: 5 },
  { name: 'gamma', amount: null }
]

const mountTable = (props = {}) => mount(ResultDataTable, {
  props: { columns, rows, ...props }
})

const bodyCellTexts = (wrapper, columnIndex) => wrapper
  .findAll('tbody tr')
  .map((tr) => tr.findAll('td')[columnIndex]?.text())
  .filter((text) => text !== undefined)

describe('ResultDataTable', () => {
  beforeEach(() => {
    exportMocks.downloadCsv.mockClear()
    clipboardMocks.copyText.mockClear()
  })

  it('renders rows with index column and NULL placeholder', () => {
    const wrapper = mountTable()
    expect(wrapper.findAll('tbody tr')).toHaveLength(3)
    expect(bodyCellTexts(wrapper, 0)).toEqual(['1', '2', '3'])
    expect(wrapper.find('.result-table-null').exists()).toBe(true)
  })

  it('cycles header sort asc -> desc -> none with numeric awareness and nulls last', async () => {
    const wrapper = mountTable()
    const amountHeader = wrapper.findAll('th.result-table-th')[1]

    await amountHeader.trigger('click')
    expect(bodyCellTexts(wrapper, 2)).toEqual(['5', '30', 'NULL'])

    await amountHeader.trigger('click')
    expect(bodyCellTexts(wrapper, 2)).toEqual(['30', '5', 'NULL'])

    await amountHeader.trigger('click')
    expect(bodyCellTexts(wrapper, 1)).toEqual(['beta', 'alpha', 'gamma'])
  })

  it('filters rows by global keyword', async () => {
    const wrapper = mountTable()
    await wrapper.find('.result-table-search').setValue('alp')
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
    expect(bodyCellTexts(wrapper, 1)).toEqual(['alpha'])
  })

  it('filters rows by column distinct values', async () => {
    const wrapper = mountTable()
    await wrapper.find('[data-filter-column="name"]').trigger('click')
    const options = wrapper.findAll('.result-table-filter-option')
    expect(options.length).toBe(3)
    await options[0].find('input').setChecked(true)
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
    expect(bodyCellTexts(wrapper, 1)).toEqual(['alpha'])
  })

  it('hides pagination at or below threshold and shows it above', () => {
    expect(mountTable().find('.result-table-pager').exists()).toBe(false)

    const manyRows = Array.from({ length: 45 }, (_, i) => ({ name: `n${i}`, amount: i }))
    const wrapper = mountTable({ rows: manyRows })
    expect(wrapper.find('.result-table-pager').exists()).toBe(true)
    expect(wrapper.findAll('tbody tr')).toHaveLength(20)
    expect(wrapper.find('.result-table-pager-info').text()).toBe('1 / 3')
  })

  it('exports filtered rows as csv', async () => {
    const wrapper = mountTable({ title: '趋势' })
    await wrapper.find('.result-table-search').setValue('alpha')
    await wrapper.find('[data-action="export-csv"]').trigger('click')
    expect(exportMocks.downloadCsv).toHaveBeenCalledTimes(1)
    const [base, cols, exportedRows] = exportMocks.downloadCsv.mock.calls[0]
    expect(base).toBe('趋势')
    expect(cols).toEqual(columns)
    expect(exportedRows).toHaveLength(1)
    expect(exportedRows[0].name).toBe('alpha')
  })

  it('copies markdown and tsv content', async () => {
    const wrapper = mountTable()
    await wrapper.find('[data-action="copy-markdown"]').trigger('click')
    expect(clipboardMocks.copyText.mock.calls[0][0]).toContain('| name | amount |')

    await wrapper.find('[data-action="copy-tsv"]').trigger('click')
    expect(clipboardMocks.copyText.mock.calls[1][0]).toContain('name\tamount')
  })

  it('shows truncation notice from meta', () => {
    const wrapper = mountTable({ meta: { hasMore: true } })
    expect(wrapper.find('.result-table-notice').text()).toContain('截断')
  })
})
