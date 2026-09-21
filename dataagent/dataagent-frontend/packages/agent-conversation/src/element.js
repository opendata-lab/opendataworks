import { defineCustomElement } from 'vue'
import ConversationRoot from './ui/ConversationRoot.vue'

const DEFAULT_TAG = 'dataagent-conversation'

// One constructor per tag name. `customElements.define` rejects a constructor
// that is already registered under another name with NotSupportedError, so
// reusing a single class would break the moment a host registers both the
// default tag and a versioned alias.
const constructors = new Map()

function constructorFor(tagName) {
  let Ctor = constructors.get(tagName)
  if (!Ctor) {
    Ctor = defineCustomElement(ConversationRoot)
    constructors.set(tagName, Ctor)
  }
  return Ctor
}

/**
 * Register the conversation element.
 *
 * Idempotent per tag name, so hosts can call it from module scope without
 * guarding against double execution (React fast-refresh, multiple entrypoints).
 *
 * @param {string} [tagName] custom tag name; pass a versioned alias when two
 *   major versions of the SDK must coexist on one page.
 * @returns {string} the tag name that is now registered.
 */
export function defineAgentConversation(tagName = DEFAULT_TAG) {
  if (!customElements.get(tagName)) {
    customElements.define(tagName, constructorFor(tagName))
  }
  return tagName
}

export { DEFAULT_TAG }
