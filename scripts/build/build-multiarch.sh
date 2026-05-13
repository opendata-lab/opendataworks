#!/bin/bash

# OpenDataWorks 多架构镜像构建和推送脚本
# 支持: AMD64 (x86_64) 和 ARM64 (aarch64)
# 推送目标: Docker Hub

set -e

echo "========================================="
echo "  OpenDataWorks 多架构镜像构建脚本"
echo "========================================="
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 默认配置
DEFAULT_REGISTRY="docker.io"
DEFAULT_NAMESPACE="opendataworks"
VERSION="${VERSION:-latest}"
PLATFORMS="linux/amd64,linux/arm64"

# 解析命令行参数
PUSH=false
BUILD_FRONTEND=true
BUILD_BACKEND=true
BUILD_DATAAGENT_BACKEND=true
BUILD_DATAAGENT_EVALS_BUILTIN=true
BUILD_DATAAGENT_EVALS_DEEPEVAL=true
BUILD_PORTAL_MCP=true

usage() {
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  -u, --username USER     Docker Hub 用户名 (必需)"
    echo "  -p, --password PASS     Docker Hub 密码/Token (必需)"
    echo "  -n, --namespace NS      Docker Hub 命名空间 (默认: opendataworks)"
    echo "  -v, --version VER       镜像版本标签 (默认: latest)"
    echo "  --push                  构建后推送到 Docker Hub"
    echo "  --no-frontend           跳过前端镜像构建"
    echo "  --no-backend            跳过后端镜像构建"
    echo "  --no-dataagent-backend  跳过 DataAgent 后端镜像构建"
    echo "  --no-dataagent-evals-builtin  跳过 DataAgent builtin 评测镜像构建"
    echo "  --no-dataagent-evals-deepeval  跳过 DataAgent DeepEval 评测镜像构建"
    echo "  --no-portal-mcp         跳过 Portal MCP 镜像构建"
    echo "  --platform PLATFORMS    目标平台 (默认: linux/amd64,linux/arm64)"
    echo "  -h, --help              显示此帮助信息"
    echo ""
    echo "示例:"
    echo "  $0 -u myuser -p mytoken --push"
    echo "  $0 -u myuser -p mytoken -v v1.2.0 --push"
    echo "  $0 -u myuser -p mytoken --no-dataagent-backend --push"
    echo ""
    exit 1
}

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        -u|--username)
            DOCKER_USERNAME="$2"
            shift 2
            ;;
        -p|--password)
            DOCKER_PASSWORD="$2"
            shift 2
            ;;
        -n|--namespace)
            DEFAULT_NAMESPACE="$2"
            shift 2
            ;;
        -v|--version)
            VERSION="$2"
            shift 2
            ;;
        --push)
            PUSH=true
            shift
            ;;
        --no-frontend)
            BUILD_FRONTEND=false
            shift
            ;;
        --no-backend)
            BUILD_BACKEND=false
            shift
            ;;
        --no-dataagent-backend)
            BUILD_DATAAGENT_BACKEND=false
            shift
            ;;
        --no-dataagent-evals-builtin)
            BUILD_DATAAGENT_EVALS_BUILTIN=false
            shift
            ;;
        --no-dataagent-evals-deepeval)
            BUILD_DATAAGENT_EVALS_DEEPEVAL=false
            shift
            ;;
        --no-portal-mcp)
            BUILD_PORTAL_MCP=false
            shift
            ;;
        --platform)
            PLATFORMS="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo -e "${RED}❌ 未知参数: $1${NC}"
            usage
            ;;
    esac
done

# 检查必需参数
if [ "$PUSH" = true ]; then
    if [ -z "$DOCKER_USERNAME" ] || [ -z "$DOCKER_PASSWORD" ]; then
        echo -e "${RED}❌ 错误: 推送到 Docker Hub 需要提供用户名和密码${NC}"
        echo ""
        usage
    fi
fi

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo -e "${RED}❌ 错误: 未找到 Docker${NC}"
    echo "请先安装 Docker: https://docs.docker.com/get-docker/"
    exit 1
fi

echo -e "${GREEN}✓ 检测到 Docker${NC}"
docker --version
echo ""

