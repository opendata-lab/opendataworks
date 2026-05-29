from __future__ import annotations

import difflib
import hashlib
import io
import json
import logging
import os
import re
import shutil
import stat
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any

import anyio

from config import get_settings, update_settings
from core.claude_cli import resolve_claude_cli_path
from core.provider_runtime import (
    build_provider_env,
    normalize_provider_id as normalize_runtime_provider_id,
    safe_base_url_for_log,
)
from core.skill_admin_store import get_skill_admin_store
from core.skill_discovery import (
    resolve_agent_project_cwd,
    resolve_skill_discovery_root_dir,
    resolve_skills_root_dir,
)

logger = logging.getLogger(__name__)

SUPPORTED_PROVIDERS = ("anthropic", "openrouter", "anyrouter", "anthropic_compatible")
SUPPORTED_PROVIDER_SET = set(SUPPORTED_PROVIDERS)
MANAGED_FILE_SUFFIXES = {".json", ".md", ".markdown", ".py"}
DEFAULT_PROVIDER_ID = "openrouter"
MODEL_DETECTION_TIMEOUT_SECONDS = 30
LEGACY_SQL_SKILL_FOLDER = "dataagent-nl2sql"
DEFAULT_PRIMARY_SKILL_FOLDER = "opendataworks-business-knowledge"
PLATFORM_TOOLS_SKILL_FOLDER = "opendataworks-platform-tools"
DEFAULT_SKILLS_OUTPUT_DIR = f"../.claude/skills/{DEFAULT_PRIMARY_SKILL_FOLDER}"
BUILTIN_SKILL_FOLDERS = {
    DEFAULT_PRIMARY_SKILL_FOLDER,
    PLATFORM_TOOLS_SKILL_FOLDER,
}
DEFAULT_ENABLED_BUILTIN_SKILL_FOLDERS = (
    DEFAULT_PRIMARY_SKILL_FOLDER,
    PLATFORM_TOOLS_SKILL_FOLDER,
)
SKILL_FOLDER_RE = re.compile(r"^[A-Za-z0-9._-]+$")

PROVIDER_DEFINITIONS: dict[str, dict[str, Any]] = {
    "anthropic": {
        "display_name": "Anthropic",
        "provider_group": "官方模型",
        "default_base_url": "https://api.anthropic.com",
        "default_model": "claude-sonnet-4-20250514",
        "supported_models": [
            "claude-opus-4-6",
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-20250219",
        ],
    },
    "openrouter": {
        "display_name": "OpenRouter",
        "provider_group": "聚合路由",
        "default_base_url": "https://openrouter.ai/api",
        "default_model": "anthropic/claude-sonnet-4.5",
        "supported_models": [
            "anthropic/claude-sonnet-4.5",
            "anthropic/claude-sonnet-4.6",
            "anthropic/claude-opus-4.1",
        ],
    },
    "anyrouter": {
        "display_name": "AnyRouter",
        "provider_group": "聚合路由",
        "default_base_url": "https://a-ocnfniawgw.cn-shanghai.fcapp.run",
        "default_model": "claude-opus-4-6",
        "supported_models": [
            "claude-opus-4-6",
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-20250219",
        ],
    },
    "anthropic_compatible": {
        "display_name": "Anthropic Compatible",
        "provider_group": "自定义接入",
        "default_base_url": "",
        "default_model": "",
        "supported_models": [],
    },
}

RUNTIME_SETTING_KEYS = {
    "provider_id",
    "model",
    "anthropic_api_key",
    "anthropic_auth_token",
    "anthropic_base_url",
    "mysql_host",
    "mysql_port",
    "mysql_user",
    "mysql_password",
    "mysql_database",
    "doris_host",
    "doris_port",
    "doris_user",
    "doris_password",
    "doris_database",
    "skills_output_dir",
    "session_mysql_database",
}


def _backend_root() -> Path:
    return Path(__file__).resolve().parent.parent


def current_settings_payload() -> dict[str, Any]:
    runtime = _runtime_settings_payload()
    store = get_skill_admin_store()
    try:
        store.init_schema()
        db_payload = store.load_settings_record() or {}
    except Exception as exc:
        logger.warning("Failed to load admin settings from store: %s", exc)
        db_payload = {}
    return _merge_settings_payload(runtime, db_payload)


def _runtime_settings_payload() -> dict[str, Any]:
    cfg = get_settings()
    return {
        "provider_id": cfg.llm_provider,
        "model": cfg.claude_model,
        "anthropic_api_key": cfg.anthropic_api_key,
        "anthropic_auth_token": cfg.anthropic_auth_token,
        "anthropic_base_url": cfg.anthropic_base_url,
        "mysql_host": cfg.mysql_host,
        "mysql_port": cfg.mysql_port,
        "mysql_user": cfg.mysql_user,
        "mysql_password": cfg.mysql_password,
        "mysql_database": cfg.mysql_database,
        "doris_host": cfg.doris_host,
        "doris_port": cfg.doris_port,
        "doris_user": cfg.doris_user,
        "doris_password": cfg.doris_password,
        "doris_database": cfg.doris_database,
        "skills_output_dir": cfg.skills_output_dir,
        "session_mysql_database": cfg.session_mysql_database,
        # Widget allowlist is managed exclusively from the settings page and
        # persisted in da_agent_settings; there is no env-var source.
        "widget_allowed_sites": [],
    }


def _now_iso() -> str:
    return datetime.now().isoformat(timespec="seconds")


def _normalize_provider_id(provider_id: str | None, *, allow_empty: bool = False) -> str:
    value = str(provider_id or "").strip().lower()
    if value in SUPPORTED_PROVIDER_SET:
        return value
    return "" if allow_empty else DEFAULT_PROVIDER_ID


def _string_list(values: Any) -> list[str]:
    if not isinstance(values, (list, tuple, set)):
        return []
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        item = str(value or "").strip()
        if not item or item in seen:
            continue
        seen.add(item)
        result.append(item)
    return result


def _normalize_widget_allowed_sites(raw: Any) -> list[dict[str, Any]]:
    items = raw
    if isinstance(raw, str):
        try:
            items = json.loads(raw or "[]")
        except Exception:
            return []
    if not isinstance(items, list):
        return []
    normalized: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in items:
        if not isinstance(item, dict):
            continue
        website_id = str(item.get("website_id") or "").strip()
        if not website_id or website_id in seen:
            continue
        seen.add(website_id)
        origins = item.get("allowed_origins")
        origin_list = _string_list(list(origins) if isinstance(origins, (list, tuple, set)) else [])
        normalized.append({
            "website_id": website_id[:128],
            "allowed_origins": origin_list,
            "project_name": str(item.get("project_name") or "").strip()[:128],
            "project_color": str(item.get("project_color") or "").strip()[:32],
        })
    return normalized


