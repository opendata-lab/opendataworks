"""The shipped JSON Schema must stay in step with the pydantic model."""

import json
from pathlib import Path

from methodology_schema import (
    FIELD_DICTIONARY,
    NODE_TYPE_OPTIONS,
    PLACEHOLDER_FORMS,
    methodology_json_schema,
    methodology_json_schema_text,
)

SKILL_ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = SKILL_ROOT / "assets" / "methodology.schema.json"


def test_shipped_schema_matches_the_model():
    shipped = SCHEMA_PATH.read_text(encoding="utf-8")
    assert shipped == methodology_json_schema_text(), (
        "assets/methodology.schema.json 与 pydantic 模型不一致；"
        "请重新运行 `python3 scripts/methodology_schema.py > assets/methodology.schema.json`"
    )


def test_schema_carries_the_field_dictionary():
    schema = methodology_json_schema()
    assert schema["x-field-dictionary"] == FIELD_DICTIONARY


def test_every_node_type_has_a_group_and_a_description():
    for name, entry in NODE_TYPE_OPTIONS.items():
        assert entry["group"] in {"query", "compute", "control"}, name
        assert entry["description"], name


def test_the_three_placeholder_forms_are_documented():
    assert set(PLACEHOLDER_FORMS) == {"{{ expr }}", "{{! expr }}", "{{? name }}"}


def test_schema_is_valid_json_and_declares_the_required_top_level_fields():
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    assert set(schema["required"]) >= {"id", "version", "name_zh", "intent", "caliber", "owner", "nodes", "target"}
