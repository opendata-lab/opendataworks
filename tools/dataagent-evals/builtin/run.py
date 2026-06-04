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
from pathlib import Path
from typing import Any


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
JUDGE_DEFAULT_TIMEOUT_SECONDS = 300
JUDGE_MAX_FINAL_ANSWER_CHARS = 6000
JUDGE_MAX_SQL_OUTPUTS = 20
JUDGE_MAX_SQL_OUTPUT_CHARS = 800
JUDGE_MAX_TOOL_EVENTS = 30
JUDGE_MAX_CHART_OUTPUTS = 5
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


class JudgeConfig:
    def __init__(
        self,
        *,
        base_url: str,
        token: str,
        model: str,
        timeout_seconds: int = JUDGE_DEFAULT_TIMEOUT_SECONDS,
        max_tokens: int = 4096,
    ):
        self.base_url = base_url
        self.token = token
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.max_tokens = max_tokens


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
    return root / "reports" / "dataagent-evals" / _timestamp()


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    root = _repo_or_package_root()
    parser = argparse.ArgumentParser(description="Run DataAgent online evaluations.")
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
    parser.add_argument(
        "--judge-timeout-seconds",
        type=int,
        default=int(os.environ.get("DATAAGENT_EVAL_JUDGE_TIMEOUT_SECONDS", str(JUDGE_DEFAULT_TIMEOUT_SECONDS))),
        help="Judge request timeout.",
    )
    parser.add_argument(
        "--judge-max-tokens",
        type=int,
        default=int(os.environ.get("DATAAGENT_EVAL_JUDGE_MAX_TOKENS", "4096")),
        help="Judge response max tokens.",
    )
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


def http_json(
    method: str,
    url: str,
    payload: dict[str, Any] | None = None,
    *,
    timeout: int = 30,
    headers: dict[str, str] | None = None,
) -> dict[str, Any]:
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
    except TimeoutError as exc:
        raise EvalRunnerError(f"request timed out {url} after {timeout}s: {exc}", exit_code=2) from exc
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


def _collect_tool_names(blocks: list[dict[str, Any]]) -> list[str]:
    names: list[str] = []
    for block in blocks:
        if not isinstance(block, dict) or block.get("type") != "tool_use":
            continue
        text = str(block.get("tool_name") or "").strip()
        if text and text not in names:
            names.append(text)
    return names


def _looks_like_sql(text: str) -> bool:
    return bool(re.match(r"(?is)^\s*(?:select|with)\b", str(text or "")))


def _normalise_sql(text: str) -> str:
    return re.sub(r"\s+", " ", str(text or "").strip()).rstrip(";")


def _collect_structured_sql(value: Any) -> list[str]:
    if isinstance(value, list):
        sqls: list[str] = []
        for item in value:
            sqls.extend(_collect_structured_sql(item))
        return sqls
    if not isinstance(value, dict):
        return []

    sqls: list[str] = []
    for key, item in value.items():
        key_text = str(key or "").lower()
        if key_text in {"sql", "query"} and isinstance(item, str) and _looks_like_sql(item):
            sqls.append(_normalise_sql(item))
            continue
        sqls.extend(_collect_structured_sql(item))
    return sqls


def _extract_sql_outputs(blocks: list[dict[str, Any]], final_answer: str) -> list[str]:
    sqls: list[str] = []
    for block in blocks:
        if not isinstance(block, dict) or block.get("type") != "tool_use":
            continue
        sqls.extend(_collect_structured_sql(block.get("input")))
        sqls.extend(_collect_structured_sql(block.get("output")))
    fenced = re.findall(r"```sql\s*(.*?)```", str(final_answer or ""), flags=re.IGNORECASE | re.DOTALL)
    sqls.extend(_normalise_sql(item) for item in fenced if _looks_like_sql(item))
    result: list[str] = []
    for text in sqls:
        if text and text not in result:
            result.append(text)
    return result


def _extract_chart_outputs(blocks: list[dict[str, Any]]) -> list[Any]:
    charts: list[Any] = []
    for block in blocks:
        if not isinstance(block, dict) or block.get("type") != "tool_use":
            continue
        for source in (block.get("input"), block.get("output")):
            if isinstance(source, dict):
                for key in ("chart", "chart_spec", "echarts", "spec"):
                    value = source.get(key)
                    if value is not None:
                        charts.append(value)
    return charts


