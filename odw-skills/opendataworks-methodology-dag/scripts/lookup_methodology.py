#!/usr/bin/env python3
"""Search the methodology registry. Never executes anything.

Returns the semantic surface an assistant needs to decide whether a registered
methodology answers the question — intent, caliber, parameter slots, output
fields — so the decision is made before any query runs.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import Any, Dict, List, Mapping

from registry import REGISTRY_DIR, RegistryError, load_registry, print_json

TOKEN_RE = re.compile(r"[A-Za-z0-9_]+")
# Chinese text has no spaces, so keyword matching falls back to character bigrams.
BIGRAM_MIN_LENGTH = 2


def _tokens(text: str) -> List[str]:
    lowered = str(text or "").lower()
    tokens = TOKEN_RE.findall(lowered)
    cjk = re.sub(r"[A-Za-z0-9_\s]+", "", lowered)
    tokens.extend(cjk[index : index + BIGRAM_MIN_LENGTH] for index in range(max(0, len(cjk) - 1)))
    return [token for token in tokens if token]


def _haystack(methodology: Mapping[str, Any]) -> str:
    parts = [
        methodology.get("id"),
        methodology.get("name_zh"),
        methodology.get("name_en"),
        methodology.get("intent"),
        methodology.get("caliber"),
        *(methodology.get("synonyms") or []),
        *(methodology.get("output_fields") or []),
    ]
    return " ".join(str(part) for part in parts if part)


def _summarize(methodology: Mapping[str, Any], score: float | None = None) -> Dict[str, Any]:
    payload: Dict[str, Any] = {
        "id": methodology.get("id"),
        "version": methodology.get("version"),
        "name_zh": methodology.get("name_zh"),
        "intent": methodology.get("intent"),
        "caliber": methodology.get("caliber"),
        "owner": methodology.get("owner"),
        "synonyms": methodology.get("synonyms") or [],
        "output_fields": methodology.get("output_fields") or [],
        "ontology_ref": methodology.get("ontology_ref"),
        "params": [
            {
                "name": spec.get("name"),
                "type": spec.get("type"),
                "required": bool(spec.get("required")),
                "default": spec.get("default"),
                "values": spec.get("values") or [],
                "description": spec.get("description"),
            }
            for spec in (methodology.get("params") or [])
        ],
        "node_count": len(methodology.get("nodes") or {}),
    }
    if score is not None:
        payload["score"] = round(score, 4)
    return payload


def search(registry: Mapping[str, Mapping[str, Any]], query: str, limit: int) -> List[Dict[str, Any]]:
    query_tokens = set(_tokens(query))
    if not query_tokens:
        return [_summarize(item) for item in list(registry.values())[:limit]]
    scored: List[tuple[float, Dict[str, Any]]] = []
    for methodology in registry.values():
        haystack_tokens = set(_tokens(_haystack(methodology)))
        overlap = query_tokens & haystack_tokens
        if not overlap:
            continue
        scored.append((len(overlap) / len(query_tokens), _summarize(methodology, len(overlap) / len(query_tokens))))
    scored.sort(key=lambda item: item[0], reverse=True)
    return [payload for _, payload in scored[:limit]]


def main() -> int:
    parser = argparse.ArgumentParser(description="检索已注册的分析方法论，不执行任何查询")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--query", help="用户问题或关键词")
    group.add_argument("--id", help="按 id 精确获取")
    group.add_argument("--list", action="store_true", help="列出全部方法论")
    parser.add_argument("--registry-dir", default=str(REGISTRY_DIR))
    parser.add_argument("--limit", type=int, default=5)
    args = parser.parse_args()

    try:
        registry = load_registry(Path(args.registry_dir))
    except RegistryError as exc:
        print_json(
            {
                "kind": "methodology_lookup",
                "matched": 0,
                "results": [],
                "error": str(exc),
                "stop_reason": "注册表加载失败；请回落到平台工具的常规问数链路。",
            }
        )
        return 1

    if args.id:
        methodology = registry.get(args.id)
        results = [_summarize(methodology)] if methodology else []
    elif args.list:
        results = [_summarize(item) for item in registry.values()]
    else:
        results = search(registry, args.query, args.limit)

    print_json(
        {
            "kind": "methodology_lookup",
            "tool_label": "方法论检索",
            "query": args.query or args.id or "",
            "matched": len(results),
            "results": results,
            "stop_reason": (
                ""
                if results
                else "没有命中已注册方法论；请回落到平台工具的常规问数链路（语义确认 → SQL 生成 → odw_query_readonly）。"
            ),
        }
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
