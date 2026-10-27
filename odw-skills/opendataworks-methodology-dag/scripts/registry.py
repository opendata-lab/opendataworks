#!/usr/bin/env python3
"""Loading, parameter coercion and shared JSON output helpers for the registry."""

from __future__ import annotations

import datetime as _dt
import json
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Mapping, Tuple

from methodology_schema import Methodology

SKILL_ROOT = Path(__file__).resolve().parent.parent
REGISTRY_DIR = SKILL_ROOT / "assets" / "registry"

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


class RegistryError(ValueError):
    def __init__(self, message: str, *, error_code: str = "methodology_invalid") -> None:
        super().__init__(message)
        self.error_code = error_code


def print_json(payload: Any) -> None:
    print(json.dumps(payload, ensure_ascii=False, default=str))


def load_registry(directory: Path | None = None) -> Dict[str, Dict[str, Any]]:
    """Load and schema-validate every methodology in the registry directory."""
    root = directory or REGISTRY_DIR
    registry: Dict[str, Dict[str, Any]] = {}
    if not root.is_dir():
        return registry
    for path in sorted(root.glob("*.json")):
        methodology = load_methodology_file(path)
        identifier = methodology["id"]
        if identifier in registry:
            raise RegistryError(f"注册表中存在重复的方法论 id `{identifier}`")
        registry[identifier] = methodology
    return registry


def load_methodology_file(path: Path) -> Dict[str, Any]:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RegistryError(f"{path.name}: 无法读取或解析 JSON: {exc}") from exc
    return validate_schema(raw, source=path.name)


def validate_schema(raw: Mapping[str, Any], *, source: str = "<inline>") -> Dict[str, Any]:
    try:
        model = Methodology.model_validate(raw)
    except Exception as exc:  # pydantic ValidationError
        raise RegistryError(f"{source}: schema 校验失败: {exc}") from exc
    return model.model_dump(mode="json", exclude_none=False)


# --------------------------------------------------------------------------
# Parameter coercion
# --------------------------------------------------------------------------

def coerce_params(specs: List[Mapping[str, Any]], supplied: Mapping[str, Any]) -> Tuple[Dict[str, Any], List[str]]:
    """Coerce and range-check supplied parameters against their declarations.

    Returns the resolved parameter map plus the names of missing required slots,
    so the caller can ask the user for exactly those and nothing more.
    """
    declared = {str(spec["name"]): spec for spec in specs}
    unknown = sorted(set(supplied) - set(declared))
    if unknown:
        raise RegistryError(
            f"未声明的参数: {', '.join(unknown)}；可用参数为 {', '.join(sorted(declared)) or '（无）'}",
            error_code="param_rejected",
        )

    resolved: Dict[str, Any] = {}
    missing: List[str] = []
    for name, spec in declared.items():
        if name in supplied and supplied[name] is not None:
            resolved[name] = _coerce_one(name, spec, supplied[name])
        elif spec.get("default") is not None:
            resolved[name] = _coerce_one(name, spec, spec["default"])
        elif spec.get("required"):
            missing.append(name)
            resolved[name] = None
        else:
            resolved[name] = None
    return resolved, missing


def _coerce_one(name: str, spec: Mapping[str, Any], value: Any) -> Any:
    param_type = str(spec.get("type") or "string")
    try:
        if param_type == "int":
            coerced: Any = int(value)
        elif param_type == "float":
            coerced = float(value)
        elif param_type == "bool":
            coerced = value if isinstance(value, bool) else str(value).strip().lower() in {"1", "true", "yes", "on"}
        elif param_type == "string_list":
            items = value if isinstance(value, (list, tuple)) else [value]
            coerced = [str(item) for item in items]
        elif param_type == "date":
            coerced = str(value).strip()
            if not DATE_RE.match(coerced):
                raise ValueError("日期必须是 YYYY-MM-DD")
            _dt.date.fromisoformat(coerced)
        else:
            coerced = str(value)
    except (TypeError, ValueError) as exc:
        raise RegistryError(f"参数 `{name}` 取值非法: {exc}", error_code="param_rejected") from exc

    values = [str(item) for item in (spec.get("values") or [])]
    if param_type == "enum":
        if str(coerced) not in values:
            raise RegistryError(
                f"参数 `{name}` 取值 `{coerced}` 不在允许范围 {values} 内", error_code="param_rejected"
            )
    elif param_type == "string_list" and values:
        rejected = [item for item in coerced if item not in values]
        if rejected:
            raise RegistryError(
                f"参数 `{name}` 含非法取值 {rejected}，允许范围 {values}", error_code="param_rejected"
            )

    minimum = spec.get("minimum")
    maximum = spec.get("maximum")
    if isinstance(coerced, (int, float)) and not isinstance(coerced, bool):
        if minimum is not None and coerced < minimum:
            raise RegistryError(f"参数 `{name}` 不能小于 {minimum}", error_code="param_rejected")
        if maximum is not None and coerced > maximum:
            raise RegistryError(f"参数 `{name}` 不能大于 {maximum}", error_code="param_rejected")
    max_items = spec.get("max_items")
    if max_items and isinstance(coerced, list) and len(coerced) > int(max_items):
        raise RegistryError(f"参数 `{name}` 最多 {max_items} 个取值", error_code="param_rejected")
    return coerced


def parse_params_argument(raw: str | None) -> Dict[str, Any]:
    text = str(raw or "").strip()
    if not text:
        return {}
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as exc:
        raise RegistryError(f"--params 必须是合法 JSON 对象: {exc}", error_code="param_rejected") from exc
    if not isinstance(parsed, dict):
        raise RegistryError("--params 必须是 JSON 对象", error_code="param_rejected")
    return parsed


def fail(payload: Mapping[str, Any]) -> None:
    print_json(payload)
    sys.exit(0)
