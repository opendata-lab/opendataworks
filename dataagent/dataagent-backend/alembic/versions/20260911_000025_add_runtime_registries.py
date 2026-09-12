"""add MCP and model provider registries

Revision ID: 20260911_000025
Revises: 20260911_000024
Create Date: 2026-09-11 18:30:00
"""
from __future__ import annotations

import json
from typing import Any

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "20260911_000025"
down_revision = "20260911_000024"
branch_labels = None
depends_on = None

_BUILTIN_PROVIDER_TYPES = {"anthropic", "openrouter", "anyrouter", "anthropic_compatible"}
_BUILTIN_PROVIDER_META = {
    "anthropic": ("Anthropic", "官方模型"),
    "openrouter": ("OpenRouter", "聚合路由"),
    "anyrouter": ("AnyRouter", "聚合路由"),
    "anthropic_compatible": ("Anthropic Compatible", "自定义接入"),
}


def _json_object(raw: Any) -> dict[str, Any]:
    if isinstance(raw, dict):
        return dict(raw)
    try:
        parsed = json.loads(str(raw or "{}"))
    except Exception:
        return {}
    return dict(parsed) if isinstance(parsed, dict) else {}


def _string_list(raw: Any) -> list[str]:
    if not isinstance(raw, list):
        return []
    result: list[str] = []
    seen: set[str] = set()
    for item in raw:
        value = item.get("id") or item.get("model_id") if isinstance(item, dict) else item
        text = str(value or "").strip()
        if text and text not in seen:
            result.append(text)
            seen.add(text)
    return result


def _legacy_provider_rows(settings: dict[str, Any] | None) -> list[dict[str, Any]]:
    if not settings:
        return []
    raw_json = _json_object(settings.get("raw_json"))
    raw_providers = raw_json.get("provider_settings")
    if not isinstance(raw_providers, (dict, list)):
        raw_providers = raw_json.get("providers")

    providers: dict[str, dict[str, Any]] = {}
    if isinstance(raw_providers, dict):
        for raw_id, entry in raw_providers.items():
            provider_id = str(raw_id or "").strip().lower()
            if provider_id and isinstance(entry, dict):
                providers[provider_id] = dict(entry)
    elif isinstance(raw_providers, list):
        for entry in raw_providers:
            if not isinstance(entry, dict):
                continue
            provider_id = str(entry.get("provider_id") or "").strip().lower()
            if provider_id:
                providers[provider_id] = dict(entry)

    current_id = str(settings.get("provider_id") or "").strip().lower()
    current_model = str(settings.get("model_name") or "").strip()
    if current_id and current_id not in providers:
        providers[current_id] = {
            "provider_id": current_id,
            "provider_enabled": bool(current_model),
            "enabled_models": [current_model] if current_model else [],
            "api_key": str(settings.get("anthropic_api_key") or ""),
            "auth_token": str(settings.get("anthropic_auth_token") or ""),
            "base_url": str(settings.get("anthropic_base_url") or ""),
        }

    rows: list[dict[str, Any]] = []
    for provider_id, entry in providers.items():
        provider_type = str(entry.get("provider_type") or "").strip().lower()
        if provider_type not in _BUILTIN_PROVIDER_TYPES:
            provider_type = provider_id if provider_id in _BUILTIN_PROVIDER_TYPES else "anthropic_compatible"
        default_name, default_group = _BUILTIN_PROVIDER_META.get(
            provider_id, (provider_id, "自定义供应商")
        )
        enabled_models = _string_list(entry.get("enabled_models") or entry.get("models") or [])
        provider_enabled = bool(entry.get("provider_enabled", entry.get("enabled", False)))
        rows.append(
            {
                "provider_id": provider_id,
                "provider_type": provider_type,
                "display_name": str(entry.get("name") or entry.get("display_name") or default_name).strip()[:128],
                "provider_group": str(entry.get("provider_group") or entry.get("group") or default_group).strip()[:64],
                "base_url": str(entry.get("base_url") or "").strip(),
                "api_key": str(entry.get("api_key") or "").strip(),
                "auth_token": str(entry.get("auth_token") or "").strip(),
                "provider_enabled": int(provider_enabled),
                "supports_partial_messages": int(bool(entry.get("supports_partial_messages", provider_type != "anthropic_compatible"))),
                "enabled_models_json": json.dumps(enabled_models, ensure_ascii=False),
                "custom_models_json": json.dumps(_string_list(entry.get("custom_models") or []), ensure_ascii=False),
                "models_json": json.dumps(entry.get("models") if isinstance(entry.get("models"), list) else [], ensure_ascii=False),
                "model_detections_json": json.dumps(entry.get("model_detections") if isinstance(entry.get("model_detections"), dict) else {}, ensure_ascii=False),
                "validation_status": str(entry.get("validation_status") or ("verified" if provider_enabled and enabled_models else "unverified"))[:32],
                "validation_message": str(entry.get("validation_message") or "")[:512],
                "validated_at": str(entry.get("validated_at") or "").strip() or None,
            }
        )
    return rows


