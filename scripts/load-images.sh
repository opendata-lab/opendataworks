#!/bin/bash

# OpenDataWorks 镜像加载脚本（内网部署使用）
# 功能：从 tar 包加载所有 Docker 镜像
# 支持 Docker 和 Podman

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "========================================="
echo "  OpenDataWorks 镜像加载脚本"
echo "========================================="
echo ""

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

# 镜像文件目录
IMAGE_DIR="$REPO_ROOT/deploy/docker-images"

# 检查镜像目录是否存在
if [ ! -d "$IMAGE_DIR" ]; then
    echo "❌ 错误: 镜像目录 $IMAGE_DIR 不存在"
    echo "请确保已将镜像文件放在 deploy/docker-images/ 下"
    exit 1
fi

# 检查必需的镜像文件
REQUIRED_IMAGES=(
    "opendataworks-frontend.tar"
    "opendataworks-backend.tar"
    "opendataworks-dataagent-backend.tar"
    "opendataworks-dataagent-evals-builtin.tar"
    "opendataworks-dataagent-evals-deepeval.tar"
    "opendataworks-portal-mcp.tar"
    "mysql-8.0.tar"
    "redis-7.2-alpine.tar"
)

echo "🔍 检查镜像文件..."
for image_file in "${REQUIRED_IMAGES[@]}"; do
    if [ ! -f "$IMAGE_DIR/$image_file" ]; then
        echo "❌ 缺少镜像文件: $image_file"
        exit 1
    fi
    echo "  ✓ 找到 $image_file"
done
echo ""

echo "📦 开始加载镜像..."
echo ""

# 加载前端镜像
echo "📦 [1/8] 加载前端镜像..."
$CONTAINER_CMD load -i "$IMAGE_DIR/opendataworks-frontend.tar"
echo "✅ 前端镜像加载完成"
echo ""

# 加载后端镜像
echo "📦 [2/8] 加载后端镜像..."
$CONTAINER_CMD load -i "$IMAGE_DIR/opendataworks-backend.tar"
echo "✅ 后端镜像加载完成"
echo ""

# 加载 DataAgent 后端镜像
echo "📦 [3/8] 加载 DataAgent 后端镜像..."
$CONTAINER_CMD load -i "$IMAGE_DIR/opendataworks-dataagent-backend.tar"
echo "✅ DataAgent 后端镜像加载完成"
echo ""

# 加载 builtin 评测镜像
echo "📦 [4/8] 加载 builtin 评测镜像..."
$CONTAINER_CMD load -i "$IMAGE_DIR/opendataworks-dataagent-evals-builtin.tar"
echo "✅ builtin 评测镜像加载完成"
echo ""

# 加载 DeepEval 评测镜像
echo "📦 [5/8] 加载 DeepEval 评测镜像..."
$CONTAINER_CMD load -i "$IMAGE_DIR/opendataworks-dataagent-evals-deepeval.tar"
echo "✅ DeepEval 评测镜像加载完成"
echo ""

# 加载 Portal MCP 镜像
echo "📦 [6/8] 加载 Portal MCP 镜像..."
$CONTAINER_CMD load -i "$IMAGE_DIR/opendataworks-portal-mcp.tar"
echo "✅ Portal MCP 镜像加载完成"
echo ""

# 加载 MySQL 镜像
echo "📦 [7/8] 加载 MySQL 镜像..."
$CONTAINER_CMD load -i "$IMAGE_DIR/mysql-8.0.tar"
echo "✅ MySQL 镜像加载完成"
echo ""

# 加载 Redis 镜像
echo "📦 [8/8] 加载 Redis 镜像..."
$CONTAINER_CMD load -i "$IMAGE_DIR/redis-7.2-alpine.tar"
echo "✅ Redis 镜像加载完成"
echo ""

echo "========================================="
echo "  所有镜像加载完成！"
echo "========================================="
echo ""

# 修复 localhost 前缀问题
echo "🔧 修复镜像 localhost 前缀问题..."
echo ""

# 定义需要修复的镜像（支持 latest 与版本号如 0.2.0）
# 若 manifest.json 存在则从中读取 opendataworks 镜像的 tag
IMAGE_TAG="latest"
if [[ -f "$IMAGE_DIR/manifest.json" ]]; then
    _tag=$(grep -o '"target": *"opendataworks-frontend:[^"]*"' "$IMAGE_DIR/manifest.json" 2>/dev/null | sed 's/.*opendataworks-frontend://;s/"//')
    [[ -n "$_tag" ]] && IMAGE_TAG="$_tag"
fi
IMAGES=(
    "opendataworks-frontend:${IMAGE_TAG}"
    "opendataworks-backend:${IMAGE_TAG}"
    "opendataworks-dataagent-backend:${IMAGE_TAG}"
    "opendataworks-dataagent-evals-builtin:${IMAGE_TAG}"
    "opendataworks-dataagent-evals-deepeval:${IMAGE_TAG}"
    "opendataworks-portal-mcp:${IMAGE_TAG}"
    "opendataworks-frontend:latest"
    "opendataworks-backend:latest"
    "opendataworks-dataagent-backend:latest"
    "opendataworks-dataagent-evals-builtin:latest"
    "opendataworks-dataagent-evals-deepeval:latest"
    "opendataworks-portal-mcp:latest"
    "mysql:8.0"
    "redis:7.2-alpine"
)

# 修复每个镜像
for image in "${IMAGES[@]}"; do
    localhost_image="localhost/$image"
    
    # 检查是否存在 localhost 前缀的镜像
    if $CONTAINER_CMD images --format "{{.Repository}}:{{.Tag}}" | grep -q "^$localhost_image$"; then
        echo "  🔄 修复镜像标签: $localhost_image -> $image"
        
        # 重新标记为无前缀版本
        $CONTAINER_CMD tag "$localhost_image" "$image"
        
        # 删除 localhost 前缀的镜像
        $CONTAINER_CMD rmi "$localhost_image"
        
        echo "  ✅ 修复完成: $image"
    fi
done

echo ""
echo "========================================="
echo "  镜像标签修复完成！"
echo "========================================="
echo ""

# 显示已加载的镜像
echo "📋 已加载的镜像列表："
$CONTAINER_CMD images | grep -E "opendataworks|mysql|redis" | grep -E "latest|8.0|7.2-alpine|${IMAGE_TAG}"
echo ""

echo "📝 下一步："
echo "  1. 复制 deploy/.env.example 为 deploy/.env 并根据实际环境配置"
echo "  2. 运行 scripts/start.sh 启动服务"
echo ""