def _normalize_skill_runtime(raw: Any, *, fallback_folder: str = "") -> dict[str, dict[str, bool]]:
    normalized: dict[str, dict[str, bool]] = {}
    if isinstance(raw, dict):
        for folder, entry in raw.items():
            folder_name = str(folder or "").strip()
            if not folder_name:
                continue
            enabled = bool(entry.get("enabled")) if isinstance(entry, dict) else bool(entry)
            normalized[folder_name] = {"enabled": enabled}
    legacy_enabled = bool((normalized.get(LEGACY_SQL_SKILL_FOLDER) or {}).get("enabled"))
    if LEGACY_SQL_SKILL_FOLDER in normalized:
        normalized.pop(LEGACY_SQL_SKILL_FOLDER, None)

    if not normalized and fallback_folder and fallback_folder != LEGACY_SQL_SKILL_FOLDER:
        normalized[fallback_folder] = {"enabled": True}
    if not raw or fallback_folder == LEGACY_SQL_SKILL_FOLDER or legacy_enabled:
        for folder in DEFAULT_ENABLED_BUILTIN_SKILL_FOLDERS:
            normalized.setdefault(folder, {"enabled": True})
    return normalized


def _enabled_skill_folders(skill_runtime: dict[str, dict[str, bool]]) -> list[str]:
    return sorted(
        folder
        for folder, entry in (skill_runtime or {}).items()
        if folder and bool((entry or {}).get("enabled"))
    )


def _folder_from_skills_output_dir(raw: str | None) -> str:
    value = str(raw or "").replace("\\", "/").strip().rstrip("/")
    if not value:
        return ""
    return value.rsplit("/", 1)[-1]


def _normalize_model_detections(raw: Any) -> dict[str, dict[str, str]]:
    if not isinstance(raw, dict):
        return {}
    normalized: dict[str, dict[str, str]] = {}
    for model, item in raw.items():
        model_name = str(model or "").strip()
        if not model_name or not isinstance(item, dict):
            continue
        status = str(item.get("status") or "unverified").strip()
        if status not in {"verified", "failed", "unverified"}:
            status = "unverified"
        normalized[model_name] = {
            "status": status,
            "message": str(item.get("message") or "").strip(),
            "checked_at": str(item.get("checked_at") or "").strip(),
        }
    return normalized


def _provider_definition(provider_id: str) -> dict[str, Any]:
    return dict(PROVIDER_DEFINITIONS.get(provider_id) or PROVIDER_DEFINITIONS[DEFAULT_PROVIDER_ID])


def _default_provider_settings(provider_id: str) -> dict[str, Any]:
    definition = _provider_definition(provider_id)
    return {
        "provider_id": provider_id,
        "provider_enabled": False,
        "api_key": "",
        "auth_token": "",
        "base_url": str(definition.get("default_base_url") or ""),
        "supports_partial_messages": provider_id != "anthropic_compatible",
        "enabled_models": [],
        "custom_models": [],
        "model_detections": {},
        "validation_status": "unverified",
        "validation_message": "供应商未启用",
        "validated_at": "",
    }


def _coerce_provider_settings(raw: Any) -> dict[str, dict[str, Any]]:
    if isinstance(raw, list):
        items = {}
        for entry in raw:
            if not isinstance(entry, dict):
                continue
            provider_id = _normalize_provider_id(entry.get("provider_id"), allow_empty=True)
            if not provider_id:
                continue
            items[provider_id] = dict(entry)
        return items
    if isinstance(raw, dict):
        items = {}
        for provider_id, entry in raw.items():
            normalized_id = _normalize_provider_id(provider_id, allow_empty=True)
            if not normalized_id:
                continue
            if isinstance(entry, dict):
                items[normalized_id] = dict(entry)
        return items
    return {}


def _legacy_provider_settings(payload: dict[str, Any] | None) -> dict[str, dict[str, Any]]:
    data = dict(payload or {})
    provider_id = _normalize_provider_id(data.get("provider_id"), allow_empty=True)
    model = str(data.get("model") or "").strip()
    api_key = str(data.get("anthropic_api_key") or "").strip()
    auth_token = str(data.get("anthropic_auth_token") or "").strip()
    base_url = str(data.get("anthropic_base_url") or "").strip()

    legacy = {pid: _default_provider_settings(pid) for pid in SUPPORTED_PROVIDERS}
    if provider_id:
        target = legacy[provider_id]
        if api_key:
            target["api_key"] = api_key
        if auth_token:
            target["auth_token"] = auth_token
        if base_url:
            target["base_url"] = base_url
        if model:
            target["enabled_models"] = [model]
    return legacy


def _enabled_provider_ids(provider_settings: dict[str, dict[str, Any]]) -> list[str]:
    enabled: list[str] = []
    for provider_id in SUPPORTED_PROVIDERS:
        entry = provider_settings.get(provider_id)
        if entry and bool(entry.get("enabled")):
            enabled.append(provider_id)
    return enabled


def _normalize_provider_entry(provider_id: str, payload: dict[str, Any], previous: dict[str, Any] | None = None) -> dict[str, Any]:
    definition = _provider_definition(provider_id)
    base = _default_provider_settings(provider_id)
    if previous:
        base.update(dict(previous))
    base.update(dict(payload or {}))

    provider_enabled_raw = None
    for source in (payload, previous):
        if not isinstance(source, dict):
            continue
        if "provider_enabled" in source:
            provider_enabled_raw = source.get("provider_enabled")
            break
        if "enabled" in source:
            provider_enabled_raw = source.get("enabled")
            break
    if provider_enabled_raw is None:
        provider_enabled_raw = base.get("enabled", False)
    provider_enabled = bool(provider_enabled_raw)
    requested_enabled_models = _string_list(base.get("enabled_models") or base.get("models"))
    custom_models = _string_list(base.get("custom_models"))
    model_detections = _normalize_model_detections(base.get("model_detections"))
    supported_models = _string_list(
        list(definition.get("supported_models") or [])
        + custom_models
        + requested_enabled_models
        + list(model_detections.keys())
    )
    enabled_models = requested_enabled_models
    base_url = str(base.get("base_url") or definition.get("default_base_url") or "").strip()
    api_key = str(base.get("api_key") or "").strip()
    auth_token = str(base.get("auth_token") or "").strip()
    supports_partial_messages = bool(base.get("supports_partial_messages", provider_id != "anthropic_compatible"))

    status, message = _compute_provider_validation(
        provider_id,
        provider_enabled=provider_enabled,
        api_key=api_key,
        auth_token=auth_token,
        base_url=base_url,
        enabled_models=enabled_models,
    )

    validated_at = str(base.get("validated_at") or "").strip()
    if status == "verified":
        validated_at = validated_at or _now_iso()
    else:
        validated_at = ""

    return {
        "provider_id": provider_id,
        "provider_enabled": provider_enabled,
        "api_key": api_key,
        "auth_token": auth_token,
        "base_url": base_url,
        "supports_partial_messages": supports_partial_messages,
        "enabled_models": enabled_models,
        "custom_models": custom_models,
        "supported_models": supported_models,
        "model_detections": model_detections,
        "validation_status": status,
        "validation_message": message,
        "validated_at": validated_at,
        "enabled": provider_enabled and bool(enabled_models) and status == "verified",
    }


