# DataAgent Online Evaluation Design

## Current State

DataAgent already exposes an HTTP task chain for intelligent-query execution:

- `GET /api/v1/nl2sql/health`
- `GET /api/v1/nl2sql-admin/settings`
- `POST /api/v1/nl2sql/topics`
- `POST /api/v1/nl2sql/tasks/deliver-message`
- `GET /api/v1/nl2sql/tasks/{task_id}`
- `GET /api/v1/nl2sql/tasks/{task_id}/events`
- `GET /api/v1/nl2sql/topics/{topic_id}/messages`

The offline deployment package already copies `deploy/`, `scripts/`, DataAgent settings, and editable Skills into `deploy/dataagent-runtime/`. Evaluation tooling is packaged at the offline-package root under `tools/dataagent-evals/` so it remains outside the DataAgent runtime directory. Private Skill content and private case datasets are manually deployed and are not committed to GitHub.

The business-domain Skill evaluation source lives outside this repository as a private asset. It defines private business-domain cases and acceptance thresholds, but OpenDataWorks should only carry the generic executable online evaluation module.

## Problem

The current evaluation material is documentation-only. After an offline package is deployed in an intranet environment, operators need a repeatable manual command that:

- runs externally supplied domain-specific evaluation cases through the real DataAgent HTTP task chain
- captures task status, events, final assistant messages, SQL/tool evidence, errors, duration, and usage
- uses an independently configured judge model endpoint for scoring so evaluation code does not enter the DataAgent runtime
- produces durable offline artifacts for review and release gating

The solution must not move business-domain-specific behavior into generic DataAgent runtime modules.

## Scope

In scope:

- Keep private JSONL evaluation datasets outside GitHub and require `--dataset` at runtime.
- Add a stdlib-only builtin runner under `tools/dataagent-evals/builtin/`.
- Add independent judge endpoint configuration for the runner.
- Add a standalone builtin eval image `opendataworks-dataagent-evals-builtin:<tag>`.
- Add offline-package copy support for `tools/dataagent-evals/`.
- Add documentation for online/offline execution.
- Add focused runner and runtime-boundary tests.

Out of scope for the first version:

- Persisting evaluation results in MySQL.
- Scheduling recurring evaluations.
- Changing `load-package-and-start.sh` startup behavior.
- Adding a frontend UI for evaluation runs.
- Persisting or baking judge-provider secrets into images or offline packages.
- Adding DataAgent backend routes or runtime modules for evaluation.

## Dataset Contract

The dataset is an external private JSONL file supplied through `--dataset` or `DATAAGENT_EVAL_DATASET`. Each line is one JSON object with these fixed fields:

- `case_id`
- `category`
- `question`
- `expected_intent`
- `expected_ontology_objects`
- `expected_relations`
- `expected_sql_or_tool_behavior`
- `expected_answer_points`
- `scoring`
- `veto_rules`
- `max_wait_seconds`

The dataset also preserves these optional extension fields:

- `required_sql_fragments`
- `forbidden_sql_patterns`
- `expected_tool_names`
- `judge_guidance`

The source document's scoring model is normalized to a 10-point rubric:

- `intent`: 1
- `ontology_entity`: 1
- `relation_scope`: 1
- `sql_or_tool_call`: 2
- `data_accuracy`: 2
- `reasoning`: 2
- `answer_quality`: 1

## Judge Contract

The builtin runner calls an external Anthropic-compatible judge endpoint directly. Judge connection settings are supplied at runtime through CLI options or environment variables:

- `--judge-base-url` / `DATAAGENT_EVAL_JUDGE_BASE_URL`
- `--judge-token` / `DATAAGENT_EVAL_JUDGE_TOKEN`
- `--judge-model` / `DATAAGENT_EVAL_JUDGE_MODEL`
- `--judge-timeout-seconds` / `DATAAGENT_EVAL_JUDGE_TIMEOUT_SECONDS`

The judge request includes:

