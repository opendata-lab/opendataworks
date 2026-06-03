from __future__ import annotations

import json
import os
import re
import threading
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any

import pymysql

from config import get_settings
from core.data_scope import normalize_data_scope

DEFAULT_AGENT_ID = "agent_default"
DEFAULT_AGENT_NAME = "默认助手"
OPENDATAWORKS_AGENT_ID = "agent_opendataworks"
OPENDATAWORKS_AGENT_NAME = "OpenDataWorks平台助手"
ONTOLOGY_MODELING_AGENT_ID = "agent_ontology_modeling"
ONTOLOGY_MODELING_AGENT_NAME = "本体建模助手"
PERMISSION_MODES = {"inherit", "default", "bypassPermissions"}
SAFE_AGENT_TOOLS = ["Skill", "Bash", "Read", "LS", "Glob", "Grep"]
GENERAL_AGENT_TOOLS = ["Read", "LS", "Glob", "Grep"]
PORTAL_MCP_SERVER_ID = "portal"
PORTAL_MCP_TOOL_NAMES = [
    "portal_search_tables",
    "portal_get_lineage",
    "portal_resolve_datasource",
    "portal_export_metadata",
    "portal_get_table_ddl",
    "portal_query_readonly",
]

RESERVED_ENV_KEYS = {"PATH", "HOME", "VIRTUAL_ENV", "TZ"}
RESERVED_ENV_PREFIXES = (
    "ANTHROPIC_",
    "DATAAGENT_",
    "MYSQL_",
    "DORIS_",
    "REDIS_",
    "ODW_",
    "PORTAL_MCP_",
)
ENV_KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def _to_iso(value: Any) -> str:
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value) if value is not None else ""


def _safe_json_load(raw: Any, fallback: Any) -> Any:
    if raw is None:
        return fallback
    if isinstance(raw, (dict, list)):
        return raw
    try:
        return json.loads(str(raw))
    except Exception:
        return fallback


def _json_dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def _dedupe_strings(values: Any) -> list[str]:
    if values is None:
        return []
    if isinstance(values, str):
        iterable: list[Any] = [values]
    elif isinstance(values, (list, tuple, set)):
        iterable = list(values)
    else:
        iterable = []

    result: list[str] = []
    seen: set[str] = set()
    for value in iterable:
        text = str(value or "").strip()
        if not text or text in seen:
            continue
        result.append(text)
        seen.add(text)
    return result


def _validate_name(value: Any) -> str:
    text = str(value or "").strip()
    if not text:
        raise ValueError("agent name is required")
    if len(text) > 128:
        raise ValueError("agent name must be at most 128 characters")
    return text


def _validate_permission_mode(value: Any) -> str:
    text = str(value or "inherit").strip() or "inherit"
    if text not in PERMISSION_MODES:
        raise ValueError("permission_mode must be inherit, default, or bypassPermissions")
    return text


def _validate_tools(values: Any) -> list[str]:
    tools = _dedupe_strings(values)
    allowed = set(SAFE_AGENT_TOOLS)
    unknown = [tool for tool in tools if tool not in allowed]
    if unknown:
        raise ValueError(f"unsupported allowed tool: {unknown[0]}")
    return tools


def _validate_members(values: Any, available: set[str], *, label: str) -> list[str]:
    selected = _dedupe_strings(values)
    unknown = [item for item in selected if item not in available]
    if unknown:
        raise ValueError(f"unknown {label}: {unknown[0]}")
    return selected


def _validate_max_turns(value: Any) -> int:
    if value in (None, ""):
        return 0
    try:
        turns = int(value)
    except Exception as exc:
        raise ValueError("max_turns must be an integer") from exc
    if turns < 0:
        raise ValueError("max_turns must be greater than or equal to 0")
    if turns > 200:
        raise ValueError("max_turns must be at most 200")
    return turns


def _validate_env_vars(value: Any) -> dict[str, str]:
    if value in (None, ""):
        return {}
    if not isinstance(value, dict):
        raise ValueError("env_vars must be an object")

    normalized: dict[str, str] = {}
    for raw_key, raw_value in value.items():
        key = str(raw_key or "").strip()
        upper_key = key.upper()
        if not key or not ENV_KEY_RE.match(key):
            raise ValueError(f"invalid environment variable name: {key}")
        if upper_key in RESERVED_ENV_KEYS or any(upper_key.startswith(prefix) for prefix in RESERVED_ENV_PREFIXES):
            raise ValueError(f"reserved environment variable: {key}")
        normalized[key] = str(raw_value or "")
    return dict(sorted(normalized.items()))


