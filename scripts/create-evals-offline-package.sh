#!/usr/bin/env bash

set -euo pipefail

# 生成 DataAgent 评测离线附加包。
# 评测镜像（evals-builtin / evals-deepeval）默认不随服务启动，且 deepeval 依赖很重，
# 因此从主离线包拆出，单独打包供需要在线评测的部署按需下载与加载。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PACKAGE_NAME="opendataworks-evals-offline"

usage() {
    cat <<'EOF'
Usage: scripts/create-evals-offline-package.sh [options]

Options:
  --registry <registry>     Remote registry host (default: docker.io)
  --namespace <namespace>   Docker Hub namespace for opendataworks images (default: mikefan2019)
  --tag <tag>               Image tag to pull (default: latest)
  --output <path>           Output tar.xz path (default: ./opendataworks-evals-offline-<timestamp>.tar.xz)
  --platform <platform>     Optional pull platform (e.g. linux/amd64)
  --keep-workdir            Do not delete temporary build directory (for debugging)
  -h, --help                Show this help

Environment overrides:
  OPENDATAWORKS_REGISTRY, OPENDATAWORKS_NAMESPACE, OPENDATAWORKS_TAG,
  OPENDATAWORKS_PLATFORM, OPENDATAWORKS_XZ_LEVEL (default: 6)

The add-on package contains the two evaluation images saved into a single
deduplicated archive plus the eval tooling and a loader script.
EOF
}

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
die() { log "ERROR: $*"; exit 1; }

detect_container_cmd() {
    if command -v docker >/dev/null 2>&1; then
        echo docker
    elif command -v podman >/dev/null 2>&1; then
        echo podman
    else
        die "docker or podman is required"
    fi
}

PARSER_REGISTRY="${OPENDATAWORKS_REGISTRY:-docker.io}"
PARSER_NAMESPACE="${OPENDATAWORKS_NAMESPACE:-mikefan2019}"
PARSER_TAG="${OPENDATAWORKS_TAG:-latest}"
PARSER_PLATFORM="${OPENDATAWORKS_PLATFORM:-}"
OUTPUT_PATH=""
KEEP_WORKDIR=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --registry) PARSER_REGISTRY="$2"; shift 2 ;;
        --namespace) PARSER_NAMESPACE="$2"; shift 2 ;;
        --tag) PARSER_TAG="$2"; shift 2 ;;
        --output) OUTPUT_PATH="$2"; shift 2 ;;
        --platform) PARSER_PLATFORM="$2"; shift 2 ;;
        --keep-workdir) KEEP_WORKDIR=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
done

CONTAINER_CMD=$(detect_container_cmd)
log "Using container runtime: $CONTAINER_CMD"

command -v xz >/dev/null 2>&1 || die "xz is required to compress the offline package (install xz-utils)"
XZ_LEVEL="${OPENDATAWORKS_XZ_LEVEL:-6}"

if [[ -z "$OUTPUT_PATH" ]]; then
    OUTPUT_PATH="opendataworks-evals-offline-$(date '+%Y%m%d-%H%M%S').tar.xz"
fi
[[ -e "$OUTPUT_PATH" ]] && die "output path already exists: $OUTPUT_PATH"

WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/opendataworks-evals-package.XXXXXXXX")
PACKAGE_ROOT="$WORKDIR/$PACKAGE_NAME"
trap '[[ "$KEEP_WORKDIR" = true ]] || rm -rf "$WORKDIR"' EXIT

DEPLOY_IMAGE_DIR="$PACKAGE_ROOT/docker-images"
PACKAGED_SCRIPTS_DIR="$PACKAGE_ROOT/scripts"
PACKAGED_TOOLS_DIR="$PACKAGE_ROOT/tools"
mkdir -p "$DEPLOY_IMAGE_DIR" "$PACKAGED_SCRIPTS_DIR" "$PACKAGED_TOOLS_DIR"

log "Copying eval tooling and run scripts"
if [[ -d "$REPO_ROOT/tools/dataagent-evals" ]]; then
    mkdir -p "$PACKAGED_TOOLS_DIR/dataagent-evals"
    tar -C "$REPO_ROOT/tools/dataagent-evals" -cf - . | tar -C "$PACKAGED_TOOLS_DIR/dataagent-evals" -xf -
