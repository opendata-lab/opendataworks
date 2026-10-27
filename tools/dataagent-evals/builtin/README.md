# DataAgent Builtin Evaluations

This module is the stdlib-only DataAgent evaluation runner.

It is intentionally separate from the DataAgent backend runtime. DataAgent is only used as the HTTP system under test through `/api/v1/nl2sql/*`.

## Dataset

Pass the V2 JSONL file explicitly with `--dataset` or set
`DATAAGENT_EVAL_DATASET`. Public examples may live with the tool; deployment
datasets may remain external.

Only Evaluation V2 (`schema_version=2`) is executable. See
`../dataset/README.md` for the structured semantic, time, tool, SQL, result,
answer, limit, and scoring contract.

Non-dry-run evaluation must also choose the DataAgent profile to execute with. Pass `--agent-id` or set `DATAAGENT_EVAL_AGENT_ID`. Database access requires a verified DataAgent login and database account mappings for the user and selected agent; database GRANTs determine visibility. See the datasource identity deployment requirements in `deploy/README.md`.

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

For a local real-chain smoke, pass any V2 dataset and an Agent profile that can
answer it. The runner never selects behavior from a dataset filename,
`dataset_id`, category, or suite tag. A 10-case verification set and a formal
suite use this exact runner; only the `--dataset` and output paths change.
When OAuth is enabled, pass an administrator JWT only through
`DATAAGENT_EVAL_AUTH_TOKEN`.

## Outputs

- `cases.jsonl`
- `run.json`
- `summary.json`
- `report.md`
- `report.html`
- `dataset-snapshot.jsonl`
- `raw/<case_id>.json`

When reference SQL fails, exit code 2 is preserved and the terminal error names
the `case_id`, database, engine, SQL hash, full SQL, and backend cause.
`summary.json.infrastructure_details` exposes the same fields as structured
data: `error_code`, `case_id`, `database`, `engine`, `sql`, `sql_sha256`, and
`cause`.
