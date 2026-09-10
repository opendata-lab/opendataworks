from __future__ import annotations

import copy
import importlib.util
import json
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
RUNNERS = {
    "builtin": REPO_ROOT / "tools" / "dataagent-evals" / "builtin" / "run.py",
    "deepeval": REPO_ROOT / "tools" / "dataagent-evals" / "deepeval" / "run.py",
    "opik": REPO_ROOT / "tools" / "dataagent-evals" / "opik" / "run.py",
}
DATASET = REPO_ROOT / "tools" / "dataagent-evals" / "dataset" / "examples" / "anonymous-v2.jsonl"
FIXTURE = REPO_ROOT / "tools" / "dataagent-evals" / "dataset" / "fixtures" / "evidence-v2.json"
LOCAL_DATASET = REPO_ROOT / "tools" / "dataagent-evals" / "dataset" / "examples" / "opendataworks-business-knowledge-smoke-v2.jsonl"
GOLDEN_DATASET = REPO_ROOT / "tools" / "dataagent-evals" / "dataset" / "examples" / "opendataworks-golden-v2.jsonl"


def _load(name, path):
    module_name = f"dataagent_contract_{name}"
    spec = importlib.util.spec_from_file_location(module_name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


def _merge(base, patch):
    result = copy.deepcopy(base)
    for key, value in patch.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = _merge(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def test_three_engines_load_the_same_v2_dataset_without_runtime_imports():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    stats = {}
    for name, module in modules.items():
        rows, loaded = module.load_dataset(DATASET)
        assert len(rows) == 1
        stats[name] = (loaded["dataset_hash"], tuple(loaded["case_ids"]))
        source = RUNNERS[name].read_text(encoding="utf-8")
        other_names = set(RUNNERS) - {name}
        assert all(f"dataagent-evals/{other}" not in source and f"from {other}" not in source for other in other_names)
    assert len(set(stats.values())) == 1


def test_three_engines_load_exactly_ten_opendataworks_golden_cases():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    engine_stats = {}
    for engine, module in modules.items():
        cases, stats = module.load_dataset(GOLDEN_DATASET)
        assert len(cases) == 10, engine
        assert all("golden" in set(case["suite_tags"]) for case in cases), engine
        assert sum(
            bool((case.get("expected_result") or {}).get("reference_query"))
            for case in cases
        ) == 8, engine
        assert all(
            bool((case.get("expected_result") or {}).get("reference_query", {}).get("enforce_case_gate"))
            for case in cases
            if (case.get("expected_result") or {}).get("reference_query")
        ), engine
        combined_tags = {
            tag
            for case in cases
            for tag in case.get("suite_tags") or []
        }
        assert {"multi-query", "later-evidence", "rolling-window", "expected-empty"} <= combined_tags, engine
        engine_stats[engine] = {
            "dataset_hash": stats["dataset_hash"],
            "case_ids": stats["case_ids"],
        }
    assert engine_stats["builtin"] == engine_stats["deepeval"] == engine_stats["opik"]


def test_three_engines_extract_named_skills_and_bash_scripts_without_allowlists():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    blocks = [
        {
            "type": "tool_use",
            "tool_name": "Skill",
            "input": {"skill": "sample-domain-capability"},
        },
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {
                "command": '"$PYTHON_BIN" "${CAPABILITY_ROOT}/scripts/inspect_catalog.py" --keyword orders'
            },
        },
    ]

    for engine, module in modules.items():
        assert module._collect_tool_names(blocks) == [
            "Skill",
            "Skill:sample-domain-capability",
            "Bash",
            "Bash:inspect_catalog.py",
        ], engine


def test_three_engines_match_golden_evidence_and_sdk_metrics():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    base_case = json.loads(DATASET.read_text(encoding="utf-8").strip())
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    engine_results = {}
    for engine, module in modules.items():
        scenario_results = []
        for scenario in fixture["scenarios"]:
            case = _merge(base_case, scenario["case_patch"])
            blocks = scenario["blocks"]
            answer = scenario["final_answer"]
            tools = module._collect_tool_names(blocks)
            sqls = module._extract_sql_outputs(blocks, answer)
            checked = module.auto_rule_check(case, final_answer=answer, blocks=blocks, sql_outputs=sqls, tool_names=tools)
            actual = {
                "passed": checked["passed"],
                "sql_count": len(sqls),
                "sql_execution_count": sum(module._is_successful_sql_execution(block) for block in blocks),
            }
            assert actual == scenario["expected"], f"{engine}: {scenario['name']}"
            scenario_results.append(actual)
        assert module._sdk_metrics(fixture["sdk_events"]) == fixture["expected_sdk_metrics"]
        engine_results[engine] = scenario_results
    assert engine_results["builtin"] == engine_results["deepeval"] == engine_results["opik"]


def test_three_engines_scope_empty_results_to_target_query_and_enforce_limits():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    local_cases = [json.loads(line) for line in LOCAL_DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    case = next(item for item in local_cases if item["case_id"] == "ODW_BK_002")
    blocks = [
        {
            "type": "tool_use",
            "tool_name": "Skill",
            "input": {"skill": "opendataworks-business-knowledge"},
            "output": "Launching skill: opendataworks-business-knowledge",
        },
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {"command": '"$DATAAGENT_PYTHON_BIN" "$DATAAGENT_PLATFORM_SKILL_ROOT/scripts/run_sql.py" --sql "SELECT id FROM opendataworks.data_table WHERE 1 = 0"'},
            "output": {"kind": "sql_execution", "sql": "SELECT id FROM opendataworks.data_table WHERE 1 = 0", "rows": [], "row_count": 0, "result_state": "empty_result"},
        },
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {"command": '"$DATAAGENT_PYTHON_BIN" "$DATAAGENT_PLATFORM_SKILL_ROOT/scripts/run_sql.py" --sql "SELECT COUNT(id) AS table_cnt FROM opendataworks.data_table WHERE deleted = 0"'},
            "output": {"kind": "sql_execution", "sql": "SELECT COUNT(id) AS table_cnt FROM opendataworks.data_table WHERE deleted = 0", "rows": [{"table_cnt": 37}], "row_count": 1, "result_state": "success"},
        },
    ]
    for engine, module in modules.items():
        tools = module._collect_tool_names(blocks)
        sqls = module._extract_sql_outputs(blocks, "当前共有 37 张未删除数据表，口径为 table_cnt。")
        checked = module.auto_rule_check(
            case,
            final_answer="当前共有 37 张未删除数据表，口径为 table_cnt。",
            blocks=blocks,
            sql_outputs=sqls,
            tool_names=tools,
        )
        assert checked["passed"] is True, engine
        assert checked["hard_gates"]["non_expected_empty_result"] is True, engine
        assert "wrong_domain" not in checked["failure_attribution"], engine

        inconsistent = module.auto_rule_check(
            case,
            final_answer="当前共有 38 张未删除数据表。",
            blocks=blocks,
            sql_outputs=sqls,
            tool_names=tools,
        )
        assert inconsistent["hard_gates"]["result_consistency"] is False, engine

        module._apply_runtime_hard_gates(
            checked,
            case,
            task_completed=True,
            agent_turn_count=21,
            tool_call_count=11,
        )
        assert checked["passed"] is True, engine
        assert "agent_turn_limit" not in checked["hard_gates"], engine
        assert "tool_call_limit" not in checked["hard_gates"], engine
        assert checked["limit_violations"] == [], engine
        assert module._numeric_usage({"usage": {}}, ("input_tokens",)) is None, engine


