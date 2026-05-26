from __future__ import annotations

import json
import os
import stat
import subprocess
from pathlib import Path


ODW_CLI = Path(__file__).resolve().parents[2] / ".claude" / "skills" / "opendataworks-platform-tools" / "bin" / "odw-cli"


def _write_fake_curl(tmp_path: Path) -> Path:
    script = tmp_path / "curl"
    script.write_text(
        """#!/bin/sh
set -eu
printf '%s\n' "$@" > "${TEST_CURL_ARGS_FILE:?}"
body_file=""
payload=""
url=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o)
      body_file="${2:-}"
      shift 2
      ;;
    -w)
      shift 2
      ;;
    --data-binary)
      payload="${2:-}"
      shift 2
      ;;
    http://*|https://*)
      url="$1"
      shift
      ;;
    *)
      shift
      ;;
  esac
done

printf '%s' "$url" > "${TEST_CURL_URL_FILE:?}"
printf '%s' "$payload" > "${TEST_CURL_PAYLOAD_FILE:?}"
printf '%s' "${TEST_RESPONSE_BODY:?}" > "$body_file"
printf '%s' "${TEST_HTTP_CODE:-200}"
""",
        encoding="utf-8",
    )
    script.chmod(script.stat().st_mode | stat.S_IXUSR)
    return script


def _base_env(tmp_path: Path, base_url: str) -> dict[str, str]:
    url_file = tmp_path / "curl-url.txt"
    payload_file = tmp_path / "curl-payload.txt"
    args_file = tmp_path / "curl-args.txt"
    fake_curl = _write_fake_curl(tmp_path)
    env = dict(os.environ)
    env.update(
        {
            "PATH": f"{fake_curl.parent}:{env.get('PATH', '')}",
            "ODW_BACKEND_BASE_URL": base_url,
            "ODW_AGENT_SERVICE_TOKEN": "test-token",
            "TEST_CURL_URL_FILE": str(url_file),
            "TEST_CURL_PAYLOAD_FILE": str(payload_file),
            "TEST_CURL_ARGS_FILE": str(args_file),
            "TEST_RESPONSE_BODY": '{"kind":"ok"}',
            "TEST_HTTP_CODE": "200",
        }
    )
    return env


def test_odw_cli_inspect_uses_metadata_root_for_new_ai_base_url(tmp_path: Path):
    env = _base_env(tmp_path, "http://backend:8080/api/v1/ai")

    completed = subprocess.run(
        ["sh", str(ODW_CLI), "inspect", "--keyword", "工作流"],
        check=False,
        capture_output=True,
        text=True,
        env=env,
    )

    assert completed.returncode == 0
    assert json.loads(completed.stdout)["kind"] == "ok"
    assert (tmp_path / "curl-url.txt").read_text(encoding="utf-8") == "http://backend:8080/api/v1/ai/metadata/inspect"


def test_odw_cli_lineage_uses_metadata_lineage_endpoint(tmp_path: Path):
    env = _base_env(tmp_path, "http://backend:8080/api/v1/ai")

    completed = subprocess.run(
        ["sh", str(ODW_CLI), "lineage", "--table", "some_table", "--db-name", "doris_ods", "--table-id", "12", "--depth", "2"],
        check=False,
        capture_output=True,
        text=True,
        env=env,
    )

    assert completed.returncode == 0
    assert json.loads(completed.stdout)["kind"] == "ok"
    assert (tmp_path / "curl-url.txt").read_text(encoding="utf-8") == "http://backend:8080/api/v1/ai/metadata/lineage"
    args = (tmp_path / "curl-args.txt").read_text(encoding="utf-8").splitlines()
    assert "table=some_table" in args
    assert "dbName=doris_ods" in args
    assert "tableId=12" in args
    assert "depth=2" in args


def test_odw_cli_query_readonly_uses_query_endpoint_for_legacy_metadata_base_url(tmp_path: Path):
    env = _base_env(tmp_path, "http://backend:8080/api/v1/ai/metadata")

    completed = subprocess.run(
        [
            "sh",
            str(ODW_CLI),
            "query-readonly",
            "--database",
            "opendataworks",
            "--sql",
            "SELECT 1",
            "--preferred-engine",
            "mysql",
            "--limit",
            "20",
            "--timeout-seconds",
            "15",
        ],
        check=False,
        capture_output=True,
        text=True,
        env=env,
    )

    assert completed.returncode == 0
    assert json.loads(completed.stdout)["kind"] == "ok"
    assert (tmp_path / "curl-url.txt").read_text(encoding="utf-8") == "http://backend:8080/api/v1/ai/query/read"
    assert json.loads((tmp_path / "curl-payload.txt").read_text(encoding="utf-8")) == {
        "database": "opendataworks",
        "sql": "SELECT 1",
        "preferredEngine": "mysql",
        "limit": 20,
        "timeoutSeconds": 15,
    }


def test_odw_cli_query_readonly_forwards_agent_data_scope_header(tmp_path: Path):
    env = _base_env(tmp_path, "http://backend:8080/api/v1/ai")
    env["ODW_AGENT_DATA_SCOPE_HEADER"] = "encoded-scope"

    completed = subprocess.run(
        [
            "sh",
            str(ODW_CLI),
            "query-readonly",
            "--database",
            "ads_user",
            "--sql",
            "SELECT 1",
        ],
        check=False,
        capture_output=True,
        text=True,
        env=env,
    )

    assert completed.returncode == 0
    args = (tmp_path / "curl-args.txt").read_text(encoding="utf-8").splitlines()
    assert "X-Agent-Data-Scope: encoded-scope" in args


def test_odw_cli_ddl_uses_metadata_ddl_endpoint(tmp_path: Path):
    env = _base_env(tmp_path, "http://backend:8080/api/v1/ai")

    completed = subprocess.run(
        [
            "sh",
            str(ODW_CLI),
            "ddl",
            "--database",
            "opendataworks",
            "--table",
            "workflow_publish_record",
        ],
        check=False,
        capture_output=True,
        text=True,
        env=env,
    )

    assert completed.returncode == 0
    assert json.loads(completed.stdout)["kind"] == "ok"
    assert (tmp_path / "curl-url.txt").read_text(encoding="utf-8") == "http://backend:8080/api/v1/ai/metadata/ddl"
    args = (tmp_path / "curl-args.txt").read_text(encoding="utf-8").splitlines()
    assert "database=opendataworks" in args
    assert "table=workflow_publish_record" in args
