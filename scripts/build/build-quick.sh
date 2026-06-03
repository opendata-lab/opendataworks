#!/bin/bash

# 快捷构建脚本 - 从配置文件读取参数

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# 检查配置文件
CONFIG_FILE="${CONFIG_FILE:-$SCRIPT_DIR/docker-build.env}"
EXAMPLE_FILE="$SCRIPT_DIR/docker-build.env.example"
if [ ! -f "$CONFIG_FILE" ]; then
    echo "❌ 配置文件不存在: $CONFIG_FILE"
    echo ""
    echo "请先创建配置文件:"
    echo "  cp $EXAMPLE_FILE $CONFIG_FILE"
    echo "  然后编辑配置文件填写 Docker Hub 凭证"
    echo ""
    exit 1
fi

# 加载配置
source "$CONFIG_FILE"

# 检查必需变量
if [ -z "$DOCKER_USERNAME" ] || [ -z "$DOCKER_PASSWORD" ]; then
    echo "❌ 配置文件中缺少 DOCKER_USERNAME 或 DOCKER_PASSWORD"
    echo "请编辑 $CONFIG_FILE 并填写正确的值"
    exit 1
fi

# 构建命令参数
ARGS="-u $DOCKER_USERNAME -p $DOCKER_PASSWORD"

[ -n "$DOCKER_NAMESPACE" ] && ARGS="$ARGS -n $DOCKER_NAMESPACE"
[ -n "$VERSION" ] && ARGS="$ARGS -v $VERSION"
[ -n "$PLATFORMS" ] && ARGS="$ARGS --platform $PLATFORMS"

[ "$PUSH_TO_REGISTRY" = "true" ] && ARGS="$ARGS --push"
[ "$BUILD_FRONTEND" = "false" ] && ARGS="$ARGS --no-frontend"
[ "$BUILD_BACKEND" = "false" ] && ARGS="$ARGS --no-backend"
[ "${BUILD_DATAAGENT_FRONTEND:-true}" = "false" ] && ARGS="$ARGS --no-dataagent-frontend"
[ "$BUILD_DATAAGENT_BACKEND" = "false" ] && ARGS="$ARGS --no-dataagent-backend"
[ "${BUILD_PORTAL_MCP:-true}" = "false" ] && ARGS="$ARGS --no-portal-mcp"

# 执行构建
echo "执行构建命令:"
echo "$SCRIPT_DIR/build-multiarch.sh $ARGS"
echo ""

"$SCRIPT_DIR/build-multiarch.sh" $ARGS
