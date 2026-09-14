from __future__ import annotations

from typing import Any
from urllib.parse import urlparse, urlunparse

import httpx


ANTHROPIC_MESSAGES_FORMAT = "/v1/messages"
OPENAI_COMPLETIONS_FORMAT = "/v1/chat/completions"
SUPPORTED_API_FORMATS = frozenset({ANTHROPIC_MESSAGES_FORMAT, OPENAI_COMPLETIONS_FORMAT})


def normalize_api_format(raw: str | None) -> str:
    value = str(raw or ANTHROPIC_MESSAGES_FORMAT).strip().lower()
    if value not in SUPPORTED_API_FORMATS:
        supported = ", ".join(sorted(SUPPORTED_API_FORMATS))
        raise ValueError(f"api_format must be one of: {supported}")
    return value


def build_provider_api_url(base_url: str, api_format: str) -> str:
    base = str(base_url or "").strip().rstrip("/")
    if not base:
        raise ValueError("base_url is required")
    parsed = urlparse(base)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("base_url must be an absolute http(s) URL")

    fmt = normalize_api_format(api_format)
    if base.endswith(fmt):
        return base
    if base.endswith("/v1"):
        suffix = "/messages" if fmt == ANTHROPIC_MESSAGES_FORMAT else "/chat/completions"
        return base + suffix
    return base + fmt


def provider_client_base_url(base_url: str, api_format: str) -> str:
    """Return the base URL expected by the selected SDK client.

    Anthropic clients append ``/v1/messages`` themselves, while OpenAI clients
    append ``/chat/completions`` to a base that normally ends in ``/v1``.
    Deriving both from api_format keeps provider ids out of protocol routing.
    """
    endpoint = build_provider_api_url(base_url, api_format)
    fmt = normalize_api_format(api_format)
    suffix = ANTHROPIC_MESSAGES_FORMAT if fmt == ANTHROPIC_MESSAGES_FORMAT else "/chat/completions"
    return endpoint[: -len(suffix)].rstrip("/")


def safe_base_url_for_log(raw_url: str | None) -> str:
    text = str(raw_url or "").strip()
    if not text:
        return ""
    try:
        parsed = urlparse(text)
        if parsed.scheme and parsed.netloc:
            host = parsed.hostname or ""
            if ":" in host and not host.startswith("["):
                host = f"[{host}]"
            netloc = host
            if parsed.port is not None:
                netloc = f"{netloc}:{parsed.port}"
            return urlunparse((parsed.scheme, netloc, parsed.path or "", "", "", ""))
    except Exception:
        pass
    return text.split("?", 1)[0].split("#", 1)[0][:200]


def build_provider_env(api_format: str, *, api_key: str, auth_token: str, base_url: str) -> dict[str, str]:
    fmt = normalize_api_format(api_format)
    token = str(api_key or auth_token).strip()
    client_base_url = provider_client_base_url(base_url, fmt)
    if fmt == OPENAI_COMPLETIONS_FORMAT:
        return {
            "ANTHROPIC_AUTH_TOKEN": "",
            "ANTHROPIC_API_KEY": "",
            "ANTHROPIC_BASE_URL": "",
            "OPENAI_API_KEY": token,
            "OPENAI_BASE_URL": client_base_url,
            "DISABLE_PROMPT_CACHING": "",
        }
    return {
        "ANTHROPIC_AUTH_TOKEN": "",
        "ANTHROPIC_API_KEY": token,
        "ANTHROPIC_BASE_URL": client_base_url,
        "OPENAI_API_KEY": "",
        "OPENAI_BASE_URL": "",
        "DISABLE_PROMPT_CACHING": "",
    }


def _response_error(response: httpx.Response) -> str:
    try:
        payload = response.json()
    except ValueError:
        payload = None
    if isinstance(payload, dict):
        detail = payload.get("error") or payload.get("detail") or payload.get("message")
        if isinstance(detail, dict):
            detail = detail.get("message") or detail.get("type")
        if detail:
            return str(detail)[:500]
    return str(response.text or "")[:500]


def _response_text(payload: Any, api_format: str) -> str:
    if not isinstance(payload, dict):
        return ""
    if normalize_api_format(api_format) == OPENAI_COMPLETIONS_FORMAT:
        choices = payload.get("choices")
        if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
            return ""
        message = choices[0].get("message")
        return str(message.get("content") or "").strip() if isinstance(message, dict) else ""

    content = payload.get("content")
    if not isinstance(content, list):
        return ""
    parts = [
        str(item.get("text") or "")
        for item in content
        if isinstance(item, dict) and item.get("type") == "text"
    ]
    return "".join(parts).strip()


async def request_model_text(
    *,
    api_format: str,
    base_url: str,
    api_key: str,
    auth_token: str,
    model: str,
    prompt: str,
    timeout_seconds: int,
    max_output_tokens: int = 512,
) -> str:
    fmt = normalize_api_format(api_format)
    url = build_provider_api_url(base_url, fmt)
    token = str(api_key or auth_token).strip()
    if not token:
        raise ValueError("API credential is required")

    headers = {"content-type": "application/json"}
    payload: dict[str, Any] = {
        "model": str(model or "").strip(),
        "messages": [{"role": "user", "content": str(prompt or "")}],
        "max_tokens": max(1, int(max_output_tokens)),
    }
    if fmt == ANTHROPIC_MESSAGES_FORMAT:
        headers.update({"x-api-key": token, "anthropic-version": "2023-06-01"})
    else:
        headers["authorization"] = f"Bearer {token}"

    async with httpx.AsyncClient(timeout=max(1, int(timeout_seconds))) as client:
        response = await client.post(url, headers=headers, json=payload)
    if response.status_code >= 400:
        detail = _response_error(response)
        raise RuntimeError(f"model API returned HTTP {response.status_code}: {detail}")
    text = _response_text(response.json(), fmt)
    if not text:
        raise RuntimeError("model API returned no text content")
    return text
