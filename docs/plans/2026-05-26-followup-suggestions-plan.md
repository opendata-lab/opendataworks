# Follow-up Suggestions Implementation Plan

## Goal

Replace frontend keyword-based "猜你想问" suggestions with an additive backend follow-up suggestion API, without changing existing message, task, SSE, or database contracts.

## Backend Tasks

1. Add a `FollowupSuggestionsResponse` schema with `topic_id`, `message_id`, `suggestions`, and `source`.
2. Add `core/followup_suggestions.py` to build the prompt, run a one-turn no-tool model call, parse JSON suggestions, normalize output, and return fallback or empty results on failure.
3. Add `POST /topics/{topic_id}/messages/{message_id}/followup-suggestions`.
4. Validate topic ownership, assistant sender type, finished status, visible message state, and non-empty answer content before calling the generator.
5. Reuse the original task provider/model for generation.

## Frontend Tasks

1. Add `topicApi.generateFollowupSuggestions(topicId, messageId)`.
2. Replace the `NL2SqlChat.vue` local regex suggestion rules with message-scoped API state.
3. Load suggestions only for the latest successful assistant message.
4. Cache loaded/empty/error states by message id for the current page lifetime.
5. Keep suggestion clicks on the existing send path.

## Tests

1. Backend route contract tests cover generated suggestions and invalid message states.
2. Backend generator tests cover JSON parsing, dedupe, original-question filtering, and fallback behavior.
3. Frontend API client tests cover the additive endpoint path.
4. Frontend chat tests cover API-driven rendering, empty responses, and click-to-send behavior.

## Verification

Run focused backend tests with the DataAgent virtualenv:

```bash
dataagent/dataagent-backend/.venv-py313/bin/python -m pytest dataagent/dataagent-backend/tests/test_followup_suggestions.py dataagent/dataagent-backend/tests/test_routes_contract.py::test_followup_suggestions_route_generates_without_changing_message_contract dataagent/dataagent-backend/tests/test_routes_contract.py::test_followup_suggestions_route_rejects_invalid_message_states -q
```

Run focused frontend tests after `nvm use`:

```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use
cd frontend && npm test -- src/api/__tests__/nl2sqlClient.spec.js src/views/intelligence/__tests__/NL2SqlChat.spec.js
```
