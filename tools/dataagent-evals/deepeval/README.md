# DataAgent DeepEval Architecture-Governance Evaluations

This module is a parallel DeepEval-based evaluation runner for DataAgent architecture-governance cases.

It is intentionally separate from the DataAgent backend runtime. DataAgent is only used as the HTTP system under test through `/api/v1/nl2sql/*`.

## Dataset

Datasets are private deployment assets and are not committed with this tool. Pass the JSONL file explicitly with `--dataset` or set `DATAAGENT_EVAL_DATASET`. The JSONL schema matches the builtin runner dataset so both engines can be compared case by case.

## Run With Docker

```bash
DATAAGENT_EVAL_JUDGE_BASE_URL=https://api.example.com \
DATAAGENT_EVAL_JUDGE_TOKEN=... \
DATAAGENT_EVAL_JUDGE_MODEL=claude-opus-4-6 \
bash scripts/run-dataagent-deepeval-evals.sh --base-url http://127.0.0.1:8900 --dataset /path/to/private-cases.jsonl
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

## Outputs

The output layout matches the builtin evaluation runner:

- `cases.jsonl`
- `summary.json`
- `report.md`
- `raw/<case_id>.json`
