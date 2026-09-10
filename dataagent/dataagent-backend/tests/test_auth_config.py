from __future__ import annotations

import sys
import time
import types
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

import core.auth as auth
from core.auth import (
    AuthConfigError,
    AuthIdentity,
    init_auth,
    is_auth_enabled,
    issue_session_token,
    load_auth_settings,
    resolve_oauth_role,
    sanitize_redirect_path,
    verify_local_admin,
    verify_session_token,
)
from core.topic_task_store import TopicTaskStore

VALID_SECRET = "unit-test-secret-key-0123456789abcdef-0123456789"
VALID_OAUTH_USER_INFO = (
    "def _oauth_user_info(provider, token_response, oauth_remotes):\n"
    "    return {'sub': 'test-sub'}\n"
    "OAUTH_USER_INFO = _oauth_user_info\n"
)


@pytest.fixture(autouse=True)
def _reset_auth(monkeypatch):
    monkeypatch.delenv(auth.AUTH_CONFIG_ENV, raising=False)
    auth.reset_auth_for_tests()
    # 加载器会把配置目录插入 sys.path（运行时单目录无害）；测试间恢复快照，
    # 防止上一个用例的 tmp 目录里的覆盖模块被后续用例 import 到。
    saved_sys_path = list(sys.path)
    yield
    sys.path[:] = saved_sys_path
    sys.modules.pop("dataagent_config_docker", None)
    auth.reset_auth_for_tests()


def write_config(tmp_path: Path, body: str) -> str:
    config_file = tmp_path / "auth_config.py"
    config_file.write_text(body, encoding="utf-8")
    return str(config_file)


def enable_local_admin_auth(tmp_path: Path, *, extra: str = "") -> str:
    import bcrypt

    password_hash = bcrypt.hashpw(b"admin-pass", bcrypt.gensalt(rounds=4)).decode()
    return write_config(
        tmp_path,
        f"""
AUTH_ENABLED = True
SECRET_KEY = {VALID_SECRET!r}
LOCAL_ADMINS = [{{"username": "admin", "password_bcrypt": {password_hash!r}}}]
{extra}
""",
    )


# ---------------------------------------------------------------------------
# Fail-closed 加载矩阵
# ---------------------------------------------------------------------------

def test_env_unset_disables_auth():
    settings = load_auth_settings()
    assert settings.enabled is False
    init_auth()
    assert is_auth_enabled() is False


def test_env_set_but_file_missing_fails_startup(monkeypatch, tmp_path):
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, str(tmp_path / "missing.py"))
    with pytest.raises(AuthConfigError):
        init_auth()


def test_env_set_but_file_invalid_python_fails_startup(monkeypatch, tmp_path):
    path = write_config(tmp_path, "this is not python !!!")
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, path)
    with pytest.raises(AuthConfigError):
        init_auth()


def test_enabled_without_secret_key_fails_startup(tmp_path):
    path = write_config(tmp_path, "AUTH_ENABLED = True\nLOCAL_ADMINS = []\n")
    with pytest.raises(AuthConfigError):
        load_auth_settings(path)


@pytest.mark.parametrize(
    "secret",
    ["", auth.SECRET_KEY_PLACEHOLDER, "short-secret"],
    ids=["empty", "example-placeholder", "under-32-bytes"],
)
def test_weak_secret_key_fails_startup(tmp_path, secret):
    path = write_config(
        tmp_path,
        f"AUTH_ENABLED = True\nSECRET_KEY = {secret!r}\n"
        "LOCAL_ADMINS = [{'username': 'a', 'password_bcrypt': '$2b$04$abcdefghijklmnopqrstuv'}]\n",
    )
    with pytest.raises(AuthConfigError):
        load_auth_settings(path)


def test_enabled_without_any_login_method_fails_startup(tmp_path):
    path = write_config(tmp_path, f"AUTH_ENABLED = True\nSECRET_KEY = {VALID_SECRET!r}\n")
    with pytest.raises(AuthConfigError):
        load_auth_settings(path)


def test_valid_file_with_auth_disabled_is_explicit_off(monkeypatch, tmp_path):
    path = write_config(tmp_path, "AUTH_ENABLED = False\n")
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, path)
    settings = init_auth()
    assert settings.enabled is False
    assert is_auth_enabled() is False


