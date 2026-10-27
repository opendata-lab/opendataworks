"""Static validation: what would be a runtime exception becomes a load-time reject."""

import pytest

from registry import RegistryError, validate_schema
from validate_methodology import validate_methodology


def _graph(nodes, target, **extra):
    base = {
        "id": "fixture",
        "version": "1.0.0",
        "name_zh": "夹具",
        "intent": "测试用",
        "caliber": "测试口径",
        "owner": "test",
        "target": target,
        "nodes": nodes,
    }
    base.update(extra)
    return base


def _errors(methodology, **kwargs):
    return validate_methodology(methodology, **kwargs).errors


# -- graph shape ------------------------------------------------------------

def test_missing_target_is_rejected():
    errors = _errors(_graph({"a": {"type": "literal", "rows": []}}, "nope"))
    assert any("target" in error for error in errors)


def test_dependency_on_an_undefined_node_is_rejected():
    errors = _errors(_graph({"a": {"type": "transform", "dependencies": ["ghost"]}}, "a"))
    assert any("ghost" in error for error in errors)


def test_self_dependency_is_rejected():
    errors = _errors(_graph({"a": {"type": "transform", "dependencies": ["a"]}}, "a"))
    assert any("依赖了自身" in error for error in errors)


def test_a_cycle_is_reported_as_a_concrete_path():
    methodology = _graph(
        {
            "a": {"type": "transform", "dependencies": ["b"]},
            "b": {"type": "transform", "dependencies": ["c"]},
            "c": {"type": "transform", "dependencies": ["a"]},
        },
        "a",
    )
    errors = _errors(methodology)
    cycle_errors = [error for error in errors if "环" in error]
    assert cycle_errors, errors
    # The path, not just the fact that one exists.
    assert "->" in cycle_errors[0]
    assert cycle_errors[0].count("->") >= 3


def test_an_unreachable_node_is_a_warning_not_an_error():
    report = validate_methodology(
        _graph(
            {"wanted": {"type": "literal", "rows": []}, "orphan": {"type": "literal", "rows": []}},
            "wanted",
        )
    )
    assert report.ok
    assert any("不可达" in warning for warning in report.warnings)


def test_a_conditional_branch_keeps_its_target_reachable():
    report = validate_methodology(
        _graph(
            {
                "pick": {"type": "conditional", "when": "1 == 1", "then_node": "a", "else_node": "b"},
                "a": {"type": "literal", "rows": []},
                "b": {"type": "literal", "rows": []},
            },
            "pick",
        )
    )
    assert report.ok
    assert not report.warnings


def test_a_conditional_branch_that_does_not_exist_is_rejected():
    errors = _errors(
        _graph(
            {
                "pick": {"type": "conditional", "when": "1 == 1", "then_node": "a", "else_node": "ghost"},
                "a": {"type": "literal", "rows": []},
            },
            "pick",
        )
    )
    assert any("ghost" in error for error in errors)


# -- expressions and parameters --------------------------------------------

def test_an_expression_referencing_an_undeclared_parameter_is_rejected():
    errors = _errors(
        _graph(
            {"a": {"type": "sql", "database": "d", "sql": "SELECT {{ params.ghost }}"}},
            "a",
            params=[{"name": "days", "type": "int", "description": "x"}],
        )
    )
    assert any("params.ghost" in error for error in errors)


def test_an_expression_referencing_a_node_that_is_not_a_dependency_is_rejected():
    errors = _errors(
        _graph(
            {
                "other": {"type": "literal", "rows": []},
                "a": {"type": "sql", "database": "d", "sql": "SELECT {{ other.row_count }}"},
            },
            "a",
        )
    )
    assert any("other" in error for error in errors)


def test_an_expression_that_calls_a_non_whitelisted_function_is_rejected():
    errors = _errors(
        _graph({"a": {"type": "sql", "database": "d", "sql": "SELECT {{ exec('x') }}"}}, "a")
    )
    assert any("白名单" in error for error in errors)


def test_an_enum_parameter_without_values_is_rejected():
    errors = _errors(
        _graph(
            {"a": {"type": "literal", "rows": []}},
            "a",
            params=[{"name": "layer", "type": "enum", "description": "x"}],
        )
    )
    assert any("枚举" in error for error in errors)


def test_an_undeclared_predicate_reference_is_rejected():
    errors = _errors(
        _graph({"a": {"type": "sql", "database": "d", "sql": "SELECT 1 {{? ghost }}"}}, "a")
    )
    assert any("ghost" in error for error in errors)


def test_an_unused_declared_predicate_is_only_a_warning():
    report = validate_methodology(
        _graph(
            {
                "a": {
                    "type": "sql",
                    "database": "d",
                    "sql": "SELECT 1",
                    "predicates": {"unused": {"op": "eq", "field": "x", "value": 1}},
                }
            },
            "a",
        )
    )
    assert report.ok
    assert any("unused" in warning for warning in report.warnings)


# -- call graph -------------------------------------------------------------

def test_calling_an_unregistered_methodology_is_rejected():
    methodology = _graph({"c": {"type": "call", "methodology_id": "absent"}}, "c")
    errors = _errors(methodology, registry={"fixture": methodology})
    assert any("absent" in error for error in errors)


def test_direct_self_call_is_rejected():
    methodology = _graph({"c": {"type": "call", "methodology_id": "fixture"}}, "c")
    errors = _errors(methodology, registry={"fixture": methodology})
    assert any("调用了方法论自身" in error for error in errors)


def test_a_call_cycle_across_the_registry_is_rejected():
    left = _graph({"c": {"type": "call", "methodology_id": "right"}}, "c")
    left["id"] = "left"
    right = _graph({"c": {"type": "call", "methodology_id": "left"}}, "c")
    right["id"] = "right"
    errors = _errors(left, registry={"left": left, "right": right})
    assert any("调用图存在环" in error for error in errors)


# -- schema -----------------------------------------------------------------

def test_schema_rejects_an_unknown_node_type():
    with pytest.raises(RegistryError):
        validate_schema(_graph({"a": {"type": "python", "code": "1"}}, "a"))


def test_schema_rejects_an_extra_field():
    with pytest.raises(RegistryError):
        validate_schema(_graph({"a": {"type": "literal", "rows": [], "oops": 1}}, "a"))


def test_schema_rejects_a_non_semver_version():
    with pytest.raises(RegistryError):
        validate_schema(_graph({"a": {"type": "literal", "rows": []}}, "a", version="1.0"))


def test_schema_rejects_a_transform_with_two_dependencies():
    with pytest.raises(RegistryError):
        validate_schema(
            _graph(
                {
                    "x": {"type": "literal", "rows": []},
                    "y": {"type": "literal", "rows": []},
                    "a": {"type": "transform", "dependencies": ["x", "y"]},
                },
                "a",
            )
        )
