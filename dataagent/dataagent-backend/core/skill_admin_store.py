from __future__ import annotations

import hashlib
import json
import logging
import threading
from datetime import datetime
from pathlib import Path
from typing import Any

import pymysql

from config import get_settings

logger = logging.getLogger(__name__)
KNOWN_SKILL_CATEGORIES = {"reference", "scripts", "assets"}


def _to_iso(value: Any) -> str:
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value) if value is not None else ""


def _json_default(value: Any) -> str:
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value)


def _sha256(content: str) -> str:
    return hashlib.sha256((content or "").encode("utf-8")).hexdigest()


def _normalize_provider_settings_blob(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return dict(value)
    if isinstance(value, list):
        normalized: dict[str, Any] = {}
        for entry in value:
            if not isinstance(entry, dict):
                continue
            provider_id = str(entry.get("provider_id") or "").strip()
            if not provider_id:
                continue
            normalized[provider_id] = dict(entry)
        return normalized
    return {}


def _normalize_skill_runtime_blob(value: Any) -> dict[str, dict[str, bool]]:
    if not isinstance(value, dict):
        return {}
    normalized: dict[str, dict[str, bool]] = {}
    for folder, entry in value.items():
        folder_name = str(folder or "").strip()
        if not folder_name:
            continue
        if isinstance(entry, dict):
            enabled = bool(entry.get("enabled"))
        else:
            enabled = bool(entry)
        normalized[folder_name] = {"enabled": enabled}
    return normalized


def _content_type_for_path(relative_path: str) -> str:
    suffix = Path(relative_path).suffix.lower()
    if suffix == ".json":
        return "json"
    if suffix == ".py":
        return "python"
    if suffix in {".md", ".markdown"}:
        return "markdown"
    return "text"


def _category_for_path(relative_path: str) -> str:
    normalized = str(relative_path or "").replace("\\", "/").strip("/")
    if not normalized:
        return "root"
    parts = normalized.split("/")
    if len(parts) == 1:
        return "root"
    if parts[0] in KNOWN_SKILL_CATEGORIES:
        return parts[0]
    if len(parts) >= 3 and parts[1] in KNOWN_SKILL_CATEGORIES:
        return parts[1]
    return "root"


class SkillAdminStore:
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
        cfg = get_settings()
        return cfg.session_mysql_database

    def init_schema(self):
        if self._ready:
            return
        with self._ready_lock:
            if self._ready:
                return
            self._ready = True
            logger.info("Skill admin store is ready; schema is expected to be managed by Alembic")

    def _ensure_ready(self):
        if not self._ready:
            self.init_schema()

    def load_settings_record(self) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT settings_key, provider_id, model_name, anthropic_api_key, anthropic_auth_token,
                           anthropic_base_url, mysql_host, mysql_port, mysql_user, mysql_password,
                           mysql_database, doris_host, doris_port, doris_user, doris_password,
                           doris_database, skills_output_dir, raw_json, updated_at
                    FROM da_agent_settings
                    WHERE settings_key = 'default'
                    LIMIT 1
                    """
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_settings_row(row) if row else None

    def save_settings_record(self, payload: dict[str, Any]) -> dict[str, Any]:
        self._ensure_ready()
        normalized = self._normalize_settings_payload(payload)
        raw_json = json.dumps(normalized, ensure_ascii=False, default=_json_default)

        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO da_agent_settings (
                        settings_key, provider_id, model_name, anthropic_api_key, anthropic_auth_token,
                        anthropic_base_url, mysql_host, mysql_port, mysql_user, mysql_password,
                        mysql_database, doris_host, doris_port, doris_user, doris_password,
                        doris_database, skills_output_dir, raw_json
                    ) VALUES (
                        'default', %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
                    )
                    ON DUPLICATE KEY UPDATE
                        provider_id = VALUES(provider_id),
                        model_name = VALUES(model_name),
                        anthropic_api_key = VALUES(anthropic_api_key),
                        anthropic_auth_token = VALUES(anthropic_auth_token),
                        anthropic_base_url = VALUES(anthropic_base_url),
                        mysql_host = VALUES(mysql_host),
                        mysql_port = VALUES(mysql_port),
                        mysql_user = VALUES(mysql_user),
                        mysql_password = VALUES(mysql_password),
                        mysql_database = VALUES(mysql_database),
                        doris_host = VALUES(doris_host),
                        doris_port = VALUES(doris_port),
                        doris_user = VALUES(doris_user),
                        doris_password = VALUES(doris_password),
                        doris_database = VALUES(doris_database),
                        skills_output_dir = VALUES(skills_output_dir),
                        raw_json = VALUES(raw_json),
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    (
                        normalized["provider_id"],
                        normalized["model"],
                        normalized["anthropic_api_key"],
                        normalized["anthropic_auth_token"],
                        normalized["anthropic_base_url"],
                        normalized["mysql_host"],
                        normalized["mysql_port"],
                        normalized["mysql_user"],
                        normalized["mysql_password"],
                        normalized["mysql_database"],
                        normalized["doris_host"],
                        normalized["doris_port"],
                        normalized["doris_user"],
                        normalized["doris_password"],
                        normalized["doris_database"],
                        normalized["skills_output_dir"],
                        raw_json,
                    ),
                )
            conn.commit()
        finally:
            conn.close()
        return self.load_settings_record() or normalized

    def list_documents(self) -> list[dict[str, Any]]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id, relative_path, file_name, category, content_type, current_hash,
                           current_version_id, version_count, last_change_source, last_change_summary,
                           created_at, updated_at
                    FROM da_skill_document
                    ORDER BY FIELD(category, 'root', 'reference', 'scripts', 'assets'),
                             relative_path
                    """
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return [self._normalize_document_row(row) for row in rows]

    def get_document(self, document_id: int) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id, relative_path, file_name, category, content_type, current_content, current_hash,
                           current_version_id, version_count, last_change_source, last_change_summary,
                           created_at, updated_at
                    FROM da_skill_document
                    WHERE id = %s
                    LIMIT 1
                    """,
                    (document_id,),
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_document_row(row, include_content=True) if row else None

    def get_document_by_path(self, relative_path: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id, relative_path, file_name, category, content_type, current_content, current_hash,
                           current_version_id, version_count, last_change_source, last_change_summary,
                           created_at, updated_at
                    FROM da_skill_document
                    WHERE relative_path = %s
                    LIMIT 1
                    """,
                    (relative_path,),
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_document_row(row, include_content=True) if row else None

    def rename_document_path(self, old_relative_path: str, new_relative_path: str):
        self._ensure_ready()
        old_path = str(old_relative_path or "").replace("\\", "/").strip("/")
        new_path = str(new_relative_path or "").replace("\\", "/").strip("/")
        if not old_path or not new_path or old_path == new_path:
            return
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_skill_document
                    SET relative_path = %s,
                        file_name = %s,
                        category = %s,
                        content_type = %s,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE relative_path = %s
                    """,
                    (
                        new_path,
                        Path(new_path).name,
                        _category_for_path(new_path),
                        _content_type_for_path(new_path),
                        old_path,
                    ),
                )
            conn.commit()
        finally:
            conn.close()

    def list_versions(self, document_id: int) -> list[dict[str, Any]]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT v.id, v.document_id, v.version_no, v.change_source, v.change_summary, v.actor,
                           v.content_hash, v.file_size, v.metadata_json, v.parent_version_id, v.created_at,
                           d.current_version_id
                    FROM da_skill_document_version v
                    INNER JOIN da_skill_document d ON d.id = v.document_id
                    WHERE v.document_id = %s
                    ORDER BY v.version_no DESC, v.id DESC
                    """,
                    (document_id,),
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return [self._normalize_version_row(row) for row in rows]

    def get_version(self, document_id: int, version_id: int) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT v.id, v.document_id, v.version_no, v.change_source, v.change_summary, v.actor,
                           v.content, v.content_hash, v.file_size, v.metadata_json, v.parent_version_id,
                           v.created_at, d.current_version_id
                    FROM da_skill_document_version v
                    INNER JOIN da_skill_document d ON d.id = v.document_id
                    WHERE v.document_id = %s AND v.id = %s
                    LIMIT 1
                    """,
                    (document_id, version_id),
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_version_row(row, include_content=True) if row else None

    def delete_document_by_path(self, relative_path: str):
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "DELETE FROM da_skill_document WHERE relative_path = %s",
                    (str(relative_path or "").replace('\\', '/').strip('/'),),
                )
            conn.commit()
        finally:
            conn.close()

    def save_document(
        self,
        *,
        relative_path: str,
        content: str,
        change_source: str,
        change_summary: str | None = None,
        actor: str | None = None,
        metadata: dict[str, Any] | None = None,
        parent_version_id: int | None = None,
    ) -> dict[str, Any]:
        self._ensure_ready()
        normalized_path = str(relative_path or "").replace("\\", "/").strip("/")
        if not normalized_path:
            raise ValueError("relative_path is required")

        body = content or ""
        content_hash = _sha256(body)
        metadata_json = json.dumps(metadata, ensure_ascii=False, default=_json_default) if metadata else None

        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id, current_hash, current_version_id, version_count
                    FROM da_skill_document
                    WHERE relative_path = %s
                    FOR UPDATE
                    """,
                    (normalized_path,),
                )
                existing = cur.fetchone()

                if existing and str(existing.get("current_hash") or "") == content_hash:
                    cur.execute(
                        """
                        UPDATE da_skill_document
                        SET last_change_source = %s,
                            last_change_summary = %s,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = %s
                        """,
                        (change_source, change_summary, existing["id"]),
                    )
                    conn.commit()
                    return self.get_document(int(existing["id"])) or {}

                if existing:
                    document_id = int(existing["id"])
                    version_no = int(existing.get("version_count") or 0) + 1
                else:
                    cur.execute(
                        """
                        INSERT INTO da_skill_document (
                            relative_path, file_name, category, content_type, current_content, current_hash,
                            current_version_id, version_count, last_change_source, last_change_summary
                        ) VALUES (%s, %s, %s, %s, '', '', NULL, 0, %s, %s)
                        """,
                        (
                            normalized_path,
                            Path(normalized_path).name,
                            _category_for_path(normalized_path),
                            _content_type_for_path(normalized_path),
                            change_source,
                            change_summary,
                        ),
                    )
                    document_id = int(cur.lastrowid)
                    version_no = 1

                cur.execute(
                    """
                    INSERT INTO da_skill_document_version (
                        document_id, version_no, change_source, change_summary, actor, content,
                        content_hash, file_size, metadata_json, parent_version_id
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """,
                    (
                        document_id,
                        version_no,
                        change_source,
                        change_summary,
                        actor,
                        body,
                        content_hash,
                        len(body.encode("utf-8")),
                        metadata_json,
                        parent_version_id,
                    ),
                )
                version_id = int(cur.lastrowid)
                cur.execute(
                    """
                    UPDATE da_skill_document
                    SET file_name = %s,
                        category = %s,
                        content_type = %s,
                        current_content = %s,
                        current_hash = %s,
                        current_version_id = %s,
                        version_count = %s,
                        last_change_source = %s,
                        last_change_summary = %s,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = %s
                    """,
                    (
                        Path(normalized_path).name,
                        _category_for_path(normalized_path),
                        _content_type_for_path(normalized_path),
                        body,
                        content_hash,
                        version_id,
                        version_no,
                        change_source,
                        change_summary,
                        document_id,
                    ),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_document(document_id) or {}

    def _normalize_settings_payload(self, payload: dict[str, Any]) -> dict[str, Any]:
        data = dict(payload or {})
        normalized = {
            "provider_id": str(data.get("provider_id") or "").strip(),
            "model": str(data.get("model") or "").strip(),
            "anthropic_api_key": str(data.get("anthropic_api_key") or ""),
            "anthropic_auth_token": str(data.get("anthropic_auth_token") or ""),
            "anthropic_base_url": str(data.get("anthropic_base_url") or ""),
            "mysql_host": str(data.get("mysql_host") or ""),
            "mysql_port": int(data.get("mysql_port") or 3306),
            "mysql_user": str(data.get("mysql_user") or ""),
            "mysql_password": str(data.get("mysql_password") or ""),
            "mysql_database": str(data.get("mysql_database") or ""),
            "doris_host": str(data.get("doris_host") or ""),
            "doris_port": int(data.get("doris_port") or 9030),
            "doris_user": str(data.get("doris_user") or ""),
            "doris_password": str(data.get("doris_password") or ""),
            "doris_database": str(data.get("doris_database") or ""),
            "skills_output_dir": str(data.get("skills_output_dir") or ""),
        }
        extra_keys = {
            "validated_provider_id",
            "validated_model",
            "provider_validation_status",
            "provider_validation_message",
            "provider_validated_at",
            "provider_settings",
            "providers",
            "skill_runtime",
            "widget_allowed_sites",
        }
        for key in extra_keys:
            if key in data:
                if key in {"provider_settings", "providers"}:
                    normalized["provider_settings"] = _normalize_provider_settings_blob(data.get(key))
                elif key == "skill_runtime":
                    normalized["skill_runtime"] = _normalize_skill_runtime_blob(data.get(key))
                elif key == "widget_allowed_sites":
                    value = data.get(key)
                    normalized["widget_allowed_sites"] = value if isinstance(value, list) else []
                else:
                    normalized[key] = str(data.get(key) or "")
        return normalized

    def _normalize_settings_row(self, row: dict[str, Any] | None) -> dict[str, Any]:
        if not row:
            return {}
        normalized = {
            "provider_id": str(row.get("provider_id") or ""),
            "model": str(row.get("model_name") or ""),
            "anthropic_api_key": str(row.get("anthropic_api_key") or ""),
            "anthropic_auth_token": str(row.get("anthropic_auth_token") or ""),
            "anthropic_base_url": str(row.get("anthropic_base_url") or ""),
            "mysql_host": str(row.get("mysql_host") or ""),
            "mysql_port": int(row.get("mysql_port") or 3306),
            "mysql_user": str(row.get("mysql_user") or ""),
            "mysql_password": str(row.get("mysql_password") or ""),
            "mysql_database": str(row.get("mysql_database") or ""),
            "doris_host": str(row.get("doris_host") or ""),
            "doris_port": int(row.get("doris_port") or 9030),
            "doris_user": str(row.get("doris_user") or ""),
            "doris_password": str(row.get("doris_password") or ""),
            "doris_database": str(row.get("doris_database") or ""),
            "skills_output_dir": str(row.get("skills_output_dir") or ""),
            "updated_at": _to_iso(row.get("updated_at")),
        }
        raw_json = row.get("raw_json")
        if raw_json:
            try:
                payload = json.loads(str(raw_json))
                if isinstance(payload, dict):
                    for key in {
                        "validated_provider_id",
                        "validated_model",
                        "provider_validation_status",
                        "provider_validation_message",
                        "provider_validated_at",
                        "provider_settings",
                        "providers",
                        "skill_runtime",
                        "widget_allowed_sites",
                    }:
                        if key in payload:
                            if key in {"provider_settings", "providers"}:
                                normalized["provider_settings"] = _normalize_provider_settings_blob(payload.get(key))
                            elif key == "skill_runtime":
                                normalized["skill_runtime"] = _normalize_skill_runtime_blob(payload.get(key))
                            elif key == "widget_allowed_sites":
                                value = payload.get(key)
                                normalized["widget_allowed_sites"] = value if isinstance(value, list) else []
                            else:
                                normalized[key] = str(payload.get(key) or "")
            except Exception:
                logger.warning("Failed to parse da_agent_settings.raw_json")
        return normalized

    def _normalize_document_row(self, row: dict[str, Any] | None, *, include_content: bool = False) -> dict[str, Any]:
        if not row:
            return {}
        item = {
            "id": int(row.get("id") or 0),
            "relative_path": str(row.get("relative_path") or ""),
            "file_name": str(row.get("file_name") or ""),
            "category": str(row.get("category") or "root"),
            "content_type": str(row.get("content_type") or "text"),
            "current_hash": str(row.get("current_hash") or ""),
            "current_version_id": int(row.get("current_version_id") or 0) or None,
            "version_count": int(row.get("version_count") or 0),
            "last_change_source": str(row.get("last_change_source") or ""),
            "last_change_summary": str(row.get("last_change_summary") or ""),
            "created_at": _to_iso(row.get("created_at")),
            "updated_at": _to_iso(row.get("updated_at")),
        }
        if include_content:
            item["current_content"] = str(row.get("current_content") or "")
        return item

    def _normalize_version_row(self, row: dict[str, Any] | None, *, include_content: bool = False) -> dict[str, Any]:
        if not row:
            return {}
        metadata = None
        if row.get("metadata_json"):
            try:
                metadata = json.loads(str(row.get("metadata_json") or ""))
            except Exception:
                metadata = None
        item = {
            "id": int(row.get("id") or 0),
            "document_id": int(row.get("document_id") or 0),
            "version_no": int(row.get("version_no") or 0),
            "change_source": str(row.get("change_source") or ""),
            "change_summary": str(row.get("change_summary") or ""),
            "actor": str(row.get("actor") or ""),
            "content_hash": str(row.get("content_hash") or ""),
            "file_size": int(row.get("file_size") or 0),
            "metadata": metadata,
            "parent_version_id": int(row.get("parent_version_id") or 0) or None,
            "created_at": _to_iso(row.get("created_at")),
            "is_current": int(row.get("current_version_id") or 0) == int(row.get("id") or 0),
        }
        if include_content:
            item["content"] = str(row.get("content") or "")
        return item


_skill_admin_store = SkillAdminStore()


def get_skill_admin_store() -> SkillAdminStore:
    return _skill_admin_store