# 检查 Docker Buildx
if ! docker buildx version &> /dev/null; then
    echo -e "${RED}❌ 错误: Docker Buildx 未安装或未启用${NC}"
    echo "请参考: https://docs.docker.com/buildx/working-with-buildx/"
    exit 1
fi

echo -e "${GREEN}✓ 检测到 Docker Buildx${NC}"
docker buildx version
echo ""

# 创建或使用 buildx builder
BUILDER_NAME="opendataworks-builder"
if ! docker buildx inspect $BUILDER_NAME &> /dev/null; then
    echo -e "${YELLOW}📦 创建新的 builder: $BUILDER_NAME${NC}"
    docker buildx create --name $BUILDER_NAME --use --bootstrap
else
    echo -e "${GREEN}✓ 使用现有 builder: $BUILDER_NAME${NC}"
    docker buildx use $BUILDER_NAME
fi
echo ""

# 登录 Docker Hub
if [ "$PUSH" = true ]; then
    echo -e "${YELLOW}🔑 登录 Docker Hub...${NC}"
    echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ Docker Hub 登录成功${NC}"
    else
        echo -e "${RED}❌ Docker Hub 登录失败${NC}"
        exit 1
    fi
    echo ""
fi

# 定义镜像名称
FRONTEND_IMAGE="$DEFAULT_NAMESPACE/opendataworks-frontend"
BACKEND_IMAGE="$DEFAULT_NAMESPACE/opendataworks-backend"
DATAAGENT_BACKEND_IMAGE="$DEFAULT_NAMESPACE/opendataworks-dataagent-backend"
DATAAGENT_EVALS_BUILTIN_IMAGE="$DEFAULT_NAMESPACE/opendataworks-dataagent-evals-builtin"
DATAAGENT_EVALS_DEEPEVAL_IMAGE="$DEFAULT_NAMESPACE/opendataworks-dataagent-evals-deepeval"
PORTAL_MCP_IMAGE="$DEFAULT_NAMESPACE/opendataworks-portal-mcp"

# 构建参数
BUILD_ARGS="--platform=$PLATFORMS"
if [ "$PUSH" = true ]; then
    BUILD_ARGS="$BUILD_ARGS --push"
else
    BUILD_ARGS="$BUILD_ARGS --load"
    # 注意: --load 只支持单一平台，如果是多平台需要使用 --push
    if [[ "$PLATFORMS" == *","* ]]; then
        echo -e "${YELLOW}⚠️  警告: 多平台构建必须使用 --push 选项${NC}"
        echo -e "${YELLOW}⚠️  将改为仅本地构建模式 (不推送)${NC}"
        BUILD_ARGS="--platform=$PLATFORMS"
    fi
fi

echo "========================================="
echo "  构建配置"
echo "========================================="
echo "版本标签:   $VERSION"
echo "目标平台:   $PLATFORMS"
echo "命名空间:   $DEFAULT_NAMESPACE"
echo "推送镜像:   $PUSH"
echo "构建前端:   $BUILD_FRONTEND"
echo "构建后端:   $BUILD_BACKEND"
echo "构建 DataAgent 后端: $BUILD_DATAAGENT_BACKEND"
echo "构建 DataAgent builtin 评测: $BUILD_DATAAGENT_EVALS_BUILTIN"
echo "构建 DataAgent DeepEval 评测: $BUILD_DATAAGENT_EVALS_DEEPEVAL"
echo "构建 Portal MCP: $BUILD_PORTAL_MCP"
echo "========================================="
echo ""

# 构建计数
TOTAL_BUILDS=0
SUCCESSFUL_BUILDS=0

# 构建前端镜像
if [ "$BUILD_FRONTEND" = true ]; then
    echo -e "${YELLOW}📦 构建前端镜像...${NC}"
    echo "镜像: $FRONTEND_IMAGE:$VERSION"
    echo "平台: $PLATFORMS"

    cd "$REPO_ROOT/frontend"
    if docker buildx build $BUILD_ARGS \
        -t $FRONTEND_IMAGE:$VERSION \
        -t $FRONTEND_IMAGE:latest \
        --file Dockerfile \
        . ; then
        echo -e "${GREEN}✅ 前端镜像构建成功${NC}"
        ((SUCCESSFUL_BUILDS++))
    else
        echo -e "${RED}❌ 前端镜像构建失败${NC}"
    fi
    cd "$REPO_ROOT"
    ((TOTAL_BUILDS++))
    echo ""
