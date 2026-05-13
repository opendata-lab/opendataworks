# OpenDataWorks Utility Scripts

This directory contains various utility scripts for building and deploying the OpenDataWorks platform.

## Directory Structure

| Path | Description |
|------|-------------|
| `build/` | Build helpers for Docker images (single arch / multi-arch). |
| `start.sh` | Start the stack with `deploy/docker-compose.prod.yml`, auto-creating `deploy/.env` from the example when missing. |
| `stop.sh` | Stop all services defined in `deploy/docker-compose.prod.yml`. |
| `restart.sh` | Restart services from the compose file. |
| `load-images.sh` | Load tarred images from `deploy/docker-images/` (offline deployment). |
| `load-package-and-start.sh` | Extract an offline package, load images, and optionally start the stack. |
| `create-offline-package.sh` | Produce an offline tarball containing compose files, scripts, and images. |
| `run-dataagent-evals.sh` | Run the DataAgent architecture-governance online evaluation suite against a deployed backend. |
| `run-dataagent-deepeval-evals.sh` | Run the parallel DeepEval-based DataAgent architecture-governance evaluation suite in Docker/Podman. |

## Common Tasks

### Deployment
From the repository root:
```bash
# Start the application (deploy/.env is required)
bash scripts/start.sh

# Stop the application
bash scripts/stop.sh
```

### Build
Build the project using scripts in `build/`.
```bash
# Build images (multi-arch, including integrated frontend and DataAgent backend)
bash scripts/build/build-multiarch.sh
```

### DataAgent Builtin Evaluation
Run the stdlib-only architecture-governance evaluation suite through its Docker image:
```bash
DATAAGENT_EVAL_JUDGE_BASE_URL=https://api.example.com \
DATAAGENT_EVAL_JUDGE_TOKEN=... \
DATAAGENT_EVAL_JUDGE_MODEL=claude-opus-4-6 \
bash scripts/run-dataagent-evals.sh --base-url http://127.0.0.1:8900
```

Useful options:
```bash
# Local dry-run without Docker
DATAAGENT_BUILTIN_RUN_LOCAL=1 bash scripts/run-dataagent-evals.sh --dry-run

# Run selected cases only
bash scripts/run-dataagent-evals.sh --case ARCH_ASSET_001 --case ARCH_EDGE_006
```

Default output is written to `reports/dataagent-evals/<timestamp>/` with `cases.jsonl`, `summary.json`, `report.md`, and per-case raw JSON files.

The builtin module lives at `evals/dataagent-arch-governance-builtin/` and uses image `opendataworks-dataagent-evals-builtin:<tag>`. It is outside the DataAgent runtime; DataAgent is only the system under test. The legacy `scripts/run-dataagent-evals.py` path remains as a compatibility shim for local Python execution.

### DataAgent DeepEval Evaluation
Run the DeepEval-based parallel evaluation module through its Docker image:
```bash
DATAAGENT_EVAL_JUDGE_BASE_URL=https://api.example.com \
DATAAGENT_EVAL_JUDGE_TOKEN=... \
DATAAGENT_EVAL_JUDGE_MODEL=claude-opus-4-6 \
bash scripts/run-dataagent-deepeval-evals.sh --base-url http://127.0.0.1:8900
```

Useful options mirror the builtin runner:
```bash
# Local dry-run without Docker
DATAAGENT_DEEPEVAL_RUN_LOCAL=1 bash scripts/run-dataagent-deepeval-evals.sh --dry-run

# Run selected cases only
bash scripts/run-dataagent-deepeval-evals.sh --case ARCH_ASSET_001 --case ARCH_EDGE_006
```

The builtin and DeepEval runners use the shared dataset at `evals/dataagent-arch-governance/arch-governance-core.jsonl` and write the same report file layout.
