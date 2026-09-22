/**
 * Public types for `@opendataworks/agent-conversation`.
 *
 * The transport and element surfaces below are the contract the host BFF and
 * host page implement. See `docs/bff-protocol.md` for the wire format.
 */

/** Default custom element tag name. */
export declare const DEFAULT_TAG: 'dataagent-conversation'

/**
 * Register the conversation element. Idempotent per tag name.
 *
 * @param tagName custom tag name; pass a versioned alias when two major
 *   versions of the SDK must coexist on one page.
 * @returns the tag name that is now registered.
 */
export declare function defineAgentConversation(tagName?: string): string

export declare const ACTIVE_RUN_STATUSES: ReadonlySet<RunStatus>
export declare const TERMINAL_RUN_STATUSES: ReadonlySet<RunStatus>
export declare function toRunStatus(raw: unknown): RunStatus

/**
 * SDK-level run status. This is NOT DataAgent's raw `task_status`; transports
 * translate. `waiting_input` and `waiting_permission` are ACTIVE states — hosts
 * must include them when deciding whether editing is locked.
 */
export type RunStatus =
  | 'idle'
  | 'queued'
  | 'running'
  | 'waiting_input'
  | 'waiting_permission'
  | 'finished'
  | 'cancelled'
  | 'failed'

export interface RunRef {
  taskId: string
  status: RunStatus
  detail: string
  /**
   * Opaque metadata supplied by the host when the run was started. Hosts that
   * need it to survive a page reload must restore it from their own storage in
   * `loadConversation()` — the SDK only remembers it in memory.
   */
  metadata?: Record<string, unknown>
}

export interface ConversationAttachment {
  name: string
  relPath: string
  mediaType?: string
}

export interface ConversationMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  blocks?: unknown[]
  attachments?: ConversationAttachment[]
  taskId?: string
  createdAt?: string
}

export interface ConversationSnapshot {
  messages: ConversationMessage[]
  /** Active run, or the most recent terminal run so `metadata` can be restored. */
  run: RunRef | null
}

/**
 * One item of the event stream. A `terminal` item MUST be the last one; a
 * stream that ends without it is treated as an interruption, not a completion.
 */
export type StreamItem =
  | { type: 'event'; seqId: number; event: unknown }
  | { type: 'terminal'; run: RunRef }

export interface SendOptions {
  /** Opaque to the SDK; forwarded to the transport and echoed back on completion. */
  metadata?: Record<string, unknown>
  /** Clear the composer after a successful send. Defaults to true. */
  clearDraft?: boolean
}

export interface ConversationTransport {
  loadConversation(): Promise<ConversationSnapshot>
  sendMessage(input: { content: string; metadata?: Record<string, unknown> }): Promise<RunRef>
  streamEvents(input: {
    taskId: string
    afterId: number
    signal: AbortSignal
  }): AsyncIterable<StreamItem>
  cancelRun(input: { taskId: string }): Promise<RunRef>
  submitInteraction(input: {
    taskId: string
    kind: 'permission' | 'question'
    requestId: string
    payload: unknown
  }): Promise<void>
  fileUrl(relPath: string): string

  /** Optional. When absent the SQL panel degrades to read-only. */
  executeSql?(input: {
    sql: string
    signal?: AbortSignal
  }): Promise<{ columns: string[]; rows: unknown[][] }>
  /** Optional. When absent the slash-command menu is disabled. */
  listSlashCommands?(): Promise<{ name: string; description: string }[]>
  /** Optional. When absent the permission-mode switcher is hidden. */
  setPermissionMode?(mode: string): Promise<void>
  /** Optional. When absent message feedback controls are hidden. */
  submitFeedback?(messageId: string, value: 1 | -1 | 0): Promise<void>
}

export interface AgentConversationElement extends HTMLElement {
  /**
   * Host BFF conversation address. This is the conversation key: setting a new
   * value aborts the current stream, clears state and loads the new
   * conversation. Do not call `reload()` to switch conversations.
   */
  endpoint: string
  placeholder: string
  active: boolean
  disabled: boolean
  /** Composer draft; readable and writable. */
  value: string
  /**
   * Resolves the endpoint lazily on first send. Only consulted while `endpoint`
   * is empty, so a host can defer creating the backing conversation until the
   * user actually sends something.
   */
  endpointResolver?: () => string | Promise<string>
  /**
   * Builds the transport for a given endpoint. Defaults to the built-in HTTP
   * transport. `endpoint` stays the conversation key in both modes.
   */
  transportFactory?: (endpoint: string) => ConversationTransport

