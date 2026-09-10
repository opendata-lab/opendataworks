#!/usr/bin/env python3
"""Fail when internal production identifiers appear in tracked files.

This repository is public. Real table names, column names and business terms
have reached it twice: once through evaluation fixtures that hardcoded live
tables, and once through a design document that quoted a real business
question. Both were caught by hand, months apart in the first case.

Patterns live in INTERNAL_PATTERNS below. Keep them narrow: a pattern that
fires on ordinary words trains people to skip the check, which is worse than
not having it.

Usage:
    scripts/check-internal-identifiers.py            # scan tracked files
    scripts/check-internal-identifiers.py --staged   # scan staged changes only

Exit codes: 0 clean, 1 findings, 2 usage/environment error.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys

# (regex, why it matters). Anchored to shapes that are unambiguously internal.
INTERNAL_PATTERNS: list[tuple[str, str]] = [
    (r"\bdim_tech_[a-z0-9_]+\b", "internal warehouse table name"),
    (r"分级保障", "internal business term"),
]

# Paths that legitimately contain the patterns: this script and its test.
ALLOWLIST = (
    "scripts/check-internal-identifiers.py",
    "tests/test_internal_identifier_scan.py",
)

# Binary and vendored trees carry no reviewable text.
SKIP_PREFIXES = ("node_modules/", "dist/", "build/", ".venv")
SKIP_SUFFIXES = (
    ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".pdf", ".woff", ".woff2",
    ".ttf", ".zip", ".tar", ".gz", ".jar", ".class", ".pyc", ".lock",
)


def _git(*args: str) -> list[str]:
    try:
        out = subprocess.run(
            ["git", *args], check=True, capture_output=True, text=True
        ).stdout
    except (subprocess.CalledProcessError, FileNotFoundError) as exc:
        print(f"check-internal-identifiers: git failed: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc
    return [line for line in out.splitlines() if line]


def _files(staged: bool) -> list[str]:
    paths = (
        _git("diff", "--cached", "--name-only", "--diff-filter=ACMR")
        if staged
        else _git("ls-files")
    )
    return [
        p
        for p in paths
        if p not in ALLOWLIST
        and not p.startswith(SKIP_PREFIXES)
        and not p.endswith(SKIP_SUFFIXES)
    ]


def scan(paths: list[str]) -> list[tuple[str, int, str, str]]:
    """Return (path, line_no, matched_text, reason) for every hit."""
    compiled = [(re.compile(pattern), reason) for pattern, reason in INTERNAL_PATTERNS]
    findings: list[tuple[str, int, str, str]] = []
    for path in paths:
        try:
            with open(path, encoding="utf-8") as handle:
                for line_no, line in enumerate(handle, 1):
                    for regex, reason in compiled:
                        match = regex.search(line)
                        if match:
                            findings.append((path, line_no, match.group(0), reason))
        except (OSError, UnicodeDecodeError):
            # Unreadable or non-UTF-8 content has nothing to review.
            continue
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--staged", action="store_true", help="scan staged changes instead of all tracked files"
    )
    args = parser.parse_args()

    findings = scan(_files(args.staged))
    if not findings:
        return 0

    print("Internal identifiers found in tracked content:\n", file=sys.stderr)
    for path, line_no, text, reason in findings:
        print(f"  {path}:{line_no}: {text}  ({reason})", file=sys.stderr)
    print(
        "\nReplace them with fictional equivalents. Fixtures need a syntactically\n"
        "valid value, not a real one. If a pattern is wrong, narrow it in\n"
        "scripts/check-internal-identifiers.py rather than working around it.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
