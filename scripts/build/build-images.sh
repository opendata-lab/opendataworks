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
DATAAGENT_FRONTEND_IMAGE="opendataworks-dataagent-frontend:latest"
DATAAGENT_BACKEND_IMAGE="opendataworks-dataagent-backend:latest"
DATAAGENT_RUNNER_IMAGE="opendataworks-dataagent-runner:latest"
DATAAGENT_EVALS_BUILTIN_IMAGE="opendataworks-dataagent-evals-builtin:latest"
DATAAGENT_EVALS_DEEPEVAL_IMAGE="opendataworks-dataagent-evals-deepeval:latest"
PORTAL_MCP_IMAGE="opendataworks-portal-mcp:latest"
REDIS_IMAGE="redis:7.2-alpine"

# 创建输出目录
OUTPUT_DIR="$REPO_ROOT/deploy/docker-images"
mkdir -p "$OUTPUT_DIR"

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

echo "📦 步骤 3/8: 构建 DataAgent 前端镜像 (AMD64 架构)..."
cd "$REPO_ROOT"
$CONTAINER_CMD build --platform linux/amd64 -t $DATAAGENT_FRONTEND_IMAGE \
  -f dataagent/dataagent-frontend/Dockerfile \
  .
echo "✅ DataAgent 前端镜像构建完成"
echo ""

echo "📦 步骤 4/8: 构建 DataAgent 后端镜像 (AMD64 架构)..."
cd "$REPO_ROOT"
$CONTAINER_CMD build --platform linux/amd64 -t $DATAAGENT_BACKEND_IMAGE \
  -f dataagent/dataagent-backend/Dockerfile \
  .
echo "✅ DataAgent 后端镜像构建完成"
echo ""

echo "📦 步骤 5/8: 构建 DataAgent Runner 镜像 (AMD64 架构)..."
cd "$REPO_ROOT"
$CONTAINER_CMD build --platform linux/amd64 -t $DATAAGENT_RUNNER_IMAGE \
  -f dataagent/dataagent-backend/Dockerfile.runner \
  .
echo "✅ DataAgent Runner 镜像构建完成"
echo ""

echo "📦 步骤 6/8: 构建 DataAgent builtin 评测镜像 (AMD64 架构)..."
cd "$REPO_ROOT"
$CONTAINER_CMD build --platform linux/amd64 -t $DATAAGENT_EVALS_BUILTIN_IMAGE \
  -f tools/dataagent-evals/builtin/Dockerfile \
  .
echo "✅ DataAgent builtin 评测镜像构建完成"
echo ""

echo "📦 步骤 7/8: 构建 DataAgent DeepEval 评测镜像 (AMD64 架构)..."
cd "$REPO_ROOT"
$CONTAINER_CMD build --platform linux/amd64 -t $DATAAGENT_EVALS_DEEPEVAL_IMAGE \
  -f tools/dataagent-evals/deepeval/Dockerfile \
  .
echo "✅ DataAgent DeepEval 评测镜像构建完成"
echo ""

echo "📦 步骤 8/8: 构建 Portal MCP 镜像 (AMD64 架构)..."
cd "$REPO_ROOT"
$CONTAINER_CMD build --platform linux/amd64 -t $PORTAL_MCP_IMAGE \
  -f dataagent/portal-mcp/Dockerfile \
  .
echo "✅ Portal MCP 镜像构建完成"
echo ""

echo "📦 拉取 Redis 运行时镜像..."
$CONTAINER_CMD pull --platform linux/amd64 $REDIS_IMAGE
echo "✅ Redis 镜像拉取完成"
echo ""

echo "📦 导出镜像为去重合并归档 all-images.tar ..."
# 一次性保存全部镜像，使共享 base layer（python:3.11-slim、nginx:alpine 等）去重
ALL_IMAGES=(
    "$FRONTEND_IMAGE"
    "$BACKEND_IMAGE"
    "$DATAAGENT_FRONTEND_IMAGE"
    "$DATAAGENT_BACKEND_IMAGE"
    "$DATAAGENT_RUNNER_IMAGE"
    "$DATAAGENT_EVALS_BUILTIN_IMAGE"
    "$DATAAGENT_EVALS_DEEPEVAL_IMAGE"
    "$PORTAL_MCP_IMAGE"
    "$REDIS_IMAGE"
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
echo "  ✓ $DATAAGENT_FRONTEND_IMAGE"
echo "  ✓ $DATAAGENT_BACKEND_IMAGE"
echo "  ✓ $DATAAGENT_RUNNER_IMAGE"
echo "  ✓ $DATAAGENT_EVALS_BUILTIN_IMAGE"
echo "  ✓ $DATAAGENT_EVALS_DEEPEVAL_IMAGE"
echo "  ✓ $PORTAL_MCP_IMAGE"
echo "  ✓ $REDIS_IMAGE"
echo ""
echo "📝 下一步："
echo "  1. 将 $OUTPUT_DIR/ 目录下的所有 tar 文件传输到内网服务器"
echo "  2. 在内网服务器上运行 deploy/load-images.sh 加载镜像"
echo "  3. 运行 deploy/start.sh 启动服务"
echo ""
