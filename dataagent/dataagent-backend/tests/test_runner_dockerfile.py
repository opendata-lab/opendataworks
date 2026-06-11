from __future__ import annotations

from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
BACKEND_DOCKERFILE = REPO_ROOT / "dataagent" / "dataagent-backend" / "Dockerfile"
RUNNER_DOCKERFILE = REPO_ROOT / "dataagent" / "dataagent-backend" / "Dockerfile.runner"
OLD_SANDBOX_ROOT_ENV = "DATAAGENT_" "SANDBOX_ROOT"
OLD_WORKSPACES_ROOT = "/" "workspaces"


def test_runner_dockerfile_uses_runner_entrypoint():
    content = RUNNER_DOCKERFILE.read_text(encoding="utf-8")

    assert "ENV HOME=/dataagent_runtime" in content
    assert OLD_SANDBOX_ROOT_ENV not in content
    assert "mkdir -p /dataagent_runtime /app/.claude/skills" in content
    assert OLD_WORKSPACES_ROOT not in content
    assert "WORKDIR /opt/dataagent-backend" in content
    assert "COPY dataagent/dataagent-backend /opt/dataagent-backend" in content
    assert "COPY dataagent/.claude" not in content
    assert "sandbox_runner_main:app" in content
    assert 'EXPOSE 8910' in content
    assert "alembic upgrade head" not in content
    assert '"main:app"' not in content


def test_backend_dockerfile_uses_opt_backend_and_no_bundled_skills():
    content = BACKEND_DOCKERFILE.read_text(encoding="utf-8")

    assert "ENV HOME=/dataagent_runtime" in content
    assert OLD_SANDBOX_ROOT_ENV not in content
    assert "mkdir -p /dataagent_runtime /app/.claude/skills" in content
    assert OLD_WORKSPACES_ROOT not in content
    assert "WORKDIR /opt/dataagent-backend" in content
    assert "COPY dataagent/dataagent-backend /opt/dataagent-backend" in content
    assert "COPY dataagent/.claude" not in content
    assert "alembic upgrade head" in content
    assert 'EXPOSE 8900' in content
