"""Trusted execution identity; never sourced from model tool arguments."""
from contextvars import ContextVar
from typing import Any

_identity: ContextVar[dict[str, str]] = ContextVar("agent_identity", default={})


def identity_headers(user_id: str, agent_id: str) -> dict[str, str]:
    if not agent_id:
        raise ValueError("缺少智能体身份")
    if not user_id:
        raise ValueError("数据库访问需要登录用户")
    return {"X-Agent-User-Id": user_id, "X-Agent-Id": agent_id}


def current_headers() -> dict[str, str]:
    return dict(_identity.get())


def set_execution_identity(params: Any):
    agent_id = str((getattr(params, "agent_snapshot", None) or {}).get("agent_id") or "")
    # Scheduled messages run as the topic owner, same as a message the owner sends.
    user_id = str(getattr(params, "auth_user_id", "") or "")
    # Anonymous chat stays available; its database tools have no usable identity.
    return _identity.set({"X-Agent-User-Id": user_id, "X-Agent-Id": agent_id})
