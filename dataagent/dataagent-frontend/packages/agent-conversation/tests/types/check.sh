#!/usr/bin/env bash
# Type-check the consumer fixture against React 18 and React 19.
#
# The two disagree about where JSX.IntrinsicElements lives: React 19 moved it
# under the React namespace and its transform no longer consults the global
# one. A declaration that satisfies either version alone therefore ships broken
# for the other, silently — which is exactly what happened, and what this
# catches.
set -euo pipefail

PKG_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp -r "$PKG_DIR/tests/types" "$WORK/fixture"
printf '{"name":"agent-conversation-typecheck","private":true,"type":"module"}' > "$WORK/package.json"

check_react() {
  local version="$1"
  echo "==> React ${version}"
  (
    cd "$WORK"
    npm install --silent --no-audit --no-fund "react@${version}" "@types/react@${version}" typescript@5 >/dev/null

    # Stand the package's declarations up as a resolvable module. npm install
    # wipes hand-made node_modules entries, so this has to come after it.
    mkdir -p node_modules/@opendataworks/agent-conversation
    cp -r "$PKG_DIR/types" node_modules/@opendataworks/agent-conversation/
    printf '{"name":"@opendataworks/agent-conversation","version":"0.0.0","types":"./types/index.d.ts","exports":{".":{"types":"./types/index.d.ts"}}}' \
      > node_modules/@opendataworks/agent-conversation/package.json

    npx --yes tsc -p fixture/tsconfig.json
  )
  echo "    ok"
}

check_react 18
check_react 19
echo "Both React majors type-check."
