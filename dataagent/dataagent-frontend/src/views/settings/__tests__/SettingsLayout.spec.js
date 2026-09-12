import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

const authState = { enabled: true, isAdmin: true, currentUser: { display_name: 'tester' } }
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authState }))

const push = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ meta: { tab: 'skills' } }),
  useRouter: () => ({ push })
}))

import SettingsLayout from '../SettingsLayout.vue'

const stubs = {
  'el-icon': { template: '<i><slot /></i>' },
  'el-menu': { template: '<div><slot /></div>' },
  'el-menu-item': { props: ['index'], template: '<button><slot /></button>' },
  'router-view': { template: '<div />' }
}

const mountLayout = () => mount(SettingsLayout, { global: { stubs } })

describe('SettingsLayout', () => {
  beforeEach(() => {
    push.mockClear()
    authState.isAdmin = true
  })

  it('groups an admin\'s settings by what they configure', () => {
    const wrapper = mountLayout()
    const text = wrapper.text()

    expect(text).toContain('Agent 能力')
    expect(text).toContain('接入')
    expect(text).toContain('评测')
    for (const label of ['Skills', 'MCP 服务', '模型管理', 'Widget 接入', '评测集', '评测结果']) {
      expect(text).toContain(label)
    }
  })

  it('hides everything an ordinary user may not configure', () => {
    // Skills stay: a non-admin can read the catalogue. Models, MCP, widget
    // access and the evaluation pages are admin-only and must not even appear.
    authState.isAdmin = false
    const wrapper = mountLayout()
    const text = wrapper.text()

    expect(text).toContain('Skills')
    for (const hidden of ['模型管理', 'MCP 服务', 'Widget 接入', '评测集', '评测结果', '接入', '评测']) {
      expect(text).not.toContain(hidden)
    }
  })

  it('offers a way back to the workspace', async () => {
    const wrapper = mountLayout()
    await wrapper.find('.settings-back').trigger('click')

    expect(push).toHaveBeenCalledWith('/chat')
  })
})