def _truncate_for_judge(value: Any, *, max_text: int = 2000, max_rows: int = 20) -> Any:
    if value is None or isinstance(value, (int, float, bool)):
        return value
    if isinstance(value, str):
        text = value.strip()
        if len(text) <= max_text:
            return text
        return text[:max_text] + f"...[truncated {len(text) - max_text} chars]"
    if isinstance(value, list):
        return [_truncate_for_judge(item, max_text=max_text, max_rows=max_rows) for item in value[:max_rows]]
    if isinstance(value, dict):
        return {str(key): _truncate_for_judge(item, max_text=max_text, max_rows=max_rows) for key, item in value.items()}
    return str(value)


def _bounded_list_for_judge(value: Any, *, max_items: int) -> Any:
    if not isinstance(value, list) or len(value) <= max_items:
        return value
    head_count = max(1, max_items // 2)
    tail_count = max(1, max_items - head_count - 1)
    omitted = len(value) - head_count - tail_count
    return [
        *value[:head_count],
        {"kind": "truncated", "omitted_items": omitted},
        *value[-tail_count:],
    ]


def _compact_judge_payload(payload: dict[str, Any]) -> dict[str, Any]:
    compact: dict[str, Any] = {}
    for key, value in payload.items():
        if key == "case":
            compact[key] = _truncate_for_judge(value, max_text=1200, max_rows=30)
        elif key == "final_answer":
            compact[key] = _truncate_for_judge(value, max_text=JUDGE_MAX_FINAL_ANSWER_CHARS, max_rows=1)
        elif key == "tool_events":
            limited = _bounded_list_for_judge(value, max_items=JUDGE_MAX_TOOL_EVENTS)
            compact[key] = _truncate_for_judge(limited, max_text=400, max_rows=2)
        elif key == "sql_outputs":
            limited = _bounded_list_for_judge(value, max_items=JUDGE_MAX_SQL_OUTPUTS)
            compact[key] = _truncate_for_judge(limited, max_text=JUDGE_MAX_SQL_OUTPUT_CHARS, max_rows=1)
        elif key == "chart_outputs":
            limited = _bounded_list_for_judge(value, max_items=JUDGE_MAX_CHART_OUTPUTS)
            compact[key] = _truncate_for_judge(limited, max_text=500, max_rows=2)
        else:
            compact[key] = _truncate_for_judge(value, max_text=3000, max_rows=20)
    return compact


def _summarize_tool_events(blocks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    summarized: list[dict[str, Any]] = []
    seq = 0
    for block in blocks:
        if not isinstance(block, dict) or block.get("type") != "tool_use":
            continue
        seq += 1
        item: dict[str, Any] = {
            "seq_id": seq,
            "tool_name": str(block.get("tool_name") or ""),
        }
        if block.get("input") not in (None, ""):
            item["input"] = _truncate_for_judge(block.get("input"))
        if block.get("output") not in (None, ""):
            item["output"] = _truncate_for_judge(block.get("output"))
        if block.get("is_error"):
            item["is_error"] = True
        summarized.append({key: value for key, value in item.items() if value not in (None, "")})
    return summarized


def _collect_usage(task: dict[str, Any], message: dict[str, Any]) -> dict[str, Any]:
    usage: dict[str, Any] = {}
    if isinstance(task.get("usage"), dict):
        usage.update(task["usage"])
    if isinstance(message.get("usage"), dict):
        usage.update(message["usage"])
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
    return _dedupe(failures)


def auto_rule_check(case: dict[str, Any], *, final_answer: str, blocks: list[dict[str, Any]], sql_outputs: list[str], tool_names: list[str]) -> dict[str, Any]:
    assessment_text = "\n".join(sql_outputs + [final_answer])
    missing_sql_fragments = [
        fragment
        for fragment in case.get("required_sql_fragments") or []
        if str(fragment or "").strip() and str(fragment) not in assessment_text
    ]
    missing_tool_names = [
        name
        for name in case.get("expected_tool_names") or []
        if str(name or "").strip() and str(name) not in tool_names
    ]
    failure_attribution = _auto_failure_attribution(
        assessment_text,
        missing_sql_fragments=missing_sql_fragments,
        missing_tool_names=missing_tool_names,
    )
    return {
        "passed": True,
        "missing_sql_fragments": missing_sql_fragments,
        "forbidden_sql_patterns": [],
        "missing_tool_names": missing_tool_names,
        "triggered_veto_rules": [],
        "failure_attribution": failure_attribution,
    }


def _final_assistant_message(messages: dict[str, Any], task_id: str) -> dict[str, Any]:
    """Pick the last assistant message for ``task_id`` (Chat V2 projected blocks)."""
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
    return candidates[-1] if candidates else {}


def _create_topic(base_url: str, case: dict[str, Any], agent_id: str) -> str:
    topic = http_json(
        "POST",
        f"{base_url}/api/v1/nl2sql/topics",
        {"title": f"Eval {case['case_id']}", "agent_id": agent_id},
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
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Poll task status until terminal, following recovered/replacement tasks.

    Run evidence is no longer pulled from a per-poll event stream: after the task
    is terminal the runner reads the Chat V2 server-projected ``blocks`` from
    ``GET /topics/{id}/messages`` (the same projection the Chat V2 history uses).
    """
    deadline = time.time() + max(1, timeout_seconds)
    current_task_id = task_id
    seen_task_ids = {task_id}
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
        if status in TERMINAL_STATUSES:
            if _is_recovered_task(last_task):
                recovered_task_id = _resolve_recovered_task_id(base_url, topic_id, last_task)
                if recovered_task_id and recovered_task_id not in seen_task_ids:
                    current_task_id = recovered_task_id
                    seen_task_ids.add(recovered_task_id)
                    continue
            return last_task, errors
        time.sleep(0.2)
    errors.append({"code": "timeout", "message": f"task did not finish within {timeout_seconds}s"})
    if last_poll_error:
        errors.append({"code": "poll_error", "message": last_poll_error})
    if not last_task:
        last_task = {"task_id": current_task_id, "task_status": "timeout"}
    else:
        last_task = dict(last_task)
        last_task["task_status"] = str(last_task.get("task_status") or "timeout")
    return last_task, errors


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


def _normalize_judge_payload(data: dict[str, Any], *, raw_output: str = "") -> dict[str, Any]:
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
        "judge_failed": _normalize_bool(data.get("judge_failed")),
        "raw_output": raw_output,
    }


def _failed_judge(reason: str, *, raw_output: str = "", attribution: list[str] | None = None) -> dict[str, Any]:
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


def _case_for_judge(case: dict[str, Any]) -> dict[str, Any]:
    judge_case = dict(case)
    judge_case.pop("forbidden_sql_patterns", None)
    judge_case["veto_rules"] = [
        rule
        for rule in case.get("veto_rules") or []
        if "SQL 不带 schema 前缀" not in str(rule) and "SELECT *" not in str(rule)
    ]
    return judge_case


def _judge_system_prompt() -> str:
    return (
        "你是 DataAgent 在线问数评测裁判。只能基于请求中的 case、最终回答、工具事件、SQL/图表输出和自动规则检查打分。"
        "不要基于 SELECT *、schema 前缀或 SQL 风格合规性扣分；除非 SQL 风格直接导致未查到数据或结果错误。"
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
    judge_payload = _compact_judge_payload(payload)
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
            {"role": "user", "content": _judge_message_content(judge_payload, repair=attempt > 0, previous_output=raw_output)},
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
            return _normalize_judge_payload(parsed, raw_output=raw_output)
        except EvalRunnerError as exc:
            if attempt == max_attempts - 1:
                return _failed_judge(str(exc), raw_output=raw_output, attribution=["judge_failed", "judge_http_error"])
        except Exception as exc:
            if attempt == max_attempts - 1:
                return _failed_judge(f"裁判模型未返回合法 JSON: {exc}", raw_output=raw_output)
    return _failed_judge("裁判模型未返回合法 JSON", raw_output=raw_output)


def _judge_case(judge_config: JudgeConfig, case: dict[str, Any], payload: dict[str, Any]) -> dict[str, Any]:
    judge = call_judge_model(judge_config, payload)
    judge["case_id"] = case.get("case_id")
    return judge


def run_case(base_url: str, case: dict[str, Any], args: argparse.Namespace, judge_config: JudgeConfig) -> dict[str, Any]:
    started = time.time()
    errors: list[dict[str, Any]] = []
    topic_id = ""
    task_id = ""
    task: dict[str, Any] = {}
    message: dict[str, Any] = {}
    final_answer = ""
    try:
        topic_id = _create_topic(base_url, case, str(args.agent_id or "").strip())
        task_id = _submit_task(base_url, topic_id, case, args)
        case_timeout = min(max(1, args.timeout_seconds), int(case.get("max_wait_seconds") or args.timeout_seconds or 900))
        task, poll_errors = _poll_task(base_url, task_id, case_timeout, topic_id=topic_id)
        errors.extend(poll_errors)
        final_task_id = str(task.get("task_id") or task_id).strip() or task_id
        messages = http_json(
            "GET",
            f"{base_url}/api/v1/nl2sql/topics/{urllib.parse.quote(topic_id)}/messages?page=1&page_size=200&order=asc",
            timeout=30,
        )
        message = _final_assistant_message(messages, final_task_id)
        final_answer = str(message.get("content") or "").strip()
        status = str(task.get("task_status") or "").lower()
        if status and status not in SUCCESS_STATUSES:
            errors.append({"code": status, "message": json.dumps(task.get("error") or {}, ensure_ascii=False)})
    except EvalRunnerError as exc:
        errors.append({"code": "runner_error", "message": str(exc)})

    blocks = message.get("blocks") if isinstance(message.get("blocks"), list) else []
    tool_names = _collect_tool_names(blocks)
    sql_outputs = _extract_sql_outputs(blocks, final_answer)
    chart_outputs = _extract_chart_outputs(blocks)
    usage = _collect_usage(task, message)
    rule_check = auto_rule_check(case, final_answer=final_answer, blocks=blocks, sql_outputs=sql_outputs, tool_names=tool_names)
    judge_payload = {
        "case": _case_for_judge(case),
        "user_question": str(case.get("question") or ""),
        "final_answer": final_answer,
        "task_status": str(task.get("task_status") or ""),
        "task_error": task.get("error") if isinstance(task.get("error"), dict) else None,
        "tool_events": _summarize_tool_events(blocks),
        "sql_outputs": sql_outputs,
        "chart_outputs": chart_outputs,
        "auto_rule_check": rule_check,
    }
    judge = _judge_case(judge_config, case, judge_payload) if task_id else {
        "score": 0,
        "dimension_scores": {},
        "hallucination": False,
        "veto_rules_triggered": [],
        "failure_attribution": ["task_not_submitted"],
        "comment": "task was not submitted",
        "judge_failed": True,
    }
    judge = _merge_auto_failure_attribution(judge, rule_check)
    veto_rules = list(rule_check.get("triggered_veto_rules") or []) + list(judge.get("veto_rules_triggered") or [])
    case_passed = (
        not errors
        and str(task.get("task_status") or "").lower() in SUCCESS_STATUSES
        and float(judge.get("score") or 0) >= 8
        and not bool(judge.get("judge_failed"))
        and not bool(judge.get("hallucination"))
        and not veto_rules
    )
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
        "judge": judge,
        "veto_rules_triggered": veto_rules,
        "case_passed": case_passed,
        "errors": errors,
    }


def _run_cases(base_url: str, cases: list[dict[str, Any]], args: argparse.Namespace, judge_config: JudgeConfig) -> list[dict[str, Any]]:
    if args.concurrency <= 1:
        return [run_case(base_url, case, args, judge_config) for case in cases]

    results: list[dict[str, Any] | None] = [None] * len(cases)
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        future_to_index = {pool.submit(run_case, base_url, case, args, judge_config): i for i, case in enumerate(cases)}
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
                    "judge": {"score": 0, "judge_failed": True, "failure_attribution": ["runner_crash"]},
                    "veto_rules_triggered": [],
                    "case_passed": False,
                    "errors": [{"code": "runner_crash", "message": str(exc)}],
                }
            else:
                result = results[index]
                case_id = (result or {}).get("case_id") if result else "?"
                done = len([r for r in results if r is not None])
                print(f"[{done}/{len(cases)}] {case_id} done", file=sys.stderr)
    return [r for r in results if r is not None]


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
    total = len(results)
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
        "total_cases": total,
        "passed_cases": sum(1 for item in results if bool(item.get("case_passed"))),
        "failed_cases": sum(1 for item in results if not bool(item.get("case_passed"))),
        "veto_count": veto_count,
        "judge_failed_count": judge_failed_count,
        "metrics": metrics,
        "gates": GATES,
        "passed": gates_passed,
        "recommendation": "建议上线" if gates_passed else "不建议上线",
    }


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


def render_report(summary: dict[str, Any], results: list[dict[str, Any]]) -> str:
    lines = [
        "# DataAgent 在线评测报告",
        "",
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

        base_url = str(args.base_url or "").rstrip("/")
        judge_config = _judge_config_from_args(args)
        preflight_payload = preflight(base_url)
        dataset_stats["preflight"] = preflight_payload
        dataset_stats["agent_id"] = str(args.agent_id or "").strip()
        results = _run_cases(base_url, cases, args, judge_config)
        summary = build_summary(results, dataset_stats)
        write_outputs(output_dir, results, summary)
        print(f"eval outputs written to: {output_dir}")
        return 0 if summary.get("passed") else 1
    except EvalRunnerError as exc:
        print(str(exc), file=sys.stderr)
        return exc.exit_code


if __name__ == "__main__":
    sys.exit(main())
