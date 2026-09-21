import { defineCustomElement } from 'vue'
import ConversationRoot from './ui/ConversationRoot.vue'

const DEFAULT_TAG = 'dataagent-conversation'

// endpointResolver and transportFactory are declared props, so Vue already
// generates accessors for them and handles assignment before connection.
// Shadowing those here breaks reactivity — the value lands in _props without
// scheduling an update.

const METHODS = ['reload', 'sendMessage', 'cancel', 'focus']

// One constructor per tag name. customElements.define rejects a constructor
// already registered under another name with NotSupportedError, so a shared
// class would break the moment a host registers both the default tag and a
// versioned alias.
const constructors = new Map()

function constructorFor(tagName) {
  let Ctor = constructors.get(tagName)
  if (Ctor) return Ctor

  const Base = defineCustomElement(ConversationRoot)

  Ctor = class AgentConversationElement extends Base {
    constructor() {
      super()
      // Hosts assign properties in the same render pass that creates the
      // element, which can land before Vue has mounted and exposed anything.
      // Those writes are buffered here and replayed on connect.
      this._pending = new Map()
    }

    connectedCallback() {
      super.connectedCallback()
      for (const [key, value] of this._pending) this[key] = value
      this._pending.clear()
    }

    get _api() {
      // `_instance` is Vue's internal handle on the mounted component; exposed
      // members live on its `exposed` bag.
      return this._instance?.exposed ?? null
    }

    /** The composer draft. Readable and writable, unlike a plain prop. */
    get value() {
      return this._api?.getValue() ?? this._pending.get('value') ?? ''
    }

    set value(next) {
      if (this._api) this._api.setValue(next)
      else this._pending.set('value', next)
    }
  }

  for (const name of METHODS) {
    Object.defineProperty(Ctor.prototype, name, {
      configurable: true,
      value(...args) {
        const api = this._api
        if (!api) return undefined
        return api[name](...args)
      }
    })
  }

  constructors.set(tagName, Ctor)
  return Ctor
}

/**
 * Register the conversation element.
 *
 * Idempotent per tag name, so hosts can call it from module scope without
 * guarding against double execution (React fast refresh, multiple entrypoints).
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
