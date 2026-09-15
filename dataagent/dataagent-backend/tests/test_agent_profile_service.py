from __future__ import annotations

import sys
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from config import get_settings, update_settings
from core import agent_profile_service


class _RecordingProfileStore:
    """Counts the store traffic a profile read triggers."""

    def __init__(self):
        self.calls: list[str] = []
        self.profiles = {
            agent_profile_service.DEFAULT_AGENT_ID: {
                "agent_id": agent_profile_service.DEFAULT_AGENT_ID,
                "name": "default",
                "is_default": True,
            },
            agent_profile_service.OPENDATAWORKS_AGENT_ID: {
                "agent_id": agent_profile_service.OPENDATAWORKS_AGENT_ID,
                "name": "opendataworks",
            },
            agent_profile_service.ONTOLOGY_MODELING_AGENT_ID: {
                "agent_id": agent_profile_service.ONTOLOGY_MODELING_AGENT_ID,
                "name": "ontology",
            },
        }

    def init_schema(self):
        self.calls.append("init_schema")

    def get_profile(self, agent_id):
        self.calls.append(f"get_profile:{agent_id}")
        return self.profiles.get(agent_id)

    def save_profile(self, payload):
        self.calls.append("save_profile")
        self.profiles[payload["agent_id"]] = payload
        return payload

    def list_profiles(self):
        self.calls.append("list_profiles")
        return list(self.profiles.values())

    def backfill_default_bindings(self, default_snapshot):
        self.calls.append("backfill_default_bindings")


def test_listing_agents_does_not_reseed_builtins_on_every_read(monkeypatch):
    """The welcome page paid the built-in seed plus a full-table backfill per read.

    `bootstrap_default_agent_profile()` ran on every `list_agent_profiles()` and
    every `get_agent_profile()`: three profile SELECTs plus
    `backfill_default_bindings()`, which issues two
    `UPDATE ... WHERE agent_id IS NULL OR ...` statements against
    `da_agent_topic` and `da_agent_task` and commits. That is one-time migration
    work sitting on a hot read path, and it only gets more expensive as those
    tables grow.
    """
    store = _RecordingProfileStore()
    monkeypatch.setattr(agent_profile_service, "get_agent_profile_store", lambda: store)
    agent_profile_service.reset_builtin_agent_seed()

    agent_profile_service.list_agent_profiles()
    seeding_calls = [call for call in store.calls if call != "list_profiles"]
    assert "backfill_default_bindings" in seeding_calls, "first call still seeds"

    store.calls.clear()

    # Steady state: five more reads, no reseeding and no backfill.
    for _ in range(5):
        agent_profile_service.list_agent_profiles()
        agent_profile_service.get_agent_profile(agent_profile_service.DEFAULT_AGENT_ID)

    assert "backfill_default_bindings" not in store.calls
    assert "save_profile" not in store.calls
    assert "init_schema" not in store.calls
    assert store.calls.count(f"get_profile:{agent_profile_service.OPENDATAWORKS_AGENT_ID}") == 0
    # Each read still hits the store for live data, and nothing more.
    assert store.calls.count("list_profiles") == 5
    assert store.calls.count(f"get_profile:{agent_profile_service.DEFAULT_AGENT_ID}") == 5


def test_reset_builtin_agent_seed_allows_reseeding(monkeypatch):
    store = _RecordingProfileStore()
    monkeypatch.setattr(agent_profile_service, "get_agent_profile_store", lambda: store)
    agent_profile_service.reset_builtin_agent_seed()

    agent_profile_service.list_agent_profiles()
    assert "backfill_default_bindings" in store.calls

    store.calls.clear()
    agent_profile_service.reset_builtin_agent_seed()
    agent_profile_service.list_agent_profiles()

    assert "backfill_default_bindings" in store.calls


def test_bootstrap_default_agent_profile_still_returns_default(monkeypatch):
    store = _RecordingProfileStore()
    monkeypatch.setattr(agent_profile_service, "get_agent_profile_store", lambda: store)
    agent_profile_service.reset_builtin_agent_seed()

    profile = agent_profile_service.bootstrap_default_agent_profile()

    assert profile["agent_id"] == agent_profile_service.DEFAULT_AGENT_ID
    # Still current after the seed is skipped, rather than a cached snapshot.
    store.profiles[agent_profile_service.DEFAULT_AGENT_ID]["name"] = "renamed"
    assert agent_profile_service.bootstrap_default_agent_profile()["name"] == "renamed"