# ---------------------------------------------------------------------------
# 显式关闭回归：env 已设置 + AUTH_ENABLED=False + 库中已有 owner 数据
# → 谓词与旧版逐字一致、admin 依赖 no-op 放行。
# ---------------------------------------------------------------------------

def test_explicit_disable_uses_legacy_predicate_and_admin_noop(monkeypatch, tmp_path):
    path = write_config(tmp_path, "AUTH_ENABLED = False\n")
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, path)
    init_auth()

    store = TopicTaskStore()
    # 即使 context 携带 owner 键（模拟曾启用 auth 的调用方），关闭态也回到旧谓词。
    sql, params = store._topic_context_predicate(  # noqa: SLF001
        {"source": "portal", "auth_user_id": "SSO:42", "auth_role": "user"}, alias="t"
    )
    assert sql == "COALESCE(t.source, 'portal') = %s"
    assert params == ["portal"]

    # require_admin no-op：无会话也放行。
    fake_request = types.SimpleNamespace(cookies={}, headers={})
    assert auth.require_admin(fake_request) is None


# ---------------------------------------------------------------------------
# JWT / bcrypt / 提名
# ---------------------------------------------------------------------------

def test_jwt_roundtrip_and_expiry(monkeypatch, tmp_path):
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, enable_local_admin_auth(tmp_path))
    init_auth()

    identity = AuthIdentity(user_id="SSO:42", display_name="alice", role="user", provider="SSO")
    token = issue_session_token(identity)
    decoded = verify_session_token(token)
    assert decoded is not None
    assert decoded.user_id == "SSO:42"
    assert decoded.display_name == "alice"
    assert decoded.role == "user"

    expired = issue_session_token(identity, now=time.time() - 999999)
    assert verify_session_token(expired) is None
    assert verify_session_token("garbage.token.value") is None


def test_local_admin_bcrypt_verification(monkeypatch, tmp_path):
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, enable_local_admin_auth(tmp_path))
    init_auth()

    identity = verify_local_admin("admin", "admin-pass")
    assert identity is not None
    assert identity.role == "admin"
    assert identity.user_id == "local:admin"

    assert verify_local_admin("admin", "wrong-pass") is None
    assert verify_local_admin("nobody", "admin-pass") is None


def test_admin_users_promotes_by_stable_id_not_username(monkeypatch, tmp_path):
    monkeypatch.setenv(
        auth.AUTH_CONFIG_ENV,
        enable_local_admin_auth(tmp_path, extra='ADMIN_USERS = ["SSO:1024"]\n'),
    )
    init_auth()

    assert resolve_oauth_role("SSO", "1024") == "admin"
    # display_name（或其它 sub）不提权。
    assert resolve_oauth_role("SSO", "alice") == "user"
    assert resolve_oauth_role("OTHER", "1024") == "user"


# ---------------------------------------------------------------------------
# redirect 消毒
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "raw",
    ["http://evil.com/x", "https://evil.com", "//evil.com", "/\\evil", "javascript:alert(1)", "", None],
)
def test_sanitize_redirect_rejects_unsafe_values(raw):
    assert sanitize_redirect_path(raw, fallback="/intelligent-query") == "/intelligent-query"


def test_sanitize_redirect_accepts_app_paths():
    assert sanitize_redirect_path("/intelligent-query/chat?x=1") == "/intelligent-query/chat?x=1"


# ---------------------------------------------------------------------------
# Superset docker/pythonpath_dev 同款目录模式：
# 基础配置末尾加载同目录用户覆盖模块（配置目录在 sys.path 上）。
# ---------------------------------------------------------------------------

def _write_base_config(tmp_path: Path) -> str:
    # 与 deploy/docker/dataagent/dataagent_config.py 相同的加载模式：
    # find_spec 判存在，存在则直接 import（内部错误原样抛出，fail-closed）。
    base = tmp_path / "dataagent_config.py"
    base.write_text(
        """
AUTH_ENABLED = False
SECRET_KEY = ""
LOCAL_ADMINS = []
import importlib.util as _importlib_util
if _importlib_util.find_spec("dataagent_config_docker") is not None:
    from dataagent_config_docker import *  # noqa: F401,F403
""",
        encoding="utf-8",
    )
    return str(base)


