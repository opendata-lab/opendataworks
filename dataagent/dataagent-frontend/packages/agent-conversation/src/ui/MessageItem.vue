<template>
  <article
    class="dac-message"
    :class="`dac-message-${message.role}`"
    :part="`message message-${message.role}`"
    :data-message-id="message.id"
  >
    <!-- A user turn is plain text. Rendering it as markdown would let a
         pasted snippet change how the page looks. -->
    <div v-if="message.role === 'user'" class="dac-user" part="user-turn">
      <div class="dac-bubble" part="bubble user-bubble">{{ message.content }}</div>
      <MessageAttachments
        v-if="showAttachments && message.attachments?.length"
        :attachments="message.attachments"
        :file-url="fileUrl"
        :can-preview-files="canPreviewFiles"
        @preview="(file) => $emit('preview', file)"
      />
      <MessageActions
        :message="message"
        :can-rate="false"
      />
    </div>

    <div v-else class="dac-assistant" part="assistant-turn">
      <!-- Blocks are the live shape: a streaming turn has them before it has
           any content, and interaction requests only ever exist here. A
           renderer that reads `content` alone shows an empty conversation
           until the run ends, and never shows a permission prompt at all. -->
      <template v-if="blockEntries.length">
        <template v-for="{ block, key } in blockEntries" :key="key">
          <div
            v-if="block.type === 'text'"
            class="dac-bubble"
            part="bubble assistant-bubble"
          >
            <MarkdownText :text="block.content || ''" :streaming="block.status === 'streaming'" :file-url="fileUrl" @rendered="$emit('rendered')" />
            <span v-if="block.status === 'streaming'" class="dac-cursor" part="cursor" aria-hidden="true" />
          </div>
          <ThinkingBlock
            v-else-if="block.type === 'thinking'"
            :block="block"
            :render-version="renderVersion"
            :presentation="presentation"
            :block-key="key"
            @rendered="$emit('rendered')"
          />
          <ToolOutput
            v-else-if="block.type === 'tool_use' && !isCardToolBlock(block)"
            :tool="toolFor(block)"
            :file-url-resolver="fileUrl"
          />
          <PermissionCard
            v-else-if="block.type === 'permission_request'"
            :block="block"
            :disabled="disabled || message._v2state?.status !== 'streaming'"
            @decide="(payload) => $emit('decide', { ...payload, taskId: message.taskId })"
          />
          <QuestionCard
            v-else-if="block.type === 'question_request'"
            :block="block"
            :disabled="disabled || message._v2state?.status !== 'streaming'"
            @answer="(payload) => $emit('answer', { ...payload, taskId: message.taskId })"
          />
        </template>

        <!-- Trailing activity cue: keeps a loading indicator visible
             when the active run has already rendered content but its tail
             block is not actively streaming (between turns / tool calls).
             Suppressed when waiting for user permission or question input. -->
        <p v-if="showTrailingActivity(message)" class="dac-activity" part="activity">
          <span class="dac-activity-dot" aria-hidden="true" />{{ activityLabel }}
        </p>
      </template>

      <!-- An open turn that has not produced anything yet. Without this the
           conversation shows an empty bubble for however long the agent
           spends before its first token, which reads as a stall. -->
      <p v-else-if="isStreaming(message)" class="dac-activity" part="activity">
        <span class="dac-activity-dot" aria-hidden="true" />{{ activityLabel }}
      </p>

      <div v-else class="dac-bubble" part="bubble assistant-bubble">
        <MarkdownText :text="message.content || ''" :file-url="fileUrl" @rendered="$emit('rendered')" />
      </div>

      <MessageAttachments
        v-if="showAttachments && message.attachments?.length"
        :attachments="message.attachments"
        :file-url="fileUrl"
        :can-preview-files="canPreviewFiles"
        @preview="(file) => $emit('preview', file)"
      />

      <!-- A failed turn keeps whatever it managed to produce; the card is
           appended rather than replacing it, so a run that died mid-answer
           still shows the half it finished. -->
      <div v-if="errorOf(message)" class="dac-error" part="error" role="alert">
        <span class="dac-error-text">{{ errorOf(message) }}</span>
        <button
          v-if="!disabled"
          type="button"
          class="dac-retry"
          part="retry-button"
          @click="$emit('retry', message)"
        >重试</button>
      </div>

      <MessageActions
        v-if="!isStreaming(message)"
        :message="message"
        :can-rate="canRate"
        @feedback="(payload) => $emit('feedback', payload)"
      />
    </div>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { extractErrorText } from '../core/message.js'
