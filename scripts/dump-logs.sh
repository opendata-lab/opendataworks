#!/bin/bash

# OpenDataWorks 日志导出脚本
# 功能：把全部服务容器日志 + 既有 task child 日志汇总成宿主机包目录文件，便于排障/打包带走。
#
# 服务日志来自 compose（json-file 驱动，容器重启不丢），导出到:
#   deploy/logs/services/<service>.log
# task child 日志由 dataagent-sandbox-runner 实时写在挂载卷上（--rm 删容器不影响），来自:
#   <DATAAGENT_HOST_ROOT>/<topic>/logs/<task>.log
# 复制到:
#   deploy/logs/task-child/<topic>/<task>.log

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
CHILD_DIR="$OUTPUT_DIR/task-child"

# shellcheck source=/dev/null
source "$LIB_DIR/container-runtime.sh"

read_env_value() {
    local key="$1"
    local env_file="$2"
    [ -f "$env_file" ] || return 0
    local line
    line="$(grep -E "^${key}=" "$env_file" | tail -n 1 || true)"
    printf '%s' "${line#*=}"
}

resolve_dataagent_host_root() {
    # 与 start.sh 保持一致：默认 /dataagent_runtime；相对路径按 deploy/ 解析。
    local configured
    configured="$(read_env_value "DATAAGENT_HOST_ROOT" "$ENV_FILE")"
    [ -n "$configured" ] || configured="/dataagent_runtime"
    if [[ "$configured" = /* ]]; then
        printf '%s\n' "$configured"
    else
        printf '%s\n' "$DEPLOY_DIR/$configured"
    fi
}

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

mkdir -p "$SERVICES_DIR" "$CHILD_DIR"

ENV_ARGS=()
if [ "$COMPOSE_SUPPORTS_ENV_FILE" = true ] && [ -f "$ENV_FILE" ]; then
    ENV_ARGS=(--env-file "$ENV_FILE")
fi

pushd "$DEPLOY_DIR" >/dev/null

# 解析服务列表（失败则回退到已知服务清单）
SERVICES="$("${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" "${ENV_ARGS[@]}" config --services 2>/dev/null || true)"
if [ -z "$SERVICES" ]; then
    SERVICES="mysql redis backend frontend dataagent-frontend dataagent-home-init dataagent-backend dataagent-sandbox-runner odw-mcp-server"
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

# task child 日志（runner 已落盘在挂载卷上）
HOST_ROOT="$(resolve_dataagent_host_root)"
echo ""
echo "🧩 汇总 task child 日志 (DATAAGENT_HOST_ROOT=$HOST_ROOT) -> $CHILD_DIR"
if [ -d "$HOST_ROOT" ]; then
    child_count=0
    # 结构: <HOST_ROOT>/<topic>/logs/<task>.log
    while IFS= read -r -d '' logfile; do
        topic="$(basename "$(dirname "$(dirname "$logfile")")")"
        dest_dir="$CHILD_DIR/$topic"
        mkdir -p "$dest_dir"
        if cp -f "$logfile" "$dest_dir/" 2>/dev/null; then
            child_count=$((child_count + 1))
        else
            echo "  ⚠️  无法读取 $logfile（权限不足？尝试用 root 运行）"
        fi
    done < <(find "$HOST_ROOT" -mindepth 3 -maxdepth 3 -type f -path '*/logs/*.log' -print0 2>/dev/null)
    echo "  ✅ 已复制 $child_count 个 task child 日志文件"
else
    echo "  ⚠️  未找到运行时根目录 $HOST_ROOT，跳过 child 日志"
fi

echo ""
echo "========================================="
echo "  导出完成"
echo "========================================="
echo "📁 日志目录: $OUTPUT_DIR"
echo "   服务日志:   $SERVICES_DIR"
echo "   task child: $CHILD_DIR"
echo ""
echo "可直接查看，或打包带走: tar -czf odw-logs.tgz -C \"$DEPLOY_DIR\" logs"
echo ""
