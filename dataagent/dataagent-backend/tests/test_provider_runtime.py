from __future__ import annotations

import httpx
import pytest

from core import provider_runtime


def test_api_format_is_strict_and_builds_client_base_urls() -> None:
    assert provider_runtime.provider_client_base_url(
        "https://api.anthropic.com", "/v1/messages"
    ) == "https://api.anthropic.com"
    assert provider_runtime.provider_client_base_url(
        "https://gateway.example/api", "/v1/chat/completions"
    ) == "https://gateway.example/api/v1"
    assert provider_runtime.provider_client_base_url(
        "https://gateway.example/api/v1", "/v1/chat/completions"
    ) == "https://gateway.example/api/v1"
    with pytest.raises(ValueError, match="api_format"):
        provider_runtime.normalize_api_format("openrouter")


def test_provider_env_uses_api_format_not_provider_identity() -> None:
    anthropic = provider_runtime.build_provider_env(
        "/v1/messages",
        api_key="anthropic-secret",
        auth_token="",
        base_url="https://anthropic.example",
    )
    assert anthropic["ANTHROPIC_API_KEY"] == "anthropic-secret"
    assert anthropic["ANTHROPIC_BASE_URL"] == "https://anthropic.example"
    assert anthropic["OPENAI_API_KEY"] == ""

    openai = provider_runtime.build_provider_env(
        "/v1/chat/completions",
        api_key="",
        auth_token="openai-secret",
        base_url="https://openai.example/api",
    )
    assert openai["OPENAI_API_KEY"] == "openai-secret"
    assert openai["OPENAI_BASE_URL"] == "https://openai.example/api/v1"
    assert openai["ANTHROPIC_API_KEY"] == ""


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("api_format", "base_url", "expected_path", "response_json", "expected_header"),
    [
        (
            "/v1/messages",
            "https://models.example",
            "/v1/messages",
            {"content": [{"type": "text", "text": "anthropic-ok"}]},
            ("x-api-key", "secret"),
        ),
        (
            "/v1/chat/completions",
            "https://models.example/v1",
            "/v1/chat/completions",
            {"choices": [{"message": {"content": "openai-ok"}}]},
            ("authorization", "Bearer secret"),
        ),
    ],
)
async def test_request_model_text_uses_selected_wire_protocol(
    monkeypatch,
    api_format,
    base_url,
    expected_path,
    response_json,
    expected_header,
) -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json=response_json)

    transport = httpx.MockTransport(handler)
    original_client = httpx.AsyncClient
    monkeypatch.setattr(
        provider_runtime.httpx,
        "AsyncClient",
        lambda **kwargs: original_client(transport=transport, **kwargs),
    )

    text = await provider_runtime.request_model_text(
        api_format=api_format,
        base_url=base_url,
        api_key="secret",
        auth_token="",
        model="model-x",
        prompt="ping",
        timeout_seconds=5,
    )

    assert text.endswith("ok")
    assert captured[0].url.path == expected_path
    assert captured[0].headers[expected_header[0]] == expected_header[1]
