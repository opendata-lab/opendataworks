#!/usr/bin/env bash
# Install repository git hooks.
#
# .git/hooks/ is not version controlled, so hooks cannot ship with a clone.
# This installs them on demand. CI runs the same scan regardless, so skipping
# this only costs you a round trip — it does not let a leak through.
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
HOOK="$REPO_ROOT/.git/hooks/pre-commit"

cat > "$HOOK" <<'HOOK_BODY'
#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(git rev-parse --show-toplevel)"
python3 "$REPO_ROOT/scripts/check-internal-identifiers.py" --staged
HOOK_BODY

chmod +x "$HOOK"
echo "installed: $HOOK"
