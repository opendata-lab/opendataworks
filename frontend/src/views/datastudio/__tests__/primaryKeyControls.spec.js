import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { reactive, ref } from 'vue'
import ElementPlus from 'element-plus'
import Columns from '../components/DataStudioRightPanelColumns.vue'

vi.mock('@/demo/runtime', () => ({ isDemoMode: false }))
let wrapper
class Observer { observe() {} unobserve() {} disconnect() {} }
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals() })

describe('primary key controls', () => {
  it.each([
    ['MYSQL', 1, [true, true, false]],
    ['', 1, [true, true, false]], // 旧缓存从连接配置恢复类型
    ['MYSQL', 0, [false, false, false]],
    ['DORIS', 1, [true, true, true]],
  ])('%s synced=%s applies row-specific primary edit permissions', async (sourceType, isSynced, disabled) => {
    vi.stubGlobal('ResizeObserver', Observer)
    const rows = reactive([{ id: 1, fieldName: 'id', isPrimary: 1 }, { id: 2, fieldName: 'name', isPrimary: 0 }, { id: null, fieldName: 'new_key', isPrimary: 0 }])
    const ctx = {
      activeTab: ref('t1'), tabStates: reactive({ t1: { table: { sourceType, isSynced }, fieldsEditing: true } }),
      getTableSourceType: () => sourceType || 'MYSQL',
      isDorisTable: () => sourceType === 'DORIS', structureEditBlockedReason: () => (sourceType === 'POSTGRESQL' ? 'PostgreSQL 暂不支持在线修改表结构' : ''), isAggregateTable: () => false,
      getFieldRows: () => rows, addField: vi.fn(), removeField: vi.fn(), generateMetadata: vi.fn(),
    }
    wrapper = mount(Columns, { global: { plugins: [ElementPlus], provide: { dataStudioCtx: ctx } } })
    await flushPromises()
    const switches = wrapper.findAll('tbody input[role="switch"]')
    expect(switches).toHaveLength(6)
    disabled.forEach((value, i) => {
      expect(switches[i * 2 + 1].element.disabled).toBe(value)
      expect(switches[i * 2].element.disabled).toBe(false)
    })
  })
})
