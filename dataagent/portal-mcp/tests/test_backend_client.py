from __future__ import annotations

import sys
from pathlib import Path

import httpx
import pytest

SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from portal_mcp.backend_client import BackendApiClient, BackendApiError
from portal_mcp.config import Settings
from portal_mcp.scope_context import set_data_scope_header


def _settings() -> Settings:
    return Settings(
        backend_base_url="http://backend:8080/api",
        backend_service_token="service-token",
        backend_token_header_name="X-Agent-Service-Token",
        backend_timeout_seconds=30,
        frontdoor_token="portal-token",
        frontdoor_token_header_name="X-Portal-MCP-Token",
        host="0.0.0.0",
        port=8801,
        mcp_mount_path="/mcp",
    )


class _FakeAsyncClient:
    def __init__(self, response=None, error: Exception | None = None, **kwargs):
        self._response = response
        self._error = error

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def request(self, *args, **kwargs):
        self.last_kwargs = kwargs
        if self._error is not None:
            raise self._error
        return self._response


def _response(status_code: int, *, json_payload=None, text: str = "") -> httpx.Response:
    request = httpx.Request("GET", "http://backend:8080/api/v1/ai/metadata/inspect")
    if json_payload is not None:
        return httpx.Response(status_code, json=json_payload, request=request)
    return httpx.Response(status_code, text=text, request=request)


@pytest.mark.anyio
async def test_backend_client_uses_backend_error_message(monkeypatch):
    monkeypatch.setattr(
        "portal_mcp.backend_client.httpx.AsyncClient",
        lambda **kwargs: _FakeAsyncClient(response=_response(400, json_payload={"message": "invalid request"})),
    )

    client = BackendApiClient(_settings())

    with pytest.raises(BackendApiError, match="invalid request") as exc_info:
        await client.inspect(database="dw")

    assert exc_info.value.status_code == 400


@pytest.mark.anyio
async def test_backend_client_rejects_non_json_success(monkeypatch):
    monkeypatch.setattr(
        "portal_mcp.backend_client.httpx.AsyncClient",
        lambda **kwargs: _FakeAsyncClient(response=_response(200, text="plain-text")),
    )

    client = BackendApiClient(_settings())

    with pytest.raises(BackendApiError, match="不是合法 JSON"):
        await client.inspect(database="dw")


@pytest.mark.anyio
async def test_backend_client_maps_timeout(monkeypatch):
    monkeypatch.setattr(
        "portal_mcp.backend_client.httpx.AsyncClient",
        lambda **kwargs: _FakeAsyncClient(error=httpx.ReadTimeout("boom")),
    )

    client = BackendApiClient(_settings())

    with pytest.raises(BackendApiError, match="请求超时"):
        await client.inspect(database="dw")


@pytest.mark.anyio
async def test_backend_client_maps_request_error(monkeypatch):
    request = httpx.Request("GET", "http://backend:8080/api/v1/ai/metadata/inspect")
    monkeypatch.setattr(
        "portal_mcp.backend_client.httpx.AsyncClient",
        lambda **kwargs: _FakeAsyncClient(error=httpx.ConnectError("connection refused", request=request)),
    )

    client = BackendApiClient(_settings())

    with pytest.raises(BackendApiError, match="backend agent api 不可达"):
        await client.inspect(database="dw")


@pytest.mark.anyio
async def test_backend_client_forwards_agent_data_scope_header(monkeypatch):
    fake_client = _FakeAsyncClient(response=_response(200, json_payload={"ok": True}))
    monkeypatch.setattr(
        "portal_mcp.backend_client.httpx.AsyncClient",
        lambda **kwargs: fake_client,
    )
    reset = set_data_scope_header("encoded-scope")
    try:
        await BackendApiClient(_settings()).inspect(database="dw")
    finally:
        reset()

    assert fake_client.last_kwargs["headers"]["X-Agent-Data-Scope"] == "encoded-scope"
