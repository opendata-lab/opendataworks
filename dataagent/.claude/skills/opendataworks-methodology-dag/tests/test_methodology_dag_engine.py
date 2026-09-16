"""The four evaluation properties the declarative model is supposed to buy.

These are asserted directly rather than inferred from "the run succeeded":
build only what is needed, build it at most once, independent branches run
concurrently, and a malformed graph is rejected before anything executes.
"""

import time

import pytest

import engine
from engine import MethodologyEngine, MethodologyError, run_methodology


def _graph(nodes, target, **extra):
    return {"id": "fixture", "version": "1.0.0", "target": target, "nodes": nodes, **extra}


def _counting_mock(counter, name, rows, delay=0.0):
    def _supply():
        counter[name] = counter.get(name, 0) + 1
        if delay:
            time.sleep(delay)
        return {"rows": rows}

    return _supply


# -- build it at most once --------------------------------------------------

def test_a_shared_dependency_executes_once_however_many_paths_force_it():
    counter = {}
    methodology = _graph(
        {
            "current": {"type": "sql", "database": "d", "sql": "SELECT 1"},
            "previous": {"type": "sql", "database": "d", "dependencies": ["current"], "sql": "SELECT 2"},
            "growth": {
                "type": "sqlite",
                "dependencies": ["current", "previous"],
                "sql": "SELECT c.k AS k, c.n - p.n AS delta FROM current c JOIN previous p ON c.k = p.k",
            },
        },
        "growth",
    )
    mock = {
        "current": _counting_mock(counter, "current", [{"k": "a", "n": 10}]),
        "previous": _counting_mock(counter, "previous", [{"k": "a", "n": 4}]),
    }

    result, trace = run_methodology(methodology, {}, mock=mock)

    assert counter["current"] == 1, "current is forced by two dependents but must run once"
    assert result.rows == [{"k": "a", "delta": 6}]
    assert [entry["name"] for entry in trace].count("current") == 1


# -- build only what is needed ----------------------------------------------

def test_the_untaken_conditional_branch_never_executes():
    def explode():
        raise AssertionError("the untaken branch was executed")

    methodology = _graph(
        {
            "pick": {
                "type": "conditional",
                "when": "params.scoped == None",
                "then_node": "all_rows",
                "else_node": "scoped_rows",
            },
            "all_rows": {"type": "literal", "rows": [{"k": "all"}]},
            "scoped_rows": {"type": "sql", "database": "d", "sql": "SELECT 1"},
        },
        "pick",
        params=[{"name": "scoped", "type": "string", "description": "x"}],
    )

    result, trace = run_methodology(methodology, {"scoped": None}, mock={"scoped_rows": explode})

    assert result.rows == [{"k": "all"}]
    assert "scoped_rows" not in [entry["name"] for entry in trace]
    conditional = next(entry for entry in trace if entry["name"] == "pick")
    assert conditional["branch"] == "all_rows"
    assert conditional["pruned_branch"] == "scoped_rows"


def test_the_other_branch_is_taken_when_the_predicate_flips():
    def explode():
        raise AssertionError("the untaken branch was executed")

    methodology = _graph(
        {
            "pick": {
                "type": "conditional",
                "when": "params.scoped == None",
                "then_node": "all_rows",
                "else_node": "scoped_rows",
            },
            "all_rows": {"type": "sql", "database": "d", "sql": "SELECT 1"},
            "scoped_rows": {"type": "literal", "rows": [{"k": "scoped"}]},
        },
        "pick",
        params=[{"name": "scoped", "type": "string", "description": "x"}],
    )

    result, trace = run_methodology(methodology, {"scoped": "x"}, mock={"all_rows": explode})

    assert result.rows == [{"k": "scoped"}]
    assert "all_rows" not in [entry["name"] for entry in trace]


def test_a_node_unreachable_from_the_target_never_executes():
    def explode():
        raise AssertionError("an unreachable node was executed")

    methodology = _graph(
        {
            "wanted": {"type": "literal", "rows": [{"k": 1}]},
            "orphan": {"type": "sql", "database": "d", "sql": "SELECT 1"},
        },
        "wanted",
    )

    result, trace = run_methodology(methodology, {}, mock={"orphan": explode})

    assert result.rows == [{"k": 1}]
    assert [entry["name"] for entry in trace] == ["wanted"]


# -- parallelism is a consequence of the declared structure -----------------

def test_independent_dependencies_are_forced_concurrently():
    counter = {}
    delay = 0.25
    methodology = _graph(
        {
            "a": {"type": "sql", "database": "d", "sql": "SELECT 1"},
            "b": {"type": "sql", "database": "d", "sql": "SELECT 2"},
            "c": {"type": "sql", "database": "d", "sql": "SELECT 3"},
            "merged": {"type": "sqlite", "dependencies": ["a", "b", "c"], "sql": "SELECT * FROM a"},
        },
        "merged",
    )
    mock = {
        name: _counting_mock(counter, name, [{"k": name}], delay=delay)
        for name in ("a", "b", "c")
    }

    started = time.monotonic()
    run_methodology(methodology, {}, mock=mock)
    elapsed = time.monotonic() - started

    assert counter == {"a": 1, "b": 1, "c": 1}
    assert elapsed < delay * 2, f"three {delay}s dependencies took {elapsed:.2f}s; they did not overlap"


# -- malformed graphs fail before anything runs -----------------------------

