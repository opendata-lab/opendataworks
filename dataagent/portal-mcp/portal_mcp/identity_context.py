from __future__ import annotations

from contextvars import ContextVar
from typing import Callable


_identity_headers: ContextVar[dict[str, str]] = ContextVar("identity_headers", default={})
_operator_header: ContextVar[str] = ContextVar("operator_header", default="")


def get_identity_headers() -> dict[str, str]:
    return dict(_identity_headers.get())


def set_identity_headers(headers: dict[str, str]) -> Callable[[], None]:
    token = _identity_headers.set(dict(headers))
    return lambda: _identity_headers.reset(token)


def get_operator_header() -> str:
    return _operator_header.get("")


def set_operator_header(value: str | None) -> Callable[[], None]:
    token = _operator_header.set(str(value or "").strip())

    def reset() -> None:
        _operator_header.reset(token)

    return reset
