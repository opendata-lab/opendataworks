/**
 * Dynamic Context Pruner for transformContext (Layer 2 & 3).
 *
 * Implements Zero-Mutation Context Hooks (inspired by pi-dcp):
 * - Working Set Protection: System prompt, first user prompt, and recent N messages.
 * - Non-destructive Deduplication: Replaces duplicate intermediate queries with compact placeholders.
 * - Historical Error Pruning: Condenses old resolved error stacks into single-line digests.
 * - Deep Fold: Strips row previews from old folded results while preserving Schema and result_ref.
 * - Fail-Open Guarantee: Any parsing/pruning error safely falls back to the original messages array.
 */

import type { AgentMessage } from "@earendil-works/pi-agent-core";
import { stripRenderedPreview } from "./tabular-digest.js";
import { logDiagnostic } from "../protocol/channel.js";

export interface ContextPrunerOptions {
  protectTailCount?: number;
  maxContextTokens?: number;
}

const DEFAULT_PROTECT_TAIL_COUNT = 6;

export function pruneContext(
  messages: AgentMessage[],
  options?: ContextPrunerOptions
): AgentMessage[] {
  const protectTailCount = options?.protectTailCount ?? DEFAULT_PROTECT_TAIL_COUNT;

  // Short conversations need no pruning
  if (messages.length <= protectTailCount + 2) {
    return messages;
  }

  try {
    const result: AgentMessage[] = [];
    const tailStartIndex = Math.max(0, messages.length - protectTailCount);

    // Track tool calls seen across history to detect duplicates.
    // signature -> { index, id } of the most recent call with that signature.
    const toolSignatures = new Map<string, { index: number; id: string }>();

    for (let i = 0; i < messages.length; i += 1) {
      const msg = messages[i];
      if (msg.role === "assistant" && Array.isArray(msg.content)) {
        for (const block of msg.content) {
          if (block.type === "toolCall") {
            const sig = `${block.name}:${JSON.stringify(block.arguments ?? {})}`;
            toolSignatures.set(sig, { index: i, id: block.id });
          }
        }
      }
    }

    // toolCallId -> whether that call errored. A later *failed* retry must not
    // supersede an earlier successful result, or the only usable output for
    // that query is thrown away.
    const failedToolCallIds = new Set<string>();
    for (const msg of messages) {
      const toolMsg = msg as any;
      if (toolMsg.role === "toolResult" && toolMsg.isError && toolMsg.toolCallId) {
        failedToolCallIds.add(String(toolMsg.toolCallId));
      }
    }

    // Map toolCallId to whether its call was repeated later *and succeeded*.
    const supersededToolCallIds = new Set<string>();
    for (let i = 0; i < tailStartIndex; i += 1) {
      const msg = messages[i];
      if (msg.role === "assistant" && Array.isArray(msg.content)) {
        for (const block of msg.content) {
          if (block.type === "toolCall") {
            const sig = `${block.name}:${JSON.stringify(block.arguments ?? {})}`;
            const latest = toolSignatures.get(sig);
            if (latest && latest.index > i && !failedToolCallIds.has(latest.id)) {
              supersededToolCallIds.add(block.id);
            }
          }
        }
      }
    }

    let firstUserPromptIndex = -1;
    for (let i = 0; i < messages.length; i += 1) {
      if (messages[i].role === "user") {
        firstUserPromptIndex = i;
        break;
      }
    }

    for (let i = 0; i < messages.length; i += 1) {
      const msg = messages[i];

      // 1. Anchors always protected:
      // - First user message (sets the task/question goal)
      // - Protected recent tail
      if (i === firstUserPromptIndex || i >= tailStartIndex) {
        result.push(msg);
        continue;
      }

      // 2. Intermediate assistant / tool messages:
      if (msg.role === "toolResult") {
        const toolMsg = msg as any;
        const callId = toolMsg.toolCallId;

        // If this tool call was repeated in a later turn, supersede earlier result
        if (callId && supersededToolCallIds.has(callId)) {
          result.push({
            ...toolMsg,
            content: [
              {
                type: "text" as const,
                text: `[Output omitted: identical query repeated and superseded in a later turn]`,
              },
            ],
          });
          continue;
        }

        // If this tool result had an error, compress old error stack
        if (toolMsg.isError && Array.isArray(toolMsg.content)) {
          const firstBlock = toolMsg.content[0];
          if (firstBlock && firstBlock.type === "text" && typeof firstBlock.text === "string") {
            const lines = firstBlock.text.split("\n");
            if (lines.length > 4) {
              const summaryLine = lines[0] || "Tool execution failed";
              result.push({
                ...toolMsg,
                content: [
                  {
                    type: "text" as const,
                    // Do not claim the error was resolved: nothing here checks
                    // for a later successful recovery, and asserting it would
                    // mislead the model into skipping a still-broken step.
                    text: `[Historical Error: ${summaryLine} (verbose stack trace pruned)]`,
                  },
                ],
              });
              continue;
            }
          }
        }

        // If this tool result contains an older folded result, strip samples to save tokens
        if (Array.isArray(toolMsg.content)) {
          let modified = false;
          const newContent = toolMsg.content.map((block: any) => {
            if (block.type === "text" && typeof block.text === "string") {
              const stripped = stripRenderedPreview(block.text);
              if (stripped) {
                modified = true;
                return { type: "text" as const, text: stripped };
              }
            }
            return block;
          });

          if (modified) {
            result.push({ ...toolMsg, content: newContent });
            continue;
          }
        }
      }

      result.push(msg);
    }

    return enforceBudget(result, options?.maxContextTokens, tailStartIndex, firstUserPromptIndex);
  } catch (err: unknown) {
    logDiagnostic(`transformContext: context pruning failed, falling back to original: ${err}`);
    return messages;
  }
}