- case definition
- user question
- final assistant answer
- task status and errors
- tool events
- SQL/chart/spec outputs extracted by the runner
- automatic rule-check result

The judge response text must contain a JSON object with:

- 10-point score
- per-dimension scores
- hallucination flag
- triggered veto rules
- failure attribution
- short comment
- `judge_failed` and `raw_output` when the judge model could not return valid JSON

DataAgent backend does not expose an eval judge API and does not resolve judge credentials. If the model output is not valid JSON, the runner retries once with a stricter repair prompt. If parsing still fails, the case is marked `judge_failed=true` with score `0`.

## Runner Contract

User entrypoint:

`bash scripts/run-dataagent-evals.sh --base-url http://127.0.0.1:8900 --dataset /path/to/private-cases.jsonl`

Python runner:

`tools/dataagent-evals/builtin/run.py`

Compatibility shim:

`scripts/run-dataagent-evals.py`

Runner constraints:

- stdlib-only Python
- Docker/Podman wrapper defaults to `opendataworks-dataagent-evals-builtin:<tag>`
- required external dataset: `--dataset /path/to/private-cases.jsonl`
- default output: `reports/dataagent-evals/<timestamp>/`

Runner arguments:

- `--base-url`
- `--dataset`
- `--output-dir`
- repeatable `--case`
- `--provider-id`
- `--model`
- `--timeout-seconds`
- `--concurrency`
- `--judge-base-url`
- `--judge-token`
- `--judge-model`
- `--judge-timeout-seconds`
- `--dry-run`

When `--concurrency > 1`, the runner submits and polls multiple cases in parallel while preserving dataset order in the written outputs.

## Runner Flow

1. Load and validate the dataset.
2. In `--dry-run`, create the output directory and write validation artifacts without calling services.
3. Preflight online services:
   - `GET /api/v1/nl2sql/health`
   - `GET /api/v1/nl2sql-admin/settings`
4. For each case:
   - create a topic
   - submit the question through `/api/v1/nl2sql/tasks/deliver-message`
   - poll task status and events until terminal status or timeout
   - fetch topic messages and extract the final assistant response
   - extract tool calls, SQL fragments, chart/spec-like payloads, errors, duration, and usage
   - run automatic rule checks
   - call the configured external judge model endpoint
   - write raw case artifact and JSONL case result
5. Write:
   - `cases.jsonl`
   - `summary.json`
   - `report.md`
   - `raw/<case_id>.json`

## Acceptance Metrics

Overall report gates are fixed from the source evaluation document:

- average score `>= 8.0`
- intent accuracy `>= 90%`
- ontology accuracy `>= 90%`
- SQL/tool accuracy `>= 85%`
- data result precision `>= 90%`
- data result recall `>= 85%`
- reasoning average `>= 4/5`
- hallucination rate `<= 5%`

Any triggered veto rule marks the case failed. If any veto appears in the run, the report must explicitly say `不建议上线`.

## Failure Handling

Runner exit codes:

- `0`: report generated and acceptance gates passed
- `1`: report generated but acceptance gates failed
- `2`: preflight, dataset, argument, or filesystem error

Judge failures are case-level failures but do not stop the whole run unless required judge configuration is missing before execution starts.

## Tradeoffs

The first version does not persist results to MySQL. File artifacts are simpler for offline package use and avoid schema migrations for a manually-triggered gate.

The runner extracts SQL/tool evidence heuristically from existing event/message payloads. DataAgent event contracts remain unchanged; richer extraction can be added later if the task event schema becomes more explicit.

The runner keeps business-domain case semantics in the external private dataset and judge prompt, not in generic task execution modules. Evaluation tools are packaged under root `tools/dataagent-evals/`, not under `deploy/dataagent-runtime/`, to keep test tooling separate from DataAgent runtime assets. The builtin and DeepEval runners are intentionally parallel modules under `tools/dataagent-evals/`, each with its own image and wrapper script.
