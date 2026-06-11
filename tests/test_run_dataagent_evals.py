from __future__ import annotations

import importlib.util
import json
import threading
import time
import types
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
RUNNER_PATH = REPO_ROOT / "tools" / "dataagent-evals" / "builtin" / "run.py"

_RUN_SQL = "select count(1) from opendataworks.workflow_publish_record"


def _assistant_blocks(answer: str):
    """Chat V2 server-projected blocks attached to an assistant message."""
    return [
        {
            "type": "tool_use",
            "tool_id": "toolu_1",
            "tool_name": "run_sql",
            "input": {"sql": _RUN_SQL},
            "output": [{"type": "text", "text": json.dumps({"rows": [{"cnt": 1}], "sql": _RUN_SQL})}],
            "is_error": False,
        },
        {"type": "main_text", "text": answer},
    ]


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


def test_non_dry_run_requires_agent_id(tmp_path, capsys):
    runner = _load_runner()
    dataset = _write_dataset(tmp_path / "cases.jsonl")

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


def test_agent_id_can_default_from_environment(monkeypatch):
    runner = _load_runner()
    monkeypatch.setenv("DATAAGENT_EVAL_AGENT_ID", "agent_eval")

    args = runner.parse_args(["--dry-run", "--dataset", "cases.jsonl"])

    assert args.agent_id == "agent_eval"


def test_topic_and_task_requests_include_agent_id(monkeypatch):
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


def test_default_judge_timeout_is_long_enough_for_slow_judge(monkeypatch):
    runner = _load_runner()
    monkeypatch.delenv("DATAAGENT_EVAL_JUDGE_TIMEOUT_SECONDS", raising=False)

    args = runner.parse_args(["--dry-run", "--dataset", "cases.jsonl"])
    config = runner._judge_config_from_args(
        types.SimpleNamespace(
            judge_base_url="http://judge",
            judge_token="token",
            judge_model="model",
            judge_timeout_seconds=args.judge_timeout_seconds,
            judge_max_tokens=args.judge_max_tokens,
        )
    )

    assert config.timeout_seconds == 300


def test_http_json_wraps_socket_timeout_as_eval_runner_error(monkeypatch):
    runner = _load_runner()

    def fake_urlopen(*args, **kwargs):
        raise TimeoutError("timed out")

    monkeypatch.setattr(runner.urllib.request, "urlopen", fake_urlopen)

    try:
        runner.http_json("POST", "http://judge/v1/messages", {"model": "m"}, timeout=1)
    except runner.EvalRunnerError as exc:
        assert "request timed out" in str(exc)
        assert exc.exit_code == 2
    else:
        raise AssertionError("expected EvalRunnerError")


def test_compact_judge_payload_bounds_large_evidence_payload():
    runner = _load_runner()
    payload = {
        "case": _sample_case(),
        "user_question": "最近 30 天工作流发布次数趋势",
        "final_answer": "answer-" + ("x" * 50000),
        "task_status": "success",
        "task_error": None,
        "tool_events": [
            {
                "seq_id": i,
                "event_type": "AFTER_TOOL_CALL",
                "tool_name": "run_sql",
                "output": {
                    "sql": "SELECT " + ("col, " * 1000) + "1",
                    "rows": [{"value": "y" * 1000} for _ in range(50)],
                    "summary": "返回 50 行结果",
                },
            }
            for i in range(120)
        ],
        "sql_outputs": ["SELECT " + ("x" * 10000) for _ in range(60)],
        "chart_outputs": [{"rows": ["z" * 1000 for _ in range(30)]} for _ in range(20)],
        "auto_rule_check": {"passed": True, "failure_attribution": []},
    }

    compact = runner._compact_judge_payload(payload)
    serialized = json.dumps(compact, ensure_ascii=False)

    assert len(serialized) < 80000
    assert compact["case"]["case_id"] == "ODW_SAMPLE_001"
    assert len(compact["final_answer"]) < len(payload["final_answer"])
    assert len(compact["tool_events"]) <= 80
    assert len(compact["sql_outputs"]) <= 20
    assert len(compact["chart_outputs"]) <= 5
    assert "truncated" in serialized


def test_poll_task_tolerates_transient_status_error(monkeypatch):
    runner = _load_runner()
    calls = {"task": 0}

    def fake_http_json(method, url, **kwargs):
        calls["task"] += 1
        if calls["task"] == 1:
            raise runner.EvalRunnerError("request failed transient status")
        return {"task_id": "task-1", "task_status": "success"}

    monkeypatch.setattr(runner, "http_json", fake_http_json)
    monkeypatch.setattr(runner.time, "sleep", lambda *_: None)

    task, errors = runner._poll_task("http://dataagent", "task-1", 5)

    assert task["task_status"] == "success"
    assert errors == []
    assert calls["task"] == 2