@pytest.fixture()
def _clean_override_module():
    yield
    sys.modules.pop("dataagent_config_docker", None)


def test_base_config_without_override_is_disabled(monkeypatch, tmp_path, _clean_override_module):
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, _write_base_config(tmp_path))
    settings = init_auth()
    assert settings.enabled is False


def test_docker_override_module_is_loaded_from_config_dir(monkeypatch, tmp_path, _clean_override_module):
    import bcrypt

    password_hash = bcrypt.hashpw(b"admin-pass", bcrypt.gensalt(rounds=4)).decode()
    (tmp_path / "dataagent_config_docker.py").write_text(
        f"""
AUTH_ENABLED = True
SECRET_KEY = {VALID_SECRET!r}
LOCAL_ADMINS = [{{"username": "admin", "password_bcrypt": {password_hash!r}}}]
""",
        encoding="utf-8",
    )
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, _write_base_config(tmp_path))
    settings = init_auth()
    assert settings.enabled is True
    assert settings.local_login_enabled is True


def test_broken_override_module_fails_startup(monkeypatch, tmp_path, _clean_override_module):
    (tmp_path / "dataagent_config_docker.py").write_text("this is not python !!!", encoding="utf-8")
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, _write_base_config(tmp_path))
    with pytest.raises(AuthConfigError):
        init_auth()


def test_override_with_missing_nested_import_fails_startup(monkeypatch, tmp_path, _clean_override_module):
    """P1 回归：覆盖文件存在但其内部 import 失败（如引用缺失的
    custom_sso_user_info）必须让启动失败，绝不静默回落到认证关闭。"""
    (tmp_path / "dataagent_config_docker.py").write_text(
        "from missing_custom_module import something\nAUTH_ENABLED = True\n",
        encoding="utf-8",
    )
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, _write_base_config(tmp_path))
    with pytest.raises(AuthConfigError):
        init_auth()


def test_shipped_base_config_does_not_swallow_nested_import_error(monkeypatch, tmp_path, _clean_override_module):
    """同一回归直接跑仓库自带的 deploy/docker/dataagent/dataagent_config.py。"""
    import shutil

    shipped = Path(__file__).resolve().parents[3] / "deploy" / "docker" / "dataagent" / "dataagent_config.py"
    target = tmp_path / "dataagent_config.py"
    shutil.copyfile(shipped, target)
    (tmp_path / "dataagent_config_docker.py").write_text(
        "from missing_custom_module import something\n",
        encoding="utf-8",
    )
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, str(target))
    with pytest.raises(AuthConfigError):
        init_auth()


def test_shipped_override_example_is_safe_when_copied_unchanged(tmp_path):
    """Example defaults to auth off with no half-configured OAuth provider."""
    import shutil

    shipped = (
        Path(__file__).resolve().parents[3]
        / "deploy"
        / "docker"
        / "dataagent"
        / "dataagent_config_docker.py.example"
    )
    target = tmp_path / "auth_config.py"
    shutil.copyfile(shipped, target)

    settings = load_auth_settings(str(target))
    assert settings.enabled is False
    assert settings.oauth_login_enabled is False


def test_shipped_deploy_base_config_is_disabled_by_default(monkeypatch, _clean_override_module):
    shipped = Path(__file__).resolve().parents[3] / "deploy" / "docker" / "dataagent" / "dataagent_config.py"
    assert shipped.is_file(), shipped
    settings = load_auth_settings(str(shipped))
    assert settings.enabled is False


# ---------------------------------------------------------------------------
# DATAAGENT_SETTINGS：非认证的运行时 Settings 覆盖（config.py 字段）
# ---------------------------------------------------------------------------

def test_dataagent_settings_are_captured_and_validated(monkeypatch, tmp_path):
    path = write_config(
        tmp_path,
        "AUTH_ENABLED = False\nDATAAGENT_SETTINGS = {\"agent_interactive_timeout_seconds\": 420}\n",
    )
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, path)
    settings = init_auth()
    assert settings.runtime_settings == {"agent_interactive_timeout_seconds": 420}


