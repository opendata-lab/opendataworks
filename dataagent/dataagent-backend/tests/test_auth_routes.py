from __future__ import annotations

import sys
import types
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest
from fastapi.testclient import TestClient

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

import core.auth as auth
import api.auth_routes as auth_routes
from main import app

VALID_SECRET = "unit-test-secret-key-0123456789abcdef-0123456789"


@pytest.fixture(autouse=True)
def _reset_auth(monkeypatch):
    monkeypatch.delenv(auth.AUTH_CONFIG_ENV, raising=False)
    auth.reset_auth_for_tests()
    yield
    auth.reset_auth_for_tests()


def enable_auth(
    monkeypatch,
    tmp_path: Path,
    *,
    oauth: bool = False,
    admin_users: str = "[]",
    cookie_secure: bool = False,
    cookie_samesite: str = "lax",
    response_type: str = "code",
    grant_type: str = "authorization_code",
) -> None:
    import bcrypt

    password_hash = bcrypt.hashpw(b"admin-pass", bcrypt.gensalt(rounds=4)).decode()
    oauth_block = ""
    if oauth:
        oauth_provider = {
            "name": "SSO",
            "icon": "fa-github",
            "remote_app": {
                "client_id": "cid",
                "client_secret": "secret",
                "authorize_url": "https://sso.example.com/authorize",
                "access_token_url": "https://sso.example.com/token",
                "api_base_url": "https://sso.example.com/api/",
                "client_kwargs": {"scope": "openid profile"},
                "redirect_uri": "https://app.example.com/oauth-authorized/SSO",
                "response_type": response_type,
                "grant_type": grant_type,
            },
        }
        oauth_block = (
            f"OAUTH_PROVIDERS = {[oauth_provider]!r}\n"
            "def _oauth_user_info(provider, token_response, oauth_remotes):\n"
            "    return token_response.get('userinfo') or {'sub': 'configured-sub'}\n"
            "OAUTH_USER_INFO = _oauth_user_info\n"
        )
    config_file = tmp_path / "auth_config.py"
    config_file.write_text(
        f"""
AUTH_ENABLED = True
SECRET_KEY = {VALID_SECRET!r}
COOKIE_SECURE = {cookie_secure!r}
COOKIE_SAMESITE = {cookie_samesite!r}
LOCAL_ADMINS = [{{"username": "admin", "password_bcrypt": {password_hash!r}}}]
ADMIN_USERS = {admin_users}
{oauth_block}
""",
        encoding="utf-8",
    )
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, str(config_file))
    auth.init_auth()


def test_auth_config_reports_disabled_when_env_unset():
    client = TestClient(app)
    response = client.get("/api/v1/nl2sql/auth/config")
    assert response.status_code == 200
    assert response.json() == {
        "enabled": False,
        "provider_name": "",
        "provider_icon": "",
        "local_login_enabled": False,
        "oauth_login_enabled": False,
    }


def test_login_endpoints_are_404_when_disabled():
    client = TestClient(app)
    assert client.post("/api/v1/nl2sql/auth/login", json={"username": "a", "password": "b"}).status_code == 404
    assert client.get("/api/v1/nl2sql/auth/me").status_code == 404
    assert client.post("/api/v1/nl2sql/auth/logout").status_code == 404
    assert client.get("/api/v1/nl2sql/auth/oauth/authorize").status_code == 404
    assert client.get("/oauth-authorized/SSO").status_code == 404


