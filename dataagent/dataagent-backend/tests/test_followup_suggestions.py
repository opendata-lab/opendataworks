from __future__ import annotations

import sys
from pathlib import Path

import anyio

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core.followup_suggestions import generate_followup_suggestions


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
