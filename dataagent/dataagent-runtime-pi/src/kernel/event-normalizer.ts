/**
 * Pi AgentEvent -> neutral AgentEvent.
 *
 * This is the *only* place events are produced. Inlining a second copy of this
 * mapping next to the agent subscription is how the two drift: a duplicate that
 * emitted "turn_start" instead of "turn.started" would be rejected outright by
 * the Python contract (AgentEventType is a closed enum) and every turn event
 * would silently vanish.
 *
 * Payload keys are chosen to match what the record projections and the frontend
 * render adapter already read — `output`/`is_error`, not `result`/`error` — so a
 * Pi turn renders through the same components as an SDK turn.
 */

import type { AgentEvent as PiAgentEvent } from "@earendil-works/pi-agent-core";
import type { NeutralAgentEvent } from "../protocol/frames.js";
import type { RunStateMachine } from "./run-state-machine.js";
import type { UiToolResult } from "./ui-tool-result-registry.js";
import { redact } from "../observability/redaction.js";

interface PiUsage {
  input?: number;
  output?: number;
  cacheRead?: number;
  cacheWrite?: number;
}

/** Map pi-ai's camelCase Usage onto the shape the rest of the stack reads. */
function extractUsage(message: unknown): Record<string, number> | null {
  const raw = (message as { usage?: PiUsage } | undefined)?.usage;
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const usage: Record<string, number> = {};
  const put = (key: string, value: unknown) => {
    if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
      usage[key] = value;
    }
  };
  put("input_tokens", raw.input);
  put("output_tokens", raw.output);
  put("cache_read_input_tokens", raw.cacheRead);
  put("cache_creation_input_tokens", raw.cacheWrite);
  return Object.keys(usage).length > 0 ? usage : null;
}

export class EventNormalizer {
  private turnCounter = 0;
  private currentTurnId = "turn-0";
  private contentCounter = 0;

  constructor(
    private readonly sm: RunStateMachine,
    /**
     * UI copies of tool results, captured before folding.
     *
     * Optional so existing callers and tests keep working; when absent the
     * event's own result is persisted, which is the pre-decoupling behaviour.
     */
    private readonly uiResults?: {
      take(toolCallId: string): UiToolResult | null;
    }
  ) {}

  public get turnId(): string {
    return this.currentTurnId;
  }

  public normalize(piEvent: PiAgentEvent): NeutralAgentEvent[] {
    const events: Array<NeutralAgentEvent | null> = [];

    switch (piEvent.type) {
      case "turn_start": {
        this.turnCounter += 1;
        this.currentTurnId = `turn-${this.turnCounter}`;
        this.contentCounter = 0;
        events.push(this.sm.createEvent("turn.started", { turn_id: this.currentTurnId }));
        break;
      }
      case "message_update": {
        const ev = piEvent.assistantMessageEvent as {
          type?: string;
          delta?: string;
          contentIndex?: number;
          content?: string;
        };
        const index = typeof ev?.contentIndex === "number" ? ev.contentIndex : this.contentCounter;
        const contentId = `c-${index}`;

        if (ev?.type === "text_start") {
          events.push(
            this.sm.createEvent("content.started", {
              turn_id: this.currentTurnId,
              content_id: contentId,
              kind: "answer",
            })
          );
        } else if (ev?.type === "thinking_start") {
          events.push(
            this.sm.createEvent("content.started", {
              turn_id: this.currentTurnId,
              content_id: contentId,
              kind: "reasoning",
            })
          );
        } else if (ev?.type === "text_delta" && ev.delta) {
          events.push(
            this.sm.createEvent("content.delta", {
              turn_id: this.currentTurnId,
              content_id: contentId,
              kind: "answer",
              delta: ev.delta,
            })
          );
        } else if (ev?.type === "thinking_delta" && ev.delta) {
          events.push(
            this.sm.createEvent("content.delta", {
              turn_id: this.currentTurnId,
              content_id: contentId,
              kind: "reasoning",
              delta: ev.delta,
            })
          );
        } else if (ev?.type === "text_end") {
          events.push(
            this.sm.createEvent("content.completed", {
              turn_id: this.currentTurnId,
              content_id: contentId,
              kind: "answer",
              ...(ev.content !== undefined ? { text: ev.content } : {}),
            })
          );
        } else if (ev?.type === "thinking_end") {
          events.push(
            this.sm.createEvent("content.completed", {
              turn_id: this.currentTurnId,
              content_id: contentId,
              kind: "reasoning",
              ...(ev.content !== undefined ? { text: ev.content } : {}),
            })
          );
        }
        break;
      }
      case "tool_execution_start": {
        events.push(
          this.sm.createEvent("tool.started", {
            turn_id: this.currentTurnId,
            tool_call_id: piEvent.toolCallId,
            tool_name: piEvent.toolName,
            input: redact(piEvent.args ?? {}),
          })
        );
        break;
      }
      case "tool_execution_end": {
        // Prefer the copy captured before folding: piEvent.result is the model's
        // digest whenever the result was large, and persisting that is what lost
        // charts and tables from history.
        const uiCopy = this.uiResults?.take(String(piEvent.toolCallId ?? "")) ?? null;
        const { output, output_meta } = uiCopy
          ? { output: uiCopy.output, output_meta: uiCopy.meta }
          : unwrapToolResult(piEvent.result);
        events.push(
          this.sm.createEvent("tool.completed", {
            turn_id: this.currentTurnId,
            tool_call_id: piEvent.toolCallId,
            tool_name: piEvent.toolName,
            output: redact(output),
            ...(output_meta ? { output_meta: redact(output_meta) } : {}),
            is_error: Boolean(piEvent.isError),
          })
        );
        break;
      }
      case "turn_end": {
        // usage.updated is declared in the contract and consumed by both the
        // Python adapter and the frontend, but nothing emitted it: token usage
        // was simply never recorded for a Pi turn while it was for an SDK one.
        //
        // The shape is deliberately the Anthropic-style snake_case the frontend
        // already normalizes (messageUsage.normalizeUsage reads input_tokens /
        // output_tokens / cache_*), not pi-ai's camelCase Usage. Emitting the
        // raw shape would keep the display blank.
        const usage = extractUsage(piEvent.message);
        if (usage) {
          events.push(this.sm.createEvent("usage.updated", { turn_id: this.currentTurnId, usage }));
        }
        events.push(this.sm.createEvent("turn.completed", { turn_id: this.currentTurnId }));
        break;
      }
      default:
        break;
    }

    return events.filter((e): e is NeutralAgentEvent => e !== null);
  }
}

