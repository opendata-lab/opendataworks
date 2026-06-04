#!/bin/bash

# OpenDataWorks 评测镜像加载脚本（评测离线附加包使用）
# 功能：从评测附加包加载 DataAgent 评测镜像
# 支持 Docker 和 Podman

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "========================================="
echo "  OpenDataWorks 评测镜像加载脚本"
echo "========================================="
echo ""

if command -v docker &> /dev/null; then
    CONTAINER_CMD="docker"
    echo "✓ 检测到 Docker"
elif command -v podman &> /dev/null; then
    CONTAINER_CMD="podman"
    echo "✓ 检测到 Podman"
else
    echo "❌ 错误: 未找到 Docker 或 Podman"
    exit 1
fi
echo ""

# 兼容主包目录布局（deploy/docker-images）与附加包布局（docker-images）
if [ -f "$PACKAGE_ROOT/docker-images/evals-images.tar" ]; then
    IMAGE_DIR="$PACKAGE_ROOT/docker-images"
elif [ -f "$PACKAGE_ROOT/deploy/docker-images/evals-images.tar" ]; then
    IMAGE_DIR="$PACKAGE_ROOT/deploy/docker-images"
else
    echo "❌ 错误: 未找到 evals-images.tar"
    echo "请在评测附加包解压目录内运行本脚本"
    exit 1
fi

echo "📦 加载评测镜像（去重单归档）..."
$CONTAINER_CMD load -i "$IMAGE_DIR/evals-images.tar"
echo "✅ 评测镜像加载完成"
echo ""

# 修复 localhost 前缀问题（Podman 加载可能带 localhost/ 前缀）
IMAGE_TAG="latest"
if [[ -f "$IMAGE_DIR/manifest.json" ]]; then
    _tag=$(grep -o '"target": *"opendataworks-dataagent-evals-builtin:[^"]*"' "$IMAGE_DIR/manifest.json" 2>/dev/null | sed 's/.*opendataworks-dataagent-evals-builtin://;s/"//')
    [[ -n "$_tag" ]] && IMAGE_TAG="$_tag"
fi
IMAGES=(
    "opendataworks-dataagent-evals-builtin:${IMAGE_TAG}"
    "opendataworks-dataagent-evals-deepeval:${IMAGE_TAG}"
    "opendataworks-dataagent-evals-builtin:latest"
    "opendataworks-dataagent-evals-deepeval:latest"
)
for image in "${IMAGES[@]}"; do
    localhost_image="localhost/$image"
    if $CONTAINER_CMD images --format "{{.Repository}}:{{.Tag}}" | grep -q "^$localhost_image$"; then
        echo "  🔄 修复镜像标签: $localhost_image -> $image"
        $CONTAINER_CMD tag "$localhost_image" "$image"
        $CONTAINER_CMD rmi "$localhost_image"
    fi
done

echo ""
echo "📋 已加载评测镜像："
$CONTAINER_CMD images | grep -E "opendataworks-dataagent-evals" || true
echo ""
echo "📝 下一步：按 deploy/README.md 第 3 节运行 builtin / DeepEval 评测（用 --dataset 指定私有评测集）"
echo ""
