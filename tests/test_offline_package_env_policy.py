from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tarfile

import pytest


REPO_ROOT = Path(__file__).resolve().parents[1]


def test_main_offline_package_excludes_runtime_env():
    script = (REPO_ROOT / "scripts" / "create-offline-package.sh").read_text(encoding="utf-8")

    assert "--exclude='./.env'" in script
    assert 'rm -f "$PACKAGED_DEPLOY_DIR/.env"' in script
    assert 'rewrite_offline_env_file "$PACKAGED_DEPLOY_DIR/.env"' not in script
    assert 'rewrite_offline_env_file "$PACKAGED_DEPLOY_DIR/.env.example"' in script
    assert "assert_package_has_no_runtime_env" in script


def test_opendataagent_offline_package_excludes_runtime_env():
    script = (
        REPO_ROOT / "opendataagent" / "scripts" / "create-offline-package.sh"
    ).read_text(encoding="utf-8")

    assert "--exclude='./.env'" in script
    assert 'rm -f "$PACKAGED_DEPLOY_DIR/.env"' in script
    assert 'rewrite_env_file "$PACKAGED_DEPLOY_DIR/.env"' not in script
    assert 'rewrite_env_file "$PACKAGED_DEPLOY_DIR/.env.example"' in script
    assert "assert_package_has_no_runtime_env" in script


def test_offline_loaders_only_initialize_env_when_missing():
    scripts = (
        REPO_ROOT / "scripts" / "load-package-and-start.sh",
        REPO_ROOT / "opendataagent" / "scripts" / "load-package-and-start.sh",
    )

    for script_path in scripts:
        script = script_path.read_text(encoding="utf-8")
        assert "! -f" in script
        assert ".env.example" in script
        assert 'cp "$ASSETS_DIR/.env.example" "$ASSETS_DIR/.env"' in script or (
            'cp "$DEPLOY_DIR/.env.example" "$DEPLOY_DIR/.env"' in script
        )


@pytest.mark.parametrize(
    "loader_path",
    (
        REPO_ROOT / "scripts" / "load-package-and-start.sh",
        REPO_ROOT / "opendataagent" / "scripts" / "load-package-and-start.sh",
    ),
)
def test_offline_loader_preserves_existing_runtime_env(tmp_path: Path, loader_path: Path):
    package_dir = tmp_path / "package"
    scripts_dir = package_dir / "scripts"
    deploy_dir = package_dir / "deploy"
    (deploy_dir / "docker-images").mkdir(parents=True)
    scripts_dir.mkdir()
    (scripts_dir / "load-images.sh").write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
    (deploy_dir / ".env").write_text("KEEP_EXISTING_CONFIG=true\n", encoding="utf-8")
    (deploy_dir / ".env.example").write_text("KEEP_EXISTING_CONFIG=false\n", encoding="utf-8")

    subprocess.run(
        [
            "bash",
            str(loader_path),
            "--package",
            str(package_dir),
            "--no-start",
        ],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
        text=True,
    )

    assert (deploy_dir / ".env").read_text(encoding="utf-8") == "KEEP_EXISTING_CONFIG=true\n"


@pytest.mark.parametrize(
    ("script_path", "archive_name", "extra_args"),
    (
        (
            REPO_ROOT / "scripts" / "create-offline-package.sh",
            "opendataworks-test.tar.xz",
            (),
        ),
        (
            REPO_ROOT / "opendataagent" / "scripts" / "create-offline-package.sh",
            "opendataagent-test.tar.gz",
            ("--skip-build",),
        ),
    ),
)
def test_generated_offline_archive_contains_template_but_not_runtime_env(
    tmp_path: Path,
    script_path: Path,
    archive_name: str,
    extra_args: tuple[str, ...],
):
    if archive_name.endswith(".xz") and shutil.which("xz") is None:
        pytest.skip("xz is required to exercise the main offline packager")

    fake_bin = REPO_ROOT / "tests" / "fixtures" / "offline-package" / "bin"
    output_path = tmp_path / archive_name
    env = os.environ.copy()
    env["PATH"] = f"{fake_bin}{os.pathsep}{env['PATH']}"
    env["OPENDATAWORKS_XZ_LEVEL"] = "0"

    subprocess.run(
        [
            "bash",
            str(script_path),
            "--tag",
            "env-policy-test",
            "--output",
            str(output_path),
            *extra_args,
        ],
        cwd=REPO_ROOT,
        env=env,
        check=True,
        capture_output=True,
        text=True,
    )

    with tarfile.open(output_path, mode="r:*") as archive:
        package_paths = [Path(name) for name in archive.getnames()]
        if script_path == REPO_ROOT / "scripts" / "create-offline-package.sh":
            template_name = next(name for name in archive.getnames() if name.endswith("/deploy/.env.example"))
            template = archive.extractfile(template_name).read().decode("utf-8")
            assert "DATAAGENT_LLM_JSON_FILE" not in template
            assert "DATAAGENT_SANDBOX_MODE=container" in template
            assert "DATAAGENT_SKILLS_DIR=" not in template
            assert "OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE=oda-runner:env-policy-test" in template
            assert not any(str(path).endswith("/dataagent-runtime/settings.json") for path in package_paths)
            # Validate the rewritten artifact, not just the source template. No
            # daemon or service startup is needed for Compose interpolation.
            compose = shutil.which("docker-compose")
            if compose:
                config_dir = tmp_path / "packaged-compose"
                config_dir.mkdir()
                (config_dir / ".env").write_text(template, encoding="utf-8")
                for mode in ("dev", "prod"):
                    file_name = f"docker-compose.{mode}.yml"
                    member = next(name for name in archive.getnames() if name.endswith(f"/deploy/{file_name}"))
                    (config_dir / file_name).write_bytes(archive.extractfile(member).read())
                    subprocess.run(
                        [compose, "--env-file", str(config_dir / ".env"), "-f", str(config_dir / file_name),
                         "config", "--quiet"],
                        cwd=config_dir, capture_output=True, text=True, check=True,
                    )

    assert any(path.name == ".env.example" and path.parent.name == "deploy" for path in package_paths)
    assert not any(path.name == ".env" for path in package_paths)
    if script_path == REPO_ROOT / "scripts" / "create-offline-package.sh":
        assert not any(path.parts[1:2] == ("tools",) for path in package_paths)
