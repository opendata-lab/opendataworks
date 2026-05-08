from __future__ import annotations

import os
import shutil
from pathlib import Path
from typing import Any


def resolve_claude_cli_path(cfg: Any) -> str | None:
    raw = (
        str(getattr(cfg, "claude_cli_path", "") or "").strip()
        or str(os.getenv("DATAAGENT_CLAUDE_CLI_PATH") or "").strip()
        or str(os.getenv("CLAUDE_CLI_PATH") or "").strip()
    )
    if not raw:
        return None
    expanded = str(Path(raw).expanduser())
    if os.sep not in expanded and (os.altsep is None or os.altsep not in expanded):
        return shutil.which(expanded) or expanded
    return expanded
