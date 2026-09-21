import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, dirname, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = join(dirname(fileURLToPath(import.meta.url)), '..')

// The package must never reach the host application's modules. `@/api/nl2sql`
// in particular would hard-wire the SDK to DataAgent's URLs and headers, which
// is the one thing the transport boundary exists to prevent: consumers proxy
// through their own backend and the browser never learns the DataAgent address.
//
// Upstream this was meant to be an eslint rule, but dataagent-frontend has no
// eslint setup (only the separate `frontend/` app does). A test enforces the
// same invariant without dragging a new toolchain into the package.
const FORBIDDEN = [
  { pattern: /from\s+['"]@\/api\//, reason: "imports the host app's API client" },
  { pattern: /from\s+['"]@\/views\//, reason: 'imports host app view modules' },
  { pattern: /from\s+['"]@\/(?!.*agent-conversation)/, reason: "uses the host app's '@' alias" }
]

function sourceFiles(dir) {
  const out = []
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      if (entry === '__tests__' || entry === 'node_modules') continue
      out.push(...sourceFiles(full))
    } else if (/\.(js|ts|vue)$/.test(entry)) {
      out.push(full)
    }
  }
  return out
}

describe('package boundary', () => {
  const files = sourceFiles(SRC)

  it('finds source files to check', () => {
    expect(files.length).toBeGreaterThan(0)
  })

  it.each(FORBIDDEN)('no source file $reason', ({ pattern, reason }) => {
    const offenders = files
      .filter((file) => pattern.test(readFileSync(file, 'utf8')))
      .map((file) => relative(SRC, file))

    expect(offenders, `${offenders.join(', ')} ${reason}`).toEqual([])
  })
})
