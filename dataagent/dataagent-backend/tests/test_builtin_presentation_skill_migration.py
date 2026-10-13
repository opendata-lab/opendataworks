"""Execute the production migration SQL against a small in-memory database."""
import importlib.util
import json
from pathlib import Path

import pytest
import sqlalchemy as sa

PATH = Path(__file__).resolve().parents[1] / "alembic/versions/20261012_000027_builtin_presentation_skills.py"
spec = importlib.util.spec_from_file_location("builtin_presentation_migration", PATH)
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)


@pytest.fixture
def database(monkeypatch):
    engine = sa.create_engine("sqlite://")
    with engine.begin() as conn:
        conn.execute(sa.text("""CREATE TABLE da_agent_profile (
            agent_id VARCHAR PRIMARY KEY, is_builtin INTEGER, skill_folders_json VARCHAR,
            updated_at VARCHAR, name VARCHAR)"""))
        conn.execute(sa.text("""CREATE TABLE da_agent_draft (
            agent_id VARCHAR PRIMARY KEY, draft_json VARCHAR, published_json VARCHAR,
            revision INTEGER DEFAULT 3, published_version INTEGER DEFAULT 2,
            preview_task_id VARCHAR DEFAULT 'old_task', preview_revision INTEGER DEFAULT 3,
            preview_token VARCHAR DEFAULT 'old_token', updated_at VARCHAR)"""))
        conn.execute(sa.text("CREATE TABLE da_agent_topic (agent_snapshot_json VARCHAR)"))
        conn.execute(sa.text("INSERT INTO da_agent_topic VALUES ('historical-snapshot')"))

        class Bind:
            def execute(self, statement, params):
                # SQLite has no row locks. All other production SQL is executed unchanged.
                return conn.execute(sa.text(str(statement).removesuffix(" FOR UPDATE")), params)

        monkeypatch.setattr(migration.op, "get_bind", lambda: Bind())
        yield conn
    engine.dispose()


def seed(conn, agent_id="agent_opendataworks", folders=None, *, builtin=True, draft=True, dirty=False):
    folders = folders if folders is not None else ["opendataworks-platform-tools", "custom-skill"]
    conn.execute(sa.text("INSERT INTO da_agent_profile VALUES (:id, :builtin, :skills, NULL, '自定义名称')"),
                 {"id": agent_id, "builtin": int(builtin), "skills": json.dumps(folders)})
    config = {"agent_id": agent_id, "skill_folders": folders, "name": "自定义名称",
              "system_prompt": "保留用户提示词", "is_builtin": builtin}
    published = json.dumps(config, ensure_ascii=False, sort_keys=True)
    if dirty:
        config = {**config, "name": "未发布修改", "skill_folders": [*folders, "draft-only-skill"]}
    if draft:
        conn.execute(sa.text("INSERT INTO da_agent_draft (agent_id, draft_json, published_json) VALUES (:id, :draft, :published)"),
                     {"id": agent_id, "draft": json.dumps(config, ensure_ascii=False, sort_keys=True), "published": published})


def row(conn, table, agent_id="agent_opendataworks"):
    return conn.execute(sa.text(f"SELECT * FROM {table} WHERE agent_id=:id"), {"id": agent_id}).mappings().first()


@pytest.mark.parametrize("dirty", [False, True])
def test_upgrade_preserves_edits_versions_and_history(database, dirty):
    seed(database, dirty=dirty)
    migration.upgrade()
    profile = row(database, "da_agent_profile")
    draft = row(database, "da_agent_draft")
    expected = ["opendataworks-platform-tools", "custom-skill", *migration.SKILLS]
    assert json.loads(profile["skill_folders_json"]) == expected
    assert profile["name"] == "自定义名称"
    published = json.loads(draft["published_json"])
    saved = json.loads(draft["draft_json"])
    assert published["skill_folders"] == expected
    assert saved["skill_folders"] == [*expected[:2], *(["draft-only-skill"] if dirty else []), *migration.SKILLS]
    assert saved["system_prompt"] == "保留用户提示词"
    assert saved["name"] == ("未发布修改" if dirty else "自定义名称")
    assert (draft["draft_json"] != draft["published_json"]) is dirty
    assert (draft["revision"], draft["published_version"]) == (4, 3)
    assert all(draft[key] is None for key in ("preview_task_id", "preview_revision", "preview_token"))
    assert database.execute(sa.text("SELECT agent_snapshot_json FROM da_agent_topic")).scalar() == "historical-snapshot"
    before = dict(draft)
    migration.upgrade()
    assert dict(row(database, "da_agent_draft")) == before


def test_default_agent_without_draft_gets_skills(database):
    seed(database, "agent_default", [], draft=False)
    migration.upgrade()
    assert json.loads(row(database, "da_agent_profile", "agent_default")["skill_folders_json"]) == list(migration.SKILLS)
    assert row(database, "da_agent_draft", "agent_default") is None


def test_existing_skills_and_preview_approval_are_unchanged(database):
    seed(database, folders=["custom-skill", *migration.SKILLS])
    before = dict(row(database, "da_agent_draft"))
    migration.upgrade()
    assert dict(row(database, "da_agent_draft")) == before


@pytest.mark.parametrize("agent_id,builtin", [("agent_opendataworks", False), ("custom", True)])
def test_nonbuiltin_and_other_agents_are_unchanged(database, agent_id, builtin):
    seed(database, agent_id, builtin=builtin)
    before = dict(row(database, "da_agent_draft", agent_id))
    migration.upgrade()
    assert dict(row(database, "da_agent_draft", agent_id)) == before


def test_downgrade_preserves_operator_skill_choices(database):
    seed(database)
    migration.upgrade()
    before = dict(row(database, "da_agent_draft"))
    migration.downgrade()
    assert dict(row(database, "da_agent_draft")) == before
