import { describe, expect, it } from 'vitest'
import {
  createAssistantMessageState,
  hydrateAssistantMessageState,
  processAssistantStreamEvent
} from '../messageStream'

describe('messageStream', () => {
  it('renders magic task lifecycle events and chunk records', () => {
    const msg = createAssistantMessageState({ id: 'magic-1', task_id: 'task-1' })

    processAssistantStreamEvent(msg, {
      record_type: 'event',
      seq_id: 1,
      task_id: 'task-1',
      event_type: 'BEFORE_AGENT_THINK',
      content_type: 'reasoning',
      correlation_id: 'reasoning_1',
      data: { status: 'running' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      seq_id: 2,
      task_id: 'task-1',
      event_type: 'BEFORE_AGENT_REPLY',
      content_type: 'reasoning',
      correlation_id: 'reasoning_1',
      data: { status: 'running' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'chunk',
      seq_id: 3,
      task_id: 'task-1',
      request_id: 'req-1',
      chunk_id: 1,
      content: '先定位指标',
      delta: { status: 'START' },
      metadata: { correlation_id: 'reasoning_1', content_type: 'reasoning' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      seq_id: 4,
      task_id: 'task-1',
      event_type: 'PENDING_TOOL_CALL',
      correlation_id: 'tool-read-1',
      data: { tool: { id: 'tool-read-1', name: 'Read', status: 'pending', input: { path: 'reference.md' } } }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      seq_id: 5,
      task_id: 'task-1',
      event_type: 'AFTER_TOOL_CALL',
      correlation_id: 'tool-read-1',
      data: { tool: { id: 'tool-read-1', name: 'Read', status: 'success', output: '读取完成' } }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      seq_id: 6,
      task_id: 'task-1',
      event_type: 'AFTER_AGENT_THINK',
      data: { status: 'running' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      seq_id: 7,
      task_id: 'task-1',
      event_type: 'BEFORE_AGENT_REPLY',
      content_type: 'content',
      correlation_id: 'content_1',
      data: { status: 'running' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'chunk',
      seq_id: 8,
      task_id: 'task-1',
      request_id: 'req-1',
      chunk_id: 2,
      content: '最终回答',
      delta: { status: 'END' },
      metadata: { correlation_id: 'content_1', content_type: 'content' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      seq_id: 9,
      task_id: 'task-1',
      event_type: 'AFTER_AGENT_REPLY',
      content_type: 'content',
      correlation_id: 'content_1',
      data: { status: 'finished', token_usage: { input_tokens: 10, output_tokens: 5 } }
    })

    expect(msg.task_id).toBe('task-1')
    expect(msg.status).toBe('success')
    expect(msg.thinkingText).toBe('先定位指标')
    expect(msg.content).toBe('最终回答')
    expect(msg.renderBlocks.map((block) => block.kind)).toEqual(['thinking', 'tool', 'main_text'])
    expect(msg.renderBlocks[1].tool.status).toBe('success')
    expect(msg.usage).toEqual({ input_tokens: 10, output_tokens: 5 })
    expect(msg.resume_after_seq).toBe(9)
  })

  it('renders blocks strictly from backend content_type without frontend reclassification', () => {
    const msg = createAssistantMessageState({ id: 'magic-2', task_id: 'task-2' })

    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-2',
      event_type: 'BEFORE_AGENT_REPLY',
      content_type: 'content',
      correlation_id: 'content_2',
      data: { status: 'running' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'chunk',
      task_id: 'task-2',
      request_id: 'req-2',
      chunk_id: 1,
      content: '最终结果：最近 30 天累计发布 4 次。',
      delta: { status: 'END' },
      metadata: { correlation_id: 'content_2', content_type: 'content' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-2',
      event_type: 'AFTER_AGENT_REPLY',
      content_type: 'content',
      correlation_id: 'content_2',
      data: { status: 'finished' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-2',
      event_type: 'PENDING_TOOL_CALL',
      correlation_id: 'tool-after-content',
      data: { tool: { id: 'tool-after-content', name: 'Bash', status: 'pending' } }
    })

    expect(msg.renderBlocks.map((block) => block.kind)).toEqual(['main_text', 'tool'])
    expect(msg.renderBlocks[0].text).toBe('最终结果：最近 30 天累计发布 4 次。')
  })

  it('preserves backend interleaving for reasoning and tool blocks', () => {
    const msg = createAssistantMessageState({ id: 'magic-3', task_id: 'task-3' })

    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-3',
      event_type: 'BEFORE_AGENT_THINK',
      content_type: 'reasoning',
      correlation_id: 'reasoning_1',
      data: { status: 'running' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-3',
      event_type: 'BEFORE_AGENT_REPLY',
      content_type: 'reasoning',
      correlation_id: 'reasoning_1',
      data: { status: 'running' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'chunk',
      task_id: 'task-3',
      request_id: 'req-3',
      chunk_id: 1,
      content: '先定位问题。',
      delta: { status: 'END' },
      metadata: { correlation_id: 'reasoning_1', content_type: 'reasoning' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-3',
      event_type: 'AFTER_AGENT_REPLY',
      content_type: 'reasoning',
      correlation_id: 'reasoning_1',
      data: { status: 'running' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-3',
      event_type: 'PENDING_TOOL_CALL',
      correlation_id: 'tool-read-1',
      data: { tool: { id: 'tool-read-1', name: 'Read', status: 'pending' } }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-3',
      event_type: 'AFTER_TOOL_CALL',
      correlation_id: 'tool-read-1',
      data: { tool: { id: 'tool-read-1', name: 'Read', status: 'success', output: '读取完成' } }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-3',
      event_type: 'BEFORE_AGENT_REPLY',
      content_type: 'reasoning',
      correlation_id: 'reasoning_2',
      data: { status: 'running' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'chunk',
      task_id: 'task-3',
      request_id: 'req-3',
      chunk_id: 2,
      content: '再补一段分析。',
      delta: { status: 'END' },
      metadata: { correlation_id: 'reasoning_2', content_type: 'reasoning' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-3',
      event_type: 'AFTER_AGENT_REPLY',
      content_type: 'reasoning',
      correlation_id: 'reasoning_2',
      data: { status: 'running' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-3',
      event_type: 'PENDING_TOOL_CALL',
      correlation_id: 'tool-bash-1',
      data: { tool: { id: 'tool-bash-1', name: 'Bash', status: 'pending' } }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-3',
      event_type: 'AFTER_TOOL_CALL',
      correlation_id: 'tool-bash-1',
      data: { tool: { id: 'tool-bash-1', name: 'Bash', status: 'success', output: '执行完成' } }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-3',
      event_type: 'AFTER_AGENT_THINK',
      data: { status: 'running' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'event',
      task_id: 'task-3',
      event_type: 'BEFORE_AGENT_REPLY',
      content_type: 'content',
      correlation_id: 'content_3',
      data: { status: 'running' }
    })
    processAssistantStreamEvent(msg, {
      record_type: 'chunk',
      task_id: 'task-3',
      request_id: 'req-3',
      chunk_id: 3,
      content: '最终结论。',
      delta: { status: 'END' },
      metadata: { correlation_id: 'content_3', content_type: 'content' }
    })

    expect(msg.renderBlocks.map((block) => block.kind)).toEqual(['thinking', 'tool', 'thinking', 'tool', 'main_text'])
    expect(msg.renderBlocks[0].text).toBe('先定位问题。')
    expect(msg.renderBlocks[2].text).toBe('再补一段分析。')
    expect(msg.renderBlocks[4].text).toBe('最终结论。')
  })

  it('hydrates stored history blocks and resume cursor from topic messages', () => {
    const msg = hydrateAssistantMessageState({
      message_id: 'assistant-1',
      task_id: 'task-history-1',
      status: 'running',
      content: '最终结果。',
      resume_after_seq: 18,
      blocks: [
        {
          block_id: 'reasoning:1',
          type: 'thinking',
          status: 'success',
          text: '先定位表。'
        },
        {
          block_id: 'tool:1',
          type: 'tool',
          status: 'success',
          tool_id: 'tool-read-1',
          tool_name: 'Read',
          input: { path: 'skill.md' },
          output: '读取完成'
        },
        {
          block_id: 'content:1',
          type: 'main_text',
          status: 'success',
          text: '最终结果。'
        }
      ]
    })

    expect(msg.resume_after_seq).toBe(18)
    expect(msg.renderBlocks.map((block) => block.kind)).toEqual(['thinking', 'tool', 'main_text'])
    expect(msg.renderBlocks[1].tool.name).toBe('Read')
    expect(msg.content).toBe('最终结果。')
  })

  it('appends render blocks in stream order', () => {
    const msg = createAssistantMessageState({ id: 'a1' })

    processAssistantStreamEvent(msg, { type: 'message_start', message: { id: 'a1' } })
    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'thinking' }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'thinking_delta', thinking: '先定位元数据' }
    })
    processAssistantStreamEvent(msg, { type: 'content_block_stop', index: 0 })

    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 1,
      content_block: { type: 'tool_use', id: 'tool-read-1', name: 'Read' }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_delta',
      index: 1,
      delta: { type: 'input_json_delta', partial_json: '{"file_path":"reference/00-skill-map.md"}' }
    })
    processAssistantStreamEvent(msg, { type: 'content_block_stop', index: 1 })
    processAssistantStreamEvent(msg, {
      type: 'tool.pending',
      payload: { tool_id: 'tool-read-1', tool_name: 'Read' }
    })
    processAssistantStreamEvent(msg, {
      type: 'tool.complete',
      payload: { tool_id: 'tool-read-1', tool_name: 'Read', output: '读取完成' }
    })

    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 2,
      content_block: { type: 'text' }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_delta',
      index: 2,
      delta: { type: 'text_delta', text: '最终答案' }
    })
    processAssistantStreamEvent(msg, { type: 'content_block_stop', index: 2 })
    processAssistantStreamEvent(msg, {
      type: 'done',
      payload: { status: 'success', content: '最终答案' }
    })

    expect(msg.renderBlocks.map((block) => block.kind)).toEqual(['thinking', 'tool', 'main_text'])
    expect(msg.renderBlocks[0].text).toBe('先定位元数据')
    expect(msg.renderBlocks[1].tool.name).toBe('Read')
    expect(msg.renderBlocks[1].tool.status).toBe('success')
    expect(msg.renderBlocks[1].tool.input).toEqual({ file_path: 'reference/00-skill-map.md' })
    expect(msg.renderBlocks[2].text).toBe('最终答案')
  })

  it('keeps message streaming after message_stop until done arrives', () => {
    const msg = createAssistantMessageState({ id: 'a2' })

    processAssistantStreamEvent(msg, { type: 'message_start', message: { id: 'a2' } })
    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'tool_use', id: 'tool-bash-1', name: 'Bash' }
    })
    processAssistantStreamEvent(msg, { type: 'message_stop' })

    expect(msg.status).toBe('streaming')
    expect(msg.renderBlocks[0].tool.status).toBe('streaming')

    processAssistantStreamEvent(msg, {
      type: 'tool.output',
      payload: { tool_id: 'tool-bash-1', tool_name: 'Bash', output: 'stdout line 1\n' }
    })

    expect(msg.renderBlocks[0].tool.output).toBe('stdout line 1\n')
  })

  it('marks tool invocation complete at content_block_stop before runtime starts', () => {
    const msg = createAssistantMessageState({ id: 'a4' })

    processAssistantStreamEvent(msg, { type: 'message_start', message: { id: 'a4' } })
    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'tool_use', id: 'tool-read-2', name: 'Read' }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'input_json_delta', partial_json: '{"file_path":"reference/11-datasource-routing.md"}' }
    })

    expect(msg.renderBlocks[0].tool._callComplete).toBe(false)
    expect(msg.renderBlocks[0].tool._runtimeStarted).toBe(false)

    processAssistantStreamEvent(msg, { type: 'content_block_stop', index: 0 })

    expect(msg.renderBlocks[0].tool._callComplete).toBe(true)
    expect(msg.renderBlocks[0].tool._runtimeStarted).toBe(false)

    processAssistantStreamEvent(msg, {
      type: 'tool.pending',
      payload: { tool_id: 'tool-read-2', tool_name: 'Read' }
    })

    expect(msg.renderBlocks[0].tool._runtimeStarted).toBe(true)
    expect(msg.renderBlocks[0].tool.status).toBe('pending')
  })

  it('keeps a stable frontend id across multiple message_start events', () => {
    const msg = createAssistantMessageState({ id: 'view-1' })

    processAssistantStreamEvent(msg, { type: 'message_start', message: { id: 'msg-a' } })
    processAssistantStreamEvent(msg, { type: 'message_stop' })
    processAssistantStreamEvent(msg, { type: 'message_start', message: { id: 'msg-b' } })

    expect(msg.id).toBe('view-1')
    expect(msg.message_id).toBe('msg-b')
  })

  it('does not merge blocks from different assistant messages in one run', () => {
    const msg = createAssistantMessageState({ id: 'view-2' })

    processAssistantStreamEvent(msg, { type: 'message_start', message: { id: 'msg-a' } })
    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'tool_use', id: 'tool-skill-1', name: 'Skill' }
    })
    processAssistantStreamEvent(msg, { type: 'message_stop' })
    processAssistantStreamEvent(msg, {
      type: 'tool.complete',
      payload: { block_id: 'cb-0', tool_id: 'tool-skill-1', tool_name: 'Skill', output: 'Launching skill' }
    })

    processAssistantStreamEvent(msg, { type: 'message_start', message: { id: 'msg-b' } })
    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'tool_use', id: 'tool-read-1', name: 'Read' }
    })

    expect(msg.renderBlocks).toHaveLength(2)
    expect(msg.renderBlocks[0].tool.name).toBe('Skill')
    expect(msg.renderBlocks[0].tool.output).toBe('Launching skill')
    expect(msg.renderBlocks[1].tool.name).toBe('Read')
    expect(msg.renderBlocks[0].id).not.toBe(msg.renderBlocks[1].id)
  })

  it('does not overwrite streamed text block on done', () => {
    const msg = createAssistantMessageState({ id: 'a3' })

    processAssistantStreamEvent(msg, { type: 'message_start', message: { id: 'a3' } })
    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'text' }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'text_delta', text: '流式正文' }
    })
    processAssistantStreamEvent(msg, { type: 'content_block_stop', index: 0 })
    processAssistantStreamEvent(msg, {
      type: 'done',
      payload: { status: 'success', content: '清理后的最终正文' }
    })

    expect(msg.renderBlocks).toHaveLength(1)
    expect(msg.renderBlocks[0].kind).toBe('main_text')
    expect(msg.renderBlocks[0].text).toBe('流式正文')
    expect(msg.content).toBe('清理后的最终正文')
  })

  it('handles server_tool_use blocks using the same tool trace path', () => {
    const msg = createAssistantMessageState({ id: 'a5' })

    processAssistantStreamEvent(msg, { type: 'message_start', message: { id: 'a5' } })
    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'server_tool_use', id: 'srv-1', name: 'WebFetch' }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'input_json_delta', partial_json: '{"url":"https://example.com"}' }
    })
    processAssistantStreamEvent(msg, { type: 'content_block_stop', index: 0 })

    expect(msg.renderBlocks[0].kind).toBe('tool')
    expect(msg.renderBlocks[0].tool.name).toBe('WebFetch')
    expect(msg.renderBlocks[0].tool.input).toEqual({ url: 'https://example.com' })
    expect(msg.renderBlocks[0].tool._callComplete).toBe(true)
  })

  it('captures signature deltas and message usage metadata', () => {
    const msg = createAssistantMessageState({ id: 'a6' })

    processAssistantStreamEvent(msg, {
      type: 'message_start',
      message: { id: 'a6', usage: { input_tokens: 12 } }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'thinking' }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'signature_delta', signature: 'sig-part-1' }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'signature_delta', signature: 'sig-part-2' }
    })
    processAssistantStreamEvent(msg, {
      type: 'message_delta',
      delta: { stop_reason: 'end_turn', stop_sequence: '\n\n', usage: { output_tokens: 8 } }
    })

    expect(msg.renderBlocks[0].payload.signature).toBe('sig-part-1sig-part-2')
    expect(msg.stop_reason).toBe('end_turn')
    expect(msg.stop_sequence).toBe('\n\n')
    expect(msg.usage).toEqual({ input_tokens: 12, output_tokens: 8 })
  })

  it('keeps usage metadata scoped to each assistant message segment', () => {
    const msg = createAssistantMessageState({ id: 'a8' })

    processAssistantStreamEvent(msg, {
      type: 'message_start',
      message: { id: 'seg-1', usage: { input_tokens: 10 } }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'text' }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'text_delta', text: '第一段' }
    })
    processAssistantStreamEvent(msg, {
      type: 'message_delta',
      delta: { usage: { output_tokens: 3 } }
    })
    processAssistantStreamEvent(msg, { type: 'content_block_stop', index: 0 })
    processAssistantStreamEvent(msg, { type: 'message_stop' })

    processAssistantStreamEvent(msg, {
      type: 'message_start',
      message: { id: 'seg-2', usage: { input_tokens: 20 } }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'text' }
    })
    processAssistantStreamEvent(msg, {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'text_delta', text: '第二段' }
    })
    processAssistantStreamEvent(msg, {
      type: 'message_delta',
      delta: { usage: { output_tokens: 6 } }
    })
    processAssistantStreamEvent(msg, { type: 'content_block_stop', index: 0 })
    processAssistantStreamEvent(msg, { type: 'message_stop' })

    expect(msg.renderBlocks[0].messageKey).toBe('m1')
    expect(msg.renderBlocks[1].messageKey).toBe('m2')
    expect(msg._messageMeta.m1.usage).toEqual({ input_tokens: 10, output_tokens: 3 })
    expect(msg._messageMeta.m2.usage).toEqual({ input_tokens: 20, output_tokens: 6 })
  })

  it('treats tool_result block stop as terminal tool completion', () => {
    const msg = createAssistantMessageState({ id: 'a7' })

    processAssistantStreamEvent(msg, { type: 'message_start', message: { id: 'a7' } })
    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'tool_use', id: 'tool-read-3', name: 'Read' }
    })
    processAssistantStreamEvent(msg, { type: 'content_block_stop', index: 0 })
    processAssistantStreamEvent(msg, {
      type: 'tool.output',
      payload: { tool_id: 'tool-read-3', tool_name: 'Read', output: '## 引用内容' }
    })

    expect(msg.renderBlocks[0].tool.status).toBe('streaming')

    processAssistantStreamEvent(msg, {
      type: 'content_block_start',
      index: 1,
      content_block: { type: 'tool_result', tool_use_id: 'tool-read-3', content: '## 引用内容' }
    })
    processAssistantStreamEvent(msg, { type: 'content_block_stop', index: 1 })

    expect(msg.renderBlocks).toHaveLength(1)
    expect(msg.renderBlocks[0].tool._resultStarted).toBe(true)
    expect(msg.renderBlocks[0].tool.status).toBe('success')
  })

})
