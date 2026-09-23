# @opendataworks/agent-conversation

Embeddable AI agent conversation Web Component for the OpenDataWorks DataAgent runtime, shipped as a framework-agnostic custom element (`<dataagent-conversation>`).

- **Framework-Agnostic**: Pure W3C Custom Element. Works seamlessly with React, Vue 3, Angular, Svelte, or plain Vanilla HTML.
- **Zero External Runtime Dependencies**: Runtime, message rendering, and styles are bundled internally. No external CSS stylesheet import needed. Styles are encapsulated in Shadow DOM without bleeding into or inheriting unwanted rules from the host page.
- **Built-in Conversation Kernel**: Streaming SSE decoder, run state machine, thought blocks that are collapsed by default, tool output rendering, permission/question interaction cards, and file attachments.
- **Secure BFF Proxy Architecture**: The browser element never talks directly to DataAgent or leaks API credentials. All communication proxies through your backend (BFF).
- **High Extensibility**: First-class CSS Custom Properties (`--dac-*`) design tokens, CSS Shadow Parts (`::part(...)`), flexible slot projections, and extensible transport layer with custom auth headers and credentials.

---

## Architecture

```text
Browser (<dataagent-conversation>) ──→ Transport ──→ Your BFF Backend ──→ DataAgent API
```

Your host backend holds the DataAgent address, access keys, and workspace routing; the browser never sees them. The element communicates with your BFF via a clean 6-endpoint protocol documented in [`docs/bff-protocol.md`](./docs/bff-protocol.md).

---

## Installation

```sh
npm install @opendataworks/agent-conversation
```

---

## Quick Starts

### 1. Vanilla HTML & JavaScript

```html
<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <title>Agent Conversation</title>
    <style>
      dataagent-conversation {
        display: block;
        height: 600px;
        border: 1px solid #e2e8f0;
        border-radius: 12px;
      }
    </style>
  </head>
  <body>
    <dataagent-conversation
      id="chat"
      endpoint="/api/v1/sessions/s-1/conversation"
      placeholder="输入你的分析需求..."
    >
      <button slot="composer-actions" id="btn-custom">快捷操作</button>
    </dataagent-conversation>

    <script type="module">
      import { defineAgentConversation } from '@opendataworks/agent-conversation'

      // Register custom element (idempotent, safe at module scope)
      defineAgentConversation()

      const chat = document.getElementById('chat')

      chat.addEventListener('dataagent-complete', (e) => {
        console.log('Run completed:', e.detail)
      })

      document.getElementById('btn-custom').addEventListener('click', () => {
        chat.sendMessage('请帮我分析最近 30 天的指标趋势', {
          metadata: { mode: 'quick-analysis' }
        })
      })
    </script>
  </body>
</html>
```

### 2. React 18 / 19

The package ships full TypeScript declarations (`JSX.IntrinsicElements` and `HTMLElementTagNameMap`), so `<dataagent-conversation>` works without type errors:

```tsx
import { useEffect, useRef, useState } from 'react'
import { defineAgentConversation, type AgentConversationElement } from '@opendataworks/agent-conversation'

defineAgentConversation()

export function AgentChat({ sessionId }: { sessionId: string }) {
  const chatRef = useRef<AgentConversationElement>(null)
  const [running, setRunning] = useState(false)

  // Derive endpoint from sessionId; changing endpoint automatically switches conversation
  const endpoint = `/api/v1/sessions/${sessionId}/conversation`

  useEffect(() => {
    const el = chatRef.current
    if (!el) return

    const handleRunChange = (e: CustomEvent) => {
      const active = ['queued', 'running', 'waiting_input', 'waiting_permission'].includes(e.detail.status)
      setRunning(active)
    }

    const handleComplete = (e: CustomEvent) => {
      console.log('Task finished:', e.detail.taskId, e.detail.metadata)
    }

    el.addEventListener('dataagent-run-change', handleRunChange as EventListener)
    el.addEventListener('dataagent-complete', handleComplete as EventListener)

    return () => {
      el.removeEventListener('dataagent-run-change', handleRunChange as EventListener)
      el.removeEventListener('dataagent-complete', handleComplete as EventListener)
    }
  }, [])

  const handleStartAnalysis = () => {
    chatRef.current?.sendMessage('开始智能建模', {
      metadata: { source: 'dashboard-quick-action' }
    })
  }

  return (
    <div style={{ height: '75vh' }}>
      <dataagent-conversation
        ref={chatRef}
        endpoint={endpoint}
        placeholder="描述需求或提出问题..."
      >
        <button
          slot="composer-actions"
          disabled={running}
          onClick={handleStartAnalysis}
        >
          开始建模
        </button>
      </dataagent-conversation>
    </div>
  )
}
```