def test_dataagent_settings_unknown_field_fails_startup(monkeypatch, tmp_path):
    path = write_config(tmp_path, "DATAAGENT_SETTINGS = {\"no_such_field\": 1}\n")
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, path)
    with pytest.raises(AuthConfigError):
        init_auth()


def test_dataagent_settings_must_be_dict(monkeypatch, tmp_path):
    path = write_config(tmp_path, "DATAAGENT_SETTINGS = [1, 2]\n")
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, path)
    with pytest.raises(AuthConfigError):
        init_auth()


# ---------------------------------------------------------------------------
# OAUTH_USER_INFO 配置校验（回调行为见 test_auth_routes.py）
# ---------------------------------------------------------------------------

def test_legacy_userinfo_mapper_requires_migration(monkeypatch, tmp_path):
    path = write_config(tmp_path, "OAUTH_USERINFO_MAPPER = lambda userinfo: userinfo\n")
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, path)
    with pytest.raises(AuthConfigError, match="OAUTH_USER_INFO"):
        init_auth()


def test_oauth_user_info_must_be_callable(monkeypatch, tmp_path):
    path = write_config(tmp_path, "OAUTH_USER_INFO = 'not-callable'\n")
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, path)
    with pytest.raises(AuthConfigError, match="callable"):
        init_auth()


def test_oauth_user_info_is_captured(monkeypatch, tmp_path):
    path = write_config(tmp_path, VALID_OAUTH_USER_INFO)
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, path)
    settings = init_auth()
    assert callable(settings.oauth_user_info)


@pytest.mark.parametrize(
    "body",
    [
        "async def _handler(provider, token_response, oauth_remotes):\n    return {'sub': 'x'}\n"
        "OAUTH_USER_INFO = _handler\n",
        "class _Handler:\n"
        "    async def __call__(self, provider, token_response, oauth_remotes):\n"
        "        return {'sub': 'x'}\n"
        "OAUTH_USER_INFO = _Handler()\n",
        "def _handler(provider, token_response):\n    return {'sub': 'x'}\n"
        "OAUTH_USER_INFO = _handler\n",
    ],
)
def test_oauth_user_info_rejects_async_or_wrong_signature(tmp_path, body):
    path = write_config(tmp_path, body)
    with pytest.raises(AuthConfigError, match="OAUTH_USER_INFO"):
        load_auth_settings(path)


# ---------------------------------------------------------------------------
# OAuth 启动期校验：callback 实际必需字段（P2 回归）
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("missing_field", ["api_base_url", "redirect_uri"])
def test_oauth_missing_callback_required_field_fails_startup(tmp_path, missing_field):
    oauth_provider = {
        "name": "SSO",
        "icon": "fa-github",
        "remote_app": {
            "client_id": "cid",
            "client_secret": "secret",
            "authorize_url": "https://sso.example.com/authorize",
            "access_token_url": "https://sso.example.com/token",
            "api_base_url": "https://sso.example.com/api/",
            "redirect_uri": "https://app.example.com/oauth-authorized/SSO",
        },
    }
    oauth_provider["remote_app"][missing_field] = ""
    path = write_config(
        tmp_path,
        f"AUTH_ENABLED = True\nSECRET_KEY = {VALID_SECRET!r}\n"
        f"OAUTH_PROVIDERS = {[oauth_provider]!r}\n{VALID_OAUTH_USER_INFO}",
    )
    with pytest.raises(AuthConfigError):
        load_auth_settings(path)


def test_oauth_providers_maps_superset_shape(tmp_path):
    provider = {
        "name": "GitHub",
        "icon": "fa-github",
        "token_key": "access_token",
        "remote_app": {
            "client_id": "cid",
            "client_secret": "secret",
            "authorize_url": "https://github.com/login/oauth/authorize",
            "access_token_url": "https://github.com/login/oauth/access_token",
            "api_base_url": "https://api.github.com/",
            "client_kwargs": {"scope": "read:user user:email"},
            "redirect_uri": "https://app.example.com/oauth-authorized/GitHub",
            "response_type": "code",
            "grant_type": "authorization_code",
        },
    }
    path = write_config(
        tmp_path,
        f"OAUTH_PROVIDERS = {[provider]!r}\n{VALID_OAUTH_USER_INFO}",
    )
    oauth = load_auth_settings(path).oauth
    assert oauth.provider_name == "GitHub"
    assert oauth.icon == "fa-github"
    assert oauth.token_url == provider["remote_app"]["access_token_url"]
    assert oauth.api_base_url == provider["remote_app"]["api_base_url"]
    assert oauth.scopes == "read:user user:email"
    assert oauth.response_type == "code"
    assert oauth.grant_type == "authorization_code"


