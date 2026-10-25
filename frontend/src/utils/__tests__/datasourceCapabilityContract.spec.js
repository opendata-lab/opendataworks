import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'

const sourceRoot = resolve(process.cwd(), 'src')
const baseline = JSON.parse(readFileSync(resolve(process.cwd(), '../backend/src/test/resources/datasource-descriptors.json'), 'utf8'))
const files = directory => readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
  if (entry.name === '__tests__') return []
  const path = join(directory, entry.name)
  return entry.isDirectory() ? files(path) : /\.(js|vue)$/.test(entry.name) ? [path] : []
})

describe('datasource capability contract', () => {
  it('keeps every referenced frontend capability in the backend descriptor baseline', () => {
    const names = new Set()
    for (const file of files(sourceRoot)) {
      const source = readFileSync(file, 'utf8')
      for (const match of source.matchAll(/capabilities(?:\?\.)?\.?(\w+)\??\./g)) names.add(match[1])
      for (const match of source.matchAll(/capabilityReason\([^,]+,\s*['"](\w+)['"]/g)) names.add(match[1])
    }
    expect(names.size).toBeGreaterThan(5)
    for (const name of names) {
      for (const descriptor of baseline) expect(descriptor.capabilities, name).toHaveProperty(name)
    }
  })
})