def _compute_provider_validation(
    provider_id: str,
    *,
    provider_enabled: bool,
    api_key: str,
    auth_token: str,
    base_url: str,
    enabled_models: list[str],
) -> tuple[str, str]:
    if not provider_enabled:
        return ("unverified", "供应商未启用")
    token_ready = bool(api_key) if provider_id == "anthropic" else bool(auth_token or api_key)
    if provider_id == "anthropic_compatible" and not str(base_url or "").strip():
        return ("unverified", "请填写兼容网关地址")
    if not token_ready:
        if provider_id == "anthropic":
            return ("unverified", "请填写 API Key")
        return ("unverified", "请填写 Token")
    if not enabled_models:
        return ("unverified", "请启用至少一个模型")
    return ("verified", "模型服务已可用")


def _merge_provider_settings(
    current: dict[str, dict[str, Any]] | None,
    patch: dict[str, dict[str, Any]] | None,
    *,
    legacy_payload: dict[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    merged: dict[str, dict[str, Any]] = _legacy_provider_settings(legacy_payload)
    for provider_id in SUPPORTED_PROVIDERS:
        if current and provider_id in current:
            merged[provider_id] = _normalize_provider_entry(provider_id, current[provider_id], merged.get(provider_id))

    for provider_id, entry in (patch or {}).items():
        current_entry = dict(merged.get(provider_id) or _default_provider_settings(provider_id))
        update = dict(entry or {})

        if "provider_enabled" in update:
            current_entry["provider_enabled"] = bool(update.get("provider_enabled"))
        elif "enabled" in update:
            current_entry["provider_enabled"] = bool(update.get("enabled"))
        if "api_key" in update:
            api_key = str(update.get("api_key") or "").strip()
            if api_key:
                current_entry["api_key"] = api_key
        if "auth_token" in update:
            auth_token = str(update.get("auth_token") or "").strip()
            if auth_token:
                current_entry["auth_token"] = auth_token
        if "base_url" in update:
            current_entry["base_url"] = str(update.get("base_url") or "").strip()
        if "supports_partial_messages" in update:
            current_entry["supports_partial_messages"] = bool(update.get("supports_partial_messages"))
        if "enabled_models" in update:
            current_entry["enabled_models"] = _string_list(update.get("enabled_models"))
        if "custom_models" in update:
            current_entry["custom_models"] = _string_list(update.get("custom_models"))
        if "model_detections" in update:
            current_entry["model_detections"] = _normalize_model_detections(update.get("model_detections"))
        if update.get("enabled") is False:
            current_entry["enabled_models"] = []

        merged[provider_id] = _normalize_provider_entry(provider_id, current_entry, merged.get(provider_id))

    return {
        provider_id: _normalize_provider_entry(provider_id, merged.get(provider_id) or {}, None)
        for provider_id in SUPPORTED_PROVIDERS
    }


def _merge_settings_payload(current: dict[str, Any] | None, patch: dict[str, Any] | None) -> dict[str, Any]:
    base = dict(current or {})
    update = dict(patch or {})

    for key, value in update.items():
        if key in {"provider_settings", "providers", "skill_runtime"} or value is None:
            continue
        if key in {"anthropic_api_key", "anthropic_auth_token", "mysql_password", "doris_password"} and not str(value or "").strip():
            continue
        base[key] = value

    current_provider_settings = _coerce_provider_settings(base.get("provider_settings"))
    patch_provider_settings = _coerce_provider_settings(update.get("provider_settings") or update.get("providers"))
    provider_settings = _merge_provider_settings(
        current_provider_settings,
        patch_provider_settings,
        legacy_payload=base | update,
    )
    configured_skills_output_dir = str(base.get("skills_output_dir") or "").strip()
    fallback_skill_folder = _folder_from_skills_output_dir(configured_skills_output_dir)
    normalized_skills_output_dir = configured_skills_output_dir or DEFAULT_SKILLS_OUTPUT_DIR
    if fallback_skill_folder == LEGACY_SQL_SKILL_FOLDER:
        normalized_skills_output_dir = DEFAULT_SKILLS_OUTPUT_DIR
    skill_runtime = _normalize_skill_runtime(
        update.get("skill_runtime") if "skill_runtime" in update else base.get("skill_runtime"),
        fallback_folder=fallback_skill_folder,
    )
    widget_allowed_sites = _normalize_widget_allowed_sites(base.get("widget_allowed_sites"))

    provider_id = _normalize_provider_id(base.get("provider_id"), allow_empty=True)
    if not provider_id:
        enabled_provider_ids = _enabled_provider_ids(provider_settings)
        provider_id = enabled_provider_ids[0] if enabled_provider_ids else ""

    provider_profile = provider_settings.get(provider_id) if provider_id else None
    preferred_model = str(base.get("model") or "").strip() if provider_profile else ""
    if provider_profile and preferred_model and preferred_model not in provider_profile["supported_models"]:
        provider_profile["custom_models"] = _string_list(provider_profile["custom_models"] + [preferred_model])
        provider_settings[provider_id] = _normalize_provider_entry(provider_id, provider_profile)
        provider_profile = provider_settings[provider_id]

    enabled_models = list(provider_profile.get("enabled_models") or []) if provider_profile else []
    model = preferred_model or (enabled_models[0] if enabled_models else "")
    if provider_profile and model and model not in provider_profile["supported_models"]:
        model = ""
    if not model and enabled_models:
        model = enabled_models[0]

    runtime_provider = provider_settings.get(provider_id) if provider_id else {}
    flattened = {
        "provider_id": provider_id,
        "model": model,
        "anthropic_api_key": str(runtime_provider.get("api_key") or base.get("anthropic_api_key") or ""),
        "anthropic_auth_token": str(runtime_provider.get("auth_token") or base.get("anthropic_auth_token") or ""),
        "anthropic_base_url": str(runtime_provider.get("base_url") or base.get("anthropic_base_url") or ""),
        "mysql_host": str(base.get("mysql_host") or ""),
        "mysql_port": int(base.get("mysql_port") or 3306),
        "mysql_user": str(base.get("mysql_user") or ""),
        "mysql_password": str(base.get("mysql_password") or ""),
        "mysql_database": str(base.get("mysql_database") or ""),
        "doris_host": str(base.get("doris_host") or ""),
        "doris_port": int(base.get("doris_port") or 9030),
        "doris_user": str(base.get("doris_user") or ""),
        "doris_password": str(base.get("doris_password") or ""),
        "doris_database": str(base.get("doris_database") or ""),
        "skills_output_dir": normalized_skills_output_dir,
        "session_mysql_database": str(base.get("session_mysql_database") or ""),
        "provider_settings": provider_settings,
        "skill_runtime": skill_runtime,
        "widget_allowed_sites": widget_allowed_sites,
    }
    flattened["validated_provider_id"] = provider_id if runtime_provider.get("enabled") else ""
    flattened["validated_model"] = model if model in runtime_provider.get("enabled_models", []) and runtime_provider.get("enabled") else ""
    flattened["provider_validation_status"] = str(runtime_provider.get("validation_status") or "unverified")
    flattened["provider_validation_message"] = str(runtime_provider.get("validation_message") or "")
    flattened["provider_validated_at"] = str(runtime_provider.get("validated_at") or "")
    return flattened


def runtime_patch_from_payload(payload: dict[str, Any]) -> dict[str, Any]:
    patch: dict[str, Any] = {}
    if "provider_id" in payload:
        patch["llm_provider"] = payload.get("provider_id")
    if "model" in payload:
        patch["claude_model"] = payload.get("model")
    passthrough = {
        "anthropic_api_key",
        "anthropic_auth_token",
        "anthropic_base_url",
        "mysql_host",
        "mysql_port",
        "mysql_user",
        "mysql_password",
        "mysql_database",
        "doris_host",
        "doris_port",
        "doris_user",
        "doris_password",
        "doris_database",
        "skills_output_dir",
        "session_mysql_database",
    }
    for key in passthrough:
        if key in payload:
            patch[key] = payload.get(key)
    return patch


def validate_settings_payload(payload: dict[str, Any]):
    provider_id = str(payload.get("provider_id") or "").strip().lower()
    if provider_id and provider_id not in SUPPORTED_PROVIDER_SET:
        raise ValueError("provider_id must be one of anthropic/openrouter/anyrouter/anthropic_compatible")

    raw_skills_dir = str(payload.get("skills_output_dir") or "").replace("\\", "/")
    if raw_skills_dir and "/.claude/skills/" not in raw_skills_dir and not raw_skills_dir.startswith(".claude/skills/"):
        raise ValueError("skills_output_dir must be under .claude/skills")


def _short_error(exc: Exception) -> str:
    text = str(exc or "").strip()
    return text[:500] if text else type(exc).__name__


def _model_detection_result(status: str, message: str, *, provider_id: str, model: str) -> dict[str, str]:
    return {
        "provider_id": provider_id,
        "model": model,
        "status": status,
        "message": message,
        "checked_at": _now_iso(),
    }


def _detection_preflight(
    provider_id: str,
    *,
    api_key: str,
    auth_token: str,
    base_url: str,
    model: str,
) -> str:
    if not model:
        return "请选择模型"
    if provider_id == "anthropic_compatible" and not str(base_url or "").strip():
        return "Base URL 缺失"
    token_ready = bool(api_key) if provider_id == "anthropic" else bool(auth_token or api_key)
    if not token_ready:
        return "API 密钥缺失" if provider_id == "anthropic" else "Token 缺失"
    return ""


async def _run_model_detection(
    *,
    provider_id: str,
    model: str,
    api_key: str,
    auth_token: str,
    base_url: str,
    supports_partial_messages: bool,
) -> tuple[str, str]:
    try:
        from claude_agent_sdk import ClaudeAgentOptions, query as claude_query
    except ImportError as exc:
        return "failed", f"claude-agent-sdk 未安装: {_short_error(exc)}"

    normalized_provider = normalize_runtime_provider_id(provider_id, base_url)
    provider_env = build_provider_env(
        normalized_provider,
        api_key=api_key,
        auth_token=auth_token,
        base_url=base_url,
    )
    runtime_env = dict(os.environ)
    runtime_env.update(provider_env)
    cli_path = resolve_claude_cli_path(get_settings())
    logger.info(
        "model_detection.start provider=%s model=%s base_url=%s supports_partial_messages=%s auth_token_set=%s api_key_set=%s cli_path_set=%s",
        provider_id,
        model,
        safe_base_url_for_log(base_url),
        supports_partial_messages,
        bool(str(auth_token or "").strip()),
        bool(str(api_key or "").strip()),
        bool(cli_path),
    )
    options_kwargs = dict(
        system_prompt="你是模型服务连通性检测程序。只需用最短文本回答检测请求。",
        model=model,
        cwd=str(resolve_agent_project_cwd()),
        setting_sources=["project"],
        max_turns=1,
        allowed_tools=[],
        mcp_servers={},
        include_partial_messages=supports_partial_messages,
        env=runtime_env,
        stderr=lambda line: logger.error(
            "model_detection.stderr provider=%s model=%s %s",
            provider_id,
            model,
            str(line or "").rstrip(),
        ),
    )
    if cli_path:
        options_kwargs["cli_path"] = cli_path
    options = ClaudeAgentOptions(**options_kwargs)

    try:
        with anyio.fail_after(MODEL_DETECTION_TIMEOUT_SECONDS):
            async for msg in claude_query(prompt="请直接回复 model-service-ok。", options=options):
                if type(msg).__name__ == "ResultMessage":
                    subtype = str(getattr(msg, "subtype", "") or "")
                    if subtype.startswith("error"):
                        return "failed", "模型服务返回异常"
        return "verified", "模型检测通过"
    except TimeoutError:
        return "failed", f"模型检测超时（{MODEL_DETECTION_TIMEOUT_SECONDS}s）"
    except Exception as exc:
        return "failed", f"模型检测失败: {_short_error(exc)}"


async def detect_model_availability(payload: dict[str, Any]) -> dict[str, str]:
    provider_id = _normalize_provider_id(payload.get("provider_id"), allow_empty=True)
    if not provider_id:
        raise ValueError("provider_id must be one of anthropic/openrouter/anyrouter/anthropic_compatible")

    model = str(payload.get("model") or "").strip()
    if not model:
        raise ValueError("model is required")

    current = current_settings_payload()
    provider_settings = _coerce_provider_settings(current.get("provider_settings"))
    current_entry = _normalize_provider_entry(provider_id, provider_settings.get(provider_id) or {})

    api_key = str(payload.get("api_key") or current_entry.get("api_key") or "").strip()
    auth_token = str(payload.get("auth_token") or current_entry.get("auth_token") or "").strip()
    base_url = str(payload.get("base_url") or current_entry.get("base_url") or "").strip()
    supports_partial_messages = (
        bool(payload.get("supports_partial_messages"))
        if payload.get("supports_partial_messages") is not None
        else bool(current_entry.get("supports_partial_messages", provider_id != "anthropic_compatible"))
    )

    preflight_message = _detection_preflight(
        provider_id,
        api_key=api_key,
        auth_token=auth_token,
        base_url=base_url,
        model=model,
    )
    if preflight_message:
        result = _model_detection_result("failed", preflight_message, provider_id=provider_id, model=model)
    else:
        status, message = await _run_model_detection(
            provider_id=provider_id,
            model=model,
            api_key=api_key,
            auth_token=auth_token,
            base_url=base_url,
            supports_partial_messages=supports_partial_messages,
        )
        result = _model_detection_result(status, message, provider_id=provider_id, model=model)
    return result


def bootstrap_admin_settings() -> dict[str, Any]:
    store = get_skill_admin_store()
    store.init_schema()

    runtime = _runtime_settings_payload()
    db_payload = store.load_settings_record() or {}
    merged = _merge_settings_payload(runtime, db_payload)
    validate_settings_payload(merged)
    update_settings(runtime_patch_from_payload(merged))

    if not db_payload:
        store.save_settings_record(merged)
        persisted = merged
    else:
        persisted = store.load_settings_record() or merged
    return _merge_settings_payload(runtime, persisted)


def persist_admin_settings(payload: dict[str, Any]) -> dict[str, Any]:
    current = current_settings_payload()
    merged = _merge_settings_payload(current, payload)
    validate_settings_payload(merged)

    update_settings(runtime_patch_from_payload(merged))
    store = get_skill_admin_store()
    saved = store.save_settings_record(merged)
    resolved = _merge_settings_payload(_runtime_settings_payload(), saved)
    return resolved | {"updated_at": saved.get("updated_at", "")}


def list_provider_configs(*, payload: dict[str, Any] | None = None, enabled_only: bool = False) -> list[dict[str, Any]]:
    resolved = payload or current_settings_payload()
    provider_settings = _coerce_provider_settings(resolved.get("provider_settings"))
    configs: list[dict[str, Any]] = []

    for provider_id in SUPPORTED_PROVIDERS:
        definition = _provider_definition(provider_id)
        item = _normalize_provider_entry(provider_id, provider_settings.get(provider_id) or {})
        if enabled_only and not item.get("enabled"):
            continue
        configs.append(
            {
                "provider_id": provider_id,
                "display_name": str(definition.get("display_name") or provider_id),
                "provider_group": str(definition.get("provider_group") or ""),
                "base_url": str(item.get("base_url") or ""),
                "api_key_set": bool(item.get("api_key")),
                "auth_token_set": bool(item.get("auth_token")),
                "models": list(item.get("enabled_models") or []),
                "supported_models": list(item.get("supported_models") or []),
                "custom_models": list(item.get("custom_models") or []),
                "model_detections": dict(item.get("model_detections") or {}),
                "default_model": (
                    (item.get("enabled_models") or [None])[0]
                    or str(definition.get("default_model") or "")
                ),
                "enabled": bool(item.get("enabled")),
                "provider_enabled": bool(item.get("provider_enabled")),
                "supports_partial_messages": bool(item.get("supports_partial_messages", provider_id != "anthropic_compatible")),
                "validation_status": str(item.get("validation_status") or "unverified"),
                "validation_message": str(item.get("validation_message") or ""),
            }
        )

    configs.sort(key=lambda item: (item["provider_group"], item["display_name"]))
    return configs


def resolved_chat_settings_payload() -> dict[str, Any]:
    resolved = current_settings_payload()
    providers = list_provider_configs(payload=resolved, enabled_only=True)
    default_provider_id = _normalize_provider_id(resolved.get("provider_id"), allow_empty=True)
    if not any(item["provider_id"] == default_provider_id for item in providers):
        default_provider_id = providers[0]["provider_id"] if providers else ""

    default_model = ""
    for provider in providers:
        if provider["provider_id"] == default_provider_id:
            models = list(provider.get("models") or [])
            preferred = str(resolved.get("model") or "").strip()
            default_model = preferred if preferred in models else (models[0] if models else "")
            break

    return {
        "default_provider_id": default_provider_id,
        "default_model": default_model,
        "providers": providers,
        "skills_output_dir": str(resolved.get("skills_output_dir") or ""),
        "mysql_host": str(resolved.get("mysql_host") or ""),
        "mysql_port": int(resolved.get("mysql_port") or 3306),
        "mysql_database": str(resolved.get("mysql_database") or ""),
        "doris_host": str(resolved.get("doris_host") or ""),
        "doris_port": int(resolved.get("doris_port") or 9030),
        "doris_database": str(resolved.get("doris_database") or ""),
    }


def resolve_runtime_provider_selection(provider_id: str | None, model: str | None) -> dict[str, Any]:
    resolved = current_settings_payload()
    provider_settings = _coerce_provider_settings(resolved.get("provider_settings"))
    normalized_provider_id = _normalize_provider_id(provider_id or resolved.get("provider_id"), allow_empty=True)
    if not normalized_provider_id:
        enabled_provider_ids = _enabled_provider_ids(provider_settings)
        normalized_provider_id = enabled_provider_ids[0] if enabled_provider_ids else ""
    if not normalized_provider_id:
        raise ValueError("尚未配置可用大模型供应商")
    provider = _normalize_provider_entry(normalized_provider_id, provider_settings.get(normalized_provider_id) or {})

    if not provider.get("enabled"):
        raise ValueError("所选供应商未通过校验，或尚未开启任何模型")

    enabled_models = list(provider.get("enabled_models") or [])
    selected_model = str(model or "").strip()
    if not selected_model:
        selected_model = enabled_models[0] if enabled_models else ""
    if selected_model not in enabled_models:
        raise ValueError("所选模型未加入已启用模型")

    return {
        "provider_id": normalized_provider_id,
        "model": selected_model,
        "api_key": str(provider.get("api_key") or ""),
        "auth_token": str(provider.get("auth_token") or ""),
        "base_url": str(provider.get("base_url") or ""),
        "supports_partial_messages": bool(
            provider.get("supports_partial_messages", normalized_provider_id != "anthropic_compatible")
        ),
    }


def managed_skill_files() -> list[str]:
    root = resolve_skill_discovery_root_dir()
    files: list[str] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() not in MANAGED_FILE_SUFFIXES:
            continue
        files.append(path.relative_to(root).as_posix())
    files.sort()
    return files


def _skill_folder_name(relative_path: str) -> str:
    normalized = str(relative_path or "").replace("\\", "/").strip("/")
    if not normalized:
        return ""
    return normalized.split("/", 1)[0]


def _relative_path_within_skill(relative_path: str) -> str:
    normalized = str(relative_path or "").replace("\\", "/").strip("/")
    if not normalized:
        return ""
    parts = normalized.split("/", 1)
    if len(parts) == 1:
        return parts[0]
    return parts[1]


def _validate_skill_folder_name(folder: str) -> str:
    value = str(folder or "").strip()
    if not value or value in {".", ".."} or "/" in value or "\\" in value or not SKILL_FOLDER_RE.match(value):
        raise ValueError("skill folder must match A-Za-z0-9._-")
    return value


def _is_builtin_skill_folder(folder: str) -> bool:
    return str(folder or "").strip() in BUILTIN_SKILL_FOLDERS


def _skill_source(folder: str) -> str:
    return "bundled" if _is_builtin_skill_folder(folder) else "managed"


def _current_skill_folder() -> str:
    root = resolve_skills_root_dir()
    return root.name if root else ""


def _skill_runtime_from_current_settings() -> dict[str, dict[str, bool]]:
    payload = current_settings_payload()
    fallback_folder = _folder_from_skills_output_dir(str(payload.get("skills_output_dir") or "")) or _current_skill_folder()
    return _normalize_skill_runtime(payload.get("skill_runtime"), fallback_folder=fallback_folder)


def _is_skill_enabled(folder: str, skill_runtime: dict[str, dict[str, bool]] | None = None) -> bool:
    folder_name = str(folder or "").strip()
    if not folder_name:
        return False
    runtime = skill_runtime if skill_runtime is not None else _skill_runtime_from_current_settings()
    return bool((runtime.get(folder_name) or {}).get("enabled"))


def _document_api_payload(document: dict[str, Any]) -> dict[str, Any]:
    payload = dict(document or {})
    full_relative_path = str(payload.get("relative_path") or "").replace("\\", "/").strip("/")
    folder = _skill_folder_name(full_relative_path)
    payload["folder"] = folder
    payload["relative_path"] = _relative_path_within_skill(full_relative_path)
    payload["source"] = _skill_source(folder)
    payload["editable"] = True
    payload["enabled"] = _is_skill_enabled(folder)
    return payload


def _settings_path_for_skill_folder(folder: str) -> str:
    discovery_root = resolve_skill_discovery_root_dir()
    target = (discovery_root / folder).resolve()
    return os.path.relpath(target, _backend_root()).replace("\\", "/")


def _discovered_skill_folders() -> set[str]:
    root = resolve_skill_discovery_root_dir()
    folders: set[str] = set()
    for entry in root.iterdir():
        if not entry.is_dir():
            continue
        if (entry / "SKILL.md").exists():
            folders.add(entry.name)
    return folders


def _migrate_document_paths_to_discovery_root(store) -> None:
    skill_folders = _discovered_skill_folders()
    if not skill_folders:
        return
    current_folder = _current_skill_folder()
    if not current_folder:
        return
    for document in store.list_documents():
        relative_path = str(document.get("relative_path") or "").replace("\\", "/").strip("/")
        if not relative_path:
            continue
        if _skill_folder_name(relative_path) in skill_folders:
            continue
        next_path = f"{current_folder}/{relative_path}"
        if store.get_document_by_path(next_path):
            continue
        store.rename_document_path(relative_path, next_path)


def reindex_documents_from_disk(*, change_source: str = "import", change_summary: str = "发现磁盘文件") -> list[dict[str, Any]]:
    store = get_skill_admin_store()
    root = resolve_skill_discovery_root_dir()
    _migrate_document_paths_to_discovery_root(store)
    managed_paths = managed_skill_files()
    managed_path_set = set(managed_paths)
    for document in store.list_documents():
        relative_path = str(document.get("relative_path") or "")
        if relative_path and relative_path not in managed_path_set:
            store.delete_document_by_path(relative_path)

    changed: list[dict[str, Any]] = []
    for relative_path in managed_paths:
        file_path = root / relative_path
        content = file_path.read_text(encoding="utf-8")
        existing = store.get_document_by_path(relative_path)
        current_hash = existing.get("current_hash") if existing else None
        next_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
        if existing and current_hash == next_hash:
            continue
        saved = store.save_document(
            relative_path=relative_path,
            content=content,
            change_source=change_source,
            change_summary=change_summary,
            actor="system",
        )
        changed.append(_document_api_payload(saved))
    return changed


def list_documents() -> list[dict[str, Any]]:
    reindex_documents_from_disk()
    documents = [_document_api_payload(item) for item in get_skill_admin_store().list_documents()]
    documents.sort(key=lambda item: (str(item.get("folder") or ""), str(item.get("category") or ""), str(item.get("relative_path") or "")))
    return documents


def get_document_detail(document_id: int) -> dict[str, Any] | None:
    reindex_documents_from_disk()
    store = get_skill_admin_store()
    document = store.get_document(document_id)
    if not document:
        return None
    document["versions"] = store.list_versions(document_id)
    return _document_api_payload(document)


def validate_document_content(relative_path: str, content: str):
    suffix = Path(relative_path).suffix.lower()
    if suffix == ".json":
        try:
            payload = json.loads(content)
        except Exception as exc:
            raise ValueError(f"JSON 文件格式错误: {exc}") from exc
        if not isinstance(payload, dict):
            raise ValueError("JSON 文件根节点必须是对象")


def write_skill_file(relative_path: str, content: str):
    root = resolve_skill_discovery_root_dir()
    path = (root / relative_path).resolve()
    if root not in path.parents and path != root:
        raise ValueError("invalid skill file path")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def save_document_content(document_id: int, content: str, change_summary: str | None = None) -> dict[str, Any]:
    store = get_skill_admin_store()
    document = store.get_document(document_id)
    if not document:
        raise ValueError("document not found")
    validate_document_content(document["relative_path"], content)
    write_skill_file(document["relative_path"], content)
    saved = store.save_document(
        relative_path=document["relative_path"],
        content=content,
        change_source="edit",
        change_summary=change_summary or "前端保存",
        actor="ui",
    )
    return get_document_detail(int(saved["id"])) or {}


def rollback_document(document_id: int, version_id: int) -> dict[str, Any]:
    store = get_skill_admin_store()
    document = store.get_document(document_id)
    if not document:
        raise ValueError("document not found")
    version = store.get_version(document_id, version_id)
    if not version:
        raise ValueError("version not found")
    write_skill_file(document["relative_path"], version["content"])
    saved = store.save_document(
        relative_path=document["relative_path"],
        content=version["content"],
        change_source="rollback",
        change_summary=f"回滚到 V{version['version_no']}",
        actor="ui",
        parent_version_id=version_id,
    )
    return get_document_detail(int(saved["id"])) or {}


def _normalize_zip_member_path(raw_name: str) -> str:
    raw = str(raw_name or "").replace("\\", "/")
    if not raw:
        return ""
    if raw.startswith("/") or raw.startswith("\\") or (len(raw) >= 2 and raw[1] == ":"):
        raise ValueError("ZIP contains unsafe absolute path")
    parts: list[str] = []
    for part in raw.split("/"):
        if part in {"", "."}:
            continue
        if part == "..":
            raise ValueError("ZIP contains unsafe parent path")
        parts.append(part)
    return "/".join(parts)


def _is_ignored_zip_member(relative_path: str) -> bool:
    parts = str(relative_path or "").split("/")
    return bool(parts and (parts[0] == "__MACOSX" or parts[-1] == ".DS_Store"))


def _zip_member_is_symlink(info: zipfile.ZipInfo) -> bool:
    mode = info.external_attr >> 16
    return stat.S_IFMT(mode) == stat.S_IFLNK


def _safe_extract_skill_zip(content: bytes, extract_root: Path):
    if not content:
        raise ValueError("ZIP 文件不能为空")
    buffer = io.BytesIO(content)
    if not zipfile.is_zipfile(buffer):
        raise ValueError("仅支持 ZIP 格式的 Skill 包")

    buffer.seek(0)
    extract_root_resolved = extract_root.resolve()
    extracted_files = 0
    with zipfile.ZipFile(buffer) as archive:
        if not archive.infolist():
            raise ValueError("ZIP 包为空")
        for info in archive.infolist():
            relative_path = _normalize_zip_member_path(info.filename)
            if not relative_path or _is_ignored_zip_member(relative_path):
                continue
            if _zip_member_is_symlink(info):
                raise ValueError("ZIP 包不允许包含符号链接")

            target = (extract_root_resolved / relative_path).resolve()
            if extract_root_resolved not in target.parents and target != extract_root_resolved:
                raise ValueError("ZIP contains unsafe path")
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue

            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, target.open("wb") as destination:
                shutil.copyfileobj(source, destination)
            extracted_files += 1

    if not extracted_files:
        raise ValueError("ZIP 包未包含可导入文件")


def _skill_name_from_front_matter(skill_md: Path) -> str:
    try:
        lines = skill_md.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError as exc:
        raise ValueError("SKILL.md must be UTF-8 encoded") from exc
    if not lines or lines[0].strip() != "---":
        raise ValueError("根目录 SKILL.md 必须包含 front matter name")
    for line in lines[1:]:
        if line.strip() == "---":
            break
        key, separator, value = line.partition(":")
        if separator and key.strip() == "name":
            return value.strip().strip("'\"")
    raise ValueError("根目录 SKILL.md 必须包含 front matter name")


def _resolve_imported_skill_root(extract_root: Path) -> tuple[Path, str]:
    root_skill_md = extract_root / "SKILL.md"
    if root_skill_md.is_file():
        return extract_root, _validate_skill_folder_name(_skill_name_from_front_matter(root_skill_md))

    candidates = [
        entry
        for entry in extract_root.iterdir()
        if entry.is_dir() and entry.name != "__MACOSX" and (entry / "SKILL.md").is_file()
    ]
    if len(candidates) == 1:
        candidate = candidates[0]
        return candidate, _validate_skill_folder_name(candidate.name)
    if len(candidates) > 1:
        raise ValueError("ZIP 包只能包含一个 Skill")
    raise ValueError("ZIP 包缺少 SKILL.md")


def _resolve_skill_target_dir(folder: str) -> Path:
    target_folder = _validate_skill_folder_name(folder)
    discovery_root = resolve_skill_discovery_root_dir().resolve()
    target = (discovery_root / target_folder).resolve()
    if discovery_root not in target.parents:
        raise ValueError("invalid skill folder path")
    return target


def _raw_documents_for_skill(folder: str) -> list[dict[str, Any]]:
    target_folder = str(folder or "").strip()
    return [
        document
        for document in get_skill_admin_store().list_documents()
        if _skill_folder_name(str(document.get("relative_path") or "")) == target_folder
    ]


def import_skill_from_zip(file_name: str, content: bytes) -> dict[str, Any]:
    with tempfile.TemporaryDirectory(prefix="odw-skill-import-") as tmp_dir:
        extract_root = Path(tmp_dir) / "extracted"
        extract_root.mkdir(parents=True, exist_ok=True)
        _safe_extract_skill_zip(content, extract_root)
        skill_root, folder = _resolve_imported_skill_root(extract_root)

        target = _resolve_skill_target_dir(folder)
        if target.exists() or target.is_symlink():
            raise ValueError("skill folder already exists")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(skill_root), str(target))

    current = current_settings_payload()
    primary_folder = _folder_from_skills_output_dir(str(current.get("skills_output_dir") or "")) or _current_skill_folder()
    skill_runtime = _normalize_skill_runtime(current.get("skill_runtime"), fallback_folder=primary_folder)
    skill_runtime[folder] = {"enabled": False}
    persist_admin_settings({"skill_runtime": skill_runtime})

    imported = reindex_documents_from_disk(change_source="upload", change_summary=f"导入 Skill {folder}")
    imported_documents = [item for item in imported if item.get("folder") == folder]
    if not imported_documents:
        imported_documents = [_document_api_payload(item) for item in _raw_documents_for_skill(folder)]

    return {
        "skill_id": folder,
        "source": _skill_source(folder),
        "enabled": False,
        "imported_documents": imported_documents,
        "document_count": len(get_skill_admin_store().list_documents()),
    }


def uninstall_skill(folder: str) -> dict[str, Any]:
    target_folder = _validate_skill_folder_name(folder)
    if _is_builtin_skill_folder(target_folder):
        raise ValueError("内置 Skill 不支持卸载")

    reindex_documents_from_disk()
    available_folders = _discovered_skill_folders()
    if target_folder not in available_folders:
        raise ValueError("skill folder not found")

    target = _resolve_skill_target_dir(target_folder)
    if not target.is_dir() or target.is_symlink():
        raise ValueError("invalid skill folder path")

    current = current_settings_payload()
    primary_folder = _folder_from_skills_output_dir(str(current.get("skills_output_dir") or "")) or _current_skill_folder()
    skill_runtime = _normalize_skill_runtime(current.get("skill_runtime"), fallback_folder=primary_folder)
    was_enabled = bool((skill_runtime.get(target_folder) or {}).get("enabled"))
    enabled_folders = [
        item
        for item in _enabled_skill_folders(skill_runtime)
        if item in available_folders and item != target_folder
    ]
    if was_enabled and not enabled_folders:
        raise ValueError("当前运行时至少需要保留一个启用 Skill")

    raw_removed_documents = _raw_documents_for_skill(target_folder)
    removed_documents = [_document_api_payload(item) for item in raw_removed_documents]
    shutil.rmtree(target)

    store = get_skill_admin_store()
    for document in raw_removed_documents:
        store.delete_document_by_path(str(document.get("relative_path") or ""))

    skill_runtime.pop(target_folder, None)
    payload: dict[str, Any] = {"skill_runtime": skill_runtime}
    if primary_folder == target_folder and enabled_folders:
        payload["skills_output_dir"] = _settings_path_for_skill_folder(enabled_folders[0])
    persist_admin_settings(payload)
    return {
        "skill_id": target_folder,
        "removed_documents": removed_documents,
        "was_enabled": was_enabled,
        "document_count": len(store.list_documents()),
    }


def update_skill_runtime(folder: str, enabled: bool) -> dict[str, Any]:
    target_folder = _validate_skill_folder_name(folder)
    available_folders = _discovered_skill_folders()
    if target_folder not in available_folders:
        raise ValueError("skill folder not found")

    current = current_settings_payload()
    primary_folder = _folder_from_skills_output_dir(str(current.get("skills_output_dir") or "")) or _current_skill_folder()
    skill_runtime = _normalize_skill_runtime(current.get("skill_runtime"), fallback_folder=primary_folder)
    skill_runtime[target_folder] = {"enabled": bool(enabled)}
    enabled_folders = [folder for folder in _enabled_skill_folders(skill_runtime) if folder in available_folders]
    if not enabled_folders:
        raise ValueError("当前运行时至少需要保留一个启用 Skill")

    next_primary_folder = primary_folder if primary_folder in enabled_folders else enabled_folders[0]
    payload: dict[str, Any] = {"skill_runtime": skill_runtime}
    if next_primary_folder != primary_folder:
        payload["skills_output_dir"] = _settings_path_for_skill_folder(next_primary_folder)

    persist_admin_settings(payload)
    return {
        "skill_id": target_folder,
        "enabled": bool(skill_runtime.get(target_folder, {}).get("enabled")),
    }


def resolve_enabled_skill_runtime() -> dict[str, Any]:
    current = current_settings_payload()
    available_folders = _discovered_skill_folders()
    primary_folder = _folder_from_skills_output_dir(str(current.get("skills_output_dir") or "")) or _current_skill_folder()
    skill_runtime = _normalize_skill_runtime(current.get("skill_runtime"), fallback_folder=primary_folder)
    enabled_folders = [folder for folder in _enabled_skill_folders(skill_runtime) if folder in available_folders]
    if not enabled_folders and primary_folder in available_folders:
        enabled_folders = [primary_folder]
    if primary_folder not in enabled_folders and enabled_folders:
        primary_folder = enabled_folders[0]

    discovery_root = resolve_skill_discovery_root_dir()
    roots = {
        folder: str((discovery_root / folder).resolve())
        for folder in enabled_folders
    }
    return {
        "primary_folder": primary_folder,
        "primary_root": roots.get(primary_folder, str(resolve_skills_root_dir())),
        "enabled_folders": enabled_folders,
        "enabled_roots": roots,
    }


def _resolve_compare_side(document: dict[str, Any], *, version_id: int | None, side: str) -> tuple[str, str]:
    store = get_skill_admin_store()
    if version_id is None:
        return ("当前版本", document["current_content"])
    version = store.get_version(int(document["id"]), version_id)
    if not version:
        raise ValueError(f"{side} version not found")
    return (f"V{version['version_no']}", version["content"])


def compare_document_versions(
    document_id: int,
    *,
    left_version_id: int | None = None,
    right_version_id: int | None = None,
) -> dict[str, Any]:
    store = get_skill_admin_store()
    document = store.get_document(document_id)
    if not document:
        raise ValueError("document not found")
    left_label, left_content = _resolve_compare_side(document, version_id=left_version_id, side="left")
    right_label, right_content = _resolve_compare_side(document, version_id=right_version_id, side="right")

    diff_lines = list(
        difflib.unified_diff(
            left_content.splitlines(),
            right_content.splitlines(),
            fromfile=left_label,
            tofile=right_label,
            lineterm="",
        )
    )
    added_lines = sum(1 for line in diff_lines if line.startswith("+") and not line.startswith("+++"))
    removed_lines = sum(1 for line in diff_lines if line.startswith("-") and not line.startswith("---"))

    return {
        "document_id": document_id,
        "left_label": left_label,
        "right_label": right_label,
        "left_content": left_content,
        "right_content": right_content,
        "diff_text": "\n".join(diff_lines),
        "added_lines": added_lines,
        "removed_lines": removed_lines,
        "changed_lines": added_lines + removed_lines,
    }
