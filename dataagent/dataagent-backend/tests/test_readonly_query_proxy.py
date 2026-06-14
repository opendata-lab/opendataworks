from __future__ import annotations

import base64
import json
import sys
import types
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

if "pymysql" not in sys.modules:
    sys.modules["pymysql"] = types.SimpleNamespace(
        connect=lambda *args, **kwargs: None,
        cursors=types.SimpleNamespace(DictCursor=object),
        connections=types.SimpleNamespace(Connection=object),
    )

import core.readonly_query_proxy as proxy
from core.readonly_query_proxy import (
    QueryProxyConfigError,
    QueryProxyUpstreamError,
    clamp_limit,
    clamp_timeout_seconds,
    execute_readonly_query,
)
from main import app


PROXY_ENV = {
    "ODW_BACKEND_BASE_URL": "http://backend:8080/api/v1/ai",
    "ODW_AGENT_SERVICE_TOKEN": "test-token",
}


def configure_proxy_env(monkeypatch, env=PROXY_ENV):
    for key in (
        "ODW_BACKEND_BASE_URL",
        "ODW_AGENT_SERVICE_TOKEN",
        "ODW_AGENT_SERVICE_TOKEN_HEADER_NAME",
        "ODW_AGENT_DATA_SCOPE_HEADER",
        "DATAAGENT_DATA_SCOPE_HEADER",
        "DATAAGENT_DATA_SCOPE_JSON",
    ):
        monkeypatch.delenv(key, raising=False)
    for key, value in env.items():
        monkeypatch.setenv(key, value)


def install_upstream(monkeypatch, handler):
    transport = httpx.MockTransport(handler)
    original_client = httpx.AsyncClient

    def client_factory(*args, **kwargs):
        kwargs["transport"] = transport
        return original_client(*args, **kwargs)

    monkeypatch.setattr(proxy.httpx, "AsyncClient", client_factory)


def upstream_ok(request: httpx.Request) -> httpx.Response:
    body = json.loads(request.content.decode("utf-8"))
    return httpx.Response(
        200,
        json={
            "kind": "query_result",
            "database": body["database"],
            "engine": "mysql",
            "sql": body["sql"],
            "limit": body["limit"],
            "row_count": 2,
            "has_more": False,
            "duration_ms": 12,
            "truncated_by_size": False,
            "notice": None,
            "rows": [
                {"category": "A", "cnt": 3},
                {"category": "B", "cnt": 1},
            ],
        },
    )


class TestClamps:
    def test_limit_default_and_bounds(self):
        assert clamp_limit(None) == 100
        assert clamp_limit("abc") == 100
        assert clamp_limit(0) == 1
        assert clamp_limit(500) == 500
        assert clamp_limit(99999) == 1000

    def test_timeout_default_and_bounds(self):
        assert clamp_timeout_seconds(None) == 30
        assert clamp_timeout_seconds(-5) == 1
        assert clamp_timeout_seconds(60) == 60
        assert clamp_timeout_seconds(9999) == 120


