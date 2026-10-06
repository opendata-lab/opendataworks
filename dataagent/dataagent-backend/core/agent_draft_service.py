"""Agent workbench persistence. Published profiles remain the chat contract."""
from __future__ import annotations

import json
import logging
import uuid
from typing import Any

from core.agent_profile_service import (
    available_mcp_server_ids, build_agent_snapshot, get_agent_profile,
    get_agent_profile_store, list_agent_profiles, normalize_agent_profile_payload,
)

logger = logging.getLogger(__name__)


class DraftConflict(ValueError):
    pass


def configuration(profile: dict[str, Any]) -> dict[str, Any]:
    keys = ("agent_id", "name", "description", "system_prompt", "allowed_tools",
            "mcp_server_ids", "skill_folders", "max_turns", "env_vars", "data_scope",
            "visibility", "preset_questions", "is_default", "is_builtin")
    return {key: profile.get(key, [] if key == "preset_questions" else None) for key in keys}


def encode(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def check_revision(row: dict, revision: int) -> None:
    if int(row["revision"]) != revision:
        raise DraftConflict("草稿已被其他编辑更新，请刷新后重试")


def publishable(row: dict) -> bool:
    return bool(
        row.get("preview_task_id")
        and row.get("preview_revision") == row.get("revision")
        and row.get("preview_status") == "finished"
        and row.get("latest_task_id") == row.get("preview_task_id")
        and row.get("is_agent_preview")
        and build_agent_snapshot(json.loads(row["draft_json"]))
        == build_agent_snapshot(json.loads(row.get("preview_snapshot") or "{}"))
        and row.get("published_json") != row["draft_json"]
    )


class AgentDraftStore:
    def _connect(self):
        store = get_agent_profile_store()
        return store._connect(store._schema_name())

    def _read(self, cur, agent_id: str, *, lock: bool = False) -> dict:
        # Lock only the draft row. Task success is terminal; newest-task checks
        # are read after the lock using the same transaction.
        cur.execute("SELECT * FROM da_agent_draft WHERE agent_id = %s" + (" FOR UPDATE" if lock else ""), (agent_id,))
        row = cur.fetchone()
        if not row:
            raise ValueError("agent not found")
        if row.get("preview_task_id"):
            cur.execute("""SELECT task.task_status AS preview_status,
                                  topic.current_task_id AS latest_task_id,
                                  topic.is_agent_preview,
                                  task.agent_snapshot_json AS preview_snapshot,
                                  task.topic_id AS preview_topic_id
                           FROM da_agent_task task JOIN da_agent_topic topic ON topic.topic_id = task.topic_id
                           WHERE task.task_id = %s""", (row["preview_task_id"],))
            row.update(cur.fetchone() or {})
        return row

    def _result(self, row: dict) -> dict:
        return {**json.loads(row["draft_json"]), "revision": int(row["revision"]),
                "published_version": int(row["published_version"]),
                "has_changes": row.get("published_json") != row["draft_json"],
                "preview_task_id": row.get("preview_task_id"),
                "preview_topic_id": row.get("preview_topic_id"),
                "preview_status": row.get("preview_status"),
                "can_publish": publishable(row)}

    def ensure(self, agent_id: str) -> None:
        profile = get_agent_profile(agent_id)
        if not profile:
            return
        blob = encode(configuration(profile))
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute("""INSERT IGNORE INTO da_agent_draft
                               (agent_id, draft_json, published_json, published_version)
                               VALUES (%s, %s, %s, 1)""", (agent_id, blob, blob))
            conn.commit()
        finally:
            conn.close()

    def get(self, agent_id: str) -> dict:
        self.ensure(agent_id)
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                return self._result(self._read(cur, agent_id))
        finally:
            conn.close()

    def list(self) -> list[dict]:
        for profile in list_agent_profiles():
            self.ensure(profile["agent_id"])
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT agent_id FROM da_agent_draft ORDER BY updated_at DESC")
                ids = [row["agent_id"] for row in cur.fetchall()]
                # List summaries deliberately omit prompt, env and allow-lists.
                result = []
                for agent_id in ids:
                    item = self._result(self._read(cur, agent_id))
                    result.append({key: item[key] for key in (
                        "agent_id", "name", "description", "allowed_tools", "mcp_server_ids",
                        "skill_folders", "data_scope", "is_default", "is_builtin",
                        "revision", "published_version", "has_changes")} |
                        {"visibility_mode": (item.get("visibility") or {}).get("mode", "all")})
                return result
        finally:
            conn.close()

    def create(self, payload: dict, skills: set[str]) -> dict:
        profile = normalize_agent_profile_payload(payload, available_skill_folders=skills,
                                                  available_mcp_server_ids=available_mcp_server_ids())
        profile.update(agent_id=f"agent_{uuid.uuid4().hex[:24]}", is_default=False, is_builtin=False)
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute("INSERT INTO da_agent_draft (agent_id, draft_json) VALUES (%s, %s)",
                            (profile["agent_id"], encode(configuration(profile))))
            conn.commit()
        finally:
            conn.close()
        return self.get(profile["agent_id"])

    def save(self, agent_id: str, payload: dict, revision: int, skills: set[str]) -> dict:
        self.ensure(agent_id)
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                row = self._read(cur, agent_id, lock=True)
                check_revision(row, revision)
                existing = json.loads(row["draft_json"])
                profile = normalize_agent_profile_payload(payload, existing=existing,
                    available_skill_folders=skills, available_mcp_server_ids=available_mcp_server_ids())
                profile.update({key: existing[key] for key in ("agent_id", "is_default", "is_builtin")})
                blob = encode(configuration(profile))
                if blob != row["draft_json"]:
                    cur.execute("""UPDATE da_agent_draft SET draft_json = %s, revision = revision + 1,
                                   preview_task_id = NULL, preview_revision = NULL WHERE agent_id = %s""",
                                (blob, agent_id))
            conn.commit()
        finally:
            conn.close()
        return self.get(agent_id)

    def reserve_preview(self, agent_id: str, revision: int, topic: dict) -> str:
        """Invalidate prior approval before dispatch, serialized with publish."""
        token = uuid.uuid4().hex
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                row = self._read(cur, agent_id, lock=True)
                check_revision(row, revision)
                if topic.get("agent_snapshot") != build_agent_snapshot(json.loads(row["draft_json"])):
                    raise DraftConflict("草稿已更新，请开始新的调试对话")
                if topic.get("current_task_status") in {"waiting", "running", "waiting_input", "waiting_permission"}:
                    raise DraftConflict("请等待当前调试完成，或停止后重试")
                cur.execute("""UPDATE da_agent_draft SET preview_task_id = NULL,
                               preview_revision = %s, preview_token = %s WHERE agent_id = %s""",
                            (revision, token, agent_id))
            conn.commit()
            return token
        finally:
            conn.close()

    def register_preview(self, agent_id: str, revision: int, task_id: str, token: str) -> None:
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute("""UPDATE da_agent_draft SET preview_task_id = %s, preview_revision = %s
                               WHERE agent_id = %s AND revision = %s AND preview_token = %s""",
                            (task_id, revision, agent_id, revision, token))
                if not cur.rowcount:
                    raise DraftConflict("草稿已更新，本次调试不能用于发布")
            conn.commit()
        finally:
            conn.close()

    def publish(self, agent_id: str, revision: int) -> dict:
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                row = self._read(cur, agent_id, lock=True)
                check_revision(row, revision)
                if not publishable(row):
                    raise DraftConflict("请先完成当前草稿的调试，再确认发布")
                get_agent_profile_store().write_profile(cur, json.loads(row["draft_json"]))
                cur.execute("""UPDATE da_agent_draft SET published_json = draft_json,
                               published_version = published_version + 1 WHERE agent_id = %s""", (agent_id,))
            conn.commit()
        finally:
            conn.close()
        return self.get(agent_id)

    def delete(self, agent_id: str) -> bool:
        self.ensure(agent_id)
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                row = self._read(cur, agent_id, lock=True)
                profile = json.loads(row["draft_json"])
                if profile.get("is_builtin") or profile.get("is_default"):
                    raise ValueError("built-in agent cannot be deleted")
                cur.execute("SELECT COUNT(*) AS total FROM da_agent_topic WHERE agent_id = %s AND is_agent_preview = 0", (agent_id,))
                if int((cur.fetchone() or {}).get("total") or 0):
                    raise ValueError("agent is referenced by topics")
                cur.execute("SELECT topic_id, current_task_status FROM da_agent_topic WHERE agent_id = %s AND is_agent_preview = 1", (agent_id,))
                previews = cur.fetchall()
                if any(topic.get("current_task_status") in {"waiting", "running", "waiting_input", "waiting_permission"} for topic in previews):
                    raise DraftConflict("请先停止正在运行的调试，再删除智能体")
                cur.execute("DELETE FROM da_agent_topic WHERE agent_id = %s AND is_agent_preview = 1", (agent_id,))
                cur.execute("DELETE FROM da_agent_profile WHERE agent_id = %s", (agent_id,))
                cur.execute("DELETE FROM da_agent_draft WHERE agent_id = %s", (agent_id,))
            conn.commit()
            from core.topic_workspace import delete_topic_workspace
            for topic in previews:
                try:
                    delete_topic_workspace(topic["topic_id"])
                except Exception:
                    logger.warning("Preview workspace cleanup failed topic_id=%s", topic["topic_id"], exc_info=True)
            return True
        finally:
            conn.close()


agent_drafts = AgentDraftStore()
