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
  --output <path>           Output tar.gz path (default: ./opendataworks-deployment-<timestamp>.tar.gz)
  --platform <platform>     Optional pull platform (e.g. linux/amd64)
  --keep-workdir            Do not delete temporary build directory (for debugging)
  -h, --help                Show this help

Environment overrides:
  OPENDATAWORKS_REGISTRY, OPENDATAWORKS_NAMESPACE, OPENDATAWORKS_TAG,
  OPENDATAWORKS_PLATFORM

The script packages current scripts/ and deploy/ content, pulls required images
from Docker Hub, retags them, and produces a compressed archive.
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

if [[ -z "$OUTPUT_PATH" ]]; then
    OUTPUT_PATH="opendataworks-deployment-$(date '+%Y%m%d-%H%M%S').tar.gz"
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
PACKAGED_TOOLS_DIR="$PACKAGE_ROOT/tools"
PACKAGED_DATAAGENT_RUNTIME_DIR="$PACKAGED_DEPLOY_DIR/dataagent-runtime"
DEPLOY_IMAGE_DIR="$PACKAGED_DEPLOY_DIR/docker-images"

mkdir -p "$PACKAGED_DEPLOY_DIR"
mkdir -p "$PACKAGED_SCRIPTS_DIR"
mkdir -p "$PACKAGED_TOOLS_DIR"
mkdir -p "$PACKAGED_DATAAGENT_RUNTIME_DIR/skills"
mkdir -p "$DEPLOY_IMAGE_DIR"

# 1. 复制 deploy/ 下的内容
log "Copying deploy/ content to package deploy/"
tar -C "$REPO_ROOT/deploy" --exclude='docker-images/*.tar' -cf - . | tar -C "$PACKAGED_DEPLOY_DIR" -xf -

# 2. 复制 scripts/ 下的内容 (excluding build/ which is for dev)
log "Copying scripts/ content to package scripts/"
tar -C "$REPO_ROOT/scripts" --exclude='build' -cf - . | tar -C "$PACKAGED_SCRIPTS_DIR" -xf -

# 2.1 复制通用评测工具。私有评测集不随离线包内置，运行时通过 --dataset 指定。
if [[ -d "$REPO_ROOT/tools/dataagent-evals" ]]; then
    log "Copying DataAgent eval tools"
    mkdir -p "$PACKAGED_TOOLS_DIR/dataagent-evals"
    tar -C "$REPO_ROOT/tools/dataagent-evals" -cf - . | tar -C "$PACKAGED_TOOLS_DIR/dataagent-evals" -xf -
fi

# 3. 复制 DataAgent 运行时配置（不打包整个 dataagent 源码目录）
if [[ -f "$REPO_ROOT/dataagent/.claude/settings.json" ]]; then
    log "Copying DataAgent runtime settings"
    cp "$REPO_ROOT/dataagent/.claude/settings.json" "$PACKAGED_DATAAGENT_RUNTIME_DIR/settings.json"
else
    log "No DataAgent settings.json found, creating empty runtime settings"
    printf '{\n}\n' > "$PACKAGED_DATAAGENT_RUNTIME_DIR/settings.json"
fi

if [[ -d "$REPO_ROOT/dataagent/.claude/skills" ]]; then
    log "Copying DataAgent editable skills"
    tar -C "$REPO_ROOT/dataagent/.claude/skills" --exclude='*-assistant' -cf - . | tar -C "$PACKAGED_DATAAGENT_RUNTIME_DIR/skills" -xf -
fi

# 离线部署包必须保证 skills 目录树对容器内非 root 用户（uid 1000）可访问：
# - 所有目录至少 755（a+rX 中 X 只对目录和已有 x 的文件生效）
# - 所有文件至少可读（a+r）
# - odw-cli 需要执行权限
chmod -R a+rX "$PACKAGED_DATAAGENT_RUNTIME_DIR/skills"
if [[ -f "$PACKAGED_DATAAGENT_RUNTIME_DIR/skills/dataagent-nl2sql/bin/odw-cli" ]]; then
    chmod +x "$PACKAGED_DATAAGENT_RUNTIME_DIR/skills/dataagent-nl2sql/bin/odw-cli"
    log "Ensured skills directory permissions and odw-cli execute bit for offline package"
fi

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

# 6. 处理 .env 文件
# 优先使用 deploy/.env（若已存在），否则尝试仓库根 .env，最后回退到示例
ROOT_ENV_FILE="$REPO_ROOT/.env"
ROOT_ENV_EXAMPLE="$REPO_ROOT/deploy/.env.example"

