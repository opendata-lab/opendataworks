from __future__ import annotations

from contextvars import ContextVar
from typing import Callable


_data_scope_header: ContextVar[str] = ContextVar("data_scope_header", default="")
_operator_header: ContextVar[str] = ContextVar("operator_header", default="")


def get_data_scope_header() -> str:
    return _data_scope_header.get("")


def set_data_scope_header(value: str | None) -> Callable[[], None]:
    token = _data_scope_header.set(str(value or "").strip())

    def reset() -> None:
        _data_scope_header.reset(token)

    return reset


def get_operator_header() -> str:
    return _operator_header.get("")


def set_operator_header(value: str | None) -> Callable[[], None]:
    token = _operator_header.set(str(value or "").strip())

    def reset() -> None:
        _operator_header.reset(token)

    return reset