def test_auto_rule_check_adds_generic_failure_attribution():
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


def test_extract_sql_outputs_ignores_reference_text_and_uses_tool_sql():
    runner = _load_runner()
    actual_sql = (
        "SELECT COUNT(node_name) AS node_cnt "
        "FROM public.dim_tech_env_workflow_df "
        "WHERE env_name = 'DEV'"
    )
    blocks = [
        {
            "type": "main_text",
            "seq_id": 1,
            "text": "技能说明：不要使用 SELECT *，模板中的 <SQL> 不是实际执行 SQL。",
        },
        {
            "type": "tool_use",
            "seq_id": 2,
            "tool_id": "toolu_1",
            "tool_name": "run_sql",
            "input": {"sql": actual_sql},
            "output": [{"type": "text", "text": json.dumps({"rows": [{"node_cnt": 10}]})}],
            "is_error": False,
        },
    ]

    sql_outputs = runner._extract_sql_outputs(blocks, "当前 DEV 环境共有 10 个工作流。")

    assert sql_outputs == [actual_sql]


def test_extract_evidence_from_bash_script_text_outputs():
    runner = _load_runner()
    actual_sql = "select count(1) from public.dim_tech_env_workflow_df"
    chart_spec = {
        "kind": "chart_spec",
        "chart_type": "bar",
        "dataset": [{"env_name": "DEV", "node_cnt": 10}],
        "series": [{"name": "工作流数", "field": "node_cnt"}],
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
                            "rows": [{"node_cnt": 10}],
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


def test_auto_rule_check_ignores_sql_style_forbidden_patterns():
    runner = _load_runner()
    case = {
        **_sample_case(),
        "required_sql_fragments": ["public.dim_tech_env_workflow_df"],
        "forbidden_sql_patterns": [r"(?i)select\s+\*"],
    }
    actual_sql = (
        "SELECT * "
        "FROM public.dim_tech_env_workflow_df "
        "WHERE env_name = 'DEV'"
    )
    blocks = [
        {
            "type": "main_text",
            "seq_id": 1,
            "text": "参考文档提示：避免 SELECT *；OpenDataWorks 平台元数据不是本题答案。",
        }
    ]

    result = runner.auto_rule_check(
        case,
        final_answer="当前 DEV 环境共有 10 个工作流。",
        blocks=blocks,
        sql_outputs=[actual_sql],
        tool_names=["run_sql"],
    )

    assert result["passed"] is True
    assert result["forbidden_sql_patterns"] == []
    assert "forbidden_sql" not in result["failure_attribution"]
    assert "wrong_domain" not in result["failure_attribution"]


def test_run_case_submits_turns_in_order(monkeypatch):
    runner = _load_runner()
    case = {
        **_sample_case("ODW_SAMPLE_MULTITURN_001"),
        "question": "工作流发布趋势多轮分析",
        "turns": ["最近 30 天工作流发布次数趋势如何？", "其中发布次数最多的是哪一天？"],
    }
    submitted_contents = []

    def fake_http_json(method, url, payload=None, **kwargs):
        if url.endswith("/api/v1/nl2sql/topics") and method == "POST":
            return {"topic_id": "topic_1"}
        if url.endswith("/api/v1/nl2sql/tasks/deliver-message"):
            submitted_contents.append(payload["content"])
            return {"task_id": f"task_{len(submitted_contents)}", "accepted": True}
        if url == "http://dataagent/api/v1/nl2sql/tasks/task_1":
            return {"task_id": "task_1", "topic_id": "topic_1", "task_status": "finished"}
        if url == "http://dataagent/api/v1/nl2sql/tasks/task_2":
            return {"task_id": "task_2", "topic_id": "topic_1", "task_status": "finished"}
        if url.startswith("http://dataagent/api/v1/nl2sql/topics/topic_1/messages"):
            return {
                "items": [
                    {"sender_type": "user", "content": submitted_contents[0]},
                    {"sender_type": "assistant", "task_id": "task_1", "content": "第一轮答案"},
                    {"sender_type": "user", "content": submitted_contents[1]},
                    {"sender_type": "assistant", "task_id": "task_2", "content": "第二轮答案", "blocks": []},
                ]
            }
        raise AssertionError(f"unexpected request: {method} {url}")

    def fake_judge_case(judge_config, case, payload):
        assert payload["user_question"] == "工作流发布趋势多轮分析"
        assert payload["final_answer"] == "第二轮答案"
        return {
            "score": 9,
            "dimension_scores": {"intent": 1},
            "hallucination": False,
            "veto_rules_triggered": [],
            "failure_attribution": [],
            "comment": "ok",
            "judge_failed": False,
        }

    monkeypatch.setattr(runner, "http_json", fake_http_json)
    monkeypatch.setattr(runner, "_judge_case", fake_judge_case)

    result = runner.run_case(
        "http://dataagent",
        case,
        types.SimpleNamespace(agent_id="agent_eval", provider_id="", model="", timeout_seconds=5),
        runner.JudgeConfig(base_url="http://judge", token="t", model="m"),
    )

    assert submitted_contents == case["turns"]
    assert result["task_id"] == "task_2"
    assert result["final_answer"] == "第二轮答案"
    assert result["turns"] == case["turns"]
    assert result["case_passed"] is True
    assert result["errors"] == []


def test_judge_payload_removes_sql_style_veto_rules():
    runner = _load_runner()
    case = {
        **_sample_case(),
        "veto_rules": [
            "编造不存在的数据。",
            "SQL 不带 schema 前缀、使用 SELECT * 或明显违反当前 skill SQL 硬规则。",
        ],
        "forbidden_sql_patterns": [r"(?i)select\s+\*"],
    }

    payload_case = runner._case_for_judge(case)

    assert "forbidden_sql_patterns" not in payload_case
    assert payload_case["veto_rules"] == ["编造不存在的数据。"]


def test_summarize_tool_events_drops_reasoning_noise_and_keeps_evidence():
    runner = _load_runner()
    blocks = [
        {"type": "thinking", "seq_id": 1, "text": "reasoning " + ("x" * 20000)},
        {"type": "main_text", "seq_id": 2, "text": "答案文本"},
        {
            "type": "tool_use",
            "seq_id": 10,
            "tool_id": "toolu_1",
            "tool_name": "run_sql",
            "is_error": False,
            "input": {
                "sql": "select count(1) from public.dim_tech_env_workflow_df",
                "description": "count workflows",
            },
            "output": {
                "kind": "sql_execution",
                "sql": "select count(1) from public.dim_tech_env_workflow_df",
                "columns": ["cnt"],
                "rows": [{"cnt": 10}],
                "row_count": 1,
                "result_state": "success",
                "summary": "返回 1 行结果",
            },
        },
    ]

    summary = runner._summarize_tool_events(blocks)
    serialized = json.dumps(summary, ensure_ascii=False)

    assert len(serialized) < 4000
    assert "xxxxxxxxxx" not in serialized
    assert summary == [
        {
            "seq_id": 1,
            "tool_name": "run_sql",
            "input": {
                "sql": "select count(1) from public.dim_tech_env_workflow_df",
                "description": "count workflows",
            },
            "output": {
                "kind": "sql_execution",
                "sql": "select count(1) from public.dim_tech_env_workflow_df",
                "columns": ["cnt"],
                "rows": [{"cnt": 10}],
                "row_count": 1,
                "result_state": "success",
                "summary": "返回 1 行结果",
            },
        },
    ]


def test_run_cases_parallel_preserves_dataset_order_and_records_crashes(monkeypatch):
    runner = _load_runner()
    calls = []
    cases = [
        _sample_case("CASE_SLOW"),
        _sample_case("CASE_FAST"),
        _sample_case("CASE_CRASH"),
    ]

    def fake_run_case(base_url, case, args, judge_config):
        calls.append(case["case_id"])
        if case["case_id"] == "CASE_SLOW":
            time.sleep(0.05)
        if case["case_id"] == "CASE_CRASH":
            raise RuntimeError("case crashed")
        return {
            "case_id": case["case_id"],
            "category": case.get("category"),
            "question": case.get("question"),
            "task_status": "success",
            "final_answer": "ok",
            "tool_names": [],
            "sql_outputs": [],
            "chart_outputs": [],
            "usage": {},
            "duration_seconds": 0,
            "auto_rule_check": {"passed": True, "failure_attribution": []},
            "judge": {"score": 9, "judge_failed": False, "failure_attribution": []},
            "veto_rules_triggered": [],
            "case_passed": True,
            "errors": [],
        }

    monkeypatch.setattr(runner, "run_case", fake_run_case)

    results = runner._run_cases(
        "http://dataagent",
        cases,
        types.SimpleNamespace(concurrency=2),
        runner.JudgeConfig(base_url="http://judge", token="t", model="m"),
    )

    assert [item["case_id"] for item in results] == ["CASE_SLOW", "CASE_FAST", "CASE_CRASH"]
    assert set(calls) == {"CASE_SLOW", "CASE_FAST", "CASE_CRASH"}
    assert results[2]["task_status"] == "runner_error"
    assert results[2]["judge"]["judge_failed"] is True
    assert results[2]["errors"] == [{"code": "runner_crash", "message": "case crashed"}]


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
        if self.path == "/api/v1/nl2sql/topics/topic-1":
            self._send(
                200,
                {
                    "topic_id": "topic-1",
                    "title": "eval",
                    "chat_topic_id": "chat-1",
                    "chat_conversation_id": "conv-1",
                    "current_task_id": "task-2" if self.scenario == "recovered" else "task-1",
                    "current_task_status": "success" if self.scenario == "recovered" else "running",
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
            if self.scenario == "recovered":
                self._send(
                    200,
                    {
                        "task_id": "task-1",
                        "topic_id": "topic-1",
                        "task_status": "suspended",
                        "error": {"code": "task_recovered", "message": "任务租约已过期，已转移到 task-2"},
                    },
                )
                return
            self._send(200, {"task_id": "task-1", "topic_id": "topic-1", "task_status": "success", "usage": {"input_tokens": 1}})
            return
        if self.path == "/api/v1/nl2sql/tasks/task-2":
            self._send(200, {"task_id": "task-2", "topic_id": "topic-1", "task_status": "success", "usage": {"input_tokens": 1}})
            return
        if self.path.startswith("/api/v1/nl2sql/topics/topic-1/messages"):
            if self.scenario == "recovered":
                self._send(
                    200,
                    {
                        "topic_id": "topic-1",
                        "page": 1,
                        "page_size": 200,
                        "order": "asc",
                        "total": 3,
                        "items": [
                            {"message_id": "m1", "topic_id": "topic-1", "sender_type": "user", "type": "chat", "status": "success", "content": "q"},
                            {
                                "message_id": "m2",
                                "topic_id": "topic-1",
                                "task_id": "task-1",
                                "sender_type": "assistant",
                                "type": "assistant",
                                "status": "suspended",
                                "content": "",
                            },
                            {
                                "message_id": "m3",
                                "topic_id": "topic-1",
                                "task_id": "task-2",
                                "sender_type": "assistant",
                                "type": "assistant",
                                "status": "success",
                                "content": "answer with opendataworks.workflow_publish_record",
                                "usage": {"input_tokens": 1, "output_tokens": 2},
                                "blocks": _assistant_blocks("answer with opendataworks.workflow_publish_record"),
                            },
                        ],
                    },
                )
                return
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
                            "blocks": _assistant_blocks("answer with opendataworks.workflow_publish_record"),
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
                "--agent-id",
                "agent_eval",
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

    # Assert conversations directory and files
    conversations_dir = tmp_path / "conversations"
    assert conversations_dir.is_dir()
    case_file = conversations_dir / "ODW_SAMPLE_002.json"
    assert case_file.exists()
    case_data = json.loads(case_file.read_text(encoding="utf-8"))
    assert case_data["case_id"] == "ODW_SAMPLE_002"
    assert case_data["case_passed"] is True
    assert isinstance(case_data["conversation"], list)
    assert len(case_data["conversation"]) == 2
    assert case_data["conversation"][0]["role"] == "user"
    assert case_data["conversation"][0]["content"] == "q"
    assert case_data["conversation"][1]["role"] == "assistant"
    assert case_data["conversation"][1]["content"] == "answer with opendataworks.workflow_publish_record"


def test_task_failure_generates_report_and_exit_1(tmp_path):
    code = _run_fake_scenario(tmp_path, "task_failed")

    assert code == 1
    result = json.loads((tmp_path / "cases.jsonl").read_text(encoding="utf-8").splitlines()[0])
    assert result["case_passed"] is False
    assert result["task_status"] == "failed"


def test_recovered_task_follows_replacement_and_scores_final_answer(tmp_path):
    code = _run_fake_scenario(tmp_path, "recovered")

    assert code == 0
    result = json.loads((tmp_path / "cases.jsonl").read_text(encoding="utf-8").splitlines()[0])
    assert result["task_id"] == "task-2"
    assert result["task_status"] == "success"
    assert result["final_answer"] == "answer with opendataworks.workflow_publish_record"
    assert result["errors"] == []


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