def _create_tables() -> None:
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS da_model_provider (
            provider_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '供应商稳定标识',
            provider_type VARCHAR(32) NOT NULL COMMENT '运行时供应商适配器',
            display_name VARCHAR(128) NOT NULL COMMENT '显示名称',
            provider_group VARCHAR(64) NOT NULL DEFAULT '' COMMENT '供应商分组',
            base_url VARCHAR(512) NOT NULL DEFAULT '' COMMENT '模型服务地址',
            api_key VARCHAR(512) NOT NULL DEFAULT '' COMMENT 'API Key',
            auth_token VARCHAR(512) NOT NULL DEFAULT '' COMMENT '认证 Token',
            provider_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '供应商开关',
            supports_partial_messages TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否支持增量消息',
            enabled_models_json LONGTEXT NOT NULL COMMENT '已启用模型 JSON',
            custom_models_json LONGTEXT NOT NULL COMMENT '自定义模型 JSON',
            models_json LONGTEXT NOT NULL COMMENT '模型详情 JSON',
            model_detections_json LONGTEXT NOT NULL COMMENT '模型检测结果 JSON',
            validation_status VARCHAR(32) NOT NULL DEFAULT 'unverified' COMMENT '本地校验状态',
            validation_message VARCHAR(512) NOT NULL DEFAULT '' COMMENT '本地校验信息',
            validated_at DATETIME NULL COMMENT '最近校验时间',
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
            KEY idx_da_model_provider_enabled (provider_enabled, updated_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DataAgent模型供应商注册表'
        """
    )
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS da_mcp_server (
            server_id VARCHAR(128) NOT NULL PRIMARY KEY COMMENT 'MCP服务稳定标识',
            name VARCHAR(128) NOT NULL COMMENT 'MCP服务名称',
            source VARCHAR(32) NOT NULL COMMENT 'configured或plugin',
            transport VARCHAR(16) NOT NULL COMMENT 'http、sse或stdio',
            url VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '远程服务地址',
            headers_json LONGTEXT NOT NULL COMMENT '请求头JSON',
            command_name VARCHAR(512) NOT NULL DEFAULT '' COMMENT 'stdio启动命令',
            args_json LONGTEXT NOT NULL COMMENT 'stdio参数JSON',
            env_json LONGTEXT NOT NULL COMMENT 'stdio环境变量JSON',
            enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '启用状态',
            oauth_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否需要OAuth',
            tool_count INT NOT NULL DEFAULT 0 COMMENT '工具数量',
            description VARCHAR(512) NOT NULL DEFAULT '' COMMENT '服务描述',
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
            UNIQUE KEY uk_da_mcp_server_name (name),
            KEY idx_da_mcp_server_source_enabled (source, enabled)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DataAgent MCP服务注册表'
        """
    )


def _migrate_legacy_providers() -> None:
    bind = op.get_bind()
    inspector = inspect(bind)
    if not inspector.has_table("da_agent_settings"):
        return
    settings = bind.execute(
        sa.text(
            """
            SELECT provider_id, model_name, anthropic_api_key, anthropic_auth_token,
                   anthropic_base_url, raw_json
            FROM da_agent_settings
            WHERE settings_key = 'default'
            LIMIT 1
            """
        )
    ).mappings().first()
    insert_sql = sa.text(
        """
        INSERT IGNORE INTO da_model_provider (
            provider_id, provider_type, display_name, provider_group, base_url,
            api_key, auth_token, provider_enabled, supports_partial_messages,
            enabled_models_json, custom_models_json, models_json, model_detections_json,
            validation_status, validation_message, validated_at
        ) VALUES (
            :provider_id, :provider_type, :display_name, :provider_group, :base_url,
            :api_key, :auth_token, :provider_enabled, :supports_partial_messages,
            :enabled_models_json, :custom_models_json, :models_json, :model_detections_json,
            :validation_status, :validation_message, :validated_at
        )
        """
    )
    for row in _legacy_provider_rows(dict(settings) if settings else None):
        bind.execute(insert_sql, row)


def upgrade() -> None:
    _create_tables()
    _migrate_legacy_providers()


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS da_mcp_server")
    op.execute("DROP TABLE IF EXISTS da_model_provider")