def _validate_preset_questions(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    result = []
    for item in value:
        text = str(item or "").strip()[:200]
        if text:
            result.append(text)
        if len(result) >= 3:
            break
    return result


def available_mcp_servers() -> list[dict[str, Any]]:
    cfg = get_settings()
    configured = bool(
        getattr(cfg, "dataagent_portal_mcp_enabled", True)
        and str(getattr(cfg, "dataagent_portal_mcp_base_url", "") or "").strip()
        and str(getattr(cfg, "dataagent_portal_mcp_token", "") or "").strip()
    )
    return [
        {
            "id": PORTAL_MCP_SERVER_ID,
            "name": "Portal MCP",
            "enabled": configured,
            "tool_names": list(PORTAL_MCP_TOOL_NAMES),
        }
    ]


def available_mcp_server_ids() -> set[str]:
    return {str(item.get("id") or "") for item in available_mcp_servers() if str(item.get("id") or "")}


def normalize_agent_profile_payload(
    payload: dict[str, Any],
    *,
    available_skill_folders: set[str],
    available_mcp_server_ids: set[str],
    existing: dict[str, Any] | None = None,
) -> dict[str, Any]:
    data = dict(payload or {})
    base = dict(existing or {})
    has_existing = bool(existing)

    name = _validate_name(data.get("name", base.get("name") if has_existing else ""))
    description = str(data.get("description", base.get("description") or "") or "").strip()
    system_prompt = str(data.get("system_prompt", base.get("system_prompt") or "") or "").strip()
    permission_mode = _validate_permission_mode(data.get("permission_mode", base.get("permission_mode") or "inherit"))
    allowed_tools = _validate_tools(data.get("allowed_tools", base.get("allowed_tools") or list(SAFE_AGENT_TOOLS)))
    mcp_server_ids = _validate_members(
        data.get("mcp_server_ids", base.get("mcp_server_ids") or []),
        available_mcp_server_ids,
        label="mcp server",
    )
    skill_folders = _validate_members(
        data.get("skill_folders", base.get("skill_folders") or []),
        available_skill_folders,
        label="skill folder",
    )
    max_turns = _validate_max_turns(data.get("max_turns", base.get("max_turns") or 0))
    env_vars = _validate_env_vars(data.get("env_vars", base.get("env_vars") or {}))
    data_scope = normalize_data_scope(data.get("data_scope", base.get("data_scope") or {}))
    raw_questions = data.get("preset_questions", base.get("preset_questions") or [])
    preset_questions = _validate_preset_questions(raw_questions)

    return {
        "name": name,
        "description": description,
        "system_prompt": system_prompt,
        "permission_mode": permission_mode,
        "allowed_tools": allowed_tools,
        "mcp_server_ids": mcp_server_ids,
        "skill_folders": skill_folders,
        "max_turns": max_turns,
        "env_vars": env_vars,
        "data_scope": data_scope,
        "preset_questions": preset_questions,
    }


def build_agent_snapshot(profile: dict[str, Any]) -> dict[str, Any]:
    return {
        "agent_id": str(profile.get("agent_id") or DEFAULT_AGENT_ID),
        "name": str(profile.get("name") or DEFAULT_AGENT_NAME),
        "description": str(profile.get("description") or ""),
        "system_prompt": str(profile.get("system_prompt") or ""),
        "permission_mode": str(profile.get("permission_mode") or "inherit"),
        "allowed_tools": _dedupe_strings(profile.get("allowed_tools")),
        "mcp_server_ids": _dedupe_strings(profile.get("mcp_server_ids")),
        "skill_folders": _dedupe_strings(profile.get("skill_folders")),
        "max_turns": int(profile.get("max_turns") or 0),
        "env_vars": _validate_env_vars(profile.get("env_vars") or {}),
        "data_scope": normalize_data_scope(profile.get("data_scope") or {}),
        "preset_questions": _validate_preset_questions(profile.get("preset_questions") or []),
        "is_default": bool(profile.get("is_default")),
        "is_builtin": bool(profile.get("is_builtin")),
    }


def agent_summary_from_snapshot(snapshot: dict[str, Any] | None) -> dict[str, Any]:
    payload = snapshot or default_agent_payload()
    return {
        "agent_id": str(payload.get("agent_id") or DEFAULT_AGENT_ID),
        "name": str(payload.get("name") or DEFAULT_AGENT_NAME),
        "description": str(payload.get("description") or ""),
        "is_default": bool(payload.get("is_default")),
        "is_builtin": bool(payload.get("is_builtin")),
    }


def normalize_agent_snapshot(raw: Any) -> dict[str, Any]:
    payload = _safe_json_load(raw, None)
    if not isinstance(payload, dict):
        payload = default_agent_payload()
    if not payload.get("agent_id"):
        payload["agent_id"] = DEFAULT_AGENT_ID
    if not payload.get("name"):
        payload["name"] = DEFAULT_AGENT_NAME
    return build_agent_snapshot(payload)


def default_agent_payload() -> dict[str, Any]:
    return {
        "agent_id": DEFAULT_AGENT_ID,
        "name": DEFAULT_AGENT_NAME,
        "description": "通用对话与分析入口，不预置 OpenDataWorks 专属 Skills。",
        "system_prompt": "",
        "permission_mode": "default",
        "allowed_tools": list(GENERAL_AGENT_TOOLS),
        "mcp_server_ids": [],
        "skill_folders": [],
        "max_turns": 0,
        "env_vars": {},
        "data_scope": {"allowed_scopes": []},
        "is_default": True,
        "is_builtin": True,
    }


def opendataworks_agent_payload() -> dict[str, Any]:
    return {
        "agent_id": OPENDATAWORKS_AGENT_ID,
        "name": OPENDATAWORKS_AGENT_NAME,
        "description": "面向 OpenDataWorks 数据门户、元数据、血缘、工作流和智能问数场景。",
        "system_prompt": "你是 OpenDataWorks 数据门户助手，优先围绕平台元数据、工作流、血缘、数据质量和智能问数场景提供帮助。",
        "permission_mode": "inherit",
        "allowed_tools": list(SAFE_AGENT_TOOLS),
        "mcp_server_ids": [PORTAL_MCP_SERVER_ID],
        "skill_folders": ["opendataworks-business-knowledge", "opendataworks-platform-tools"],
        "max_turns": 0,
        "env_vars": {},
        "data_scope": {"allowed_scopes": []},
        "is_default": False,
        "is_builtin": True,
    }


def ontology_modeling_agent_payload() -> dict[str, Any]:
    return {
        "agent_id": ONTOLOGY_MODELING_AGENT_ID,
        "name": ONTOLOGY_MODELING_AGENT_NAME,
        "description": "根据业务需求、上传文档和数据库表字段创建特定业务域本体语义 Skill。",
        "system_prompt": "你是 OpenDataWorks 本体建模助手，专注把用户需求、上传文档和数据库表字段整理成可复用的领域本体语义 Skill。",
        "permission_mode": "inherit",
        "allowed_tools": list(SAFE_AGENT_TOOLS),
        "mcp_server_ids": [PORTAL_MCP_SERVER_ID],
        "skill_folders": ["ontology-modeling-assistant"],
        "max_turns": 0,
        "env_vars": {},
        "data_scope": {"allowed_scopes": []},
        "preset_questions": [
            "帮我根据上传文档和候选表创建一个业务域本体 Skill",
            "把这些业务术语、表字段和指标口径整理成本体 JSON",
            "检查这个领域本体的对象、关系和 semantic_edges 是否完整",
        ],
        "is_default": False,
        "is_builtin": True,
    }


def _runtime_root() -> Path:
    cfg = get_settings()
    value = str(getattr(cfg, "dataagent_runtime_project_cwd", "") or "").strip()
    if value:
        path = Path(value).expanduser()
        if path.is_absolute():
            return path.resolve()
        return (Path(__file__).resolve().parent.parent / path).resolve()
    home = Path(os.environ.get("HOME") or str(Path.home())).expanduser()
    return (home / ".dataagent" / "runtime" / "enabled-skills").resolve()


def resolved_agent_workdir(agent_id: str, *, is_default: bool = False) -> str:
    safe_id = re.sub(r"[^A-Za-z0-9_.-]+", "-", str(agent_id or "").strip()) or "agent"
    if is_default and not str(agent_id or "").strip():
        safe_id = DEFAULT_AGENT_ID
    return str((_runtime_root().parent / "workspaces" / safe_id).resolve())


def _new_agent_id() -> str:
    return f"agent_{uuid.uuid4().hex[:24]}"


class AgentProfileStore:
    def __init__(self):
        self._ready = False
        self._ready_lock = threading.Lock()

    def _connect(self, database: str | None):
        cfg = get_settings()
        return pymysql.connect(
            host=cfg.mysql_host,
            port=cfg.mysql_port,
            user=cfg.mysql_user,
            password=cfg.mysql_password,
            database=database,
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            autocommit=False,
        )

    def _schema_name(self) -> str:
        return get_settings().session_mysql_database

    def init_schema(self):
        if self._ready:
            return
        with self._ready_lock:
            if self._ready:
                return
            self._ready = True

    def _ensure_ready(self):
        if not self._ready:
            self.init_schema()

    def _normalize_row(self, row: dict[str, Any] | None) -> dict[str, Any] | None:
        if not row:
            return None
        item = {
            "agent_id": str(row.get("agent_id") or ""),
            "name": str(row.get("name") or ""),
            "description": str(row.get("description") or ""),
            "system_prompt": str(row.get("system_prompt") or ""),
            "permission_mode": str(row.get("permission_mode") or "inherit"),
            "allowed_tools": _dedupe_strings(_safe_json_load(row.get("allowed_tools_json"), [])),
            "mcp_server_ids": _dedupe_strings(_safe_json_load(row.get("mcp_server_ids_json"), [])),
            "skill_folders": _dedupe_strings(_safe_json_load(row.get("skill_folders_json"), [])),
            "max_turns": int(row.get("max_turns") or 0),
            "env_vars": _validate_env_vars(_safe_json_load(row.get("env_vars_json"), {})),
            "data_scope": normalize_data_scope(_safe_json_load(row.get("data_scope_json"), {})),
            "preset_questions": _validate_preset_questions(_safe_json_load(row.get("preset_questions_json"), [])),
            "is_default": bool(row.get("is_default")),
            "is_builtin": bool(row.get("is_builtin")),
            "created_at": _to_iso(row.get("created_at")),
            "updated_at": _to_iso(row.get("updated_at")),
        }
        item["resolved_workdir"] = resolved_agent_workdir(item["agent_id"], is_default=item["is_default"])
        return item

    def list_profiles(self) -> list[dict[str, Any]]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT agent_id, name, description, system_prompt, permission_mode,
                           allowed_tools_json, mcp_server_ids_json, skill_folders_json,
                           max_turns, env_vars_json, data_scope_json, preset_questions_json,
                           is_default, is_builtin, created_at, updated_at
                    FROM da_agent_profile
                    ORDER BY is_builtin DESC, is_default DESC, updated_at DESC, created_at DESC
                    """
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return [item for item in (self._normalize_row(row) for row in rows) if item]

    def get_profile(self, agent_id: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT agent_id, name, description, system_prompt, permission_mode,
                           allowed_tools_json, mcp_server_ids_json, skill_folders_json,
                           max_turns, env_vars_json, data_scope_json, preset_questions_json,
                           is_default, is_builtin, created_at, updated_at
                    FROM da_agent_profile
                    WHERE agent_id = %s
                    LIMIT 1
                    """,
                    (agent_id,),
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_row(row)

    def save_profile(self, profile: dict[str, Any]) -> dict[str, Any]:
        self._ensure_ready()
        agent_id = str(profile.get("agent_id") or "").strip() or _new_agent_id()
        is_default = bool(profile.get("is_default"))
        is_builtin = bool(profile.get("is_builtin"))
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                if is_default:
                    cur.execute("UPDATE da_agent_profile SET is_default = 0 WHERE agent_id <> %s", (agent_id,))
                cur.execute(
                    """
                    INSERT INTO da_agent_profile (
                        agent_id, name, description, system_prompt, permission_mode,
                        allowed_tools_json, mcp_server_ids_json, skill_folders_json,
                        max_turns, env_vars_json, data_scope_json, preset_questions_json,
                        is_default, is_builtin
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON DUPLICATE KEY UPDATE
                        name = VALUES(name),
                        description = VALUES(description),
                        system_prompt = VALUES(system_prompt),
                        permission_mode = VALUES(permission_mode),
                        allowed_tools_json = VALUES(allowed_tools_json),
                        mcp_server_ids_json = VALUES(mcp_server_ids_json),
                        skill_folders_json = VALUES(skill_folders_json),
                        max_turns = VALUES(max_turns),
                        env_vars_json = VALUES(env_vars_json),
                        data_scope_json = VALUES(data_scope_json),
                        preset_questions_json = VALUES(preset_questions_json),
                        is_default = VALUES(is_default),
                        is_builtin = VALUES(is_builtin),
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    (
                        agent_id,
                        str(profile.get("name") or ""),
                        str(profile.get("description") or ""),
                        str(profile.get("system_prompt") or ""),
                        str(profile.get("permission_mode") or "inherit"),
                        _json_dump(_dedupe_strings(profile.get("allowed_tools"))),
                        _json_dump(_dedupe_strings(profile.get("mcp_server_ids"))),
                        _json_dump(_dedupe_strings(profile.get("skill_folders"))),
                        int(profile.get("max_turns") or 0),
                        _json_dump(_validate_env_vars(profile.get("env_vars") or {})),
                        _json_dump(normalize_data_scope(profile.get("data_scope") or {})),
                        _json_dump(_validate_preset_questions(profile.get("preset_questions") or [])),
                        1 if is_default else 0,
                        1 if is_builtin else 0,
                    ),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_profile(agent_id) or {}

    def delete_profile(self, agent_id: str) -> bool:
        self._ensure_ready()
        profile = self.get_profile(agent_id)
        if not profile:
            return False
        if profile.get("is_builtin"):
            raise ValueError("built-in agent cannot be deleted")
        if self.count_topic_references(agent_id) > 0:
            raise ValueError("agent is referenced by topics")
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM da_agent_profile WHERE agent_id = %s", (agent_id,))
            conn.commit()
        finally:
            conn.close()
        return True

    def count_topic_references(self, agent_id: str) -> int:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) AS total FROM da_agent_topic WHERE agent_id = %s", (agent_id,))
                row = cur.fetchone() or {}
        finally:
            conn.close()
        return int(row.get("total") or 0)

    def backfill_default_bindings(self, default_snapshot: dict[str, Any]) -> None:
        self._ensure_ready()
        snapshot_json = _json_dump(build_agent_snapshot(default_snapshot))
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_topic
                    SET agent_id = %s,
                        agent_snapshot_json = %s
                    WHERE agent_id IS NULL
                       OR agent_id = ''
                       OR agent_snapshot_json IS NULL
                       OR agent_snapshot_json = ''
                    """,
                    (DEFAULT_AGENT_ID, snapshot_json),
                )
                cur.execute(
                    """
                    UPDATE da_agent_task
                    SET agent_id = %s,
                        agent_snapshot_json = %s
                    WHERE agent_id IS NULL
                       OR agent_id = ''
                       OR agent_snapshot_json IS NULL
                       OR agent_snapshot_json = ''
                    """,
                    (DEFAULT_AGENT_ID, snapshot_json),
                )
            conn.commit()
        finally:
            conn.close()


_agent_profile_store = AgentProfileStore()


def get_agent_profile_store() -> AgentProfileStore:
    return _agent_profile_store


def bootstrap_default_agent_profile() -> dict[str, Any]:
    store = get_agent_profile_store()
    store.init_schema()
    default_profile = store.get_profile(DEFAULT_AGENT_ID)
    if not default_profile:
        default_profile = store.save_profile(default_agent_payload())
    opendataworks_profile = store.get_profile(OPENDATAWORKS_AGENT_ID)
    if not opendataworks_profile:
        store.save_profile(opendataworks_agent_payload())
    ontology_modeling_profile = store.get_profile(ONTOLOGY_MODELING_AGENT_ID)
    if not ontology_modeling_profile:
        store.save_profile(ontology_modeling_agent_payload())
    store.backfill_default_bindings(default_profile)
    return default_profile


def list_agent_profiles() -> list[dict[str, Any]]:
    bootstrap_default_agent_profile()
    return get_agent_profile_store().list_profiles()


def get_agent_profile(agent_id: str) -> dict[str, Any] | None:
    bootstrap_default_agent_profile()
    return get_agent_profile_store().get_profile(str(agent_id or "").strip())


def create_agent_profile(payload: dict[str, Any], *, available_skill_folders: set[str] | None = None) -> dict[str, Any]:
    skill_folders = available_skill_folders
    if skill_folders is None:
        skill_folders = set(_dedupe_strings((payload or {}).get("skill_folders")))
    normalized = normalize_agent_profile_payload(
        payload,
        available_skill_folders=skill_folders,
        available_mcp_server_ids=available_mcp_server_ids(),
    )
    normalized["agent_id"] = _new_agent_id()
    normalized["is_default"] = False
    return get_agent_profile_store().save_profile(normalized)


def update_agent_profile(
    agent_id: str,
    payload: dict[str, Any],
    *,
    available_skill_folders: set[str] | None = None,
) -> dict[str, Any]:
    existing = get_agent_profile(agent_id)
    if not existing:
        raise ValueError("agent not found")
    skill_folders = available_skill_folders
    if skill_folders is None:
        skill_folders = set(_dedupe_strings((payload or {}).get("skill_folders") or existing.get("skill_folders")))
    normalized = normalize_agent_profile_payload(
        payload,
        existing=existing,
        available_skill_folders=skill_folders,
        available_mcp_server_ids=available_mcp_server_ids(),
    )
    normalized["agent_id"] = existing["agent_id"]
    normalized["is_default"] = bool(existing.get("is_default"))
    normalized["is_builtin"] = bool(existing.get("is_builtin"))
    return get_agent_profile_store().save_profile(normalized)


def delete_agent_profile(agent_id: str) -> bool:
    return get_agent_profile_store().delete_profile(str(agent_id or "").strip())


def agent_capabilities(skill_documents: list[dict[str, Any]]) -> dict[str, Any]:
    folders: dict[str, dict[str, Any]] = {}
    for document in skill_documents:
        folder = str(document.get("folder") or "").strip()
        if not folder:
            relative = str(document.get("relative_path") or "").strip("/")
            folder = relative.split("/", 1)[0] if "/" in relative else ""
        if not folder:
            continue
        item = folders.setdefault(
            folder,
            {
                "folder": folder,
                "source": str(document.get("source") or "bundled"),
                "enabled": bool(document.get("enabled")),
            },
        )
        item["enabled"] = bool(item.get("enabled")) or bool(document.get("enabled"))
    return {
        "tools": list(SAFE_AGENT_TOOLS),
        "mcp_servers": available_mcp_servers(),
        "skills": sorted(folders.values(), key=lambda item: item["folder"]),
        "permission_modes": sorted(PERMISSION_MODES),
    }


def skill_folders_from_documents(skill_documents: list[dict[str, Any]]) -> set[str]:
    folders: set[str] = set()
    for item in agent_capabilities(skill_documents).get("skills") or []:
        folder = str(item.get("folder") or "").strip()
        if folder:
            folders.add(folder)
    return folders


def list_data_scope_options() -> list[dict[str, Any]]:
    cfg = get_settings()
    metadata_schema = str(cfg.mysql_database or "opendataworks").strip() or "opendataworks"
    rows: list[dict[str, Any]] = []
    conn = pymysql.connect(
        host=cfg.mysql_host,
        port=cfg.mysql_port,
        user=cfg.mysql_user,
        password=cfg.mysql_password,
        database=metadata_schema,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT
                    dt.cluster_id,
                    COALESCE(dc.cluster_name, '') AS cluster_name,
                    COALESCE(NULLIF(dc.source_type, ''), 'DORIS') AS source_type,
                    dt.db_name AS database_name
                FROM `{metadata_schema}`.`data_table` dt
                LEFT JOIN `{metadata_schema}`.`doris_cluster` dc
                    ON dc.id = dt.cluster_id
                WHERE dt.deleted = 0
                  AND (dt.status IS NULL OR dt.status <> 'deprecated')
                  AND dt.db_name IS NOT NULL
                  AND dt.db_name <> ''
                GROUP BY dt.cluster_id, dc.cluster_name, dc.source_type, dt.db_name
                ORDER BY dc.cluster_name ASC, dt.db_name ASC
                """
            )
            rows.extend(dict(item) for item in (cur.fetchall() or []))
    finally:
        conn.close()

    platform_database = str(cfg.mysql_database or "").strip()
    if platform_database:
        rows.append(
            {
                "cluster_id": None,
                "cluster_name": "platform-mysql",
                "source_type": "MYSQL",
                "database_name": platform_database,
            }
        )

    result: list[dict[str, Any]] = []
    seen: set[tuple[int | None, str]] = set()
    for row in rows:
        database = str(row.get("database_name") or row.get("database") or "").strip()
        if not database:
            continue
        cluster_id = row.get("cluster_id")
        cluster_id = int(cluster_id) if cluster_id not in (None, "") else None
        key = (cluster_id, database)
        if key in seen:
            continue
        seen.add(key)
        result.append(
            {
                "cluster_id": cluster_id,
                "cluster_name": str(row.get("cluster_name") or ""),
                "source_type": str(row.get("source_type") or "").upper(),
                "database": database,
            }
        )
    return result
