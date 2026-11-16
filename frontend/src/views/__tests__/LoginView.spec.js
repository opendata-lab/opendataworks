import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const routeState = reactive({ query: {} })
const routerReplace = vi.hoisted(() => vi.fn())
const optionsMock = vi.hoisted(() => vi.fn())
const loginMock = vi.hoisted(() => vi.fn())
const meMock = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => ({ replace: routerReplace })
}))

vi.mock('@/api/auth', () => ({
  authApi: {
    options: optionsMock,
    login: loginMock,
    me: meMock,
    logout: vi.fn()
  }
}))

vi.mock('@/demo/runtime', () => ({
  isDemoMode: false
}))

import LoginView from '../LoginView.vue'
import { demoAdapter } from '@/demo/mockServer'
import { useAuthStore } from '@/stores/auth'

const stubs = {
  'el-card': { template: '<div class="el-card"><slot /></div>' },
  'el-alert': {
    props: ['title', 'type'],
    template: '<div class="el-alert" :data-type="type">{{ title }}</div>'
  },
  'el-form': {
    template: '<form><slot /></form>',
    methods: { validate: () => Promise.resolve(true) }
  },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-button': {
    emits: ['click'],
    template: '<button type="button" @click="$emit(\'click\', $event)"><slot /></button>'
  },
  'el-divider': { template: '<div class="el-divider"><slot /></div>' }
}

describe('LoginView', () => {
  const originalLocation = window.location

  beforeEach(() => {
    setActivePinia(createPinia())
    routeState.query = {}
    sessionStorage.clear()
    routerReplace.mockReset().mockResolvedValue(undefined)
    optionsMock.mockReset().mockResolvedValue({ localLogin: true, oauth: [] })
    loginMock.mockReset().mockResolvedValue({ id: '1', username: 'admin', authSource: 'local' })
    meMock.mockReset().mockResolvedValue(null)

    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...originalLocation, assign: vi.fn() }
    })
  })

  afterEach(() => {
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: originalLocation
    })
  })

  it('shows only password login when no OAuth provider is configured', async () => {
    const wrapper = mount(LoginView, { global: { stubs } })
    await flushPromises()

    expect(optionsMock).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.oauth-section').exists()).toBe(false)
    expect(wrapper.find('.el-alert').exists()).toBe(false)
    wrapper.unmount()
  })

  it('renders OAuth buttons and saves sanitized redirect before navigating', async () => {
    routeState.query = { redirect: '/datastudio?tableId=7' }
    optionsMock.mockResolvedValue({
      localLogin: true,
      oauth: [
        { id: 'sso', name: 'SSO' },
        { id: 'corp', name: '公司单点' }
      ]
    })

    const wrapper = mount(LoginView, { global: { stubs } })
    await flushPromises()

    const buttons = wrapper.findAll('.oauth-button')
    expect(buttons).toHaveLength(2)
    expect(buttons[0].text()).toBe('通过 SSO 登录')
    expect(buttons[1].text()).toBe('通过 公司单点 登录')

    await buttons[0].trigger('click')
    expect(sessionStorage.getItem('odw_login_redirect')).toBe('/datastudio?tableId=7')
    expect(window.location.assign).toHaveBeenCalledWith('/api/oauth2/authorization/sso')
    wrapper.unmount()
  })

  it('completes OAuth callback using sanitized sessionStorage redirect and clears it', async () => {
    routeState.query = { oauth: 'success' }
    sessionStorage.setItem('odw_login_redirect', '/workflows?tab=monitor')
    meMock.mockResolvedValue({ userId: '9', username: 'sso:u-1', authSource: 'sso' })

    const wrapper = mount(LoginView, { global: { stubs } })
    await flushPromises()

    expect(meMock).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem('odw_login_redirect')).toBeNull()
    expect(routerReplace).toHaveBeenCalledWith('/workflows?tab=monitor')
    wrapper.unmount()
  })

  it('sanitizes unsafe OAuth and password redirects to root', async () => {
    routeState.query = { oauth: 'success' }
    sessionStorage.setItem('odw_login_redirect', '//evil.example.com')
    meMock.mockResolvedValue({ userId: '9', username: 'sso:u-1', authSource: 'sso' })

    const oauthWrapper = mount(LoginView, { global: { stubs } })
    await flushPromises()
    expect(routerReplace).toHaveBeenLastCalledWith('/')
    oauthWrapper.unmount()

    routerReplace.mockReset()
    useAuthStore().$reset()
    routeState.query = { redirect: '/\\evil.example.com' }
    const pwdWrapper = mount(LoginView, { global: { stubs } })
    await flushPromises()
    await pwdWrapper.find('.login-button').trigger('click')
    await flushPromises()
    expect(routerReplace).toHaveBeenLastCalledWith('/')
    pwdWrapper.unmount()
  })

  it('shows failure alert when oauth=success has no active session', async () => {
    routeState.query = { oauth: 'success' }
    meMock.mockResolvedValue(null)

    const wrapper = mount(LoginView, { global: { stubs } })
    await flushPromises()

    expect(routerReplace).not.toHaveBeenCalled()
    expect(wrapper.find('.el-alert').text()).toContain('第三方登录失败')
    wrapper.unmount()
  })

  it.each([
    ['oauth_denied', '已取消授权'],
    ['oauth_account_disabled', '账号已停用，请联系管理员'],
    ['oauth_failed', '第三方登录失败，请重试或联系管理员'],
    ['unexpected_code', '第三方登录失败，请重试或联系管理员']
  ])('renders error alert for %s', async (errorCode, expectedText) => {
    routeState.query = { error: errorCode }

    const wrapper = mount(LoginView, { global: { stubs } })
    await flushPromises()

    expect(wrapper.find('.el-alert').text()).toBe(expectedText)
    wrapper.unmount()
  })

  it('demo mock /auth/options returns localLogin=true and empty oauth list', async () => {
    const response = await demoAdapter({ method: 'get', url: '/auth/options' })
    expect(response.status).toBe(200)
    expect(response.data).toEqual({
      code: 200,
      message: 'success',
      data: {
        localLogin: true,
        oauth: []
      }
    })
  })
})