class TestExecuteReadonlyQuery:
    @pytest.mark.anyio
    async def test_normalizes_upstream_query_result(self, monkeypatch):
        configure_proxy_env(monkeypatch)
        captured = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["url"] = str(request.url)
            captured["headers"] = request.headers
            captured["body"] = json.loads(request.content.decode("utf-8"))
            return upstream_ok(request)

        install_upstream(monkeypatch, handler)

        result = await execute_readonly_query(
            "SELECT category, COUNT(*) AS cnt FROM demo.t GROUP BY category",
            "demo",
            engine="mysql",
            limit=200,
            timeout_seconds=20,
        )

        assert captured["url"] == "http://backend:8080/api/v1/ai/query/read"
        assert captured["headers"]["X-Agent-Service-Token"] == "test-token"
        assert captured["body"]["limit"] == 200
        assert captured["body"]["timeoutSeconds"] == 20
        assert captured["body"]["preferredEngine"] == "mysql"

        assert result["kind"] == "sql_execution"
        assert result["columns"] == ["category", "cnt"]
        assert result["row_count"] == 2
        assert result["result_state"] == "success"
        assert result["error"] is None

    @pytest.mark.anyio
    async def test_metadata_base_url_is_normalized(self, monkeypatch):
        configure_proxy_env(
            monkeypatch,
            {
                "ODW_BACKEND_BASE_URL": "http://backend:8080/api/v1/ai/metadata",
                "ODW_AGENT_SERVICE_TOKEN": "test-token",
            },
        )
        captured = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["url"] = str(request.url)
            return upstream_ok(request)

        install_upstream(monkeypatch, handler)
        await execute_readonly_query("SELECT 1", "demo")
        assert captured["url"] == "http://backend:8080/api/v1/ai/query/read"

    @pytest.mark.anyio
    async def test_data_scope_header_from_json(self, monkeypatch):
        configure_proxy_env(monkeypatch)
        monkeypatch.setenv(
            "DATAAGENT_DATA_SCOPE_JSON",
            '{"allowed_scopes": [{"database": "demo", "source_type": "MYSQL"}]}',
        )
        captured = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["scope"] = request.headers.get("X-Agent-Data-Scope")
            return upstream_ok(request)

        install_upstream(monkeypatch, handler)
        await execute_readonly_query("SELECT 1", "demo")
        assert captured["scope"]
        # base64url 无填充
        assert "=" not in captured["scope"]
        decoded = base64.urlsafe_b64decode(captured["scope"] + "=" * (-len(captured["scope"]) % 4))
        assert b"demo" in decoded

    @pytest.mark.anyio
    async def test_data_scope_header_prefers_precomputed_value(self, monkeypatch):
        configure_proxy_env(monkeypatch)
        monkeypatch.setenv("ODW_AGENT_DATA_SCOPE_HEADER", "precomputed-scope-token")
        captured = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["scope"] = request.headers.get("X-Agent-Data-Scope")
            return upstream_ok(request)

        install_upstream(monkeypatch, handler)
        await execute_readonly_query("SELECT 1", "demo")
        assert captured["scope"] == "precomputed-scope-token"

    @pytest.mark.anyio
    async def test_empty_result_state(self, monkeypatch):
        configure_proxy_env(monkeypatch)

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={"kind": "query_result", "rows": [], "row_count": 0, "has_more": False},
            )

        install_upstream(monkeypatch, handler)
        result = await execute_readonly_query("SELECT 1 WHERE 1=0", "demo")
        assert result["result_state"] == "empty_result"
        assert result["rows"] == []

    @pytest.mark.anyio
    async def test_missing_config_raises(self, monkeypatch):
        configure_proxy_env(monkeypatch, env={})
        with pytest.raises(QueryProxyConfigError):
            await execute_readonly_query("SELECT 1", "demo")

    @pytest.mark.anyio
    async def test_upstream_4xx_passthrough(self, monkeypatch):
        configure_proxy_env(monkeypatch)

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(403, json={"message": "数据范围不允许访问该库"})

        install_upstream(monkeypatch, handler)
        with pytest.raises(QueryProxyUpstreamError) as exc_info:
            await execute_readonly_query("SELECT 1", "forbidden_db")
        assert exc_info.value.status_code == 403
        assert "数据范围" in str(exc_info.value)

    @pytest.mark.anyio
    async def test_upstream_5xx_maps_to_502(self, monkeypatch):
        configure_proxy_env(monkeypatch)

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(500, text="boom")

        install_upstream(monkeypatch, handler)
        with pytest.raises(QueryProxyUpstreamError) as exc_info:
            await execute_readonly_query("SELECT 1", "demo")
        assert exc_info.value.status_code == 502

    @pytest.mark.anyio
    async def test_network_error_maps_to_502(self, monkeypatch):
        configure_proxy_env(monkeypatch)

        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("connection refused", request=request)

        install_upstream(monkeypatch, handler)
        with pytest.raises(QueryProxyUpstreamError) as exc_info:
            await execute_readonly_query("SELECT 1", "demo")
        assert exc_info.value.status_code == 502


class TestExecuteQueryRoute:
    def test_execute_route_success(self, monkeypatch):
        configure_proxy_env(monkeypatch)
        install_upstream(monkeypatch, upstream_ok)

        client = TestClient(app)
        response = client.post(
            "/api/v1/nl2sql/query/execute",
            json={"sql": "SELECT 1", "database": "demo", "limit": 50},
        )
        assert response.status_code == 200
        payload = response.json()
        assert payload["kind"] == "sql_execution"
        assert payload["result_state"] == "success"

    def test_execute_route_rejects_blank_sql(self, monkeypatch):
        configure_proxy_env(monkeypatch)
        client = TestClient(app)
        response = client.post(
            "/api/v1/nl2sql/query/execute",
            json={"sql": "   ", "database": "demo"},
        )
        assert response.status_code == 400

    def test_execute_route_rejects_blank_database(self, monkeypatch):
        configure_proxy_env(monkeypatch)
        client = TestClient(app)
        response = client.post(
            "/api/v1/nl2sql/query/execute",
            json={"sql": "SELECT 1", "database": ""},
        )
        assert response.status_code == 400

    def test_execute_route_unconfigured_returns_503(self, monkeypatch):
        configure_proxy_env(monkeypatch, env={})
        client = TestClient(app)
        response = client.post(
            "/api/v1/nl2sql/query/execute",
            json={"sql": "SELECT 1", "database": "demo"},
        )
        assert response.status_code == 503

    def test_execute_route_upstream_failure_maps_status(self, monkeypatch):
        configure_proxy_env(monkeypatch)

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(400, json={"message": "仅允许只读 SQL"})

        install_upstream(monkeypatch, handler)
        client = TestClient(app)
        response = client.post(
            "/api/v1/nl2sql/query/execute",
            json={"sql": "DELETE FROM t", "database": "demo"},
        )
        assert response.status_code == 400
        assert "只读" in response.json()["detail"]