fi

# 构建后端镜像
if [ "$BUILD_BACKEND" = true ]; then
    echo -e "${YELLOW}📦 构建后端镜像...${NC}"
    echo "镜像: $BACKEND_IMAGE:$VERSION"
    echo "平台: $PLATFORMS"

    if docker buildx build $BUILD_ARGS \
        -t $BACKEND_IMAGE:$VERSION \
        -t $BACKEND_IMAGE:latest \
        --file backend/Dockerfile \
        . ; then
        echo -e "${GREEN}✅ 后端镜像构建成功${NC}"
        ((SUCCESSFUL_BUILDS++))
    else
        echo -e "${RED}❌ 后端镜像构建失败${NC}"
    fi
    ((TOTAL_BUILDS++))
    echo ""
fi

# 构建 DataAgent 后端镜像
if [ "$BUILD_DATAAGENT_BACKEND" = true ]; then
    echo -e "${YELLOW}📦 构建 DataAgent 后端镜像...${NC}"
    echo "镜像: $DATAAGENT_BACKEND_IMAGE:$VERSION"
    echo "平台: $PLATFORMS"

    cd "$REPO_ROOT"
    if docker buildx build $BUILD_ARGS \
        -t $DATAAGENT_BACKEND_IMAGE:$VERSION \
        -t $DATAAGENT_BACKEND_IMAGE:latest \
        --file dataagent/dataagent-backend/Dockerfile \
        . ; then
        echo -e "${GREEN}✅ DataAgent 后端镜像构建成功${NC}"
        ((SUCCESSFUL_BUILDS++))
    else
        echo -e "${RED}❌ DataAgent 后端镜像构建失败${NC}"
    fi
    ((TOTAL_BUILDS++))
    echo ""
fi

if [ "$BUILD_DATAAGENT_EVALS_DEEPEVAL" = true ]; then
    echo -e "${YELLOW}📦 构建 DataAgent DeepEval 评测镜像...${NC}"
    echo "镜像: $DATAAGENT_EVALS_DEEPEVAL_IMAGE:$VERSION"
    echo "平台: $PLATFORMS"

    cd "$REPO_ROOT"
    if docker buildx build $BUILD_ARGS \
        -t $DATAAGENT_EVALS_DEEPEVAL_IMAGE:$VERSION \
        -t $DATAAGENT_EVALS_DEEPEVAL_IMAGE:latest \
        --file evals/dataagent-arch-governance-deepeval/Dockerfile \
        . ; then
        echo -e "${GREEN}✅ DataAgent DeepEval 评测镜像构建成功${NC}"
        ((SUCCESSFUL_BUILDS++))
    else
        echo -e "${RED}❌ DataAgent DeepEval 评测镜像构建失败${NC}"
    fi
    ((TOTAL_BUILDS++))
    echo ""
fi

if [ "$BUILD_DATAAGENT_EVALS_BUILTIN" = true ]; then
    echo -e "${YELLOW}📦 构建 DataAgent builtin 评测镜像...${NC}"
    echo "镜像: $DATAAGENT_EVALS_BUILTIN_IMAGE:$VERSION"
    echo "平台: $PLATFORMS"

    cd "$REPO_ROOT"
    if docker buildx build $BUILD_ARGS \
        -t $DATAAGENT_EVALS_BUILTIN_IMAGE:$VERSION \
        -t $DATAAGENT_EVALS_BUILTIN_IMAGE:latest \
        --file evals/dataagent-arch-governance-builtin/Dockerfile \
        . ; then
        echo -e "${GREEN}✅ DataAgent builtin 评测镜像构建成功${NC}"
        ((SUCCESSFUL_BUILDS++))
    else
        echo -e "${RED}❌ DataAgent builtin 评测镜像构建失败${NC}"
    fi
    ((TOTAL_BUILDS++))
    echo ""
