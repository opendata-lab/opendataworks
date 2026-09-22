import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import * as api from '../index.js'
import { defineAgentConversation, DEFAULT_TAG } from '../element.js'

const PACKAGE_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const pkg = JSON.parse(readFileSync(join(PACKAGE_ROOT, 'package.json'), 'utf8'))
const types = readFileSync(join(PACKAGE_ROOT, 'types', 'index.d.ts'), 'utf8')

/**
 * What a consumer is allowed to rely on.
 *
 * Adding a name here is a deliberate act; removing or renaming one is a
 * breaking change. The list exists because the package has twice shipped a
 * capability that was declared and never connected — a type or a doc line is
 * not evidence that something works, but an export that must resolve is at
 * least evidence that it exists.
 */
const PUBLIC_API = [
  'defineAgentConversation',
  'DEFAULT_TAG',
  'createHttpTransport',
  'ConversationError',
  'ErrorCode',
  'StreamInterrupted',
  'toRunStatus',
  'ACTIVE_RUN_STATUSES',
  'TERMINAL_RUN_STATUSES'
]

describe('package contract', () => {
  it('exports exactly the documented surface', () => {
    expect(Object.keys(api).sort()).toEqual([...PUBLIC_API].sort())
  })

  it('exports values, not only types', () => {
    // `ErrorCode` shipped once as a type-only declaration with a runtime
    // export: TypeScript consumers compiled, and the import was undefined at
    // runtime. Every name here has to survive being used.
    for (const name of PUBLIC_API) {
      expect(api[name], `${name} is exported but undefined`).toBeDefined()
    }
  })

  it('declares every exported name in the published types', () => {
    for (const name of PUBLIC_API) {
      expect(types, `${name} is exported without a type declaration`).toContain(name)
    }
  })

  it('ships the entry points package.json promises', () => {
    expect(pkg.exports['.'].import).toBe('./dist/index.js')
    expect(pkg.exports['.'].types).toBe('./types/index.d.ts')
    // No `./style.css`: styles are compiled into the element and injected into
    // the shadow root, so a consumer never imports CSS.
    expect(Object.keys(pkg.exports)).toEqual(['.'])
  })

  it('registers the element under the documented tag', () => {
    expect(DEFAULT_TAG).toBe('dataagent-conversation')
    expect(defineAgentConversation()).toBe(DEFAULT_TAG)
    expect(customElements.get(DEFAULT_TAG)).toBeTruthy()
  })

  it('documents each optional transport capability it actually reads', () => {
    // The inverse of the bug above: a capability the README promises must be
    // one the code consults, or hosts implement a method nothing calls.
    const readme = readFileSync(join(PACKAGE_ROOT, 'README.md'), 'utf8')
    for (const capability of ['executeSql', 'submitFeedback', 'uploadFiles', 'readFile']) {
      expect(readme, `${capability} is unreadable to a host`).toContain(capability)
      expect(types, `${capability} has no declared shape`).toContain(capability)
    }
  })
})
