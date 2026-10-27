#!/bin/bash

# OpenDataWorks 镜像构建脚本
# 功能：构建所有服务的 Docker 镜像并导出为 tar 包
# 支持 Docker 和 Podman

set -e

echo "========================================="
echo "  OpenDataWorks 镜像构建脚本"
echo "========================================="
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# 检测使用 Docker 还是 Podman
if command -v docker &> /dev/null; then
    CONTAINER_CMD="docker"
    echo "✓ 检测到 Docker"
elif command -v podman &> /dev/null; then
    CONTAINER_CMD="podman"
    echo "✓ 检测到 Podman"
else
    echo "❌ 错误: 未找到 Docker 或 Podman"
    echo "请先安装 Docker 或 Podman"
    exit 1
fi
echo ""

# 定义镜像名称和版本
FRONTEND_IMAGE="opendataworks-frontend:latest"
BACKEND_IMAGE="opendataworks-backend:latest"
ODW_MCP_SERVER_IMAGE="opendataworks-odw-mcp-server:latest"
MYSQL_IMAGE="mysql:8.0"
REDIS_IMAGE="redis:7.2-alpine"
LOG_COLLECTOR_IMAGE="docker:27.5.1-cli"

# 创建输出目录
OUTPUT_DIR="$REPO_ROOT/deploy/docker-images"
mkdir -p "$OUTPUT_DIR"

PIP_BUILD_ARGS=()
if [ -n "${PIP_INDEX_URL:-}" ]; then
    PIP_BUILD_ARGS+=(--build-arg "PIP_INDEX_URL=$PIP_INDEX_URL")
fi
if [ -n "${PIP_TRUSTED_HOST:-}" ]; then
    PIP_BUILD_ARGS+=(--build-arg "PIP_TRUSTED_HOST=$PIP_TRUSTED_HOST")
fi

echo "📦 步骤 1/8: 构建前端镜像 (AMD64 架构)..."
cd "$REPO_ROOT/frontend"
$CONTAINER_CMD build --platform linux/amd64 -t $FRONTEND_IMAGE .
cd "$REPO_ROOT"
echo "✅ 前端镜像构建完成"
echo ""

echo "📦 步骤 2/8: 构建后端镜像 (AMD64 架构)..."
$CONTAINER_CMD build --platform linux/amd64 -t $BACKEND_IMAGE \
  -f backend/Dockerfile \
  .
echo "✅ 后端镜像构建完成"
echo ""

echo "📦 步骤 8/8: 构建 ODW MCP Server 镜像 (AMD64 架构)..."
cd "$REPO_ROOT"
$CONTAINER_CMD build --platform linux/amd64 -t $ODW_MCP_SERVER_IMAGE \
  "${PIP_BUILD_ARGS[@]}" \
  -f odw-mcp-servers/opendataworks/Dockerfile \
  .
echo "✅ ODW MCP Server 镜像构建完成"
echo ""

echo "📦 拉取 MySQL、Redis 与日志收集运行时镜像..."
for image in $MYSQL_IMAGE $REDIS_IMAGE $LOG_COLLECTOR_IMAGE; do
    $CONTAINER_CMD pull --platform linux/amd64 "$image"
done
echo "✅ 运行时镜像拉取完成"
echo ""

echo "📦 导出镜像为去重合并归档 all-images.tar ..."
# 一次性保存全部镜像，使共享 base layer（python:3.11-slim、nginx:alpine 等）去重
ALL_IMAGES=(
    "$FRONTEND_IMAGE"
    "$BACKEND_IMAGE"
    "$ODW_MCP_SERVER_IMAGE"
    "$MYSQL_IMAGE"
    "$REDIS_IMAGE"
    "$LOG_COLLECTOR_IMAGE"
)
if [ "$CONTAINER_CMD" = "podman" ]; then
    $CONTAINER_CMD save --multi-image-archive -o "$OUTPUT_DIR/all-images.tar" "${ALL_IMAGES[@]}"
else
    $CONTAINER_CMD save -o "$OUTPUT_DIR/all-images.tar" "${ALL_IMAGES[@]}"
fi

echo "✅ 所有镜像导出完成 -> $OUTPUT_DIR/all-images.tar"
echo ""

echo "========================================="
echo "  镜像构建和导出完成！"
echo "========================================="
echo ""
echo "📁 镜像文件保存在: $OUTPUT_DIR/"
ls -lh "$OUTPUT_DIR"/*.tar
echo ""
echo "镜像清单："
echo "  ✓ $FRONTEND_IMAGE"
echo "  ✓ $BACKEND_IMAGE"
echo "  ✓ $ODW_MCP_SERVER_IMAGE"
echo "  ✓ $REDIS_IMAGE"
echo ""
echo "📝 下一步："
echo "  1. 将 $OUTPUT_DIR/ 目录下的所有 tar 文件传输到内网服务器"
echo "  2. 在内网服务器上运行 deploy/load-images.sh 加载镜像"
echo "  3. 运行 deploy/start.sh 启动服务"
echo ""
