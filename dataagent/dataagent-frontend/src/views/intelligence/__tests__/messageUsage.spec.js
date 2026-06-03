import { describe, expect, it } from 'vitest'
import { formatUsageFooter, normalizeUsage, usageMetaItems } from '../messageUsage'

describe('messageUsage', () => {
  it('returns null for empty usage payloads', () => {
    expect(normalizeUsage(null)).toBeNull()
    expect(normalizeUsage({})).toBeNull()
  })

  it('formats input, output and total tokens', () => {
    expect(usageMetaItems({
      input_tokens: 1200,
      output_tokens: 345
    })).toEqual(['输入 1,200', '输出 345', '总计 1,545'])
  })

  it('includes cache usage only when positive', () => {
    expect(usageMetaItems({
      input_tokens: 10,
      output_tokens: 5,
      cache_creation_input_tokens: 64,
      cache_read_input_tokens: 128
    })).toEqual([
      '输入 10',
      '输出 5',
      '总计 15',
      '缓存写入 64',
      '缓存命中 128'
    ])
  })

  it('formats compact footer text data for message bottom-right display', () => {
    expect(formatUsageFooter({
      input_tokens: 67,
      output_tokens: 282,
      cache_read_input_tokens: 91
    })).toEqual({
      total: '349',
      input: '67',
      output: '282',
      cacheRead: '91',
      cacheCreation: ''
    })
  })
})