fi
for f in run-dataagent-evals.sh run-dataagent-evals.py run-dataagent-deepeval-evals.sh load-evals-images.sh; do
    [[ -f "$REPO_ROOT/scripts/$f" ]] && cp "$REPO_ROOT/scripts/$f" "$PACKAGED_SCRIPTS_DIR/$f"
done

OP_TAG="$PARSER_TAG"
COMBINED_ARCHIVE="evals-images.tar"
declare -a SAVE_TARGETS=()
declare -a MANIFEST_RAW=()

EVAL_IMAGES=(
    "${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-dataagent-evals-builtin:${OP_TAG}|opendataworks-dataagent-evals-builtin:${OP_TAG}"
    "${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-dataagent-evals-deepeval:${OP_TAG}|opendataworks-dataagent-evals-deepeval:${OP_TAG}"
)

pull_image() {
    if [[ -n "$PARSER_PLATFORM" ]]; then
        "$CONTAINER_CMD" pull --platform "$PARSER_PLATFORM" "$1"
    else
        "$CONTAINER_CMD" pull "$1"
    fi
}

log "Pulling eval images from ${PARSER_REGISTRY}/${PARSER_NAMESPACE} tag ${OP_TAG}"
for entry in "${EVAL_IMAGES[@]}"; do
    IFS='|' read -r source target <<<"$entry"
    log "Pulling $source"
    pull_image "$source"
    [[ "$source" != "$target" ]] && "$CONTAINER_CMD" tag "$source" "$target"
    SAVE_TARGETS+=("$target")
    digest=$("$CONTAINER_CMD" image inspect --format='{{index .RepoDigests 0}}' "$source" 2>/dev/null || true)
    MANIFEST_RAW+=("$COMBINED_ARCHIVE|$source|$target|$digest")
done

log "Saving ${#SAVE_TARGETS[@]} eval images into deduplicated archive docker-images/$COMBINED_ARCHIVE"
if [[ "$CONTAINER_CMD" == "podman" ]]; then
    "$CONTAINER_CMD" save --multi-image-archive -o "$DEPLOY_IMAGE_DIR/$COMBINED_ARCHIVE" "${SAVE_TARGETS[@]}"
else
    "$CONTAINER_CMD" save -o "$DEPLOY_IMAGE_DIR/$COMBINED_ARCHIVE" "${SAVE_TARGETS[@]}"
fi

MANIFEST_FILE="$DEPLOY_IMAGE_DIR/manifest.json"
{
    printf '[\n'
    for i in "${!MANIFEST_RAW[@]}"; do
        IFS='|' read -r archive source target digest <<<"${MANIFEST_RAW[$i]}"
        printf '  {\n'
        printf '    "archive": "%s",\n' "$archive"
        printf '    "source": "%s",\n' "$source"
        printf '    "target": "%s"' "$target"
        if [[ -n "$digest" ]]; then printf ',\n    "digest": "%s"\n' "$digest"; else printf '\n'; fi
        if [[ "$i" -lt $((${#MANIFEST_RAW[@]} - 1)) ]]; then printf '  },\n'; else printf '  }\n'; fi
    done
    printf ']\n'
} > "$MANIFEST_FILE"

log "Generating checksums"
if command -v sha256sum >/dev/null 2>&1; then
    (cd "$DEPLOY_IMAGE_DIR" && sha256sum *.tar > checksums.sha256)
elif command -v shasum >/dev/null 2>&1; then
    (cd "$DEPLOY_IMAGE_DIR" && shasum -a 256 *.tar > checksums.sha256)
else
    die "sha256sum or shasum binary is required to compute checksums"
fi

cat > "$PACKAGE_ROOT/README.md" <<'EOF'
# OpenDataWorks 评测离线附加包

本附加包包含 DataAgent 评测镜像，独立于主离线包发布。

## 加载

```bash
tar -xJf opendataworks-evals-offline-*.tar.xz
cd opendataworks-evals-offline
scripts/load-evals-images.sh
```

加载后，按主仓库 `deploy/README.md` 第 3 节运行 builtin / DeepEval 评测。
私有评测集不随包内置，运行时通过 `--dataset` 指定。
EOF

log "Creating xz archive $OUTPUT_PATH (level ${XZ_LEVEL}, multi-threaded)"
tar -C "$WORKDIR" -cf - "$PACKAGE_NAME" | xz -T0 "-${XZ_LEVEL}" -c > "$OUTPUT_PATH"

log "Eval offline add-on package ready: $OUTPUT_PATH"