def test_default_agent_payload_is_general_builtin_agent():
    payload = agent_profile_service.default_agent_payload()

    assert payload["agent_id"] == "agent_default"
    assert payload["name"] == "默认助手"
    assert payload["description"] == "通用对话与分析入口，不预置 OpenDataWorks 专属 Skills。"
    assert payload["allowed_tools"] == ["Read", "LS", "Glob", "Grep"]
    assert payload["mcp_server_ids"] == []
    assert payload["skill_folders"] == []
    assert payload["is_default"] is True
    assert payload["is_builtin"] is True


def test_opendataworks_agent_payload_is_builtin_with_platform_capabilities():
    payload = agent_profile_service.opendataworks_agent_payload()

    assert payload["agent_id"] == "agent_opendataworks"
    assert payload["name"] == "OpenDataWorks平台助手"
    assert payload["allowed_tools"] == ["Skill", "Bash", "Read", "LS", "Glob", "Grep"]
    assert payload["mcp_server_ids"] == ["portal"]
    assert payload["skill_folders"] == [
        "opendataworks-business-knowledge",
        "opendataworks-platform-tools",
        "opendataworks-data-dev",
    ]
    assert payload["is_default"] is False
    assert payload["is_builtin"] is True


def test_ontology_modeling_agent_payload_is_builtin_with_modeling_skill():
    payload = agent_profile_service.ontology_modeling_agent_payload()

    assert payload["agent_id"] == "agent_ontology_modeling"
    assert payload["name"] == "本体建模助手"
    assert payload["allowed_tools"] == ["Skill", "Bash", "Read", "LS", "Glob", "Grep"]
    assert payload["mcp_server_ids"] == ["portal"]
    assert payload["skill_folders"] == ["ontology-modeling-assistant"]
    assert "本体" in payload["description"]
    assert payload["is_default"] is False
    assert payload["is_builtin"] is True


def test_normalize_agent_profile_payload_accepts_scoped_runtime_config():
    payload = agent_profile_service.normalize_agent_profile_payload(
        {
            "name": "质量巡检助手",
            "description": "只处理数据质量规则和巡检结果分析。",
            "system_prompt": "你是数据质量巡检场景的智能体。",
            "permission_mode": "bypassPermissions",
            "allowed_tools": ["Read", "Skill", "Read", "Grep"],
            "mcp_server_ids": ["portal"],
            "skill_folders": ["opendataworks-business-knowledge"],
            "max_turns": 12,
            "env_vars": {"AGENT_SCENE": "quality"},
            "data_scope": {
                "allowed_scopes": [
                    {"cluster_id": 3, "source_type": "DORIS", "database": "ads_user"},
                    {"cluster_id": 3, "source_type": "DORIS", "database": "ads_user"},
                    {"cluster_id": None, "source_type": "MYSQL", "database": "opendataworks"},
                ]
            },
        },
        available_skill_folders={"opendataworks-business-knowledge", "opendataworks-platform-tools"},
        available_mcp_server_ids={"portal"},
    )

    assert payload["name"] == "质量巡检助手"
    assert "permission_mode" not in payload
    assert payload["allowed_tools"] == ["Read", "Skill", "Grep"]
    assert payload["mcp_server_ids"] == ["portal"]
    assert payload["skill_folders"] == ["opendataworks-business-knowledge"]
    assert payload["max_turns"] == 12
    assert payload["env_vars"] == {"AGENT_SCENE": "quality"}
    assert payload["data_scope"] == {
        "allowed_scopes": [
            {"cluster_id": 3, "source_type": "DORIS", "database": "ads_user"},
            {"cluster_id": None, "source_type": "MYSQL", "database": "opendataworks"},
        ]
    }


def test_normalize_agent_profile_payload_defaults_empty_data_scope_to_deny_all():
    payload = agent_profile_service.normalize_agent_profile_payload(
        {"name": "无数据授权智能体"},
        available_skill_folders=set(),
        available_mcp_server_ids=set(),
    )

    assert payload["data_scope"] == {"allowed_scopes": []}