def test_three_engines_keep_turns_and_usage_from_distinct_tasks():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    events = [
        {"_eval_task_id": "task-a", "task_id": "task-a", "turn_index": 1, "record_type": "assistant"},
        {"_eval_task_id": "task-b", "task_id": "task-b", "turn_index": 1, "record_type": "assistant"},
        {"_eval_task_id": "task-b", "task_id": "task-b", "turn_index": 2, "record_type": "assistant"},
    ]
    tasks = [
        {"task_id": "task-a", "usage": {"input_tokens": 10, "output_tokens": 4}},
        {"task_id": "task-b", "usage": {"input_tokens": 20, "output_tokens": 6}},
    ]
    messages = [
        {"task_id": "task-a", "usage": {"input_tokens": 10, "output_tokens": 4}},
        {"task_id": "task-b", "usage": {"input_tokens": 20, "output_tokens": 6}},
    ]

    for engine, module in modules.items():
        assert module._sdk_metrics(events)["agent_turn_count"] == 3, engine
        assert module._aggregate_usage(tasks, messages) == {
            "input_tokens": 30,
            "output_tokens": 10,
        }, engine


def test_three_engines_accept_equivalent_count_alias_and_select_target_result_shape():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    local_cases = [json.loads(line) for line in LOCAL_DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    case = next(item for item in local_cases if item["case_id"] == "ODW_BK_002")
    blocks = [
        {
            "type": "tool_use",
            "tool_name": "Skill",
            "input": {"skill": "opendataworks-business-knowledge"},
            "output": "Launching skill: opendataworks-business-knowledge",
        },
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {"command": "run_sql.py --sql SELECT COUNT(*) AS total FROM opendataworks.data_table WHERE deleted=0"},
            "output": {"kind": "sql_execution", "sql": "SELECT COUNT(*) AS total FROM opendataworks.data_table WHERE deleted=0", "rows": [{"total": 37}], "result_state": "success"},
        },
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {"command": "run_sql.py --sql SELECT status, COUNT(*) AS cnt FROM opendataworks.data_table WHERE deleted=0 GROUP BY status"},
            "output": {"kind": "sql_execution", "sql": "SELECT status, COUNT(*) AS cnt FROM opendataworks.data_table WHERE deleted=0 GROUP BY status", "rows": [{"status": "active", "cnt": 37}], "result_state": "success"},
        },
    ]

    for engine, module in modules.items():
        tools = module._collect_tool_names(blocks)
        sqls = module._extract_sql_outputs(blocks, "当前共有 37 张未删除数据表。")
        checked = module.auto_rule_check(
            case,
            final_answer="当前共有 37 张未删除数据表，口径为 deleted=0。",
            blocks=blocks,
            sql_outputs=sqls,
            tool_names=tools,
        )
        assert checked["passed"] is True, engine
        assert checked["missing_sql_fragments"] == [], engine
        assert checked["result_consistency"] == {"applicable": True, "passed": True}, engine


def test_three_engines_project_aliases_and_extra_columns_for_answer_consistency():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    rows = [{"all_records": 123, "target_alias": 0, "description": "no failures"}]

    for engine, module in modules.items():
        assert module._answer_references_result_values(
            rows,
            ["failed_publish_cnt"],
            "本月失败发布 0 次。",
        ) is True, engine
        assert module._answer_references_result_values(
            rows,
            ["failed_publish_cnt"],
            "本月失败发布 1 次。",
        ) is False, engine


