# mock BFF

A ~120 line reference implementation of the host BFF protocol, with a page that
embeds the element against it. No DataAgent involved.

```sh
cd ../..                 # packages/agent-conversation
npm --prefix ../.. run build:sdk
node examples/mock-bff/server.mjs
open http://127.0.0.1:8787/
```

What it is good for:

- **Reading.** It is the shortest complete statement of the protocol; the
  contract itself is in `docs/bff-protocol.md`.
- **Watching the stream.** Sending scripts a short run: named `agent-event`
  frames, a keep-alive, then `done`.
- **Exercising recovery.** Kill the process mid-run. The element reports
  `stream_interrupted` and reconnects on a backoff rather than treating the
  dropped connection as a finished run — the distinction the named-frame
  protocol exists to make.
- **Seeing the host slot work.** "开始建模" lives in the page, sits in the
  composer footer, and sends with `metadata: { mode: 'model' }`, which comes
  back on `dataagent-complete`.
