# DataAgent DeepEval Parallel Evaluation Plan

## Implementation Tasks

- Add a standalone DeepEval runner under `evals/dataagent-arch-governance-deepeval/` with requirements, Dockerfile, and README.
- Add the shared JSONL dataset under `evals/dataagent-arch-governance/` while leaving the existing builtin runner path intact.
- Add `scripts/run-dataagent-deepeval-evals.sh` as the manual Docker/Podman entrypoint.
- Add `opendataworks-dataagent-evals-deepeval:<tag>` to image build, offline package creation, and offline image loading scripts.
- Update deploy and scripts documentation with DeepEval run commands and judge environment variables.

## Verification

- Unit test JSONL-to-DeepEval case conversion and custom metric normalization.
- Contract test fake DataAgent HTTP flow plus fake DeepEval evaluation.
- Contract test offline packaging scripts reference the DeepEval image and eval directory.
- Run shell syntax checks for the touched scripts.
- Run DeepEval runner dry-run against the shared dataset.
