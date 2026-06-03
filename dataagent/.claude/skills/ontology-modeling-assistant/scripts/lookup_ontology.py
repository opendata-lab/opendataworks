#!/usr/bin/env python3
"""
Query small slices from ontology-modeling-assistant/assets/ontology.json.

Examples:
  python3 scripts/lookup_ontology.py --query 上传文档
  python3 scripts/lookup_ontology.py --object domain_entity --include properties,functions
  python3 scripts/lookup_ontology.py --relation entity_has_attribute
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any, Dict, Iterable, List, Optional, Tuple


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ONTOLOGY_PATH = os.path.join(SCRIPT_DIR, "..", "assets", "ontology.json")


def load_ontology() -> Dict[str, Any]:
    with open(ONTOLOGY_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def as_list(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


def normalize(value: Any) -> str:
    return str(value or "").casefold()


def parse_include(raw: Optional[str]) -> set[str]:
    if not raw:
        return set()
    if raw == "all":
        return {"properties", "functions", "relations"}
    return {part.strip() for part in raw.split(",") if part.strip()}


def iter_object_text_fields(obj: Dict[str, Any]) -> Iterable[Tuple[str, str]]:
    for key in ("id", "name_zh", "name_en", "kind", "description"):
        if obj.get(key):
            yield key, str(obj[key])
    for synonym in as_list(obj.get("synonyms")):
        yield "synonym", str(synonym)
    for prop in as_list(obj.get("properties")):
        for key in ("name_zh", "name_en", "description"):
            if prop.get(key):
                yield f"property.{key}", str(prop[key])
    for func in as_list(obj.get("query_functions")):
        for key in ("function_name", "intent", "grain", "notes"):
            if func.get(key):
                yield f"function.{key}", str(func[key])


def iter_relation_text_fields(rel: Dict[str, Any]) -> Iterable[Tuple[str, str]]:
    for key in ("id", "from_object", "to_object", "relation_kind", "cardinality", "description"):
        if rel.get(key):
            yield key, str(rel[key])
    for item in as_list(rel.get("modeling_guidance")):
        yield "modeling_guidance", str(item)


def score_text(term: str, field: str, text: str) -> int:
    term_norm = normalize(term)
    text_norm = normalize(text)
    if not term_norm or not text_norm:
        return 0
    if term_norm == text_norm:
        if field in {"id", "name_zh", "name_en"}:
            return 120
        if field == "synonym":
            return 100
        return 80
    if term_norm in text_norm:
        if field in {"id", "name_zh", "name_en"}:
            return 80
        if field == "synonym":
            return 70
        if field.startswith("property."):
            return 45
        if field.startswith("function."):
            return 40
        return 30
    return 0


def find_object(data: Dict[str, Any], object_id: str) -> Optional[Dict[str, Any]]:
    wanted = normalize(object_id)
    for obj in as_list(data.get("object_types")):
        if normalize(obj.get("id")) == wanted:
            return obj
    return None


def find_relation(data: Dict[str, Any], relation_id: str) -> Optional[Dict[str, Any]]:
    wanted = normalize(relation_id)
    for rel in as_list(data.get("object_relations")):
        if normalize(rel.get("id")) == wanted or normalize(rel.get("relation_id")) == wanted:
            return rel
    return None


def compact_function(func: Dict[str, Any]) -> Dict[str, Any]:
    result = {
        "function_name": func.get("function_name"),
        "intent": func.get("intent"),
        "grain": func.get("grain"),
        "params": func.get("params", []),
        "output_fields": func.get("output_fields", []),
    }
    if func.get("notes"):
        result["notes"] = func["notes"]
    return result


def compact_relation(rel: Dict[str, Any]) -> Dict[str, Any]:
    result = {
        "id": rel.get("id") or rel.get("relation_id"),
        "from_object": rel.get("from_object"),
        "to_object": rel.get("to_object"),
        "relation_kind": rel.get("relation_kind"),
        "cardinality": rel.get("cardinality"),
        "description": rel.get("description"),
        "modeling_guidance": rel.get("modeling_guidance", []),
    }
    return result


def related_relations(data: Dict[str, Any], object_id: str) -> List[Dict[str, Any]]:
    return [
        compact_relation(rel)
        for rel in as_list(data.get("object_relations"))
        if rel.get("from_object") == object_id or rel.get("to_object") == object_id
    ]


def compact_object(data: Dict[str, Any], obj: Dict[str, Any], include: set[str]) -> Dict[str, Any]:
    object_id = obj.get("id")
    result: Dict[str, Any] = {
        "id": object_id,
        "name_zh": obj.get("name_zh"),
        "name_en": obj.get("name_en"),
        "kind": obj.get("kind"),
        "description": obj.get("description"),
        "synonyms": obj.get("synonyms", []),
        "property_count": len(as_list(obj.get("properties"))),
        "query_function_count": len(as_list(obj.get("query_functions"))),
        "relation_count": len(related_relations(data, object_id)),
    }
    if obj.get("needs_confirmation"):
        result["needs_confirmation"] = obj.get("needs_confirmation")
    if "properties" in include:
        result["properties"] = obj.get("properties", [])
    if "functions" in include:
        result["query_functions"] = [compact_function(func) for func in as_list(obj.get("query_functions"))]
    if "relations" in include:
        result["relations"] = related_relations(data, object_id)
    return result


def search_objects(data: Dict[str, Any], term: str) -> List[Dict[str, Any]]:
    results = []
    for obj in as_list(data.get("object_types")):
        best_score = 0
        best_field = ""
        for field, text in iter_object_text_fields(obj):
            score = score_text(term, field, text)
            if score > best_score:
                best_score = score
                best_field = field
        if best_score:
            results.append({
                "id": obj.get("id"),
                "name_zh": obj.get("name_zh"),
                "kind": obj.get("kind"),
                "score": best_score,
                "matched_field": best_field,
            })
    return sorted(results, key=lambda item: (-item["score"], item["id"]))[:8]


def search_relations(data: Dict[str, Any], term: str) -> List[Dict[str, Any]]:
    results = []
    for rel in as_list(data.get("object_relations")):
        best_score = 0
        best_field = ""
        for field, text in iter_relation_text_fields(rel):
            score = score_text(term, field, text)
            if score > best_score:
                best_score = score
                best_field = field
        if best_score:
            results.append({
                "id": rel.get("id"),
                "from_object": rel.get("from_object"),
                "to_object": rel.get("to_object"),
                "relation_kind": rel.get("relation_kind"),
                "score": best_score,
                "matched_field": best_field,
            })
    return sorted(results, key=lambda item: (-item["score"], item["id"]))[:8]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Lookup ontology modeling meta ontology slices.")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--object", dest="object_id")
    group.add_argument("--relation", dest="relation_id")
    group.add_argument("--query", dest="query")
    parser.add_argument("--include", help="Comma separated: properties,functions,relations or all")
    parser.add_argument("--json", action="store_true", help="Print JSON output")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    data = load_ontology()
    include = parse_include(args.include)

    if args.object_id:
        obj = find_object(data, args.object_id)
        if not obj:
            print(f"object not found: {args.object_id}", file=sys.stderr)
            return 2
        payload = {"kind": "object", "object": compact_object(data, obj, include)}
    elif args.relation_id:
        rel = find_relation(data, args.relation_id)
        if not rel:
            print(f"relation not found: {args.relation_id}", file=sys.stderr)
            return 2
        payload = {"kind": "relation", "relation": compact_relation(rel)}
    else:
        payload = {
            "kind": "search",
            "query": args.query,
            "objects": search_objects(data, args.query),
            "relations": search_relations(data, args.query),
        }

    print(json.dumps(payload, ensure_ascii=False, indent=2 if args.json else None))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
