#!/usr/bin/env bash
# Requires the disposable MySQL and Dolphin environment described in testing-guide.md.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"
exec mvn -B -ntp -f backend/pom.xml \
  -Dtest=WorkflowRuntimeSyncRealIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test "$@"
