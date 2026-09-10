/**
 * Stdio protocol shared with the Python control plane.
 *
 * The Python side of this contract is core/pi_runtime.py, and it is pinned from
 * both directions by a cross-process test (tests/test_pi_runtime_contract.py)
 * that drives a real child process. That test exists because two sides of a
 * framed protocol can disagree on payload shape while each side's own unit
 * tests stay green — a wrapper here (payload = {event}) versus a bare event
 * there silently drops every event, and nothing local would notice.
 *
 * Note in particular: the payload of `run.event` IS the neutral AgentEvent, not
 * an object containing one.
 */

export const PROTOCOL_VERSION = 1;

/** Frames the control plane sends to this Cell. */
export type InboundFrameType = "cell.init" | "run.cancel" | "cell.shutdown";
/** Frames this Cell sends to the control plane. */
export type OutboundFrameType = "cell.ready" | "run.event" | "run.settled" | "protocol.error";

export interface ProtocolFrame<T = Record<string, unknown>> {
  protocol_version: number;
  type: string;
  payload: T;
}

/** Neutral agent event. Mirrors dataagent/contracts/agent-events/v1. */
export type AgentEventType =
  | "run.started"
  | "turn.started"
  | "content.started"
  | "content.delta"
  | "content.completed"
  | "tool.started"
  | "tool.progress"
  | "tool.completed"
  | "tool.denied"
  | "usage.updated"
  | "turn.completed"
  | "run.completed"
  | "run.failed"
  | "run.cancelled"
  | "run.suspended";

export interface NeutralAgentEvent {
  event_id: string;
  run_id: string;
  task_id: string;
  task_attempt_id: string;
  sequence: number;
  timestamp: string;
  type: AgentEventType;
  payload: Record<string, unknown>;
}

export interface McpServerConfig {
  name: string;
  type?: string;
  url: string;
  headers?: Record<string, string>;
}

export interface GovernanceSettings {
  max_inline_result_bytes?: number;
  protect_tail_turns?: number;
  max_context_tokens?: number;
  /** Fraction of max_context_tokens that triggers a compaction. */
  prune_high_watermark_ratio?: number;
  /** Fraction of max_context_tokens a compaction must reach. */
  prune_target_ratio?: number;
  /**
   * Prompt cache retention passed explicitly to pi-ai.
   *
   * pi-ai defaults to "short" when nothing is supplied, so caching was on while
   * the backend believed DISABLE_PROMPT_CACHING had turned it off — a variable
   * the Pi runtime never reads.
   */
  cache_retention?: "short" | "long" | "off";
}

export interface CellInitPayload {
  run_id: string;
  task_id: string;
  topic_id: string;
  system_prompt: string;
  messages: Array<{ role: string; content: string }>;
  history?: Array<{ role: string; content: string }>;
  prompt?: string;
  model: { provider_id: string; model_id: string };
  workspace: { project_cwd: string };
  boundary_policy: Record<string, unknown>;
  skills: Array<{ name: string; root_path: string }>;
  mcp_servers?: McpServerConfig[];
  runtime_env: Record<string, string>;
  limits: {
    total_timeout_seconds: number;
    idle_timeout_seconds: number;
    max_turns: number;
    max_tool_calls: number;
  };
  governance_settings?: GovernanceSettings;
}

export function makeFrame<T extends Record<string, unknown>>(
  type: OutboundFrameType,
  payload: T
): ProtocolFrame<T> {
  return { protocol_version: PROTOCOL_VERSION, type, payload };
}
