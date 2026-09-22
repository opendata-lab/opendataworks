// Re-export shim. The implementation moved into
// packages/agent-conversation so the SDK and this app share one conversation
// kernel instead of two. Existing imports and their tests keep working through
// here unchanged — that is the equivalence check for the move.
export * from '../../../packages/agent-conversation/src/core/streamParser.js'
