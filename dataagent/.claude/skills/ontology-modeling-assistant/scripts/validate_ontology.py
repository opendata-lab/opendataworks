#!/usr/bin/env python3
"""
Validate a domain ontology JSON file for ontology-modeling-assistant.

Examples:
  python3 scripts/validate_ontology.py
  python3 scripts/validate_ontology.py --path assets/ontology.json --json
  python3 scripts/validate_ontology.py --path /path/to/domain-ontology/assets/ontology.json
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from typing import Any, Dict, Iterable, List, Optional

from pydantic import ValidationError

from ontology_schema import OntologyFile, ontology_json_schema_text


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ONTOLOGY_PATH = os.path.join(SCRIPT_DIR, "..", "assets", "ontology.json")

ID_RE = re.compile(r"^[a-z][a-z0-9_]*$")
TOP_LEVEL_REQUIRED = ("metadata", "object_types", "object_relations")
METADATA_REQUIRED = ("id", "name_zh", "name_en", "owner", "version")
OBJECT_REQUIRED = ("id", "name_zh", "name_en", "kind", "description", "synonyms")
RELATION_REQUIRED = ("id", "from_object", "to_object", "relation_kind", "cardinality", "description")
QUERY_FUNCTION_REQUIRED = ("function_name", "intent", "grain", "params", "output_fields", "notes")


def as_list(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


def make_issue(code: str, path: str, message: str) -> Dict[str, str]:
    return {"code": code, "path": path, "message": message}


def pydantic_error_path(location: tuple[Any, ...]) -> str:
    path = "$"
    for item in location:
        if isinstance(item, int):
            path += f"[{item}]"
        else:
            path += f".{item}"
    return path


def validate_with_pydantic(data: Dict[str, Any]) -> list[Dict[str, str]]:
    try:
        OntologyFile.model_validate(data)
    except ValidationError as exc:
        issues = []
        for error in exc.errors():
            message = str(error.get("msg") or "schema validation failed")
            if "input" in error:
                message = f"{message}; input={error['input']!r}"
            issues.append(
                make_issue(
                    "schema_validation",
                    pydantic_error_path(tuple(error.get("loc") or ())),
                    message,
                )
            )
        return issues
    return []


def load_json(path: str) -> tuple[Optional[Dict[str, Any]], list[Dict[str, str]]]:
    try:
        with open(path, "r", encoding="utf-8") as f:
            payload = json.load(f)
    except FileNotFoundError:
        return None, [make_issue("file_not_found", path, f"ontology file not found: {path}")]
    except json.JSONDecodeError as exc:
        return None, [make_issue("invalid_json", path, f"invalid JSON: {exc}")]
    except OSError as exc:
        return None, [make_issue("read_error", path, f"failed to read ontology file: {exc}")]
    if not isinstance(payload, dict):
        return None, [make_issue("invalid_root", "$", "ontology root must be a JSON object")]
    return payload, []


def require_fields(item: Dict[str, Any], fields: Iterable[str], path: str, errors: list[Dict[str, str]]) -> None:
    for field in fields:
        value = item.get(field)
        if value is None or value == "":
            errors.append(make_issue("missing_required_field", f"{path}.{field}", f"missing required field: {field}"))


def validate_id(value: Any, path: str, errors: list[Dict[str, str]]) -> str:
    text = str(value or "").strip()
    if not text:
        errors.append(make_issue("missing_id", path, "missing id"))
        return ""
    if not ID_RE.match(text):
        errors.append(make_issue("invalid_id", path, f"id must be snake_case and start with a letter: {text}"))
    return text


def collect_unique_ids(
    items: list[Any],
    *,
    collection_name: str,
    path: str,
    errors: list[Dict[str, str]],
) -> set[str]:
    ids: set[str] = set()
    for index, item in enumerate(items):
        item_path = f"{path}[{index}]"
        if not isinstance(item, dict):
            errors.append(make_issue("invalid_item", item_path, f"{collection_name} item must be an object"))
            continue
        item_id = validate_id(item.get("id") or item.get("relation_id"), f"{item_path}.id", errors)
        if not item_id:
            continue
        if item_id in ids:
            errors.append(make_issue("duplicate_id", f"{item_path}.id", f"duplicate {collection_name} id: {item_id}"))
        ids.add(item_id)
    return ids


def validate_query_functions(obj: Dict[str, Any], *, object_path: str, errors: list[Dict[str, str]]) -> None:
    funcs = obj.get("query_functions", [])
    if funcs is None:
        return
    if not isinstance(funcs, list):
        errors.append(make_issue("invalid_query_functions", f"{object_path}.query_functions", "query_functions must be a list"))
        return
    seen: set[str] = set()
    for index, func in enumerate(funcs):
        func_path = f"{object_path}.query_functions[{index}]"
        if not isinstance(func, dict):
            errors.append(make_issue("invalid_query_function", func_path, "query function must be an object"))
            continue
        require_fields(func, QUERY_FUNCTION_REQUIRED, func_path, errors)
        name = str(func.get("function_name") or "").strip()
        if name:
            validate_id(name, f"{func_path}.function_name", errors)
            if name in seen:
                errors.append(make_issue("duplicate_query_function", f"{func_path}.function_name", f"duplicate query function: {name}"))
            seen.add(name)
        for field in ("params", "output_fields"):
            if field in func and not isinstance(func.get(field), list):
                errors.append(make_issue("invalid_query_function_field", f"{func_path}.{field}", f"{field} must be a list"))


def validate_ontology(data: Dict[str, Any]) -> Dict[str, Any]:
    errors: list[Dict[str, str]] = validate_with_pydantic(data)
    warnings: list[Dict[str, str]] = []

    for field in TOP_LEVEL_REQUIRED:
        if field not in data:
            errors.append(make_issue("missing_top_level_field", f"$.{field}", f"missing top-level field: {field}"))
    if "semantic_edges" in data:
        errors.append(
            make_issue(
                "legacy_semantic_edges",
                "$.semantic_edges",
                "semantic_edges is no longer supported; put these edges in object_relations and use relation_kind",
            )
        )
    if "evidence_sources" in data:
        errors.append(
            make_issue(
                "legacy_evidence_sources",
                "$.evidence_sources",
                "evidence_sources is no longer supported; keep ontology JSON focused on metadata, object_types, and object_relations",
            )
        )
    if "quality_gates" in data:
        errors.append(
            make_issue(
                "legacy_quality_gates",
                "$.quality_gates",
                "quality_gates is no longer supported in ontology JSON",
            )
        )

    metadata = data.get("metadata") if isinstance(data.get("metadata"), dict) else {}
    if not metadata:
        errors.append(make_issue("invalid_metadata", "$.metadata", "metadata must be an object"))
    else:
        require_fields(metadata, METADATA_REQUIRED, "$.metadata", errors)
        if metadata.get("id"):
            validate_id(metadata.get("id"), "$.metadata.id", errors)

    for field in ("object_types", "object_relations"):
        if field in data and not isinstance(data.get(field), list):
            errors.append(make_issue("invalid_collection", f"$.{field}", f"{field} must be a list"))

    object_types = as_list(data.get("object_types"))
    object_relations = as_list(data.get("object_relations"))

    object_ids = collect_unique_ids(object_types, collection_name="object_types", path="$.object_types", errors=errors)
    collect_unique_ids(object_relations, collection_name="object_relations", path="$.object_relations", errors=errors)

    for index, obj in enumerate(object_types):
        path = f"$.object_types[{index}]"
        if not isinstance(obj, dict):
            continue
        require_fields(obj, OBJECT_REQUIRED, path, errors)
        if "synonyms" in obj and not isinstance(obj.get("synonyms"), list):
            errors.append(make_issue("invalid_synonyms", f"{path}.synonyms", "synonyms must be a list"))
        if "evidence_ids" in obj:
            errors.append(
                make_issue(
                    "legacy_evidence_ids",
                    f"{path}.evidence_ids",
                    "evidence_ids is no longer supported on object_types",
                )
            )
        validate_query_functions(obj, object_path=path, errors=errors)

    for index, rel in enumerate(object_relations):
        path = f"$.object_relations[{index}]"
        if not isinstance(rel, dict):
            continue
        require_fields(rel, RELATION_REQUIRED, path, errors)
        from_object = str(rel.get("from_object") or "").strip()
        to_object = str(rel.get("to_object") or "").strip()
        if from_object and from_object not in object_ids:
            errors.append(make_issue("unknown_relation_endpoint", f"{path}.from_object", f"unknown relation endpoint object: {from_object}"))
        if to_object and to_object not in object_ids:
            errors.append(make_issue("unknown_relation_endpoint", f"{path}.to_object", f"unknown relation endpoint object: {to_object}"))
        if "evidence_requirements" in rel:
            errors.append(
                make_issue(
                    "legacy_evidence_requirements",
                    f"{path}.evidence_requirements",
                    "evidence_requirements is no longer supported on object_relations",
                )
            )
        if "evidence_ids" in rel:
            errors.append(
                make_issue(
                    "legacy_evidence_ids",
                    f"{path}.evidence_ids",
                    "evidence_ids is no longer supported on object_relations",
                )
            )
    summary = {
        "object_type_count": len(object_types),
        "relation_count": len(object_relations),
    }
    return {
        "valid": not errors,
        "error_count": len(errors),
        "warning_count": len(warnings),
        "errors": errors,
        "warnings": warnings,
        "summary": summary,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Validate ontology JSON structure and references.")
    parser.add_argument("--path", default=DEFAULT_ONTOLOGY_PATH, help="Path to assets/ontology.json")
    parser.add_argument("--json", action="store_true", help="Print machine-readable JSON")
    parser.add_argument("--schema", action="store_true", help="Print Pydantic-generated JSON Schema")
    return parser


def print_human(result: Dict[str, Any], path: str) -> None:
    status = "valid" if result["valid"] else "invalid"
    print(f"Ontology {status}: {path}")
    summary = result["summary"]
    print(
        "Summary: "
        f"objects={summary['object_type_count']}, "
        f"relations={summary['relation_count']}"
    )
    for issue in result["errors"]:
        print(f"ERROR {issue['code']} {issue['path']}: {issue['message']}")
    for issue in result["warnings"]:
        print(f"WARN {issue['code']} {issue['path']}: {issue['message']}")


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.schema:
        print(ontology_json_schema_text(), end="")
        return 0
    path = os.path.abspath(os.path.expanduser(str(args.path)))
    data, load_errors = load_json(path)
    if load_errors:
        result = {
            "valid": False,
            "error_count": len(load_errors),
            "warning_count": 0,
            "errors": load_errors,
            "warnings": [],
            "summary": {
                "object_type_count": 0,
                "relation_count": 0,
            },
        }
    else:
        result = validate_ontology(data or {})

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print_human(result, path)
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
