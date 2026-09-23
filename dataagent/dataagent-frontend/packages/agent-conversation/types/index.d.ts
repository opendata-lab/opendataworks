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

/**
 * What the composer offers, supplied by the host.
 *
 * Every field is optional and an omitted one renders nothing — the SDK draws
 * the controls, the host decides which ones exist. OntoFoundry passes no
 * providers, so it gets no model picker; DataAgent passes its own list without
 * the SDK knowing anything about DataAgent.
 */
export interface ComposerConfig {
  /** As the runtime config returns them — pass `settings.providers` unchanged. */
  providers?: { provider_id: string; models?: string[] }[]
  /** As the shells already declare them (`PERMISSION_MODE_OPTIONS`). */
  permissionModes?: { value: string; label: string; desc?: string }[]
  /** Build these with the exported `buildCommands(names)`. */
  slashCommands?: SlashCommand[]
  /** Offered while the conversation is empty; clicking one sends it. */
  suggestions?: string[]
}

export interface SlashCommand {
  /** The token inserted, including the leading slash: `/compact`. */
  id: string
  label: string
  hint: string
  insertText: string
}

/** Turn the names an agent reports into menu entries. */
export declare function buildCommands(names: string[]): SlashCommand[]
export declare function buildCommand(name: string): SlashCommand | null
export declare function filterCommands(commands: SlashCommand[], query: string): SlashCommand[]
/** The text after `/` when the draft is a bare command token, else null. */
export declare function parseSlashQuery(text: string): string | null

/**
 * The composer's current selection, sent with every message.
 *
 * It rides along with `sendMessage` rather than being pushed separately: a
 * separate call would leave the server's idea of the mode and the one the user
 * can see free to disagree whenever it failed.
 */
export interface ComposerSettings {
  provider_id?: string
  model?: string
  permission_mode?: string
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
  sendMessage(input: {
    content: string
    metadata?: Record<string, unknown>
    /** Present only when the user attached files to this message. */
    attachments?: ConversationAttachment[]
    /** The composer's current selection, when the host configured pickers. */
    settings?: ComposerSettings
  }): Promise<RunRef>
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

  /**
   * Optional. When absent the SQL panel renders the query read-only: no limit
   * selector, no execute button. The transport is built per conversation and
   * attaches its own identity, so nothing about the conversation is passed here.
   */
  executeSql?(input: {
    sql: string
    database?: string
    engine?: string
    limit?: number
  }): Promise<SqlExecutionResult>

  /**
   * Optional. When absent the composer shows no attach button.
   *
   * Receives the files the user picked and returns them as workspace
   * references; the SDK sends those with the next message. Mapping a browser
   * File onto a workspace path is the host's job — the SDK never learns where
   * the workspace lives.
   */
  uploadFiles?(files: File[], options?: { signal?: AbortSignal }): Promise<ConversationAttachment[]>

  /**
   * Optional. When absent attachments stay download-only links.
   *
   * Supplying it lets the SDK preview an attachment in place: images through a
   * revoked object URL, HTML inside a sandboxed iframe. It returns bytes rather
   * than a URL so the host keeps control of authentication.
   */
  readFile?(relPath: string): Promise<Blob>

  /**
   * Optional. When absent the thumbs up / down buttons are not rendered.
   *
   * `feedback` is `''` when the user clears a previous rating. The SDK applies
   * the change optimistically and rolls it back if this rejects.
   */
  submitFeedback?(input: {
    messageId: string
    feedback: string
  }): Promise<{ feedback?: string } | void>
}

export interface SqlExecutionResult {
  columns?: string[]
  rows?: unknown[]
  row_count?: number
  has_more?: boolean
  truncated_by_size?: boolean
  duration_ms?: number
  notice?: string
  /** Set when the query ran but failed; rendered in place of a result table. */
  error?: string | null
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
  /** Model, permission-mode, slash-command and opener options. */
  composerConfig?: ComposerConfig
  /** Wording for the indicator shown before a turn's first token. */
  activityLabel?: string

  /** Reload the current conversation. Does not change `endpoint`. */
  reload(): Promise<void>
  /** Send `content`, or the current `value` when omitted. */
  sendMessage(content?: string, options?: SendOptions): Promise<void>
  cancel(): Promise<void>
  focus(): void
  /**
   * Scroll one message into view and highlight it. Resolves false when that
   * message is not in this conversation. Use this instead of reaching through
   * the shadow root, which couples the host to internal markup.
   */
  focusMessage(messageId: string): Promise<boolean>
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
