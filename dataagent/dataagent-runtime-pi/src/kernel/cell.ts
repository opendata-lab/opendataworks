/**
 * One run on one Cell.
 *
 * Owns the agent lifecycle for a single cell.init and guarantees the control
 * plane always sees exactly one terminal event, whatever happens: normal
 * completion, model error, policy denial, cancel, or a limit being hit.
 */

import { Agent } from "@earendil-works/pi-agent-core";
import type { AgentEvent as PiAgentEvent, StreamFn } from "@earendil-works/pi-agent-core";
import type { Api, Model, Usage } from "@earendil-works/pi-ai";
import type { CellInitPayload, NeutralAgentEvent } from "../protocol/frames.js";
import { RunStateMachine } from "./run-state-machine.js";
import { EventNormalizer, unwrapToolResult } from "./event-normalizer.js";
import { WorkspaceBoundaryEnforcer, type BoundaryPolicy } from "../policy/workspace-boundary-enforcer.js";
import { createTools } from "../tools/tool-registry.js";
import { connectMcpServers, type McpBridgeResult } from "../mcp/portal-mcp-client.js";
import { logDiagnostic } from "../protocol/channel.js";
import { saveToolResult } from "../context/result-store.js";
import {
  shouldFold,
  extractDigest,
  formatDigestText,
  STRUCTURED_OUTPUT_MAX_BYTES,
} from "../context/tabular-digest.js";
import { CompactionSession } from "../context/compaction-session.js";
import { UiToolResultRegistry } from "./ui-tool-result-registry.js";

/**
 * Add fold provenance to a tool's own metadata without losing either.
 *
 * The fold has to win the canonical names: a reader following `result_ref` must
 * reach the stored result, not whatever the tool happened to reference. But
 * overwriting silently destroys real evidence — fetch_tool_result reports the
 * source it read in `details.result_ref`, and a replacement that plausible is
 * one nobody would ever notice. Displaced values keep a `source_` prefix.
 */
export function mergeFoldProvenance(
  toolMeta: unknown,
  fold: Record<string, unknown>
): Record<string, unknown> {
  const isPlainObject = (value: unknown): value is Record<string, unknown> =>
    typeof value === "object" && value !== null && !Array.isArray(value);

  const merged: Record<string, unknown> = isPlainObject(toolMeta) ? { ...toolMeta } : {};
  if (toolMeta != null && !isPlainObject(toolMeta)) {
    // A tool's details are typed as anything. Spreading a string turned it into
    // numeric keys, an array into indices, and a number or boolean into nothing
    // at all — so keep whatever it is whole instead of destructuring it.
    merged.raw_details = toolMeta;
  }
  for (const [key, value] of Object.entries(fold)) {
    const displaced = `source_${key}`;
    // One slot per field, and the first occupant keeps it. A second fold over
    // an already-displaced value would otherwise overwrite the chain's root
    // with its own parent and leave no trace that anything was lost.
    if (key in merged && !(displaced in merged)) {
      merged[displaced] = merged[key];
    }
    merged[key] = value;
  }
  return merged;
}

export type EventSink = (event: NeutralAgentEvent) => void;
/** Liveness signal during a slow tool. Carries no state the UI renders. */
export type HeartbeatSink = (detail: Record<string, unknown>) => void;

export interface RunModelFactory {
  (providerId: string, modelId: string, cacheRetention?: string): {
    model: Model<Api>;
    streamFn: StreamFn;
  };
}

export interface CellRunResult {
  terminal_status: "success" | "failed" | "cancelled";
  last_sequence: number;
  error?: string;
}

export class Cell {
  private agent: Agent | null = null;
  private cancelled = false;

  constructor(private readonly modelFactory: RunModelFactory) {}

  public cancel(): void {
    this.cancelled = true;
    this.agent?.abort();
  }

