/**
 * Watermark-triggered, stateful context compaction.
 *
 * pi-ai enables prompt caching by default and places the cache breakpoint on
 * the last user message, so everything before it must stay byte-identical
 * across turns to score a cache hit. The previous pruner rewrote history on
 * *every* turn — and because its protected tail was measured from the end of
 * the array, a message kept verbatim on one turn was condensed on the next.
 * That invalidated the cached prefix every single turn, plausibly costing more
 * than the tokens the pruning saved.
 *
 * So compaction is now rare and sticky:
 *
 *   below the high watermark   -> return the input untouched (cache hit)
 *   first crossing            -> compact once, down to the target watermark
 *   later turns below high    -> reuse that same compacted prefix, append only
 *   crossing again            -> start the next generation (one more miss)
 *
 * The protect-tail boundary is frozen when a generation is created, so it
 * cannot drift as the conversation grows.
 */

import type { AgentMessage } from "@earendil-works/pi-agent-core";
import { logDiagnostic } from "../protocol/channel.js";
import { pruneContext, type ContextPrunerOptions } from "./context-pruner.js";

export interface CompactionSettings extends ContextPrunerOptions {
  /** Fraction of maxContextTokens at which a compaction is triggered. */
  highWatermarkRatio?: number;
  /** Fraction of maxContextTokens a compaction must reach. */
  targetRatio?: number;
}

const DEFAULT_HIGH_RATIO = 0.9;
const DEFAULT_TARGET_RATIO = 0.7;

// Rough bytes-per-token. Deliberately not a tokenizer: this only decides
// *whether* we are near the ceiling, and pulling one in would tie the runtime
// to a model family. Conservative for CJK, so it trips early rather than late.
const BYTES_PER_TOKEN = 3;

export function estimateBytes(messages: AgentMessage[]): number {
  try {
    return Buffer.byteLength(JSON.stringify(messages), "utf8");
  } catch {
    return 0;
  }
}

interface Generation {
  /** Number of input messages this generation was built from. */
  sourceLength: number;
  /** The compacted projection of those messages. */
  prefix: AgentMessage[];
  /**
   * Size this generation must exceed before compacting again.
   *
   * Normally the configured high watermark. But compaction cannot always reach
   * the target: the protected tail alone may be larger than it. Without this
   * floor, a run whose tail exceeds the watermark would re-compact on every
   * single turn, achieve nothing, and invalidate the cache each time — the
   * exact behaviour this class exists to remove.
   */
  triggerBytes: number;
}

/**
 * Per-run compaction state.
 *
 * One instance lives for one Cell run, so a generation is only ever reused
 * within the conversation that produced it.
 */
export class CompactionSession {
  private generation: Generation | null = null;
  private generationCount = 0;

  constructor(private readonly settings: CompactionSettings = {}) {}

  /** Generations created so far; 0 means nothing has been compacted yet. */
  public get generations(): number {
    return this.generationCount;
  }

  public transform(messages: AgentMessage[]): AgentMessage[] {
    const maxTokens = this.settings.maxContextTokens ?? 0;
    if (maxTokens <= 0) {
      return messages;
    }

    const highRatio = this.settings.highWatermarkRatio ?? DEFAULT_HIGH_RATIO;
    const targetRatio = this.settings.targetRatio ?? DEFAULT_TARGET_RATIO;
    const highBytes = maxTokens * BYTES_PER_TOKEN * highRatio;
    const targetBytes = maxTokens * BYTES_PER_TOKEN * targetRatio;

    const reused = this.applyExistingGeneration(messages);
    const trigger = this.generation?.triggerBytes ?? highBytes;
    if (estimateBytes(reused) <= trigger) {
      // Still under the ceiling: hand back the prefix we already committed to,
      // with the new messages appended. Nothing older changes, so the cached
      // prefix survives.
      return reused;
    }

    // Over the ceiling. Compact from the full input — not from the previous
    // generation — so a later pass can still see everything it may condense.
    const compacted = pruneContext(messages, {
      ...this.settings,
      maxContextTokens: Math.floor(targetBytes / BYTES_PER_TOKEN),
    });
    const achieved = estimateBytes(compacted);
    this.generationCount += 1;
    this.generation = {
      sourceLength: messages.length,
      prefix: compacted,
      // If compaction fell short of the target, the next trigger is measured
      // from what it actually achieved. Re-running would only reproduce the
      // same floor while throwing away the cache.
      triggerBytes: Math.max(highBytes, achieved * (1 + (1 - highRatio))),
    };
    logDiagnostic(
      `context compaction generation ${this.generationCount}: ` +
        `${estimateBytes(messages)}B -> ${achieved}B (target ${Math.floor(targetBytes)}B)`
    );
    return compacted;
  }

  /**
   * Re-apply the committed prefix to the current message list.
   *
   * Messages beyond the generation's source length are new and appended
   * verbatim. If the caller somehow passes fewer messages than the generation
   * was built from, the generation no longer applies and the input is used.
   */
  private applyExistingGeneration(messages: AgentMessage[]): AgentMessage[] {
    const current = this.generation;
    if (!current || messages.length < current.sourceLength) {
      return messages;
    }
    return [...current.prefix, ...messages.slice(current.sourceLength)];
  }
}
