import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ listTokens: vi.fn(), createToken: vi.fn(), revokeToken: vi.fn() }))
const confirm = vi.hoisted(() => vi.fn())
vi.mock('@/api/users', () => ({ usersApi: api }))
vi.mock('element-plus', async original => ({ ...(await original()), ElMessageBox: { confirm }, ElMessage: { success: vi.fn(), warning: vi.fn() } }))
import ServiceAccountTokens from '../components/ServiceAccountTokens.vue'

const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const row = { id: 11, name: '生产', prefix: 'odw_sa_prefix', expiresAt: '2099-01-01', lastUsedAt: null }
async function view(enabled = true) {
  const wrapper = mount(ServiceAccountTokens, { props: { modelValue: true, account: { id: 7, nickname: '问数', enabled } }, global: {
    plugins: [ElementPlus], stubs: { 'el-dialog': { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' } }
  } })
  await flushPromises()
  return wrapper
}

describe('ServiceAccountTokens', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listTokens.mockResolvedValue([row])
    api.createToken.mockResolvedValue({ id: 12, token: 'test-only-secret' })
    api.revokeToken.mockResolvedValue({})
    confirm.mockResolvedValue('confirm')
  })

  it('签发明文仅本次展示，关闭再打开只加载元数据', async () => {
    const wrapper = await view()
    wrapper.vm.draft.name = '  新集成  '
    await wrapper.vm.createToken()
    expect(api.createToken).toHaveBeenCalledWith(7, { name: '新集成', expiresInDays: 90 })
    expect(wrapper.find('[data-testid="token-reveal"]').exists()).toBe(true)
    await wrapper.setProps({ modelValue: false })
    expect(wrapper.vm.generatedToken).toBe('')
    await wrapper.setProps({ modelValue: true })
    expect(wrapper.vm.generatedToken).toBe('')
    expect(api.createToken).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('停用账号不能签发但仍可撤销；取消撤销不发送请求', async () => {
    const wrapper = await view(false)
    wrapper.vm.draft.name = 'disabled'
    await wrapper.vm.createToken()
    expect(api.createToken).not.toHaveBeenCalled()
    confirm.mockRejectedValueOnce('cancel')
    await wrapper.vm.revokeToken(row)
    expect(api.revokeToken).not.toHaveBeenCalled()
    await wrapper.vm.revokeToken(row)
    expect(api.revokeToken).toHaveBeenCalledWith(7, 11)
    wrapper.unmount()
  })

  it('晚到的签发响应不能把旧账号 Token 展示给切换后的账号', async () => {
    const pending = deferred()
    api.createToken.mockReturnValueOnce(pending.promise)
    const wrapper = await view()
    wrapper.vm.draft.name = 'slow'
    const task = wrapper.vm.createToken()
    await wrapper.setProps({ account: { id: 8, nickname: '另一个账号', enabled: true } })
    pending.resolve({ token: 'old-account-secret' })
    await task
    expect(wrapper.vm.generatedToken).toBe('')
    expect(wrapper.text()).not.toContain('old-account-secret')
    wrapper.unmount()
  })

  it('撤销确认期间切换账号不撤销另一账号的凭据', async () => {
    const pending = deferred()
    confirm.mockReturnValueOnce(pending.promise)
    const wrapper = await view()
    const task = wrapper.vm.revokeToken(row)
    await wrapper.setProps({ account: { id: 8, enabled: true } })
    pending.resolve('confirm')
    await task
    expect(api.revokeToken).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('失败可重试，缺明文不提示创建成功，非法有效期不发送', async () => {
    api.listTokens.mockRejectedValueOnce(new Error('列表不可用'))
    const wrapper = await view()
    expect(wrapper.vm.listError).toBe('列表不可用')
    await wrapper.vm.loadTokens()
    expect(wrapper.vm.listError).toBe('')
    wrapper.vm.draft.name = 'test'
    wrapper.vm.draft.expiresInDays = 0
    await wrapper.vm.createToken()
    expect(api.createToken).not.toHaveBeenCalled()
    wrapper.vm.draft.expiresInDays = 90
    api.createToken.mockRejectedValueOnce(new Error('签发失败'))
    await wrapper.vm.createToken()
    expect(wrapper.vm.issueError).toBe('签发失败')
    api.createToken.mockResolvedValueOnce({ id: 13 })
    await wrapper.vm.createToken()
    expect(wrapper.vm.generatedToken).toBe('')
    expect(wrapper.vm.issueError).toContain('未返回 Token 明文')
    wrapper.unmount()
  })
})
