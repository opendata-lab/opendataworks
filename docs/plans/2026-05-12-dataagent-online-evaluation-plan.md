# DataAgent Online Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manually-triggered online DataAgent evaluation module for externally supplied private business-domain cases.

**Architecture:** Keep execution outside the main DataAgent runtime by keeping private datasets out of GitHub and adding a stdlib-only builtin runner under `tools/dataagent-evals/builtin/`. The runner drives the real HTTP task chain and calls an independently configured judge endpoint; DataAgent backend remains only the system under test.

**Tech Stack:** FastAPI, Pydantic, Claude Agent SDK, Python stdlib runner, Bash wrapper, JSONL/JSON/Markdown report artifacts.

---

## Files

- Do not commit private datasets such as `*-core.jsonl`
- Modify: `dataagent/dataagent-backend/main.py` only to ensure no eval router is registered
- Modify: `dataagent/dataagent-backend/models/schemas.py` only to remove eval-only schemas
- Create: `dataagent/dataagent-backend/tests/test_runtime_excludes_eval_api.py`
- Create: `tools/dataagent-evals/builtin/run.py`
- Create: `tools/dataagent-evals/builtin/Dockerfile`
- Create: `tools/dataagent-evals/builtin/README.md`
- Create: `scripts/run-dataagent-evals.sh`
- Create: `scripts/run-dataagent-evals.py` compatibility shim
- Create: `tests/test_run_dataagent_evals.py`
- Modify: `scripts/create-offline-package.sh`
- Modify: `scripts/README.md`
- Modify: `deploy/README.md`

## Task 1: Private Dataset Boundary

- [ ] Do not commit `*-core.jsonl` or any private case file.
- [ ] Require `--dataset` or `DATAAGENT_EVAL_DATASET` to point at a private JSONL file.
- [ ] Keep fixed fields: `case_id`, `category`, `question`, `expected_intent`, `expected_ontology_objects`, `expected_relations`, `expected_sql_or_tool_behavior`, `expected_answer_points`, `scoring`, `veto_rules`, `max_wait_seconds`.
- [ ] Keep extension fields on every record as arrays/strings: `required_sql_fragments`, `forbidden_sql_patterns`, `expected_tool_names`, `judge_guidance`.
- [ ] Verify the supplied dataset has unique case IDs with the runner dry-run test.

## Task 2: Runtime Boundary Tests

- [ ] Add `dataagent/dataagent-backend/tests/test_runtime_excludes_eval_api.py`.
- [ ] Assert DataAgent backend does not register `/api/v1/dataagent/evals/judge`.
- [ ] Update runner tests so fake DataAgent HTTP server does not expose any eval route.
- [ ] Add a separate fake judge server in runner tests.

## Task 3: Runtime Boundary Implementation

- [ ] Remove `dataagent/dataagent-backend/api/eval_routes.py`.
- [ ] Remove `dataagent/dataagent-backend/core/eval_judge_service.py`.
- [ ] Remove eval-only schemas from `dataagent/dataagent-backend/models/schemas.py`.
- [ ] Remove eval router import and registration from `dataagent/dataagent-backend/main.py`.
- [ ] Remove `dataagent/dataagent-backend/tests/test_eval_judge_routes.py`.

## Task 4: Runner Tests

- [ ] Add `tests/test_run_dataagent_evals.py`.
- [ ] Load `tools/dataagent-evals/builtin/run.py` as a module through `importlib.util.spec_from_file_location`.
- [ ] Test `--dry-run` validates an external dataset:
  - case count
  - unique `case_id`
  - scoring total equals 10
  - writes `summary.json` and `report.md`
- [ ] Test fake HTTP success:
  - local DataAgent fake server returns health, settings, topic, task, events, and messages
  - separate judge fake server returns Anthropic-compatible JSON text
  - assertion: exit code `0`, `cases.jsonl` exists, summary passes gates.
- [ ] Test task failure:
  - fake task terminal status is `failed`
  - assertion: case is failed and exit code `1`.
- [ ] Test timeout:
  - fake task never reaches terminal status before a low timeout
  - assertion: case error contains timeout and exit code `1`.
- [ ] Test judge failure:
  - fake judge returns `judge_failed=true`
  - assertion: case failed and report includes judge failure attribution.
- [ ] Test veto:
  - fake judge returns a triggered veto
  - assertion: overall recommendation is `不建议上线`.
- [ ] Test preflight error:
  - health returns non-200
  - assertion: exit code `2`.

## Task 5: Runner Implementation

- [ ] Implement `tools/dataagent-evals/builtin/run.py` with argparse options:
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
- [ ] Require an external dataset path and return exit code `2` when missing.
- [ ] Implement sequential HTTP flow with `urllib.request`.
- [ ] Extract evidence from task events and final messages:
  - tool names
  - SQL-like strings
  - chart/spec-like payloads
  - errors
  - usage
- [ ] Implement automatic rule checks for required SQL fragments, forbidden SQL patterns, expected tool names, and veto-like hard failures.
- [ ] Implement metrics, gates, exit codes, and artifact writers.
- [ ] Add `scripts/run-dataagent-evals.sh` as a Docker/Podman wrapper for `opendataworks-dataagent-evals-builtin:<tag>`.
- [ ] Keep `scripts/run-dataagent-evals.py` as a local compatibility shim.

## Task 6: Offline Packaging And Docs

- [ ] Update `scripts/create-offline-package.sh` to copy `tools/dataagent-evals/` into the package root and avoid copying private datasets into `deploy/dataagent-runtime/`.
- [ ] Add `opendataworks-dataagent-evals-builtin:<tag>` to image build, offline package, and load scripts.
- [ ] Update `scripts/README.md` with online evaluation command examples.
- [ ] Update `deploy/README.md` with offline package evaluation steps and artifact locations.

## Task 7: Verification

- [ ] Run runtime boundary tests:
  - `cd dataagent/dataagent-backend`
  - `../dataagent-backend/.venv-py313/bin/python -m pytest tests/test_runtime_excludes_eval_api.py -q` when the venv exists, otherwise use a verified Python 3.10+ interpreter.
- [ ] Run runner tests:
  - `python -m pytest tests/test_run_dataagent_evals.py -q`
- [ ] Run packaging wrapper checks:
  - `bash scripts/run-dataagent-evals.sh --help`
  - `python tools/dataagent-evals/builtin/run.py --dry-run --dataset /path/to/private-cases.jsonl`
- [ ] If a local full DataAgent environment is available, run:
  - `bash scripts/run-dataagent-evals.sh --base-url http://127.0.0.1:8900 --dataset /path/to/private-cases.jsonl --case CASE_ID`
- [ ] If the full online smoke is not run, report exactly which layers were verified and that real model/task-chain smoke remains untested.
