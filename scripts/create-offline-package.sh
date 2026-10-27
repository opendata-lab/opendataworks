#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PACKAGE_NAME="opendataworks-deployment"

usage() {
    cat <<'EOF'
Usage: scripts/create-offline-package.sh [options]

Options:
  --registry <registry>     Remote registry host (default: docker.io)
  --namespace <namespace>   Docker Hub namespace for opendataworks images (default: mikefan2019)
  --tag <tag>               Image tag to pull (default: latest)
  --output <path>           Output tar.xz path (default: ./opendataworks-deployment-<timestamp>.tar.xz)
  --platform <platform>     Optional pull platform (e.g. linux/amd64)
  --keep-workdir            Do not delete temporary build directory (for debugging)
  -h, --help                Show this help

Environment overrides:
  OPENDATAWORKS_REGISTRY, OPENDATAWORKS_NAMESPACE, OPENDATAWORKS_TAG,
  OPENDATAWORKS_PLATFORM, OPENDATAWORKS_XZ_LEVEL (default: 6)

The script packages current scripts/ and deploy/ content, pulls required images
from Docker Hub, retags them, saves them into a single deduplicated image
archive, and produces an xz-compressed archive. Runtime deploy/.env is never
included; the package contains deploy/.env.example only.
EOF
}

log() {
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

die() {
    log "ERROR: $*"
    exit 1
}

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
        --registry)
            PARSER_REGISTRY="$2"
            shift 2
            ;;
        --namespace)
            PARSER_NAMESPACE="$2"
            shift 2
            ;;
        --tag)
            PARSER_TAG="$2"
            shift 2
            ;;
        --output)
            OUTPUT_PATH="$2"
            shift 2
            ;;
        --platform)
            PARSER_PLATFORM="$2"
            shift 2
            ;;
        --keep-workdir)
            KEEP_WORKDIR=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1"
            ;;
    esac
done

CONTAINER_CMD=$(detect_container_cmd)
log "Using container runtime: $CONTAINER_CMD"

command -v xz >/dev/null 2>&1 || die "xz is required to compress the offline package (install xz-utils)"
XZ_LEVEL="${OPENDATAWORKS_XZ_LEVEL:-6}"

if [[ -z "$OUTPUT_PATH" ]]; then
    OUTPUT_PATH="opendataworks-deployment-$(date '+%Y%m%d-%H%M%S').tar.xz"
fi

if [[ -e "$OUTPUT_PATH" ]]; then
    die "output path already exists: $OUTPUT_PATH"
fi

WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/opendataworks-package.XXXXXXXX")
PACKAGE_ROOT="$WORKDIR/$PACKAGE_NAME"
trap '[[ "$KEEP_WORKDIR" = true ]] || rm -rf "$WORKDIR"' EXIT

log "Preparing deployment package workspace at $PACKAGE_ROOT"
mkdir -p "$PACKAGE_ROOT"

# 定义包内目录结构
PACKAGED_DEPLOY_DIR="$PACKAGE_ROOT/deploy"
PACKAGED_SCRIPTS_DIR="$PACKAGE_ROOT/scripts"
DEPLOY_IMAGE_DIR="$PACKAGED_DEPLOY_DIR/docker-images"

mkdir -p "$PACKAGED_DEPLOY_DIR"
mkdir -p "$PACKAGED_SCRIPTS_DIR"
mkdir -p "$DEPLOY_IMAGE_DIR"

# 1. 复制 deploy/ 下的内容
log "Copying deploy/ content to package deploy/"
tar -C "$REPO_ROOT/deploy" \
    --exclude='./.env' \
    --exclude='.env' \
    --exclude='docker-images/*.tar' \
    -cf - . | tar -C "$PACKAGED_DEPLOY_DIR" -xf -

# 2. 复制 scripts/ 下的内容 (excluding build/ which is for dev)
log "Copying scripts/ content to package scripts/"
tar -C "$REPO_ROOT/scripts" --exclude='build' -cf - . | tar -C "$PACKAGED_SCRIPTS_DIR" -xf -

# 4. 清理旧的 tar 包（如果不知何故被复制了）
rm -f "$DEPLOY_IMAGE_DIR/"*.tar 2>/dev/null || true

