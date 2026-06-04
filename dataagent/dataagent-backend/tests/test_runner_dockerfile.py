from __future__ import annotations

from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
RUNNER_DOCKERFILE = REPO_ROOT / "dataagent" / "dataagent-backend" / "Dockerfile.runner"


def test_runner_dockerfile_uses_runner_entrypoint():
    content = RUNNER_DOCKERFILE.read_text(encoding="utf-8")

    assert "sandbox_runner_main:app" in content
    assert 'EXPOSE 8910' in content
    assert "alembic upgrade head" not in content
    assert '"main:app"' not in content
