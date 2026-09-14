from __future__ import annotations

import importlib.util
from pathlib import Path
from types import ModuleType


MIGRATION_PATH = (
    Path(__file__).resolve().parents[1]
    / "alembic"
    / "versions"
    / "20260914_add_api_format.py"
)


def _load_migration() -> ModuleType:
    spec = importlib.util.spec_from_file_location("api_format_migration", MIGRATION_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_upgrade_adds_api_format_and_removes_all_legacy_provider_copies(monkeypatch) -> None:
    migration = _load_migration()
    executed: list[str] = []
    monkeypatch.setattr(migration.op, "execute", executed.append)

    migration.upgrade()

    assert len(executed) == 3
    assert "ADD COLUMN api_format" in executed[0]
    assert executed[1] == "DELETE FROM da_model_provider"
    assert "UPDATE da_agent_settings" in executed[2]
    assert "provider_id = ''" in executed[2]
    assert "anthropic_api_key = ''" in executed[2]
    assert "'$.provider_settings'" in executed[2]
    assert "'$.skill_runtime'" not in executed[2]
