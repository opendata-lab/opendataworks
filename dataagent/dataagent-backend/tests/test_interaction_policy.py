from __future__ import annotations

from types import SimpleNamespace

from core.interaction_policy import (
    is_recommended,
    plan_deadline,
    question_deadline,
    recommended_answers,
)
from core.user_interaction import plan_rejection_message


def test_recommended_answers_pick_the_recommendation_or_skip():
    questions = [
        {
            "header": "粒度",
            "question": "订单事实按什么粒度？",
            "options": [{"label": "按订单行（推荐）"}, {"label": "按订单"}],
        },
        {
            "header": "口径",
            "question": "退款是否扣减？",
            "options": [{"label": "扣减", "recommended": True}, {"label": "不扣减"}],
        },
        {"header": "命名", "question": "表名前缀？", "options": [{"label": "dwd_"}, {"label": "fact_"}]},
        {
            "header": "维度",
            "question": "关联哪些维度？",
            "multiSelect": True,
            "options": [{"label": "门店 (Recommended)"}, {"label": "商品", "recommended": True}, {"label": "会员"}],
        },
    ]

    answers = recommended_answers(questions)

    assert [a["selected"] for a in answers] == [
        ["按订单行（推荐）"],
        ["扣减"],
        [],
        ["门店 (Recommended)", "商品"],
    ]
    assert [a["skipped"] for a in answers] == [False, False, True, False]
    assert all(a["auto"] for a in answers)


def test_is_recommended_accepts_plain_strings():
    assert is_recommended("按天(推荐)")
    assert not is_recommended("按天")


def test_plan_deadline_only_when_agent_approves_on_timeout():
    cfg = SimpleNamespace(dataagent_interaction_timeout_seconds=600)

    assert question_deadline(cfg).seconds == 600
    assert plan_deadline(cfg, {"plan_timeout_action": "approve"}).seconds == 600
    assert plan_deadline(cfg, {"plan_timeout_action": "park"}).seconds == 0
    assert plan_deadline(cfg, {}).seconds == 0
    assert plan_deadline(cfg, {"plan_timeout_action": "approve"}).iso


def test_zero_timeout_waits_forever():
    deadline = question_deadline(SimpleNamespace(dataagent_interaction_timeout_seconds=0))

    assert deadline.seconds == 0
    assert not deadline.expired
    assert deadline.iso == ""


def test_plan_rejection_carries_the_users_note():
    assert "先不建汇总表" in plan_rejection_message("先不建汇总表")
    assert "ExitPlanMode" in plan_rejection_message("")
