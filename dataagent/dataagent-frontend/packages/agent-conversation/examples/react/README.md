# React consumer example

Proves the package works from a React host that has never heard of Vue.

```sh
# 1. build and pack the SDK
cd ../..                       # packages/agent-conversation
npm --prefix ../.. run build:sdk
npm pack

# 2. install the tarball here
cd examples/react
npm install
npm install ../../opendataworks-agent-conversation-0.1.0.tgz

# 3. run it against the mock BFF
node ../mock-bff/server.mjs &
npm run dev
```

What to check:

- The conversation renders and sends. No SSE parsing or run state machine in
  this app.
- `node -e "require('vue')"` fails here. Vue ships inside the package.
- No stylesheet is imported, yet the element is styled — the styles are
  injected into its shadow root.
- "开始建模" sits in the composer footer, is React-rendered, and its
  `metadata.mode` comes back on `dataagent-complete`.
- Switching sessions changes only `endpoint`; the element resets itself
  without the host calling `reload()`.
