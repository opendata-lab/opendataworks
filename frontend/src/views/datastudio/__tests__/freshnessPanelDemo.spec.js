// 演示环境下新鲜度页签与其他写操作一致：只读浏览样例结果，配置与检查入口禁用并说明原因。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { reactive, ref } from 'vue'

const { getFreshness, checkFreshness, saveFreshness, deleteFreshness } = vi.hoisted(() => ({
  getFreshness: vi.fn(),
  checkFreshness: vi.fn(),
  saveFreshness: vi.fn(),
  deleteFreshness: vi.fn(),
}))

vi.mock('@/api/table', () => ({
  tableApi: { getFreshness, checkFreshness, saveFreshness, deleteFreshness, freshnessHistory: vi.fn(() => Promise.resolve([])) },
}))
vi.mock('@/demo/runtime', () => ({ isDemoMode: true, showDemoReadonlyMessage: vi.fn() }))

import DataStudioRightPanelFreshness from '../components/DataStudioRightPanelFreshness.vue'

const passthrough = { template: '<div><slot name="label" /><slot /></div>' }
const ButtonStub = {
  props: ['disabled', 'loading', 'type', 'size', 'text'],
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
}

const mountDemo = (resp) => {
  getFreshness.mockResolvedValue(resp)
  return shallowMount(DataStudioRightPanelFreshness, {
    global: {
      provide: {
        dataStudioCtx: {
          activeTab: ref('t1'),
          tabStates: reactive({ t1: { table: { id: 1 } } }),
          formatDateTime: (v) => String(v ?? '-'),
        },
      },
      stubs: {
        ElButton: ButtonStub,
        ElScrollbar: passthrough,
        ElDescriptions: passthrough,
        ElDescriptionsItem: passthrough,
        ElTag: passthrough,
        ElEmpty: { props: ['description'], template: '<div class="empty">{{ description }}<slot /></div>' },
      },
      directives: { loading: {} },
      config: { warnHandler: () => {} },
    },
  })
}

const buttonByText = (wrapper, text) => wrapper.findAll('button').find((b) => b.text().trim() === text)

describe('DataStudioRightPanelFreshness 演示只读', () => {
  beforeEach(() => {
    getFreshness.mockReset()
    checkFreshness.mockReset()
    saveFreshness.mockReset()
  })

  it('已配置样例：立即检查与编辑禁用，历史可看，并标明是演示样例', async () => {
    const wrapper = mountDemo({
      configured: true,
      config: { mode: 'metadata' },
      effective: { mode: 'metadata', warnAfter: { count: 1, period: 'day' }, errorAfter: { count: 2, period: 'day' } },
      latestResult: { status: 'pass', ageSeconds: 1800, maxLoadedAt: 'a', createdAt: 'b' },
    })
    await flushPromises()

    expect(buttonByText(wrapper, '立即检查').attributes('disabled')).toBeDefined()
    expect(buttonByText(wrapper, '编辑').attributes('disabled')).toBeDefined()
    expect(buttonByText(wrapper, '历史').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('演示样例')

    await buttonByText(wrapper, '编辑').trigger('click')
    await flushPromises()
    // 仍是查看态：没有进入表单，也没有出现保存按钮
    expect(buttonByText(wrapper, '保存')).toBeUndefined()
    expect(checkFreshness).not.toHaveBeenCalled()
  })

  it('未配置样例：引导配置按钮同样禁用', async () => {
    const wrapper = mountDemo({ configured: false, config: null, effective: null, latestResult: null })
    await flushPromises()
    expect(buttonByText(wrapper, '配置新鲜度').attributes('disabled')).toBeDefined()
    expect(buttonByText(wrapper, '配置').attributes('disabled')).toBeDefined()
  })
})
