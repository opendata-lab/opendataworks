"""Guards which HTTP handlers may sit on the event loop.

FastAPI runs an ``async def`` handler *on the event loop* and a plain ``def``
handler in a threadpool. So a handler that does blocking work — `pymysql` has no
async driver here, and bcrypt is CPU-bound — must be ``def``, or it freezes the
whole process for its duration, including every open SSE chat stream.

That was measured, not assumed. Before the fix, 20 concurrent requests against
`/topics` pushed a trivial `/health` from 5 ms to 185 ms; `/topics/{id}/messages`
pushed it to 242 ms. A single local login ran bcrypt on the loop for 80 ms at
cost 10 and 320 ms at cost 12.

The inverse also matters: a handler that only reads in-memory config should stay
``async def``, because routing it through the threadpool buys a thread hop for no
benefit. Hence two lists rather than one blanket rule.
"""

from __future__ import annotations

import ast
import sys
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))


# Handlers that touch MySQL, the filesystem, or bcrypt. These must not be async.
MUST_BE_SYNC = {
    "api/routes.py": {
        "api_runtime_config",
        "api_widget_events",
        "api_create_topic",
        "api_list_topics",
        "api_get_topic",
        "api_update_topic",
        "api_delete_topic",
        "api_list_topic_messages",
        "api_list_topic_files",
        "api_download_topic_file",
        "api_update_message_feedback",
        "api_get_task",
        "api_get_task_message",
        "api_list_sdk_events",
        "api_submit_permission_decision",
        "api_submit_question_answer",
        "api_query_message_queues",
        "api_create_message_queue",
        "api_update_message_queue",
        "api_delete_message_queue",
        "api_query_message_schedules",
        "api_create_message_schedule",
        "api_update_message_schedule",
        "api_delete_message_schedule",
        "api_get_message_schedule",
        "api_list_message_schedule_logs",
    },
    "api/auth_routes.py": {
        # bcrypt verification is the single most expensive thing on this path.
        "login",
    },
    "api/admin_routes.py": {
        "get_admin_settings",
        "get_providers",
        "get_mcp_servers",
        "get_skill_documents",
        "get_skill_document",
        "get_agents",
        "get_readable_agent_profiles",
        "get_agent",
        "get_agent_configuration",
    },
}

# Handlers that must stay async: they await real coroutines, stream, or only read
# in-memory config.
MUST_BE_ASYNC = {
    "api/routes.py": {
        # Pure in-memory config read; a threadpool hop would be pure overhead.
        "api_health",
        # Awaits the task coordinator or a real coroutine.
        "api_upload_topic_file",
        "api_generate_followup_suggestions",
        "api_deliver_message",
        "api_create_task",
        "api_cancel_task",
        "api_consume_message_queue",
        "api_execute_readonly_query",
        # Returns a StreamingResponse; the SSE generator stays on the loop.
        "api_stream_sdk_events",
    },
    "api/auth_routes.py": {
        "auth_config",
        "me",
        "logout",
        "oauth_authorize",
    },
    "api/admin_routes.py": {
        "create_model_detection",
        "import_skill",
    },
}


def _function_kinds(relative_path: str) -> dict[str, str]:
    tree = ast.parse((BACKEND_ROOT / relative_path).read_text(encoding="utf-8"))
    kinds: dict[str, str] = {}
    for node in tree.body:
        if isinstance(node, ast.AsyncFunctionDef):
            kinds[node.name] = "async"
        elif isinstance(node, ast.FunctionDef):
            kinds[node.name] = "sync"
    return kinds


@pytest.mark.parametrize("relative_path", sorted(MUST_BE_SYNC))
def test_blocking_handlers_are_not_async(relative_path):
    kinds = _function_kinds(relative_path)
    offenders = []
    for name in sorted(MUST_BE_SYNC[relative_path]):
        kind = kinds.get(name)
        assert kind is not None, f"{relative_path}: handler {name} not found; update this contract"
        if kind == "async":
            offenders.append(name)
    assert not offenders, (
        f"{relative_path}: these handlers do blocking I/O but are `async def`, so they run on "
        f"the event loop and stall every other request: {offenders}"
    )


@pytest.mark.parametrize("relative_path", sorted(MUST_BE_ASYNC))
def test_genuinely_async_handlers_stay_async(relative_path):
    kinds = _function_kinds(relative_path)
    offenders = []
    for name in sorted(MUST_BE_ASYNC[relative_path]):
        kind = kinds.get(name)
        assert kind is not None, f"{relative_path}: handler {name} not found; update this contract"
        if kind == "sync":
            offenders.append(name)
    assert not offenders, (
        f"{relative_path}: these handlers await coroutines, stream, or only read memory and must "
        f"stay `async def`: {offenders}"
    )


def test_sse_generator_still_blocks_the_loop_and_is_tracked():
    """The SSE poll loop does blocking store reads on the event loop.

    Measured at ~3.1 ms per poll per open stream (one `list_sdk_records` plus one
    `get_task`), so 10 concurrent streams spend ~3% of each second blocking the
    loop. That is tolerable at current concurrency, and converting the handler
    would not fix it — the blocking calls live in the async generator, so the fix
    is `anyio.to_thread.run_sync` around them. This test pins the known state so
    the next person does not mistake it for already-handled.
    """
    source = (BACKEND_ROOT / "api/routes.py").read_text(encoding="utf-8")
    tree = ast.parse(source)
    generator = next(
        node
        for node in tree.body
        if isinstance(node, ast.AsyncFunctionDef) and node.name == "_stream_sdk_events"
    )
    body = ast.get_source_segment(source, generator) or ""

    assert "store.list_sdk_records(" in body
    assert "store.get_task(" in body
    assert "to_thread" not in body, (
        "the SSE poll loop was moved off the event loop; update this test and the note in "
        "docs/design/2026-09-15-settings-pages-load-performance-design.md"
    )
