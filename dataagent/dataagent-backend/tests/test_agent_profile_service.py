from __future__ import annotations

import sys
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core import agent_profile_service


def test_default_agent_payload_is_general_builtin_agent():
    payload = agent_profile_service.default_agent_payload()

    assert payload["agent_id"] == "agent_default"
    assert payload["name"] == "通用智能体"
    assert payload["description"] == "通用对话与分析入口，不预置 OpenDataWorks 专属 Skills。"
    assert payload["allowed_tools"] == ["Read", "LS", "Glob", "Grep"]
    assert payload["mcp_server_ids"] == []
    assert payload["skill_folders"] == []
    assert payload["is_default"] is True
    assert payload["is_builtin"] is True


def test_opendataworks_agent_payload_is_builtin_with_platform_capabilities():
    payload = agent_profile_service.opendataworks_agent_payload()

    assert payload["agent_id"] == "agent_opendataworks"
    assert payload["name"] == "OpenDataWorks助手智能体"
    assert payload["allowed_tools"] == ["Skill", "Bash", "Read", "LS", "Glob", "Grep"]
    assert payload["mcp_server_ids"] == ["portal"]
    assert payload["skill_folders"] == ["opendataworks-business-knowledge", "opendataworks-platform-tools"]
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
        },
        available_skill_folders={"opendataworks-business-knowledge", "opendataworks-platform-tools"},
        available_mcp_server_ids={"portal"},
    )

    assert payload["name"] == "质量巡检助手"
    assert payload["permission_mode"] == "bypassPermissions"
    assert payload["allowed_tools"] == ["Read", "Skill", "Grep"]
    assert payload["mcp_server_ids"] == ["portal"]
    assert payload["skill_folders"] == ["opendataworks-business-knowledge"]
    assert payload["max_turns"] == 12
    assert payload["env_vars"] == {"AGENT_SCENE": "quality"}


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
            "resolved_workdir": "/tmp/agent",
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
        "permission_mode": "default",
        "allowed_tools": ["Skill", "Read"],
        "mcp_server_ids": ["portal"],
        "skill_folders": ["opendataworks-business-knowledge"],
        "max_turns": 8,
        "env_vars": {"AGENT_SCENE": "quality"},
        "is_default": False,
        "is_builtin": False,
    }
