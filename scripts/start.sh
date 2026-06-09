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

read_env_value() {
    local key="$1"
    local env_file="$2"

    if [ ! -f "$env_file" ]; then
        return 0
    fi

    local line
    line="$(grep -E "^${key}=" "$env_file" | tail -n 1 || true)"
    printf '%s' "${line#*=}"
}

set_env_value() {
    local key="$1"
    local value="$2"
    local env_file="$3"
    local tmp_file="${env_file}.tmp.$$"

    if grep -q -E "^${key}=" "$env_file" 2>/dev/null; then
        awk -v target_key="$key" -v target_value="$value" '
            BEGIN { written = 0 }
            $0 ~ "^" target_key "=" {
                print target_key "=" target_value
                written = 1
                next
            }
            { print }
            END {
                if (!written) {
                    print target_key "=" target_value
                }
            }
        ' "$env_file" > "$tmp_file"
    else
        cp "$env_file" "$tmp_file"
        {
            echo ""
            echo "${key}=${value}"
        } >> "$tmp_file"
    fi

    mv "$tmp_file" "$env_file"
}

normalize_path() {
    local path="$1"
    local is_absolute=false
    local -a parts=()
    local -a normalized=()
    local part
    local joined=""
    local i

    if [[ "$path" = /* ]]; then
        is_absolute=true
        path="${path#/}"
    fi

    IFS='/' read -r -a parts <<< "$path"
    for part in "${parts[@]}"; do
        case "$part" in
            ""|".")
                continue
                ;;
            "..")
                if [ "${#normalized[@]}" -gt 0 ]; then
                    unset 'normalized[${#normalized[@]}-1]'
                fi
                ;;
            *)
                normalized+=("$part")
                ;;
        esac
    done

    if [ "${#normalized[@]}" -gt 0 ]; then
        joined="${normalized[0]}"
        for ((i = 1; i < ${#normalized[@]}; i++)); do
            joined+="/${normalized[i]}"
        done
    fi

    if [ "$is_absolute" = true ]; then
        if [ -n "$joined" ]; then
            printf '/%s\n' "$joined"
        else
            printf '/\n'
        fi
        return 0
    fi

    if [ -n "$joined" ]; then
        printf '%s\n' "$joined"
    else
        printf '.\n'
    fi
}

resolve_dataagent_skills_dir() {
    local configured
    configured="$(read_env_value "DATAAGENT_SKILLS_DIR" "$ENV_FILE")"
    if [ -z "$configured" ]; then
        configured="../dataagent/.claude/skills"
    fi

    if [[ "$configured" = /* ]]; then
        printf '%s\n' "$configured"
        return 0
    fi

    local absolute_path
    absolute_path="$(normalize_path "$DEPLOY_DIR/$configured")"
    printf '%s\n' "$absolute_path"
}

resolve_dataagent_host_root() {
    # 解析 DataAgent 持久化运行时根目录的宿主机绝对路径。
    #
    # compose 卷挂载会把相对路径按项目目录（deploy/）解析，但 sandbox runner 是用
    # 转发进容器的 DATAAGENT_HOST_ROOT 在自己的容器内（/app）重新解析，再让宿主
    # Docker 守护进程按该路径为每个 task child 绑定挂载 topic 目录。若用户填的是
    # 相对路径，runner 解析出的就是 /app/<rel> 这种容器内路径，child bind 源会错位。
    # 因此这里统一把相对路径按 deploy/ 解析成宿主机绝对路径，并在启动 compose 前
    # export，使卷挂载与 runner 转发的 DATAAGENT_HOST_ROOT 始终指向同一宿主机目录。
    local configured
    configured="$(read_env_value "DATAAGENT_HOST_ROOT" "$ENV_FILE")"
    if [ -z "$configured" ]; then
        configured="/dataagent_runtime"
    fi

    if [[ "$configured" = /* ]]; then
        normalize_path "$configured"
        return 0
    fi

    normalize_path "$DEPLOY_DIR/$configured"
}

ensure_dataagent_cli_executable() {
    local skills_dir
    skills_dir="$(resolve_dataagent_skills_dir)"

    if [ ! -d "$skills_dir" ]; then
        return 0
    fi

    # 统一 skills 目录 owner 为容器运行时用户，避免 bin/ 等子目录残留 root:root
    local runtime_uid
    local runtime_gid
    runtime_uid="$(read_env_value "DATAAGENT_RUNTIME_UID" "$ENV_FILE")"
    runtime_gid="$(read_env_value "DATAAGENT_RUNTIME_GID" "$ENV_FILE")"
    runtime_uid="${runtime_uid:-1000}"
    runtime_gid="${runtime_gid:-1000}"
    chown -R "${runtime_uid}:${runtime_gid}" "$skills_dir" 2>/dev/null || true

    # 确保目录可遍历、文件可读
    chmod -R a+rX "$skills_dir" 2>/dev/null || true

    local cli_path="$skills_dir/dataagent-nl2sql/bin/odw-cli"

    if [ ! -f "$cli_path" ]; then
        return 0
    fi

    if [ -x "$cli_path" ]; then
        return 0
    fi

    if chmod +x "$cli_path" 2>/dev/null; then
        echo "🔧 已修复 DataAgent runtime CLI 执行权限: $cli_path"
    else
        echo "⚠️  警告: 无法自动修复 DataAgent runtime CLI 执行权限: $cli_path"
        echo "   智能问数 metadata 链路会退回 sh 执行，但建议仍在宿主机保留 +x 权限。"
    fi
}

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
    echo "   - SPRING_DATASOURCE_URL / USERNAME / PASSWORD 指向 mysql 服务"
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

ensure_dataagent_cli_executable

# 统一 DataAgent 运行时根目录为宿主机绝对路径，保证 backend 卷挂载与 runner 反查的
# child bind 源指向同一目录，支持用户在 .env 中自定义（含相对于 deploy/ 的相对路径）。
DATAAGENT_HOST_ROOT="$(resolve_dataagent_host_root)"
export DATAAGENT_HOST_ROOT
echo "📁 DataAgent 运行时根目录(宿主机): $DATAAGENT_HOST_ROOT"

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
echo "  DataAgent 前端: http://localhost:8901"
echo "  后端: http://localhost:8080"
echo "  DataAgent 后端: http://localhost:8900"
echo "  Portal MCP: http://localhost:8801/mcp"
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
