from __future__ import annotations

import importlib.util
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
RUNNER_PATH = REPO_ROOT / "tools" / "dataagent-evals" / "builtin" / "run.py"


def _sample_case(case_id: str = "ODW_SAMPLE_001"):
    return {
        "case_id": case_id,
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
    }


def _write_dataset(path: Path, case_id: str = "ODW_SAMPLE_001") -> Path:
    path.write_text(json.dumps(_sample_case(case_id), ensure_ascii=False) + "\n", encoding="utf-8")
    return path


def _load_runner():
    spec = importlib.util.spec_from_file_location("run_dataagent_evals", RUNNER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_dry_run_validates_external_dataset(tmp_path):
    runner = _load_runner()
    dataset = _write_dataset(tmp_path / "cases.jsonl")

    code = runner.main(
        [
            "--dry-run",
            "--dataset",
            str(dataset),
            "--output-dir",
            str(tmp_path),
        ]
    )

    assert code == 0
    summary = json.loads((tmp_path / "summary.json").read_text(encoding="utf-8"))
    assert summary["total_cases"] == 1
    assert summary["dataset_valid"] is True
    assert summary["unique_case_ids"] is True
    assert summary["scoring_total_valid"] is True
    assert (tmp_path / "report.md").exists()


def test_missing_dataset_argument_returns_exit_2(tmp_path, capsys):
    runner = _load_runner()

    code = runner.main(["--dry-run", "--output-dir", str(tmp_path)])

    assert code == 2
    captured = capsys.readouterr()
    assert "--dataset" in captured.err


def test_default_output_root_prefers_mounted_workspace(tmp_path, monkeypatch):
    runner = _load_runner()
    workspace = tmp_path / "workspace"
    (workspace / "scripts").mkdir(parents=True)
    monkeypatch.chdir(workspace)

    root = runner._repo_or_package_root()

    assert root == workspace
    assert str(runner.default_output_dir(root)).startswith(str(workspace / "reports" / "dataagent-evals"))


def test_judge_request_embeds_system_prompt_in_user_content(monkeypatch):
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


def test_poll_task_retries_transient_event_error(monkeypatch):
    runner = _load_runner()
    calls = {"task": 0, "events": 0}

    def fake_http_json(method, url, **kwargs):
        if "/events" in url:
            calls["events"] += 1
            if calls["events"] == 1:
                raise runner.EvalRunnerError("request failed events timeout")
            return {
                "task_id": "task-1",
                "task_status": "success",
                "next_after_seq": 1,
                "events": [{"seq_id": 1, "data": {"tool_name": "run_sql"}}],
            }
        calls["task"] += 1
        return {"task_id": "task-1", "task_status": "running" if calls["task"] == 1 else "success"}

    monkeypatch.setattr(runner, "http_json", fake_http_json)

    task, events, errors = runner._poll_task("http://dataagent", "task-1", 5)

    assert task["task_status"] == "success"
    assert events == [{"seq_id": 1, "data": {"tool_name": "run_sql"}}]
    assert errors == []


def test_auto_rule_check_adds_generic_failure_attribution():
    runner = _load_runner()

    result = runner.auto_rule_check(
        _sample_case(),
        final_answer="当前 OpenDataWorks 平台元数据未找到目标。请在目标数据库中执行 SQL：SELECT ... WHERE ds = '{target_date}'",
        events=[{"data": {"output": {"row_count": 0}}}],
        sql_outputs=["SELECT count(1) FROM opendataworks.workflow_publish_record WHERE ds = '{target_date}'"],
        tool_names=[],
    )

    assert {"sql_only", "wrong_domain", "placeholder_leak", "empty_result"}.issubset(
        set(result["failure_attribution"])
    )


class _FakeDataAgentHandler(BaseHTTPRequestHandler):
    scenario = "success"
    task_poll_count = 0

    def log_message(self, format, *args):  # noqa: A002
        return

    def _send(self, status: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        if self.path == "/api/v1/nl2sql/health":
            if self.scenario == "preflight_error":
                self._send(503, {"status": "down"})
                return
            self._send(200, {"status": "ok"})
            return
        if self.path == "/api/v1/nl2sql-admin/settings":
            self._send(200, {"provider_id": "openrouter", "model": "anthropic/claude-sonnet-4.5"})
            return
        if self.path.startswith("/api/v1/nl2sql/tasks/task-1/events"):
            self._send(
                200,
                {
                    "task_id": "task-1",
                    "task_status": "success",
                    "after_seq": 0,
                    "next_after_seq": 2,
                    "has_more": False,
                    "events": [
                        {
                            "record_type": "event",
                            "seq_id": 1,
                            "event_type": "BEFORE_TOOL_CALL",
                            "data": {"tool_name": "run_sql", "input": {"sql": "select count(1) from opendataworks.workflow_publish_record"}},
                        },
                        {
                            "record_type": "event",
                            "seq_id": 2,
                            "event_type": "AFTER_TOOL_CALL",
                            "data": {"output": {"rows": [{"cnt": 1}], "sql": "select count(1) from opendataworks.workflow_publish_record"}},
                        },
                    ],
                },
            )
            return
        if self.path == "/api/v1/nl2sql/tasks/task-1":
            self.__class__.task_poll_count += 1
            if self.scenario == "timeout":
                self._send(200, {"task_id": "task-1", "topic_id": "topic-1", "task_status": "running"})
                return
            if self.scenario == "task_failed":
                self._send(
                    200,
                    {
                        "task_id": "task-1",
                        "topic_id": "topic-1",
                        "task_status": "failed",
                        "error": {"message": "boom"},
                    },
                )
                return
            self._send(200, {"task_id": "task-1", "topic_id": "topic-1", "task_status": "success", "usage": {"input_tokens": 1}})
            return
        if self.path.startswith("/api/v1/nl2sql/topics/topic-1/messages"):
            self._send(
                200,
                {
                    "topic_id": "topic-1",
                    "page": 1,
                    "page_size": 200,
                    "order": "asc",
                    "total": 2,
                    "items": [
                        {"message_id": "m1", "topic_id": "topic-1", "sender_type": "user", "type": "chat", "status": "success", "content": "q"},
                        {
                            "message_id": "m2",
                            "topic_id": "topic-1",
                            "task_id": "task-1",
                            "sender_type": "assistant",
                            "type": "assistant",
                            "status": "success",
                            "content": "answer with opendataworks.workflow_publish_record",
                            "usage": {"input_tokens": 1, "output_tokens": 2},
                        },
                    ],
                },
            )
            return
        self._send(404, {"detail": self.path})

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if self.path == "/api/v1/nl2sql/topics":
            self._send(200, {"topic_id": "topic-1", "title": "eval", "chat_topic_id": "chat-1", "chat_conversation_id": "conv-1"})
            return
        if self.path == "/api/v1/nl2sql/tasks/deliver-message":
            self._send(
                200,
                {
                    "accepted": True,
                    "topic_id": "topic-1",
                    "task_id": "task-1",
                    "task_status": "waiting",
                    "user_message_id": "m1",
                    "assistant_message_id": "m2",
                },
            )
            return
        self._send(404, {"detail": self.path})


class FakeServer:
    def __init__(self, scenario: str):
        self.scenario = scenario
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), _FakeDataAgentHandler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    def __enter__(self):
        _FakeDataAgentHandler.scenario = self.scenario
        _FakeDataAgentHandler.task_poll_count = 0
        self.thread.start()
        host, port = self.httpd.server_address
        return f"http://{host}:{port}"

    def __exit__(self, exc_type, exc, tb):
        self.httpd.shutdown()
        self.thread.join(timeout=5)
        self.httpd.server_close()


class _FakeJudgeHandler(BaseHTTPRequestHandler):
    scenario = "success"
    paths: list[str] = []

    def log_message(self, format, *args):  # noqa: A002
        return

    def _send(self, status: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):  # noqa: N802
        self.__class__.paths.append(self.path)
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if self.path != "/v1/messages":
            self._send(404, {"detail": self.path})
            return
        if self.scenario == "judge_failed":
            text = json.dumps(
                {
                    "score": 0,
                    "dimension_scores": {},
                    "hallucination": False,
                    "veto_rules_triggered": [],
                    "failure_attribution": ["judge_failed"],
                    "comment": "judge failed",
                    "judge_failed": True,
                },
                ensure_ascii=False,
            )
        else:
            veto = ["工具失败或数据不足后仍输出确定性结论。"] if self.scenario == "veto" else []
            text = json.dumps(
                {
                    "score": 9 if not veto else 8,
                    "dimension_scores": {
                        "intent": 1,
                        "ontology_entity": 1,
                        "relation_scope": 1,
                        "sql_or_tool_call": 2,
                        "data_accuracy": 2,
                        "reasoning": 2 if not veto else 1,
                        "answer_quality": 1,
                    },
                    "hallucination": False,
                    "veto_rules_triggered": veto,
                    "failure_attribution": [],
                    "comment": "ok",
                    "judge_failed": False,
                },
                ensure_ascii=False,
            )
        self._send(200, {"content": [{"type": "text", "text": text}]})


class FakeJudgeServer:
    def __init__(self, scenario: str):
        self.scenario = scenario
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), _FakeJudgeHandler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    def __enter__(self):
        _FakeJudgeHandler.scenario = self.scenario
        _FakeJudgeHandler.paths = []
        self.thread.start()
        host, port = self.httpd.server_address
        return f"http://{host}:{port}"

    def __exit__(self, exc_type, exc, tb):
        self.httpd.shutdown()
        self.thread.join(timeout=5)
        self.httpd.server_close()