def test_normalize_agent_profile_payload_rejects_reserved_environment_keys():
    with pytest.raises(ValueError, match="reserved environment variable"):
        agent_profile_service.normalize_agent_profile_payload(
            {
                "name": "危险配置",
                "env_vars": {"DATAAGENT_TOKEN": "bad"},
            },
            available_skill_folders=set(),
            available_mcp_server_ids=set(),
        )


def test_normalize_agent_profile_payload_defaults_visibility_to_all():
    payload = agent_profile_service.normalize_agent_profile_payload(
        {"name": "默认可见性智能体"},
        available_skill_folders=set(),
        available_mcp_server_ids=set(),
    )

    assert payload["visibility"] == {"mode": "all", "allowed_users": [], "allowed_groups": []}


def test_normalize_agent_profile_payload_accepts_visibility_scope():
    payload = agent_profile_service.normalize_agent_profile_payload(
        {
            "name": "受限智能体",
            "visibility": {
                "mode": "selected",
                "allowed_users": ["SSO:42", "SSO:42", " local:alice "],
            },
        },
        available_skill_folders=set(),
        available_mcp_server_ids=set(),
    )

    assert payload["visibility"] == {
        "mode": "selected",
        "allowed_users": ["SSO:42", "local:alice"],
        "allowed_groups": [],
    }


def test_normalize_agent_profile_payload_preserves_existing_visibility_on_partial_update():
    existing = {
        "name": "受限智能体",
        "visibility": {"mode": "authenticated", "allowed_users": [], "allowed_groups": []},
    }
    payload = agent_profile_service.normalize_agent_profile_payload(
        {"description": "只改描述"},
        existing=existing,
        available_skill_folders=set(),
        available_mcp_server_ids=set(),
    )

    assert payload["visibility"]["mode"] == "authenticated"


def test_normalize_agent_profile_payload_rejects_invalid_visibility_mode():
    with pytest.raises(ValueError, match="invalid visibility mode"):
        agent_profile_service.normalize_agent_profile_payload(
            {"name": "非法可见性", "visibility": {"mode": "vip-only"}},
            available_skill_folders=set(),
            available_mcp_server_ids=set(),
        )


def test_build_agent_snapshot_excludes_visibility():
    snapshot = agent_profile_service.build_agent_snapshot(
        {
            "agent_id": "agent_scoped",
            "name": "受限智能体",
            "visibility": {"mode": "selected", "allowed_users": ["SSO:42"], "allowed_groups": []},
        }
    )

    # 快照供运行时消费，可见性只在实时 profile 上强制，避免话题携带过期副本。
    assert "visibility" not in snapshot


def test_build_agent_snapshot_keeps_runtime_fields_without_timestamps():
    snapshot = agent_profile_service.build_agent_snapshot(
        {
            "agent_id": "agent_quality",
            "name": "质量巡检助手",
            "description": "只处理数据质量规则和巡检结果分析。",
            "system_prompt": "你是数据质量巡检场景的智能体。",
            "permission_mode": "default",
            "allowed_tools": ["Skill", "Read"],
            "mcp_server_ids": ["portal"],
            "skill_folders": ["opendataworks-business-knowledge"],
            "max_turns": 8,
            "env_vars": {"AGENT_SCENE": "quality"},
            "data_scope": {
                "allowed_scopes": [
                    {"cluster_id": 3, "source_type": "DORIS", "database": "ads_user"},
                ]
            },
            "is_default": False,
            "is_builtin": False,
            "created_at": "2026-05-21T10:00:00",
            "updated_at": "2026-05-21T11:00:00",
        }
    )

    assert snapshot == {
        "agent_id": "agent_quality",
        "name": "质量巡检助手",
        "description": "只处理数据质量规则和巡检结果分析。",
        "system_prompt": "你是数据质量巡检场景的智能体。",
        "allowed_tools": ["Skill", "Read"],
        "mcp_server_ids": ["portal"],
        "skill_folders": ["opendataworks-business-knowledge"],
        "max_turns": 8,
        "env_vars": {"AGENT_SCENE": "quality"},
        "data_scope": {
            "allowed_scopes": [
                {"cluster_id": 3, "source_type": "DORIS", "database": "ads_user"},
            ]
        },
        "is_default": False,
        "is_builtin": False,
    }
