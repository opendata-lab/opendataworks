from __future__ import annotations

from urllib.parse import urlparse, urlunparse


def normalize_api_format(raw: str | None) -> str:
    value = str(raw or "").strip().lower()
    if value in {"/v1/chat/completions", "openai", "openrouter", "anyrouter", "anthropic_compatible"}:
        return "/v1/chat/completions"
    return "/v1/messages"


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
    raw = str(api_format or "").strip().lower()
    if normalize_api_format(api_format) == "/v1/chat/completions":
        return {
            "ANTHROPIC_AUTH_TOKEN": str(auth_token or api_key).strip(),
            "ANTHROPIC_API_KEY": "",
            "ANTHROPIC_BASE_URL": str(base_url or "").strip(),
            "DISABLE_PROMPT_CACHING": "1" if raw == "anthropic_compatible" else "",
        }
    return {
        "ANTHROPIC_AUTH_TOKEN": "",
        "ANTHROPIC_API_KEY": str(api_key or "").strip(),
        "ANTHROPIC_BASE_URL": str(base_url or "").strip(),
        "DISABLE_PROMPT_CACHING": "",
    }