import { blocksOf } from '../core/blockPresentation.js'
import { blockToToolProp } from '../core/streamParser.js'
import MarkdownText from './MarkdownText.vue'
import ToolOutput from './ToolOutput.vue'
import ThinkingBlock from './ThinkingBlock.vue'
import PermissionCard from './PermissionCard.vue'
import QuestionCard from './QuestionCard.vue'
import MessageActions from './MessageActions.vue'
import MessageAttachments from './MessageAttachments.vue'

const props = defineProps({
  message: { type: Object, required: true },
  renderVersion: { type: Number, default: 0 },
  presentation: { type: Object, required: true },
  fileUrl: { type: Function, default: (p) => p },
  disabled: { type: Boolean, default: false },
  canRate: { type: Boolean, default: false },
  canPreviewFiles: { type: Boolean, default: false },
  /** Hosts that surface generated files elsewhere turn the cards off. */
  showAttachments: { type: Boolean, default: true },
  /** Shown while an open turn has produced nothing yet. */
  activityLabel: { type: String, default: '正在处理…' },
})

defineEmits(['decide', 'answer', 'retry', 'feedback', 'preview', 'rendered'])

// renderVersion invalidates raw historical blocks without deep traversal.
const blockEntries = computed(() => {
  props.renderVersion
  return props.presentation.entriesFor(props.message)
})
// Keep unchanged tools stable when another block in this message streams.
const toolProps = new WeakMap()
const toolFor = (block) => {
  const next = blockToToolProp(block)
  const previous = toolProps.get(block)
  if (previous && Object.keys(next).every(key => Object.is(previous[key], next[key]))) return previous
  toolProps.set(block, next)
  return next
}
const isStreaming = (message) => message?._v2state?.status === 'streaming'

const errorOf = (message) => {
  if (message?.role !== 'assistant') return ''
  const state = message?._v2state
  if (state?.status === 'error') return state.errorText || '会话执行失败'
  if (['error', 'failed'].includes(String(message?.status || ''))) {
    return extractErrorText(message?.error) || '会话执行失败'
  }
  return ''
}

// These tools surface as their own cards (question / plan); a raw tool row
// beside the card would only repeat it.
const CARD_TOOLS = new Set(['AskUserQuestion', 'ExitPlanMode'])
const isCardToolBlock = (block) => CARD_TOOLS.has(String(block?.name || '').split('__').pop())

const isBlockActivelyProgressing = (block) => {
  if (!block) return false
  if (block.type === 'text' || block.type === 'thinking') return block.status === 'streaming'
  if (block.type === 'tool_use') return block.output == null
  return false
}

const showTrailingActivity = (message) => {
  if (!isStreaming(message)) return false
  const state = message?._v2state
  if (!state || state.status === 'done' || state.status === 'error') return false
  const blocks = blocksOf(message)
  if (!blocks.length) return false
  const lastBlock = blocks[blocks.length - 1]
  if (lastBlock?.type === 'permission_request' && (lastBlock.decision === 'pending' || !lastBlock.decision)) return false
  if (lastBlock?.type === 'question_request' && !lastBlock.answered) return false
  return !isBlockActivelyProgressing(lastBlock)
}

</script>
