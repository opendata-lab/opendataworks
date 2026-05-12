from __future__ import annotations

from urllib.parse import urlparse, urlunparse


def normalize_provider_id(raw: str | None, base_url: str | None = None) -> str:
    value = str(raw or "").strip().lower()
    if value in {"anthropic", "openrouter", "anyrouter", "anthropic_compatible"}:
        return value
    base = str(base_url or "").lower()
    if "openrouter.ai" in base:
        return "openrouter"
    if "anyrouter" in base or ".fcapp.run" in base:
        return "anyrouter"
    if base:
        return "anthropic_compatible"
    return "anthropic"


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


def build_provider_env(provider_id: str, *, api_key: str, auth_token: str, base_url: str) -> dict[str, str]:
    if provider_id == "openrouter":
        return {
            "ANTHROPIC_AUTH_TOKEN": str(auth_token or api_key).strip(),
            "ANTHROPIC_API_KEY": "",
            "ANTHROPIC_BASE_URL": str(base_url or "https://openrouter.ai/api").strip(),
            "DISABLE_PROMPT_CACHING": "",
        }

    if provider_id == "anyrouter":
        return {
            "ANTHROPIC_AUTH_TOKEN": str(auth_token or api_key).strip(),
            "ANTHROPIC_API_KEY": "",
            "ANTHROPIC_BASE_URL": str(base_url or "https://a-ocnfniawgw.cn-shanghai.fcapp.run").strip(),
            "DISABLE_PROMPT_CACHING": "",
        }

    if provider_id == "anthropic_compatible":
        return {
            "ANTHROPIC_AUTH_TOKEN": str(auth_token or api_key).strip(),
            "ANTHROPIC_API_KEY": "",
            "ANTHROPIC_BASE_URL": str(base_url or "").strip(),
            "DISABLE_PROMPT_CACHING": "1",
        }

    return {
        "ANTHROPIC_AUTH_TOKEN": "",
        "ANTHROPIC_API_KEY": str(api_key or "").strip(),
        "ANTHROPIC_BASE_URL": str(base_url or "").strip(),
        "DISABLE_PROMPT_CACHING": "",
    }
