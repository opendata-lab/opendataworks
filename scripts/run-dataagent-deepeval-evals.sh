#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE="${OPENDATAWORKS_DATAAGENT_EVALS_DEEPEVAL_IMAGE:-opendataworks-dataagent-evals-deepeval:latest}"

usage() {
    cat <<'EOF'
Usage: scripts/run-dataagent-deepeval-evals.sh [options]

Runs the DeepEval-based DataAgent architecture-governance evaluation module.

Common options are passed through to the container:
  --base-url <url>
  --dataset <path>       Required private JSONL dataset path
  --output-dir <path>
  --case <case_id>
  --provider-id <provider_id>
  --model <model>
  --timeout-seconds <seconds>
  --judge-base-url <url>
  --judge-token <token>
  --judge-model <model>
  --dry-run

Default output:
  reports/dataagent-evals/deepeval-<timestamp>/ under the package/workspace directory.

Environment:
  OPENDATAWORKS_DATAAGENT_EVALS_DEEPEVAL_IMAGE
  DATAAGENT_EVAL_JUDGE_BASE_URL
  DATAAGENT_EVAL_JUDGE_TOKEN
  DATAAGENT_EVAL_JUDGE_MODEL
  DATAAGENT_EVAL_JUDGE_MAX_TOKENS
  DATAAGENT_EVAL_DATASET
  DATAAGENT_DEEPEVAL_RUN_LOCAL=1  Run local Python instead of Docker/Podman.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

if [[ "${DATAAGENT_DEEPEVAL_RUN_LOCAL:-}" == "1" ]]; then
    exec python3 "$REPO_ROOT/tools/dataagent-evals/deepeval/run.py" "$@"
fi

if command -v docker >/dev/null 2>&1; then
    CONTAINER_CMD=docker
elif command -v podman >/dev/null 2>&1; then
    CONTAINER_CMD=podman
else
    echo "docker or podman is required; set DATAAGENT_DEEPEVAL_RUN_LOCAL=1 for local Python dry-run" >&2
    exit 2
fi

DATASET_PATH="${DATAAGENT_EVAL_DATASET:-}"
ARGS=("$@")
for ((i = 0; i < ${#ARGS[@]}; i++)); do
    if [[ "${ARGS[$i]}" == "--dataset" && $((i + 1)) -lt ${#ARGS[@]} ]]; then
        DATASET_PATH="${ARGS[$((i + 1))]}"
    elif [[ "${ARGS[$i]}" == --dataset=* ]]; then
        DATASET_PATH="${ARGS[$i]#--dataset=}"
    fi
done

EXTRA_VOLUMES=()
if [[ -n "$DATASET_PATH" && "$DATASET_PATH" = /* ]]; then
    DATASET_DIR="$(cd "$(dirname "$DATASET_PATH")" && pwd)"
    EXTRA_VOLUMES+=(-v "$DATASET_DIR:$DATASET_DIR:ro")
fi

exec "$CONTAINER_CMD" run --rm \
    --network host \
    -e DATAAGENT_EVAL_JUDGE_BASE_URL="${DATAAGENT_EVAL_JUDGE_BASE_URL:-}" \
    -e DATAAGENT_EVAL_JUDGE_TOKEN="${DATAAGENT_EVAL_JUDGE_TOKEN:-}" \
    -e DATAAGENT_EVAL_JUDGE_MODEL="${DATAAGENT_EVAL_JUDGE_MODEL:-}" \
    -e DATAAGENT_EVAL_JUDGE_TIMEOUT_SECONDS="${DATAAGENT_EVAL_JUDGE_TIMEOUT_SECONDS:-120}" \
    -e DATAAGENT_EVAL_JUDGE_MAX_TOKENS="${DATAAGENT_EVAL_JUDGE_MAX_TOKENS:-4096}" \
    -e DATAAGENT_EVAL_DATASET="${DATAAGENT_EVAL_DATASET:-}" \
    -v "$REPO_ROOT:/workspace" \
    "${EXTRA_VOLUMES[@]}" \
    -w /workspace \
    "$IMAGE" "$@"
