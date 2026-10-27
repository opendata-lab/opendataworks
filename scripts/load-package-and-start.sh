#!/usr/bin/env bash

set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/load-package-and-start.sh [options] --package <path>

Options:
  --package <path>     Path to deployment tar.xz/tar.gz (from create-offline-package script) or extracted directory
  --target-dir <path>  Target directory for extraction when --package is an archive (default: ./opendataworks-deployment)
  --no-start           Load images only; skip compose up
  --no-env-copy        Do not auto-copy .env.example to .env when missing
  -h, --help           Show this help

The script extracts the offline deployment bundle (if needed), loads all docker-images/*.tar,
ensures .env exists, and starts the stack with docker compose / podman compose if requested.
EOF
}

log() {
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

die() {
    log "ERROR: $*"
    exit 1
}

PACKAGE_PATH=""
TARGET_DIR="./opendataworks-deployment"
DO_START=true
AUTO_COPY_ENV=true

while [[ $# -gt 0 ]]; do
    case "$1" in
        --package)
            PACKAGE_PATH="$2"
            shift 2
            ;;
        --target-dir)
            TARGET_DIR="$2"
            shift 2
            ;;
        --no-start)
            DO_START=false
            shift
            ;;
        --no-env-copy)
            AUTO_COPY_ENV=false
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            if [[ -z "$PACKAGE_PATH" ]]; then
                PACKAGE_PATH="$1"
                shift
            else
                die "unexpected argument: $1"
            fi
            ;;
    esac
done

if [[ -z "$PACKAGE_PATH" ]]; then
    die "package path is required (use --package)"
fi

if [[ ! -e "$PACKAGE_PATH" ]]; then
    die "package path does not exist: $PACKAGE_PATH"
fi

if [[ -f "$PACKAGE_PATH" ]]; then
    tar_decompress_flag=""
    case "$PACKAGE_PATH" in
        *.tar.xz|*.txz)
            command -v xz >/dev/null 2>&1 || die "xz is required to extract $PACKAGE_PATH (install xz-utils)"
            tar_decompress_flag="-J"
            ;;
        *.tar.gz|*.tgz)
            tar_decompress_flag="-z"
            ;;
        *)
            die "unsupported archive format: $PACKAGE_PATH (expected .tar.xz or .tar.gz)"
            ;;
    esac
    mkdir -p "$TARGET_DIR"
    if [[ "$(ls -A "$TARGET_DIR" 2>/dev/null)" ]]; then
        die "target directory not empty: $TARGET_DIR"
    fi
    log "Extracting $PACKAGE_PATH to $TARGET_DIR"
    tar "$tar_decompress_flag" -xf "$PACKAGE_PATH" --strip-components=1 -C "$TARGET_DIR"
    PACKAGE_DIR="$TARGET_DIR"
elif [[ -d "$PACKAGE_PATH" ]]; then
    PACKAGE_DIR="$PACKAGE_PATH"
else
    die "package path must be a tar.gz file or directory"
fi

if [[ -z "${PACKAGE_DIR:-}" ]]; then
    die "failed to determine package directory"
fi

# 后面会 cd 进包目录再调用其中的脚本，先换成绝对路径，相对路径（含默认的解压目录）才不会叠加。
PACKAGE_DIR="$(cd "$PACKAGE_DIR" && pwd)"

SCRIPTS_DIR="$PACKAGE_DIR/scripts"
ASSETS_DIR="$PACKAGE_DIR/deploy"

if [[ ! -d "$SCRIPTS_DIR" ]]; then
    die "scripts directory not found in package"
fi

if [[ ! -d "$ASSETS_DIR/docker-images" ]]; then
    die "deploy/docker-images directory not found in package"
fi

log "Loading docker images from $ASSETS_DIR/docker-images"
(cd "$PACKAGE_DIR" && bash "$SCRIPTS_DIR/load-images.sh")

if [[ "$AUTO_COPY_ENV" = true ]]; then
    if [[ ! -f "$ASSETS_DIR/.env" && -f "$ASSETS_DIR/.env.example" ]]; then
        log "No deploy/.env detected, copying from deploy/.env.example"
        cp "$ASSETS_DIR/.env.example" "$ASSETS_DIR/.env"
        log "Review $ASSETS_DIR/.env and review database credentials, JWT secret and service tokens"
    fi
fi

# 镜像 tag 属于离线包本身：升级时把已有 .env 中的 *_IMAGE 换成本包的值，其他配置保持不变。
if [[ -f "$ASSETS_DIR/.env" && -f "$ASSETS_DIR/.env.example" ]]; then
    while IFS= read -r image_line; do
        image_key="${image_line%%=*}"
        if grep -q "^${image_key}=" "$ASSETS_DIR/.env"; then
            sed "s|^${image_key}=.*|${image_line}|" "$ASSETS_DIR/.env" > "$ASSETS_DIR/.env.tmp" && mv "$ASSETS_DIR/.env.tmp" "$ASSETS_DIR/.env"
        else
            echo "$image_line" >> "$ASSETS_DIR/.env"
        fi
    done < <(grep -E '^[A-Z0-9_]+_IMAGE=' "$ASSETS_DIR/.env.example" || true)
fi

if [[ "$DO_START" = true ]]; then
    log "Starting services via scripts/start.sh"
    (cd "$PACKAGE_DIR" && bash "scripts/start.sh")
else
    log "Skipping compose start (--no-start specified)"
fi

log "Deployment assets are ready in: $PACKAGE_DIR"
