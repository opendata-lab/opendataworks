import { reactive } from 'vue'

// Synthetic coordinates from legacy projected snapshots are not stream IDs.
export const SYNTHETIC_BLOCK_POSITION = Symbol('syntheticBlockPosition')

export const blocksOf = (message) => {
  const live = message?._v2state?.blocks
  if (Array.isArray(live) && live.length) return live
  return Array.isArray(message?.blocks) ? message.blocks : []
}

const identityOf = (block) => {
  const id = [block.id, block.tool_use_id, block.tool_id, block.requestId, block.request_id]
    .find(value => value !== undefined && value !== null && value !== '')
  if (id !== undefined && id !== null && id !== '') return `${block.type}:id:${id}`
  if (!block[SYNTHETIC_BLOCK_POSITION] && block.blockIndex != null) {
    return `${block.type}:position:${block.turnIndex ?? 0}:${block.blockIndex}`
  }
  return null
}

/** UI-only state: survives snapshot replacement, never changes stream models. */
export function createBlockPresentation() {
  let previousMessage
  let previous = []
  let nextKey = 0
  const objectKeys = new WeakMap()
  const expanded = reactive(new Set())

  return {
    expanded,
    entriesFor(message) {
      const replaced = previousMessage && previousMessage !== message
      const oldByIdentity = new Map(previous.filter(entry => entry.identity).map(entry => [entry.identity, entry]))
      const oldByType = new Map()
      for (const entry of previous) {
        if (!oldByType.has(entry.block.type)) oldByType.set(entry.block.type, [])
        oldByType.get(entry.block.type).push(entry)
      }
      const ordinals = new Map()
      const entries = blocksOf(message).map(block => {
        const identity = identityOf(block)
        const ordinal = ordinals.get(block.type) || 0
        ordinals.set(block.type, ordinal + 1)
        let key = objectKeys.get(block) || oldByIdentity.get(identity)?.key
        // Only a replacement snapshot may use the legacy same-kind ordinal.
        // During streaming, inserting/reordering blocks keeps object/stream IDs.
        const old = oldByType.get(block.type)?.[ordinal]
        if (!key && replaced && old && (!identity || !old.identity)) key = old.key
        key ||= identity || `${block.type}:local:${++nextKey}`
        objectKeys.set(block, key)
        return { block, key, identity }
      })
      const retained = new Set(entries.map(entry => entry.key))
      for (const entry of previous) if (!retained.has(entry.key)) expanded.delete(entry.key)
      previousMessage = message
      previous = entries
      return entries
    },
  }
}
