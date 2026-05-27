from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import anyio

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core import followup_suggestions
from core.followup_suggestions import generate_followup_suggestions


def test_default_model_runner_uses_bounded_ten_turn_budget(monkeypatch, tmp_path):
    class FakeOptions:
        last_kwargs = None

        def __init__(self, **kwargs):
            FakeOptions.last_kwargs = kwargs

    class AssistantMessage:
        content = '{"suggestions":["查看异常波动对应的明细"]}'

    class ResultMessage:
        subtype = "success"
        result = ""

    async def fake_query(*, prompt, options):
        yield AssistantMessage()
        yield ResultMessage()

    monkeypatch.setitem(
        sys.modules,
        "claude_agent_sdk",
        SimpleNamespace(ClaudeAgentOptions=FakeOptions, query=fake_query),
    )
    monkeypatch.setattr(
        followup_suggestions,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": provider_id,
            "model": model,
            "api_key": "",
            "auth_token": "",
            "base_url": "",
            "supports_partial_messages": False,
        },
    )
    monkeypatch.setattr(followup_suggestions, "resolve_agent_project_cwd", lambda: tmp_path)
    monkeypatch.setattr(followup_suggestions, "resolve_claude_cli_path", lambda _cfg: None)

    async def run():
        return await followup_suggestions._default_model_runner(  # noqa: SLF001
            prompt="生成追问建议",
            provider_id="openrouter",
            model="anthropic/claude-sonnet-4.5",
            timeout_seconds=3,
        )

    result = anyio.run(run)

    assert result == '{"suggestions":["查看异常波动对应的明细"]}'
    assert FakeOptions.last_kwargs["max_turns"] == 10


def test_generate_followup_suggestions_parses_and_normalizes_model_json():
    async def fake_runner(**_kwargs):
        return """
        ```json
        {
          "suggestions": [
            "最近 30 天工作流发布次数趋势",
            " 查看异常峰值对应的工作流明细 ",
            "查看异常峰值对应的工作流明细",
            "按发布操作类型拆解这段趋势"
          ]
        }
        ```
        """

    async def run():
        return await generate_followup_suggestions(
            previous_question="最近 30 天工作流发布次数趋势",
            answer_text="最近 30 天发布次数上升，5 月 20 日出现异常峰值。",
            result_summary="",
            provider_id="openrouter",
            model="anthropic/claude-sonnet-4.5",
            model_runner=fake_runner,
        )

    result = anyio.run(run)

    assert result == {
        "suggestions": ["查看异常峰值对应的工作流明细", "按发布操作类型拆解这段趋势"],
        "source": "generated",
    }


def test_generate_followup_suggestions_extracts_text_from_structured_items():
    async def fake_runner(**_kwargs):
        return """
        {
          "suggestions": [
            {"question": "查看异常峰值对应的工作流明细"},
            "{\\"question\\": \\"按发布操作类型拆解这段趋势\\"}",
            {"suggestions": ["查看失败任务明细"]}
          ]
        }
        """

    async def run():
        return await generate_followup_suggestions(
            previous_question="最近 30 天工作流发布次数趋势",
            answer_text="最近 30 天发布次数上升，5 月 20 日出现异常峰值。",
            result_summary="",
            provider_id="openrouter",
            model="anthropic/claude-sonnet-4.5",
            model_runner=fake_runner,
        )

    result = anyio.run(run)

    assert result == {
        "suggestions": ["查看异常峰值对应的工作流明细", "按发布操作类型拆解这段趋势", "查看失败任务明细"],
        "source": "generated",
    }


def test_generate_followup_suggestions_retries_once_when_model_schema_is_invalid():
    prompts: list[str] = []
    responses = [
        '{"suggestions":[{"question":"查看异常峰值对应的工作流明细"}]}',
        '{"suggestions":["查看异常峰值对应的工作流明细","按发布操作类型拆解这段趋势"]}',
    ]

    async def fake_runner(**kwargs):
        prompts.append(kwargs["prompt"])
        return responses[len(prompts) - 1]

    async def run():
        return await generate_followup_suggestions(
            previous_question="最近 30 天工作流发布次数趋势",
            answer_text="最近 30 天发布次数上升，5 月 20 日出现异常峰值。",
            result_summary="",
            provider_id="openrouter",
            model="anthropic/claude-sonnet-4.5",
            model_runner=fake_runner,
        )

    result = anyio.run(run)

    assert result == {
        "suggestions": ["查看异常峰值对应的工作流明细", "按发布操作类型拆解这段趋势"],
        "source": "generated",
    }
    assert len(prompts) == 2
    assert "上一次输出不符合格式要求" in prompts[1]
    assert "suggestions 数组中的每一项必须是字符串" in prompts[1]


def test_generate_followup_suggestions_returns_fallback_when_model_fails():
    async def failing_runner(**_kwargs):
        raise TimeoutError("model timed out")

    async def run():
        return await generate_followup_suggestions(
            previous_question="最近 30 天工作流发布次数趋势",
            answer_text="SQL 查询如下：select count(*) from workflow_publish_record;",
            result_summary="",
            provider_id="openrouter",
            model="anthropic/claude-sonnet-4.5",
            model_runner=failing_runner,
        )

    result = anyio.run(run)

    assert result["source"] == "fallback"
    assert result["suggestions"] == [
        "解释一下这个 SQL 的逻辑",
        "这个查询还能按哪些维度继续分析？",
        "帮我检查这个 SQL 是否有优化空间",
    ]
