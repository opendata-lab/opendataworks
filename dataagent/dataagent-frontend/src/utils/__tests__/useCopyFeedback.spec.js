import { effectScope, nextTick } from 'vue'

import { useCopyFeedback } from '../useCopyFeedback'

const copyTextMock = vi.fn()
vi.mock('../../../packages/agent-conversation/src/utils/clipboard.js', () => ({
  copyText: (value) => copyTextMock(value)
}))

describe('useCopyFeedback', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    copyTextMock.mockReset().mockResolvedValue(undefined)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('marks a key copied then clears it after the duration', () => {
    const { copiedKey, markCopied } = useCopyFeedback(1500)
    expect(copiedKey.value).toBe('')

    markCopied('md')
    expect(copiedKey.value).toBe('md')

    vi.advanceTimersByTime(1499)
    expect(copiedKey.value).toBe('md')
    vi.advanceTimersByTime(1)
    expect(copiedKey.value).toBe('')
  })

  it('re-clicking resets the timer instead of clearing early', () => {
    const { copiedKey, markCopied } = useCopyFeedback(1000)
    markCopied('a')
    vi.advanceTimersByTime(800)
    markCopied('b')
    expect(copiedKey.value).toBe('b')
    vi.advanceTimersByTime(800)
    expect(copiedKey.value).toBe('b')
    vi.advanceTimersByTime(200)
    expect(copiedKey.value).toBe('')
  })

  it('copyWithFeedback copies the text and flags the key', async () => {
    const { copiedKey, copyWithFeedback } = useCopyFeedback()
    await copyWithFeedback('SELECT 1', 'sql')
    expect(copyTextMock).toHaveBeenCalledWith('SELECT 1')
    expect(copiedKey.value).toBe('sql')
  })

  it('clears its pending timer when the owning scope is disposed', async () => {
    const scope = effectScope()
    let api
    scope.run(() => {
      api = useCopyFeedback(1000)
    })
    api.markCopied('x')
    expect(api.copiedKey.value).toBe('x')
    scope.stop()
    // The timer is cleared on dispose; advancing time must not throw or mutate.
    vi.advanceTimersByTime(2000)
    await nextTick()
    expect(api.copiedKey.value).toBe('x')
  })
})
