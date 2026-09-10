"""Drive one long conversation at production settings and watch what governs it.

The question this answers is whether context governance ever engages in practice.
Folding and compaction are both configured against numbers nobody has measured:
token usage was not persisted at all before 2026-09-10, so every earlier run was
blind. Rather than guess at new thresholds, push a real conversation until the
configured ones either fire or clearly never will.
"""
from __future__ import annotations

import json
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:8811/api/v1/nl2sql"

# Each turn is meant to carry the previous ones forward, so context accumulates
# the way it does in a real session rather than resetting per question.
TURNS = [
    "列出平台里所有工作流，并统计各状态数量",
    "这些工作流分别属于哪些项目？按项目汇总一下数量",
    "平台元数据里一共有多少张表？按数据源类型分组统计",
    "挑其中记录数最多的 5 张表，把它们的字段结构列出来",
    "这几张表之间有血缘关系吗？画一下上下游",
    "最近有哪些调度任务失败过？失败原因分别是什么",
    "把前面查到的工作流、表结构、失败任务综合起来，给一份平台健康度报告",
    "基于上面全部分析，哪些表最值得优化？逐条给出理由和依据",
]


def post(path: str, body: dict) -> dict:
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def get(path: str) -> dict:
    with urllib.request.urlopen(BASE + path, timeout=30) as resp:
        return json.loads(resp.read())


def main() -> int:
    topic = post("/topics", {"title": "multiturn-probe", "agent_id": "agent_opendataworks"})
    topic_id = topic["topic_id"]
    print(f"topic={topic_id}", flush=True)

    for index, question in enumerate(TURNS, start=1):
        started = time.time()
        accepted = post(
            "/tasks",
            {
                "topic_id": topic_id,
                "message_type": "user_message",
                "message_content": question,
            },
        )
        task_id = accepted["task_id"]

        status = "waiting"
        while time.time() - started < 600:
            time.sleep(5)
            status = get(f"/tasks/{task_id}").get("task_status", "")
            if status in ("finished", "error", "suspended"):
                break

        print(
            f"turn {index}: {status} in {time.time() - started:.0f}s  task={task_id}",
            flush=True,
        )
        if status != "finished":
            print(f"  stopping: turn {index} did not finish", flush=True)
            break

    print(f"DONE topic={topic_id}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
