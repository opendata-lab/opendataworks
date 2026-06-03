from __future__ import annotations

import hashlib
import io
import sys
import types
import zipfile
from pathlib import Path

import anyio
import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core import skill_admin_service
from core.skill_admin_service import (
    _merge_provider_settings,
    _merge_settings_payload,
    _normalize_widget_allowed_sites,
)


BUSINESS_SKILL = "opendataworks-business-knowledge"
PLATFORM_TOOLS_SKILL = "opendataworks-platform-tools"
ONTOLOGY_MODELING_SKILL = "ontology-modeling-assistant"
LEGACY_SQL_SKILL = "dataagent-nl2sql"


class FakeSkillStore:
    def __init__(self):
        self.documents: dict[str, dict] = {}
        self.next_id = 1

    def list_documents(self):
        return list(self.documents.values())

    def get_document_by_path(self, relative_path):
        return self.documents.get(str(relative_path or "").replace("\\", "/").strip("/"))

    def save_document(
        self,
        *,
        relative_path,
        content,
        change_source,
        change_summary=None,
        actor=None,
        metadata=None,
        parent_version_id=None,
    ):
        normalized_path = str(relative_path or "").replace("\\", "/").strip("/")
        existing = self.documents.get(normalized_path)
        document_id = existing["id"] if existing else self.next_id
        if not existing:
            self.next_id += 1
        version_count = int(existing.get("version_count") or 0) + 1 if existing else 1
        document = {
            "id": document_id,
            "relative_path": normalized_path,
            "file_name": Path(normalized_path).name,
            "category": "root" if "/" not in normalized_path else normalized_path.split("/")[1],
            "content_type": "markdown",
            "current_content": content,
            "current_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
            "current_version_id": version_count,
            "version_count": version_count,
            "last_change_source": change_source,
            "last_change_summary": change_summary or "",
            "created_at": "2026-04-17T10:00:00",
            "updated_at": "2026-04-17T10:00:00",
        }
        self.documents[normalized_path] = document
        return dict(document)

    def delete_document_by_path(self, relative_path):
        self.documents.pop(str(relative_path or "").replace("\\", "/").strip("/"), None)

    def rename_document_path(self, old_relative_path, new_relative_path):
        old_path = str(old_relative_path or "").replace("\\", "/").strip("/")
        new_path = str(new_relative_path or "").replace("\\", "/").strip("/")
        document = self.documents.pop(old_path, None)
        if document:
            document["relative_path"] = new_path
            document["file_name"] = Path(new_path).name
            self.documents[new_path] = document