### 3. Vue 3

In Vue 3, configure Vite/Vue compiler to recognize `<dataagent-conversation>` as a Custom Element:

```js
// vite.config.js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          isCustomElement: (tag) => tag.startsWith('dataagent-')
        }
      }
    })
  ]
})
```

Then use it inside any template:

```vue
<template>
  <div class="chat-container">
    <dataagent-conversation
      ref="chatRef"
      :endpoint="endpoint"
      placeholder="输入分析问题..."
      @dataagent-complete="onComplete"
    >
      <button slot="composer-actions" @click="sendPrompt">预置分析</button>
    </dataagent-conversation>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { defineAgentConversation } from '@opendataworks/agent-conversation'

defineAgentConversation()

const chatRef = ref(null)
const endpoint = ref('/api/v1/sessions/s-1/conversation')

const onComplete = (event) => {
  console.log('Completed:', event.detail)
}

const sendPrompt = () => {
  chatRef.value?.sendMessage('查看核心转化率趋势')
}
</script>
```

---

## Component API

### Attributes & Properties

| Name | Type | Attribute | Description |
| --- | --- | --- | --- |
| `endpoint` | `string` | `endpoint` | **The conversation key.** Setting or changing this value aborts current stream, clears state, and loads the new conversation. |
| `placeholder` | `string` | `placeholder` | Input placeholder text in composer. Default: `""`. |
| `active` | `boolean` | `active` | Default `true`. Set to `false` when conversation tab is hidden to release SSE connections and pause polling. |
| `disabled` | `boolean` | `disabled` | Disables input and submission while still allowing message browsing. |
| `value` | `string` | — | Getter & setter for current draft text in composer textarea. |
| `endpointResolver` | `(context) => string \| Promise<string>` | — | Lazy session resolver. If `endpoint` is empty, consulted on the first send, or on the first upload when `composerConfig.uploadBeforeConversation` is enabled. |
| `transportFactory` | `(endpoint: string) => ConversationTransport` | — | Factory creating custom transport for the given endpoint. Defaults to built-in HTTP transport. |

> [!IMPORTANT]
> **`endpoint` is the conversation key.** Setting a new `endpoint` string switches the conversation. Do not call `reload()` to switch conversations; `reload()` only refetches the *current* conversation.

### Public Methods

Access methods via DOM reference (`ref` or `document.getElementById`):

| Method | Signature | Description |
| --- | --- | --- |
| `sendMessage(content?, options?)` | `(content?: string, options?: SendOptions) => Promise<void>` | Sends a message. If `content` is omitted, sends the current draft `value`. `options.metadata` is echoed back on completion. |
| `reload()` | `() => Promise<void>` | Refetches the current conversation snapshot and restores the active run if any. |
| `cancel()` | `() => Promise<void>` | Cancels the currently executing agent task. No-op if idle. |
| `focus()` | `() => void` | Focuses the composer textarea. |
| `focusMessage(messageId)` | `(messageId: string) => Promise<boolean>` | Scrolls one message into view and highlights it briefly. Returns `false` when that message is not in this conversation. Use this for deep links instead of querying the shadow root, which couples the host to internal markup. |

### Optional Transport Capabilities

The element is capability-driven: a control appears only when the transport can
serve it. Omitting a method is the supported way to turn a feature off — nothing
is rendered that would silently discard the user's action.

| Transport method | When present | When absent |
| --- | --- | --- |
| `executeSql(input)` | SQL tool cards get a row-limit selector and an execute button | The query renders read-only — no controls at all |
| `setPermissionMode(mode)` | The permission picker persists the choice, rolling back if the call rejects | The picker still works; the mode only travels with each message |
| `submitFeedback({ messageId, feedback })` | 👍 / 👎 appear under finished answers; applied optimistically and rolled back if the call rejects | No rating buttons |
| `uploadFiles(files)` | The composer gets an attach button, upload state, and removable chips; references ride with the next message | No attach button |
| `readFile(relPath)` | Attachments get **预览** and **下载**: images through a revoked object URL, HTML inside `sandbox=""`, text files as plain text. Saving goes through this too, because a bare link cannot carry auth headers | Attachments stay plain links |