def test_a_missing_target_is_rejected():
    methodology = _graph({"a": {"type": "literal", "rows": []}}, "nope")
    with pytest.raises(MethodologyError) as excinfo:
        MethodologyEngine(methodology, {}).run()
    assert excinfo.value.error_code == "methodology_invalid"


def test_a_call_cycle_is_rejected_with_the_chain_in_the_message():
    left = _graph({"c": {"type": "call", "methodology_id": "right"}}, "c")
    left["id"] = "left"
    right = _graph({"c": {"type": "call", "methodology_id": "left"}}, "c")
    right["id"] = "right"

    with pytest.raises(MethodologyError) as excinfo:
        MethodologyEngine(left, {}, registry={"left": left, "right": right}).run()
    assert "调用成环" in str(excinfo.value)
    assert "left -> right -> left" in str(excinfo.value)


def test_calling_an_unregistered_methodology_is_reported_as_not_found():
    methodology = _graph({"c": {"type": "call", "methodology_id": "absent"}}, "c")
    with pytest.raises(MethodologyError) as excinfo:
        MethodologyEngine(methodology, {}, registry={}).run()
    assert excinfo.value.error_code == "methodology_not_found"


# -- timeouts ---------------------------------------------------------------

def test_an_exhausted_total_budget_stops_the_run():
    methodology = _graph({"a": {"type": "sql", "database": "d", "sql": "SELECT 1"}}, "a")
    with pytest.raises(MethodologyError) as excinfo:
        MethodologyEngine(methodology, {}, total_timeout=-1).run()
    assert excinfo.value.error_code == "methodology_timeout"


# -- node behaviour ---------------------------------------------------------

def test_platform_tools_is_found_as_a_sibling_without_any_environment_variable():
    """The skill locates its neighbour from its own path, needing nothing from the host."""
    root = engine._resolve_platform_skill_root()

    assert root.name == engine.PLATFORM_TOOLS_FOLDER
    assert (root / "scripts" / "run_sql.py").is_file()


def test_platform_tools_resolution_reads_no_environment_variable(monkeypatch):
    """定位只依赖自身路径。

    此前 DATAAGENT_PLATFORM_SKILL_ROOT 可以覆盖同级解析；该变量已随调用锚点
    统一移除，因为它恒等于同级目录。这里让任何 os.getenv 调用都爆炸，确保解析
    路径上不会再悄悄长出一个环境变量依赖。
    """
    def _explode(*args, **kwargs):
        raise AssertionError("platform-tools 定位不应读取任何环境变量")

    monkeypatch.setattr(engine.os, "getenv", _explode)

    assert engine._platform_skill_root().name == engine.PLATFORM_TOOLS_FOLDER


def test_sql_node_without_platform_tools_anywhere_reports_a_precise_cause(monkeypatch):
    monkeypatch.setattr(engine, "PLATFORM_TOOLS_FOLDER", "absent-platform-tools")
    methodology = _graph({"a": {"type": "sql", "database": "d", "sql": "SELECT 1"}}, "a")

    with pytest.raises(MethodologyError) as excinfo:
        MethodologyEngine(methodology, {}).run()

    assert excinfo.value.error_code == "platform_tools_unavailable"
    assert "absent-platform-tools" in str(excinfo.value)


def test_transform_filters_derives_and_sorts():
    methodology = _graph(
        {
            "source": {
                "type": "literal",
                "rows": [
                    {"day": "2026-07-02", "total": 10, "bad": 2},
                    {"day": "2026-07-01", "total": 0, "bad": 0},
                    {"day": "2026-07-03", "total": 4, "bad": 1},
                ],
            },
            "shaped": {
                "type": "transform",
                "dependencies": ["source"],
                "filter": "row.total > 0",
                "derive": {"rate": "round(float(row.bad) / float(row.total), 2)"},
                "select": ["day", "rate"],
                "sort": [{"field": "day", "order": "desc"}],
            },
        },
        "shaped",
    )

    result, _ = run_methodology(methodology, {})

    assert result.columns == ["day", "rate"]
    assert result.rows == [{"day": "2026-07-03", "rate": 0.25}, {"day": "2026-07-02", "rate": 0.2}]


def test_sqlite_node_joins_results_that_came_from_different_sources():
    methodology = _graph(
        {
            "from_mysql": {"type": "literal", "rows": [{"k": "a", "left": 1}, {"k": "b", "left": 2}]},
            "from_doris": {"type": "literal", "rows": [{"k": "a", "right": 10}]},
            "joined": {
                "type": "sqlite",
                "dependencies": ["from_mysql", "from_doris"],
                "sql": (
                    "SELECT m.k AS k, m.left AS l, COALESCE(d.right, 0) AS r "
                    "FROM from_mysql m LEFT JOIN from_doris d ON m.k = d.k ORDER BY m.k"
                ),
            },
        },
        "joined",
    )

    result, _ = run_methodology(methodology, {})

    assert result.rows == [{"k": "a", "l": 1, "r": 10}, {"k": "b", "l": 2, "r": 0}]


def test_a_broken_sqlite_statement_is_attributed_to_the_methodology_not_to_a_retry():
    methodology = _graph(
        {
            "source": {"type": "literal", "rows": [{"k": 1}]},
            "bad": {"type": "sqlite", "dependencies": ["source"], "sql": "SELECT nope FROM source"},
        },
        "bad",
    )
    with pytest.raises(MethodologyError) as excinfo:
        MethodologyEngine(methodology, {}).run()
    assert excinfo.value.failure_attribution == ["invalid_sql"]
    assert excinfo.value.retryable is False