if [[ -d "$REPO_ROOT/database/mysql" ]]; then
    # 注意：database/mysql 已被删除，此逻辑仅作兼容保留，或应移除
    # 既然V2迁移已包含数据，可能不再需要，但保留以防万一用户重建了目录
    if [ "$(ls -A $REPO_ROOT/database/mysql)" ]; then
        log "Copying database/mysql scripts"
        mkdir -p "$PACKAGE_ROOT/database/mysql"
        tar -C "$REPO_ROOT/database/mysql" -cf - . | tar -C "$PACKAGE_ROOT/database/mysql" -xf -
    fi
fi

# 5. 复制文档
cp "$REPO_ROOT/deploy/README.md" "$PACKAGE_ROOT/README.md"
if [[ -f "$REPO_ROOT/docs/handbook/operations-guide.md" ]]; then
    cp "$REPO_ROOT/docs/handbook/operations-guide.md" "$PACKAGE_ROOT/OPERATIONS_GUIDE.md"
fi
if [[ -f "$REPO_ROOT/docs/handbook/testing-guide.md" ]]; then
    cp "$REPO_ROOT/docs/handbook/testing-guide.md" "$PACKAGE_ROOT/TESTING_GUIDE.md"
fi

# 6. 只处理环境配置模板。
# 运行时 deploy/.env 属于目标服务器，不得从打包机复制或在制品内生成。
rm -f "$PACKAGED_DEPLOY_DIR/.env"
if [[ ! -f "$PACKAGED_DEPLOY_DIR/.env.example" ]]; then
    die "deploy/.env.example is required for the offline package"
fi

rewrite_offline_env_file() {
    local env_file="$1"
    if [[ ! -f "$env_file" ]]; then
        return
    fi

    sed \
        -e "s|^# *OPENDATAWORKS_BACKEND_IMAGE=.*|OPENDATAWORKS_BACKEND_IMAGE=opendataworks-backend:${PARSER_TAG}|" \
        -e "s|^OPENDATAWORKS_BACKEND_IMAGE=.*|OPENDATAWORKS_BACKEND_IMAGE=opendataworks-backend:${PARSER_TAG}|" \
        -e "s|^# *OPENDATAWORKS_FRONTEND_IMAGE=.*|OPENDATAWORKS_FRONTEND_IMAGE=opendataworks-frontend:${PARSER_TAG}|" \
        -e "s|^OPENDATAWORKS_FRONTEND_IMAGE=.*|OPENDATAWORKS_FRONTEND_IMAGE=opendataworks-frontend:${PARSER_TAG}|" \
        -e "s|^# *OPENDATAWORKS_DATAAGENT_FRONTEND_IMAGE=.*|OPENDATAWORKS_DATAAGENT_FRONTEND_IMAGE=oda-frontend:${PARSER_TAG}|" \
        -e "s|^OPENDATAWORKS_DATAAGENT_FRONTEND_IMAGE=.*|OPENDATAWORKS_DATAAGENT_FRONTEND_IMAGE=oda-frontend:${PARSER_TAG}|" \
        -e "s|^# *OPENDATAWORKS_DATAAGENT_BACKEND_IMAGE=.*|OPENDATAWORKS_DATAAGENT_BACKEND_IMAGE=oda-backend:${PARSER_TAG}|" \
        -e "s|^OPENDATAWORKS_DATAAGENT_BACKEND_IMAGE=.*|OPENDATAWORKS_DATAAGENT_BACKEND_IMAGE=oda-backend:${PARSER_TAG}|" \
        -e "s|^# *OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE=.*|OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE=oda-runner:${PARSER_TAG}|" \
        -e "s|^OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE=.*|OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE=oda-runner:${PARSER_TAG}|" \
        -e "s|^# *OPENDATAWORKS_ODW_MCP_SERVER_IMAGE=.*|OPENDATAWORKS_ODW_MCP_SERVER_IMAGE=opendataworks-odw-mcp-server:${PARSER_TAG}|" \
        -e "s|^OPENDATAWORKS_ODW_MCP_SERVER_IMAGE=.*|OPENDATAWORKS_ODW_MCP_SERVER_IMAGE=opendataworks-odw-mcp-server:${PARSER_TAG}|" \
        -e "s|^# *DATAAGENT_SANDBOX_MODE=.*|DATAAGENT_SANDBOX_MODE=container|" \
        -e "s|^DATAAGENT_SANDBOX_MODE=.*|DATAAGENT_SANDBOX_MODE=container|" \
        "$env_file" > "${env_file}.tmp" && mv "${env_file}.tmp" "$env_file"

    grep -q '^OPENDATAWORKS_BACKEND_IMAGE=' "$env_file" 2>/dev/null || \
        { echo ""; echo "# 离线部署镜像（由 create-offline-package 自动设置）"; echo "OPENDATAWORKS_BACKEND_IMAGE=opendataworks-backend:${PARSER_TAG}"; } >> "$env_file"
    grep -q '^OPENDATAWORKS_FRONTEND_IMAGE=' "$env_file" 2>/dev/null || \
        echo "OPENDATAWORKS_FRONTEND_IMAGE=opendataworks-frontend:${PARSER_TAG}" >> "$env_file"
    grep -q '^OPENDATAWORKS_DATAAGENT_FRONTEND_IMAGE=' "$env_file" 2>/dev/null || \
        echo "OPENDATAWORKS_DATAAGENT_FRONTEND_IMAGE=oda-frontend:${PARSER_TAG}" >> "$env_file"
    grep -q '^OPENDATAWORKS_DATAAGENT_BACKEND_IMAGE=' "$env_file" 2>/dev/null || \
        echo "OPENDATAWORKS_DATAAGENT_BACKEND_IMAGE=oda-backend:${PARSER_TAG}" >> "$env_file"
    grep -q '^OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE=' "$env_file" 2>/dev/null || \
        echo "OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE=oda-runner:${PARSER_TAG}" >> "$env_file"
    grep -q '^OPENDATAWORKS_ODW_MCP_SERVER_IMAGE=' "$env_file" 2>/dev/null || \
        echo "OPENDATAWORKS_ODW_MCP_SERVER_IMAGE=opendataworks-odw-mcp-server:${PARSER_TAG}" >> "$env_file"
    # 离线包默认开启 sandbox 容器模式（每个 task 由 sandbox runner 启动独立 child 容器执行），
    # 源码部署仍以 .env.example 中的空值（关闭）为默认，二者不混淆。
    grep -q '^DATAAGENT_SANDBOX_MODE=' "$env_file" 2>/dev/null || \
        echo "DATAAGENT_SANDBOX_MODE=container" >> "$env_file"
}

