from __future__ import annotations

from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
IMAGE_NAME = "opendataworks-dataagent-evals-deepeval"
BUILTIN_IMAGE_NAME = "opendataworks-dataagent-evals-builtin"


def test_offline_package_scripts_reference_deepeval_image_and_evals_dir():
    create_package = (REPO_ROOT / "scripts" / "create-offline-package.sh").read_text(encoding="utf-8")
    load_images = (REPO_ROOT / "scripts" / "load-images.sh").read_text(encoding="utf-8")
    build_images = (REPO_ROOT / "scripts" / "build" / "build-images.sh").read_text(encoding="utf-8")
    build_multiarch = (REPO_ROOT / "scripts" / "build" / "build-multiarch.sh").read_text(encoding="utf-8")

    assert IMAGE_NAME in create_package
    assert "evals" in create_package
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

    assert (REPO_ROOT / "evals" / "dataagent-arch-governance-builtin" / "run.py").exists()
    assert (REPO_ROOT / "evals" / "dataagent-arch-governance-builtin" / "Dockerfile").exists()
    assert BUILTIN_IMAGE_NAME in create_package
    assert "opendataworks-dataagent-evals-builtin.tar" in load_images
    assert BUILTIN_IMAGE_NAME in build_images
    assert BUILTIN_IMAGE_NAME in build_multiarch
    assert "OPENDATAWORKS_DATAAGENT_EVALS_BUILTIN_IMAGE" in run_wrapper
    assert "DATAAGENT_BUILTIN_RUN_LOCAL" in run_wrapper
    assert "DATAAGENT_EVAL_JUDGE_MAX_TOKENS" in run_wrapper
