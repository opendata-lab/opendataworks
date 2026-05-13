from __future__ import annotations

import sys
import types
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

if "pymysql" not in sys.modules:
    sys.modules["pymysql"] = types.SimpleNamespace(
        connect=lambda *args, **kwargs: None,
        cursors=types.SimpleNamespace(DictCursor=object),
        connections=types.SimpleNamespace(Connection=object),
    )

from main import app


def test_dataagent_runtime_does_not_register_eval_judge_api():
    paths = {getattr(route, "path", "") for route in app.routes}

    assert "/api/v1/dataagent/evals/judge" not in paths