def make_zip(entries: dict[str, str]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for path, content in entries.items():
            archive.writestr(path, content)
    return buffer.getvalue()


def configure_skill_filesystem(monkeypatch, tmp_path, store=None, *, settings=None):
    discovery_root = tmp_path / ".claude" / "skills"
    discovery_root.mkdir(parents=True)
    fake_store = store or FakeSkillStore()
    persisted = {}

    monkeypatch.setattr(skill_admin_service, "resolve_skill_discovery_root_dir", lambda: discovery_root)
    monkeypatch.setattr(skill_admin_service, "resolve_skills_root_dir", lambda: discovery_root / BUSINESS_SKILL)
    monkeypatch.setattr(skill_admin_service, "get_skill_admin_store", lambda: fake_store)
    monkeypatch.setattr(
        skill_admin_service,
        "current_settings_payload",
        lambda: settings
        or {
            "skills_output_dir": f"../.claude/skills/{BUSINESS_SKILL}",
            "skill_runtime": {
                BUSINESS_SKILL: {"enabled": True},
                PLATFORM_TOOLS_SKILL: {"enabled": True},
            },
        },
    )
    monkeypatch.setattr(skill_admin_service, "persist_admin_settings", lambda payload: persisted.update(payload) or payload)
    return discovery_root, fake_store, persisted


def test_merge_provider_settings_can_reenable_provider_with_models():
    current = {
        "anyrouter": {
            "provider_id": "anyrouter",
            "auth_token": "existing-token",
            "base_url": "https://a-ocnfniawgw.cn-shanghai.fcapp.run",
            "provider_enabled": True,
            "enabled_models": [],
            "custom_models": [],
            "model_detections": {},
            "enabled": False,
            "validation_status": "unverified",
            "validation_message": "请启用至少一个模型",
        }
    }
    patch = {
        "anyrouter": {
            "provider_id": "anyrouter",
            "provider_enabled": True,
            "auth_token": "existing-token",
            "base_url": "https://a-ocnfniawgw.cn-shanghai.fcapp.run",
            "enabled_models": ["claude-opus-4-6"],
            "custom_models": [],
            "model_detections": {
                "claude-opus-4-6": {
                    "status": "verified",
                    "message": "模型检测通过",
                    "checked_at": "2026-04-17T10:00:00",
                }
            },
        }
    }

    merged = _merge_provider_settings(
        current,
        patch,
        legacy_payload={"provider_id": "anyrouter", "model": "claude-opus-4-6"},
    )

    provider = merged["anyrouter"]
    assert provider["enabled_models"] == ["claude-opus-4-6"]
    assert provider["validation_status"] == "verified"
    assert provider["enabled"] is True


def test_merge_provider_settings_preserves_partial_capability_flag():
    merged = _merge_provider_settings(
        {
            "anthropic_compatible": {
                "provider_id": "anthropic_compatible",
                "auth_token": "relay-token",
                "base_url": "https://relay.example.invalid",
                "provider_enabled": True,
                "enabled_models": ["claude-sonnet-4.5"],
                "model_detections": {
                    "claude-sonnet-4.5": {
                        "status": "verified",
                        "message": "模型检测通过",
                        "checked_at": "2026-04-17T10:00:00",
                    }
                },
                "supports_partial_messages": True,
            }
        },
        {
            "anthropic_compatible": {
                "provider_id": "anthropic_compatible",
                "supports_partial_messages": False,
            }
        },
    )

    provider = merged["anthropic_compatible"]
    assert provider["supports_partial_messages"] is False
    assert provider["validation_status"] == "verified"
    assert provider["enabled"] is True


def test_merge_provider_settings_allows_enabled_model_without_detection():
    merged = _merge_provider_settings(
        {},
        {
            "openrouter": {
                "provider_id": "openrouter",
                "provider_enabled": True,
                "auth_token": "token",
                "base_url": "https://openrouter.ai/api",
                "enabled_models": ["anthropic/claude-sonnet-4.5"],
                "model_detections": {},
            }
        },
    )

    provider = merged["openrouter"]
    assert provider["enabled_models"] == ["anthropic/claude-sonnet-4.5"]
    assert provider["validation_status"] == "verified"
    assert provider["enabled"] is True


def test_normalize_widget_allowed_sites_from_json_string():
    sites = _normalize_widget_allowed_sites(
        '[{"website_id":"demo","allowed_origins":["https://a.com","https://a.com"],"project_name":"Demo","project_color":"#4A90A4"}]'
    )

    assert sites == [
        {
            "website_id": "demo",
            "allowed_origins": ["https://a.com"],
            "project_name": "Demo",
            "project_color": "#4A90A4",
        }
    ]


def test_normalize_widget_allowed_sites_drops_invalid_and_duplicate_entries():
    sites = _normalize_widget_allowed_sites(
        [
            {"website_id": "  ", "allowed_origins": ["x"]},
            {"website_id": "dup"},
            {"website_id": "dup"},
            "not-a-dict",
            {"allowed_origins": ["y"]},
        ]
    )

    assert [item["website_id"] for item in sites] == ["dup"]
    assert sites[0]["allowed_origins"] == []


def test_merge_settings_payload_carries_widget_allowed_sites_from_patch():
    merged = _merge_settings_payload(
        {
            "skills_output_dir": f"../.claude/skills/{BUSINESS_SKILL}",
            "widget_allowed_sites": [{"website_id": "old", "allowed_origins": []}],
        },
        {
            "widget_allowed_sites": [
                {"website_id": "new", "allowed_origins": ["https://b.com"], "project_color": "#000"}
            ]
        },
    )

    assert merged["widget_allowed_sites"] == [
        {
            "website_id": "new",
            "allowed_origins": ["https://b.com"],
            "project_name": "",
            "project_color": "#000",
        }
    ]


def test_merge_settings_payload_preserves_widget_allowed_sites_without_patch():
    merged = _merge_settings_payload(
        {
            "skills_output_dir": f"../.claude/skills/{BUSINESS_SKILL}",
            "widget_allowed_sites": [{"website_id": "keep", "allowed_origins": ["*"]}],
        },
        {},
    )

    assert merged["widget_allowed_sites"] == [
        {
            "website_id": "keep",
            "allowed_origins": ["*"],
            "project_name": "",
            "project_color": "",
        }
    ]


def test_merge_settings_payload_allows_clearing_widget_allowed_sites():
    merged = _merge_settings_payload(
        {
            "skills_output_dir": f"../.claude/skills/{BUSINESS_SKILL}",
            "widget_allowed_sites": [{"website_id": "keep", "allowed_origins": ["*"]}],
        },
        {"widget_allowed_sites": []},
    )

    assert merged["widget_allowed_sites"] == []


def test_merge_settings_defaults_to_current_bundled_skills_when_runtime_missing():
    merged = _merge_settings_payload(
        {"skills_output_dir": f"../.claude/skills/{BUSINESS_SKILL}"},
        {},
    )

    assert LEGACY_SQL_SKILL not in merged["skill_runtime"]
    assert merged["skill_runtime"][BUSINESS_SKILL]["enabled"] is True
    assert merged["skill_runtime"][PLATFORM_TOOLS_SKILL]["enabled"] is True


def test_merge_settings_migrates_legacy_sql_skill_to_current_bundled_skills():
    merged = _merge_settings_payload(
        {
            "skills_output_dir": f"../.claude/skills/{LEGACY_SQL_SKILL}",
            "skill_runtime": {LEGACY_SQL_SKILL: {"enabled": True}},
        },
        {},
    )

    assert merged["skills_output_dir"] == f"../.claude/skills/{BUSINESS_SKILL}"
    assert LEGACY_SQL_SKILL not in merged["skill_runtime"]
    assert merged["skill_runtime"][BUSINESS_SKILL]["enabled"] is True
    assert merged["skill_runtime"][PLATFORM_TOOLS_SKILL]["enabled"] is True


def test_merge_settings_preserves_explicit_bundled_skill_disabled():
    merged = _merge_settings_payload(
        {
            "skills_output_dir": f"../.claude/skills/{BUSINESS_SKILL}",
            "skill_runtime": {
                BUSINESS_SKILL: {"enabled": True},
                PLATFORM_TOOLS_SKILL: {"enabled": False},
            },
        },
        {},
    )

    assert LEGACY_SQL_SKILL not in merged["skill_runtime"]
    assert merged["skill_runtime"][BUSINESS_SKILL]["enabled"] is True
    assert merged["skill_runtime"][PLATFORM_TOOLS_SKILL]["enabled"] is False


def test_merge_settings_payload_keeps_provider_and_model_empty_without_enabled_provider():
    merged = _merge_settings_payload(
        {
            "provider_id": "",
            "model": "",
            "skills_output_dir": f"../.claude/skills/{BUSINESS_SKILL}",
            "session_mysql_database": "dataagent",
        },
        {},
    )

    assert merged["provider_id"] == ""
    assert merged["model"] == ""
    assert merged["validated_provider_id"] == ""
    assert merged["validated_model"] == ""


def test_bootstrap_admin_settings_persists_blank_provider_and_model(monkeypatch):
    captured = {}

    class FakeStore:
        def init_schema(self):
            return None

        def load_settings_record(self):
            return None

        def save_settings_record(self, payload):
            captured["saved"] = dict(payload)
            return dict(payload)

    monkeypatch.setattr(skill_admin_service, "get_skill_admin_store", lambda: FakeStore())
    monkeypatch.setattr(
        skill_admin_service,
        "get_settings",
        lambda: types.SimpleNamespace(
            llm_provider="",
            claude_model="",
            anthropic_api_key="",
            anthropic_auth_token="",
            anthropic_base_url="",
            mysql_host="",
            mysql_port=3306,
            mysql_user="",
            mysql_password="",
            mysql_database="",
            doris_host="",
            doris_port=9030,
            doris_user="",
            doris_password="",
            doris_database="",
            skills_output_dir=f"../.claude/skills/{BUSINESS_SKILL}",
            session_mysql_database="dataagent",
        ),
    )
    monkeypatch.setattr(skill_admin_service, "update_settings", lambda patch: patch)

    resolved = skill_admin_service.bootstrap_admin_settings()

    assert captured["saved"]["provider_id"] == ""
    assert captured["saved"]["model"] == ""
    assert resolved["provider_id"] == ""
    assert resolved["model"] == ""


def test_resolve_runtime_provider_selection_returns_partial_capability(monkeypatch):
    monkeypatch.setattr(
        skill_admin_service,
        "current_settings_payload",
        lambda: {
            "provider_id": "anthropic_compatible",
            "model": "claude-sonnet-4.5",
            "provider_settings": {
                "anthropic_compatible": {
                    "provider_id": "anthropic_compatible",
                    "provider_enabled": True,
                    "auth_token": "relay-token",
                    "base_url": "https://relay.example.invalid",
                    "enabled_models": ["claude-sonnet-4.5"],
                    "model_detections": {},
                    "supports_partial_messages": False,
                }
            },
        },
    )

    resolved = skill_admin_service.resolve_runtime_provider_selection("anthropic_compatible", "claude-sonnet-4.5")
    assert resolved["provider_id"] == "anthropic_compatible"
    assert resolved["model"] == "claude-sonnet-4.5"
    assert resolved["supports_partial_messages"] is False


def test_resolve_runtime_provider_selection_requires_enabled_provider(monkeypatch):
    monkeypatch.setattr(
        skill_admin_service,
        "current_settings_payload",
        lambda: {
            "provider_id": "",
            "model": "",
            "provider_settings": {},
        },
    )

    try:
        skill_admin_service.resolve_runtime_provider_selection(None, None)
    except ValueError as exc:
        assert str(exc) == "尚未配置可用大模型供应商"
    else:
        raise AssertionError("expected ValueError")


def test_detect_model_availability_returns_verified_detection(monkeypatch):
    captured = {}

    class FakeOptions:
        def __init__(self, **kwargs):
            self.kwargs = kwargs

    async def fake_query(prompt, options):
        captured["prompt"] = prompt
        captured["options"] = options.kwargs
        yield types.SimpleNamespace(subtype="")

    monkeypatch.setitem(
        sys.modules,
        "claude_agent_sdk",
        types.SimpleNamespace(ClaudeAgentOptions=FakeOptions, query=fake_query),
    )
    monkeypatch.setattr(skill_admin_service, "resolve_agent_project_cwd", lambda: Path("/tmp"))
    monkeypatch.setattr(
        skill_admin_service,
        "current_settings_payload",
        lambda: {
            "provider_id": "openrouter",
            "model": "",
            "provider_settings": {
                "openrouter": {
                    "provider_id": "openrouter",
                    "provider_enabled": True,
                    "auth_token": "saved-token",
                    "base_url": "https://openrouter.ai/api",
                    "enabled_models": [],
                    "custom_models": [],
                    "model_detections": {},
                }
            },
        },
    )

    result = anyio.run(
        skill_admin_service.detect_model_availability,
        {
            "provider_id": "openrouter",
            "model": "anthropic/claude-sonnet-4.5",
        },
    )

    assert result["status"] == "verified"
    assert captured["options"]["model"] == "anthropic/claude-sonnet-4.5"


def test_detect_model_availability_returns_failed_without_token(monkeypatch):
    monkeypatch.setattr(
        skill_admin_service,
        "current_settings_payload",
        lambda: {
            "provider_id": "openrouter",
            "model": "",
            "provider_settings": {
                "openrouter": {
                    "provider_id": "openrouter",
                    "provider_enabled": True,
                    "auth_token": "",
                    "base_url": "https://openrouter.ai/api",
                    "enabled_models": [],
                    "custom_models": [],
                    "model_detections": {},
                }
            },
        },
    )

    result = anyio.run(
        skill_admin_service.detect_model_availability,
        {
            "provider_id": "openrouter",
            "model": "anthropic/claude-sonnet-4.5",
        },
    )

    assert result["status"] == "failed"
    assert "Token" in result["message"]


def test_list_documents_enriches_skill_fields(monkeypatch):
    class FakeStore:
        def list_documents(self):
            return [
                {
                    "id": 1,
                    "relative_path": "opendataworks-platform-tools/reference/40-runtime-metadata.md",
                    "file_name": "40-runtime-metadata.md",
                    "category": "reference",
                    "content_type": "markdown",
                    "current_hash": "hash",
                    "current_version_id": 2,
                    "version_count": 2,
                    "last_change_source": "sync",
                    "last_change_summary": "manual sync",
                    "created_at": "2026-03-06T10:00:00",
                    "updated_at": "2026-03-06T12:00:00",
                }
            ]

    monkeypatch.setattr(skill_admin_service, "reindex_documents_from_disk", lambda *args, **kwargs: [])
    monkeypatch.setattr(skill_admin_service, "get_skill_admin_store", lambda: FakeStore())
    monkeypatch.setattr(
        skill_admin_service,
        "current_settings_payload",
        lambda: {
            "skills_output_dir": f"../.claude/skills/{BUSINESS_SKILL}",
            "skill_runtime": {
                BUSINESS_SKILL: {"enabled": True},
                PLATFORM_TOOLS_SKILL: {"enabled": True},
                "marketing-insights": {"enabled": True},
            },
        },
    )

    documents = skill_admin_service.list_documents()

    assert documents[0]["folder"] == "opendataworks-platform-tools"
    assert documents[0]["relative_path"] == "reference/40-runtime-metadata.md"
    assert documents[0]["source"] == "bundled"
    assert documents[0]["enabled"] is True
    assert documents[0]["editable"] is True


def test_update_skill_runtime_enables_second_skill_without_changing_primary(monkeypatch):
    captured = {}

    monkeypatch.setattr(skill_admin_service, "_discovered_skill_folders", lambda: {BUSINESS_SKILL, "marketing-insights"})
    monkeypatch.setattr(
        skill_admin_service,
        "current_settings_payload",
        lambda: {
            "skills_output_dir": f"../.claude/skills/{BUSINESS_SKILL}",
            "skill_runtime": {
                BUSINESS_SKILL: {"enabled": True},
            },
        },
    )
    monkeypatch.setattr(
        skill_admin_service,
        "persist_admin_settings",
        lambda payload: captured.setdefault("payload", payload) or payload,
    )
    result = skill_admin_service.update_skill_runtime("marketing-insights", True)

    assert "skills_output_dir" not in captured["payload"]
    assert captured["payload"]["skill_runtime"][BUSINESS_SKILL]["enabled"] is True
    assert captured["payload"]["skill_runtime"]["marketing-insights"]["enabled"] is True
    assert result["skill_id"] == "marketing-insights"
    assert result["enabled"] is True


def test_update_skill_runtime_rejects_disabling_last_skill(monkeypatch):
    monkeypatch.setattr(skill_admin_service, "_discovered_skill_folders", lambda: {BUSINESS_SKILL})
    monkeypatch.setattr(
        skill_admin_service,
        "current_settings_payload",
        lambda: {
            "skills_output_dir": f"../.claude/skills/{BUSINESS_SKILL}",
            "skill_runtime": {
                BUSINESS_SKILL: {"enabled": True},
            },
        },
    )

    try:
        skill_admin_service.update_skill_runtime(BUSINESS_SKILL, False)
    except ValueError as exc:
        assert "至少需要保留一个启用 Skill" in str(exc)
    else:
        raise AssertionError("expected ValueError")


def test_update_skill_runtime_moves_primary_when_disabling_current(monkeypatch):
    captured = {}

    monkeypatch.setattr(skill_admin_service, "_discovered_skill_folders", lambda: {BUSINESS_SKILL, "marketing-insights"})
    monkeypatch.setattr(
        skill_admin_service,
        "current_settings_payload",
        lambda: {
            "skills_output_dir": f"../.claude/skills/{BUSINESS_SKILL}",
            "skill_runtime": {
                BUSINESS_SKILL: {"enabled": True},
                "marketing-insights": {"enabled": True},
            },
        },
    )
    monkeypatch.setattr(
        skill_admin_service,
        "_settings_path_for_skill_folder",
        lambda folder: f"../.claude/skills/{folder}",
    )
    monkeypatch.setattr(
        skill_admin_service,
        "persist_admin_settings",
        lambda payload: captured.setdefault("payload", payload) or payload,
    )
    result = skill_admin_service.update_skill_runtime(BUSINESS_SKILL, False)

    assert captured["payload"]["skills_output_dir"] == "../.claude/skills/marketing-insights"
    assert captured["payload"]["skill_runtime"][BUSINESS_SKILL]["enabled"] is False
    assert captured["payload"]["skill_runtime"]["marketing-insights"]["enabled"] is True
    assert result == {"skill_id": BUSINESS_SKILL, "enabled": False}


def test_resolve_enabled_skill_runtime_ignores_deleted_legacy_sql_skill(monkeypatch, tmp_path):
    discovery_root = tmp_path / ".claude" / "skills"
    (discovery_root / BUSINESS_SKILL).mkdir(parents=True)
    (discovery_root / BUSINESS_SKILL / "SKILL.md").write_text("# Business\n", encoding="utf-8")
    (discovery_root / PLATFORM_TOOLS_SKILL).mkdir(parents=True)
    (discovery_root / PLATFORM_TOOLS_SKILL / "SKILL.md").write_text("# Tools\n", encoding="utf-8")

    monkeypatch.setattr(skill_admin_service, "resolve_skill_discovery_root_dir", lambda: discovery_root)
    monkeypatch.setattr(skill_admin_service, "resolve_skills_root_dir", lambda: discovery_root / BUSINESS_SKILL)
    monkeypatch.setattr(
        skill_admin_service,
        "current_settings_payload",
        lambda: {
            "skills_output_dir": f"../.claude/skills/{LEGACY_SQL_SKILL}",
            "skill_runtime": {LEGACY_SQL_SKILL: {"enabled": True}},
        },
    )

    runtime = skill_admin_service.resolve_enabled_skill_runtime()

    assert runtime["primary_folder"] == BUSINESS_SKILL
    assert runtime["enabled_folders"] == [BUSINESS_SKILL, PLATFORM_TOOLS_SKILL]
    assert LEGACY_SQL_SKILL not in runtime["enabled_roots"]


def test_import_skill_from_root_zip_defaults_to_disabled(monkeypatch, tmp_path):
    discovery_root, store, persisted = configure_skill_filesystem(monkeypatch, tmp_path)

    payload = skill_admin_service.import_skill_from_zip(
        "marketing-insights.zip",
        make_zip(
            {
                "SKILL.md": "---\nname: marketing-insights\n---\n# Marketing\n",
                "reference/guide.md": "# Guide\n",
            }
        ),
    )

    assert (discovery_root / "marketing-insights" / "SKILL.md").exists()
    assert payload["skill_id"] == "marketing-insights"
    assert payload["source"] == "managed"
    assert payload["enabled"] is False
    assert persisted["skill_runtime"]["marketing-insights"]["enabled"] is False
    assert "marketing-insights/SKILL.md" in store.documents


def test_import_skill_from_folder_zip(monkeypatch, tmp_path):
    discovery_root, store, persisted = configure_skill_filesystem(monkeypatch, tmp_path)

    payload = skill_admin_service.import_skill_from_zip(
        "marketing-insights.zip",
        make_zip(
            {
                "marketing-insights/SKILL.md": "# Marketing\n",
                "marketing-insights/scripts/run.py": "print('ok')\n",
            }
        ),
    )

    assert (discovery_root / "marketing-insights" / "scripts" / "run.py").exists()
    assert payload["imported_documents"]
    assert persisted["skill_runtime"]["marketing-insights"]["enabled"] is False
    assert "marketing-insights/scripts/run.py" in store.documents


def test_import_skill_rejects_unsafe_zip_path(monkeypatch, tmp_path):
    configure_skill_filesystem(monkeypatch, tmp_path)

    with pytest.raises(ValueError, match="unsafe parent path"):
        skill_admin_service.import_skill_from_zip(
            "bad.zip",
            make_zip({"../evil/SKILL.md": "# Evil\n"}),
        )


def test_import_skill_rejects_duplicate_folder(monkeypatch, tmp_path):
    discovery_root, _, _ = configure_skill_filesystem(monkeypatch, tmp_path)
    (discovery_root / "marketing-insights").mkdir()

    with pytest.raises(ValueError, match="already exists"):
        skill_admin_service.import_skill_from_zip(
            "marketing-insights.zip",
            make_zip({"marketing-insights/SKILL.md": "# Marketing\n"}),
        )


def test_import_skill_rejects_missing_skill_md(monkeypatch, tmp_path):
    configure_skill_filesystem(monkeypatch, tmp_path)

    with pytest.raises(ValueError, match="缺少 SKILL.md"):
        skill_admin_service.import_skill_from_zip(
            "bad.zip",
            make_zip({"reference/guide.md": "# Guide\n"}),
        )


def test_uninstall_skill_removes_managed_folder_and_runtime(monkeypatch, tmp_path):
    settings = {
        "skills_output_dir": "../.claude/skills/marketing-insights",
        "skill_runtime": {
            BUSINESS_SKILL: {"enabled": True},
            "marketing-insights": {"enabled": True},
        },
    }
    discovery_root, store, persisted = configure_skill_filesystem(monkeypatch, tmp_path, settings=settings)
    (discovery_root / BUSINESS_SKILL).mkdir()
    (discovery_root / BUSINESS_SKILL / "SKILL.md").write_text("# Builtin\n", encoding="utf-8")
    (discovery_root / "marketing-insights").mkdir()
    (discovery_root / "marketing-insights" / "SKILL.md").write_text("# Marketing\n", encoding="utf-8")
    store.save_document(
        relative_path="marketing-insights/SKILL.md",
        content="# Marketing\n",
        change_source="upload",
    )
    monkeypatch.setattr(skill_admin_service, "reindex_documents_from_disk", lambda *args, **kwargs: [])
    monkeypatch.setattr(skill_admin_service, "_settings_path_for_skill_folder", lambda folder: f"../.claude/skills/{folder}")

    result = skill_admin_service.uninstall_skill("marketing-insights")

    assert not (discovery_root / "marketing-insights").exists()
    assert result["skill_id"] == "marketing-insights"
    assert result["was_enabled"] is True
    assert result["removed_documents"][0]["folder"] == "marketing-insights"
    assert "marketing-insights/SKILL.md" not in store.documents
    assert "marketing-insights" not in persisted["skill_runtime"]
    assert persisted["skills_output_dir"] == f"../.claude/skills/{BUSINESS_SKILL}"


def test_uninstall_skill_rejects_builtin(monkeypatch, tmp_path):
    configure_skill_filesystem(monkeypatch, tmp_path)

    with pytest.raises(ValueError, match="内置 Skill 不支持卸载"):
        skill_admin_service.uninstall_skill(BUSINESS_SKILL)

    with pytest.raises(ValueError, match="内置 Skill 不支持卸载"):
        skill_admin_service.uninstall_skill(PLATFORM_TOOLS_SKILL)

    with pytest.raises(ValueError, match="内置 Skill 不支持卸载"):
        skill_admin_service.uninstall_skill(ONTOLOGY_MODELING_SKILL)


def test_uninstall_skill_rejects_last_enabled(monkeypatch, tmp_path):
    settings = {
        "skills_output_dir": "../.claude/skills/marketing-insights",
        "skill_runtime": {
            "marketing-insights": {"enabled": True},
        },
    }
    discovery_root, store, _ = configure_skill_filesystem(monkeypatch, tmp_path, settings=settings)
    (discovery_root / "marketing-insights").mkdir()
    (discovery_root / "marketing-insights" / "SKILL.md").write_text("# Marketing\n", encoding="utf-8")
    store.save_document(
        relative_path="marketing-insights/SKILL.md",
        content="# Marketing\n",
        change_source="upload",
    )
    monkeypatch.setattr(skill_admin_service, "reindex_documents_from_disk", lambda *args, **kwargs: [])

    with pytest.raises(ValueError, match="至少需要保留一个启用 Skill"):
        skill_admin_service.uninstall_skill("marketing-insights")
