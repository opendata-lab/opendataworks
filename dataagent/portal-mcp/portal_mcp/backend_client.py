from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx

from .config import Settings
from .scope_context import get_data_scope_header


class BackendApiError(RuntimeError):
    def __init__(self, message: str, *, status_code: int | None = None):
        super().__init__(message)
        self.status_code = status_code


@dataclass
class BackendApiClient:
    settings: Settings

    async def inspect(self, **params: Any) -> dict[str, Any]:
        return await self._request("GET", "/v1/ai/metadata/inspect", params=params)

    async def lineage(self, **params: Any) -> dict[str, Any]:
        return await self._request("GET", "/v1/ai/metadata/lineage", params=params)

    async def resolve_datasource(self, **params: Any) -> dict[str, Any]:
        return await self._request("GET", "/v1/ai/metadata/datasource/resolve", params=params)

    async def export_metadata(self, **params: Any) -> list[dict[str, Any]]:
        return await self._request("GET", "/v1/ai/metadata/export", params=params)

    async def get_table_ddl(self, **params: Any) -> dict[str, Any]:
        return await self._request("GET", "/v1/ai/metadata/ddl", params=params)

    async def query_readonly(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._request("POST", "/v1/ai/query/read", json=payload)

    async def _request(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        json: dict[str, Any] | None = None,
    ) -> Any:
        headers = {
            "Accept": "application/json",
            self.settings.backend_token_header_name: self.settings.backend_service_token,
        }
        data_scope = get_data_scope_header()
        if data_scope:
            headers["X-Agent-Data-Scope"] = data_scope
        timeout = httpx.Timeout(self.settings.backend_timeout_seconds)
        async with httpx.AsyncClient(timeout=timeout) as client:
            try:
                response = await client.request(
                    method,
                    f"{self.settings.backend_base_url}{path}",
                    headers=headers,
                    params=_strip_none(params),
                    json=_strip_none(json),
                )
            except httpx.TimeoutException as exc:
                raise BackendApiError("backend agent api 请求超时") from exc
            except httpx.RequestError as exc:
                raise BackendApiError(f"backend agent api 不可达: {exc}") from exc

        if response.is_error:
            raise BackendApiError(_extract_error_message(response), status_code=response.status_code)

        try:
            return response.json()
        except ValueError as exc:
            raise BackendApiError("backend agent api 返回的不是合法 JSON", status_code=response.status_code) from exc


def _extract_error_message(response: httpx.Response) -> str:
    try:
        payload = response.json()
    except ValueError:
        text = response.text.strip()
        return text or f"backend agent api 请求失败: HTTP {response.status_code}"

    if isinstance(payload, dict):
        message = str(payload.get("message") or payload.get("error") or "").strip()
        if message:
            return message
    return f"backend agent api 请求失败: HTTP {response.status_code}"


def _strip_none(payload: dict[str, Any] | None) -> dict[str, Any] | None:
    if payload is None:
        return None
    return {key: value for key, value in payload.items() if value is not None}
