from __future__ import annotations

from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
IMAGE_NAME = "opendataworks-dataagent-evals-deepeval"
BUILTIN_IMAGE_NAME = "opendataworks-dataagent-evals-builtin"
RUNNER_IMAGE_NAME = "opendataworks-dataagent-runner"


def test_offline_package_scripts_reference_deepeval_image_and_tools_dir():
    create_package = (REPO_ROOT / "scripts" / "create-offline-package.sh").read_text(encoding="utf-8")
    load_images = (REPO_ROOT / "scripts" / "load-images.sh").read_text(encoding="utf-8")
    build_images = (REPO_ROOT / "scripts" / "build" / "build-images.sh").read_text(encoding="utf-8")
    build_multiarch = (REPO_ROOT / "scripts" / "build" / "build-multiarch.sh").read_text(encoding="utf-8")

    assert IMAGE_NAME in create_package
    assert "tools/dataagent-evals" in create_package
    assert "REPO_ROOT/evals" not in create_package
    assert "dataagent/evals" not in create_package
    assert "dataagent-runtime/evals" not in create_package
    assert IMAGE_NAME in load_images
    assert "opendataworks-dataagent-evals-deepeval.tar" in load_images
    assert IMAGE_NAME in build_images
    assert IMAGE_NAME in build_multiarch


def test_builtin_eval_module_is_packaged_as_parallel_image():
    create_package = (REPO_ROOT / "scripts" / "create-offline-package.sh").read_text(encoding="utf-8")
    load_images = (REPO_ROOT / "scripts" / "load-images.sh").read_text(encoding="utf-8")
    build_images = (REPO_ROOT / "scripts" / "build" / "build-images.sh").read_text(encoding="utf-8")
    build_multiarch = (REPO_ROOT / "scripts" / "build" / "build-multiarch.sh").read_text(encoding="utf-8")
    run_wrapper = (REPO_ROOT / "scripts" / "run-dataagent-evals.sh").read_text(encoding="utf-8")

    assert (REPO_ROOT / "tools" / "dataagent-evals" / "builtin" / "run.py").exists()
    assert (REPO_ROOT / "tools" / "dataagent-evals" / "builtin" / "Dockerfile").exists()
    assert BUILTIN_IMAGE_NAME in create_package
    assert "opendataworks-dataagent-evals-builtin.tar" in load_images
    assert BUILTIN_IMAGE_NAME in build_images
    assert BUILTIN_IMAGE_NAME in build_multiarch
    assert "OPENDATAWORKS_DATAAGENT_EVALS_BUILTIN_IMAGE" in run_wrapper
    assert "DATAAGENT_BUILTIN_RUN_LOCAL" in run_wrapper
    assert "DATAAGENT_EVAL_JUDGE_MAX_TOKENS" in run_wrapper
    assert "tools/dataagent-evals/builtin/run.py" in run_wrapper


def test_dataagent_sandbox_runner_image_is_packaged_and_built():
    create_package = (REPO_ROOT / "scripts" / "create-offline-package.sh").read_text(encoding="utf-8")
    load_images = (REPO_ROOT / "scripts" / "load-images.sh").read_text(encoding="utf-8")
    build_images = (REPO_ROOT / "scripts" / "build" / "build-images.sh").read_text(encoding="utf-8")
    build_multiarch = (REPO_ROOT / "scripts" / "build" / "build-multiarch.sh").read_text(encoding="utf-8")
    build_quick = (REPO_ROOT / "scripts" / "build" / "build-quick.sh").read_text(encoding="utf-8")
    workflow = (REPO_ROOT / ".github" / "workflows" / "docker-build.yml").read_text(encoding="utf-8")
    env_example = (REPO_ROOT / "deploy" / ".env.example").read_text(encoding="utf-8")
    compose_prod = (REPO_ROOT / "deploy" / "docker-compose.prod.yml").read_text(encoding="utf-8")

    assert RUNNER_IMAGE_NAME in create_package
    assert "opendataworks-dataagent-runner.tar" in create_package
    assert "OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE=opendataworks-dataagent-runner:${PARSER_TAG}" in create_package
    assert "DATAAGENT_SANDBOX_IMAGE=opendataworks-dataagent-runner:${PARSER_TAG}" in create_package
    assert "opendataworks-dataagent-runner.tar" in load_images
    assert RUNNER_IMAGE_NAME in build_images
    assert "Dockerfile.runner" in build_images
    assert RUNNER_IMAGE_NAME in build_multiarch
    assert "Dockerfile.runner" in build_multiarch
    assert "BUILD_DATAAGENT_RUNNER" in build_quick
    assert RUNNER_IMAGE_NAME in workflow
    assert "Dockerfile.runner" in workflow
    assert "OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE" in env_example
    assert "opendataworks-dataagent-runner:1.3.0" in compose_prod
    assert "opendataworks-dataagent-runner:1.2.0" not in compose_prod


def test_deepeval_eval_wrapper_keeps_volume_array_non_empty_for_bash_3():
    run_wrapper = (REPO_ROOT / "scripts" / "run-dataagent-deepeval-evals.sh").read_text(encoding="utf-8")

    assert "VOLUMES=(-v \"$REPO_ROOT:/workspace\")" in run_wrapper
    assert "EXTRA_VOLUMES=()" not in run_wrapper
    assert "\"${VOLUMES[@]}\"" in run_wrapper


def test_online_eval_design_documents_verified_parallel_concurrency():
    design = (REPO_ROOT / "docs" / "design" / "2026-05-12-dataagent-online-evaluation-design.md").read_text(
        encoding="utf-8"
    )

    assert "runs sequentially" not in design
    assert "--concurrency > 1" in design


def test_private_business_skill_assets_are_ignored_and_not_packaged():
    gitignore = (REPO_ROOT / ".gitignore").read_text(encoding="utf-8")
    create_package = (REPO_ROOT / "scripts" / "create-offline-package.sh").read_text(encoding="utf-8")

    assert "/dataagent/.claude/skills/*/" in gitignore
    assert "!/dataagent/.claude/skills/dataagent-nl2sql/" in gitignore
    assert "/evals/" in gitignore
    assert "*-core.jsonl" in gitignore
    assert "--exclude='*-assistant'" in create_package
