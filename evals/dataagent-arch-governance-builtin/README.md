# DataAgent Builtin Architecture-Governance Evaluations

This module is the stdlib-only DataAgent architecture-governance evaluation runner.

It is intentionally separate from the DataAgent backend runtime. DataAgent is only used as the HTTP system under test through `/api/v1/nl2sql/*`.

## Dataset

Default dataset:

```bash
evals/dataagent-arch-governance/arch-governance-core.jsonl
```

The same JSONL dataset is shared with the DeepEval runner.

## Run With Docker

```bash
DATAAGENT_EVAL_JUDGE_BASE_URL=https://api.example.com \
DATAAGENT_EVAL_JUDGE_TOKEN=... \
DATAAGENT_EVAL_JUDGE_MODEL=claude-opus-4-6 \
bash scripts/run-dataagent-evals.sh --base-url http://127.0.0.1:8900
```

The wrapper runs `opendataworks-dataagent-evals-builtin:latest` by default. Override with:

```bash
OPENDATAWORKS_DATAAGENT_EVALS_BUILTIN_IMAGE=opendataworks-dataagent-evals-builtin:1.2.0
```

## Local Dry Run

```bash
python3 evals/dataagent-arch-governance-builtin/run.py --dry-run
```

or:

```bash
DATAAGENT_BUILTIN_RUN_LOCAL=1 bash scripts/run-dataagent-evals.sh --dry-run
```

Dry run validates the dataset and writes `summary.json` / `report.md` without calling DataAgent or the judge model.

## Outputs

- `cases.jsonl`
- `summary.json`
- `report.md`
- `raw/<case_id>.json`