# 如果 deploy 目录里已经有了 .env (从上面 tar 复制过来的)，则保留
if [[ ! -f "$PACKAGED_DEPLOY_DIR/.env" ]]; then
    if [[ -f "$ROOT_ENV_FILE" ]]; then
        log "Copying repository .env to deploy/.env"
        cp "$ROOT_ENV_FILE" "$PACKAGED_DEPLOY_DIR/.env"
    elif [[ -f "$ROOT_ENV_EXAMPLE" ]]; then
        log "No .env found, copying .env.example as deploy/.env"
        cp "$ROOT_ENV_EXAMPLE" "$PACKAGED_DEPLOY_DIR/.env"
    else
        log "WARNING: neither .env nor .env.example found at repository root"
    fi
fi

# 确保 .env.example 存在
if [[ ! -f "$PACKAGED_DEPLOY_DIR/.env.example" ]]; then
    if [[ -f "$ROOT_ENV_EXAMPLE" ]]; then
         cp "$ROOT_ENV_EXAMPLE" "$PACKAGED_DEPLOY_DIR/.env.example"
    fi
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
        -e "s|^# *OPENDATAWORKS_DATAAGENT_BACKEND_IMAGE=.*|OPENDATAWORKS_DATAAGENT_BACKEND_IMAGE=opendataworks-dataagent-backend:${PARSER_TAG}|" \
        -e "s|^OPENDATAWORKS_DATAAGENT_BACKEND_IMAGE=.*|OPENDATAWORKS_DATAAGENT_BACKEND_IMAGE=opendataworks-dataagent-backend:${PARSER_TAG}|" \
        -e "s|^# *OPENDATAWORKS_DATAAGENT_EVALS_BUILTIN_IMAGE=.*|OPENDATAWORKS_DATAAGENT_EVALS_BUILTIN_IMAGE=opendataworks-dataagent-evals-builtin:${PARSER_TAG}|" \
        -e "s|^OPENDATAWORKS_DATAAGENT_EVALS_BUILTIN_IMAGE=.*|OPENDATAWORKS_DATAAGENT_EVALS_BUILTIN_IMAGE=opendataworks-dataagent-evals-builtin:${PARSER_TAG}|" \
        -e "s|^# *OPENDATAWORKS_DATAAGENT_EVALS_DEEPEVAL_IMAGE=.*|OPENDATAWORKS_DATAAGENT_EVALS_DEEPEVAL_IMAGE=opendataworks-dataagent-evals-deepeval:${PARSER_TAG}|" \
        -e "s|^OPENDATAWORKS_DATAAGENT_EVALS_DEEPEVAL_IMAGE=.*|OPENDATAWORKS_DATAAGENT_EVALS_DEEPEVAL_IMAGE=opendataworks-dataagent-evals-deepeval:${PARSER_TAG}|" \
        -e "s|^# *OPENDATAWORKS_PORTAL_MCP_IMAGE=.*|OPENDATAWORKS_PORTAL_MCP_IMAGE=opendataworks-portal-mcp:${PARSER_TAG}|" \
        -e "s|^OPENDATAWORKS_PORTAL_MCP_IMAGE=.*|OPENDATAWORKS_PORTAL_MCP_IMAGE=opendataworks-portal-mcp:${PARSER_TAG}|" \
        -e "s|^DATAAGENT_LLM_JSON_FILE=.*|DATAAGENT_LLM_JSON_FILE=./dataagent-runtime/settings.json|" \
        -e "s|^DATAAGENT_SKILLS_DIR=.*|DATAAGENT_SKILLS_DIR=./dataagent-runtime/skills|" \
        "$env_file" > "${env_file}.tmp" && mv "${env_file}.tmp" "$env_file"

    grep -q '^OPENDATAWORKS_BACKEND_IMAGE=' "$env_file" 2>/dev/null || \
        { echo ""; echo "# 离线部署镜像（由 create-offline-package 自动设置）"; echo "OPENDATAWORKS_BACKEND_IMAGE=opendataworks-backend:${PARSER_TAG}"; } >> "$env_file"
    grep -q '^OPENDATAWORKS_FRONTEND_IMAGE=' "$env_file" 2>/dev/null || \
        echo "OPENDATAWORKS_FRONTEND_IMAGE=opendataworks-frontend:${PARSER_TAG}" >> "$env_file"
    grep -q '^OPENDATAWORKS_DATAAGENT_BACKEND_IMAGE=' "$env_file" 2>/dev/null || \
        echo "OPENDATAWORKS_DATAAGENT_BACKEND_IMAGE=opendataworks-dataagent-backend:${PARSER_TAG}" >> "$env_file"
    grep -q '^OPENDATAWORKS_DATAAGENT_EVALS_BUILTIN_IMAGE=' "$env_file" 2>/dev/null || \
        echo "OPENDATAWORKS_DATAAGENT_EVALS_BUILTIN_IMAGE=opendataworks-dataagent-evals-builtin:${PARSER_TAG}" >> "$env_file"
    grep -q '^OPENDATAWORKS_DATAAGENT_EVALS_DEEPEVAL_IMAGE=' "$env_file" 2>/dev/null || \
        echo "OPENDATAWORKS_DATAAGENT_EVALS_DEEPEVAL_IMAGE=opendataworks-dataagent-evals-deepeval:${PARSER_TAG}" >> "$env_file"
    grep -q '^OPENDATAWORKS_PORTAL_MCP_IMAGE=' "$env_file" 2>/dev/null || \
        echo "OPENDATAWORKS_PORTAL_MCP_IMAGE=opendataworks-portal-mcp:${PARSER_TAG}" >> "$env_file"
    grep -q '^DATAAGENT_LLM_JSON_FILE=' "$env_file" 2>/dev/null || \
        echo "DATAAGENT_LLM_JSON_FILE=./dataagent-runtime/settings.json" >> "$env_file"
    grep -q '^DATAAGENT_SKILLS_DIR=' "$env_file" 2>/dev/null || \
        echo "DATAAGENT_SKILLS_DIR=./dataagent-runtime/skills" >> "$env_file"
}