def _run_fake_scenario(tmp_path, scenario: str, *extra_args: str):
    runner = _load_runner()
    dataset = _write_dataset(tmp_path / "cases.jsonl", case_id="ODW_SAMPLE_002")
    with FakeServer(scenario) as base_url, FakeJudgeServer(scenario) as judge_url:
        return runner.main(
            [
                "--base-url",
                base_url,
                "--dataset",
                str(dataset),
                "--output-dir",
                str(tmp_path),
                "--judge-base-url",
                judge_url,
                "--judge-token",
                "token",
                "--judge-model",
                "judge-model",
                "--case",
                "ODW_SAMPLE_002",
                *extra_args,
            ]
        )


def test_fake_http_success_generates_passing_report(tmp_path):
    code = _run_fake_scenario(tmp_path, "success")

    assert code == 0
    assert _FakeJudgeHandler.paths == ["/v1/messages"]
    assert (tmp_path / "cases.jsonl").exists()
    summary = json.loads((tmp_path / "summary.json").read_text(encoding="utf-8"))
    assert summary["passed"] is True
    assert summary["recommendation"] == "建议上线"


def test_task_failure_generates_report_and_exit_1(tmp_path):
    code = _run_fake_scenario(tmp_path, "task_failed")

    assert code == 1
    result = json.loads((tmp_path / "cases.jsonl").read_text(encoding="utf-8").splitlines()[0])
    assert result["case_passed"] is False
    assert result["task_status"] == "failed"


def test_timeout_generates_case_error_and_exit_1(tmp_path):
    code = _run_fake_scenario(tmp_path, "timeout", "--timeout-seconds", "1")

    assert code == 1
    result = json.loads((tmp_path / "cases.jsonl").read_text(encoding="utf-8").splitlines()[0])
    assert result["case_passed"] is False
    assert "timeout" in result["errors"][0]["code"]


def test_judge_failed_marks_case_failed(tmp_path):
    code = _run_fake_scenario(tmp_path, "judge_failed")

    assert code == 1
    result = json.loads((tmp_path / "cases.jsonl").read_text(encoding="utf-8").splitlines()[0])
    assert result["judge"]["judge_failed"] is True
    assert result["case_passed"] is False


def test_veto_marks_report_not_recommended(tmp_path):
    code = _run_fake_scenario(tmp_path, "veto")

    assert code == 1
    summary = json.loads((tmp_path / "summary.json").read_text(encoding="utf-8"))
    assert summary["recommendation"] == "不建议上线"
    assert summary["veto_count"] == 1


def test_preflight_error_returns_exit_2(tmp_path):
    code = _run_fake_scenario(tmp_path, "preflight_error")

    assert code == 2
