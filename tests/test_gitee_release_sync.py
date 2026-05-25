from __future__ import annotations

import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
RENDER_SCRIPT = REPO_ROOT / "scripts" / "release" / "render-gitee-release-body.sh"
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "docker-build.yml"


def test_gitee_release_body_uses_uploaded_attachment_links(tmp_path):
    release_body = tmp_path / "release_body.md"
    attachments = tmp_path / "gitee_attach_files.jsonl"

    release_body.write_text(
        "\n".join(
            [
                "## OpenDataWorks v1.2.3",
                "",
                "### 离线部署包 (Offline Deployment Package)",
                "- **[opendataworks-deployment-1.2.3.tar.gz](https://github.com/MingkeVan/opendataworks/releases/download/v1.2.3/opendataworks-deployment-1.2.3.tar.gz)** - 适用于无外网环境",
                "- 校验和: `opendataworks-deployment-1.2.3.tar.gz.sha256`",
                "- **[opendataagent-deployment-1.2.3.tar.gz](https://github.com/MingkeVan/opendataworks/releases/download/v1.2.3/opendataagent-deployment-1.2.3.tar.gz)** - 独立 `opendataagent` 离线部署包",
                "- [Frontend](https://hub.docker.com/r/example/opendataworks-frontend/tags?name=1.2.3)",
            ]
        ),
        encoding="utf-8",
    )
    attachments.write_text(
        "\n".join(
            [
                '{"id":1001,"name":"opendataworks-deployment-1.2.3.tar.gz"}',
                '{"id":1002,"name":"opendataagent-deployment-1.2.3.tar.gz"}',
            ]
        )
        + "\n",
        encoding="utf-8",
    )

    result = subprocess.run(
        ["bash", str(RENDER_SCRIPT), str(release_body), str(attachments), "opendata-lab/opendataworks"],
        check=True,
        text=True,
        capture_output=True,
    )

    assert "https://github.com/MingkeVan/opendataworks/releases/download" not in result.stdout
    assert (
        "https://gitee.com/opendata-lab/opendataworks/attach_files/1001/download"
        in result.stdout
    )
    assert (
        "https://gitee.com/opendata-lab/opendataworks/attach_files/1002/download"
        in result.stdout
    )
    assert "https://hub.docker.com/r/example/opendataworks-frontend/tags?name=1.2.3" in result.stdout


def test_gitee_sync_workflow_patches_release_body_after_upload():
    workflow = WORKFLOW.read_text(encoding="utf-8")

    assert workflow.count("render-gitee-release-body.sh") == 2
    assert workflow.count(": > gitee_attach_files.jsonl") == 2
    assert workflow.count(">> gitee_attach_files.jsonl") == 2
    assert workflow.count("-X PATCH") >= 2
