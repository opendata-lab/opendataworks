import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "lookup_ontology.py"


def run_lookup(*args):
    return subprocess.run(
        [sys.executable, str(SCRIPT), *args, "--json"],
        cwd=ROOT,
        check=False,
        text=True,
        capture_output=True,
    )


def test_lookup_object_by_id_returns_compact_model_slice():
    result = run_lookup("--object", "domain_entity")

    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload["kind"] == "object"
    assert payload["object"]["id"] == "domain_entity"
    assert payload["object"]["name_zh"] == "业务实体"
    assert payload["object"]["property_count"] > 0
    assert payload["object"]["query_function_count"] > 0
    assert "relations" not in payload


def test_lookup_relation_by_id_returns_join_or_modeling_path():
    result = run_lookup("--relation", "entity_has_attribute")

    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload["kind"] == "relation"
    assert payload["relation"]["id"] == "entity_has_attribute"
    assert payload["relation"]["from_object"] == "domain_entity"
    assert payload["relation"]["to_object"] == "domain_attribute"
    assert payload["relation"]["modeling_guidance"]


def test_lookup_semantic_mapping_relation_by_id_returns_relation_kind():
    result = run_lookup("--relation", "term_maps_to_table_column")

    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload["kind"] == "relation"
    assert payload["relation"]["id"] == "term_maps_to_table_column"
    assert payload["relation"]["relation_kind"] == "semantic_mapping"
    assert "evidence_ids" not in payload["relation"]
    assert "evidence_requirements" not in payload["relation"]


def test_search_by_chinese_term_returns_ranked_candidates_without_full_ontology():
    result = run_lookup("--query", "上传文档")

    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload["kind"] == "search"
    object_ids = [item["id"] for item in payload["objects"]]
    assert "source_document" in object_ids
    relation_ids = [item["id"] for item in payload["relations"]]
    assert "document_mentions_domain_entity" in relation_ids
    assert "semantic_edges" not in payload
    assert len(json.dumps(payload, ensure_ascii=False)) < 5000


def test_include_functions_exposes_domain_skill_scaffold_contract():
    result = run_lookup("--object", "domain_ontology_skill", "--include", "functions")

    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    object_text = json.dumps(payload["object"], ensure_ascii=False)
    assert "scaffold_domain_ontology_skill" in object_text
    assert "skill_folder" in object_text
    assert "ontology_json_path" in object_text
