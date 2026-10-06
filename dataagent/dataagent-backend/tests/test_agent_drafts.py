"""Publication must be backed by a successful run of the current draft."""
import json
import pytest
from fastapi.testclient import TestClient
from api import admin_routes
from core.agent_draft_service import DraftConflict, check_revision, configuration, encode, publishable
from core.agent_profile_service import build_agent_snapshot, default_agent_payload
from main import app


def ready_row():
    config = configuration({**default_agent_payload(), 'visibility': {'mode': 'all', 'allowed_users': [], 'allowed_groups': []}})
    return {'draft_json': encode(config), 'published_json': None, 'revision': 3,
            'preview_revision': 3, 'preview_task_id': 'task_1', 'latest_task_id': 'task_1',
            'preview_status': 'finished', 'is_agent_preview': 1,
            'preview_snapshot': json.dumps(build_agent_snapshot(config))}


def test_successful_current_preview_is_publishable():
    assert publishable(ready_row())


@pytest.mark.parametrize('updates', [
    {'preview_status': 'error'}, {'preview_status': 'suspended'}, {'preview_status': 'running'},
    {'preview_revision': 2}, {'latest_task_id': 'task_new_failed'}, {'is_agent_preview': 0},
    {'preview_snapshot': '{}'}, {'preview_task_id': None},
])
def test_old_failed_cancelled_or_nonpreview_runs_cannot_publish(updates):
    assert not publishable({**ready_row(), **updates})


def test_same_config_cannot_publish_twice():
    row = ready_row(); row['published_json'] = row['draft_json']
    assert not publishable(row)


def test_runtime_snapshot_must_match_including_env_and_scope():
    row = ready_row(); config = json.loads(row['draft_json']); config['env_vars'] = {'SAFE_FLAG': 'new'}
    row['draft_json'] = encode(config)
    assert not publishable(row)


def test_concurrent_revision_rejected():
    with pytest.raises(DraftConflict):
        check_revision({'revision': 4}, 3)


def test_management_save_requires_revision_and_reports_conflict(monkeypatch):
    client = TestClient(app)
    response = client.put('/api/v1/dataagent/agents/a', json={'name': 'new'})
    assert response.status_code == 422
    monkeypatch.setattr(admin_routes, 'list_documents', lambda: [])
    def conflict(*args):
        raise DraftConflict('草稿已更新')
    monkeypatch.setattr(admin_routes.agent_drafts, 'save', conflict)
    response = client.put('/api/v1/dataagent/agents/a', json={'name': 'new', 'expected_revision': 3})
    assert response.status_code == 409


def test_static_workbench_route_does_not_match_public_agent_route(monkeypatch):
    monkeypatch.setattr(admin_routes.agent_drafts, 'list', lambda: [{'agent_id': 'draft-only', 'published_version': 0}])
    response = TestClient(app).get('/api/v1/dataagent/agents/workbench')
    assert response.status_code == 200
    assert response.json()[0]['published_version'] == 0


def test_preview_submission_cannot_target_a_formal_chat(monkeypatch):
    monkeypatch.setattr(admin_routes.agent_drafts, 'get', lambda _: {'revision': 1})
    class Store:
        def get_topic(self, _):
            return {'agent_id': 'a', 'is_agent_preview': False}
    monkeypatch.setattr(admin_routes, 'get_topic_task_store', lambda: Store())
    response = TestClient(app).post('/api/v1/dataagent/agents/a/preview-tasks', json={
        'expected_revision': 1, 'topic_id': 'formal', 'message_content': 'test'})
    assert response.status_code == 404