// Rough bytes-per-token. Deliberately not a real tokenizer: pulling one in ties
// the runtime to a specific model family and costs startup time, while this
// only needs to decide *whether* we are near the ceiling. It under-estimates
// tokens for CJK (which is ~1 token per 1-2 chars, i.e. ~3 bytes/token), so the
// constant is conservative on purpose — it trips early rather than late.
const BYTES_PER_TOKEN = 3;

function messageBytes(msg: AgentMessage): number {
  try {
    return Buffer.byteLength(JSON.stringify(msg), "utf8");
  } catch {
    return 0;
  }
}

/**
 * Hard ceiling enforcement, applied after the content-level compaction above.
 *
 * Compaction alone cannot bound the context: a long conversation with no
 * duplicates, no errors and no folded results comes back essentially unchanged.
 * This pass drops the *content* of the oldest tool results — never the messages
 * themselves — so tool_call/tool_result pairing survives, which is what the
 * providers actually reject on.
 */
function enforceBudget(
  messages: AgentMessage[],
  maxContextTokens: number | undefined,
  tailStartIndex: number,
  firstUserPromptIndex: number
): AgentMessage[] {
  if (!maxContextTokens || maxContextTokens <= 0) {
    return messages;
  }
  const budgetBytes = maxContextTokens * BYTES_PER_TOKEN;
  let total = 0;
  for (const msg of messages) {
    total += messageBytes(msg);
  }
  if (total <= budgetBytes) {
    return messages;
  }

  const out = [...messages];
  let evicted = 0;
  for (let i = 0; i < out.length && total > budgetBytes; i += 1) {
    if (i === firstUserPromptIndex || i >= tailStartIndex) {
      continue;
    }
    const msg = out[i] as any;
    if (msg.role !== "toolResult" || !Array.isArray(msg.content)) {
      continue;
    }
    const before = messageBytes(out[i]);
    const stripped = {
      ...msg,
      content: [
        {
          type: "text" as const,
          text: "[Output evicted to stay within the context budget. Re-run the tool or use fetch_tool_result if this data is needed again.]",
        },
      ],
    } as AgentMessage;
    const after = messageBytes(stripped);
    if (after >= before) {
      continue;
    }
    out[i] = stripped;
    total -= before - after;
    evicted += 1;
  }

  if (total > budgetBytes) {
    logDiagnostic(
      `transformContext: still ${total}B over a ${budgetBytes}B budget after evicting ${evicted} tool results; ` +
        `remaining bulk is in protected messages`
    );
  }
  return out;
}
