"""The skill list must carry what each skill is for.

Every SKILL.md in this repo declares a description and none of it reached the
UI, so the client filled that column with last_change_summary — the reindex
note, identical on every row and looking like content while telling a reader
nothing about the skill.
"""
from __future__ import annotations

from pathlib import Path

from core.skill_admin_service import _skill_description_from_front_matter


def _write_skill(root: Path, folder: str, body: str) -> None:
    (root / folder).mkdir(parents=True, exist_ok=True)
    (root / folder / "SKILL.md").write_text(body, encoding="utf-8")


def test_the_description_comes_from_front_matter(tmp_path, monkeypatch):
    monkeypatch.setattr(
        "core.skill_admin_service.resolve_skill_discovery_root_dir", lambda: tmp_path
    )
    _write_skill(
        tmp_path,
        "demo-skill",
        '---\nname: demo-skill\ndescription: "查询平台元数据与血缘。"\n---\n\n正文\n',
    )

    assert _skill_description_from_front_matter("demo-skill") == "查询平台元数据与血缘。"


def test_a_skill_without_one_yields_empty_rather_than_a_stand_in(tmp_path, monkeypatch):
    """Empty, so the client can say so — not a value borrowed from elsewhere."""
    monkeypatch.setattr(
        "core.skill_admin_service.resolve_skill_discovery_root_dir", lambda: tmp_path
    )
    _write_skill(tmp_path, "bare-skill", "---\nname: bare-skill\n---\n\n正文\n")

    assert _skill_description_from_front_matter("bare-skill") == ""


def test_a_missing_or_unreadable_skill_does_not_break_the_listing(tmp_path, monkeypatch):
    monkeypatch.setattr(
        "core.skill_admin_service.resolve_skill_discovery_root_dir", lambda: tmp_path
    )

    assert _skill_description_from_front_matter("not-there") == ""
    assert _skill_description_from_front_matter("") == ""


def test_the_payload_actually_carries_it():
    """The helper existing is not enough — the list row has to include it.

    Deleting the payload line leaves every other test in this file green while
    the UI goes back to having no description to show.
    """
    import inspect

    from core import skill_admin_service

    source = inspect.getsource(skill_admin_service._document_api_payload)
    assert 'payload["description"]' in source, (
        "_document_api_payload must publish the description"
    )