@pytest.mark.parametrize(
    "provider_name",
    ["local", "LOCAL", "local:corp", ".", "..", "bad/name", "%2F", "-SSO", "S" * 65],
)
def test_oauth_provider_name_is_safe_path_and_identity_namespace(tmp_path, provider_name):
    provider = {
        "name": provider_name,
        "remote_app": {
            "client_id": "cid",
            "authorize_url": "https://sso.example.com/authorize",
            "access_token_url": "https://sso.example.com/token",
            "api_base_url": "https://sso.example.com/api/",
            "redirect_uri": f"https://app.example.com/oauth-authorized/{provider_name}",
        },
    }
    path = write_config(tmp_path, f"OAUTH_PROVIDERS = {[provider]!r}\n")
    with pytest.raises(AuthConfigError, match="Provider key"):
        load_auth_settings(path)


@pytest.mark.parametrize("field", ["userinfo_url", "user_id_field", "username_field"])
def test_oauth_provider_rejects_removed_top_level_fields(tmp_path, field):
    provider = {
        "name": "SSO",
        "remote_app": {
            "client_id": "cid",
            "authorize_url": "https://sso.example.com/authorize",
            "access_token_url": "https://sso.example.com/token",
            "api_base_url": "https://sso.example.com/api/",
            "redirect_uri": "https://app.example.com/oauth-authorized/SSO",
        },
        field: "legacy",
    }
    path = write_config(tmp_path, f"OAUTH_PROVIDERS = {[provider]!r}\n")
    with pytest.raises(AuthConfigError, match="已不支持"):
        load_auth_settings(path)


def test_oauth_provider_rejects_explicit_userinfo_endpoint(tmp_path):
    provider = {
        "name": "SSO",
        "remote_app": {
            "client_id": "cid",
            "authorize_url": "https://sso.example.com/authorize",
            "access_token_url": "https://sso.example.com/token",
            "userinfo_endpoint": "https://sso.example.com/userinfo",
            "api_base_url": "https://sso.example.com/api/",
            "redirect_uri": "https://app.example.com/oauth-authorized/SSO",
        },
    }
    path = write_config(tmp_path, f"OAUTH_PROVIDERS = {[provider]!r}\n")
    with pytest.raises(AuthConfigError, match="OAUTH_USER_INFO"):
        load_auth_settings(path)


def test_oauth_provider_requires_user_info_handler(tmp_path):
    provider = {
        "name": "SSO",
        "remote_app": {
            "client_id": "cid",
            "authorize_url": "https://sso.example.com/authorize",
            "access_token_url": "https://sso.example.com/token",
            "api_base_url": "https://sso.example.com/api/",
            "redirect_uri": "https://app.example.com/oauth-authorized/SSO",
        },
    }
    path = write_config(tmp_path, f"OAUTH_PROVIDERS = {[provider]!r}\n")
    with pytest.raises(AuthConfigError, match="缺少 OAUTH_USER_INFO"):
        load_auth_settings(path)


@pytest.mark.parametrize(
    "redirect_uri",
    [
        "https://app.example.com/api/v1/nl2sql/auth/oauth/callback",
        "https://app.example.com/oauth-authorized/Other",
        "/oauth-authorized/SSO",
        "https://app.example.com/oauth-authorized/SSO?next=/chat",
    ],
)
def test_oauth_redirect_uri_must_use_fab_provider_callback(tmp_path, redirect_uri):
    provider = {
        "name": "SSO",
        "remote_app": {
            "client_id": "cid",
            "authorize_url": "https://sso.example.com/authorize",
            "access_token_url": "https://sso.example.com/token",
            "api_base_url": "https://sso.example.com/api/",
            "redirect_uri": redirect_uri,
        },
    }
    path = write_config(tmp_path, f"OAUTH_PROVIDERS = {[provider]!r}\n")
    with pytest.raises(AuthConfigError, match="oauth-authorized"):
        load_auth_settings(path)


