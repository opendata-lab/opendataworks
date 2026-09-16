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
import threading
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any

from config import get_settings, update_settings
from core.provider_runtime import (
    normalize_api_format,
    request_model_text,
    safe_base_url_for_log,
)
from core.skill_admin_store import get_skill_admin_store
from core.runtime_registry_store import get_runtime_registry_store
from core.skill_discovery import (
    resolve_skill_discovery_root_dir,
    resolve_skills_root_dir,
)

logger = logging.getLogger(__name__)

MANAGED_FILE_SUFFIXES = {".json", ".md", ".markdown", ".py"}
DEFAULT_PROVIDER_ID = "openrouter"
MODEL_DETECTION_TIMEOUT_SECONDS = 30
LEGACY_SQL_SKILL_FOLDER = "dataagent-nl2sql"
DEFAULT_PRIMARY_SKILL_FOLDER = "opendataworks-business-knowledge"
PLATFORM_TOOLS_SKILL_FOLDER = "opendataworks-platform-tools"
ONTOLOGY_MODELING_SKILL_FOLDER = "ontology-modeling-assistant"
DATA_DEV_SKILL_FOLDER = "opendataworks-data-dev"
METHODOLOGY_DAG_SKILL_FOLDER = "opendataworks-methodology-dag"
# 与数据平台无关的通用展示能力，可被任意智能体单独启用，不依赖平台工具
CHART_VISUALIZATION_SKILL_FOLDER = "chart-visualization"
REPORT_GENERATION_SKILL_FOLDER = "report-generation"
DEFAULT_SKILLS_OUTPUT_DIR = f"../.claude/skills/{DEFAULT_PRIMARY_SKILL_FOLDER}"
# 必须覆盖仓库内所有随发布携带的 skill：它决定 source=bundled，而 bundled 是
# 「不可卸载、不可覆盖导入」的唯一依据。漏掉一个，该 skill 就会被判成 managed，
# 管理员能从 UI 卸载它，uninstall_skill() 会 rmtree 掉仓库跟踪的目录。
# 由 test_builtin_skill_folders_cover_every_tracked_skill 锁定，勿手工增删。
BUILTIN_SKILL_FOLDERS = {
    DEFAULT_PRIMARY_SKILL_FOLDER,
    PLATFORM_TOOLS_SKILL_FOLDER,
    ONTOLOGY_MODELING_SKILL_FOLDER,
    DATA_DEV_SKILL_FOLDER,
    METHODOLOGY_DAG_SKILL_FOLDER,
    CHART_VISUALIZATION_SKILL_FOLDER,
    REPORT_GENERATION_SKILL_FOLDER,
}
DEFAULT_ENABLED_BUILTIN_SKILL_FOLDERS = (
    DEFAULT_PRIMARY_SKILL_FOLDER,
    PLATFORM_TOOLS_SKILL_FOLDER,
    CHART_VISUALIZATION_SKILL_FOLDER,
    REPORT_GENERATION_SKILL_FOLDER,
)
SKILL_FOLDER_RE = re.compile(r"^[A-Za-z0-9._-]+$")
PROVIDER_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")

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
    provider_rows = get_runtime_registry_store().list_providers()
    if provider_rows:
        # Registry rows are authoritative. The copy in raw_json is retained only
        # as rollback data for versions predating the registry migration.
        db_payload["provider_settings"] = {
            str(row.get("provider_id") or ""): row for row in provider_rows if row.get("provider_id")
        }
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
    if PROVIDER_ID_RE.match(value):
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
            "allow_anonymous": item.get("allow_anonymous") is True,
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
    return {
        "display_name": provider_id,
        "default_base_url": "",
        "default_model": "",
        "supported_models": [],
    }


