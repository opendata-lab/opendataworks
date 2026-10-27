"""Injection safety and predicate-DSL behaviour of the binding layer."""

import pytest

from binding import (
    BindingError,
    bind_sql,
    compile_predicate,
    evaluate,
    render_checked_value,
    render_fragment,
)


# -- checked values ---------------------------------------------------------

def test_checked_value_escapes_quotes_so_it_cannot_close_the_literal():
    rendered = render_checked_value("o'brien", expr="params.owner")
    assert rendered == "'o''brien'"


def test_checked_value_escapes_a_classic_injection_payload():
    payload = "' OR 1=1 --"
    sql = bind_sql("SELECT * FROM t WHERE owner = {{ params.owner }}", {"params": {"owner": payload}})
    assert sql == "SELECT * FROM t WHERE owner = ''' OR 1=1 --'"
    # The payload stayed inside one quoted literal: no unbalanced quote remains.
    assert sql.count("'") % 2 == 0


def test_checked_value_expands_scalar_array_into_an_in_list():
    sql = bind_sql(
        "SELECT * FROM t WHERE layer IN ({{ pluck(dep.rows, 'layer') }})",
        {"dep": {"rows": [{"layer": "ODS"}, {"layer": "DWD"}]}},
    )
    assert sql == "SELECT * FROM t WHERE layer IN ('ODS', 'DWD')"


def test_checked_value_rejects_a_nested_structure():
    with pytest.raises(BindingError, match="数组元素必须是标量"):
        render_checked_value([{"a": 1}], expr="dep.rows")


def test_checked_value_rejects_a_mapping():
    with pytest.raises(BindingError, match="必须求值为标量或标量数组"):
        render_checked_value({"a": 1}, expr="dep")


def test_checked_value_rejects_an_empty_array_instead_of_emitting_in_nothing():
    with pytest.raises(BindingError, match="空列表"):
        render_checked_value([], expr="dep.rows")


# -- identifier fragments ---------------------------------------------------

@pytest.mark.parametrize(
    "payload",
    ["1; DROP TABLE users", "layer; --", "layer'", "layer OR 1=1", "", "  ", "layer col"],
)
def test_fragment_rejects_anything_that_is_not_a_bare_identifier(payload):
    with pytest.raises(BindingError):
        render_fragment(payload, expr="params.dim")


def test_fragment_accepts_a_qualified_column_name():
    assert render_fragment("t.layer", expr="params.dim") == "t.layer"


def test_fragment_accepts_a_declared_enum_value_that_is_not_an_identifier():
    assert render_fragment("count(*)", expr="params.agg", allowed_values=["count(*)"]) == "count(*)"


# -- predicate DSL ----------------------------------------------------------

def test_predicate_with_a_null_value_is_dropped_entirely():
    fragment = compile_predicate({"op": "eq", "field": "layer", "value": "$params.layer"}, {"params": {"layer": None}})
    assert fragment == ""


def test_predicate_clause_disappears_from_the_template_when_the_param_is_absent():
    sql = bind_sql(
        "SELECT * FROM t WHERE deleted = 0 {{? layer_filter }} GROUP BY layer",
        {"params": {"layer": None}},
        predicates={"layer_filter": {"op": "eq", "field": "layer", "value": "$params.layer"}},
    )
    assert "layer_filter" not in sql
    assert "AND" not in sql
    assert sql.startswith("SELECT * FROM t WHERE deleted = 0")


def test_predicate_clause_is_emitted_when_the_param_is_supplied():
    sql = bind_sql(
        "SELECT * FROM t WHERE deleted = 0 {{? layer_filter }}",
        {"params": {"layer": "ODS"}},
        predicates={"layer_filter": {"op": "eq", "field": "layer", "value": "$params.layer"}},
    )
    assert sql.endswith("AND layer = 'ODS'")


def test_composite_predicate_drops_only_its_empty_sub_clauses():
    fragment = compile_predicate(
        {
            "op": "and",
            "clauses": [
                {"op": "eq", "field": "layer", "value": "$params.layer"},
                {"op": "gte", "field": "row_count", "value": "$params.floor"},
            ],
        },
        {"params": {"layer": None, "floor": 10}},
    )
    assert fragment == "row_count >= 10"


def test_in_predicate_drops_an_empty_upstream_list():
    fragment = compile_predicate(
        {"op": "in", "field": "layer", "values": "$pluck(dep.rows, 'layer')"},
        {"dep": {"rows": []}},
    )
    assert fragment == ""


def test_in_predicate_escapes_each_element():
    fragment = compile_predicate(
        {"op": "in", "field": "owner", "values": ["a'b", "c"]},
        {},
    )
    assert fragment == "owner IN ('a''b', 'c')"


def test_predicate_field_is_restricted_to_an_identifier():
    with pytest.raises(BindingError):
        compile_predicate({"op": "eq", "field": "layer = 1 OR 1", "value": "x"}, {})


# -- expression evaluator ---------------------------------------------------

@pytest.mark.parametrize(
    "expression",
    [
        "__import__('os').system('id')",
        "params.__class__",
        "(lambda: 1)()",
        "[x for x in [1]]",
        "open('/etc/passwd')",
        "params.a.__globals__",
    ],
)
def test_evaluator_rejects_every_escape_attempt(expression):
    with pytest.raises(BindingError):
        evaluate(expression, {"params": {"a": 1}})


def test_evaluator_supports_the_documented_surface():
    context = {"params": {"days": 30}, "row": {"a": 4, "b": 2}}
    assert evaluate("params.days * 2", context) == 60
    assert evaluate("round(float(row.a) / float(row.b), 2)", context) == 2.0
    assert evaluate("row.a if row.b else 0", context) == 4
    assert evaluate("params.days == None", context) is False


def test_evaluator_reports_an_unknown_field_rather_than_returning_none():
    with pytest.raises(BindingError, match="找不到字段"):
        evaluate("params.missing", {"params": {}})


# -- template hygiene -------------------------------------------------------

def test_unclosed_placeholder_leaves_residue_and_is_rejected():
    with pytest.raises(BindingError, match="无法识别"):
        bind_sql("SELECT {{ params.a } FROM t", {"params": {"a": 1}})


def test_a_malformed_placeholder_never_reaches_the_output():
    # ``{{{`` parses as a checked value whose body is not a valid expression;
    # either way the template is rejected rather than emitted half-bound.
    with pytest.raises(BindingError):
        bind_sql("SELECT {{{ oops }} FROM t", {})


def test_undeclared_predicate_reference_is_rejected():
    with pytest.raises(BindingError, match="未在本节点 predicates 中声明"):
        bind_sql("SELECT 1 {{? nope }}", {}, predicates={})
