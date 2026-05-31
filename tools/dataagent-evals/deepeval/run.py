#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Any

# Keep DeepEval fully offline for intranet deployments. These flags must be set
# before importing deepeval so the import never triggers telemetry, update
# checks, or Confident AI cloud coupling. The runner drives the judge metric
# itself and does not depend on any deepeval.com / Confident AI service.
os.environ.setdefault("DEEPEVAL_TELEMETRY_OPT_OUT", "YES")
os.environ.setdefault("DEEPEVAL_UPDATE_WARNING_OPT_OUT", "YES")
os.environ.setdefault("DEEPEVAL_DISABLE_PROGRESS_BAR", "YES")

try:
    from deepeval import evaluate as deepeval_evaluate
except Exception:  # pragma: no cover - exercised in environments without deepeval
    deepeval_evaluate = None

try:
    from deepeval.metrics import BaseMetric
except Exception:  # pragma: no cover
    BaseMetric = object  # type: ignore[assignment]

try:
    from deepeval.test_case import LLMTestCase
except Exception:  # pragma: no cover
    LLMTestCase = None  # type: ignore[assignment]


REQUIRED_CASE_FIELDS = {
    "case_id",
    "category",
    "question",
    "expected_intent",
    "expected_ontology_objects",
    "expected_relations",
    "expected_sql_or_tool_behavior",
    "expected_answer_points",
    "scoring",
    "veto_rules",
    "max_wait_seconds",
}
TERMINAL_STATUSES = {"success", "finished", "failed", "error", "suspended", "cancelled", "canceled"}
SUCCESS_STATUSES = {"success", "finished"}
GATES = {
    "average_score": 8.0,
    "intent_accuracy": 0.90,
    "ontology_accuracy": 0.90,
    "sql_tool_accuracy": 0.85,
    "data_precision": 0.90,
    "data_recall": 0.85,
    "reasoning_average": 4.0,
    "hallucination_rate": 0.05,
}
JUDGE_DIMENSIONS = (
    "intent",
    "ontology_entity",
    "relation_scope",
    "sql_or_tool_call",
    "data_accuracy",
    "reasoning",
    "answer_quality",
)


class EvalRunnerError(Exception):
    def __init__(self, message: str, *, exit_code: int = 2):
        super().__init__(message)
        self.exit_code = exit_code


@dataclass(frozen=True)
class JudgeConfig:
    base_url: str
    token: str
    model: str
    timeout_seconds: int = 120
    max_tokens: int = 4096


def _is_workspace_root(path: Path) -> bool:
    return (
        (path / "scripts").is_dir()
        or (path / "deploy").is_dir()
        or (path / "tools" / "dataagent-evals").is_dir()
    )


def _repo_or_package_root() -> Path:
    file_root = Path(__file__).resolve().parents[3]
    cwd = Path.cwd().resolve()
    for candidate in (cwd, *cwd.parents, file_root):
        if _is_workspace_root(candidate):
            return candidate
    return file_root


def _timestamp() -> str:
    return dt.datetime.now().strftime("%Y%m%d-%H%M%S")


def default_output_dir(root: Path) -> Path:
    return root / "reports" / "dataagent-evals" / f"deepeval-{_timestamp()}"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    root = _repo_or_package_root()
    parser = argparse.ArgumentParser(description="Run DataAgent evaluations with DeepEval.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8900", help="DataAgent backend base URL.")
    parser.add_argument(
        "--dataset",
        default=os.environ.get("DATAAGENT_EVAL_DATASET", ""),
        help="Required external private evaluation JSONL dataset path.",
    )
    parser.add_argument("--output-dir", default=str(default_output_dir(root)), help="Report output directory.")
    parser.add_argument("--case", action="append", dest="case_ids", default=[], help="Case ID to run. Can be repeated.")
    parser.add_argument("--agent-id", default=os.environ.get("DATAAGENT_EVAL_AGENT_ID", ""), help="Required DataAgent agent_id for non-dry-run evaluation tasks.")
    parser.add_argument("--provider-id", default="", help="Override DataAgent execution provider for evaluated tasks.")
    parser.add_argument("--model", default="", help="Override DataAgent execution model for evaluated tasks.")
    parser.add_argument("--timeout-seconds", type=int, default=900, help="Maximum wait per case.")
    parser.add_argument("--concurrency", type=int, default=1, help="Number of cases to run in parallel.")
    parser.add_argument("--judge-base-url", default=os.environ.get("DATAAGENT_EVAL_JUDGE_BASE_URL", ""), help="Anthropic-compatible judge base URL.")
    parser.add_argument("--judge-token", default=os.environ.get("DATAAGENT_EVAL_JUDGE_TOKEN", ""), help="Judge model API token.")
    parser.add_argument("--judge-model", default=os.environ.get("DATAAGENT_EVAL_JUDGE_MODEL", ""), help="Judge model name.")
    parser.add_argument("--judge-timeout-seconds", type=int, default=int(os.environ.get("DATAAGENT_EVAL_JUDGE_TIMEOUT_SECONDS", "120")), help="Judge request timeout.")
    parser.add_argument("--judge-max-tokens", type=int, default=int(os.environ.get("DATAAGENT_EVAL_JUDGE_MAX_TOKENS", "4096")), help="Judge response max tokens.")
    parser.add_argument("--dry-run", action="store_true", help="Validate dataset and output directory without service calls.")
    return parser.parse_args(argv)


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        raise EvalRunnerError(f"dataset not found: {path}")
    cases: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                item = json.loads(text)
            except json.JSONDecodeError as exc:
                raise EvalRunnerError(f"invalid JSONL at {path}:{line_no}: {exc}") from exc
            if not isinstance(item, dict):
                raise EvalRunnerError(f"case at {path}:{line_no} is not a JSON object")
            cases.append(item)
    return cases


