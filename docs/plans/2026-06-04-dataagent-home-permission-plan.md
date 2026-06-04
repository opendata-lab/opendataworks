# DataAgent Home Permission Plan

## Goal

Stop intelligent-query from failing with `permission denied /tmp/dataagent-home/.dataagent`
when the non-root `dataagent-backend` writes into the root-owned host bind mount.

## Tasks

1. Add `dataagent-home-init` one-shot service to dev and prod Compose.
   - Run as `0:0`, reuse the backend image, `restart: "no"`.
   - Mount `${DATAAGENT_HOME_HOST_DIR:-/workspaces}:/workspaces` (root path after the
     simplification; see `2026-06-04-dataagent-workspace-path-simplification-plan.md`).
   - Ensure the home root exists, then
     `chown -R ${DATAAGENT_RUNTIME_UID:-1000}:${DATAAGENT_RUNTIME_GID:-1000}` and
     `chmod -R u+rwX` the home. Do not pre-create a shared `enabled-skills` dir;
     skills are exposed per-topic at runtime, not from one shared cwd.

2. Order dependent services after the init.
   - Add `depends_on: dataagent-home-init: service_completed_successfully` to
     `dataagent-backend` and `dataagent-sandbox-runner` in dev and prod.

3. Update docs.
   - Document the init service in `deploy/README.md` (dev + prod notes).
   - Update the UID/GID comment in `deploy/.env.example`.
   - Add design + plan under the `dataagent-home-permission` topic.

## Verification

- `python3 -c "import yaml; yaml.safe_load(open(f))"` for both Compose files (passed).
- `docker compose -f deploy/docker-compose.prod.yml config` and a real
  `docker compose up` smoke when a Docker host is available: confirm
  `dataagent-home-init` exits 0, `/workspaces` is owned by the
  runtime UID/GID, `dataagent-backend` becomes healthy, and one NL2SQL request no
  longer returns the permission error. Docker was unavailable in the change
  environment, so the live `up` smoke remains unrun here.

## Backout

- Remove the `dataagent-home-init` service and the two `depends_on` edges from dev
  and prod Compose.
- Revert the README and `.env.example` notes.
- No application code or schema changes to revert.