@pytest.mark.parametrize(
    "api_base_url",
    [
        "https://sso.example.com/api",
        "/api/",
        "ftp://sso.example.com/api/",
        "https://sso.example.com/api/?tenant=1",
    ],
)
def test_oauth_api_base_url_must_be_absolute_and_end_with_slash(tmp_path, api_base_url):
    provider = {
        "name": "SSO",
        "remote_app": {
            "client_id": "cid",
            "authorize_url": "https://sso.example.com/authorize",
            "access_token_url": "https://sso.example.com/token",
            "api_base_url": api_base_url,
            "redirect_uri": "https://app.example.com/oauth-authorized/SSO",
        },
    }
    path = write_config(tmp_path, f"OAUTH_PROVIDERS = {[provider]!r}\n")
    with pytest.raises(AuthConfigError, match="api_base_url"):
        load_auth_settings(path)


@pytest.mark.parametrize(
    ("field", "value"),
    [("response_type", "token"), ("grant_type", "client_credentials")],
)
def test_oauth_provider_rejects_unsupported_flow_values(tmp_path, field, value):
    provider = {
        "name": "SSO",
        "remote_app": {
            "client_id": "cid",
            "authorize_url": "https://sso.example.com/authorize",
            "access_token_url": "https://sso.example.com/token",
            "api_base_url": "https://sso.example.com/api/",
            "redirect_uri": "https://app.example.com/oauth-authorized/SSO",
            field: value,
        },
    }
    path = write_config(tmp_path, f"OAUTH_PROVIDERS = {[provider]!r}\n")
    with pytest.raises(AuthConfigError, match="授权码模式"):
        load_auth_settings(path)


@pytest.mark.parametrize(
    "providers",
    [
        {"name": "not-a-list"},
        ({"name": "tuple-is-not-a-list"},),
        [{"name": "one"}, {"name": "two"}],
        ["not-a-dict"],
    ],
)
def test_oauth_providers_rejects_unsupported_shapes(tmp_path, providers):
    path = write_config(tmp_path, f"OAUTH_PROVIDERS = {providers!r}\n")
    with pytest.raises(AuthConfigError):
        load_auth_settings(path)


def test_nonempty_oauth_provider_cannot_silently_disable_itself(tmp_path):
    path = write_config(tmp_path, "OAUTH_PROVIDERS = [{'name': 'half-configured'}]\n")
    with pytest.raises(AuthConfigError, match="remote_app"):
        load_auth_settings(path)


@pytest.mark.parametrize(
    "provider",
    [
        {"name": "bad-remote", "remote_app": []},
        {
            "name": "bad-client-kwargs",
            "remote_app": {
                "client_id": "cid",
                "authorize_url": "https://sso.example.com/authorize",
                "access_token_url": "https://sso.example.com/token",
                "api_base_url": "https://sso.example.com/api/",
                "redirect_uri": "https://app.example.com/oauth-authorized/bad-client-kwargs",
                "client_kwargs": [],
            },
        },
    ],
)
def test_oauth_provider_rejects_falsey_non_dict_nested_config(tmp_path, provider):
    path = write_config(tmp_path, f"OAUTH_PROVIDERS = {[provider]!r}\n")
    with pytest.raises(AuthConfigError, match="dict"):
        load_auth_settings(path)


def test_legacy_flat_oauth_fails_with_migration_required(tmp_path):
    path = write_config(tmp_path, "OAUTH = {'provider_name': 'legacy'}\n")
    with pytest.raises(AuthConfigError, match="OAUTH_PROVIDERS"):
        load_auth_settings(path)


# ---------------------------------------------------------------------------
# bcrypt 超长口令（bcrypt 5 对 >72 字节抛 ValueError）按认证失败处理（P2 回归）
# ---------------------------------------------------------------------------

def test_overlong_password_is_rejected_not_crash(monkeypatch, tmp_path):
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, enable_local_admin_auth(tmp_path))
    init_auth()
    assert verify_local_admin("admin", "x" * 200) is None
