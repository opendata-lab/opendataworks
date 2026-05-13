# DataAgent DeepEval Parallel Evaluation Design

## Current State

The repository has a builtin DataAgent architecture-governance evaluation module under `evals/dataagent-arch-governance-builtin/`. It drives the real `/api/v1/nl2sql/*` task chain and writes JSONL, JSON, and Markdown reports through image `opendataworks-dataagent-evals-builtin:<tag>`.

The requested addition is a parallel DeepEval-based module for comparing evaluation ergonomics without changing DataAgent runtime behavior.

## Problem

DeepEval should be available as a separate evaluation engine, but its Python dependency tree must not be installed into `dataagent-backend` or the deployed DataAgent runtime. Operators should be able to run it manually from an offline package.

## Solution

- Add `evals/dataagent-arch-governance-deepeval/` as a standalone DeepEval runner module.
- Keep the shared architecture-governance dataset in `evals/dataagent-arch-governance/arch-governance-core.jsonl`.
- Run DataAgent cases through the existing HTTP task chain and convert final answers plus evidence into DeepEval `LLMTestCase` instances.
- Use a custom DeepEval metric to call an independently configured Anthropic-compatible judge endpoint and preserve the existing 10-point rubric, veto rules, and failure attribution.
- Package DeepEval dependencies only in `opendataworks-dataagent-evals-deepeval:<tag>`.

## Interfaces

Manual entrypoint:

```bash
bash scripts/run-dataagent-deepeval-evals.sh --base-url http://127.0.0.1:8900
```

Judge configuration:

- `DATAAGENT_EVAL_JUDGE_BASE_URL`
- `DATAAGENT_EVAL_JUDGE_TOKEN`
- `DATAAGENT_EVAL_JUDGE_MODEL`

Outputs match the builtin runner:

- `cases.jsonl`
- `summary.json`
- `report.md`
- `raw/<case_id>.json`

## Tradeoffs

The DeepEval runner duplicates the HTTP task-driving logic instead of sharing Python internals with the builtin runner. This keeps the two evaluation engines independently comparable and avoids forcing DeepEval dependencies into the builtin stdlib-only path.
