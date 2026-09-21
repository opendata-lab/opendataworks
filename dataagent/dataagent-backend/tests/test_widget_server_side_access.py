"""Server-side access keys for widget sites.

A browser always sends Origin; a backend never does, and the origin check lets
an absent Origin through. That is correct for same-origin browser traffic but
means a server-to-server caller is authenticated by nothing more than knowing a
website_id. These tests pin both halves: the new key path, and the promise that
sites which never opt in behave exactly as before.
"""

import hashlib

import pytest

from core.skill_admin_service import (
    _normalize_widget_allowed_sites,
    _preserve_access_key_hashes,
)

DIGEST = hashlib.sha256(b"super-secret").hexdigest()


def sites(*items):
    return _normalize_widget_allowed_sites(list(items))


class TestNormalisation:
    def test_a_site_without_server_side_defaults_to_disabled(self):
        [site] = sites({"website_id": "portal"})

        assert site["server_side"] == {"enabled": False, "access_key_hash": ""}

    def test_server_side_survives_normalisation(self):
        [site] = sites({
            "website_id": "ontofoundry",
            "server_side": {"enabled": True, "access_key_hash": DIGEST},
        })

        assert site["server_side"] == {"enabled": True, "access_key_hash": DIGEST}

    def test_a_non_dict_server_side_is_ignored_rather_than_raising(self):
        [site] = sites({"website_id": "portal", "server_side": "yes please"})

        assert site["server_side"]["enabled"] is False


class TestRoundTripPreservesTheKey:
    """The admin read DTO masks the digest, and the UI saves that same shape
    back. Without carrying the stored value forward, opening the settings page
    and pressing save would silently revoke every site's key."""

    def test_saving_masked_settings_keeps_the_stored_digest(self):
        stored = sites({
            "website_id": "ontofoundry",
            "server_side": {"enabled": True, "access_key_hash": DIGEST},
        })
        # What comes back from the UI: enabled, but no digest.
        incoming = sites({
            "website_id": "ontofoundry",
            "server_side": {"enabled": True},
        })

        _preserve_access_key_hashes(incoming, stored)

        assert incoming[0]["server_side"]["access_key_hash"] == DIGEST

    def test_an_unrelated_site_is_unaffected(self):
        stored = sites({
            "website_id": "ontofoundry",
            "server_side": {"enabled": True, "access_key_hash": DIGEST},
        })
        incoming = sites({"website_id": "portal"})

        _preserve_access_key_hashes(incoming, stored)

        assert incoming[0]["server_side"]["access_key_hash"] == ""

    def test_an_explicit_digest_wins_so_rotation_is_possible(self):
        rotated = hashlib.sha256(b"rotated").hexdigest()
        stored = sites({
            "website_id": "ontofoundry",
            "server_side": {"enabled": True, "access_key_hash": DIGEST},
        })
        incoming = sites({
            "website_id": "ontofoundry",
            "server_side": {"enabled": True, "access_key_hash": rotated},
        })

        _preserve_access_key_hashes(incoming, stored)

        assert incoming[0]["server_side"]["access_key_hash"] == rotated


class TestRequestContext:
    """`_request_context` is the gate every widget call passes through."""

    @pytest.fixture
    def resolve(self, monkeypatch):
        from api import routes

        def _resolve(site, headers):
            monkeypatch.setattr(routes, "_allowed_widget_sites", lambda: [site])
            request = type("Req", (), {"headers": headers})()
            return routes._request_context(request)

        return _resolve

    @staticmethod
    def widget_headers(**extra):
        return {
            "X-ODW-Client": "widget",
            "X-ODW-Website-Id": "ontofoundry",
            "X-ODW-User-Id": "ontofoundry:w-1:s-1",
            **extra,
        }

    def test_a_site_that_never_opted_in_still_allows_an_absent_origin(self, resolve):
        # The existing behaviour, held in place: enabling this feature for one
        # site must not change how any other site works.
        context = resolve(
            {"website_id": "ontofoundry", "allowed_origins": []},
            self.widget_headers(),
        )

        assert context["source"] == "widget"

    def test_an_enabled_site_rejects_a_caller_with_no_key(self, resolve):
        from fastapi import HTTPException

        with pytest.raises(HTTPException) as caught:
            resolve(
                {
                    "website_id": "ontofoundry",
                    "allowed_origins": [],
                    "server_side": {"enabled": True, "access_key_hash": DIGEST},
                },
                self.widget_headers(),
            )

        assert caught.value.status_code == 403

    def test_an_enabled_site_rejects_a_wrong_key(self, resolve):
        from fastapi import HTTPException

        with pytest.raises(HTTPException) as caught:
            resolve(
                {
                    "website_id": "ontofoundry",
                    "allowed_origins": [],
                    "server_side": {"enabled": True, "access_key_hash": DIGEST},
                },
                self.widget_headers(**{"X-ODW-Access-Key": "not-it"}),
            )

        assert caught.value.status_code == 403

    def test_a_correct_key_passes_without_an_origin(self, resolve):
        context = resolve(
            {
                "website_id": "ontofoundry",
                "allowed_origins": [],
                "server_side": {"enabled": True, "access_key_hash": DIGEST},
            },
            self.widget_headers(**{"X-ODW-Access-Key": "super-secret"}),
        )

        assert context["external_user_id"] == "ontofoundry:w-1:s-1"

    def test_a_correct_key_bypasses_the_origin_allowlist(self, resolve):
        # A backend cannot satisfy an origin allowlist; enforcing one against a
        # caller that has already proven a secret would only look like security.
        context = resolve(
            {
                "website_id": "ontofoundry",
                "allowed_origins": ["https://somewhere.else"],
                "server_side": {"enabled": True, "access_key_hash": DIGEST},
            },
            self.widget_headers(**{
                "X-ODW-Access-Key": "super-secret",
                "Origin": "https://not.on.the.list",
            }),
        )

        assert context["source"] == "widget"

    def test_an_enabled_site_with_no_stored_digest_rejects_everyone(self, resolve):
        from fastapi import HTTPException

        with pytest.raises(HTTPException):
            resolve(
                {
                    "website_id": "ontofoundry",
                    "allowed_origins": [],
                    "server_side": {"enabled": True, "access_key_hash": ""},
                },
                self.widget_headers(**{"X-ODW-Access-Key": "anything"}),
            )