`uploadFiles` and message history use the same attachment shape:

```ts
interface ConversationAttachment {
  name: string
  relPath: string
  mediaType?: string
  size?: number // bytes; rendered beside the filename when present
}
```

### Composer configuration

Set the `composerConfig` property to add model, permission-mode, slash-command
and opener controls. Every field is optional, and an omitted one renders
nothing — the SDK draws the controls, the host decides which exist.

```js
el.composerConfig = {
  // The shapes the host already holds — pass them straight through.
  providers: [{ provider_id: 'anthropic', models: ['sonnet', 'opus'], default_model: 'sonnet', enabled: true }],
  default_provider_id: 'anthropic',
  default_model: 'opus',
  permissionModes: [{ value: 'default', label: 'Default', desc: '写操作前确认' }],
  permissionMode: 'default',
  slashCommands: buildCommands(['compact', 'clear']),
  suggestions: ['分析最近 30 天的订单趋势'],
  // DataAgent can provision a Topic before the first upload.
  uploadBeforeConversation: true
}
```

Omitting `providers` means the host does not require a model picker and can use
its own fixed backend model. Supplying the array makes it authoritative:
providers with `enabled: false` are excluded, and the composer cannot send when
no enabled provider offers a model.

The current selection is sent with each message as
`sendMessage({ content, settings: { provider_id, model, permission_mode } })`
rather than pushed separately, so what was sent and what the user could see
cannot disagree. Suggestions are offered only while the conversation is empty.
When lazy creation is enabled, the resolver receives either
`{ reason: 'send', content, settings }` or `{ reason: 'upload', files }`; this
lets the host title a new conversation from the real first prompt and create a
workspace before uploading without inventing a second provisional transport.

### DOM Events

All events bubble and compose across Shadow DOM (`bubbles: true, composed: true`):

| Event | `event.detail` | Description |
| --- | --- | --- |
| `dataagent-ready` | `{}` | Emitted when conversation snapshot finishes loading. |
| `dataagent-draft-change` | `{ value: string }` | Emitted whenever user edits the input text. |
| `dataagent-run-change` | `{ taskId: string, status: RunStatus, detail: string }` | Emitted when the task status changes, including live `waiting_permission` / `waiting_input` transitions and their return to `running`. |
| `dataagent-complete` | `{ taskId: string, status: RunStatus, metadata?: object }` | Emitted when a run reaches terminal status. |
| `dataagent-error` | `{ code: ErrorCode, message: string, hint?: string }` | Emitted when transport or protocol errors occur. |

### Slots

| Slot Name | Placement | Typical Use Case |
| --- | --- | --- |
| `composer-actions` | Inside composer footer, left of send button | Host action buttons (e.g. "Start Modeling", "Quick Prompt"). |
| `composer-toolbar` | Below composer footer, full-width row | Host extra controls (e.g. model selector, permission toggles). |
| `composer-overlay` | Above composer textarea | Floating menus (e.g. slash command popovers). |
| `empty` | Message area when empty | Custom empty state illustration or welcome guide. |

---

## Theming & Styling

`<dataagent-conversation>` supports two standard W3C mechanisms to customize styles inside Shadow DOM:

### 1. CSS Custom Properties (Design Tokens)

Set CSS variables on `<dataagent-conversation>` or any parent container to easily match your application theme:

```css
dataagent-conversation {
  --dac-primary: #3b82f6;               /* Brand primary color */
  --dac-primary-hover: #2563eb;         /* Hover state */
  --dac-bg: #ffffff;                    /* Container background */
  --dac-border-color: #e2e8f0;          /* Border and dividers */
  --dac-bubble-radius: 14px;            /* Message bubble corners */
  --dac-user-bubble-bg: #eff6ff;        /* User bubble background */
  --dac-assistant-bubble-bg: #f8fafc;   /* Assistant bubble background */
}
```