def _scoring_total(case: dict[str, Any]) -> float:
    scoring = case.get("scoring")
    if not isinstance(scoring, dict):
        return -1
    if "total_score" in scoring:
        try:
            return float(scoring.get("total_score"))
        except Exception:
            return -1
    total = 0.0
    for key, value in scoring.items():
        if key == "total_score":
            continue
        try:
            total += float(value)
        except Exception:
            return -1
    return total


def load_dataset(path: Path, case_ids: list[str] | None = None) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    cases = _load_jsonl(path)
    seen: set[str] = set()
    duplicate_ids: list[str] = []
    missing_fields: list[dict[str, Any]] = []
    invalid_scoring: list[str] = []

    for item in cases:
        case_id = str(item.get("case_id") or "").strip()
        if case_id in seen:
            duplicate_ids.append(case_id)
        seen.add(case_id)
        missing = sorted(field for field in REQUIRED_CASE_FIELDS if field not in item)
        if missing:
            missing_fields.append({"case_id": case_id, "missing": missing})
        if abs(_scoring_total(item) - 10.0) > 0.001:
            invalid_scoring.append(case_id)

    if case_ids:
        requested = set(case_ids)
        cases = [item for item in cases if str(item.get("case_id") or "") in requested]
        found = {str(item.get("case_id") or "") for item in cases}
        missing_requested = sorted(requested - found)
        if missing_requested:
            raise EvalRunnerError(f"requested case id not found: {', '.join(missing_requested)}")

    stats = {
        "engine": "deepeval",
        "dataset_path": str(path),
        "total_cases": len(cases),
        "dataset_valid": not missing_fields and not duplicate_ids and not invalid_scoring,
        "unique_case_ids": not duplicate_ids,
        "duplicate_case_ids": duplicate_ids,
        "missing_fields": missing_fields,
        "scoring_total_valid": not invalid_scoring,
        "invalid_scoring_case_ids": invalid_scoring,
    }
    if not stats["dataset_valid"]:
        raise EvalRunnerError(f"dataset validation failed: {json.dumps(stats, ensure_ascii=False)}")
    return cases, stats


