// Re-export shim. The implementation moved into packages/agent-conversation
// so the SDK and this app share one implementation. See the SDK design,
// "逐文件去向".
export * from '../../../packages/agent-conversation/src/core/toolPresentation.js'
