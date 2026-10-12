import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AiAssistantTrigger from '../AiAssistantTrigger.vue'
import { FLOATING_WIDGET_KEY } from '@/utils/floatingWidget'

let wrapper
beforeEach(() => { vi.useFakeTimers() })
afterEach(() => { wrapper?.unmount(); vi.useRealTimers(); vi.unstubAllGlobals() })

describe('AiAssistantTrigger', () => {
  it('rotates caller prompts, pauses on hover/focus, and passes the complete active draft', async () => {
    const open = vi.fn()
    wrapper = mount(AiAssistantTrigger, { props: { prompts: ['第一条', '完整第二条场景提示'], openAssistant: open } })
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.find('button').attributes('aria-label')).toContain('完整第二条场景提示')
    await wrapper.find('button').trigger('mouseenter')
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.find('button').attributes('aria-label')).toContain('完整第二条场景提示')
    await wrapper.find('button').trigger('mouseleave')
    await wrapper.find('button').trigger('focus')
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.find('button').attributes('aria-label')).toContain('完整第二条场景提示')
    await wrapper.find('button').trigger('click')
    expect(open).toHaveBeenCalledWith('完整第二条场景提示')
    await flushPromises()
    expect(wrapper.attributes('data-state')).toBe('success')
    await wrapper.setProps({ prompts: ['换页后的提示'] })
    expect(wrapper.find('button').attributes('aria-label')).toContain('换页后的提示')
  })

  it('uses the provided instance, prevents duplicate opens and allows explicit retry after failure', async () => {
    let rejectOpen
    const open = vi.fn().mockImplementationOnce(() => new Promise((_, reject) => { rejectOpen = reject })).mockResolvedValue(undefined)
    const isOpen = ref(false)
    wrapper = mount(AiAssistantTrigger, { props: { prompts: ['草稿'] }, global: { provide: { [FLOATING_WIDGET_KEY]: { open, isOpen } } } })
    await wrapper.find('button').trigger('click')
    expect(wrapper.attributes('data-state')).toBe('loading')
    await wrapper.find('button').trigger('click')
    expect(open).toHaveBeenCalledTimes(1)
    rejectOpen(new Error('load failed'))
    await flushPromises()
    expect(wrapper.attributes('data-state')).toBe('error')
    expect(wrapper.find('[role="status"]').text()).toContain('点击重试')
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(open).toHaveBeenCalledTimes(2)
    isOpen.value = true
    await nextTick()
    expect(wrapper.find('button').attributes('aria-expanded')).toBe('true')
    isOpen.value = false
    await nextTick()
    expect(wrapper.attributes('data-state')).toBe('default')
  })

  it('pauses for reduced motion and a hidden page, and is disabled without an opener', async () => {
    let mediaChange
    const media = { matches: true, addEventListener: (_, cb) => { mediaChange = cb }, removeEventListener: vi.fn() }
    vi.stubGlobal('matchMedia', () => media)
    wrapper = mount(AiAssistantTrigger, { props: { prompts: ['一', '二'], openAssistant: vi.fn() } })
    await vi.advanceTimersByTimeAsync(10000)
    expect(wrapper.find('button').attributes('aria-label')).toContain('一')
    media.matches = false
    mediaChange()
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.find('button').attributes('aria-label')).toContain('一')
    wrapper.unmount()
    wrapper = mount(AiAssistantTrigger)
    expect(wrapper.find('button').element.disabled).toBe(true)
    expect(wrapper.attributes('data-state')).toBe('disabled')
    vi.restoreAllMocks()
  })
})
