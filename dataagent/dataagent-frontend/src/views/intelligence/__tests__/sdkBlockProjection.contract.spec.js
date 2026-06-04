import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createChatState, processV2Record } from '../v2StreamParser'

// Contract test: the live streaming parser must project the same `records` into
// the same canonical block list as the backend `topic_task_store._project_sdk_records`.
// The Python side locks the same fixtures in test_sdk_block_projection_contract.py.
// vitest runs from the dataagent-frontend root, so `..` reaches the dataagent dir.
const casesPath = resolve(process.cwd(), '..', 'contracts', 'sdk-block-projection', 'cases.json')
const fixtures = JSON.parse(readFileSync(casesPath, 'utf-8'))

// Normalize v2StreamParser blocks to the shared canonical shape.
function toCanonical(blocks) {
  const canonical = []
  for (const block of blocks) {
    if (block.type === 'tool_use') {
      canonical.push({
        kind: 'tool_use',
        tool_name: block.name ?? null,
        input: block.input ?? null,
        output: block.output ?? null,
        is_error: Boolean(block.is_error),
      })
    } else {
      const text = String(block.content ?? '')
      if (!text.trim()) continue
      canonical.push({ kind: block.type, text })
    }
  }
  return canonical
}

describe('v2StreamParser projection contract', () => {
  it('loads shared golden fixtures', () => {
    expect(Array.isArray(fixtures.cases) && fixtures.cases.length).toBeTruthy()
  })

  for (const testCase of fixtures.cases) {
    it(`matches golden fixture: ${testCase.name}`, () => {
      const state = createChatState()
      for (const record of testCase.records) processV2Record(state, record)
      expect(toCanonical(state.blocks)).toEqual(testCase.expected)
    })
  }
})
