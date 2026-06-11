import { shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routerReplace = vi.hoisted(() => vi.fn())
const routeState = vi.hoisted(() => ({
  path: '/settings',
  query: {}
}))

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal()),
  useRoute: () => routeState,
  useRouter: () => ({
    replace: routerReplace
  })
}))

import ConfigurationManagement from '../ConfigurationManagement.vue'

const stubs = {
  DolphinConfig: { template: '<div>Dolphin 配置内容</div>' },
  MinioConfigManagement: { template: '<div>MinIO 环境内容</div>' },
  'el-card': { template: '<section><slot /></section>' },
  'el-tabs': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<div><slot /></div>'
  },
  'el-tab-pane': {
    props: ['label', 'name'],
    template: '<div class="tab-pane-stub">{{ label }}<slot /></div>'
  }
}

const mountView = (query = {}) => {
  routeState.query = query
  return shallowMount(ConfigurationManagement, {
    global: { stubs }
  })
}

describe('ConfigurationManagement', () => {
  beforeEach(() => {
    routerReplace.mockReset()
    routeState.query = {}
  })

  it('keeps only infrastructure configuration tabs under settings', () => {
    const wrapper = mountView()

    expect(wrapper.text()).toContain('Dolphin 配置')
    expect(wrapper.text()).toContain('MinIO 环境')
    expect(wrapper.text()).not.toContain('模型服务')
    expect(wrapper.text()).not.toContain('Skill 列表')
  })

  it('redirects legacy Skill and model query tabs to intelligent query', () => {
    mountView({ tab: 'skills' })
    expect(routerReplace).toHaveBeenCalledWith({
      path: '/intelligent-query',
      query: {}
    })

    routerReplace.mockReset()
    mountView({ tab: 'dataagent' })
    expect(routerReplace).toHaveBeenCalledWith({
      path: '/intelligent-query',
      query: {}
    })
  })
})
