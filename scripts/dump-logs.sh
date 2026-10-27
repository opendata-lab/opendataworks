#!/bin/bash

# OpenDataWorks 日志导出脚本
# 功能：把全部服务容器日志汇总成宿主机包目录文件，便于排障/打包带走。
#
# 服务日志来自 compose（json-file 驱动，容器重启不丢），导出到:
#   deploy/logs/services/<service>.log

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY_DIR="$REPO_ROOT/deploy"
LIB_DIR="$SCRIPT_DIR/lib"
COMPOSE_FILE_NAME="docker-compose.prod.yml"
COMPOSE_FILE="$DEPLOY_DIR/$COMPOSE_FILE_NAME"
ENV_FILE="$DEPLOY_DIR/.env"
OUTPUT_DIR="$DEPLOY_DIR/logs"
SERVICES_DIR="$OUTPUT_DIR/services"

# shellcheck source=/dev/null
source "$LIB_DIR/container-runtime.sh"

if [ ! -f "$COMPOSE_FILE" ]; then
    echo "❌ 错误: 未找到 $COMPOSE_FILE"
    exit 1
fi

if ! detect_compose_cmd; then
    echo "❌ 错误: 未找到可用的 compose 命令（docker-compose、docker compose、podman compose、podman-compose）"
    exit 1
fi

echo "========================================="
echo "  OpenDataWorks 日志导出"
echo "========================================="
echo ""

mkdir -p "$SERVICES_DIR"

ENV_ARGS=()
if [ "$COMPOSE_SUPPORTS_ENV_FILE" = true ] && [ -f "$ENV_FILE" ]; then
    ENV_ARGS=(--env-file "$ENV_FILE")
fi

pushd "$DEPLOY_DIR" >/dev/null

# 解析服务列表（失败则回退到已知服务清单）
SERVICES="$("${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" "${ENV_ARGS[@]}" config --services 2>/dev/null || true)"
if [ -z "$SERVICES" ]; then
    SERVICES="mysql redis backend frontend odw-mcp-server log-collector"
fi

echo "📦 导出服务日志 -> $SERVICES_DIR"
for svc in $SERVICES; do
    out="$SERVICES_DIR/${svc}.log"
    if "${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" "${ENV_ARGS[@]}" logs --no-color --timestamps "$svc" >"$out" 2>/dev/null; then
        echo "  ✅ $svc -> $(basename "$out")"
    else
        echo "stale or not running" >"$out"
        echo "  ⚠️  $svc 无日志或容器不存在"
    fi
done

popd >/dev/null

echo ""
echo "========================================="
echo "  导出完成"
echo "========================================="
echo "📁 日志目录: $OUTPUT_DIR"
echo "   服务日志:   $SERVICES_DIR"
echo ""
echo "可直接查看，或打包带走: tar -czf odw-logs.tgz -C \"$DEPLOY_DIR\" logs"
echo ""