def http_json(method: str, url: str, payload: dict[str, Any] | None = None, *, timeout: int = 30, headers: dict[str, str] | None = None) -> dict[str, Any]:
    data = None
    request_headers = {"Accept": "application/json"}
    if headers:
        request_headers.update(headers)
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request_headers["Content-Type"] = "application/json; charset=utf-8"
    request = urllib.request.Request(url, data=data, method=method, headers=request_headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise EvalRunnerError(f"HTTP {exc.code} {url}: {body}", exit_code=2) from exc
    except urllib.error.URLError as exc:
        raise EvalRunnerError(f"request failed {url}: {exc}", exit_code=2) from exc
    except json.JSONDecodeError as exc:
        raise EvalRunnerError(f"invalid JSON response from {url}: {exc}", exit_code=2) from exc


def preflight(base_url: str) -> dict[str, Any]:
    health = http_json("GET", f"{base_url}/api/v1/nl2sql/health", timeout=15)
    settings = http_json("GET", f"{base_url}/api/v1/nl2sql-admin/settings", timeout=15)
    return {"health": health, "settings": settings}


def _flatten_strings(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        parts: list[str] = []
        for item in value.values():
            parts.extend(_flatten_strings(item))
        return parts
    if isinstance(value, list):
        parts: list[str] = []
        for item in value:
            parts.extend(_flatten_strings(item))
        return parts
    if isinstance(value, (int, float, bool)):
        return [str(value)]
    return []


def _collect_tool_names(events: list[dict[str, Any]]) -> list[str]:
    names: list[str] = []
    for event in events:
        data = event.get("data") if isinstance(event, dict) else {}
        candidates = []
        if isinstance(event, dict):
            candidates.extend([event.get("tool_name"), event.get("name"), event.get("tool")])
        if isinstance(data, dict):
            candidates.extend([data.get("tool_name"), data.get("name"), data.get("tool")])
        for candidate in candidates:
            if isinstance(candidate, dict):
                candidate = candidate.get("name")
            text = str(candidate or "").strip()
            if text and text not in names:
                names.append(text)
    return names


def _extract_sql_outputs(events: list[dict[str, Any]], final_answer: str) -> list[str]:
    combined = "\n".join(_flatten_strings(events) + [final_answer])
    sqls: list[str] = []
    fenced = re.findall(r"```sql\s*(.*?)```", combined, flags=re.IGNORECASE | re.DOTALL)
    sqls.extend(item.strip() for item in fenced if item.strip())
    for match in re.findall(r"(?is)\b(?:select|with)\b.+?(?:;|\n\n|$)", combined):
        text = re.sub(r"\s+", " ", match).strip().rstrip(";")
        if text and text not in sqls:
            sqls.append(text)
    return sqls


def _extract_chart_outputs(events: list[dict[str, Any]]) -> list[Any]:
    charts: list[Any] = []
    for event in events:
        for key in ("chart", "chart_spec", "echarts", "spec"):
            value = event.get(key) if isinstance(event, dict) else None
            if value is None and isinstance(event.get("data") if isinstance(event, dict) else None, dict):
                value = event["data"].get(key)
            if value is not None:
                charts.append(value)
    return charts


def _collect_usage(task: dict[str, Any], messages: dict[str, Any], events: list[dict[str, Any]]) -> dict[str, Any]:
    usage: dict[str, Any] = {}
    if isinstance(task.get("usage"), dict):
        usage.update(task["usage"])
    for message in messages.get("items") or []:
        if isinstance(message, dict) and isinstance(message.get("usage"), dict):
            usage.update(message["usage"])
    for event in events:
        data = event.get("data") if isinstance(event, dict) else None
        if isinstance(data, dict) and isinstance(data.get("usage"), dict):
            usage.update(data["usage"])
    return usage


def _dedupe(values: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = str(value or "").strip()
        if text and text not in seen:
            result.append(text)
            seen.add(text)
    return result


def _auto_failure_attribution(
    combined: str,
    *,
    missing_sql_fragments: list[str],
    forbidden_hits: list[str],
    missing_tool_names: list[str],
) -> list[str]:
    failures: list[str] = []
    if re.search(r"请.*执行.*SQL|供.*执行|无法直接执行|未注入.*SQL|没有\s*SQL\s*执行|SQL.*尚未执行", combined, re.I | re.S):
        failures.append("sql_only")
    if re.search(r"OpenDataWorks\s*平台元数据|托管元数据|data_table|data_lineage|data_task|data_workflow|inspect_metadata\.py|get_lineage\.py", combined, re.I):
        failures.append("wrong_domain")
    if re.search(r"\{(?:target_date|TARGET_DATE|start_date|START_DATE|end_date|END_DATE|database_name|DATABASE_NAME|database_schema|DATABASE_SCHEMA|table_name|TABLE_NAME|period|PERIOD|timeDim|RULE_KEY)\}|占位符|TODO", combined):
        failures.append("placeholder_leak")
    if re.search(r"超时|timeout", combined, re.I):
        failures.append("tool_timeout")
    if re.search(r"未找到|没有找到|不存在|无匹配|空结果集|返回空", combined):
        failures.append("empty_result")
    if missing_sql_fragments:
        failures.append("missing_sql_fragment")
    if missing_tool_names:
        failures.append("missing_tool")
    if forbidden_hits:
        failures.append("forbidden_sql")
    return _dedupe(failures)


def auto_rule_check(case: dict[str, Any], *, final_answer: str, events: list[dict[str, Any]], sql_outputs: list[str], tool_names: list[str]) -> dict[str, Any]:
    combined = "\n".join(_flatten_strings(events) + sql_outputs + [final_answer])
    missing_sql_fragments = [
        fragment
        for fragment in case.get("required_sql_fragments") or []
        if str(fragment or "").strip() and str(fragment) not in combined
    ]
    forbidden_hits: list[str] = []
    for pattern in case.get("forbidden_sql_patterns") or []:
        try:
            if re.search(str(pattern), combined):
                forbidden_hits.append(str(pattern))
        except re.error:
            if str(pattern) in combined:
                forbidden_hits.append(str(pattern))
    missing_tool_names = [
        name
        for name in case.get("expected_tool_names") or []
        if str(name or "").strip() and str(name) not in tool_names
    ]
    triggered_veto_rules: list[str] = []
    if forbidden_hits:
        triggered_veto_rules.append("SQL 不带 schema 前缀、使用 SELECT * 或明显违反当前 skill SQL 硬规则。")
    failure_attribution = _auto_failure_attribution(
        combined,
        missing_sql_fragments=missing_sql_fragments,
        forbidden_hits=forbidden_hits,
        missing_tool_names=missing_tool_names,
    )
    return {
        "passed": not forbidden_hits,
        "missing_sql_fragments": missing_sql_fragments,
        "forbidden_sql_patterns": forbidden_hits,
        "missing_tool_names": missing_tool_names,
        "triggered_veto_rules": triggered_veto_rules,
        "failure_attribution": failure_attribution,
    }


def _final_assistant_answer(messages: dict[str, Any], task_id: str) -> str:
    candidates = []
    for message in messages.get("items") or []:
        if not isinstance(message, dict):
            continue
        if str(message.get("sender_type") or "") != "assistant":
            continue
        if task_id and message.get("task_id") not in {None, "", task_id}:
            continue
        candidates.append(message)
    if not candidates:
        for message in messages.get("items") or []:
            if isinstance(message, dict) and str(message.get("sender_type") or "") == "assistant":
                candidates.append(message)
    return str((candidates[-1] if candidates else {}).get("content") or "").strip()


def _create_topic(base_url: str, case: dict[str, Any], agent_id: str) -> str:
    topic = http_json(
        "POST",
        f"{base_url}/api/v1/nl2sql/topics",
        {"title": f"DeepEval {case['case_id']}", "agent_id": agent_id},
    )
    topic_id = str(topic.get("topic_id") or "").strip()
    if not topic_id:
        raise EvalRunnerError("topic creation response did not include topic_id")
    return topic_id


def _submit_task(base_url: str, topic_id: str, case: dict[str, Any], args: argparse.Namespace) -> str:
    payload: dict[str, Any] = {
        "topic_id": topic_id,
        "content": str(case.get("question") or ""),
        "agent_id": str(args.agent_id or "").strip(),
        "execution_mode": "background",
    }
    if args.provider_id:
        payload["provider_id"] = args.provider_id
    if args.model:
        payload["model"] = args.model
    submitted = http_json("POST", f"{base_url}/api/v1/nl2sql/tasks/deliver-message", payload)
    task_id = str(submitted.get("task_id") or "").strip()
    if not task_id:
        raise EvalRunnerError("task submission response did not include task_id")
    return task_id


def _is_recovered_task(task: dict[str, Any]) -> bool:
    error = task.get("error") if isinstance(task.get("error"), dict) else {}
    return str(task.get("task_status") or "").lower() == "suspended" and str(error.get("code") or "") == "task_recovered"


def _task_id_from_recovery_message(task: dict[str, Any]) -> str:
    error = task.get("error") if isinstance(task.get("error"), dict) else {}
    message = str(error.get("message") or "")
    match = re.search(r"\btask[-_][A-Za-z0-9]+\b", message)
    return match.group(0) if match else ""


def _resolve_recovered_task_id(base_url: str, topic_id: str, task: dict[str, Any]) -> str:
    parent_task_id = str(task.get("task_id") or "").strip()
    if topic_id:
        try:
            topic = http_json("GET", f"{base_url}/api/v1/nl2sql/topics/{urllib.parse.quote(topic_id)}", timeout=30)
        except EvalRunnerError:
            topic = {}
        current_task_id = str(topic.get("current_task_id") or "").strip()
        if current_task_id and current_task_id != parent_task_id:
            return current_task_id
    recovered_task_id = _task_id_from_recovery_message(task)
    if recovered_task_id and recovered_task_id != parent_task_id:
        return recovered_task_id
    return ""


def _poll_task(
    base_url: str,
    task_id: str,
    timeout_seconds: int,
    *,
    topic_id: str = "",
) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    deadline = time.time() + max(1, timeout_seconds)
    current_task_id = task_id
    seen_task_ids = {task_id}
    after_seq = 0
    all_events: list[dict[str, Any]] = []
    errors: list[dict[str, Any]] = []
    last_task: dict[str, Any] = {}
    last_poll_error = ""
    while time.time() < deadline:
        try:
            last_task = http_json("GET", f"{base_url}/api/v1/nl2sql/tasks/{urllib.parse.quote(current_task_id)}", timeout=30)
        except EvalRunnerError as exc:
            last_poll_error = str(exc)
            time.sleep(1.0)
            continue

        status = str(last_task.get("task_status") or "").lower()
        try:
            page = http_json(
                "GET",
                f"{base_url}/api/v1/nl2sql/tasks/{urllib.parse.quote(current_task_id)}/events?after_seq={after_seq}&limit=1000",
                timeout=60,
            )
            events = [item for item in page.get("events") or [] if isinstance(item, dict)]
            all_events.extend(events)
            after_seq = max(after_seq, int(page.get("next_after_seq") or after_seq))
            status = str(last_task.get("task_status") or page.get("task_status") or "").lower()
        except EvalRunnerError as exc:
            last_poll_error = str(exc)

        if status in TERMINAL_STATUSES:
            if _is_recovered_task(last_task):
                recovered_task_id = _resolve_recovered_task_id(base_url, topic_id, last_task)
                if recovered_task_id and recovered_task_id not in seen_task_ids:
                    current_task_id = recovered_task_id
                    seen_task_ids.add(recovered_task_id)
                    after_seq = 0
                    continue
            return last_task, all_events, errors
        time.sleep(0.2)
    errors.append({"code": "timeout", "message": f"task did not finish within {timeout_seconds}s"})
    if last_poll_error:
        errors.append({"code": "poll_error", "message": last_poll_error})
    last_task = dict(last_task or {"task_id": current_task_id})
    last_task["task_status"] = str(last_task.get("task_status") or "timeout")
    return last_task, all_events, errors


def run_case(base_url: str, case: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    started = time.time()
    errors: list[dict[str, Any]] = []
    topic_id = ""
    task_id = ""
    task: dict[str, Any] = {}
    events: list[dict[str, Any]] = []
    messages: dict[str, Any] = {}
    final_answer = ""
    try:
        topic_id = _create_topic(base_url, case, str(args.agent_id or "").strip())
        task_id = _submit_task(base_url, topic_id, case, args)
        case_timeout = min(max(1, args.timeout_seconds), int(case.get("max_wait_seconds") or args.timeout_seconds or 900))
        task, events, poll_errors = _poll_task(base_url, task_id, case_timeout, topic_id=topic_id)
        errors.extend(poll_errors)
        final_task_id = str(task.get("task_id") or task_id).strip() or task_id
        messages = http_json(
            "GET",
            f"{base_url}/api/v1/nl2sql/topics/{urllib.parse.quote(topic_id)}/messages?page=1&page_size=200&order=asc",
            timeout=30,
        )
        final_answer = _final_assistant_answer(messages, final_task_id)
        status = str(task.get("task_status") or "").lower()
        if status and status not in SUCCESS_STATUSES:
            errors.append({"code": status, "message": json.dumps(task.get("error") or {}, ensure_ascii=False)})
    except EvalRunnerError as exc:
        errors.append({"code": "runner_error", "message": str(exc)})

    tool_names = _collect_tool_names(events)
    sql_outputs = _extract_sql_outputs(events, final_answer)
    chart_outputs = _extract_chart_outputs(events)
    usage = _collect_usage(task, messages, events)
    rule_check = auto_rule_check(case, final_answer=final_answer, events=events, sql_outputs=sql_outputs, tool_names=tool_names)
    return {
        "case_id": case.get("case_id"),
        "category": case.get("category"),
        "question": case.get("question"),
        "agent_id": str(args.agent_id or "").strip(),
        "topic_id": topic_id,
        "task_id": str(task.get("task_id") or task_id),
        "task_status": str(task.get("task_status") or ""),
        "final_answer": final_answer,
        "tool_names": tool_names,
        "sql_outputs": sql_outputs,
        "chart_outputs": chart_outputs,
        "usage": usage,
        "duration_seconds": round(time.time() - started, 3),
        "auto_rule_check": rule_check,
        "judge": {},
        "veto_rules_triggered": list(rule_check.get("triggered_veto_rules") or []),
        "case_passed": False,
        "errors": errors,
    }


def _run_cases(base_url: str, cases: list[dict[str, Any]], args: argparse.Namespace) -> list[dict[str, Any]]:
    if args.concurrency <= 1:
        return [run_case(base_url, case, args) for case in cases]

    results: list[dict[str, Any] | None] = [None] * len(cases)
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        future_to_index = {pool.submit(run_case, base_url, case, args): i for i, case in enumerate(cases)}
        for future in as_completed(future_to_index):
            index = future_to_index[future]
            try:
                results[index] = future.result()
            except Exception as exc:
                case = cases[index]
                results[index] = {
                    "case_id": case.get("case_id"),
                    "category": case.get("category"),
                    "question": case.get("question"),
                    "agent_id": str(getattr(args, "agent_id", "") or "").strip(),
                    "task_status": "runner_error",
                    "final_answer": "",
                    "tool_names": [],
                    "sql_outputs": [],
                    "chart_outputs": [],
                    "usage": {},
                    "duration_seconds": 0,
                    "auto_rule_check": {"passed": False, "failure_attribution": ["runner_crash"]},
                    "judge": {},
                    "veto_rules_triggered": [],
                    "case_passed": False,
                    "errors": [{"code": "runner_crash", "message": str(exc)}],
                }
            else:
                result = results[index]
                case_id = (result or {}).get("case_id") if result else "?"
                print(f"[{len([r for r in results if r is not None])}/{len(cases)}] {case_id} done", file=sys.stderr)
    return [r for r in results if r is not None]


def _ensure_deepeval_available() -> None:
    if deepeval_evaluate is None or LLMTestCase is None:
        raise EvalRunnerError("deepeval is not installed; run through the DeepEval eval Docker image or install requirements.txt")


def to_deepeval_test_case(case: dict[str, Any], case_result: dict[str, Any]) -> Any:
    _ensure_deepeval_available()
    expected_payload = {
        "case_id": case.get("case_id"),
        "category": case.get("category"),
        "expected_intent": case.get("expected_intent"),
        "expected_ontology_objects": case.get("expected_ontology_objects") or [],
        "expected_relations": case.get("expected_relations") or [],
        "expected_sql_or_tool_behavior": case.get("expected_sql_or_tool_behavior") or [],
        "expected_answer_points": case.get("expected_answer_points") or [],
        "scoring": case.get("scoring") or {},
        "veto_rules": case.get("veto_rules") or [],
        "judge_guidance": case.get("judge_guidance") or "",
    }
    context_payload = {"case": case, "case_result": case_result}
    return LLMTestCase(
        input=str(case.get("question") or ""),
        actual_output=str(case_result.get("final_answer") or ""),
        expected_output=json.dumps(expected_payload, ensure_ascii=False, sort_keys=True),
        context=[json.dumps(context_payload, ensure_ascii=False, sort_keys=True)],
    )


def _json_object_text(raw: str) -> str:
    text = str(raw or "").strip()
    if not text:
        return text
    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].startswith("```"):
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    decoder = json.JSONDecoder()
    for index, char in enumerate(text):
        if char != "{":
            continue
        candidate = text[index:]
        try:
            parsed, end = decoder.raw_decode(candidate)
        except json.JSONDecodeError:
            continue
        if isinstance(parsed, dict):
            return candidate[:end]
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        return text[start : end + 1]
    return text


def _normalize_float(value: Any, *, minimum: float = 0, maximum: float = 10) -> float:
    try:
        number = float(value)
    except Exception:
        number = minimum
    if number < minimum:
        return minimum
    if number > maximum:
        return maximum
    return number


def _normalize_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        text = value.strip().lower()
        if not text:
            return False
        if text in {"false", "0", "no", "n", "none", "null", "否", "无", "不存在"}:
            return False
        if text in {"true", "1", "yes", "y", "是", "有", "存在"}:
            return True
    return bool(value)


def _string_list(value: Any) -> list[str]:
    if isinstance(value, str):
        text = value.strip()
        return [text] if text else []
    if not isinstance(value, list):
        return []
    result: list[str] = []
    for item in value:
        text = str(item or "").strip()
        if text:
            result.append(text)
    return result


def normalize_judge_payload(data: dict[str, Any], *, raw_output: str = "") -> dict[str, Any]:
    dimensions: dict[str, float] = {}
    raw_dimensions = data.get("dimension_scores")
    if isinstance(raw_dimensions, dict):
        for key in JUDGE_DIMENSIONS:
            dimensions[key] = _normalize_float(raw_dimensions.get(key), minimum=0, maximum=2)
    return {
        "score": _normalize_float(data.get("score"), minimum=0, maximum=10),
        "dimension_scores": dimensions,
        "hallucination": _normalize_bool(data.get("hallucination")),
        "veto_rules_triggered": _string_list(data.get("veto_rules_triggered")),
        "failure_attribution": _string_list(data.get("failure_attribution")),
        "comment": str(data.get("comment") or "").strip(),
        "judge_failed": False,
        "raw_output": raw_output,
    }


def failed_judge(reason: str, *, raw_output: str = "", attribution: list[str] | None = None) -> dict[str, Any]:
    return {
        "score": 0,
        "dimension_scores": {},
        "hallucination": False,
        "veto_rules_triggered": [],
        "failure_attribution": attribution or ["judge_failed"],
        "comment": reason,
        "judge_failed": True,
        "raw_output": raw_output,
    }


def _merge_auto_failure_attribution(judge: dict[str, Any], rule_check: dict[str, Any]) -> dict[str, Any]:
    merged = dict(judge)
    merged["failure_attribution"] = _dedupe(
        list(rule_check.get("failure_attribution") or []) + list(judge.get("failure_attribution") or [])
    )
    return merged


def _judge_system_prompt() -> str:
    return (
        "你是 DataAgent 在线问数评测裁判。只能基于请求中的 case、最终回答、工具事件、SQL/图表输出和自动规则检查打分。"
        "不要调用任何工具，不要编造事实。必须只输出一个 JSON 对象，字段为："
        "score, dimension_scores, hallucination, veto_rules_triggered, failure_attribution, comment。"
        "score 为 0 到 10；dimension_scores 包含 intent, ontology_entity, relation_scope, sql_or_tool_call, data_accuracy, reasoning, answer_quality。"
    )


def _judge_user_prompt(payload: dict[str, Any], *, repair: bool = False, previous_output: str = "") -> str:
    if repair:
        return (
            "上一次裁判输出不是合法 JSON。请只返回修复后的 JSON 对象，不要包含 Markdown 或解释。\n\n"
            f"上一次输出：\n{previous_output}\n\n"
            f"评测输入：\n{json.dumps(payload, ensure_ascii=False, indent=2)}"
        )
    return "请按 10 分制评估以下 DataAgent 问数回答，严格返回 JSON。\n\n" + json.dumps(payload, ensure_ascii=False, indent=2)


def _judge_message_content(payload: dict[str, Any], *, repair: bool = False, previous_output: str = "") -> str:
    return _judge_system_prompt() + "\n\n" + _judge_user_prompt(payload, repair=repair, previous_output=previous_output)


def _anthropic_messages_url(base_url: str) -> str:
    base = str(base_url or "").strip().rstrip("/")
    if not base:
        raise EvalRunnerError("judge base URL is required")
    if base.endswith("/v1/messages") or base.endswith("/messages"):
        return base
    return f"{base}/v1/messages"


def _extract_message_text(response: dict[str, Any]) -> str:
    parts: list[str] = []
    content = response.get("content")
    if isinstance(content, str):
        parts.append(content)
    elif isinstance(content, list):
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict):
                value = item.get("text") or item.get("content") or item.get("result")
                if isinstance(value, str):
                    parts.append(value)
    if isinstance(response.get("result"), str):
        parts.append(response["result"])
    return "\n".join(part for part in parts if str(part or "").strip()).strip()


def call_judge_model(config: JudgeConfig, payload: dict[str, Any]) -> dict[str, Any]:
    raw_output = ""
    headers = {
        "anthropic-version": "2023-06-01",
        "x-api-key": config.token,
        "Authorization": f"Bearer {config.token}",
    }
    max_attempts = 3
    for attempt in range(max_attempts):
        if attempt > 0:
            time.sleep(1)
        messages: list[dict[str, str]] = [
            {"role": "user", "content": _judge_message_content(payload, repair=attempt > 0, previous_output=raw_output)},
            {"role": "assistant", "content": "{"},
        ]
        body = {
            "model": config.model,
            "max_tokens": config.max_tokens,
            "temperature": 0,
            "messages": messages,
        }
        try:
            response = http_json("POST", _anthropic_messages_url(config.base_url), body, timeout=config.timeout_seconds, headers=headers)
            raw_output = "{" + _extract_message_text(response)
            parsed = json.loads(_json_object_text(raw_output))
            if not isinstance(parsed, dict):
                raise ValueError("judge output is not a JSON object")
            return normalize_judge_payload(parsed, raw_output=raw_output)
        except EvalRunnerError as exc:
            if attempt == max_attempts - 1:
                return failed_judge(str(exc), raw_output=raw_output, attribution=["judge_failed", "judge_http_error"])
        except Exception as exc:
            if attempt == max_attempts - 1:
                return failed_judge(f"裁判模型未返回合法 JSON: {exc}", raw_output=raw_output)
    return failed_judge("裁判模型未返回合法 JSON", raw_output=raw_output)


class DataAgentEvaluationMetric(BaseMetric):  # type: ignore[misc, valid-type]
    shared_case_judges: dict[str, dict[str, Any]] = {}

    def __init__(self, judge_config: JudgeConfig, threshold: float = 0.8):
        self.judge_config = judge_config
        self.threshold = threshold
        self.score = 0.0
        self.reason = ""
        self.success = False
        self.case_judges: dict[str, dict[str, Any]] = {}

    def measure(self, test_case: Any) -> float:
        payload = self._payload_from_test_case(test_case)
        case_id = str(payload.get("case", {}).get("case_id") or "")
        judge = call_judge_model(self.judge_config, payload)
        if not bool(judge.get("judge_failed")):
            judge = normalize_judge_payload(judge, raw_output=str(judge.get("raw_output") or ""))
        self.case_judges[case_id] = judge
        self.__class__.shared_case_judges[case_id] = judge
        self.score = min(1.0, max(0.0, float(judge.get("score") or 0) / 10.0))
        self.reason = str(judge.get("comment") or "")
        self.success = (
            self.score >= self.threshold
            and not bool(judge.get("judge_failed"))
            and not bool(judge.get("hallucination"))
            and not (judge.get("veto_rules_triggered") or [])
        )
        return self.score

    async def a_measure(self, test_case: Any) -> float:
        return self.measure(test_case)

    def is_successful(self) -> bool:
        return bool(self.success)

    @property
    def __name__(self) -> str:
        return "DataAgentEvaluationMetric"

    @staticmethod
    def _payload_from_test_case(test_case: Any) -> dict[str, Any]:
        context = getattr(test_case, "context", None) or []
        if not context:
            raise EvalRunnerError("DeepEval test case context is missing")
        context_payload = json.loads(str(context[0]))
        case = context_payload.get("case") or {}
        case_result = context_payload.get("case_result") or {}
        return {
            "case": case,
            "user_question": str(case.get("question") or getattr(test_case, "input", "") or ""),
            "final_answer": str(case_result.get("final_answer") or getattr(test_case, "actual_output", "") or ""),
            "task_status": str(case_result.get("task_status") or ""),
            "task_error": None,
            "tool_events": case_result.get("tool_events") or [],
            "sql_outputs": case_result.get("sql_outputs") or [],
            "chart_outputs": case_result.get("chart_outputs") or [],
            "auto_rule_check": case_result.get("auto_rule_check") or {},
        }


def _test_case_case_id(test_case: Any) -> str:
    try:
        payload = DataAgentEvaluationMetric._payload_from_test_case(test_case)
    except Exception:
        return ""
    return str((payload.get("case") or {}).get("case_id") or "")


def run_deepeval(test_cases: list[Any], metric: DataAgentEvaluationMetric) -> None:
    """Drive the judge metric locally, fully offline.

    DeepEval's ``evaluate()`` couples each run to telemetry and Confident AI
    cloud calls. On intranet deployments those calls fail or hang *after* every
    case has already been measured, which previously crashed the runner before
    any report was written. The runner only needs each metric to call our own
    Anthropic-compatible judge, so we iterate the test cases directly. This is
    the single primary path and requires no deepeval.com / Confident AI service.

    A single failing case is recorded as a failed judge instead of aborting the
    whole batch, so a complete report is always produced.
    """
    _ensure_deepeval_available()
    DataAgentEvaluationMetric.shared_case_judges = {}
    for test_case in test_cases:
        case_id = _test_case_case_id(test_case)
        try:
            metric.measure(test_case)
        except Exception as exc:  # never lose the remaining cases or the report
            judge = failed_judge(
                f"judge measurement crashed: {exc}",
                attribution=["judge_failed", "judge_crash"],
            )
            metric.case_judges[case_id] = judge
            DataAgentEvaluationMetric.shared_case_judges[case_id] = judge
            print(f"judge measurement crashed for case {case_id or '?'}: {exc}", file=sys.stderr)


def _apply_judges(results: list[dict[str, Any]], metric: DataAgentEvaluationMetric) -> list[dict[str, Any]]:
    for item in results:
        case_id = str(item.get("case_id") or "")
        judge = (
            metric.case_judges.get(case_id)
            or DataAgentEvaluationMetric.shared_case_judges.get(case_id)
            or failed_judge("DeepEval metric did not return a judge result")
        )
        judge = _merge_auto_failure_attribution(judge, item.get("auto_rule_check") or {})
        veto_rules = list(item.get("auto_rule_check", {}).get("triggered_veto_rules") or []) + list(judge.get("veto_rules_triggered") or [])
        item["judge"] = judge
        item["veto_rules_triggered"] = veto_rules
        item["case_passed"] = (
            not item.get("errors")
            and str(item.get("task_status") or "").lower() in SUCCESS_STATUSES
            and float(judge.get("score") or 0) >= 8
            and not bool(judge.get("judge_failed"))
            and not bool(judge.get("hallucination"))
            and not veto_rules
        )
    return results


def _avg(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def build_summary(results: list[dict[str, Any]], dataset_stats: dict[str, Any], *, dry_run: bool = False) -> dict[str, Any]:
    if dry_run:
        return {
            **dataset_stats,
            "dry_run": True,
            "passed": True,
            "recommendation": "dry-run",
        }
    scores = [float((item.get("judge") or {}).get("score") or 0) for item in results]
    dimensions = [item.get("judge", {}).get("dimension_scores") or {} for item in results]
    intent_accuracy = _avg([1.0 if float(dim.get("intent") or 0) >= 1 else 0.0 for dim in dimensions])
    ontology_accuracy = _avg([1.0 if float(dim.get("ontology_entity") or 0) >= 1 else 0.0 for dim in dimensions])
    sql_tool_accuracy = _avg([min(1.0, float(dim.get("sql_or_tool_call") or 0) / 2.0) for dim in dimensions])
    data_accuracy = _avg([min(1.0, float(dim.get("data_accuracy") or 0) / 2.0) for dim in dimensions])
    reasoning_average = _avg([min(5.0, float(dim.get("reasoning") or 0) * 2.5) for dim in dimensions])
    hallucination_rate = _avg([1.0 if bool(item.get("judge", {}).get("hallucination")) else 0.0 for item in results])
    veto_count = sum(len(item.get("veto_rules_triggered") or []) for item in results)
    judge_failed_count = sum(1 for item in results if bool(item.get("judge", {}).get("judge_failed")))
    metrics = {
        "average_score": round(_avg(scores), 4),
        "intent_accuracy": round(intent_accuracy, 4),
        "ontology_accuracy": round(ontology_accuracy, 4),
        "sql_tool_accuracy": round(sql_tool_accuracy, 4),
        "data_precision": round(data_accuracy, 4),
        "data_recall": round(data_accuracy, 4),
        "reasoning_average": round(reasoning_average, 4),
        "hallucination_rate": round(hallucination_rate, 4),
    }
    gates_passed = (
        metrics["average_score"] >= GATES["average_score"]
        and metrics["intent_accuracy"] >= GATES["intent_accuracy"]
        and metrics["ontology_accuracy"] >= GATES["ontology_accuracy"]
        and metrics["sql_tool_accuracy"] >= GATES["sql_tool_accuracy"]
        and metrics["data_precision"] >= GATES["data_precision"]
        and metrics["data_recall"] >= GATES["data_recall"]
        and metrics["reasoning_average"] >= GATES["reasoning_average"]
        and metrics["hallucination_rate"] <= GATES["hallucination_rate"]
        and veto_count == 0
        and judge_failed_count == 0
        and all(bool(item.get("case_passed")) for item in results)
    )
    return {
        **dataset_stats,
        "dry_run": False,
        "total_cases": len(results),
        "passed_cases": sum(1 for item in results if bool(item.get("case_passed"))),
        "failed_cases": sum(1 for item in results if not bool(item.get("case_passed"))),
        "veto_count": veto_count,
        "judge_failed_count": judge_failed_count,
        "metrics": metrics,
        "gates": GATES,
        "passed": gates_passed,
        "recommendation": "建议上线" if gates_passed else "不建议上线",
    }


def render_report(summary: dict[str, Any], results: list[dict[str, Any]]) -> str:
    lines = [
        "# DataAgent DeepEval 评测报告",
        "",
        f"- 引擎: `{summary.get('engine', 'deepeval')}`",
        f"- 数据集: `{summary.get('dataset_path', '')}`",
        f"- Agent: `{summary.get('agent_id', '')}`",
        f"- 用例数: {summary.get('total_cases', 0)}",
        f"- 结论: {summary.get('recommendation', '')}",
        "",
    ]
    if summary.get("dry_run"):
        lines.extend(
            [
                "## Dry Run",
                "",
                f"- case_id 唯一: {summary.get('unique_case_ids')}",
                f"- 评分总分有效: {summary.get('scoring_total_valid')}",
                f"- 数据集有效: {summary.get('dataset_valid')}",
                "",
            ]
        )
        return "\n".join(lines)
    metrics = summary.get("metrics") or {}
    lines.extend(
        [
            "## 核心指标",
            "",
            "| 指标 | 结果 | 目标 |",
            "|---|---:|---:|",
            f"| 平均分 | {metrics.get('average_score', 0):.2f} | >= 8.0 |",
            f"| 意图识别准确率 | {metrics.get('intent_accuracy', 0):.2%} | >= 90% |",
            f"| 本体识别准确率 | {metrics.get('ontology_accuracy', 0):.2%} | >= 90% |",
            f"| SQL / 工具调用准确率 | {metrics.get('sql_tool_accuracy', 0):.2%} | >= 85% |",
            f"| 数据结果 Precision | {metrics.get('data_precision', 0):.2%} | >= 90% |",
            f"| 数据结果 Recall | {metrics.get('data_recall', 0):.2%} | >= 85% |",
            f"| 推理平均分 | {metrics.get('reasoning_average', 0):.2f} | >= 4/5 |",
            f"| 幻觉率 | {metrics.get('hallucination_rate', 0):.2%} | <= 5% |",
            "",
            "## 用例明细",
            "",
            "| case_id | 类别 | 分数 | 通过 | 失败归因 |",
            "|---|---|---:|---|---|",
        ]
    )
    for item in results:
        judge = item.get("judge") or {}
        attribution = ", ".join(judge.get("failure_attribution") or [])
        if item.get("errors"):
            attribution = attribution or ", ".join(error.get("code", "") for error in item.get("errors") or [])
        lines.append(
            f"| {item.get('case_id')} | {item.get('category')} | {float(judge.get('score') or 0):.2f} | "
            f"{'是' if item.get('case_passed') else '否'} | {attribution} |"
        )
    lines.append("")
    return "\n".join(lines)


def write_outputs(output_dir: Path, results: list[dict[str, Any]], summary: dict[str, Any]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    raw_dir = output_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    if results:
        with (output_dir / "cases.jsonl").open("w", encoding="utf-8") as handle:
            for item in results:
                handle.write(json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n")
                (raw_dir / f"{item.get('case_id')}.json").write_text(
                    json.dumps(item, ensure_ascii=False, indent=2, sort_keys=True),
                    encoding="utf-8",
                )
    (output_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    (output_dir / "report.md").write_text(render_report(summary, results), encoding="utf-8")


def _judge_config_from_args(args: argparse.Namespace) -> JudgeConfig:
    base_url = str(args.judge_base_url or "").strip()
    token = str(args.judge_token or "").strip()
    model = str(args.judge_model or "").strip()
    if not base_url or not token or not model:
        raise EvalRunnerError("judge config is required: --judge-base-url, --judge-token, --judge-model", exit_code=2)
    return JudgeConfig(
        base_url=base_url,
        token=token,
        model=model,
        timeout_seconds=max(1, int(args.judge_timeout_seconds or 120)),
        max_tokens=max(1, int(args.judge_max_tokens or 4096)),
    )


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.concurrency < 1:
        print("--concurrency must be >= 1", file=sys.stderr)
        return 2
    if not str(args.dataset or "").strip():
        print("--dataset is required and must point to the private evaluation JSONL file", file=sys.stderr)
        return 2
    if not args.dry_run and not str(args.agent_id or "").strip():
        print("--agent-id is required for non-dry-run evaluation", file=sys.stderr)
        return 2
    root = _repo_or_package_root()
    dataset_path = Path(args.dataset)
    if not dataset_path.is_absolute():
        dataset_path = root / dataset_path
    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = root / output_dir

    try:
        cases, dataset_stats = load_dataset(dataset_path, args.case_ids)
        if args.dry_run:
            summary = build_summary([], dataset_stats, dry_run=True)
            write_outputs(output_dir, [], summary)
            print(f"eval outputs written to: {output_dir}")
            return 0

        _ensure_deepeval_available()
        judge_config = _judge_config_from_args(args)
        base_url = str(args.base_url or "").rstrip("/")
        preflight_payload = preflight(base_url)
        dataset_stats["preflight"] = preflight_payload
        dataset_stats["agent_id"] = str(args.agent_id or "").strip()
        results = _run_cases(base_url, cases, args)
        try:
            metric = DataAgentEvaluationMetric(judge_config)
            test_cases = [to_deepeval_test_case(case, result) for case, result in zip(cases, results)]
            run_deepeval(test_cases, metric)
            _apply_judges(results, metric)
            summary = build_summary(results, dataset_stats)
        except Exception as exc:
            # The expensive case runs already finished; never drop the report
            # just because the judging/summary step failed. Persist what we have.
            for item in results:
                if not item.get("judge"):
                    item["judge"] = failed_judge(f"judging aborted: {exc}")
            summary = build_summary(results, dataset_stats)
            summary["judging_error"] = str(exc)
            write_outputs(output_dir, results, summary)
            print(f"eval outputs written to: {output_dir}")
            print(f"judging step failed after all cases ran: {exc}", file=sys.stderr)
            return 1
        write_outputs(output_dir, results, summary)
        print(f"eval outputs written to: {output_dir}")
        return 0 if summary.get("passed") else 1
    except EvalRunnerError as exc:
        print(str(exc), file=sys.stderr)
        return exc.exit_code


if __name__ == "__main__":
    sys.exit(main())
