"""Widget conversations are isolated by (website_id, external_user_id).

This is load-bearing for OntoFoundry, which sends one synthetic external user
per modeling session precisely so that sessions cannot see each other. It also
looked, during review, like a hole: the task endpoints never call
`_require_topic`, so they read as unguarded. They are not — `get_task` applies
the topic context predicate itself.

That makes the property invisible at the call sites, which is exactly the kind
of thing a later refactor removes without noticing. These tests exist to make
that removal loud.
"""

import pytest

from core import topic_task_store


def widget_context(external_user_id, website_id="ontofoundry"):
    return {
        "source": "widget",
        "website_id": website_id,
        "external_user_id": external_user_id,
        "visitor_id": "",
    }


def bare_store():
    """An instance without touching the database — the predicate is pure."""
    return topic_task_store.TopicTaskStore.__new__(topic_task_store.TopicTaskStore)


class TestContextPredicate:
    """The SQL fragment every scoped read is built from."""

    def test_a_logged_in_widget_user_is_scoped_to_its_own_site_and_id(self):
        sql, params = bare_store()._topic_context_predicate(
            widget_context("ontofoundry:w-1:s-1")
        )

        assert "source" in sql and "website_id" in sql and "external_user_id" in sql
        assert params == ["widget", "ontofoundry", "ontofoundry:w-1:s-1"]

    def test_two_sessions_on_one_site_produce_different_predicates(self):
        # OntoFoundry's whole isolation story rests on this: the identity it
        # sends is the modeling session, so session A's parameters must not
        # match session B's rows.
        _, a = bare_store()._topic_context_predicate(widget_context("ontofoundry:w-1:s-1"))
        _, b = bare_store()._topic_context_predicate(widget_context("ontofoundry:w-1:s-2"))

        assert a != b

    def test_an_anonymous_visitor_is_scoped_by_visitor_id_instead(self):
        context = {
            "source": "widget",
            "website_id": "demo",
            "external_user_id": "",
            "visitor_id": "visitor_abc",
        }

        sql, params = bare_store()._topic_context_predicate(context)

        assert "visitor_id" in sql
        assert "visitor_abc" in params


class TestTaskReadsAreScoped:
    """Task endpoints do not call `_require_topic`; `get_task` scopes instead.

    If this ever stops being true, knowing a task id would be enough to read
    another session's run — and nothing at the route layer would show it.
    """

    def test_get_task_threads_the_request_context_into_its_query(self, monkeypatch):
        seen = {}

        def fake_predicate(self, context=None, alias=""):
            seen["context"] = context
            seen["alias"] = alias
            return "1=1", []

        monkeypatch.setattr(
            topic_task_store.TopicTaskStore, "_topic_context_predicate", fake_predicate
        )
        monkeypatch.setattr(topic_task_store.TopicTaskStore, "_ensure_ready", lambda self: None)

        class Cursor:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def execute(self, *_args, **_kwargs):
                return None

            def fetchone(self):
                return None

        class Conn:
            def cursor(self):
                return Cursor()

            def close(self):
                return None

        monkeypatch.setattr(
            topic_task_store.TopicTaskStore, "_connect", lambda self, **_: Conn()
        )
        monkeypatch.setattr(
            topic_task_store.TopicTaskStore, "_schema_name", lambda self: "public"
        )

        store = topic_task_store.TopicTaskStore.__new__(topic_task_store.TopicTaskStore)
        context = widget_context("ontofoundry:w-1:s-1")

        store.get_task("t-1", context=context)

        assert seen["context"] == context, "get_task must scope by the caller's context"
        assert seen["alias"] == "topic", "the predicate has to target the joined topic"

    @pytest.mark.parametrize(
        "route_name",
        [
            "api_get_task",
            "api_get_task_message",
            "api_stream_sdk_events",
            "api_cancel_task",
            "api_submit_permission_decision",
            "api_submit_question_answer",
        ],
    )
    def test_every_task_route_resolves_a_request_context(self, route_name):
        # Each of these reads a task by id. Whatever else they do, they must
        # derive a context first — that is what reaches get_task and scopes the
        # row. A route that stopped doing so would silently become readable
        # across sessions.
        import inspect

        from api import routes

        source = inspect.getsource(getattr(routes, route_name))

        assert "_request_context(" in source, (
            f"{route_name} must resolve a request context before reading a task"
        )
