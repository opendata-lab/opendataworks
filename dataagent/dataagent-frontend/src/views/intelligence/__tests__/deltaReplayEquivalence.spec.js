import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { gunzipSync } from 'node:zlib'
import { createChatState, processV2Record } from '../v2StreamParser'

const fixturePath = resolve(
  process.cwd(),
  '..',
  'contracts',
  'sdk-block-projection',
  'real-task-delta-replay.json.gz',
)
const fixture = JSON.parse(gunzipSync(readFileSync(fixturePath)).toString('utf-8'))

function replay(records) {
  const state = createChatState()
  for (const record of records) processV2Record(state, record)
  return state
}

describe('terminal history replay without content.delta', () => {
  it('is block-for-block and text-for-text equivalent for a real 20,300-row task', () => {
    const { records, expected, source } = fixture

    expect(source.task_id).toBe('task_0a188428626c42a6a519fa8f')
    const durableRecords = records.filter((record) => record.event_type !== 'content.delta')

    expect(records).toHaveLength(expected.record_count)
    expect(records.filter((record) => record.event_type === 'content.delta'))
      .toHaveLength(expected.delta_count)
    expect(durableRecords).toHaveLength(expected.record_count_without_delta)

    const liveReplay = replay(records)
    const terminalReplay = replay(durableRecords)

    expect(liveReplay.blocks).toHaveLength(expected.block_count)
    expect(terminalReplay.blocks).toEqual(liveReplay.blocks)
    expect(terminalReplay.blocks.reduce(
      (total, block) => total + String(block.content ?? '').length,
      0,
    )).toBe(expected.text_length)
  })
})

