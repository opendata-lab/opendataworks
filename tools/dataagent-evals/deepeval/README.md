# DataAgent DeepEval Evaluations

This module is a parallel DeepEval-based evaluation runner for DataAgent evaluations.

It is intentionally separate from the DataAgent backend runtime. DataAgent is only used as the HTTP system under test through `/api/v1/nl2sql/*`.

## Dataset

Datasets are private deployment assets and are not committed with this tool. Pass the JSONL file explicitly with `--dataset` or set `DATAAGENT_EVAL_DATASET`. The JSONL schema matches the builtin runner dataset so both engines can be compared case by case.

Non-dry-run evaluation must also choose the DataAgent profile to execute with. Pass `--agent-id` or set `DATAAGENT_EVAL_AGENT_ID`. The selected agent's `data_scope` is snapshotted on each eval topic and enforces metadata/query access.

## Run With Docker

```bash
DATAAGENT_EVAL_JUDGE_BASE_URL=https://api.example.com \
DATAAGENT_EVAL_JUDGE_TOKEN=... \
DATAAGENT_EVAL_JUDGE_MODEL=claude-opus-4-6 \
bash scripts/run-dataagent-deepeval-evals.sh --base-url http://127.0.0.1:8900 --agent-id agent_eval --dataset /path/to/private-cases.jsonl
```

The wrapper runs `opendataworks-dataagent-evals-deepeval:latest` by default. Override with:

```bash
OPENDATAWORKS_DATAAGENT_EVALS_DEEPEVAL_IMAGE=opendataworks-dataagent-evals-deepeval:1.2.0
```

## Local Dry Run

```bash
python3 tools/dataagent-evals/deepeval/run.py --dry-run --dataset /path/to/private-cases.jsonl
```

Dry run validates the dataset and writes `summary.json` / `report.md` without calling DataAgent or the judge model.

## Offline / Intranet Operation

This runner is built to run on a closed network without any DeepEval cloud
service (deepeval.com / Confident AI):

- DeepEval telemetry and update checks are opted out at import time
  (`DEEPEVAL_TELEMETRY_OPT_OUT`, `DEEPEVAL_UPDATE_WARNING_OPT_OUT`,
  `DEEPEVAL_DISABLE_PROGRESS_BAR`), so importing the package never phones home.
- The runner drives the judge metric itself with a local loop instead of
  calling DeepEval's `evaluate()`. `evaluate()` couples each run to telemetry
  and Confident AI cloud calls that fail or hang on an intranet *after* every
  case has already run, which previously crashed the tool before any report was
  written. The local loop has no such dependency.
- The only external service the runner needs is the judge model endpoint
  (`--judge-base-url` / `DATAAGENT_EVAL_JUDGE_BASE_URL`). Point it at an internal
  Anthropic-compatible gateway and no internet access is required.

## Robustness

- A single failing judge call is recorded as a failed judge for that case
  instead of aborting the whole batch.
- A complete report is always written once the cases have run, even if the
  judging step fails late. In that case `summary.json` records `judging_error`
  and the process exits non-zero.

## Outputs

The output layout matches the builtin evaluation runner:

- `cases.jsonl`
- `summary.json`
- `report.md`
- `raw/<case_id>.json`
