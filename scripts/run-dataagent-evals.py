#!/usr/bin/env python3
from __future__ import annotations

import runpy
from pathlib import Path


RUNNER = Path(__file__).resolve().parents[1] / "tools" / "dataagent-evals" / "builtin" / "run.py"


if __name__ == "__main__":
    runpy.run_path(str(RUNNER), run_name="__main__")