#### Supported CSS Variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `--dac-primary` | `#10b981` | Interactive / brand color (send button, focus borders) |
| `--dac-primary-hover` | `#059669` | Interactive hover state |
| `--dac-bg` | `#ffffff` | Component root background |
| `--dac-font-family` | `system-ui, -apple-system, sans-serif` | Global font family |
| `--dac-font-size` | `14px` | Global font size |
| `--dac-text-color` | `#0f172a` | Main text color |
| `--dac-text-muted` | `#64748b` | Subdued text (timestamps, status detail) |
| `--dac-border-color` | `#e2e8f0` | Dividers and frame borders |
| `--dac-bubble-radius` | `14px` | Message bubble border radius |
| `--dac-assistant-bubble-bg` | `#f1f5f9` | Assistant message bubble background |
| `--dac-assistant-bubble-color` | `inherit` | Assistant message bubble text color |
| `--dac-user-bubble-bg` | `#ecfdf5` | User message bubble background |
| `--dac-user-bubble-color` | `#065f46` | User message bubble text color |
| `--dac-composer-bg` | `transparent` | Composer container background |
| `--dac-input-bg` | `#ffffff` | Textarea input background |
| `--dac-input-color` | `inherit` | Textarea text color |
| `--dac-input-border` | `#cbd5e1` | Textarea border color |
| `--dac-input-radius` | `10px` | Textarea border radius |
| `--dac-send-color` | `#ffffff` | Send button text color |
| `--dac-disabled-bg` | `#cbd5e1` | Disabled send button background |
| `--dac-stop-bg` | `#f1f5f9` | Stop button background |
| `--dac-button-radius` | `8px` | Button border radius |

### 2. CSS Shadow Parts (`::part(...)`)

Target specific internal elements with `::part(...)` selectors for pixel-level style overrides:

```css
/* Custom send button styling */
dataagent-conversation::part(send-button) {
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
  font-weight: 700;
}

/* Custom assistant bubble appearance */
dataagent-conversation::part(assistant-bubble) {
  border: 1px solid #e2e8f0;
}

/* Custom composer textarea */
dataagent-conversation::part(input) {
  font-family: 'Fira Code', monospace;
}
```

#### Available Parts

- **Containers & List**: `root`, `messages`, `empty`
- **Messages & Content**: `message`, `message-user`, `message-assistant`, `user-turn`, `assistant-turn`, `bubble`, `user-bubble`, `assistant-bubble`, `thinking`, `thinking-toggle`, `thinking-content`, `attachments`, `attachment-size`, `activity`
- **Composer & Controls**: `composer`, `input`, `footer`, `actions`, `controls`, `run-detail`, `send-button`, `stop-button`

---

## Custom Transport & Authentication

By default, the element calls your BFF using `createHttpTransport`. When your BFF requires Bearer tokens, custom tenant headers, or cross-origin credentials, configure `transportFactory`:

```ts
import { createHttpTransport } from '@opendataworks/agent-conversation'

const chat = document.querySelector('dataagent-conversation')

chat.transportFactory = (endpoint) => {
  return createHttpTransport(endpoint, {
    // Custom headers (supports static object or async getter function)
    headers: async () => ({
      Authorization: `Bearer ${await getAuthToken()}`,
      'X-Tenant-Id': getCurrentTenantId()
    }),
    // Include cookies for cross-origin BFF
    credentials: 'include'
  })
}
```

---

## Run Status Lifecycle

| Status | Category | Meaning |
| --- | --- | --- |
| `idle` | Active | Initial state, awaiting first action. |
| `queued` | Active | Task accepted, waiting in scheduling queue. |
| `running` | Active | Agent is actively generating or executing tools. |
| `waiting_input` | Active | Agent has asked a clarifying question; awaiting user response. |
| `waiting_permission` | Active | Agent requested a sensitive tool invocation; awaiting user approval. |
| `finished` | Terminal | Task finished successfully. |
| `cancelled` | Terminal | Task was cancelled by user. |
| `failed` | Terminal | Task encountered an unrecoverable error. |

---

## Development & Testing

Run commands from `dataagent/dataagent-frontend`:

```sh
npm run test:sdk    # Run vitest for packages/agent-conversation
npm run build:sdk   # Build ES and UMD bundles into dist/
```

Verify tarball contents:

```sh
cd packages/agent-conversation
npm pack --dry-run
```

Explore runnable examples:
- `examples/mock-bff`: Pure Node.js mock BFF server and vanilla HTML preview.
- `examples/react`: Standalone React 18 application integrating the component.

---

## License

GPL-3.0-only.
