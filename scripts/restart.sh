#!/bin/bash

# OpenDataWorks 重启脚本
# 功能：重启所有服务

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY_DIR="$REPO_ROOT/deploy"
LIB_DIR="$SCRIPT_DIR/lib"
COMPOSE_FILE_NAME="docker-compose.prod.yml"
COMPOSE_FILE="$DEPLOY_DIR/$COMPOSE_FILE_NAME"
ENV_FILE="$DEPLOY_DIR/.env"

# shellcheck source=/dev/null
source "$LIB_DIR/container-runtime.sh"
# shellcheck source=/dev/null
source "$LIB_DIR/site-config.sh"

if ! detect_compose_cmd; then
    echo "❌ 错误: 未找到可用的 compose 命令（docker-compose、docker compose、podman compose、podman-compose）"
    exit 1
fi

echo "========================================="
echo "  OpenDataWorks 重启脚本"
echo "========================================="
echo ""

ensure_site_configs "$DEPLOY_DIR" || exit 1

echo "🔄 重启 OpenDataWorks 服务..."
pushd "$DEPLOY_DIR" >/dev/null
ENV_FLAG_ARGS=()
if [ "$COMPOSE_SUPPORTS_ENV_FILE" = true ] && [ -f "$ENV_FILE" ]; then
    ENV_FLAG_ARGS=(--env-file "$ENV_FILE")
fi

"${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" "${ENV_FLAG_ARGS[@]}" restart

echo ""
echo "⏳ 等待服务重启..."
sleep 5

echo ""
echo "========================================="
echo "  服务状态"
echo "========================================="
echo ""

# 显示服务状态
"${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" "${ENV_FLAG_ARGS[@]}" ps
popd >/dev/null

echo ""
echo "✅ 服务重启完成"
echo ""
