#!/usr/bin/env python3
"""Static validation of methodology DAGs.

Because a methodology is data rather than code, the engine can check it before
anything runs: every referenced dependency exists, the target exists, the
dependency graph is acyclic (with any cycle reported as a concrete path), every
template placeholder parses and only references declared names, and every
``call`` resolves without forming a cycle in the call graph. Errors that would be
runtime exceptions in hand-written glue become load-time rejections.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Mapping, Sequence

from binding import (
    BindingError,
    assert_template_markers_understood,
    iter_template_expressions,
    iter_template_predicates,
    parse_expression,
)
from engine import _platform_skill_root
from registry import (
    REGISTRY_DIR,
    RegistryError,
    load_methodology_file,
    load_registry,
    print_json,
    validate_schema,
)

SQL_NODE_TYPES = {"sql", "sqlite"}
TEMPLATE_NODE_TYPES = {"sql", "sqlite"}


class Report:
    def __init__(self, identifier: str) -> None:
        self.identifier = identifier
        self.errors: List[str] = []
        self.warnings: List[str] = []

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)

    @property
    def ok(self) -> bool:
        return not self.errors

    def payload(self) -> Dict[str, Any]:
        return {
            "id": self.identifier,
            "valid": self.ok,
            "errors": list(self.errors),
            "warnings": list(self.warnings),
        }


def _declared_names(methodology: Mapping[str, Any]) -> set[str]:
    names = {"params", "row"}
    names.update(methodology.get("nodes") or {})
    return names


def _expression_roots(expression: str) -> set[str]:
    import ast

    tree = parse_expression(expression)
    roots: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Name):
            roots.add(node.id)
    return roots


def _check_expression(
    report: Report,
    where: str,
    expression: str,
    allowed_roots: set[str],
    *,
    param_names: set[str],
) -> None:
    try:
        roots = _expression_roots(expression)
    except BindingError as exc:
        report.error(f"{where}: {exc}")
        return
    from binding import ALLOWED_FUNCTIONS

    for root in roots:
        if root in ALLOWED_FUNCTIONS:
            continue
        if root not in allowed_roots:
            report.error(
                f"{where}: 表达式 `{expression}` 引用了未知的名字 `{root}`；"
                f"只能引用 params、本节点依赖或 row"
            )
    if "params" in roots:
        import ast

        tree = parse_expression(expression)
        for node in ast.walk(tree):
            if (
                isinstance(node, ast.Attribute)
                and isinstance(node.value, ast.Name)
                and node.value.id == "params"
                and node.attr not in param_names
            ):
                report.error(f"{where}: 表达式引用了未声明的参数 `params.{node.attr}`")


def _detect_cycle(graph: Mapping[str, Sequence[str]]) -> List[str] | None:
    """DFS for a cycle, returning the concrete path rather than a bare boolean."""
    WHITE, GREY, BLACK = 0, 1, 2
    color: Dict[str, int] = {node: WHITE for node in graph}
    stack: List[str] = []

    def visit(node: str) -> List[str] | None:
        color[node] = GREY
        stack.append(node)
        for neighbour in graph.get(node, ()):
            if neighbour not in color:
                continue
            if color[neighbour] == GREY:
                start = stack.index(neighbour)
                return [*stack[start:], neighbour]
            if color[neighbour] == WHITE:
                found = visit(neighbour)
                if found:
                    return found
        color[node] = BLACK
        stack.pop()
        return None

    for node in graph:
        if color[node] == WHITE:
            found = visit(node)
            if found:
                return found
    return None


def _reachable(graph: Mapping[str, Sequence[str]], start: str) -> set[str]:
    seen: set[str] = set()
    pending = [start]
    while pending:
        current = pending.pop()
        if current in seen or current not in graph:
            continue
        seen.add(current)
        pending.extend(graph.get(current, ()))
    return seen


def _stub_value(spec: Mapping[str, Any]) -> Any:
    param_type = str(spec.get("type") or "string")
    if spec.get("default") is not None:
        return spec["default"]
    if spec.get("values"):
        return spec["values"][0]
    return {
        "int": 1,
        "float": 1.0,
        "bool": True,
        "date": "2026-01-01",
        "string_list": ["stub"],
    }.get(param_type, "stub")


def _validate_sql_with_platform_tools(sql: str) -> tuple[bool, str]:
    """Run the platform-tools SQL validator when it is reachable."""
    script = _platform_skill_root() / "scripts" / "validate_sql.py"
    if not script.is_file():
        return True, "skipped: 找不到 opendataworks-platform-tools 的 validate_sql.py"
    python_bin = str(os.getenv("DATAAGENT_PYTHON_BIN") or "").strip() or sys.executable
    try:
        completed = subprocess.run(  # noqa: S603 - fixed argv, no shell
            [python_bin, str(script), "--json", sql],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return True, f"skipped: 调用 validate_sql.py 失败 {exc}"
    import json as _json

    try:
        payload = _json.loads((completed.stdout or "").strip())
    except _json.JSONDecodeError:
        return True, "skipped: validate_sql.py 未返回 JSON"
    if payload.get("valid") is False:
        return False, "; ".join(str(item) for item in (payload.get("errors") or []))
    return True, ""


def validate_methodology(
    methodology: Mapping[str, Any],
    *,
    registry: Mapping[str, Mapping[str, Any]] | None = None,
    check_sql: bool = False,
) -> Report:
    report = Report(str(methodology.get("id") or "<unknown>"))
    nodes: Dict[str, Mapping[str, Any]] = dict(methodology.get("nodes") or {})
    target = str(methodology.get("target") or "")
    param_specs = list(methodology.get("params") or [])
    param_names = {str(spec["name"]) for spec in param_specs}
    registry = dict(registry or {})

    if target not in nodes:
        report.error(f"target `{target}` 不是已定义的节点")

    # Dependency graph, with conditional branches as soft edges.
    hard_graph: Dict[str, List[str]] = {}
    full_graph: Dict[str, List[str]] = {}
    for name, node in nodes.items():
        dependencies = [str(item) for item in (node.get("dependencies") or [])]
        for dependency in dependencies:
            if dependency not in nodes:
                report.error(f"节点 `{name}` 依赖了不存在的节点 `{dependency}`")
            if dependency == name:
                report.error(f"节点 `{name}` 依赖了自身")
        hard_graph[name] = dependencies
        branches: List[str] = []
        if str(node.get("type")) == "conditional":
            for key in ("then_node", "else_node"):
                branch = str(node.get(key) or "")
                if branch not in nodes:
                    report.error(f"条件节点 `{name}` 的 {key} `{branch}` 不是已定义的节点")
                else:
                    branches.append(branch)
        full_graph[name] = [*dependencies, *branches]

    cycle = _detect_cycle(full_graph)
    if cycle:
        report.error("依赖图存在环: " + " -> ".join(cycle))

    if target in nodes and not cycle:
        reachable = _reachable(full_graph, target)
        for name in sorted(set(nodes) - reachable):
            report.warn(f"节点 `{name}` 从 target 不可达，永远不会执行")

    # Per-node checks.
    for name, node in nodes.items():
        node_type = str(node.get("type") or "")
        allowed_roots = {"params", *[str(item) for item in (node.get("dependencies") or [])]}
        if node_type == "transform":
            allowed_roots.add("row")

        if node_type in TEMPLATE_NODE_TYPES:
            template = str(node.get("sql") or "")
            try:
                assert_template_markers_understood(template)
            except BindingError as exc:
                report.error(f"节点 `{name}`: {exc}")
            for expression in iter_template_expressions(template):
                _check_expression(report, f"节点 `{name}`", expression, allowed_roots, param_names=param_names)
            declared_predicates = set(node.get("predicates") or {})
            for referenced in iter_template_predicates(template):
                if referenced not in declared_predicates:
                    report.error(f"节点 `{name}` 引用了未声明的谓词片段 `{referenced}`")
            for unused in sorted(declared_predicates - set(iter_template_predicates(template))):
                report.warn(f"节点 `{name}` 声明了未被模板引用的谓词 `{unused}`")
            for predicate_name, predicate in (node.get("predicates") or {}).items():
                _check_predicate(report, f"节点 `{name}`.谓词 `{predicate_name}`", predicate, allowed_roots, param_names)

        if node_type == "sqlite":
            for dependency in node.get("dependencies") or []:
                if f'"{dependency}"' not in str(node.get("sql") or "") and dependency not in str(node.get("sql") or ""):
                    report.warn(f"节点 `{name}` 的 SQL 未引用依赖表 `{dependency}`")

        if node_type == "transform":
            for label, expression in [("filter", node.get("filter")), *[(f"derive.{k}", v) for k, v in (node.get("derive") or {}).items()]]:
                if expression:
                    _check_expression(report, f"节点 `{name}`.{label}", str(expression), allowed_roots, param_names=param_names)

        if node_type == "conditional":
            _check_expression(report, f"节点 `{name}`.when", str(node.get("when") or ""), allowed_roots, param_names=param_names)

        if node_type == "call":
            target_id = str(node.get("methodology_id") or "")
            if registry and target_id not in registry:
                report.error(f"节点 `{name}` 调用的方法论 `{target_id}` 不在注册表中")
            if target_id == methodology.get("id"):
                report.error(f"节点 `{name}` 调用了方法论自身，调用图必须无环")
            for key, expression in (node.get("params") or {}).items():
                _check_expression(report, f"节点 `{name}`.params.{key}", str(expression), allowed_roots, param_names=param_names)

    # Call graph acyclicity across the whole registry.
    if registry:
        call_graph = {
            identifier: [
                str(node.get("methodology_id"))
                for node in (entry.get("nodes") or {}).values()
                if str(node.get("type")) == "call"
            ]
            for identifier, entry in registry.items()
        }
        call_graph.setdefault(str(methodology.get("id")), [])
        call_cycle = _detect_cycle(call_graph)
        if call_cycle:
            report.error("方法论调用图存在环: " + " -> ".join(call_cycle))

    # Parameter declarations.
    for spec in param_specs:
        if str(spec.get("type")) == "enum" and not spec.get("values"):
            report.error(f"参数 `{spec.get('name')}` 是枚举类型但没有声明 values")

    # Bind with stub values and hand the SQL to the platform validator.
    if check_sql and report.ok:
        _check_bound_sql(report, methodology, nodes, param_specs)

    return report


def _check_predicate(
    report: Report,
    where: str,
    predicate: Mapping[str, Any],
    allowed_roots: set[str],
    param_names: set[str],
) -> None:
    for key in ("value",):
        raw = predicate.get(key)
        if isinstance(raw, str) and raw.startswith("$"):
            _check_expression(report, where, raw[1:], allowed_roots, param_names=param_names)
    values = predicate.get("values")
    if isinstance(values, str) and values.startswith("$"):
        _check_expression(report, where, values[1:], allowed_roots, param_names=param_names)
    elif isinstance(values, list):
        for item in values:
            if isinstance(item, str) and item.startswith("$"):
                _check_expression(report, where, item[1:], allowed_roots, param_names=param_names)
    for clause in predicate.get("clauses") or []:
        _check_predicate(report, where, clause, allowed_roots, param_names)


def _check_bound_sql(
    report: Report,
    methodology: Mapping[str, Any],
    nodes: Mapping[str, Mapping[str, Any]],
    param_specs: Sequence[Mapping[str, Any]],
) -> None:
    """Bind each SQL template against stub values so the SQL itself is checked."""
    from binding import bind_sql

    stub_params = {str(spec["name"]): _stub_value(spec) for spec in param_specs}
    fragment_values: List[str] = []
    for spec in param_specs:
        fragment_values.extend(str(value) for value in (spec.get("values") or []))

    for name, node in nodes.items():
        if str(node.get("type")) != "sql":
            continue
        context: Dict[str, Any] = {"params": stub_params}
        for dependency in node.get("dependencies") or []:
            context[str(dependency)] = {"columns": ["stub"], "rows": [{"stub": "stub"}], "row_count": 1}
        try:
            bound = bind_sql(
                str(node.get("sql") or ""),
                context,
                predicates=dict(node.get("predicates") or {}),
                fragment_allowed_values=fragment_values,
            )
        except BindingError as exc:
            report.error(f"节点 `{name}` 用桩参数绑定失败: {exc}")
            continue
        valid, detail = _validate_sql_with_platform_tools(bound)
        if not valid:
            report.error(f"节点 `{name}` 的 SQL 未通过 validate_sql.py: {detail}")
        elif detail.startswith("skipped"):
            report.warn(f"节点 `{name}` 的 SQL 校验被跳过（{detail}）")


def main() -> int:
    parser = argparse.ArgumentParser(description="校验方法论 DAG 工件")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true", help="校验整个注册表")
    group.add_argument("--path", help="校验单个方法论 JSON 文件")
    group.add_argument("--id", help="校验注册表中的一个方法论")
    parser.add_argument("--registry-dir", default=str(REGISTRY_DIR))
    parser.add_argument("--check-sql", action="store_true", help="额外用桩参数绑定后调用 validate_sql.py")
    args = parser.parse_args()

    try:
        registry = load_registry(Path(args.registry_dir))
    except RegistryError as exc:
        print_json({"kind": "methodology_validation", "valid": False, "errors": [str(exc)]})
        return 1

    if args.path:
        try:
            targets = [load_methodology_file(Path(args.path))]
        except RegistryError as exc:
            print_json({"kind": "methodology_validation", "valid": False, "errors": [str(exc)]})
            return 1
    elif args.id:
        if args.id not in registry:
            print_json(
                {"kind": "methodology_validation", "valid": False, "errors": [f"注册表中没有 `{args.id}`"]}
            )
            return 1
        targets = [registry[args.id]]
    else:
        targets = list(registry.values())

    reports = [
        validate_methodology(methodology, registry=registry, check_sql=args.check_sql) for methodology in targets
    ]
    payload = {
        "kind": "methodology_validation",
        "valid": all(report.ok for report in reports),
        "checked": len(reports),
        "results": [report.payload() for report in reports],
    }
    print_json(payload)
    return 0 if payload["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())


# Re-exported for callers that only need schema validation.
__all__ = ["Report", "validate_methodology", "validate_schema", "main"]