rewrite_offline_env_file "$PACKAGED_DEPLOY_DIR/.env.example"

assert_package_has_no_runtime_env() {
    local unexpected_env
    unexpected_env="$(find "$PACKAGE_ROOT" -type f -name '.env' -print -quit)"
    if [[ -n "$unexpected_env" ]]; then
        die "runtime .env must not be included in the offline package: $unexpected_env"
    fi
}

declare -a MANIFEST_RAW=()

pull_image() {
    local image="$1"
    if [[ -n "$PARSER_PLATFORM" ]]; then
        "$CONTAINER_CMD" pull --platform "$PARSER_PLATFORM" "$image"
    else
        "$CONTAINER_CMD" pull "$image"
    fi
}

# 将多个镜像保存到同一个归档，使共享 base layer 去重（python:3.11-slim、nginx:alpine 等）。
# Docker 的 save 原生支持多镜像；Podman 需要 --multi-image-archive。
save_images() {
    local archive="$1"
    shift
    if [[ "$CONTAINER_CMD" == "podman" ]]; then
        "$CONTAINER_CMD" save --multi-image-archive -o "$DEPLOY_IMAGE_DIR/$archive" "$@"
    else
        "$CONTAINER_CMD" save -o "$DEPLOY_IMAGE_DIR/$archive" "$@"
    fi
}

retag_image() {
    local source="$1"
    local target="$2"
    if [[ "$source" != "$target" ]]; then
        "$CONTAINER_CMD" tag "$source" "$target"
    fi
}

get_digest() {
    local image="$1"
    "$CONTAINER_CMD" image inspect --format='{{index .RepoDigests 0}}' "$image" 2>/dev/null || true
}

compute_checksums() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$@"
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$@"
    else
        die "sha256sum or shasum binary is required to compute checksums"
    fi
}

OP_TAG="$PARSER_TAG"

