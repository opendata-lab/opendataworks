# DataAgent Builtin Evaluations

This module is the stdlib-only DataAgent evaluation runner.

It is intentionally separate from the DataAgent backend runtime. DataAgent is only used as the HTTP system under test through `/api/v1/nl2sql/*`.

## Dataset

Datasets are private deployment assets and are not committed with this tool. Pass the JSONL file explicitly with `--dataset` or set `DATAAGENT_EVAL_DATASET`.

Non-dry-run evaluation must also choose the DataAgent profile to execute with. Pass `--agent-id` or set `DATAAGENT_EVAL_AGENT_ID`. The selected agent's `data_scope` is snapshotted on each eval topic and enforces metadata/query access.

## Run With Docker

```bash
DATAAGENT_EVAL_JUDGE_BASE_URL=https://api.example.com \
DATAAGENT_EVAL_JUDGE_TOKEN=... \
DATAAGENT_EVAL_JUDGE_MODEL=claude-opus-4-6 \
bash scripts/run-dataagent-evals.sh --base-url http://127.0.0.1:8900 --agent-id agent_eval --dataset /path/to/private-cases.jsonl
```

The wrapper runs `opendataworks-dataagent-evals-builtin:latest` by default. Override with:

```bash
OPENDATAWORKS_DATAAGENT_EVALS_BUILTIN_IMAGE=opendataworks-dataagent-evals-builtin:1.2.0
```

## Local Dry Run

```bash
python3 tools/dataagent-evals/builtin/run.py --dry-run --dataset /path/to/private-cases.jsonl
```

or:

```bash
DATAAGENT_BUILTIN_RUN_LOCAL=1 bash scripts/run-dataagent-evals.sh --dry-run --dataset /path/to/private-cases.jsonl
```

Dry run validates the dataset and writes `summary.json` / `report.md` without calling DataAgent or the judge model.

## Outputs

- `cases.jsonl`
- `summary.json`
- `report.md`
- `raw/<case_id>.json`