  /** Reload the current conversation. Does not change `endpoint`. */
  reload(): Promise<void>
  /** Send `content`, or the current `value` when omitted. */
  sendMessage(content?: string, options?: SendOptions): Promise<void>
  cancel(): Promise<void>
  focus(): void
}

/**
 * Named slots the element projects host content into.
 *
 * `composer-overlay` renders above the input, `composer-toolbar` below the
 * footer. Order is part of the contract, not an implementation detail: a slash
 * menu has to sit against the textarea, and a control row has to sit under it.
 */
export type AgentConversationSlot =
  | "empty"
  | "composer-overlay"
  | "composer-actions"
  | "composer-toolbar";

export interface AgentConversationEventMap {
  'dataagent-ready': CustomEvent<Record<string, never>>
  'dataagent-draft-change': CustomEvent<{ value: string }>
  'dataagent-run-change': CustomEvent<{ taskId: string; status: RunStatus; detail: string }>
  'dataagent-complete': CustomEvent<{
    taskId: string
    status: RunStatus
    metadata?: Record<string, unknown>
  }>
  'dataagent-error': CustomEvent<{ code: ErrorCode; message: string; hint?: string }>
}

export type ErrorCode =
  | 'transport_unreachable'
  | 'conversation_unavailable'
  | 'stream_interrupted'
  | 'protocol_error'

/**
 * The same names as values, for comparing against `dataagent-error` details.
 *
 * `src/index.js` exports this object at runtime; declaring only the union type
 * left `import { ErrorCode }` usable in a type position but not a value one,
 * which is exactly how a consumer would want to use it.
 */
export declare const ErrorCode: {
  readonly TRANSPORT_UNREACHABLE: 'transport_unreachable'
  readonly CONVERSATION_UNAVAILABLE: 'conversation_unavailable'
  readonly STREAM_INTERRUPTED: 'stream_interrupted'
  readonly PROTOCOL_ERROR: 'protocol_error'
}

export declare class ConversationError extends Error {
  readonly code: ErrorCode
  readonly hint: string
  constructor(code: ErrorCode, message: string, hint?: string)
}

export declare class StreamInterrupted extends ConversationError {
  constructor(message?: string)
}

export interface HttpTransportOptions {
  /** Static conversation address. */
  endpoint?: string
  /** Resolves the conversation address. Called per request so a lazily created session picks up its address. */
  getEndpoint?: () => string | Promise<string>
  /** Custom request headers (or async getter) passed to API and SSE requests. */
  headers?: Record<string, string> | (() => Record<string, string> | Promise<Record<string, string>>)
  /** Fetch credentials policy, defaults to 'same-origin'. Set to 'include' for cross-origin BFF. */
  credentials?: RequestCredentials
  /** Custom fetch function. Defaults to global fetch. */
  fetch?: typeof fetch
}

/**
 * Creates the built-in HTTP transport speaking the host BFF protocol.
 */
export declare function createHttpTransport(options: HttpTransportOptions): ConversationTransport
export declare function createHttpTransport(
  endpoint: string,
  options?: Omit<HttpTransportOptions, 'endpoint' | 'getEndpoint'>
): ConversationTransport

/** Props the element accepts in JSX. */
export interface AgentConversationAttributes {
  endpoint?: string
  placeholder?: string
  active?: boolean
  disabled?: boolean
  [key: string]: unknown
}

declare global {
  interface HTMLElementTagNameMap {
    'dataagent-conversation': AgentConversationElement
  }

  // React 18 and anything else that reads the global JSX namespace.
  namespace JSX {
    interface IntrinsicElements {
      'dataagent-conversation': AgentConversationAttributes
    }
  }
}

// React 19 moved JSX under the React namespace, and its transform no longer
// consults the global one — so the augmentation above is invisible to it.
// Both spellings are needed; neither alone covers both major versions.
declare module 'react' {
  namespace JSX {
    interface IntrinsicElements {
      'dataagent-conversation': AgentConversationAttributes
    }
  }
}
