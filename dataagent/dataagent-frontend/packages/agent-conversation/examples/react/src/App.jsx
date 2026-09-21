import { useEffect, useRef, useState } from 'react'
import { defineAgentConversation } from '@opendataworks/agent-conversation'

// Idempotent, so module scope is fine even with fast refresh and StrictMode's
// double-invoked effects.
defineAgentConversation()

/**
 * The integration OntoFoundry will write, in miniature.
 *
 * Note what is absent: no SSE parsing, no run state machine, no stylesheet
 * import, and no Vue. The host supplies an endpoint and a button.
 */
export default function App() {
  const chatRef = useRef(null)
  const [sessionId, setSessionId] = useState('s-1')
  const [log, setLog] = useState([])

  // Derive the endpoint from the id, never from an object that loads
  // asynchronously — a value that briefly becomes empty reads as a
  // conversation switch and resets the element.
  const endpoint = `/api/conversation?session=${sessionId}`

  useEffect(() => {
    const el = chatRef.current
    if (!el) return

    const record = (name) => (event) =>
      setLog((entries) => [...entries.slice(-6), `${name} ${JSON.stringify(event.detail)}`])

    const handlers = ['ready', 'run-change', 'complete', 'error'].map((name) => {
      const handler = record(name)
      el.addEventListener(`dataagent-${name}`, handler)
      return [name, handler]
    })

    return () => {
      for (const [name, handler] of handlers) {
        el.removeEventListener(`dataagent-${name}`, handler)
      }
    }
  }, [])

  const startModeling = () => {
    const el = chatRef.current
    const text = el.value.trim() || '基于已选材料开始建模'
    // metadata is opaque to the SDK and comes back on dataagent-complete,
    // which is how a host tells "this run should refresh my panels" apart
    // from ordinary chat.
    el.sendMessage(text, { metadata: { mode: 'model' } })
  }

  return (
    <div style={{ maxWidth: 780, margin: '32px auto', fontFamily: 'Inter, sans-serif' }}>
      <div style={{ marginBottom: 12 }}>
        <button onClick={() => setSessionId((id) => (id === 's-1' ? 's-2' : 's-1'))}>
          切换会话（当前 {sessionId}）
        </button>
      </div>

      <div style={{ height: '70vh', border: '1px solid #e2e8f0', borderRadius: 12, overflow: 'hidden' }}>
        <dataagent-conversation
          ref={chatRef}
          endpoint={endpoint}
          placeholder="描述你的建模需求"
        >
          <button slot="composer-actions" onClick={startModeling}>开始建模</button>
        </dataagent-conversation>
      </div>

      <pre style={{ fontSize: 12, color: '#475569' }}>{log.join('\n')}</pre>
    </div>
  )
}