def test_three_engines_validate_calendar_month_from_fixed_literal_bounds():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    local_cases = [json.loads(line) for line in LOCAL_DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    case = next(item for item in local_cases if item["case_id"] == "ODW_BK_004")

    for engine, module in modules.items():
        today = module.dt.datetime.now(module.dt.timezone(module.dt.timedelta(hours=8))).date()
        start = module._add_months(today.replace(day=1), 0)
        end = module._add_months(start, 1)
        sql = (
            "SELECT COUNT(*) AS failed_publish_cnt FROM opendataworks.workflow_publish_record "
            f"WHERE status='failed' AND created_at >= '{start.isoformat()} 00:00:00' "
            f"AND created_at < '{end.isoformat()} 00:00:00'"
        )
        blocks = [
            {"type": "tool_use", "tool_name": "Skill", "input": {"skill": "opendataworks-business-knowledge"}, "output": "ok"},
            {"type": "tool_use", "tool_name": "Bash", "input": {"command": f"run_sql.py --sql {sql}"}, "output": {"kind": "sql_execution", "sql": sql, "rows": [{"failed_publish_cnt": 0}], "result_state": "success"}},
        ]
        tools = module._collect_tool_names(blocks)
        sqls = module._extract_sql_outputs(blocks, "本月失败发布 0 次。")
        checked = module.auto_rule_check(
            case,
            final_answer="本月失败发布 0 次，按 created_at 的自然月边界统计。",
            blocks=blocks,
            sql_outputs=sqls,
            tool_names=tools,
        )
        assert checked["passed"] is True, engine
        assert checked["time_dimension"]["passed"] is True, engine
        assert checked["hard_gates"]["time_dimension"] is True, engine


def test_business_knowledge_allowed_empty_language_is_not_a_failure_code():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    local_cases = [json.loads(line) for line in LOCAL_DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    case = next(item for item in local_cases if item["case_id"] == "ODW_BK_005")
    blocks = [{"type": "tool_use", "tool_name": "Skill", "input": {"skill": "opendataworks-business-knowledge"}, "output": "ok"}]
    for engine, module in modules.items():
        tools = module._collect_tool_names(blocks)
        checked = module.auto_rule_check(
            case,
            final_answer="缺少 db_name，尚不能唯一定位 orders 表；请提供数据库名。",
            blocks=blocks,
            sql_outputs=[],
            tool_names=tools,
        )
        assert "empty_result" not in checked["failure_attribution"], engine
        assert checked["passed"] is True, engine


def test_three_engines_accept_expected_empty_reference_sql(monkeypatch):
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    local_cases = [json.loads(line) for line in LOCAL_DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    case = next(item for item in local_cases if item["case_id"] == "ODW_BK_006")
    sql = case["expected_result"]["reference_query"]["sql"]
    blocks = [
        {
            "type": "tool_use",
            "tool_name": "Skill",
            "input": {"skill": "opendataworks-business-knowledge"},
            "output": "Launching skill: opendataworks-business-knowledge",
        },
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {"command": f'"$DATAAGENT_PYTHON_BIN" "$DATAAGENT_PLATFORM_SKILL_ROOT/scripts/run_sql.py" --sql "{sql}"'},
            "output": {
                "kind": "sql_execution",
                "sql": sql,
                "rows": [],
                "row_count": 0,
                "result_state": "empty_result",
            },
        },
    ]
    answer = "已按 table_name 查询，未找到 __odw_eval_missing_table_006__，因此不提供数据库、负责人或状态。"

    for engine, module in modules.items():
        monkeypatch.setattr(
            module,
            "dataagent_http_json",
            lambda *_args, **_kwargs: {"rows": [], "row_count": 0, "result_state": "empty_result"},
        )
        reference = module._execute_reference_query("http://dataagent", case, "topic_1")
        assert reference["rows"] == [], engine
        assert reference["row_count"] == 0, engine

        actual_rows = module._actual_query_row_sets(blocks)
        assert actual_rows == [[]], engine
        compared = module._compare_reference(reference, actual_rows, case)
        assert compared["passed"] is True, engine

        tools = module._collect_tool_names(blocks)
        sql_outputs = module._extract_sql_outputs(blocks, answer)
        checked = module.auto_rule_check(
            case,
            final_answer=answer,
            blocks=blocks,
            sql_outputs=sql_outputs,
            tool_names=tools,
        )
        assert checked["passed"] is True, engine
        assert checked["hard_gates"]["non_expected_empty_result"] is True, engine
        assert "empty_result" not in checked["failure_attribution"], engine


def test_three_engines_derive_expected_empty_from_reference_truth():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    local_cases = [json.loads(line) for line in LOCAL_DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    source_case = next(item for item in local_cases if item["case_id"] == "ODW_BK_006")
    case = {
        **source_case,
        "expected_result": {**source_case["expected_result"], "allow_empty": False},
    }
    sql = case["expected_result"]["reference_query"]["sql"]
    blocks = [
        {
            "type": "tool_use",
            "tool_name": "Skill",
            "input": {"skill": "opendataworks-business-knowledge"},
            "output": "Launching skill: opendataworks-business-knowledge",
        },
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {"command": f'run_sql.py --sql "{sql}"'},
            "output": {
                "kind": "sql_execution",
                "sql": sql,
                "rows": [],
                "row_count": 0,
                "result_state": "empty_result",
            },
        },
    ]
    answer = "未找到 __odw_eval_missing_table_006__，没有编造数据库、负责人或状态。"

    for engine, module in modules.items():
        reference = {"applicable": True, "passed": None, "rows": [], "row_count": 0}
        rule_case = module._case_with_reference_empty_policy(case, reference)
        assert case["expected_result"]["allow_empty"] is False, engine
        assert rule_case["expected_result"]["allow_empty"] is True, engine
        checked = module.auto_rule_check(
            rule_case,
            final_answer=answer,
            blocks=blocks,
            sql_outputs=module._extract_sql_outputs(blocks, answer),
            tool_names=module._collect_tool_names(blocks),
        )
        assert checked["passed"] is True, engine
        assert checked["hard_gates"]["non_expected_empty_result"] is True, engine
        assert "empty_result" not in checked["failure_attribution"], engine

        nonempty_reference = {"applicable": True, "rows": [{"table_name": "orders"}], "row_count": 1}
        assert module._case_with_reference_empty_policy(case, nonempty_reference) is case, engine


def test_three_engines_report_reference_sql_case_and_statement_on_backend_failure(monkeypatch):
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    sql = "SELECT component_name, owner_name FROM public.dim_demo_component_df"
    case = {
        "case_id": "ARCH_DIAGNOSTIC_001",
        "expected_result": {
            "reference_query": {
                "sql": sql,
                "database": "public",
                "engine": "doris",
                "limit": 100,
            }
        },
    }

    for engine, module in modules.items():
        monkeypatch.setattr(
            module,
            "dataagent_http_json",
            lambda *_args, **_kwargs: {
                "rows": [],
                "result_state": "failed",
                "error": "Unknown column 'owner_name'",
            },
        )
        try:
            module._execute_reference_query("http://dataagent", case, "topic_1")
        except module.InfrastructureAbort as exc:
            message = str(exc)
            assert message.startswith("reference_sql_failed:"), engine
            assert "case_id=ARCH_DIAGNOSTIC_001" in message, engine
            assert "database=public" in message, engine
            assert "engine=doris" in message, engine
            assert f'sql={json.dumps(sql, ensure_ascii=False)}' in message, engine
            assert "Unknown column 'owner_name'" in message, engine
            assert exc.details["error_code"] == "reference_sql_failed", engine
            assert exc.details["case_id"] == "ARCH_DIAGNOSTIC_001", engine
            assert exc.details["sql"] == sql, engine
            assert exc.details["cause"].endswith("Unknown column 'owner_name'"), engine
        else:
            raise AssertionError(f"{engine}: failed reference SQL must abort with diagnostics")


def _platform_reliability_case():
    return {
        "schema_version": 2,
        "case_id": "ODW_EVAL_RELIABILITY",
        "category": "business-knowledge",
        "case_type": "query",
        "suite_tags": ["local-smoke", "evaluator-reliability"],
        "question": "查询 OpenDataWorks 工作流发布记录",
        "expected_semantics": {},
        "expected_time": {
            "required": False,
            "field": "",
            "range": {},
            "grain": "",
            "timezone": "Asia/Shanghai",
        },
        "expected_tools": {
            "required_steps": ["query_execute"],
            "allowed_alternative_groups": [["run_sql"]],
        },
        "expected_sql": {
            "execution_required": True,
            "tables": ["opendataworks.workflow_publish_record"],
            "fields": [],
            "predicates": [],
            "aggregations": [],
            "forbidden_patterns": [],
        },
        "expected_result": {
            "allow_empty": False,
            "required_columns": [],
            "answer_result_fields": [],
        },
        "expected_answer": {},
        "limits": {},
        "scoring": {
            "intent": 1,
            "ontology_entity": 1,
            "relation_scope": 1,
            "sql_or_tool_call": 2,
            "result_consistency": 2,
            "reasoning": 2,
            "answer_quality": 1,
            "total_score": 10,
        },
        "veto_rules": [],
    }


def _sql_blocks(statements):
    blocks = []
    for index, item in enumerate(statements):
        sql = item["sql"]
        rows = item.get("rows", [{"cnt": 2}])
        blocks.append(
            {
                "type": "tool_use",
                "tool_id": f"tool_{index}",
                "tool_name": "run_sql",
                "input": {"sql": sql},
                "output": {
                    "kind": "sql_execution",
                    "sql": sql,
                    "rows": rows,
                    "row_count": len(rows),
                    "result_state": "success" if rows else "empty_result",
                },
                "is_error": False,
            }
        )
    return blocks


def test_three_engines_exceed_ninety_percent_accuracy_on_opendataworks_golden_replay():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    engine_predictions = {}

    for engine, module in modules.items():
        today = module.dt.datetime.now(module.dt.timezone(module.dt.timedelta(hours=8))).date()
        rolling_start = today - module.dt.timedelta(days=6)
        previous_start = module._add_months(today.replace(day=1), -1)
        previous_end = today.replace(day=1) - module.dt.timedelta(days=1)
        current_start = today.replace(day=1)
        normal_sql = "SELECT COUNT(*) AS cnt FROM opendataworks.workflow_publish_record"
        scenarios = [
            {
                "name": "leading-line-comment",
                "sqls": [{"sql": f"-- OpenDataWorks regression\n{normal_sql}"}],
                "answer": "共 2 条发布记录。",
                "expected": True,
            },
            {
                "name": "leading-block-comment",
                "sqls": [{"sql": f"/* OpenDataWorks regression */\n{normal_sql}"}],
                "answer": "共 2 条发布记录。",
                "expected": True,
            },
            {
                "name": "required-fragments-across-queries",
                "patch": {
                    "expected_sql": {
                        "tables": ["opendataworks.data_task", "opendataworks.data_workflow"],
                    }
                },
                "sqls": [
                    {"sql": "SELECT id, workflow_id FROM opendataworks.data_task", "rows": [{"id": 7, "workflow_id": 3}]},
                    {"sql": "SELECT id, name FROM opendataworks.data_workflow", "rows": [{"id": 3, "name": "daily_sync"}]},
                ],
                "answer": "任务 7 属于工作流 daily_sync。",
                "expected": True,
            },
            {
                "name": "missing-one-required-fragment",
                "patch": {
                    "expected_sql": {
                        "tables": ["opendataworks.data_task", "opendataworks.data_workflow"],
                    }
                },
                "sqls": [{"sql": "SELECT id FROM opendataworks.data_task", "rows": [{"id": 7}]}],
                "answer": "查到任务 7。",
                "expected": False,
            },
            {
                "name": "rolling-calendar-days-correct",
                "patch": {
                    "expected_time": {
                        "required": True,
                        "field": "created_at",
                        "range": {"kind": "rolling_calendar_days", "days": 7},
                    }
                },
                "sqls": [{
                    "sql": (
                        f"{normal_sql} WHERE created_at >= '{rolling_start.isoformat()}' "
                        f"AND created_at <= '{today.isoformat()}'"
                    )
                }],
                "answer": "最近 7 个自然日共 2 条发布记录。",
                "expected": True,
            },
            {
                "name": "rolling-calendar-days-wrong-window",
                "patch": {
                    "expected_time": {
                        "required": True,
                        "field": "created_at",
                        "range": {"kind": "rolling_calendar_days", "days": 7},
                    }
                },
                "sqls": [{
                    "sql": f"{normal_sql} WHERE created_at >= '2020-01-01' AND created_at < '2020-01-08'"
                }],
                "answer": "最近 7 个自然日共 2 条发布记录。",
                "expected": False,
            },
            {
                "name": "calendar-month-comparison-correct",
                "patch": {
                    "expected_time": {
                        "required": True,
                        "field": "created_at",
                        "range": {"kind": "calendar_month_comparison"},
                    }
                },
                "sqls": [{
                    "sql": (
                        "SELECT COUNT(*) AS cnt FROM opendataworks.workflow_publish_record "
                        f"WHERE created_at >= '{previous_start.isoformat()}' "
                        f"AND created_at <= '{previous_end.isoformat()}' "
                        f"OR created_at >= '{current_start.isoformat()}' "
                        f"AND created_at <= '{today.isoformat()}'"
                    )
                }],
                "answer": "已比较上月与本月至今的发布次数。",
                "expected": True,
            },
            {
                "name": "calendar-month-comparison-incomplete",
                "patch": {
                    "expected_time": {
                        "required": True,
                        "field": "created_at",
                        "range": {"kind": "calendar_month_comparison"},
                    }
                },
                "sqls": [{
                    "sql": (
                        f"{normal_sql} WHERE created_at >= '{current_start.isoformat()}' "
                        f"AND created_at <= '{today.isoformat()}'"
                    )
                }],
                "answer": "已比较上月与本月至今的发布次数。",
                "expected": False,
            },
            {
                "name": "ordinary-execution-prose-is-not-sql-only",
                "sqls": [{"sql": normal_sql}],
                "answer": "平台提供查询函数并执行查询后，共得到 2 条发布记录。",
                "expected": True,
                "absent_attribution": "sql_only",
            },
            {
                "name": "set-difference-word-is-not-empty-result",
                "sqls": [{"sql": normal_sql}],
                "answer": "不存在依赖关系的工作流共有 2 个。",
                "expected": True,
                "absent_attribution": "empty_result",
            },
            {
                "name": "structured-unexpected-empty",
                "sqls": [{"sql": normal_sql, "rows": []}],
                "answer": "没有发布记录。",
                "expected": False,
                "required_attribution": "unexpected_empty_result",
            },
            {
                "name": "structured-allowed-empty",
                "patch": {"expected_result": {"allow_empty": True}},
                "sqls": [{"sql": normal_sql, "rows": []}],
                "answer": "当前没有发布记录。",
                "expected": True,
            },
            {
                "name": "explicit-result-field-consistent",
                "patch": {"expected_result": {"answer_result_fields": ["cnt"]}},
                "sqls": [{"sql": normal_sql, "rows": [{"cnt": 2}]}],
                "answer": "工作流发布记录共 2 条。",
                "expected": True,
            },
            {
                "name": "explicit-result-field-inconsistent",
                "patch": {"expected_result": {"answer_result_fields": ["cnt"]}},
                "sqls": [{"sql": normal_sql, "rows": [{"cnt": 2}]}],
                "answer": "工作流发布记录共 3 条。",
                "expected": False,
            },
            {
                "name": "generic-skill-does-not-satisfy-lookup",
                "patch": {"expected_tools": {"required_steps": ["ontology_lookup", "query_execute"]}},
                "extra_blocks": [{
                    "type": "tool_use",
                    "tool_name": "Skill",
                    "input": {"skill": "opendataworks-business-knowledge"},
                    "output": "ok",
                }],
                "sqls": [{"sql": normal_sql}],
                "answer": "共 2 条发布记录。",
                "expected": False,
            },
            {
                "name": "explicit-lookup-script-satisfies-lookup",
                "patch": {"expected_tools": {"required_steps": ["ontology_lookup", "query_execute"]}},
                "extra_blocks": [{
                    "type": "tool_use",
                    "tool_name": "Bash",
                    "input": {"command": "lookup_ontology.py --object workflow_publish_record"},
                    "output": {"object": "workflow_publish_record"},
                }],
                "sqls": [{"sql": normal_sql}],
                "answer": "共 2 条发布记录。",
                "expected": True,
            },
            {
                "name": "unknown-time-kind-does-not-fall-back-to-field",
                "patch": {
                    "expected_time": {
                        "required": True,
                        "field": "created_at",
                        "range": {"kind": "unsupported_window"},
                    }
                },
                "sqls": [{"sql": f"{normal_sql} WHERE created_at IS NOT NULL"}],
                "answer": "共 2 条发布记录。",
                "expected": False,
            },
        ]

        predictions = []
        for scenario in scenarios:
            case = _merge(_platform_reliability_case(), scenario.get("patch") or {})
            blocks = list(scenario.get("extra_blocks") or []) + _sql_blocks(scenario["sqls"])
            tools = module._collect_tool_names(blocks)
            sqls = module._extract_sql_outputs(blocks, scenario["answer"])
            checked = module.auto_rule_check(
                case,
                final_answer=scenario["answer"],
                blocks=blocks,
                sql_outputs=sqls,
                tool_names=tools,
            )
            predicted = bool(checked["passed"])
            predictions.append(predicted)
            if scenario.get("required_attribution"):
                assert scenario["required_attribution"] in checked["failure_attribution"], (
                    engine,
                    scenario["name"],
                )
            if scenario.get("absent_attribution"):
                assert scenario["absent_attribution"] not in checked["failure_attribution"], (
                    engine,
                    scenario["name"],
                )

        expected = [bool(scenario["expected"]) for scenario in scenarios]
        correct = sum(actual == target for actual, target in zip(predictions, expected))
        accuracy = correct / len(expected)
        positive_indices = [index for index, target in enumerate(expected) if target]
        negative_indices = [index for index, target in enumerate(expected) if not target]
        positive_accuracy = sum(predictions[index] for index in positive_indices) / len(positive_indices)
        negative_accuracy = sum(not predictions[index] for index in negative_indices) / len(negative_indices)
        assert accuracy >= 0.90, f"{engine}: evaluator accuracy={accuracy:.2%}"
        assert positive_accuracy >= 0.90, f"{engine}: evaluator positive accuracy={positive_accuracy:.2%}"
        assert negative_accuracy >= 0.90, f"{engine}: evaluator negative accuracy={negative_accuracy:.2%}"
        assert predictions == expected, f"{engine}: current golden replay must remain 100% accurate"
        engine_predictions[engine] = predictions

    assert engine_predictions["builtin"] == engine_predictions["deepeval"] == engine_predictions["opik"]


def test_three_engines_compare_aliases_extra_columns_split_queries_and_apply_ninety_percent_gates():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    case = _platform_reliability_case()
    case["expected_result"]["reference_query"] = {
        "sql": "SELECT status, COUNT(*) AS cnt FROM opendataworks.workflow_publish_record GROUP BY status",
        "comparison_fields": ["status", "cnt"],
        "comparison_mode": "unordered_values",
    }
    expected_rows = [{"status": "success", "cnt": 4}]
    actual_with_description = [{"state_alias": "success", "total_alias": 4, "status_label": "发布成功"}]
    split_results = [[{"state_alias": "success"}], [{"total_alias": 4}]]

    summaries = {}
    for engine, module in modules.items():
        judge_case = module._case_for_judge(case)
        assert "reference_query" not in judge_case["expected_result"], engine

        matched = module._compare_reference(
            {"applicable": True, "rows": expected_rows, "row_count": 1},
            [actual_with_description],
            case,
        )
        assert matched["status"] == "matched", engine
        assert matched["comparable"] is True, engine
        assert matched["passed"] is True, engine

        split_matched = module._compare_reference(
            {"applicable": True, "rows": expected_rows, "row_count": 1},
            split_results,
            case,
        )
        assert split_matched["status"] == "matched", engine
        assert split_matched["comparable"] is True, engine
        assert split_matched["passed"] is True, engine

        results = []
        for index in range(10):
            dimensions = {
                "intent": 1,
                "ontology_entity": 1,
                "relation_scope": 1,
                "sql_or_tool_call": 2,
                "result_consistency": 2,
                "reasoning": 2,
                "answer_quality": 1,
            }
            results.append(
                {
                    "case_id": f"ODW_GATE_{index:02d}",
                    "task_id": f"task-{index}",
                    "task_status": "success",
                    "case_passed": index < 9,
                    "errors": [],
                    "judge": {
                        "score": 10,
                        "dimension_scores": dimensions,
                        "hallucination": False,
                        "judge_failed": False,
                    },
                    "auto_rule_check": {
                        "hard_gates": {
                            "required_tool_execution": True,
                            "sql_execution_and_口径": True,
                        },
                        "result_consistency": {"applicable": False, "passed": True},
                        "time_dimension": {"applicable": False, "passed": True},
                    },
                    "reference_data_accuracy": {
                        "applicable": True,
                        "comparable": True,
                        "passed": index < 9,
                    },
                    "veto_rules_triggered": [],
                    "timing": {},
                    "usage": {},
                }
            )
        summary = module.build_summary(results, {"dataset_valid": True})
        assert summary["metrics"]["data_accuracy"]["value"] == 0.9, engine
        assert summary["metrics"]["effective_pass_rate"]["value"] == 0.9, engine
        assert summary["gate_results"]["data_accuracy"]["passed"] is True, engine
        assert summary["passed"] is True, engine
        summaries[engine] = {
            "data_accuracy": summary["metrics"]["data_accuracy"],
            "gate_results": summary["gate_results"],
            "passed": summary["passed"],
        }

    assert summaries["builtin"] == summaries["deepeval"] == summaries["opik"]


def test_three_engines_treat_zero_scalar_as_equivalent_to_successful_empty_group_result():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    case = _platform_reliability_case()
    case["expected_result"]["reference_query"] = {
        "sql": "SELECT COUNT(*) AS target_count FROM records WHERE status = 'missing'",
        "comparison_mode": "scalar",
    }
    reference = {
        "applicable": True,
        "rows": [{"target_count": 0}],
        "row_count": 1,
    }

    for engine, module in modules.items():
        compared = module._compare_reference(reference, [[]], case)
        assert compared["status"] == "matched", engine
        assert compared["comparable"] is True, engine
        assert compared["passed"] is True, engine
        rule_case = module._case_with_reference_empty_policy(case, reference)
        assert rule_case["expected_result"]["allow_empty"] is True, engine


def test_three_engines_accept_fixed_bounds_for_reference_interval_predicate():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    cases = [json.loads(line) for line in GOLDEN_DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    case = next(item for item in cases if item["case_id"] == "ODW_GOLD_008")

    for engine, module in modules.items():
        today = module.dt.datetime.now(module.dt.timezone(module.dt.timedelta(hours=8))).date()
        start = today - module.dt.timedelta(days=29)
        end = today + module.dt.timedelta(days=1)
        sql = (
            "SELECT status, COUNT(*) AS cnt FROM opendataworks.workflow_publish_record "
            f"WHERE created_at >= '{start.isoformat()}' AND created_at < '{end.isoformat()}' "
            "GROUP BY status"
        )
        blocks = [
            {"type": "tool_use", "tool_name": "Skill", "input": {"skill": "sample-business-knowledge"}},
            {
                "type": "tool_use",
                "tool_name": "Bash",
                "input": {"command": f"run_sql.py --sql {sql}"},
                "output": {
                    "kind": "sql_execution",
                    "sql": sql,
                    "rows": [{"status": "success", "cnt": 2}],
                    "result_state": "success",
                },
            },
        ]
        sql_outputs = module._extract_sql_outputs(blocks, "success 2 次")
        checked = module.auto_rule_check(
            case,
            final_answer="最近 30 个自然日 success 2 次，没有其他状态。",
            blocks=blocks,
            sql_outputs=sql_outputs,
            tool_names=module._collect_tool_names(blocks),
        )
        assert checked["time_dimension"]["passed"] is True, engine
        assert checked["missing_sql_fragments"] == [], engine
        assert checked["passed"] is True, engine


def test_three_engines_override_structural_judge_dimensions_with_deterministic_checks():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    judge = {
        "score": 6,
        "dimension_scores": {
            "intent": 1,
            "ontology_entity": 1,
            "relation_scope": 1,
            "sql_or_tool_call": 0,
            "result_consistency": 0,
            "reasoning": 2,
            "answer_quality": 1,
        },
        "judge_failed": False,
    }
    rule_check = {
        "hard_gates": {
            "required_tool_execution": True,
            "sql_execution_and_口径": True,
            "time_dimension": True,
        },
        "result_consistency": {"applicable": True, "passed": True},
    }

    outputs = {}
    for engine, module in modules.items():
        updated = module._apply_deterministic_dimension_scores(judge, rule_check)
        assert updated["dimension_scores"]["sql_or_tool_call"] == 2, engine
        assert updated["dimension_scores"]["result_consistency"] == 2, engine
        assert updated["score"] == 10, engine

        failed = module._apply_deterministic_dimension_scores(
            judge,
            {
                "hard_gates": {
                    "required_tool_execution": True,
                    "sql_execution_and_口径": False,
                    "time_dimension": True,
                },
                "result_consistency": {"applicable": True, "passed": False},
            },
        )
        assert failed["dimension_scores"]["sql_or_tool_call"] == 0, engine
        assert failed["dimension_scores"]["result_consistency"] == 0, engine
        assert failed["score"] == 6, engine
        outputs[engine] = (updated, failed)

    assert outputs["builtin"] == outputs["deepeval"] == outputs["opik"]


def test_three_engines_treat_judge_total_mismatch_as_diagnostic_only():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    payload = {
        "score": 10,
        "dimension_scores": {
            "intent": 1,
            "ontology_entity": 1,
            "relation_scope": 1,
            "sql_or_tool_call": 2,
            "result_consistency": 2,
            "reasoning": 1,
            "answer_quality": 1,
        },
        "judge_failed": False,
    }

    outputs = {}
    for engine, module in modules.items():
        normalize = (
            module.normalize_judge_payload
            if engine == "deepeval"
            else module._normalize_judge_payload
        )
        normalized = normalize(payload)
        assert normalized["score"] == 9, engine
        assert normalized["judge_failed"] is False, engine
        assert "judge_score_inconsistent" in normalized["failure_attribution"], engine
        outputs[engine] = {
            key: normalized[key]
            for key in ("score", "dimension_scores", "judge_failed", "failure_attribution")
        }

    assert outputs["builtin"] == outputs["deepeval"] == outputs["opik"]


def test_three_engines_do_not_branch_on_dataset_labels():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    base_case = {
        "case_id": "LABEL_INDEPENDENCE_001",
        "case_type": "query",
        "category": "domain-a",
        "suite_tags": ["suite-a", "smoke"],
        "expected_sql": {
            "execution_required": True,
            "tables": ["analytics.orders"],
            "fields": ["id"],
            "predicates": [],
            "aggregations": ["COUNT"],
            "forbidden_patterns": [],
        },
        "expected_time": {"required": False},
        "expected_tools": {
            "required_steps": ["query_execute"],
            "allowed_alternative_groups": [["Bash:query.py"]],
        },
        "expected_result": {
            "allow_empty": False,
            "answer_result_fields": ["order_cnt"],
            "required_columns": ["order_cnt"],
        },
    }
    relabeled_case = {
        **base_case,
        "category": "unrelated-domain",
        "suite_tags": ["formal", "release"],
    }
    sql = "SELECT COUNT(id) AS total FROM analytics.orders"
    blocks = [
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {"command": f"query.py --sql {sql}"},
            "output": {
                "kind": "sql_execution",
                "sql": sql,
                "rows": [{"total": 3}],
                "result_state": "success",
            },
        }
    ]

    for engine, module in modules.items():
        tools = module._collect_tool_names(blocks)
        sqls = module._extract_sql_outputs(blocks, "订单数为 3。")
        original = module.auto_rule_check(
            base_case,
            final_answer="订单数为 3。",
            blocks=blocks,
            sql_outputs=sqls,
            tool_names=tools,
        )
        relabeled = module.auto_rule_check(
            relabeled_case,
            final_answer="订单数为 3。",
            blocks=blocks,
            sql_outputs=sqls,
            tool_names=tools,
        )
        assert original == relabeled, engine


def test_three_engines_require_the_named_skill_semantics():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}

    for engine, module in modules.items():
        assert module._required_tool_step_satisfied(
            "business_knowledge_skill",
            ["Skill:sample-business-knowledge"],
            [],
        ), engine
        assert not module._required_tool_step_satisfied(
            "business_knowledge_skill",
            ["Skill:sample-platform-tools"],
            [],
        ), engine


def test_three_engines_support_case_declared_sql_fragment_alternatives():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    fragment = {"any_of": ["is_deleted = 0", "is_deleted != 1", "is_deleted <> 1"]}

    for engine, module in modules.items():
        assert module._sql_fragment_matches(
            fragment,
            "SELECT COUNT(*) FROM analytics.orders WHERE is_deleted != 1",
            {"aggregations": ["COUNT"]},
        ), engine
        assert not module._sql_fragment_matches(
            fragment,
            "SELECT COUNT(*) FROM analytics.orders",
            {"aggregations": ["COUNT"]},
        ), engine


def test_builtin_and_opik_keep_every_successful_query_for_the_judge():
    modules = {
        name: _load(name, RUNNERS[name])
        for name in ("builtin", "opik")
    }
    evidence = [
        {
            "sql": f"SELECT {index} AS value",
            "row_count": 1,
            "row_preview": [{"value": index}],
            "result_state": "success",
        }
        for index in range(25)
    ]

    for engine, module in modules.items():
        compact = module._compact_judge_payload({"query_evidence": evidence})
        assert len(compact["query_evidence"]) == len(evidence), engine
        assert compact["query_evidence"][0]["sql"] == "SELECT 0 AS value", engine
        assert compact["query_evidence"][-1]["sql"] == "SELECT 24 AS value", engine


def test_three_engines_only_enforce_reference_mismatch_when_case_opts_in():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}

    for engine, module in modules.items():
        diagnostic_rule = {"passed": True, "failure_attribution": [], "hard_gates": {}}
        module._apply_reference_gate(
            diagnostic_rule,
            {
                "applicable": True,
                "comparable": True,
                "passed": False,
                "enforced": False,
            },
        )
        assert diagnostic_rule["passed"] is True, engine
        assert "reference_data_accuracy" not in diagnostic_rule["hard_gates"], engine

        enforced_rule = {"passed": True, "failure_attribution": [], "hard_gates": {}}
        module._apply_reference_gate(
            enforced_rule,
            {
                "applicable": True,
                "comparable": True,
                "passed": False,
                "enforced": True,
            },
        )
        assert enforced_rule["passed"] is False, engine
        assert enforced_rule["hard_gates"]["reference_data_accuracy"] is False, engine
        assert "enforced_reference_data_mismatch" in enforced_rule["failure_attribution"], engine

        incomparable_rule = {"passed": True, "failure_attribution": [], "hard_gates": {}}
        module._apply_reference_gate(
            incomparable_rule,
            {
                "applicable": True,
                "comparable": False,
                "passed": None,
                "enforced": True,
            },
        )
        assert incomparable_rule["passed"] is True, engine
