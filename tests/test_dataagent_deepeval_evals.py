from __future__ import annotations

import importlib.util
import json
import threading
import sys
import types
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
RUNNER_PATH = REPO_ROOT / "evals" / "dataagent-arch-governance-deepeval" / "run.py"


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
        "case_id": "ARCH_ASSET_001",
        "category": "架构资产与口径查询",
        "question": "什么是分级保障组件？",
        "expected_intent": "咨询",
        "expected_ontology_objects": ["graded_assurance_component"],
        "expected_relations": [],
        "expected_sql_or_tool_behavior": ["说明 system_level IS NOT NULL"],
        "expected_answer_points": ["说明统计口径"],
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
        "judge_guidance": "看口径",
    }


def test_deepeval_case_conversion_uses_expected_fields(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    case = _sample_case()
    case_result = {
        "case_id": case["case_id"],
        "question": case["question"],
        "final_answer": "分级保障组件口径为 system_level IS NOT NULL",
        "tool_names": ["run_sql"],
        "sql_outputs": [{"sql": "select count(1)"}],
        "chart_outputs": [],
        "auto_rule_check": {"passed": True, "triggered_veto_rules": []},
    }

    test_case = runner.to_deepeval_test_case(case, case_result)

    assert test_case.input == case["question"]
    assert test_case.actual_output == case_result["final_answer"]
    expected = json.loads(test_case.expected_output)
    assert expected["case_id"] == "ARCH_ASSET_001"
    assert expected["expected_ontology_objects"] == ["graded_assurance_component"]
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
    metric = runner.DataAgentArchitectureMetric(runner.JudgeConfig(base_url="http://judge", token="t", model="m"))

    score = metric.measure(test_case)

    assert score == 0.8
    assert metric.is_successful() is True
    assert metric.case_judges["ARCH_ASSET_001"]["score"] == 8.0
    assert metric.case_judges["ARCH_ASSET_001"]["hallucination"] is False
    assert metric.case_judges["ARCH_ASSET_001"]["failure_attribution"] == ["minor_tool_gap"]
    assert calls[0]["case"]["case_id"] == "ARCH_ASSET_001"


def test_deepeval_apply_judges_uses_shared_results_when_metric_is_copied(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    runner.DataAgentArchitectureMetric.shared_case_judges = {
        "ARCH_ASSET_001": {
            "score": 8,
            "dimension_scores": {"intent": 1},
            "hallucination": False,
            "veto_rules_triggered": [],
            "failure_attribution": [],
            "comment": "ok",
            "judge_failed": False,
        }
    }
    metric = runner.DataAgentArchitectureMetric(runner.JudgeConfig(base_url="http://judge", token="t", model="m"))
    result = {
        "case_id": "ARCH_ASSET_001",
        "task_status": "finished",
        "errors": [],
        "auto_rule_check": {"triggered_veto_rules": []},
    }

    updated = runner._apply_judges([result], metric)

    assert updated[0]["judge"]["score"] == 8
    assert updated[0]["judge"]["judge_failed"] is False
    assert updated[0]["case_passed"] is True


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
        {"case": {"case_id": "ARCH_ASSET_001"}, "final_answer": "answer"},
    )

    body = calls[0]["payload"]
    assert result["judge_failed"] is False
    assert body["max_tokens"] == 2222
    assert "system" not in body
    assert "你是 DataAgent 架构治理在线评测裁判" in body["messages"][0]["content"]


def test_deepeval_poll_task_retries_transient_event_error(monkeypatch):
    _install_fake_deepeval(monkeypatch)
    runner = _load_runner()
    calls = {"task": 0, "events": 0}

    def fake_http_json(method, url, **kwargs):
        if "/events" in url:
            calls["events"] += 1
            if calls["events"] == 1:
                raise runner.EvalRunnerError("request failed events timeout")
            return {
                "task_id": "task_1",
                "task_status": "finished",
                "next_after_seq": 1,
                "events": [{"seq_id": 1, "data": {"tool_name": "run_sql"}}],
            }
        calls["task"] += 1
        return {"task_id": "task_1", "task_status": "running" if calls["task"] == 1 else "finished"}

    monkeypatch.setattr(runner, "http_json", fake_http_json)

    task, events, errors = runner._poll_task("http://dataagent", "task_1", 5)

    assert task["task_status"] == "finished"
    assert events == [{"seq_id": 1, "data": {"tool_name": "run_sql"}}]
    assert errors == []


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
            elif self.path.startswith("/api/v1/nl2sql/tasks/task_1/events"):
                self._json({"task_id": "task_1", "task_status": "finished", "next_after_seq": 1, "events": [{"data": {"tool_name": "run_sql"}}]})
            elif self.path == "/api/v1/nl2sql/tasks/task_1":
                self._json({"task_id": "task_1", "task_status": "finished"})
            elif self.path.startswith("/api/v1/nl2sql/topics/topic_1/messages"):
                self._json({"items": [{"sender_type": "assistant", "task_id": "task_1", "content": "分级保障组件口径为 system_level IS NOT NULL"}]})
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
    assert len(calls["test_cases"]) == 1