  public async run(
    init: CellInitPayload,
    sink: EventSink,
    onHeartbeat: HeartbeatSink = () => {}
  ): Promise<CellRunResult> {
    const heartbeat = onHeartbeat;
    const sm = new RunStateMachine(init.run_id, init.task_id, init.run_id);
    // Holds the UI copy of a tool result between afterToolCall and the
    // normalizer. Scoped to this run so nothing survives it.
    const uiResults = new UiToolResultRegistry();
    const normalizer = new EventNormalizer(sm, uiResults);

    const emit = (event: NeutralAgentEvent | null) => {
      if (event) {
        sink(event);
      }
    };

    emit(sm.createEvent("run.started", { topic_id: init.topic_id }));

    let toolCalls = 0;
    let turnCount = 0;
    let limitDenial: string | null = null;
    let mcpBridge: McpBridgeResult | null = null;
    let activeToolTimer: NodeJS.Timeout | null = null;

    try {
      mcpBridge = await connectMcpServers(init.mcp_servers, { connectTimeoutMs: 10_000 });
      // Cache retention must reach the stream options, not merely the init
      // frame: pi-ai defaults to "short" whenever the option is absent.
      const { model, streamFn } = this.modelFactory(
        init.model.provider_id,
        init.model.model_id,
        init.governance_settings?.cache_retention
      );
      const boundary = new WorkspaceBoundaryEnforcer(init.boundary_policy as unknown as BoundaryPolicy);
      const tools = createTools({
        boundary,
        workspaceRoot: init.workspace.project_cwd,
        runtimeEnv: init.runtime_env ?? {},
        skills: init.skills,
        extraTools: mcpBridge.tools,
      });

      // Extract history messages and current prompt
      let historyItems: Array<{ role: string; content: string }> = [];
      let promptText = "";

      if (init.prompt !== undefined && init.prompt !== null) {
        promptText = init.prompt;
        historyItems = init.history || [];
      } else if (init.messages && init.messages.length > 0) {
        // Backwards compatibility fallback for older cell.init frames
        promptText = init.messages[init.messages.length - 1]?.content || "";
        historyItems = init.messages.slice(0, -1);
      }

      const emptyUsage: Usage = {
        input: 0,
        output: 0,
        cacheRead: 0,
        cacheWrite: 0,
        totalTokens: 0,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
      };

      const initialMessages = historyItems.map((message) => {
        if (message.role === "assistant") {
          return {
            role: "assistant" as const,
            content: [{ type: "text" as const, text: message.content }],
            api: model.api,
            provider: model.provider,
            model: model.id,
            usage: emptyUsage,
            stopReason: "stop" as const,
            timestamp: Date.now(),
          };
        }
        return {
          role: "user" as const,
          content: [{ type: "text" as const, text: message.content }],
          timestamp: Date.now(),
        };
      });

      const governance = init.governance_settings;
      const compaction = new CompactionSession({
        protectTailCount: governance?.protect_tail_turns ?? 6,
        maxContextTokens: governance?.max_context_tokens ?? 64_000,
        highWatermarkRatio: governance?.prune_high_watermark_ratio,
        targetRatio: governance?.prune_target_ratio,
      });

      const agent = new Agent({
        initialState: {
          systemPrompt: init.system_prompt,
          model,
          tools: tools as never,
          messages: initialMessages as never,
        },
        streamFn,
        // DataAgent tools mutate shared workspace state and issue SQL; running
        // them concurrently would make ordering — and therefore the boundary
        // decisions and the event stream — nondeterministic.
        toolExecution: "sequential",
        // One session per run: compaction state must not leak between runs, and
        // reusing a prefix built from another conversation would be wrong.
        transformContext: async (messages) => compaction.transform(messages),
        afterToolCall: async (context) => {
          const foldThreshold = init.governance_settings?.max_inline_result_bytes ?? 16 * 1024;
          const toolResult = context.result;
          if (!toolResult || !Array.isArray(toolResult.content)) {
            return undefined;
          }

          // Measure and persist every text block, not just the first. A small
          // leading block used to hide large ones from the threshold, and when
          // the first block did trigger folding the remaining blocks were
          // dropped with the replaced content array and never stored.
          const textBlocks = toolResult.content.filter(
            (block): block is { type: "text"; text: string } => block?.type === "text"
          );
          const nonTextBlockCount = toolResult.content.length - textBlocks.length;
          const rawText = textBlocks.map((block) => String(block.text ?? "")).join("\n");
          if (!shouldFold(rawText, foldThreshold)) {
            return undefined;
          }

          // Register the UI's copy before folding anything. The two used to be
          // the same object, so shrinking the model's context also erased the
          // chart from history. Now the transcript keeps the full result and
          // only the model sees the digest — which is also why structured
          // outputs no longer need a fold exemption paid for in tokens.
          const toolCallId = String(context.toolCall?.id ?? "");
          const uiBytes = Buffer.byteLength(rawText, "utf8");
          // Unwrap here rather than storing the raw wrapper, so a registered
          // copy and the normalizer's own fallback produce the same shape. It
          // also keeps the tool's own details: registering content alone
          // silently dropped them for exactly the large results this registry
          // exists to protect, and the fold provenance below then looked like
          // the whole story.
          const unwrapped = unwrapToolResult(toolResult);
          // Held locally so the fold below can attach provenance without a
          // take/set round-trip.
          const uiOutput =
            uiBytes <= STRUCTURED_OUTPUT_MAX_BYTES
              // Copy the array: sharing it would let the fold below mutate what
              // the UI is about to persist.
              ? toolResult.content.map((block) => ({ ...block }))
              : null;
          if (uiOutput) {
            uiResults.set(toolCallId, { output: uiOutput, meta: unwrapped.output_meta });
          } else {
            // Past the persistence ceiling the transcript keeps the digest too.
            // Saying so beats handing the renderer a digest silently, which
            // draws a broken chart with no sign anything was dropped.
            logDiagnostic(
              `STRUCTURED_OUTPUT_TOO_LARGE: ${uiBytes}B exceeds ${STRUCTURED_OUTPUT_MAX_BYTES}B; ` +
                `UI copy will hold the digest`
            );
          }

          try {
            const toolName = String(context.toolCall?.name ?? "tool");
            const saveOutcome = await saveToolResult(init.workspace.project_cwd, rawText);
            const digest = extractDigest(rawText, {
              resultRef: saveOutcome.result_ref,
              toolName,
            });
            let compactText = formatDigestText(digest);
            // Last-resort guard: folding must never make the context larger.
            // Cell capping handles the common case, but a very wide schema or
            // many columns can still out-grow the original.
            if (Buffer.byteLength(compactText, "utf8") >= Buffer.byteLength(rawText, "utf8")) {
              const minimal: Record<string, unknown> = { ...digest };
              delete minimal.preview_head;
              delete minimal.preview_tail;
              compactText = JSON.stringify(minimal, null, 2);
            }

            // Non-text blocks (images and the like) are not folded and not
            // stored, so keep them inline rather than losing them.
            const preservedBlocks = toolResult.content.filter(
              (block: { type?: string }) => block && block.type !== "text"
            );

            // One merge, one shape. The registry built provenance after
            // unwrapping and the returned details before it, so the same
            // source_result_ref sat at the top of output_meta for a small
            // result and inside engine_details for a large one. Where a field
            // lives must not depend on how big the result was.
            //
            // Merging rather than replacing also matters past the persistence
            // ceiling: nothing is registered there, so this is the only copy
            // the transcript gets.
            const foldedDetails = mergeFoldProvenance(toolResult.details, {
              folded: true,
              result_ref: saveOutcome.result_ref,
              storage_path: saveOutcome.relative_path,
              // What was folded, not what it compacted to: a tabular result is
              // rewritten to JSONL on the way to disk, so the stored size
              // understates the payload this digest stands in for.
              original_bytes: uiBytes,
              stored_bytes: saveOutcome.byte_size,
              folded_text_blocks: textBlocks.length,
              preserved_blocks: nonTextBlockCount,
            });

            // Record on the UI copy that the model saw a digest, and where the
            // full result lives. The transcript still holds the whole payload;
            // this is provenance, not a substitute for it.
            if (uiOutput) {
              uiResults.set(toolCallId, {
                output: uiOutput,
                meta: unwrapToolResult({ content: uiOutput, details: foldedDetails }).output_meta,
              });
            }

            return {
              content: [{ type: "text" as const, text: compactText }, ...preservedBlocks],
              details: foldedDetails,
            };
          } catch (err) {
            logDiagnostic(`afterToolCall fold failed, keeping raw: ${err}`);
            return undefined;
          }
        },
        beforeToolCall: async (context) => {
          toolCalls += 1;
          if (init.limits.max_tool_calls > 0 && toolCalls > init.limits.max_tool_calls) {
            limitDenial = `已达到单轮工具调用上限 ${init.limits.max_tool_calls}`;
            return { block: true, reason: limitDenial, terminate: true };
          }
          // Second line of defence. The tools enforce the boundary themselves,
          // but a tool added later must not be able to skip it by forgetting.
          const toolName = String(context.toolCall?.name ?? "");
          const args = (context.toolCall?.arguments ?? {}) as Record<string, unknown>;
          const reason = boundary.validate(toolName, args);
          if (reason) {
            emit(
              sm.createEvent("tool.denied", {
                turn_id: normalizer.turnId,
                // The reducer locates the tool block by this id; without it a
                // denied call renders as a call that never finished.
                tool_call_id: String(context.toolCall?.id ?? ""),
                tool_name: toolName,
                reason,
              })
            );
            return { block: true, reason };
          }
          return undefined;
        },
        shouldStopAfterTurn: () => {
          if (init.limits.max_turns <= 0) {
            return false;
          }
          return sm.lastSequence > 0 && turnCount >= init.limits.max_turns;
        },
      });

      this.agent = agent;

      agent.subscribe((piEvent: PiAgentEvent) => {
        if (piEvent.type === "turn_start") {
          turnCount += 1;
        } else if (piEvent.type === "tool_execution_start") {
          if (activeToolTimer) clearInterval(activeToolTimer);
          const toolCallId = piEvent.toolCallId;
          const toolName = piEvent.toolName;
          activeToolTimer = setInterval(() => {
            // A protocol frame, not an event: this exists to keep the control
            // plane's idle timer alive during a slow tool, and nothing renders
            // it. Emitting an event instead filled the record table with rows
            // no consumer read.
            heartbeat({ tool_call_id: toolCallId, tool_name: toolName });
          }, 15_000);
        } else if (piEvent.type === "tool_execution_end") {
          if (activeToolTimer) {
            clearInterval(activeToolTimer);
            activeToolTimer = null;
          }
        }
        for (const event of normalizer.normalize(piEvent)) {
          emit(event);
        }
      });

      await agent.prompt(promptText);

      if (this.cancelled || agent.signal?.aborted) {
        emit(sm.createEvent("run.cancelled", { reason: "cancelled by control plane" }));
        return { terminal_status: "cancelled", last_sequence: sm.lastSequence };
      }
      if (limitDenial) {
        emit(sm.createEvent("run.failed", { error_code: "PI_LIMIT_EXCEEDED", message: limitDenial }));
        return { terminal_status: "failed", last_sequence: sm.lastSequence, error: limitDenial };
      }

      // agent.prompt() resolves rather than rejects when the model stream
      // fails: the failure is recorded on the agent state as errorMessage.
      // Reporting success here would persist a successful-looking turn that
      // carries no answer, so the state has to be consulted explicitly.
      const modelError = agent.state.errorMessage;
      if (modelError) {
        emit(sm.createEvent("run.failed", { error_code: "PI_MODEL_ERROR", message: modelError }));
        return { terminal_status: "failed", last_sequence: sm.lastSequence, error: modelError };
      }

      emit(sm.createEvent("run.completed", { terminal_status: "success" }));
      return { terminal_status: "success", last_sequence: sm.lastSequence };
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      const stack = err instanceof Error ? err.stack : message;
      logDiagnostic(`run failed: ${stack}`);
      // Cancel surfaces as an abort rejection from the agent loop; it is a
      // cancellation, not a failure, and must not be reported as an error.
      if (this.cancelled) {
        emit(sm.createEvent("run.cancelled", { reason: "cancelled by control plane" }));
        return { terminal_status: "cancelled", last_sequence: sm.lastSequence };
      }
      emit(sm.createEvent("run.failed", { error_code: "PI_EXECUTION_ERROR", message }));
      return { terminal_status: "failed", last_sequence: sm.lastSequence, error: message };
    } finally {
      if (activeToolTimer) {
        clearInterval(activeToolTimer);
        activeToolTimer = null;
      }
      this.agent = null;
      // Whatever was never consumed goes now, on every exit path — a run that
      // failed or was cancelled mid-tool would otherwise leave results behind.
      if (uiResults.unconsumed > 0) {
        // Registered but never claimed: the afterToolCall/tool_execution_end
        // pairing is broken, which is the one asymmetry worth logging.
        logDiagnostic(`ui result registry: ${uiResults.unconsumed} unconsumed copy/copies`);
      }
      uiResults.clear();
      if (mcpBridge) {
        await mcpBridge.close();
      }
    }
  }
}
