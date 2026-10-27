#!/bin/bash

# OpenDataWorks 启动脚本
# 功能：启动所有服务

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY_DIR="$REPO_ROOT/deploy"
LIB_DIR="$SCRIPT_DIR/lib"
COMPOSE_FILE_NAME="docker-compose.prod.yml"
COMPOSE_FILE="$DEPLOY_DIR/$COMPOSE_FILE_NAME"
ENV_FILE="$DEPLOY_DIR/.env"
ENV_EXAMPLE="$DEPLOY_DIR/.env.example"

# shellcheck source=/dev/null
source "$LIB_DIR/container-runtime.sh"

if [ ! -f "$COMPOSE_FILE" ]; then
    echo "❌ 错误: 未找到 $COMPOSE_FILE"
    exit 1
fi

echo "========================================="
echo "  OpenDataWorks 启动脚本"
echo "========================================="
echo ""

# 检查 .env 文件
if [ ! -f "$ENV_FILE" ]; then
    if [ ! -f "$ENV_EXAMPLE" ]; then
        echo "❌ 错误: 未找到 $ENV_EXAMPLE"
        exit 1
    fi

    echo "⚠️  警告: 未找到部署配置文件 $ENV_FILE"
    echo "正在从模板复制..."
    cp "$ENV_EXAMPLE" "$ENV_FILE"
    echo "✅ 已创建 $ENV_FILE"
    echo ""
    echo "⚠️  请编辑 $ENV_FILE，确认关键配置："
    echo "   - MYSQL_* 数据库凭据及 AUTH_JWT_SECRET / 服务令牌；Spring 连接会自动推导"
    echo "   - 启动后在系统管理界面配置 DolphinScheduler 连接"
    echo ""
    read -p "是否继续启动？(y/n) " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "已取消启动"
        exit 0
    fi
fi

if ! detect_compose_cmd; then
    echo "❌ 错误: 未找到可用的 compose 命令（docker-compose、docker compose、podman compose、podman-compose）"
    exit 1
fi

# 检查容器运行时是否可用
if ! ensure_container_runtime_ready "$COMPOSE_RUNTIME"; then
    if [ "$COMPOSE_RUNTIME" = "docker" ]; then
        echo "❌ 错误: Docker 未运行，请先启动 Docker"
    else
        echo "❌ 错误: Podman 不可用，请先启动 Podman 或检查当前用户权限"
    fi
    exit 1
fi

echo "🚀 启动 OpenDataWorks 服务..."
echo ""


# 预建日志收集目录（log-collector sidecar 把各服务日志实时写到这里）。
# 先建好宿主机目录，避免 Docker 以 root 自动创建后产生属主困惑。
mkdir -p "$DEPLOY_DIR/logs" 2>/dev/null || true

# 启动服务
pushd "$DEPLOY_DIR" >/dev/null
if [ "$COMPOSE_SUPPORTS_ENV_FILE" = true ]; then
    "${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" --env-file "$ENV_FILE" up -d
else
    "${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" up -d
fi

echo ""
echo "⏳ 等待服务启动..."
sleep 5

echo ""
echo "========================================="
echo "  服务状态检查"
echo "========================================="
echo ""

# 显示服务状态
if [ "$COMPOSE_SUPPORTS_ENV_FILE" = true ]; then
    "${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" --env-file "$ENV_FILE" ps
else
    "${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" ps
fi
popd >/dev/null

ENV_FLAG_TEXT=""
if [ "$COMPOSE_SUPPORTS_ENV_FILE" = true ]; then
    ENV_FLAG_TEXT=" --env-file $ENV_FILE"
fi

echo ""
echo "========================================="
echo "  OpenDataWorks 启动完成！"
echo "========================================="
echo ""
echo "📝 服务访问地址："
echo "  前端: http://localhost:8081"
echo "  智能问数: http://localhost:8081/intelligent-query"
echo "  后端: http://localhost:8080"
echo "  ODW MCP Server: http://localhost:8801/mcp"
echo "  MySQL: localhost:3306"
echo ""
echo "📋 常用命令："
echo "  查看日志: ${COMPOSE_CMD[*]} -f $COMPOSE_FILE$ENV_FLAG_TEXT logs -f [service_name]"
echo "  停止服务: scripts/stop.sh"
echo "  重启服务: scripts/restart.sh"
echo "  查看状态: ${COMPOSE_CMD[*]} -f $COMPOSE_FILE$ENV_FLAG_TEXT ps"
echo ""
echo "🔐 默认账号信息："
echo "  MySQL root: root / root123"
echo "  MySQL 应用: opendataworks / opendataworks123"
echo ""