fi

if [ "$BUILD_PORTAL_MCP" = true ]; then
    echo -e "${YELLOW}📦 构建 Portal MCP 镜像...${NC}"
    echo "镜像: $PORTAL_MCP_IMAGE:$VERSION"
    echo "平台: $PLATFORMS"

    cd "$REPO_ROOT"
    if docker buildx build $BUILD_ARGS \
        -t $PORTAL_MCP_IMAGE:$VERSION \
        -t $PORTAL_MCP_IMAGE:latest \
        --file dataagent/portal-mcp/Dockerfile \
        . ; then
        echo -e "${GREEN}✅ Portal MCP 镜像构建成功${NC}"
        ((SUCCESSFUL_BUILDS++))
    else
        echo -e "${RED}❌ Portal MCP 镜像构建失败${NC}"
    fi
    ((TOTAL_BUILDS++))
    echo ""
fi

# 总结
echo "========================================="
echo "  构建完成"
echo "========================================="
echo -e "成功: ${GREEN}$SUCCESSFUL_BUILDS${NC}/$TOTAL_BUILDS"
echo ""

if [ $SUCCESSFUL_BUILDS -eq $TOTAL_BUILDS ]; then
    echo -e "${GREEN}🎉 所有镜像构建成功！${NC}"
    echo ""

    if [ "$PUSH" = true ]; then
        echo "✅ 镜像已推送到 Docker Hub:"
        [ "$BUILD_FRONTEND" = true ] && echo "  - $FRONTEND_IMAGE:$VERSION"
        [ "$BUILD_BACKEND" = true ] && echo "  - $BACKEND_IMAGE:$VERSION"
        [ "$BUILD_DATAAGENT_BACKEND" = true ] && echo "  - $DATAAGENT_BACKEND_IMAGE:$VERSION"
        [ "$BUILD_DATAAGENT_EVALS_BUILTIN" = true ] && echo "  - $DATAAGENT_EVALS_BUILTIN_IMAGE:$VERSION"
        [ "$BUILD_DATAAGENT_EVALS_DEEPEVAL" = true ] && echo "  - $DATAAGENT_EVALS_DEEPEVAL_IMAGE:$VERSION"
        [ "$BUILD_PORTAL_MCP" = true ] && echo "  - $PORTAL_MCP_IMAGE:$VERSION"
        echo ""
        echo "📝 拉取镜像命令:"
        [ "$BUILD_FRONTEND" = true ] && echo "  docker pull $FRONTEND_IMAGE:$VERSION"
        [ "$BUILD_BACKEND" = true ] && echo "  docker pull $BACKEND_IMAGE:$VERSION"
        [ "$BUILD_DATAAGENT_BACKEND" = true ] && echo "  docker pull $DATAAGENT_BACKEND_IMAGE:$VERSION"
        [ "$BUILD_DATAAGENT_EVALS_BUILTIN" = true ] && echo "  docker pull $DATAAGENT_EVALS_BUILTIN_IMAGE:$VERSION"
        [ "$BUILD_DATAAGENT_EVALS_DEEPEVAL" = true ] && echo "  docker pull $DATAAGENT_EVALS_DEEPEVAL_IMAGE:$VERSION"
        [ "$BUILD_PORTAL_MCP" = true ] && echo "  docker pull $PORTAL_MCP_IMAGE:$VERSION"
    else
        echo "ℹ️  镜像已构建到本地 Docker 镜像仓库"
        echo ""
        echo "查看本地镜像:"
        echo "  docker images | grep opendataworks"
    fi
    echo ""
    echo "📝 下一步:"
    if [ "$PUSH" = false ]; then
        echo "  1. 运行 '$0 --push' 推送镜像到 Docker Hub"
        echo "  2. 或使用 docker-compose.prod.yml 启动服务"
    else
        echo "  1. 在目标服务器上拉取镜像"
        echo "  2. 使用 docker-compose 启动服务"
    fi
    echo ""

    exit 0
else
    echo -e "${RED}❌ 部分镜像构建失败${NC}"
    echo "请检查错误日志并重试"
    exit 1
fi