rewrite_offline_env_file "$PACKAGED_DEPLOY_DIR/.env"
rewrite_offline_env_file "$PACKAGED_DEPLOY_DIR/.env.example"

declare -a MANIFEST_RAW=()

pull_image() {
    local image="$1"
    if [[ -n "$PARSER_PLATFORM" ]]; then
        "$CONTAINER_CMD" pull --platform "$PARSER_PLATFORM" "$image"
    else
        "$CONTAINER_CMD" pull "$image"
    fi
}

save_image() {
    local image="$1"
    local archive="$2"
    "$CONTAINER_CMD" save -o "$DEPLOY_IMAGE_DIR/$archive" "$image"
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

MAIN_IMAGES=(
    "opendataworks-frontend.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-frontend:${OP_TAG}|opendataworks-frontend:${OP_TAG}"
    "opendataworks-backend.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-backend:${OP_TAG}|opendataworks-backend:${OP_TAG}"
    "opendataworks-dataagent-backend.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-dataagent-backend:${OP_TAG}|opendataworks-dataagent-backend:${OP_TAG}"
    "opendataworks-dataagent-evals-builtin.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-dataagent-evals-builtin:${OP_TAG}|opendataworks-dataagent-evals-builtin:${OP_TAG}"
    "opendataworks-dataagent-evals-deepeval.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-dataagent-evals-deepeval:${OP_TAG}|opendataworks-dataagent-evals-deepeval:${OP_TAG}"
    "opendataworks-portal-mcp.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-portal-mcp:${OP_TAG}|opendataworks-portal-mcp:${OP_TAG}"
)

EXTRA_IMAGES=(
    "mysql-8.0.tar|docker.io/library/mysql:8.0|mysql:8.0"
    "redis-7.2-alpine.tar|docker.io/library/redis:7.2-alpine|redis:7.2-alpine"
)

log "Pulling application images from ${PARSER_REGISTRY}/${PARSER_NAMESPACE} tag ${OP_TAG}"
for entry in "${MAIN_IMAGES[@]}"; do
    IFS='|' read -r archive source target <<<"$entry"
    log "Pulling $source"
    pull_image "$source"
    retag_image "$source" "$target"
    log "Saving $target to deploy/docker-images/$archive"
    save_image "$target" "$archive"
    digest=$(get_digest "$source")
    MANIFEST_RAW+=("$archive|$source|$target|$digest")
done

log "Pulling dependency images"
for entry in "${EXTRA_IMAGES[@]}"; do
    IFS='|' read -r archive source target <<<"$entry"
    log "Pulling $source"
    pull_image "$source"
    retag_image "$source" "$target"
    log "Saving $target to deploy/docker-images/$archive"
    save_image "$target" "$archive"
    digest=$(get_digest "$source")
    MANIFEST_RAW+=("$archive|$source|$target|$digest")
done

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

log "Creating archive $OUTPUT_PATH"
tar -C "$WORKDIR" -czf "$OUTPUT_PATH" "$PACKAGE_NAME"

if [[ "$KEEP_WORKDIR" = true ]]; then
    log "Temporary workspace kept at: $WORKDIR"
else
    log "Cleaning up temporary workspace"
fi

log "Offline deployment package ready: $OUTPUT_PATH"
log "Included manifest: $PACKAGE_NAME/deploy/docker-images/manifest.json"
log "Image checksums: $PACKAGE_NAME/deploy/docker-images/checksums.sha256"