# 评测镜像（evals-builtin / evals-deepeval）默认不随服务启动，且 deepeval 依赖很重，
MAIN_IMAGES=(
    "opendataworks-frontend.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-frontend:${OP_TAG}|opendataworks-frontend:${OP_TAG}"
    "opendataworks-backend.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-backend:${OP_TAG}|opendataworks-backend:${OP_TAG}"
    "oda-frontend.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/oda-frontend:${OP_TAG}|oda-frontend:${OP_TAG}"
    "oda-backend.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/oda-backend:${OP_TAG}|oda-backend:${OP_TAG}"
    "oda-runner.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/oda-runner:${OP_TAG}|oda-runner:${OP_TAG}"
    "opendataworks-odw-mcp-server.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-odw-mcp-server:${OP_TAG}|opendataworks-odw-mcp-server:${OP_TAG}"
)

EXTRA_IMAGES=(
    "mysql-8.0.tar|docker.io/library/mysql:8.0|mysql:8.0"
    "redis-7.2-alpine.tar|docker.io/library/redis:7.2-alpine|redis:7.2-alpine"
)

COMBINED_ARCHIVE="all-images.tar"
declare -a SAVE_TARGETS=()

log "Pulling application images from ${PARSER_REGISTRY}/${PARSER_NAMESPACE} tag ${OP_TAG}"
for entry in "${MAIN_IMAGES[@]}"; do
    IFS='|' read -r archive source target <<<"$entry"
    log "Pulling $source"
    pull_image "$source"
    retag_image "$source" "$target"
    SAVE_TARGETS+=("$target")
    digest=$(get_digest "$source")
    MANIFEST_RAW+=("$COMBINED_ARCHIVE|$source|$target|$digest")
done

log "Pulling dependency images"
for entry in "${EXTRA_IMAGES[@]}"; do
    IFS='|' read -r archive source target <<<"$entry"
    log "Pulling $source"
    pull_image "$source"
    retag_image "$source" "$target"
    SAVE_TARGETS+=("$target")
    digest=$(get_digest "$source")
    MANIFEST_RAW+=("$COMBINED_ARCHIVE|$source|$target|$digest")
done

# 一次性保存全部镜像，使共享层去重，显著减小镜像归档体积
log "Saving ${#SAVE_TARGETS[@]} images into deduplicated archive deploy/docker-images/$COMBINED_ARCHIVE"
save_images "$COMBINED_ARCHIVE" "${SAVE_TARGETS[@]}"

MANIFEST_FILE="$DEPLOY_IMAGE_DIR/manifest.json"
{
    printf '[\n'
    for i in "${!MANIFEST_RAW[@]}"; do
        IFS='|' read -r archive source target digest <<<"${MANIFEST_RAW[$i]}"
        printf '  {\n'
        printf '    "archive": "%s",\n' "$archive"
        printf '    "source": "%s",\n' "$source"
        printf '    "target": "%s"' "$target"
        if [[ -n "$digest" ]]; then
            printf ',\n    "digest": "%s"\n' "$digest"
        else
            printf '\n'
        fi
        if [[ "$i" -lt $((${#MANIFEST_RAW[@]} - 1)) ]]; then
            printf '  },\n'
        else
            printf '  }\n'
        fi
    done
    printf ']\n'
} > "$MANIFEST_FILE"

checksum_file="$DEPLOY_IMAGE_DIR/checksums.sha256"
log "Generating checksums"
(cd "$DEPLOY_IMAGE_DIR" && compute_checksums *.tar > checksums.tmp && mv checksums.tmp "$(basename "$checksum_file")")

assert_package_has_no_runtime_env

log "Creating xz archive $OUTPUT_PATH (level ${XZ_LEVEL}, multi-threaded)"
tar -C "$WORKDIR" -cf - "$PACKAGE_NAME" | xz -T0 "-${XZ_LEVEL}" -c > "$OUTPUT_PATH"

if [[ "$KEEP_WORKDIR" = true ]]; then
    log "Temporary workspace kept at: $WORKDIR"
else
    log "Cleaning up temporary workspace"
fi

log "Offline deployment package ready: $OUTPUT_PATH"
log "Included manifest: $PACKAGE_NAME/deploy/docker-images/manifest.json"
log "Image checksums: $PACKAGE_NAME/deploy/docker-images/checksums.sha256"
