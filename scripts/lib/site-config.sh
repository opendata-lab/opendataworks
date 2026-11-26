#!/bin/bash
# 现场配置文件（nginx 转发、OAuth 等）属于目标服务器：仓库和离线包只带 .example，
# 首次启动时复制一份，之后升级不再覆盖。模板变化时由运维对照升级说明手工合并。

SITE_CONFIG_FILES=(
    "docker/nginx/frontend.conf"
    "docker/backend/application.yml"
)

# ensure_site_configs <deploy 目录>：缺少的现场配置从 .example 复制，已有的不动。
ensure_site_configs() {
    local deploy_dir="$1" rel target
    for rel in "${SITE_CONFIG_FILES[@]}"; do
        target="$deploy_dir/$rel"
        if [ -f "$target" ]; then
            continue
        fi
        if [ ! -f "$target.example" ]; then
            echo "❌ 错误: 未找到 $target.example"
            return 1
        fi
        cp "$target.example" "$target"
        echo "✅ 已从模板创建 $target（按现场修改，升级不会覆盖）"
    done
}
