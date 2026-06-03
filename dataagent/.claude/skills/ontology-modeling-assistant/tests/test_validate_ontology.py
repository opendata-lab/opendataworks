import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "validate_ontology.py"
SCHEMA_ASSET = ROOT / "assets" / "ontology.schema.json"


def run_validate(path):
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--path", str(path), "--json"],
        cwd=ROOT,
        check=False,
        text=True,
        capture_output=True,
    )


def run_schema_export():
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--schema"],
        cwd=ROOT,
        check=False,
        text=True,
        capture_output=True,
    )


def write_ontology(tmp_path, payload):
    path = tmp_path / "ontology.json"
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return path


def minimal_valid_ontology():
    return {
        "metadata": {
            "id": "demo_ontology",
            "name_zh": "演示本体",
            "name_en": "Demo Ontology",
            "owner": "test",
            "version": "1.0.0",
        },
        "object_types": [
            {
                "id": "customer",
                "name_zh": "客户",
                "name_en": "Customer",
                "kind": "entity",
                "description": "客户实体。",
                "synonyms": ["会员"],
                "properties": [
                    {"name_zh": "客户ID", "name_en": "customer_id", "description": "客户唯一标识。"}
                ],
                "query_functions": [
                    {
                        "function_name": "list_customers",
                        "intent": "查询客户列表。",
                        "grain": "customer",
                        "params": ["target_date"],
                        "output_fields": ["customer_id"],
                        "notes": "语义交接函数，不直接执行 SQL。",
                    }
                ],
            },
            {
                "id": "customer_status",
                "name_zh": "客户状态",
                "name_en": "Customer Status",
                "kind": "attribute",
                "description": "客户状态属性。",
                "synonyms": [],
            },
        ],
        "object_relations": [
            {
                "id": "customer_has_status",
                "from_object": "customer",
                "to_object": "customer_status",
                "relation_kind": "has_attribute",
                "cardinality": "one_to_many",
                "description": "客户拥有状态。",
            }
        ],
    }


def test_validate_builtin_ontology_passes():
    result = run_validate(ROOT / "assets" / "ontology.json")

    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload["valid"] is True
    assert payload["error_count"] == 0
    assert payload["summary"]["object_type_count"] >= 8
    assert payload["summary"]["relation_count"] >= 9
    assert "semantic_edge_count" not in payload["summary"]
    assert "evidence_source_count" not in payload["summary"]
    assert "quality_gate_count" not in payload["summary"]


def test_validate_accepts_minimal_valid_domain_ontology(tmp_path):
    result = run_validate(write_ontology(tmp_path, minimal_valid_ontology()))

    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload["valid"] is True
    assert payload["summary"]["relation_count"] == 1


def test_validate_rejects_relation_with_missing_endpoint(tmp_path):
    ontology = minimal_valid_ontology()
    ontology["object_relations"][0]["to_object"] = "missing_status"

    result = run_validate(write_ontology(tmp_path, ontology))

    assert result.returncode == 1
    payload = json.loads(result.stdout)
    assert payload["valid"] is False
    assert any("missing_status" in item["message"] for item in payload["errors"])


def test_validate_rejects_legacy_evidence_sources_top_level(tmp_path):
    ontology = minimal_valid_ontology()
    ontology["evidence_sources"] = [
        {
            "id": "req_1",
            "source_type": "requirement",
            "name": "需求",
            "description": "用户确认的需求。",
        }
    ]

    result = run_validate(write_ontology(tmp_path, ontology))

    assert result.returncode == 1
    payload = json.loads(result.stdout)
    assert any("evidence_sources" in item["message"] or "evidence_sources" in item["path"] for item in payload["errors"])


def test_validate_rejects_legacy_evidence_fields_on_objects_and_relations(tmp_path):
    ontology = minimal_valid_ontology()
    ontology["object_types"][0]["evidence_ids"] = ["req_1"]
    ontology["object_relations"][0]["evidence_requirements"] = ["字段名"]

    result = run_validate(write_ontology(tmp_path, ontology))

    assert result.returncode == 1
    payload = json.loads(result.stdout)
    error_text = json.dumps(payload["errors"], ensure_ascii=False)
    assert "evidence_ids" in error_text
    assert "evidence_requirements" in error_text


