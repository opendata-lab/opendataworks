const toFiniteNumber = (value) => {
  const num = Number(value)
  return Number.isFinite(num) && num >= 0 ? num : null
}

const formatNumber = (value) => new Intl.NumberFormat('zh-CN').format(value)

export const normalizeUsage = (usage) => {
  if (!usage || typeof usage !== 'object' || Array.isArray(usage)) return null

  const inputTokens = toFiniteNumber(usage.input_tokens)
  const outputTokens = toFiniteNumber(usage.output_tokens)
  const cacheCreationTokens = toFiniteNumber(usage.cache_creation_input_tokens)
  const cacheReadTokens = toFiniteNumber(usage.cache_read_input_tokens)

  const hasAny = [inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens].some((value) => value !== null)
  if (!hasAny) return null

  const totalTokens = (inputTokens ?? 0) + (outputTokens ?? 0)

  return {
    inputTokens,
    outputTokens,
    cacheCreationTokens,
    cacheReadTokens,
    totalTokens
  }
}

export const usageMetaItems = (usage) => {
  const normalized = normalizeUsage(usage)
  if (!normalized) return []

  const items = []
  if (normalized.inputTokens !== null) items.push(`输入 ${formatNumber(normalized.inputTokens)}`)
  if (normalized.outputTokens !== null) items.push(`输出 ${formatNumber(normalized.outputTokens)}`)
  if (normalized.totalTokens > 0) items.push(`总计 ${formatNumber(normalized.totalTokens)}`)
  if ((normalized.cacheCreationTokens ?? 0) > 0) items.push(`缓存写入 ${formatNumber(normalized.cacheCreationTokens)}`)
  if ((normalized.cacheReadTokens ?? 0) > 0) items.push(`缓存命中 ${formatNumber(normalized.cacheReadTokens)}`)
  return items
}

export const formatUsageFooter = (usage) => {
  const normalized = normalizeUsage(usage)
  if (!normalized) return null

  return {
    total: formatNumber(normalized.totalTokens),
    input: normalized.inputTokens !== null ? formatNumber(normalized.inputTokens) : '',
    output: normalized.outputTokens !== null ? formatNumber(normalized.outputTokens) : '',
    cacheRead: (normalized.cacheReadTokens ?? 0) > 0 ? formatNumber(normalized.cacheReadTokens) : '',
    cacheCreation: (normalized.cacheCreationTokens ?? 0) > 0 ? formatNumber(normalized.cacheCreationTokens) : ''
  }
}
