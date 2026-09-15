"""认证路由：本地管理员密码登录 + OAuth2 授权码登录。

登录相关接口复用 ``/api/v1/nl2sql/auth`` 前缀；OAuth 回调遵循 FAB/Superset
约定，单独暴露为根路径 ``/oauth-authorized/{provider}``。
auth 关闭（env 未设置或显式 AUTH_ENABLED=False）时各端点安全降级：
``/config`` 返回 ``enabled=false``，其余登录端点返回 404。
"""
from __future__ import annotations

import logging
from urllib.parse import urlencode

import anyio
import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response
from pydantic import BaseModel

from core.auth import (
    OAUTH_STATE_COOKIE,
    OAUTH_STATE_COOKIE_MAX_AGE,
    AuthIdentity,
    generate_oauth_nonce,
    get_auth_settings,
    issue_oauth_state,
    issue_session_token,
    require_identity,
    resolve_oauth_role,
    sanitize_redirect_path,
    verify_local_admin,
    verify_oauth_state,
    verify_oauth_state_binding,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/nl2sql/auth", tags=["auth"])
oauth_callback_router = APIRouter(tags=["auth"])

_OAUTH_HTTP_TIMEOUT_SECONDS = 15.0
_MAX_IDENTITY_FIELD_LENGTH = 255


class _OAuthUserInfoError(RuntimeError):
    """外置 OAUTH_USER_INFO 钩子执行或返回契约失败。"""


def _terminal_oauth_redirect(error: str) -> RedirectResponse:
    """结束已验证的 OAuth 事务并清除一次性 nonce Cookie。"""
    response = RedirectResponse(url=f"/login?error={error}", status_code=302)
    response.delete_cookie(key=OAUTH_STATE_COOKIE, path="/")
    return response


def _valid_identity_text(value: object, *, max_length: int = _MAX_IDENTITY_FIELD_LENGTH) -> bool:
    return (
        isinstance(value, str)
        and bool(value)
        and value == value.strip()
        and len(value) <= max_length
        and not any(ord(char) < 32 or ord(char) == 127 for char in value)
    )


class LoginRequest(BaseModel):
    username: str
    password: str


def _require_enabled() -> None:
    if not get_auth_settings().enabled:
        raise HTTPException(status_code=404, detail="Authentication is not enabled")


def _identity_payload(identity: AuthIdentity) -> dict:
    return {
        "user_id": identity.user_id,
        "display_name": identity.display_name,
        "role": identity.role,
        "provider": identity.provider,
    }


def _set_session_cookie(response: Response, token: str) -> None:
    cfg = get_auth_settings()
    response.set_cookie(
        key=cfg.cookie_name,
        value=token,
        max_age=cfg.session_ttl_seconds,
        path="/",
        # HttpOnly 硬编码：前端无需读取会话令牌，不可配置放宽。
        httponly=True,
        secure=cfg.cookie_secure,
        samesite=cfg.cookie_samesite,
    )


def _clear_session_cookie(response: Response) -> None:
    cfg = get_auth_settings()
    response.delete_cookie(key=cfg.cookie_name, path="/")


@router.get("/config")
async def auth_config():
    cfg = get_auth_settings()
    if not cfg.enabled:
        return {
            "enabled": False,
            "provider_name": "",
            "provider_icon": "",
            "local_login_enabled": False,
            "oauth_login_enabled": False,
        }
    return {
        "enabled": True,
        "provider_name": cfg.oauth.provider_name if cfg.oauth_login_enabled else "",
        "provider_icon": cfg.oauth.icon if cfg.oauth_login_enabled else "",
        "local_login_enabled": cfg.local_login_enabled,
        "oauth_login_enabled": cfg.oauth_login_enabled,
    }


@router.post("/login")
def login(payload: LoginRequest):
    _require_enabled()
    cfg = get_auth_settings()
    if not cfg.local_login_enabled:
        raise HTTPException(status_code=400, detail="Local login is not configured")
    identity = verify_local_admin(payload.username, payload.password)
    if identity is None:
        raise HTTPException(status_code=401, detail="Invalid username or password")
    response = JSONResponse({"code": 200, "data": _identity_payload(identity)})
    _set_session_cookie(response, issue_session_token(identity))
    return response


@router.get("/me")
async def me(request: Request):
    _require_enabled()
    identity = require_identity(request)
    return {"code": 200, "data": _identity_payload(identity)}


@router.post("/logout")
async def logout():
    _require_enabled()
    response = JSONResponse({"code": 200, "data": {"ok": True}})
    _clear_session_cookie(response)
    return response


@router.get("/oauth/authorize")
async def oauth_authorize(request: Request):
    _require_enabled()
    cfg = get_auth_settings()
    if not cfg.oauth_login_enabled:
        raise HTTPException(status_code=400, detail="OAuth login is not configured")
    redirect_path = sanitize_redirect_path(request.query_params.get("redirect"), fallback="")
    # nonce 同时写进 state 与发起浏览器的 HttpOnly 临时 Cookie，callback 比对，
    # 防止攻击者用自己的 callback URL 给受害者写入攻击者会话（登录 CSRF）。
    nonce = generate_oauth_nonce()
    params = {
        "response_type": cfg.oauth.response_type,
        "client_id": cfg.oauth.client_id,
        "redirect_uri": cfg.oauth.redirect_uri,
        "scope": cfg.oauth.scopes,
        "state": issue_oauth_state(
            redirect_path,
            nonce=nonce,
            provider=cfg.oauth.provider_name,
        ),
    }
    response = RedirectResponse(url=f"{cfg.oauth.authorize_url}?{urlencode(params)}", status_code=302)
    # nonce Cookie 必须 SameSite=Lax（硬编码，不跟随 cfg.cookie_samesite）：
    # 回调是 IdP → 本站的顶级跨站 GET 导航，SameSite=Strict 的 Cookie 不会被
    # 浏览器带回，会让每次 OAuth 登录的绑定校验必失败。Lax 恰好覆盖顶级跨站 GET，
    # 是 OAuth state Cookie 的标准取值。会话 Cookie da_session 落地后只在同站发送，
    # 故仍沿用 cfg.cookie_samesite。
    response.set_cookie(
        key=OAUTH_STATE_COOKIE,
        value=nonce,
        max_age=OAUTH_STATE_COOKIE_MAX_AGE,
        path="/",
        httponly=True,
        secure=cfg.cookie_secure,
        samesite="lax",
    )
    return response


async def _exchange_code_for_userinfo(code: str) -> dict:
    cfg = get_auth_settings()
    async with httpx.AsyncClient(timeout=_OAUTH_HTTP_TIMEOUT_SECONDS) as client:
        token_response = await client.post(
            cfg.oauth.token_url,
            data={
                "grant_type": cfg.oauth.grant_type,
                "code": code,
                "client_id": cfg.oauth.client_id,
                "client_secret": cfg.oauth.client_secret,
                "redirect_uri": cfg.oauth.redirect_uri,
            },
            headers={"Accept": "application/json"},
        )
        token_response.raise_for_status()
        token_payload = token_response.json()
        if not isinstance(token_payload, dict):
            raise HTTPException(status_code=502, detail="OAuth token endpoint returned unexpected payload")

    handler = cfg.oauth_user_info
    if handler is None:
        raise _OAuthUserInfoError("OAUTH_USER_INFO is not configured")
    access_token = token_payload.get("access_token")
    raw_token_type = token_payload.get("token_type")
    token_type = "Bearer" if raw_token_type in (None, "") else raw_token_type
    if not _valid_identity_text(access_token, max_length=8192):
        raise HTTPException(status_code=502, detail="OAuth token endpoint returned no valid access_token")
    if not isinstance(token_type, str) or token_type.lower() != "bearer":
        raise HTTPException(status_code=502, detail="OAuth token endpoint returned unsupported token_type")

    def _call_user_info_handler() -> object:
        with httpx.Client(
            base_url=cfg.oauth.api_base_url,
            headers={
                "Authorization": f"Bearer {access_token}",
                "Accept": "application/json",
            },
            timeout=_OAUTH_HTTP_TIMEOUT_SECONDS,
        ) as remote:
            oauth_remotes = {cfg.oauth.provider_name: remote}
            return handler(cfg.oauth.provider_name, token_payload, oauth_remotes)

    try:
        userinfo = await anyio.to_thread.run_sync(_call_user_info_handler)
    except Exception as e:
        raise _OAuthUserInfoError("OAUTH_USER_INFO failed") from e
    if not isinstance(userinfo, dict):
        raise _OAuthUserInfoError("OAUTH_USER_INFO must return a dict")
    return userinfo


@oauth_callback_router.get("/oauth-authorized/{provider}")
async def oauth_callback(provider: str, request: Request):
    _require_enabled()
    cfg = get_auth_settings()
    if not cfg.oauth_login_enabled:
        raise HTTPException(status_code=400, detail="OAuth login is not configured")
    if provider != cfg.oauth.provider_name:
        raise HTTPException(status_code=404, detail="OAuth provider is not configured")

    state_payload = verify_oauth_state(request.query_params.get("state") or "")
    if state_payload is None:
        raise HTTPException(status_code=400, detail="Invalid or expired OAuth state")
    if str(state_payload.get("provider") or "") != provider:
        raise HTTPException(status_code=400, detail="OAuth state provider does not match callback")
    # state 必须绑定发起登录的浏览器（authorize 时种下的 nonce Cookie）。
    if not verify_oauth_state_binding(state_payload, request.cookies.get(OAUTH_STATE_COOKIE)):
        raise HTTPException(status_code=400, detail="OAuth state is not bound to this browser")
    if request.query_params.get("error"):
        return _terminal_oauth_redirect("oauth_denied")
    code = str(request.query_params.get("code") or "")
    if not code:
        return _terminal_oauth_redirect("oauth_exchange_failed")

    try:
        userinfo = await _exchange_code_for_userinfo(code)
    except _OAuthUserInfoError as e:
        cause = e.__cause__ or e
        logger.error(
            "OAUTH_USER_INFO failed provider=%s error_type=%s",
            provider,
            type(cause).__name__,
        )
        return _terminal_oauth_redirect("oauth_user_info_failed")
    except HTTPException as e:
        logger.error(
            "OAuth code exchange rejected provider=%s status_code=%s",
            provider,
            e.status_code,
        )
        return _terminal_oauth_redirect("oauth_exchange_failed")
    except Exception as e:
        logger.error(
            "OAuth code exchange failed provider=%s error_type=%s",
            provider,
            type(e).__name__,
        )
        return _terminal_oauth_redirect("oauth_exchange_failed")

    # OAUTH_USER_INFO 对齐 FAB SecurityManager.oauth_user_info 的扩展边界：
    # 外置钩子负责获取并归一化 claims，核心只消费稳定 sub 与显示/角色字段。
    oauth_user_id = userinfo.get("sub")
    if not _valid_identity_text(oauth_user_id):
        logger.error(
            "OAuth userinfo missing user id: keys=%s",
            sorted(str(key) for key in userinfo),
        )
        return _terminal_oauth_redirect("oauth_missing_user_id")

    display_name = oauth_user_id
    for claim in ("display_name", "preferred_username", "name", "email"):
        candidate = userinfo.get(claim)
        if candidate in (None, ""):
            continue
        if not _valid_identity_text(candidate):
            logger.error("OAUTH_USER_INFO returned invalid display claim=%s", claim)
            return _terminal_oauth_redirect("oauth_user_info_failed")
        display_name = candidate
        break

    role_value = userinfo.get("role")
    if role_value in (None, ""):
        mapped_role = None
    elif isinstance(role_value, str) and role_value in {"admin", "user"}:
        mapped_role = role_value
    else:
        logger.error("OAUTH_USER_INFO returned an invalid role")
        return _terminal_oauth_redirect("oauth_user_info_failed")

    provider = cfg.oauth.provider_name
    if len(provider) + 1 + len(oauth_user_id) > _MAX_IDENTITY_FIELD_LENGTH:
        logger.error("OAuth namespaced user id exceeds storage limit provider=%s", provider)
        return _terminal_oauth_redirect("oauth_missing_user_id")
    identity = AuthIdentity(
        user_id=f"{provider}:{oauth_user_id}",
        display_name=display_name,
        # 钩子显式返回 role 时优先生效；否则按 ADMIN_USERS（provider:sub）提名。
        role=mapped_role or resolve_oauth_role(provider, oauth_user_id),
        provider=provider,
    )

    redirect_path = sanitize_redirect_path(
        state_payload.get("redirect"),
        fallback="/",
    )
    response = RedirectResponse(url=redirect_path, status_code=302)
    _set_session_cookie(response, issue_session_token(identity))
    # nonce 为一次性：登录完成即清除。
    response.delete_cookie(key=OAUTH_STATE_COOKIE, path="/")
    return response