/** Public output_meta fields, in the snake_case the wire contract uses. */
const DETAIL_FIELD_ALIASES = new Map<string, string>([
  ["exitCode", "exit_code"],
  ["exit_code", "exit_code"],
  ["bytes", "byte_count"],
  ["byte_count", "byte_count"],
  ["truncated", "truncated"],
  ["count", "count"],
  ["folded", "model_context_folded"],
  ["model_context_folded", "model_context_folded"],
  ["result_ref", "result_ref"],
  ["storage_path", "storage_path"],
  ["original_bytes", "original_bytes"],
  // Fold provenance arrives nested, so it cannot collide with a tool's own
  // fields and needs no arbitration.
  ["dataagent_fold", "fold"],
  ["stored_bytes", "stored_bytes"],
  ["skill_name", "skill_name"],
  ["root_path", "root_path"],
  ["denied", "denied"],
  ["error", "error"],
]);

/**
 * Split a pi-agent-core tool result into the neutral wire shape.
 *
 * pi returns `{content: ContentBlock[], details?: object}`. Emitting that whole
 * object as `output` is what broke chart and table rendering: the frontend
 * looks for a platform `kind` inside a string, a content-block array, or an
 * object that carries `kind` itself — and finds nothing in a `{content,
 * details}` wrapper, so it falls back to printing raw JSON.
 *
 * Unwrapping `content` restores that detection with no frontend change, because
 * the array branch already reads each block's `text`. Engine metadata moves to
 * the sibling `output_meta`, keeping `output` a superset-compatible SDK shape.
 */
export function unwrapToolResult(result: unknown): {
  output: unknown;
  output_meta: Record<string, unknown> | null;
} {
  if (!result || typeof result !== "object" || Array.isArray(result)) {
    return { output: result ?? null, output_meta: null };
  }

  const wrapper = result as { content?: unknown; details?: unknown; kind?: unknown };
  // A platform structured output carries `kind` at the top level and is already
  // the payload. The comment here always said so, but the condition only caught
  // it when the payload had neither field — one that happened to carry `content`
  // was taken apart, and the Python reader kept it whole, so the same record
  // meant two things.
  //
  // Object.hasOwn, not `in`: `in` answers for inherited names too.
  const isLegacyWrapper =
    (Object.hasOwn(wrapper, "content") || Object.hasOwn(wrapper, "details")) &&
    !Object.hasOwn(wrapper, "kind");
  if (!isLegacyWrapper) {
    return { output: result, output_meta: null };
  }

  // Null-prototype: assigning `__proto__` on a normal object sets the
  // prototype instead of storing a key, which silently loses the value.
  const meta: Record<string, unknown> = Object.create(null);
  const engineDetails: Record<string, unknown> = Object.create(null);
  if (wrapper.details && typeof wrapper.details === "object" && !Array.isArray(wrapper.details)) {
    for (const [key, value] of Object.entries(wrapper.details as Record<string, unknown>)) {
      const alias = DETAIL_FIELD_ALIASES.get(key);
      if (alias) {
        meta[alias] = value;
      } else {
        // Unknown keys are kept rather than dropped — they are still evidence —
        // but namespaced so they cannot collide with contract fields.
        engineDetails[key] = value;
      }
    }
  }
  if (wrapper.details != null && !(typeof wrapper.details === "object" && !Array.isArray(wrapper.details))) {
    // Same rule the fold path uses: details are typed as anything, and a string
    // or a number is evidence too. Dropping it here while the Python reader
    // kept it made one record mean different things on the two sides.
    engineDetails.raw_details = wrapper.details;
  }

  if (Object.keys(engineDetails).length > 0) {
    // Spread back to an ordinary object at the boundary: the null prototype is
    // how accumulation stays safe, not something callers should have to know.
    // Spread copies own keys without running setters, so `__proto__` survives
    // as data rather than becoming a prototype again.
    meta.engine_details = { ...engineDetails };
  }

  return {
    output: wrapper.content ?? null,
    output_meta: Object.keys(meta).length > 0 ? { ...meta } : null,
  };
}
