import { computed, ref, shallowRef, watch } from 'vue'
import { createHttpTransport } from '../transport/http.js'

/**
 * Owns the conversation address and everything that follows from it changing.
 *
 * `endpoint` is the conversation key. Setting a different one aborts the live
 * stream, clears state and loads the new conversation. That is the *only*
 * switch signal — an earlier design let hosts swap a transport object instead,
 * which left "does replacing the transport reset state?" undefined and had the
 * widget quietly keep talking to its previous topic.
 *
 * A host that has not created its conversation yet leaves `endpoint` empty and
 * supplies `endpointResolver`, which is consulted once, on the first operation
 * that needs a backing conversation.
 *
 * @param {object} options
 * @param {import('vue').Ref<string>} options.endpoint
 * @param {import('vue').Ref<((context?: object) => string | Promise<string>) | null>} options.endpointResolver
 * @param {import('vue').Ref<((endpoint: string) => object) | null>} options.transportFactory
 * @param {(reason: 'switch' | 'resolve' | 'reload') => void} options.onReset
 */
export function useEndpoint({ endpoint, endpointResolver, transportFactory, onReset }) {
  // Bumped on every switch. Anything in flight compares against it and drops
  // its result if it no longer matches, so a slow response from the previous
  // conversation cannot land in the new one.
  const generation = ref(0)
  const resolved = ref(String(endpoint?.value || ''))
  const transport = shallowRef(null)
  let resolving = false
  let resolution = null
  let endpointDuringResolution

  const buildTransport = (address) => {
    const factory = transportFactory?.value
    if (typeof factory === 'function') return factory(address)
    return createHttpTransport({ getEndpoint: () => resolved.value })
  }

  const adopt = (address, reason) => {
    resolved.value = address
    transport.value = address ? buildTransport(address) : null
    generation.value += 1
    onReset?.(reason)
  }

  watch(
    () => String(endpoint?.value || ''),
    (next) => {
      if (resolving) {
        // Defer deciding whether this is the resolver's expected host write or
        // a genuine user-initiated switch until the resolver returns its key.
        endpointDuringResolution = next
        return
      }
      if (next === resolved.value) return
      adopt(next, 'switch')
    }
  )

  if (resolved.value) transport.value = buildTransport(resolved.value)

  return {
    generation: computed(() => generation.value),
    resolved: computed(() => resolved.value),
    transport: computed(() => transport.value),

    /** True once there is somewhere to send to. */
    ready: computed(() => Boolean(resolved.value)),

    /**
     * Resolve the address for a send or enabled first upload. Consults
     * `endpointResolver` only when no endpoint is set, and adopts the result so
     * subsequent operations and the host's own re-render converge on the same
     * value.
     */
    async ensure(context) {
      if (resolved.value) return resolved.value
      if (resolution) return resolution
      const resolver = endpointResolver?.value
      if (typeof resolver !== 'function') return ''
      resolving = true
      endpointDuringResolution = undefined
      resolution = (async () => {
        try {
          const address = String((await resolver(context)) || '')
          const hostAddress = endpointDuringResolution
          // A different host address means the user switched conversations while
          // creation was in flight. Honor that switch and abort the original
          // send/upload instead of delivering it into the newly selected topic.
          if (hostAddress !== undefined && hostAddress !== address) {
            if (hostAddress !== resolved.value) adopt(hostAddress, 'switch')
            return ''
          }
          // A matching host write is the normal routing update for the Topic the
          // resolver just created. Adopt it exactly once and skip empty history.
          const next = hostAddress ?? address
          if (!resolved.value && next) adopt(next, 'resolve')
          return resolved.value || address
        } catch (error) {
          // A route switch remains authoritative even when the unrelated lazy
          // creation failed; the watcher will not fire a second time for the
          // already-written prop, so adopt it before rethrowing.
          const hostAddress = endpointDuringResolution
          if (hostAddress !== undefined && hostAddress !== resolved.value) {
            adopt(hostAddress, 'switch')
          }
          throw error
        }
      })().finally(() => {
        resolving = false
        endpointDuringResolution = undefined
        resolution = null
      })
      return resolution
    },

    /** Same conversation, fresh load. Deliberately does not clear the address. */
    reload() {
      generation.value += 1
      return onReset?.('reload')
    }
  }
}
