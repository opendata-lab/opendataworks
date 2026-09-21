# @opendataworks/agent-conversation

Embeddable agent conversation UI for the OpenDataWorks DataAgent runtime, shipped
as a framework-agnostic custom element.

> **Status: T1 scaffold.** The element registers, attaches a shadow root and
> projects host slots. The conversation kernel, composer and message list land in
> T2/T3 — see `docs/plans/2026-09-21-agent-conversation-sdk-plan.md`.

## Install

```sh
npm i @opendataworks/agent-conversation
```

No stylesheet import is needed. Styles are compiled into the bundle and injected
into the element's shadow root, so they neither leak into nor inherit from the
host page.

## Use

```js
import { defineAgentConversation } from '@opendataworks/agent-conversation'

defineAgentConversation() // idempotent; safe at module scope
```

```html
<dataagent-conversation endpoint="/api/v1/workspaces/w-1/sessions/s-1/agent-conversation">
  <button slot="composer-actions">开始建模</button>
</dataagent-conversation>
```

### The one rule worth internalising

**`endpoint` is the conversation key.** Setting a new value aborts the current
stream, clears state and loads the new conversation. Switching conversations by
any other means — replacing a transport, calling `reload()` — is not supported
and will leave you on the old conversation.

`reload()` refetches the *current* conversation. It is not a switch.

## Architecture

The element never talks to DataAgent directly. All network access goes through a
transport, and the default transport speaks a small HTTP protocol against **your
own backend**:

```
browser → <dataagent-conversation> → transport → your BFF → DataAgent
```

Your backend holds the DataAgent address and credentials; the browser never sees
either. The six endpoints your BFF must implement are documented in
`docs/bff-protocol.md`.

For a host that needs to defer creating the backing conversation until the user
actually sends something, leave `endpoint` empty and set `endpointResolver`; it
is consulted once, on first send.

## Development

From `dataagent/dataagent-frontend`:

```sh
npm run test:sdk    # vitest
npm run build:sdk   # ES + UMD into packages/agent-conversation/dist
```

From `dataagent/dataagent-frontend/packages/agent-conversation`:

```sh
npm pack --dry-run  # verify tarball contents before publishing
```

## License

GPL-3.0-only. Note that this propagates to anything that bundles it.
