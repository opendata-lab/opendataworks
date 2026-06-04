from __future__ import annotations

import importlib.util
import json
import threading
import sys
import time
import types
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
RUNNER_PATH = REPO_ROOT / "tools" / "dataagent-evals" / "deepeval" / "run.py"


def _load_runner():
    spec = importlib.util.spec_from_file_location("dataagent_deepeval_runner", RUNNER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules["dataagent_deepeval_runner"] = module
    spec.loader.exec_module(module)
    return module


def _install_fake_deepeval(monkeypatch):
    class LLMTestCase:
        def __init__(self, *, input, actual_output, expected_output=None, context=None):
            self.input = input
            self.actual_output = actual_output
            self.expected_output = expected_output
            self.context = context or []

    class BaseMetric:
        pass

    calls = {"test_cases": [], "metrics": []}

    def evaluate(*, test_cases, metrics, **kwargs):
        calls["test_cases"] = list(test_cases)
        calls["metrics"] = list(metrics)
        for test_case in test_cases:
            for metric in metrics:
                metric.measure(test_case)
        return types.SimpleNamespace(test_results=[])

    monkeypatch.setitem(sys.modules, "deepeval", types.SimpleNamespace(evaluate=evaluate))
    monkeypatch.setitem(sys.modules, "deepeval.test_case", types.SimpleNamespace(LLMTestCase=LLMTestCase))
    monkeypatch.setitem(sys.modules, "deepeval.metrics", types.SimpleNamespace(BaseMetric=BaseMetric))
    return calls


def _sample_case():
    return {
        "case_id": "ODW_SAMPLE_001",
        "category": "DataAgent 通用样例",
        "question": "最近 30 天工作流发布次数趋势",
        "expected_intent": "趋势分析",
        "expected_ontology_objects": ["workflow_publish_record"],
        "expected_relations": [],
        "expected_sql_or_tool_behavior": ["查询工作流发布记录并按日期聚合"],
        "expected_answer_points": ["说明时间范围和统计口径"],
        "scoring": {
            "intent": 1,
            "ontology_entity": 1,
            "relation_scope": 1,
            "sql_or_tool_call": 2,
            "data_accuracy": 2,
            "reasoning": 2,
            "answer_quality": 1,
            "total_score": 10,
        },
        "veto_rules": ["不要编造"],
        "max_wait_seconds": 900,
        "required_sql_fragments": [],
        "forbidden_sql_patterns": [],
        "expected_tool_names": [],
        "judge_guidance": "看时间范围和统计口径",
    }


def test_deepeval_case_conversion_uses_expected_fields(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    case = _sample_case()
    case_result = {
        "case_id": case["case_id"],
        "question": case["question"],
        "final_answer": "最近 30 天工作流发布次数按日期聚合如下。",
        "tool_names": ["run_sql"],
        "sql_outputs": [{"sql": "select count(1)"}],
        "chart_outputs": [],
        "auto_rule_check": {"passed": True, "triggered_veto_rules": []},
    }

    test_case = runner.to_deepeval_test_case(case, case_result)

    assert test_case.input == case["question"]
    assert test_case.actual_output == case_result["final_answer"]
    expected = json.loads(test_case.expected_output)
    assert expected["case_id"] == "ODW_SAMPLE_001"
    assert expected["expected_ontology_objects"] == ["workflow_publish_record"]
    context = json.loads(test_case.context[0])
    assert context["case_result"]["tool_names"] == ["run_sql"]
    assert context["case_result"]["auto_rule_check"]["passed"] is True


def test_deepeval_metric_calls_independent_judge_and_normalizes_result(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    calls = []

    def fake_judge(config, payload):
        calls.append(payload)
        return {
            "score": 8,
            "dimension_scores": {"intent": 1, "data_accuracy": 2, "reasoning": 2},
            "hallucination": "false",
            "veto_rules_triggered": "",
            "failure_attribution": "minor_tool_gap",
            "comment": "ok",
        }

    monkeypatch.setattr(runner, "call_judge_model", fake_judge)
    case = _sample_case()
    case_result = {
        "case_id": case["case_id"],
        "question": case["question"],
        "final_answer": "answer",
        "auto_rule_check": {"passed": True, "triggered_veto_rules": []},
    }
    test_case = runner.to_deepeval_test_case(case, case_result)
    metric = runner.DataAgentEvaluationMetric(runner.JudgeConfig(base_url="http://judge", token="t", model="m"))

    score = metric.measure(test_case)

    assert score == 0.8
    assert metric.is_successful() is True
    assert metric.case_judges["ODW_SAMPLE_001"]["score"] == 8.0
    assert metric.case_judges["ODW_SAMPLE_001"]["hallucination"] is False
    assert metric.case_judges["ODW_SAMPLE_001"]["failure_attribution"] == ["minor_tool_gap"]
    assert calls[0]["case"]["case_id"] == "ODW_SAMPLE_001"


def test_deepeval_apply_judges_uses_shared_results_when_metric_is_copied(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    runner.DataAgentEvaluationMetric.shared_case_judges = {
        "ODW_SAMPLE_001": {
            "score": 8,
            "dimension_scores": {"intent": 1},
            "hallucination": False,
            "veto_rules_triggered": [],
            "failure_attribution": [],
            "comment": "ok",
            "judge_failed": False,
        }
    }
    metric = runner.DataAgentEvaluationMetric(runner.JudgeConfig(base_url="http://judge", token="t", model="m"))
    result = {
        "case_id": "ODW_SAMPLE_001",
        "task_status": "finished",
        "errors": [],
        "auto_rule_check": {"triggered_veto_rules": []},
    }

    updated = runner._apply_judges([result], metric)

    assert updated[0]["judge"]["score"] == 8
    assert updated[0]["judge"]["judge_failed"] is False
    assert updated[0]["case_passed"] is True


def test_deepeval_agent_id_can_default_from_environment(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    monkeypatch.setenv("DATAAGENT_EVAL_AGENT_ID", "agent_eval")

    args = runner.parse_args(["--dry-run", "--dataset", "cases.jsonl"])

    assert args.agent_id == "agent_eval"


def test_deepeval_non_dry_run_requires_agent_id(tmp_path, capsys, monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(json.dumps(_sample_case(), ensure_ascii=False) + "\n", encoding="utf-8")

    code = runner.main(
        [
            "--dataset",
            str(dataset),
            "--output-dir",
            str(tmp_path),
            "--judge-base-url",
            "http://judge",
            "--judge-token",
            "token",
            "--judge-model",
            "model",
        ]
    )

    assert code == 2
    captured = capsys.readouterr()
    assert "--agent-id" in captured.err


def test_deepeval_topic_and_task_requests_include_agent_id(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    calls = []

    def fake_http_json(method, url, payload=None, **kwargs):
        calls.append({"method": method, "url": url, "payload": payload, "kwargs": kwargs})
        if url.endswith("/topics"):
            return {"topic_id": "topic-1"}
        return {"task_id": "task-1"}

    monkeypatch.setattr(runner, "http_json", fake_http_json)
    args = types.SimpleNamespace(provider_id="", model="", agent_id="agent_eval")

    topic_id = runner._create_topic("http://dataagent", _sample_case(), args.agent_id)
    task_id = runner._submit_task("http://dataagent", topic_id, _sample_case(), args)

    assert task_id == "task-1"
    assert calls[0]["payload"]["agent_id"] == "agent_eval"
    assert calls[1]["payload"]["agent_id"] == "agent_eval"


def test_deepeval_run_deepeval_records_failed_judge_when_measure_crashes(monkeypatch, capsys):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    case = _sample_case()
    test_case = runner.to_deepeval_test_case(
        case,
        {"case_id": case["case_id"], "final_answer": "answer", "auto_rule_check": {"passed": True}},
    )
    metric = runner.DataAgentEvaluationMetric(runner.JudgeConfig(base_url="http://judge", token="t", model="m"))

    def boom(config, payload):
        raise RuntimeError("judge call exploded")

    monkeypatch.setattr(runner, "call_judge_model", boom)

    # A crashing case must not abort the batch or lose the report.
    runner.run_deepeval([test_case], metric)

    judge = runner.DataAgentEvaluationMetric.shared_case_judges["ODW_SAMPLE_001"]
    assert judge["judge_failed"] is True
    assert "judge_crash" in judge["failure_attribution"]
    captured = capsys.readouterr()
    assert "judge measurement crashed" in captured.err


def test_deepeval_run_deepeval_measures_all_cases_without_cloud_evaluate(monkeypatch):
    calls = _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    case = _sample_case()
    test_case = runner.to_deepeval_test_case(
        case,
        {"case_id": case["case_id"], "final_answer": "answer", "auto_rule_check": {"passed": True}},
    )
    metric = runner.DataAgentEvaluationMetric(runner.JudgeConfig(base_url="http://judge", token="t", model="m"))

    def fake_judge(config, payload):
        return {
            "score": 9,
            "dimension_scores": {"intent": 1},
            "hallucination": False,
            "veto_rules_triggered": [],
            "failure_attribution": [],
            "comment": "ok",
        }

    monkeypatch.setattr(runner, "call_judge_model", fake_judge)

    runner.run_deepeval([test_case], metric)

    assert metric.case_judges["ODW_SAMPLE_001"]["score"] == 9.0
    # The offline runner never invokes DeepEval's cloud-coupled evaluate().
    assert calls["test_cases"] == []


def test_deepeval_runner_opts_out_of_cloud_telemetry(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    monkeypatch.delenv("DEEPEVAL_TELEMETRY_OPT_OUT", raising=False)
    monkeypatch.delenv("DEEPEVAL_UPDATE_WARNING_OPT_OUT", raising=False)

    import os

    _load_runner()

    assert os.environ.get("DEEPEVAL_TELEMETRY_OPT_OUT") == "YES"
    assert os.environ.get("DEEPEVAL_UPDATE_WARNING_OPT_OUT") == "YES"


def test_deepeval_judge_request_embeds_system_prompt_in_user_content(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    calls = []

    def fake_http_json(method, url, payload=None, **kwargs):
        calls.append({"method": method, "url": url, "payload": payload, "kwargs": kwargs})
        return {
            "content": [
                {
                    "type": "text",
                    "text": '{"score":9,"dimension_scores":{"intent":1},"hallucination":false,"veto_rules_triggered":[],"failure_attribution":[],"comment":"ok"}',
                }
            ]
        }

    monkeypatch.setattr(runner, "http_json", fake_http_json)

    result = runner.call_judge_model(
        runner.JudgeConfig(base_url="https://judge.example", token="token", model="model", max_tokens=2222),
        {"case": {"case_id": "ODW_SAMPLE_001"}, "final_answer": "answer"},
    )

    body = calls[0]["payload"]
    assert result["judge_failed"] is False
    assert body["max_tokens"] == 2222
    assert "system" not in body
    assert "你是 DataAgent 在线问数评测裁判" in body["messages"][0]["content"]


def test_deepeval_poll_task_tolerates_transient_status_error(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    calls = {"task": 0}

    def fake_http_json(method, url, **kwargs):
        calls["task"] += 1
        if calls["task"] == 1:
            raise runner.EvalRunnerError("request failed transient status")
        return {"task_id": "task_1", "task_status": "finished"}

    monkeypatch.setattr(runner, "http_json", fake_http_json)
    monkeypatch.setattr(runner.time, "sleep", lambda *_: None)

    task, errors = runner._poll_task("http://dataagent", "task_1", 5)

    assert task["task_status"] == "finished"
    assert errors == []
    assert calls["task"] == 2


def test_deepeval_extracts_evidence_from_bash_script_text_outputs(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    actual_sql = "select count(1) from public.dim_tech_public_env_cmp_df"
    chart_spec = {
        "kind": "chart_spec",
        "chart_type": "bar",
        "dataset": [{"env_name": "PROD", "cmp_cnt": 301}],
        "series": [{"name": "组件数", "field": "cmp_cnt"}],
    }
    blocks = [
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {
                "command": (
                    '"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/run_sql.py" '
                    f'--database public --engine mysql --sql "{actual_sql}"'
                )
            },
            "output": [
                {
                    "type": "text",
                    "text": json.dumps(
                        {
                            "kind": "sql_execution",
                            "sql": actual_sql,
                            "rows": [{"cmp_cnt": 301}],
                        },
                        ensure_ascii=False,
                    ),
                }
            ],
        },
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {"command": "build_chart_spec.py --chart-type bar"},
            "output": json.dumps(chart_spec, ensure_ascii=False),
        },
    ]

    assert runner._extract_sql_outputs(blocks, "") == [actual_sql]
    assert runner._extract_chart_outputs(blocks) == [chart_spec]


def test_deepeval_run_case_uses_recovered_task_answer(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()

    def fake_http_json(method, url, payload=None, **kwargs):
        if url.endswith("/api/v1/nl2sql/topics") and method == "POST":
            return {"topic_id": "topic_1"}
        if url.endswith("/api/v1/nl2sql/tasks/deliver-message"):
            return {"task_id": "task_1", "accepted": True}
        if url == "http://dataagent/api/v1/nl2sql/tasks/task_1":
            return {
                "task_id": "task_1",
                "topic_id": "topic_1",
                "task_status": "suspended",
                "error": {"code": "task_recovered", "message": "任务租约已过期，已转移到 task_2"},
            }
        if url == "http://dataagent/api/v1/nl2sql/topics/topic_1":
            return {"topic_id": "topic_1", "current_task_id": "task_2", "current_task_status": "finished"}
        if url == "http://dataagent/api/v1/nl2sql/tasks/task_2":
            return {"task_id": "task_2", "topic_id": "topic_1", "task_status": "finished"}
        if url.startswith("http://dataagent/api/v1/nl2sql/topics/topic_1/messages"):
            return {
                "items": [
                    {"sender_type": "assistant", "task_id": "task_1", "content": ""},
                    {
                        "sender_type": "assistant",
                        "task_id": "task_2",
                        "content": "recovered answer",
                        "blocks": [{"type": "tool_use", "tool_id": "toolu_1", "tool_name": "run_sql", "input": {"sql": "select 1"}, "output": "ok", "is_error": False}],
                    },
                ]
            }
        raise AssertionError(f"unexpected request: {method} {url}")

    monkeypatch.setattr(runner, "http_json", fake_http_json)

    result = runner.run_case(
        "http://dataagent",
        _sample_case(),
        types.SimpleNamespace(agent_id="agent_eval", provider_id="", model="", timeout_seconds=5),
    )

    assert result["task_id"] == "task_2"
    assert result["task_status"] == "finished"
    assert result["final_answer"] == "recovered answer"
    assert result["errors"] == []


def test_deepeval_auto_rule_check_adds_generic_failure_attribution(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()

    result = runner.auto_rule_check(
        _sample_case(),
        final_answer="当前 OpenDataWorks 平台元数据未找到目标。请在目标数据库中执行 SQL：SELECT ... WHERE ds = '{target_date}'",
        blocks=[{"type": "tool_use", "tool_name": "run_sql", "output": {"row_count": 0}}],
        sql_outputs=["SELECT count(1) FROM opendataworks.workflow_publish_record WHERE ds = '{target_date}'"],
        tool_names=[],
    )

    assert {"sql_only", "wrong_domain", "placeholder_leak", "empty_result"}.issubset(
        set(result["failure_attribution"])
    )


def test_deepeval_run_cases_parallel_preserves_dataset_order_and_records_crashes(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    calls = []
    cases = [
        {**_sample_case(), "case_id": "CASE_SLOW"},
        {**_sample_case(), "case_id": "CASE_FAST"},
        {**_sample_case(), "case_id": "CASE_CRASH"},
    ]

    def fake_run_case(base_url, case, args):
        calls.append(case["case_id"])
        if case["case_id"] == "CASE_SLOW":
            time.sleep(0.05)
        if case["case_id"] == "CASE_CRASH":
            raise RuntimeError("case crashed")
        return {
            "case_id": case["case_id"],
            "category": case.get("category"),
            "question": case.get("question"),
            "task_status": "finished",
            "final_answer": "ok",
            "tool_names": [],
            "sql_outputs": [],
            "chart_outputs": [],
            "usage": {},
            "duration_seconds": 0,
            "auto_rule_check": {"passed": True, "failure_attribution": []},
            "judge": {},
            "veto_rules_triggered": [],
            "case_passed": False,
            "errors": [],
        }

    monkeypatch.setattr(runner, "run_case", fake_run_case)

    results = runner._run_cases("http://dataagent", cases, types.SimpleNamespace(concurrency=2))

    assert [item["case_id"] for item in results] == ["CASE_SLOW", "CASE_FAST", "CASE_CRASH"]
    assert set(calls) == {"CASE_SLOW", "CASE_FAST", "CASE_CRASH"}
    assert results[2]["task_status"] == "runner_error"
    assert results[2]["errors"] == [{"code": "runner_crash", "message": "case crashed"}]


def test_deepeval_dry_run_writes_unified_report(tmp_path, monkeypatch):
    calls = _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(json.dumps(_sample_case(), ensure_ascii=False) + "\n", encoding="utf-8")
    output_dir = tmp_path / "out"

    code = runner.main(["--dry-run", "--dataset", str(dataset), "--output-dir", str(output_dir)])

    assert code == 0
    summary = json.loads((output_dir / "summary.json").read_text(encoding="utf-8"))
    assert summary["total_cases"] == 1
    assert summary["dataset_valid"] is True
    assert summary["engine"] == "deepeval"
    assert (output_dir / "report.md").exists()
    assert calls["test_cases"] == []


def test_deepeval_missing_dataset_argument_returns_exit_2(tmp_path, capsys, monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()

    code = runner.main(["--dry-run", "--output-dir", str(tmp_path)])

    assert code == 2
    captured = capsys.readouterr()
    assert "--dataset" in captured.err


def test_deepeval_default_output_root_prefers_mounted_workspace(tmp_path, monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    workspace = tmp_path / "workspace"
    (workspace / "scripts").mkdir(parents=True)
    monkeypatch.chdir(workspace)

    root = runner._repo_or_package_root()

    assert root == workspace
    assert str(runner.default_output_dir(root)).startswith(str(workspace / "reports" / "dataagent-evals"))


def test_deepeval_runner_drives_dataagent_and_writes_case_outputs(tmp_path, monkeypatch):
    calls = _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(json.dumps(_sample_case(), ensure_ascii=False) + "\n", encoding="utf-8")
    output_dir = tmp_path / "out"

    def fake_judge(config, payload):
        return {
            "score": 9,
            "dimension_scores": {
                "intent": 1,
                "ontology_entity": 1,
                "relation_scope": 1,
                "sql_or_tool_call": 2,
                "data_accuracy": 2,
                "reasoning": 2,
                "answer_quality": 1,
            },
            "hallucination": False,
            "veto_rules_triggered": [],
            "failure_attribution": [],
            "comment": "ok",
        }

    monkeypatch.setattr(runner, "call_judge_model", fake_judge)

    class Handler(BaseHTTPRequestHandler):
        def _json(self, payload, code=200):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):
            return

        def do_GET(self):
            if self.path == "/api/v1/nl2sql/health":
                self._json({"status": "ok"})
            elif self.path == "/api/v1/nl2sql-admin/settings":
                self._json({"provider_id": "fake", "model": "fake-model"})
            elif self.path == "/api/v1/nl2sql/tasks/task_1":
                self._json({"task_id": "task_1", "task_status": "finished"})
            elif self.path.startswith("/api/v1/nl2sql/topics/topic_1/messages"):
                self._json(
                    {
                        "items": [
                            {
                                "sender_type": "assistant",
                                "task_id": "task_1",
                                "content": "最近 30 天工作流发布次数按日期聚合如下。",
                                "usage": {"input_tokens": 1, "output_tokens": 2},
                                "blocks": [
                                    {"type": "tool_use", "tool_id": "toolu_1", "tool_name": "run_sql", "input": {"sql": "select 1"}, "output": "ok", "is_error": False},
                                    {"type": "main_text", "text": "最近 30 天工作流发布次数按日期聚合如下。"},
                                ],
                            }
                        ]
                    }
                )
            else:
                self._json({"error": self.path}, code=404)

        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length:
                self.rfile.read(length)
            if self.path == "/api/v1/nl2sql/topics":
                self._json({"topic_id": "topic_1"})
            elif self.path == "/api/v1/nl2sql/tasks/deliver-message":
                self._json({"task_id": "task_1", "accepted": True})
            else:
                self._json({"error": self.path}, code=404)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        code = runner.main(
            [
                "--base-url",
                f"http://127.0.0.1:{server.server_port}",
                "--dataset",
                str(dataset),
                "--output-dir",
                str(output_dir),
                "--agent-id",
                "agent_eval",
                "--judge-base-url",
                "http://judge",
                "--judge-token",
                "token",
                "--judge-model",
                "model",
            ]
        )
    finally:
        server.shutdown()
        thread.join(timeout=5)

    assert code == 0
    summary = json.loads((output_dir / "summary.json").read_text(encoding="utf-8"))
    assert summary["passed"] is True
    assert summary["passed_cases"] == 1
    result = json.loads((output_dir / "cases.jsonl").read_text(encoding="utf-8").strip())
    assert result["task_status"] == "finished"
    assert result["judge"]["score"] == 9.0
    assert result["case_passed"] is True
    # Offline runner drives the judge metric locally; DeepEval's cloud-coupled
    # evaluate() is never invoked.
    assert calls["test_cases"] == []
