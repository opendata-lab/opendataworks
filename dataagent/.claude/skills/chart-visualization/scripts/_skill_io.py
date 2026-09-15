from __future__ import annotations

"""Skill 脚本的标准输入输出辅助。

本模块只做三件事：读取 JSON 输入、打印 JSON 输出、构造错误负载。
不依赖任何数据平台、MCP 或宿主环境变量，可在无外部集成的环境独立运行。
"""

import json
import sys
from typing import Any


def print_json(payload: dict[str, Any]):
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def error_payload(kind: str, message: str, **extra: Any) -> dict[str, Any]:
    payload = {"kind": kind, "error": message}
    payload.update(extra)
    return payload


def load_json_input(raw: str | None = None, file_path: str | None = None) -> Any:
    if raw:
        return json.loads(raw)
    if file_path:
        with open(file_path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    data = sys.stdin.read().strip()
    if not data:
        raise ValueError("未提供 JSON 输入")
    return json.loads(data)
