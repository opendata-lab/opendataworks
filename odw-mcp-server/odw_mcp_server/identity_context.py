from __future__ import annotations

from contextvars import ContextVar
from typing import Callable


_identity_headers: ContextVar[dict[str, str]] = ContextVar("identity_headers", default={})


def get_identity_headers() -> dict[str, str]:
    return dict(_identity_headers.get())


def set_identity_headers(headers: dict[str, str]) -> Callable[[], None]:
    token = _identity_headers.set(dict(headers))
    return lambda: _identity_headers.reset(token)
