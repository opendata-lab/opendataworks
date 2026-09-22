/**
 * Compile-only fixture: what a TypeScript consumer actually writes.
 *
 * It is type-checked against React 18 and React 19 separately, because the two
 * disagree about where JSX.IntrinsicElements lives — a declaration that works
 * under one silently fails under the other, which is how the element's JSX
 * typing shipped broken for React 19.
 */
import { useEffect, useRef } from 'react'
import {
  defineAgentConversation,
  ErrorCode,
  type AgentConversationElement,
  type ConversationTransport,
  type RunStatus,
} from '@opendataworks/agent-conversation'

defineAgentConversation()

export function Consumer({ endpoint }: { endpoint: string }) {
  const ref = useRef<AgentConversationElement>(null)

  useEffect(() => {
    const el = ref.current
    if (!el) return

    const onError = (event: Event) => {
      const { code } = (event as CustomEvent<{ code: RunStatus | string }>).detail
      // ErrorCode must exist as a *value*, not only a type: comparing against
      // it is the whole reason a consumer imports it.
      if (code === ErrorCode.STREAM_INTERRUPTED) return
      if (code === ErrorCode.TRANSPORT_UNREACHABLE) return
    }

    el.addEventListener('dataagent-error', onError)
    return () => el.removeEventListener('dataagent-error', onError)
  }, [])

  // Properties that are only settable as JS properties, never attributes.
  useEffect(() => {
    const el = ref.current
    if (!el) return
    el.endpointResolver = async () => endpoint
    el.transportFactory = (address: string): ConversationTransport =>
      ({}) as ConversationTransport
    el.value = 'draft'
    void el.sendMessage('hello', { metadata: { mode: 'model' } })
    void el.cancel()
    void el.reload()
    el.focus()
  }, [endpoint])

  return (
    <dataagent-conversation ref={ref} endpoint={endpoint} placeholder="ask">
      <button slot="composer-actions">开始建模</button>
    </dataagent-conversation>
  )
}