def _default_provider_settings(provider_id: str) -> dict[str, Any]:
    definition = _provider_definition(provider_id)
    default_format = "/v1/messages"
    return {
        "provider_id": provider_id,
        "provider_type": default_format,
        "api_format": default_format,
        "display_name": str(definition.get("display_name") or provider_id),
        "provider_group": "",
        "provider_enabled": False,
        "api_key": "",
        "auth_token": "",
        "base_url": "",
        "supports_partial_messages": True,
        "enabled_models": [],
        "custom_models": [],
        "models": [],
        "model_detections": {},
        "validation_status": "unverified",
        "validation_message": "",
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

    legacy = {}
    if provider_id:
        target = legacy.setdefault(provider_id, _default_provider_settings(provider_id))
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
    ordered_ids = sorted(provider_settings.keys())
    for provider_id in ordered_ids:
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

    api_format = normalize_api_format(base.get("api_format"))
    provider_type = api_format

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
    raw_models = base.get("models") if isinstance(base.get("models"), list) else []
    models: list[dict[str, Any]] = []
    for raw_model in raw_models:
        if isinstance(raw_model, dict):
            model_id = str(raw_model.get("id") or raw_model.get("model_id") or "").strip()
            if not model_id:
                continue
            models.append({
                "id": model_id,
                "max_output_tokens": raw_model.get("max_output_tokens"),
                "context_window": raw_model.get("context_window"),
            })
        else:
            model_id = str(raw_model or "").strip()
            if model_id:
                models.append({"id": model_id, "max_output_tokens": None, "context_window": None})
    model_detections = _normalize_model_detections(base.get("model_detections"))
    supported_models = _string_list(
        list(definition.get("supported_models") or [])
        + custom_models
        + requested_enabled_models
        + [str(item.get("id") or "") for item in models]
        + list(model_detections.keys())
    )
    enabled_models = requested_enabled_models
    base_url = str(base.get("base_url") or definition.get("default_base_url") or "").strip()
    api_key = str(base.get("api_key") or "").strip()
    auth_token = str(base.get("auth_token") or "").strip()
    supports_partial_messages = bool(base.get("supports_partial_messages", True))

    status, message = _compute_provider_validation(
        api_format,
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
        "provider_type": provider_type,
        "api_format": api_format,
        "display_name": str(base.get("name") or base.get("display_name") or definition.get("display_name") or provider_id).strip()[:128],
        "provider_group": str(base.get("provider_group") or definition.get("provider_group") or "").strip()[:64],
        "provider_enabled": provider_enabled,
        "api_key": api_key,
        "auth_token": auth_token,
        "base_url": base_url,
        "supports_partial_messages": supports_partial_messages,
        "enabled_models": enabled_models,
        "custom_models": custom_models,
        "models": models,
        "supported_models": supported_models,
        "model_detections": model_detections,
        "validation_status": status,
        "validation_message": message,
        "validated_at": validated_at,
        "enabled": provider_enabled and bool(enabled_models) and status == "verified",
    }


def _compute_provider_validation(
    api_format: str,
    *,
    provider_enabled: bool,
    api_key: str,
    auth_token: str,
    base_url: str,
    enabled_models: list[str],
) -> tuple[str, str]:
    if not provider_enabled:
        return ("unverified", "供应商未启用")
    fmt = normalize_api_format(api_format)
    token_ready = bool(auth_token or api_key)
    if not token_ready:
        if fmt == "/v1/messages":
            return ("unverified", "请填写 API Key")
        return ("unverified", "请填写 Token")
    if not str(base_url or "").strip():
        return ("unverified", "请填写 API Base URL")
    if not enabled_models:
        return ("unverified", "请启用至少一个模型")
    return ("verified", "模型服务已可用")


def _merge_provider_settings(
    current: dict[str, dict[str, Any]] | None,
    patch: dict[str, dict[str, Any]] | None,
    *,
    legacy_payload: dict[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    # Once provider rows exist they are the complete registry. Only synthesize
    # the four historical definitions while bootstrapping an empty registry.
    merged: dict[str, dict[str, Any]] = (
        {} if current else _legacy_provider_settings(legacy_payload)
    )
    for provider_id in set(current or {}):
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
        if "models" in update:
            current_entry["models"] = list(update.get("models") or [])
        if "name" in update or "display_name" in update:
            current_entry["display_name"] = str(update.get("name") or update.get("display_name") or "").strip()
        if "provider_group" in update:
            current_entry["provider_group"] = str(update.get("provider_group") or "").strip()
        if "provider_type" in update:
            current_entry["provider_type"] = str(update.get("provider_type") or "").strip()
        if "api_format" in update:
            current_entry["api_format"] = str(update.get("api_format") or "").strip()
        if update.get("enabled") is False:
            current_entry["enabled_models"] = []

        merged[provider_id] = _normalize_provider_entry(provider_id, current_entry, merged.get(provider_id))

    return {
        provider_id: _normalize_provider_entry(provider_id, merged.get(provider_id) or {}, None)
        for provider_id in sorted(merged.keys())
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
    if not provider_id or provider_id not in provider_settings:
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
    if provider_id and not PROVIDER_ID_RE.match(provider_id):
        raise ValueError("provider_id must match A-Za-z0-9._- and be at most 64 characters")

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
    api_format: str,
    *,
    api_key: str,
    auth_token: str,
    base_url: str,
    model: str,
) -> str:
    if not model:
        return "请选择模型"
    fmt = normalize_api_format(api_format)
    token_ready = bool(auth_token or api_key)
    if not token_ready:
        return "API 密钥缺失" if fmt == "/v1/messages" else "Token 缺失"
    if not str(base_url or "").strip():
        return "API Base URL 缺失"
    return ""


async def _run_model_detection(
    *,
    provider_id: str = "",
    api_format: str,
    model: str,
    api_key: str,
    auth_token: str,
    base_url: str,
    supports_partial_messages: bool,
) -> tuple[str, str]:
    logger.info(
        "model_detection.start provider=%s api_format=%s model=%s base_url=%s supports_partial_messages=%s auth_token_set=%s api_key_set=%s",
        provider_id,
        api_format,
        model,
        safe_base_url_for_log(base_url),
        supports_partial_messages,
        bool(str(auth_token or "").strip()),
        bool(str(api_key or "").strip()),
    )
    try:
        await request_model_text(
            api_format=api_format,
            base_url=base_url,
            api_key=api_key,
            auth_token=auth_token,
            model=model,
            prompt="请直接回复 model-service-ok。",
            timeout_seconds=MODEL_DETECTION_TIMEOUT_SECONDS,
            max_output_tokens=32,
        )
        return "verified", "模型检测通过"
    except Exception as exc:
        return "failed", f"模型检测失败: {_short_error(exc)}"


async def detect_model_availability(payload: dict[str, Any]) -> dict[str, str]:
    provider_id = _normalize_provider_id(payload.get("provider_id"), allow_empty=True)
    if not provider_id:
        raise ValueError("provider_id must match A-Za-z0-9._- and be at most 64 characters")

    model = str(payload.get("model") or "").strip()
    if not model:
        raise ValueError("model is required")

    current = current_settings_payload()
    provider_settings = _coerce_provider_settings(current.get("provider_settings"))
    if provider_id not in provider_settings:
        raise ValueError("provider not found")
    current_entry = _normalize_provider_entry(provider_id, provider_settings.get(provider_id) or {})
    api_format = normalize_api_format(payload.get("api_format") or current_entry.get("api_format"))

    api_key = str(payload.get("api_key") or current_entry.get("api_key") or "").strip()
    auth_token = str(payload.get("auth_token") or current_entry.get("auth_token") or "").strip()
    base_url = str(payload.get("base_url") or current_entry.get("base_url") or "").strip()
    supports_partial_messages = (
        bool(payload.get("supports_partial_messages"))
        if payload.get("supports_partial_messages") is not None
        else bool(current_entry.get("supports_partial_messages", True))
    )

    preflight_message = _detection_preflight(
        api_format,
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
            api_format=api_format,
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
    registry = get_runtime_registry_store()
    registry.init_schema()
    if not registry.list_providers():
        for provider in _coerce_provider_settings(merged.get("provider_settings")).values():
            normalized = _normalize_provider_entry(str(provider.get("provider_id") or ""), provider)
            registry.save_provider(normalized)
        merged = current_settings_payload()
    update_settings(runtime_patch_from_payload(merged))

    if not db_payload:
        store.save_settings_record(merged)
    return current_settings_payload()


def persist_admin_settings(payload: dict[str, Any]) -> dict[str, Any]:
    provider_patch = _coerce_provider_settings(payload.get("provider_settings") or payload.get("providers"))
    for provider_id, entry in provider_patch.items():
        save_provider_config({"provider_id": provider_id, **dict(entry)}, create=False)

    current = current_settings_payload()
    settings_patch = {key: value for key, value in payload.items() if key not in {"provider_settings", "providers"}}
    merged = _merge_settings_payload(current, settings_patch)
    validate_settings_payload(merged)

    update_settings(runtime_patch_from_payload(merged))
    store = get_skill_admin_store()
    saved = store.save_settings_record(merged)
    return current_settings_payload() | {"updated_at": saved.get("updated_at", "")}


def list_provider_configs(
    *,
    payload: dict[str, Any] | None = None,
    enabled_only: bool = False,
    runtime_only: bool = False,
) -> list[dict[str, Any]]:
    resolved = payload or current_settings_payload()
    provider_settings = _coerce_provider_settings(resolved.get("provider_settings"))
    configs: list[dict[str, Any]] = []

    ordered_ids = sorted(provider_settings.keys())
    for provider_id in ordered_ids:
        if provider_id not in provider_settings:
            continue
        definition = _provider_definition(provider_id)
        item = _normalize_provider_entry(provider_id, provider_settings.get(provider_id) or {})
        if enabled_only and not item.get("enabled"):
            continue
        configs.append(
            {
                "provider_id": provider_id,
                "provider_type": str(item.get("provider_type") or "anthropic"),
                "api_format": normalize_api_format(item.get("api_format")),
                "display_name": str(item.get("display_name") or definition.get("display_name") or provider_id),
                "name": str(item.get("display_name") or definition.get("display_name") or provider_id),
                "provider_group": str(item.get("provider_group") or definition.get("provider_group") or ""),
                "base_url": str(item.get("base_url") or ""),
                "api_key_set": bool(item.get("api_key")),
                "auth_token_set": bool(item.get("auth_token")),
                "models": (
                    list(item.get("enabled_models") or [])
                    if runtime_only or not item.get("models")
                    else list(item.get("models") or [])
                ),
                "enabled_models": list(item.get("enabled_models") or []),
                "supported_models": list(item.get("supported_models") or []),
                "custom_models": list(item.get("custom_models") or []),
                "model_detections": dict(item.get("model_detections") or {}),
                "default_model": (
                    (item.get("enabled_models") or [None])[0]
                    or str(definition.get("default_model") or "")
                ),
                "enabled": bool(item.get("enabled")),
                "provider_enabled": bool(item.get("provider_enabled")),
                "supports_partial_messages": bool(item.get("supports_partial_messages", True)),
                "validation_status": str(item.get("validation_status") or "unverified"),
                "validation_message": str(item.get("validation_message") or ""),
            }
        )

    configs.sort(key=lambda item: item["display_name"])
    return configs


def resolved_chat_settings_payload() -> dict[str, Any]:
    resolved = current_settings_payload()
    providers = list_provider_configs(payload=resolved, enabled_only=True, runtime_only=True)
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


def save_provider_config(payload: dict[str, Any], *, create: bool) -> dict[str, Any]:
    provider_id = _normalize_provider_id(payload.get("provider_id"), allow_empty=True)
    if not provider_id:
        raise ValueError("provider_id must match A-Za-z0-9._- and be at most 64 characters")
    store = get_runtime_registry_store()
    existing = store.get_provider(provider_id)
    if create and existing:
        raise ValueError("provider_id already exists")
    if not create and not existing:
        raise KeyError("provider not found")
    if create and not str(payload.get("name") or payload.get("display_name") or "").strip():
        raise ValueError("name is required")

    current_map = {provider_id: existing} if existing else {}
    merged_map = _merge_provider_settings(current_map, {provider_id: payload}, legacy_payload={})
    normalized = merged_map[provider_id]
    saved = store.save_provider(normalized)
    return saved


def delete_provider_config(provider_id: str) -> None:
    normalized_id = _normalize_provider_id(provider_id, allow_empty=True)
    if not normalized_id:
        raise KeyError("provider not found")
    store = get_runtime_registry_store()
    if not store.get_provider(normalized_id):
        raise KeyError("provider not found")
    settings_before_delete = current_settings_payload()
    was_current = str(settings_before_delete.get("provider_id") or "") == normalized_id
    store.delete_provider(normalized_id)

    if not was_current:
        return
    settings = current_settings_payload()
    candidates = list_provider_configs(payload=settings, enabled_only=True, runtime_only=True)
    next_provider = candidates[0] if candidates else None
    next_provider_id = str((next_provider or {}).get("provider_id") or "")
    next_models = list((next_provider or {}).get("models") or [])
    persist_admin_settings({
        "provider_id": next_provider_id,
        "model": str(next_models[0] if next_models else ""),
    })


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
        "provider_type": str(provider.get("provider_type") or "anthropic_compatible"),
        "api_format": normalize_api_format(provider.get("api_format")),
        "model": selected_model,
        "api_key": str(provider.get("api_key") or ""),
        "auth_token": str(provider.get("auth_token") or ""),
        "base_url": str(provider.get("base_url") or ""),
        "supports_partial_messages": bool(
            provider.get("supports_partial_messages", normalized_provider_id != "anthropic_compatible")
        ),
    }


def _managed_skill_entries() -> list[tuple[str, int, int]]:
    """Managed files with an mtime/size fingerprint, sorted by path.

    `stat` only — no file contents. This is what lets a list request detect an
    out-of-band edit without reading every skill file.
    """
    root = resolve_skill_discovery_root_dir()
    entries: list[tuple[str, int, int]] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() not in MANAGED_FILE_SUFFIXES:
            continue
        try:
            stat_result = path.stat()
        except OSError:
            # Disappeared mid-scan; the next request will see a new signature.
            continue
        entries.append((path.relative_to(root).as_posix(), stat_result.st_mtime_ns, stat_result.st_size))
    entries.sort()
    return entries


def managed_skill_files() -> list[str]:
    return [entry[0] for entry in _managed_skill_entries()]


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
    return _folder_from_skills_output_dir(str(get_settings().skills_output_dir or "")) or DEFAULT_PRIMARY_SKILL_FOLDER


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


def _document_api_payload(
    document: dict[str, Any],
    *,
    skill_runtime: dict[str, dict[str, bool]] | None = None,
    description_cache: dict[str, str] | None = None,
) -> dict[str, Any]:
    """Shape one stored document for the API.

    `skill_runtime` and `description_cache` let a caller resolve the shared
    per-request state once. Without them this reloads settings from MySQL and
    re-parses the folder's SKILL.md for every single document, which is how a
    list of 84 documents came to cost 255 fresh connections — see
    `list_documents`.
    """
    payload = dict(document or {})
    full_relative_path = str(payload.get("relative_path") or "").replace("\\", "/").strip("/")
    folder = _skill_folder_name(full_relative_path)
    payload["folder"] = folder
    payload["relative_path"] = _relative_path_within_skill(full_relative_path)
    payload["source"] = _skill_source(folder)
    payload["editable"] = True
    payload["enabled"] = _is_skill_enabled(folder, skill_runtime)
    # Every SKILL.md in this repo declares what the skill is for, and none of it
    # reached the UI: the list had no description field, so the client filled
    # that column with last_change_summary — the reindex note, identical on
    # every row ("发现磁盘文件") and looking like content while saying nothing.
    payload["description"] = _skill_description_from_front_matter(folder, description_cache)
    return payload


def _skill_description_from_front_matter(
    folder: str,
    description_cache: dict[str, str] | None = None,
) -> str:
    if not folder:
        return ""
    if description_cache is not None and folder in description_cache:
        return description_cache[folder]
    description = ""
    try:
        skill_md = (resolve_skill_discovery_root_dir() / folder / "SKILL.md").resolve()
    except Exception:
        skill_md = None
    if skill_md is not None:
        try:
            description = _front_matter_value(skill_md, "description")
        except ValueError:
            # Unreadable front matter is not worth failing a list request over.
            description = ""
    if description_cache is not None:
        description_cache[folder] = description
    return description


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


def _migrate_document_paths_to_discovery_root(store, documents: list[dict[str, Any]]) -> bool:
    """Move pre-discovery-root document paths under their skill folder.

    Takes the already-fetched document list so a plain read does not re-scan the
    table. Returns whether anything moved, so the caller knows to refetch.
    """
    skill_folders = _discovered_skill_folders()
    if not skill_folders:
        return False
    current_folder = _current_skill_folder()
    if not current_folder:
        return False
    known_paths = {
        str(document.get("relative_path") or "").replace("\\", "/").strip("/")
        for document in documents
    }
    migrated = False
    for document in documents:
        relative_path = str(document.get("relative_path") or "").replace("\\", "/").strip("/")
        if not relative_path:
            continue
        if _skill_folder_name(relative_path) in skill_folders:
            continue
        next_path = f"{current_folder}/{relative_path}"
        if next_path in known_paths:
            continue
        store.rename_document_path(relative_path, next_path)
        known_paths.discard(relative_path)
        known_paths.add(next_path)
        migrated = True
    return migrated


_reindex_lock = threading.Lock()
_indexed_signature: tuple[tuple[str, int, int], ...] | None = None


def reset_disk_index_signature() -> None:
    """Forget the last indexed disk state, forcing the next scan to do full work."""
    global _indexed_signature
    with _reindex_lock:
        _indexed_signature = None


def reindex_documents_from_disk(
    *,
    change_source: str = "import",
    change_summary: str = "发现磁盘文件",
    force: bool = False,
) -> list[dict[str, Any]]:
    """Sync managed skill files on disk into the document store.

    Short-circuits when no managed file's mtime or size has changed since the
    last scan, so a plain list request costs one `stat` pass instead of reading
    and hashing every skill file. The write paths (import, edit, rollback,
    uninstall) all keep the store in sync themselves, so the DB — not disk — is
    what a read serves; the signature check only covers edits made outside the
    app.
    """
    global _indexed_signature
    with _reindex_lock:
        return _reindex_documents_locked(
            change_source=change_source,
            change_summary=change_summary,
            force=force,
        )


def _reindex_documents_locked(*, change_source: str, change_summary: str, force: bool) -> list[dict[str, Any]]:
    global _indexed_signature
    entries = _managed_skill_entries()
    signature = tuple(entries)
    if not force and _indexed_signature is not None and signature == _indexed_signature:
        return []

    store = get_skill_admin_store()
    root = resolve_skill_discovery_root_dir()
    # One table read serves the migration, the orphan sweep and the hash
    # comparison below. Looking each file up individually cost one fresh MySQL
    # connection per managed file on every read.
    documents = store.list_documents()
    if _migrate_document_paths_to_discovery_root(store, documents):
        documents = store.list_documents()

    managed_paths = [entry[0] for entry in entries]
    managed_path_set = set(managed_paths)
    indexed: dict[str, dict[str, Any]] = {}
    for document in documents:
        relative_path = str(document.get("relative_path") or "").replace("\\", "/").strip("/")
        if not relative_path:
            continue
        if relative_path not in managed_path_set:
            store.delete_document_by_path(relative_path)
            continue
        indexed[relative_path] = document

    # Files whose fingerprint is unchanged since the last scan and which are
    # already indexed need no read: one changed file must not re-hash the whole
    # tree.
    unchanged_fingerprints = set(_indexed_signature or ())

    changed: list[dict[str, Any]] = []
    # Resolved on first change only: a warm reindex writes nothing and must not
    # pay a settings read, while a cold one (or a fresh import) shares a single
    # resolve across every document it rewrites.
    shared_runtime: dict[str, dict[str, bool]] | None = None
    description_cache: dict[str, str] = {}
    for entry in entries:
        relative_path = entry[0]
        existing = indexed.get(relative_path)
        if existing and entry in unchanged_fingerprints:
            continue
        file_path = root / relative_path
        content = file_path.read_text(encoding="utf-8")
        current_hash = existing.get("current_hash") if existing else None
        next_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
        if existing and current_hash == next_hash:
            continue
        if shared_runtime is None:
            shared_runtime = _skill_runtime_from_current_settings()
        saved = store.save_document(
            relative_path=relative_path,
            content=content,
            change_source=change_source,
            change_summary=change_summary,
            actor="system",
        )
        changed.append(
            _document_api_payload(
                saved,
                skill_runtime=shared_runtime,
                description_cache=description_cache,
            )
        )
    _indexed_signature = signature
    return changed


def _descriptions_from_store(store) -> dict[str, str]:
    """folder -> description, parsed from the SKILL.md content already in the DB."""
    contents = store.list_skill_manifest_contents()
    descriptions: dict[str, str] = {}
    for relative_path, content in contents.items():
        folder = _skill_folder_name(str(relative_path or "").replace("\\", "/").strip("/"))
        if not folder:
            continue
        descriptions[folder] = _front_matter_value_from_text(content, "description")
    return descriptions


def list_documents() -> list[dict[str, Any]]:
    """List managed skill documents from the store.

    Reads the DB only. Every write path keeps the store in sync — import
    reindexes, edits and rollbacks dual-write, uninstall deletes the rows, and
    `main.py` indexes at startup — so a page load needs no disk access at all.
    Disk belongs to the agent runtime, which consumes per-topic copies of the
    skill folders, and to `reindex_documents_from_disk()` for explicit syncs.
    """
    store = get_skill_admin_store()
    # Resolve the shared state once per request. Both of these used to be
    # recomputed per document: the runtime config via a fresh pair of MySQL
    # connections, the description via a re-read of the same SKILL.md from disk.
    # Every write path keeps the store in sync, so this serves the DB only.
    skill_runtime = _skill_runtime_from_current_settings()
    description_cache = _descriptions_from_store(store)
    documents = [
        _document_api_payload(item, skill_runtime=skill_runtime, description_cache=description_cache)
        for item in store.list_documents()
    ]
    documents.sort(key=lambda item: (str(item.get("folder") or ""), str(item.get("category") or ""), str(item.get("relative_path") or "")))
    return documents


def get_document_detail(document_id: int) -> dict[str, Any] | None:
    """Fetch one document with its version history.

    Content and versions live in the store, so opening a skill file needs no
    disk read either — the editor shows what the DB has, which is also what the
    last write put on disk.
    """
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


def _front_matter_value_from_text(text: str, key: str) -> str:
    lines = str(text or "").splitlines()
    if not lines or lines[0].strip() != "---":
        return ""
    for line in lines[1:]:
        if line.strip() == "---":
            break
        candidate_key, separator, value = line.partition(":")
        if separator and candidate_key.strip() == key:
            return value.strip().strip("'\"")
    return ""


def _front_matter_value(skill_md: Path, key: str) -> str:
    try:
        text = skill_md.read_text(encoding="utf-8")
    except FileNotFoundError:
        return ""
    except UnicodeDecodeError as exc:
        raise ValueError("SKILL.md must be UTF-8 encoded") from exc
    return _front_matter_value_from_text(text, key)


def _skill_name_from_front_matter(skill_md: Path) -> str:
    name = _front_matter_value(skill_md, "name")
    if not name:
        raise ValueError("根目录 SKILL.md 必须包含 front matter name")
    return name


def _optional_skill_name_from_front_matter(skill_md: Path) -> str:
    return _front_matter_value(skill_md, "name")


def _skill_version_from_front_matter(skill_md: Path) -> str:
    return _front_matter_value(skill_md, "version")


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
        declared_name = _optional_skill_name_from_front_matter(candidate / "SKILL.md")
        if declared_name and declared_name != candidate.name:
            raise ValueError("SKILL.md name must match skill folder")
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
        incoming_version = _skill_version_from_front_matter(skill_root / "SKILL.md")
        previous_version = ""
        replaced = False
        if target.exists() or target.is_symlink():
            if _is_builtin_skill_folder(folder):
                raise ValueError("内置 Skill 不支持覆盖导入")
            if not target.is_dir() or target.is_symlink():
                raise ValueError("invalid skill folder path")
            previous_version = _skill_version_from_front_matter(target / "SKILL.md")
            if incoming_version == previous_version:
                raise ValueError("同名 Skill 已存在且版本相同，请更新 SKILL.md front matter 中的 version 后重新导入")
            shutil.rmtree(target)
            replaced = True
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(skill_root), str(target))

    current = current_settings_payload()
    primary_folder = _folder_from_skills_output_dir(str(current.get("skills_output_dir") or "")) or _current_skill_folder()
    skill_runtime = _normalize_skill_runtime(current.get("skill_runtime"), fallback_folder=primary_folder)
    enabled = bool((skill_runtime.get(folder) or {}).get("enabled")) if replaced else False
    skill_runtime[folder] = {"enabled": enabled}
    persist_admin_settings({"skill_runtime": skill_runtime})

    change_summary = f"更新 Skill {folder}" if replaced else f"导入 Skill {folder}"
    imported = reindex_documents_from_disk(change_source="upload", change_summary=change_summary)
    imported_documents = [item for item in imported if item.get("folder") == folder]
    if not imported_documents:
        imported_documents = [_document_api_payload(item) for item in _raw_documents_for_skill(folder)]

    return {
        "skill_id": folder,
        "source": _skill_source(folder),
        "enabled": enabled,
        "replaced": replaced,
        "version": incoming_version,
        "previous_version": previous_version,
        "imported_documents": imported_documents,
        "document_count": len(get_skill_admin_store().list_documents()),
    }


def export_skill_as_zip(folder: str) -> tuple[str, bytes]:
    target_folder = _validate_skill_folder_name(folder)

    available_folders = _discovered_skill_folders()
    if target_folder not in available_folders:
        raise ValueError("skill folder not found")

    target = _resolve_skill_target_dir(target_folder)
    if not target.is_dir() or target.is_symlink():
        raise ValueError("invalid skill folder path")

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(target.rglob("*")):
            if path.is_symlink() or not path.is_file():
                continue
            arcname = f"{target_folder}/{path.relative_to(target).as_posix()}"
            archive.write(path, arcname)
    return f"{target_folder}.zip", buffer.getvalue()


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
        # 管理页列表来自 list_documents()，读的是 DB；本函数校验的是实时磁盘扫描，
        # 且以 SKILL.md 存在为准。所以一个目录可以既在列表里（.py/.json 建了行）
        # 又不可启用（缺 SKILL.md）——光说 "not found" 完全看不出是哪种情况。
        root = resolve_skill_discovery_root_dir()
        target_dir = root / target_folder
        if target_dir.is_dir():
            reason = "目录存在但缺少 SKILL.md" if not (target_dir / "SKILL.md").is_file() else "目录不可读"
        else:
            reason = "目录不存在"
        raise ValueError(f"skill folder not found: {target_folder}（{reason}，已扫描 {root}）")

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