def test_auth_config_exposes_oauth_provider_name_and_icon(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    response = TestClient(app).get("/api/v1/nl2sql/auth/config")
    assert response.status_code == 200
    assert response.json()["provider_name"] == "SSO"
    assert response.json()["provider_icon"] == "fa-github"
    assert response.json()["oauth_login_enabled"] is True


def test_local_login_sets_httponly_cookie_and_me_roundtrip(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path)
    client = TestClient(app)

    response = client.post("/api/v1/nl2sql/auth/login", json={"username": "admin", "password": "admin-pass"})
    assert response.status_code == 200
    assert response.json()["data"]["role"] == "admin"

    set_cookie = response.headers.get("set-cookie", "")
    assert "da_session=" in set_cookie
    assert "HttpOnly" in set_cookie
    assert "SameSite=lax" in set_cookie
    assert "Secure" not in set_cookie

    me = client.get("/api/v1/nl2sql/auth/me")
    assert me.status_code == 200
    assert me.json()["data"]["user_id"] == "local:admin"

    logout = client.post("/api/v1/nl2sql/auth/logout")
    assert logout.status_code == 200
    assert client.get("/api/v1/nl2sql/auth/me").status_code == 401


def test_cookie_secure_flag_follows_config(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, cookie_secure=True)
    client = TestClient(app)
    response = client.post("/api/v1/nl2sql/auth/login", json={"username": "admin", "password": "admin-pass"})
    assert "Secure" in response.headers.get("set-cookie", "")


def test_local_login_rejects_bad_password(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path)
    client = TestClient(app)
    response = client.post("/api/v1/nl2sql/auth/login", json={"username": "admin", "password": "nope"})
    assert response.status_code == 401


def test_oauth_authorize_redirects_with_state(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = client.get(
        "/api/v1/nl2sql/auth/oauth/authorize?redirect=/chat",
        follow_redirects=False,
    )
    assert response.status_code == 302
    location = urlparse(response.headers["location"])
    assert location.netloc == "sso.example.com"
    query = parse_qs(location.query)
    assert query["client_id"] == ["cid"]
    assert query["response_type"] == ["code"]
    assert query["scope"] == ["openid profile"]
    assert query["redirect_uri"] == ["https://app.example.com/oauth-authorized/SSO"]
    state = query["state"][0]
    payload = auth.verify_oauth_state(state)
    assert payload is not None
    assert payload["redirect"] == "/chat"
    assert payload["provider"] == "SSO"

    # authorize 必须把 state nonce 同步种进发起浏览器的 HttpOnly Cookie（CSRF 绑定）。
    set_cookie = response.headers.get("set-cookie", "")
    assert f"{auth.OAUTH_STATE_COOKIE}=" in set_cookie
    assert "HttpOnly" in set_cookie
    assert client.cookies.get(auth.OAUTH_STATE_COOKIE) == payload["nonce"]


def test_oauth_nonce_cookie_is_lax_even_when_session_samesite_strict(monkeypatch, tmp_path):
    """P2 回归：nonce Cookie 必须硬编码 SameSite=Lax。若跟随 cfg（strict），
    浏览器不会在 IdP → 本站的跨站回调导航上带回它，callback 绑定校验必失败，
    OAuth 登录整体不可用。"""
    enable_auth(monkeypatch, tmp_path, oauth=True, cookie_samesite="strict")
    client = TestClient(app)

    response = client.get("/api/v1/nl2sql/auth/oauth/authorize", follow_redirects=False)
    assert response.status_code == 302

    nonce_cookie = next(
        c for c in response.headers.get_list("set-cookie") if c.startswith(f"{auth.OAUTH_STATE_COOKIE}=")
    ).lower()
    assert "samesite=lax" in nonce_cookie
    assert "samesite=strict" not in nonce_cookie


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


class _FakeAsyncClient:
    last_post = None

    def __init__(self, *, token_payload=None):
        self._token_payload = {"access_token": "at-1"} if token_payload is None else token_payload

    def __call__(self, *args, **kwargs):
        return self

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def post(self, url, **kwargs):
        type(self).last_post = (url, kwargs)
        return _FakeResponse(self._token_payload)


class _FakeRemoteClient:
    last_init = None
    last_get = None

    def __init__(self, *, base_url, headers, timeout):
        type(self).last_init = {
            "base_url": base_url,
            "headers": headers,
            "timeout": timeout,
        }

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def get(self, url, **kwargs):
        type(self).last_get = (url, kwargs)
        return _FakeResponse({"sub": "remote-sub", "preferred_username": "remote-user"})


def _oauth_callback(
    client,
    monkeypatch,
    *,
    userinfo,
    redirect="",
    bind_nonce=True,
    cookie_nonce=None,
    provider="SSO",
    state_provider="SSO",
    use_config_handler=False,
    handler_calls=None,
    token_payload=None,
):
    token_payload = {"access_token": "at-1"} if token_payload is None else token_payload
    if use_config_handler:
        token_payload = dict(token_payload)
        token_payload["userinfo"] = userinfo
    monkeypatch.setattr(
        auth_routes.httpx,
        "AsyncClient",
        _FakeAsyncClient(token_payload=token_payload),
    )
    if not use_config_handler:
        def _oauth_user_info(provider_name, response, oauth_remotes):
            if handler_calls is not None:
                remote = oauth_remotes[provider_name]
                handler_calls.append(
                    (
                        provider_name,
                        response.copy(),
                        str(remote.base_url),
                        remote.headers.get("Authorization"),
                    )
                )
            return userinfo

        auth.get_auth_settings().oauth_user_info = _oauth_user_info
    nonce = auth.generate_oauth_nonce()
    state = auth.issue_oauth_state(redirect, nonce=nonce, provider=state_provider)
    # 正常流程：authorize 会把 nonce 种进发起浏览器的 HttpOnly Cookie。
    if bind_nonce:
        client.cookies.set(auth.OAUTH_STATE_COOKIE, cookie_nonce if cookie_nonce is not None else nonce)
    else:
        client.cookies.delete(auth.OAUTH_STATE_COOKIE) if auth.OAUTH_STATE_COOKIE in client.cookies else None
    return client.get(
        f"/oauth-authorized/{provider}?code=abc&state={state}",
        follow_redirects=False,
    )


def test_oauth_callback_issues_session_and_redirects(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(
        client, monkeypatch,
        userinfo={"sub": "42", "preferred_username": "alice"},
        redirect="/chat",
    )
    assert response.status_code == 302
    assert response.headers["location"] == "/chat"
    assert "HttpOnly" in response.headers.get("set-cookie", "")

    me = client.get("/api/v1/nl2sql/auth/me")
    assert me.status_code == 200
    data = me.json()["data"]
    assert data["user_id"] == "SSO:42"
    assert data["display_name"] == "alice"
    assert data["role"] == "user"


@pytest.mark.parametrize(
    ("userinfo", "expected_display_name"),
    [
        ({"sub": "42", "display_name": "Alice Z", "preferred_username": "alice"}, "Alice Z"),
        ({"sub": "42", "preferred_username": "alice", "name": "Alice"}, "alice"),
        ({"sub": "42", "name": "Alice", "email": "alice@example.com"}, "Alice"),
        ({"sub": "42", "email": "alice@example.com"}, "alice@example.com"),
        ({"sub": "42"}, "42"),
    ],
)
def test_oauth_callback_uses_oidc_display_name_fallbacks(
    monkeypatch,
    tmp_path,
    userinfo,
    expected_display_name,
):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(client, monkeypatch, userinfo=userinfo)
    assert response.status_code == 302
    assert client.get("/api/v1/nl2sql/auth/me").json()["data"]["display_name"] == expected_display_name


def test_oauth_callback_requires_normalized_sub(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(
        client,
        monkeypatch,
        userinfo={"preferred_username": "alice", "id": "legacy-id"},
    )
    assert response.status_code == 302
    assert response.headers["location"] == "/login?error=oauth_missing_user_id"
    assert client.get("/api/v1/nl2sql/auth/me").status_code == 401


@pytest.mark.parametrize(
    "sub",
    [123, {"id": "42"}, ["42"], " 42", "42\n", "x" * 255],
)
def test_oauth_callback_rejects_invalid_or_oversized_sub(monkeypatch, tmp_path, sub):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(client, monkeypatch, userinfo={"sub": sub})
    assert response.status_code == 302
    assert response.headers["location"] == "/login?error=oauth_missing_user_id"
    assert client.get("/api/v1/nl2sql/auth/me").status_code == 401


@pytest.mark.parametrize(
    "userinfo",
    [
        {"sub": "42", "preferred_username": 123},
        {"sub": "42", "preferred_username": "alice\n"},
        {"sub": "42", "role": "ADMIN"},
        {"sub": "42", "role": ["admin"]},
    ],
)
def test_oauth_callback_rejects_invalid_display_or_role_claims(monkeypatch, tmp_path, userinfo):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(client, monkeypatch, userinfo=userinfo)
    assert response.status_code == 302
    assert response.headers["location"] == "/login?error=oauth_user_info_failed"
    assert client.get("/api/v1/nl2sql/auth/me").status_code == 401


def test_oauth_callback_promotes_admin_by_provider_sub_not_display_name(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True, admin_users='["SSO:1024"]')
    client = TestClient(app)

    promoted = _oauth_callback(client, monkeypatch, userinfo={"sub": "1024", "preferred_username": "bob"})
    assert promoted.status_code == 302
    assert client.get("/api/v1/nl2sql/auth/me").json()["data"]["role"] == "admin"

    client.cookies.clear()
    # display_name 与提名值相同也不提权（提名只认 provider:sub）。
    not_promoted = _oauth_callback(client, monkeypatch, userinfo={"sub": "7", "preferred_username": "SSO:1024"})
    assert not_promoted.status_code == 302
    assert client.get("/api/v1/nl2sql/auth/me").json()["data"]["role"] == "user"


def test_oauth_callback_rejects_bad_state(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)
    response = client.get(
        "/oauth-authorized/SSO?code=abc&state=tampered.state",
        follow_redirects=False,
    )
    assert response.status_code == 400


def test_oauth_provider_error_requires_valid_state_and_uses_fixed_error(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    invalid = client.get(
        "/oauth-authorized/SSO?error=sensitive-provider-message&state=tampered.state",
        follow_redirects=False,
    )
    assert invalid.status_code == 400

    nonce = auth.generate_oauth_nonce()
    state = auth.issue_oauth_state("/", nonce=nonce, provider="SSO")
    client.cookies.set(auth.OAUTH_STATE_COOKIE, nonce)
    denied = client.get(
        f"/oauth-authorized/SSO?error=sensitive-provider-message&state={state}",
        follow_redirects=False,
    )
    assert denied.status_code == 302
    assert denied.headers["location"] == "/login?error=oauth_denied"
    assert "sensitive-provider-message" not in denied.headers["location"]
    set_cookies = denied.headers.get_list("set-cookie")
    assert any(f'{auth.OAUTH_STATE_COOKIE}=""' in cookie for cookie in set_cookies)


def test_oauth_callback_rejects_unknown_provider(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(client, monkeypatch, userinfo={"sub": "42"}, provider="unknown")
    assert response.status_code == 404


def test_legacy_oauth_callback_route_is_not_exposed(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    response = TestClient(app).get(
        "/api/v1/nl2sql/auth/oauth/callback?code=abc&state=unused",
        follow_redirects=False,
    )
    assert response.status_code == 404


def test_oauth_callback_rejects_state_for_another_provider(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(
        client,
        monkeypatch,
        userinfo={"sub": "42"},
        state_provider="Other",
    )
    assert response.status_code == 400


def test_oauth_transport_reads_response_and_grant_types_from_settings(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    # 启动期只允许授权码模式。这里在已校验的设置对象上放入辨识值，专门证明
    # authorize/token 传输读取配置对象，而不是继续在路由里写死常量。
    settings = auth.get_auth_settings()
    settings.oauth.response_type = "code-from-settings"
    settings.oauth.grant_type = "grant-from-settings"
    client = TestClient(app)

    authorize = client.get("/api/v1/nl2sql/auth/oauth/authorize", follow_redirects=False)
    query = parse_qs(urlparse(authorize.headers["location"]).query)
    assert query["response_type"] == ["code-from-settings"]

    handler_calls = []
    callback = _oauth_callback(
        client,
        monkeypatch,
        userinfo={"sub": "42"},
        handler_calls=handler_calls,
    )
    assert callback.status_code == 302
    token_url, token_request = _FakeAsyncClient.last_post
    assert token_url == "https://sso.example.com/token"
    assert token_request["data"]["grant_type"] == "grant-from-settings"
    assert token_request["data"]["redirect_uri"] == "https://app.example.com/oauth-authorized/SSO"
    assert handler_calls == [
        (
            "SSO",
            {"access_token": "at-1"},
            "https://sso.example.com/api/",
            "Bearer at-1",
        )
    ]


def test_oauth_callback_falls_back_to_safe_redirect(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)
    # state 中的 redirect 不合法（协议相对 URL）→ 回落根路径，
    # 由前端 router 决定当前默认页，后端不绑定具体页面 URL。
    response = _oauth_callback(client, monkeypatch, userinfo={"sub": "42"}, redirect="//evil.com")
    assert response.status_code == 302
    assert response.headers["location"] == "/"


# ---------------------------------------------------------------------------
# OAUTH_USER_INFO：对应 Superset custom SecurityManager.oauth_user_info
# ---------------------------------------------------------------------------

def enable_auth_with_user_info(monkeypatch, tmp_path, *, handler_body: str) -> None:
    import bcrypt

    password_hash = bcrypt.hashpw(b"admin-pass", bcrypt.gensalt(rounds=4)).decode()
    config_file = tmp_path / "auth_config.py"
    config_file.write_text(
        f"""
AUTH_ENABLED = True
SECRET_KEY = {VALID_SECRET!r}
LOCAL_ADMINS = [{{"username": "admin", "password_bcrypt": {password_hash!r}}}]
ADMIN_USERS = ["SSO:1024"]
OAUTH_PROVIDERS = [{{
    "name": "SSO",
    "remote_app": {{
        "client_id": "cid",
        "client_secret": "secret",
        "authorize_url": "https://sso.example.com/authorize",
        "access_token_url": "https://sso.example.com/token",
        "api_base_url": "https://sso.example.com/api/",
        "redirect_uri": "https://app.example.com/oauth-authorized/SSO",
    }},
}}]
{handler_body}
""",
        encoding="utf-8",
    )
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, str(config_file))
    auth.init_auth()


NESTED_USER_INFO = """
def _oauth_user_info(provider, token_response, oauth_remotes):
    userinfo = token_response.get("userinfo") or {}
    payload = userinfo.get("data") or {}
    mapped = {"sub": payload.get("uid"), "preferred_username": payload.get("nickname")}
    if "dataagent-admin" in (payload.get("roles") or []):
        mapped["role"] = "admin"
    return mapped
OAUTH_USER_INFO = _oauth_user_info
"""

REMOTE_USER_INFO = """
def _oauth_user_info(provider, token_response, oauth_remotes):
    response = oauth_remotes[provider].get("userinfo")
    response.raise_for_status()
    return response.json()
OAUTH_USER_INFO = _oauth_user_info
"""


def test_oauth_user_info_uses_token_bound_remote_get(monkeypatch, tmp_path):
    enable_auth_with_user_info(monkeypatch, tmp_path, handler_body=REMOTE_USER_INFO)
    monkeypatch.setattr(auth_routes.httpx, "Client", _FakeRemoteClient)
    client = TestClient(app)

    response = _oauth_callback(
        client,
        monkeypatch,
        userinfo={},
        use_config_handler=True,
    )
    assert response.status_code == 302
    assert _FakeRemoteClient.last_init == {
        "base_url": "https://sso.example.com/api/",
        "headers": {
            "Authorization": "Bearer at-1",
            "Accept": "application/json",
        },
        "timeout": 15.0,
    }
    assert _FakeRemoteClient.last_get == ("userinfo", {})
    me = client.get("/api/v1/nl2sql/auth/me").json()["data"]
    assert me["user_id"] == "SSO:remote-sub"
    assert me["display_name"] == "remote-user"


def test_sync_oauth_user_info_normalizes_nonstandard_payload(monkeypatch, tmp_path):
    enable_auth_with_user_info(monkeypatch, tmp_path, handler_body=NESTED_USER_INFO)
    client = TestClient(app)

    response = _oauth_callback(
        client, monkeypatch,
        userinfo={"code": 0, "data": {"uid": "7", "nickname": "小王", "roles": []}},
        use_config_handler=True,
    )
    assert response.status_code == 302
    data = client.get("/api/v1/nl2sql/auth/me").json()["data"]
    assert data["user_id"] == "SSO:7"
    assert data["display_name"] == "小王"
    assert data["role"] == "user"


def test_oauth_user_info_role_wins_over_admin_users(monkeypatch, tmp_path):
    enable_auth_with_user_info(monkeypatch, tmp_path, handler_body=NESTED_USER_INFO)
    client = TestClient(app)

    response = _oauth_callback(
        client, monkeypatch,
        userinfo={"data": {"uid": "7", "nickname": "ops", "roles": ["dataagent-admin"]}},
        use_config_handler=True,
    )
    assert response.status_code == 302
    assert client.get("/api/v1/nl2sql/auth/me").json()["data"]["role"] == "admin"


def test_oauth_user_info_without_role_falls_back_to_admin_users(monkeypatch, tmp_path):
    enable_auth_with_user_info(monkeypatch, tmp_path, handler_body=NESTED_USER_INFO)
    client = TestClient(app)

    response = _oauth_callback(
        client, monkeypatch,
        userinfo={"data": {"uid": "1024", "nickname": "bob", "roles": []}},
        use_config_handler=True,
    )
    assert response.status_code == 302
    # 钩子未返回 role → 回落 ADMIN_USERS（SSO:1024）提名。
    assert client.get("/api/v1/nl2sql/auth/me").json()["data"]["role"] == "admin"


def test_oauth_user_info_failure_is_redacted_and_does_not_stop_service(
    monkeypatch,
    tmp_path,
    caplog,
):
    enable_auth_with_user_info(
        monkeypatch, tmp_path,
        handler_body=(
            "def _oauth_user_info(provider, token_response, oauth_remotes):\n"
            "    raise RuntimeError('refresh_token=super-secret')\n"
            "OAUTH_USER_INFO = _oauth_user_info\n"
        ),
    )
    client = TestClient(app)

    response = _oauth_callback(
        client,
        monkeypatch,
        userinfo={"sub": "42"},
        use_config_handler=True,
    )
    assert response.status_code == 302
    assert response.headers["location"] == "/login?error=oauth_user_info_failed"
    assert "super-secret" not in caplog.text
    assert "error_type=RuntimeError" in caplog.text
    # 服务仍健康。
    assert client.get("/api/v1/nl2sql/auth/config").status_code == 200


def test_oauth_user_info_non_dict_fails_the_login(monkeypatch, tmp_path):
    enable_auth_with_user_info(
        monkeypatch,
        tmp_path,
        handler_body=(
            "def _oauth_user_info(provider, token_response, oauth_remotes):\n"
            "    return []\n"
            "OAUTH_USER_INFO = _oauth_user_info\n"
        ),
    )
    client = TestClient(app)

    response = _oauth_callback(
        client,
        monkeypatch,
        userinfo={"sub": "42"},
        use_config_handler=True,
    )
    assert response.status_code == 302
    assert response.headers["location"] == "/login?error=oauth_user_info_failed"


@pytest.mark.parametrize(
    "token_payload",
    [
        {},
        {"access_token": 123},
        {"access_token": "at-1", "token_type": "mac"},
        {"access_token": "at-1", "token_type": 0},
    ],
)
def test_invalid_token_payload_is_exchange_failure(monkeypatch, tmp_path, token_payload):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)
    handler_calls = []

    response = _oauth_callback(
        client,
        monkeypatch,
        userinfo={"sub": "42"},
        handler_calls=handler_calls,
        token_payload=token_payload,
    )

    assert response.status_code == 302
    assert response.headers["location"] == "/login?error=oauth_exchange_failed"
    assert handler_calls == []


# ---------------------------------------------------------------------------
# OAuth 登录 CSRF：state 必须绑定发起登录的浏览器（P1 回归）
# ---------------------------------------------------------------------------

def test_oauth_callback_rejects_state_without_browser_nonce(monkeypatch, tmp_path):
    """攻击者拿自己的合法 state/code 构造 callback URL 让受害者打开：
    受害者浏览器没有对应 nonce Cookie，必须拒绝。"""
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(client, monkeypatch, userinfo={"sub": "42"}, bind_nonce=False)
    assert response.status_code == 400
    # 未写入任何会话。
    assert client.get("/api/v1/nl2sql/auth/me").status_code == 401


def test_oauth_callback_rejects_mismatched_browser_nonce(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(
        client, monkeypatch, userinfo={"sub": "42"}, cookie_nonce="another-browser-nonce"
    )
    assert response.status_code == 400
    assert client.get("/api/v1/nl2sql/auth/me").status_code == 401


def test_oauth_callback_clears_nonce_cookie_on_success(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(client, monkeypatch, userinfo={"sub": "42"})
    assert response.status_code == 302
    set_cookies = response.headers.get_list("set-cookie")
    assert any(f'{auth.OAUTH_STATE_COOKIE}=""' in c or f"{auth.OAUTH_STATE_COOKIE}=;" in c for c in set_cookies)


# ---------------------------------------------------------------------------
# bcrypt 超长口令 → 401 而非 500（P2 回归）
# ---------------------------------------------------------------------------

def test_login_with_overlong_password_returns_401(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path)
    client = TestClient(app)
    response = client.post(
        "/api/v1/nl2sql/auth/login",
        json={"username": "admin", "password": "x" * 200},
    )
    assert response.status_code == 401
