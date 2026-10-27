"""助手可见范围：归一化与后端强制判定。

可见性存储在 ``da_agent_profile.visibility_json``
一样走"归一化 JSON 列"模式：

    {"mode": "all|authenticated|selected", "allowed_users": [...], "allowed_groups": []}

``allowed_users`` 存放命名空间化稳定用户 ID（local:<u> / <provider>:<sub>，
与 ``ADMIN_USERS`` 同格式）。``allowed_groups`` 为用户组预留字段：随配置
归一化、持久化，但在组来源（OAuth groups claim 或组管理表）落地前不参与
匹配（docs/design/2026-07-20-agent-visibility-scope-design.md）。
"""
from __future__ import annotations

import json
from typing import Any

from core.auth import AuthIdentity, is_auth_enabled

VISIBILITY_MODE_ALL = "all"
VISIBILITY_MODE_AUTHENTICATED = "authenticated"
VISIBILITY_MODE_SELECTED = "selected"
VISIBILITY_MODES = (
    VISIBILITY_MODE_ALL,
    VISIBILITY_MODE_AUTHENTICATED,
    VISIBILITY_MODE_SELECTED,
)

MAX_ALLOWED_USERS = 200
MAX_ALLOWED_USER_LENGTH = 255
MAX_ALLOWED_GROUPS = 50
MAX_ALLOWED_GROUP_LENGTH = 128


def default_agent_visibility() -> dict[str, Any]:
    return {
        "mode": VISIBILITY_MODE_ALL,
        "allowed_users": [],
        "allowed_groups": [],
    }


def normalize_agent_visibility(value: Any, *, strict: bool = False) -> dict[str, Any]:
    """归一化可见性配置。

    ``strict=False``（DB 读侧）对缺失/脏数据容错，一律回落默认全量可见；
    ``strict=True``（API 入参）对未知 mode、非法结构、超限抛 ``ValueError``，
    与 ``normalize_agent_profile_payload`` 的既有校验错误走同一条 400 路径。
    """
    payload = _safe_json_load(value)
    if not isinstance(payload, dict):
        if strict and payload not in (None, ""):
            raise ValueError("visibility must be an object")
        payload = {}

    raw_mode = str(payload.get("mode") or "").strip().lower()
    if not raw_mode:
        mode = VISIBILITY_MODE_ALL
    elif raw_mode in VISIBILITY_MODES:
        mode = raw_mode
    elif strict:
        raise ValueError(f"invalid visibility mode: {raw_mode}")
    else:
        mode = VISIBILITY_MODE_ALL

    allowed_users = _normalize_entries(
        payload.get("allowed_users"),
        max_items=MAX_ALLOWED_USERS,
        max_length=MAX_ALLOWED_USER_LENGTH,
        label="visibility allowed_users",
        strict=strict,
    )
    allowed_groups = _normalize_entries(
        payload.get("allowed_groups"),
        max_items=MAX_ALLOWED_GROUPS,
        max_length=MAX_ALLOWED_GROUP_LENGTH,
        label="visibility allowed_groups",
        strict=strict,
    )

    return {
        "mode": mode,
        "allowed_users": allowed_users,
        "allowed_groups": allowed_groups,
    }


def agent_visible_to(profile: dict[str, Any] | None, identity: AuthIdentity | None) -> bool:
    """判定助手对给定身份是否可见。

    auth 未启用时整体放行，保持与 ``require_admin``/``require_user`` 一致的
    fail-open 回滚语义；管理员始终可见。
    """
    if not is_auth_enabled():
        return True
    if identity is not None and identity.is_admin:
        return True

    visibility = normalize_agent_visibility((profile or {}).get("visibility"))
    mode = visibility["mode"]
    if mode == VISIBILITY_MODE_ALL:
        return True
    if identity is None:
        return False
    if mode == VISIBILITY_MODE_AUTHENTICATED:
        return True
    return identity.user_id in visibility["allowed_users"]


def filter_visible_agent_profiles(
    profiles: list[dict[str, Any]], identity: AuthIdentity | None
) -> list[dict[str, Any]]:
    return [profile for profile in profiles if agent_visible_to(profile, identity)]


def _normalize_entries(
    value: Any,
    *,
    max_items: int,
    max_length: int,
    label: str,
    strict: bool,
) -> list[str]:
    if value in (None, ""):
        return []
    if not isinstance(value, (list, tuple)):
        if strict:
            raise ValueError(f"{label} must be a list")
        return []

    result: list[str] = []
    seen: set[str] = set()
    for item in value:
        entry = str(item or "").strip()
        if not entry or entry in seen:
            continue
        if len(entry) > max_length:
            if strict:
                raise ValueError(f"{label} entry too long (max {max_length} chars)")
            continue
        seen.add(entry)
        result.append(entry)
    if len(result) > max_items:
        if strict:
            raise ValueError(f"{label} too many entries (max {max_items})")
        result = result[:max_items]
    return result


def _safe_json_load(value: Any) -> Any:
    if isinstance(value, str) and value.strip():
        try:
            return json.loads(value)
        except Exception:
            return {}
    return value