def test_validate_rejects_legacy_quality_gates_top_level(tmp_path):
    ontology = minimal_valid_ontology()
    ontology["quality_gates"] = [{"id": "complete", "description": "检查完整性。"}]

    result = run_validate(write_ontology(tmp_path, ontology))

    assert result.returncode == 1
    payload = json.loads(result.stdout)
    assert any("quality_gates" in item["message"] or "quality_gates" in item["path"] for item in payload["errors"])


def test_validate_rejects_incomplete_query_function(tmp_path):
    ontology = minimal_valid_ontology()
    del ontology["object_types"][0]["query_functions"][0]["output_fields"]

    result = run_validate(write_ontology(tmp_path, ontology))

    assert result.returncode == 1
    payload = json.loads(result.stdout)
    assert any("output_fields" in item["message"] for item in payload["errors"])


def test_validate_rejects_legacy_semantic_edges_top_level(tmp_path):
    ontology = minimal_valid_ontology()
    ontology["semantic_edges"] = []

    result = run_validate(write_ontology(tmp_path, ontology))

    assert result.returncode == 1
    payload = json.loads(result.stdout)
    assert any("semantic_edges" in item["message"] for item in payload["errors"])


def test_schema_flag_exports_pydantic_json_schema_with_relation_kind_enum():
    result = run_schema_export()

    assert result.returncode == 0, result.stderr
    schema = json.loads(result.stdout)
    assert schema["required"] == ["metadata", "object_types", "object_relations"]
    assert "evidence_sources" not in schema["properties"]
    assert "quality_gates" not in schema["properties"]
    assert "evidence_ids" not in schema["$defs"]["ObjectType"]["properties"]
    assert "evidence_ids" not in schema["$defs"]["ObjectRelation"]["properties"]
    assert "evidence_requirements" not in schema["$defs"]["ObjectRelation"]["properties"]
    assert "confidence" not in schema["$defs"]["ObjectType"]["properties"]
    assert "confidence" not in schema["$defs"]["ObjectRelation"]["properties"]
    relation_kind = schema["$defs"]["ObjectRelation"]["properties"]["relation_kind"]
    assert "semantic_mapping" in relation_kind["enum"]
    assert "caliber_rule" in relation_kind["enum"]
    assert "document_evidence" not in relation_kind["enum"]
    assert "schema_evidence" not in relation_kind["enum"]
    field_dictionary = schema["x-field-dictionary"]
    assert "confidence" not in field_dictionary
    assert field_dictionary["object_relations.relation_kind"]["values"]["semantic_mapping"]["label_zh"] == "语义映射"
    assert field_dictionary["object_relations.cardinality"]["values"]["one_to_many"]["label_zh"] == "一对多"


def test_committed_schema_asset_matches_pydantic_export():
    result = run_schema_export()

    assert result.returncode == 0, result.stderr
    assert SCHEMA_ASSET.exists()
    assert json.loads(SCHEMA_ASSET.read_text(encoding="utf-8")) == json.loads(result.stdout)


def test_field_dictionary_lives_in_schema_not_reference_markdown():
    assert not (ROOT / "reference" / "ontology-field-dictionary.md").exists()


def test_validate_rejects_legacy_confidence_fields(tmp_path):
    ontology = minimal_valid_ontology()
    ontology["object_types"][0]["confidence"] = "high"
    ontology["object_relations"][0]["confidence"] = "medium"

    result = run_validate(write_ontology(tmp_path, ontology))

    assert result.returncode == 1
    payload = json.loads(result.stdout)
    error_text = json.dumps(payload["errors"], ensure_ascii=False)
    assert "confidence" in error_text


def test_validate_rejects_invalid_relation_kind_with_pydantic_schema(tmp_path):
    ontology = minimal_valid_ontology()
    ontology["object_relations"][0]["relation_kind"] = "made_up_relation"

    result = run_validate(write_ontology(tmp_path, ontology))

    assert result.returncode == 1
    payload = json.loads(result.stdout)
    error_text = json.dumps(payload["errors"], ensure_ascii=False)
    assert "relation_kind" in error_text
    assert "made_up_relation" in error_text
