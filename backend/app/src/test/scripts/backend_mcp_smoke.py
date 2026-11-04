"""Exercise the built-in MCP contract against service_accounts_smoke.py --keep fixtures.

Uses the same private --credentials, --backend-env, --state inputs. Requires a
configured local Dolphin target. Creates disposable task/workflow records and
removes them in finally; changes only the named smoke source's native roles.
Doris-only table creation is checked for engine rejection against MySQL.
"""
import argparse
import json
from datetime import datetime, timedelta
from pathlib import Path

import httpx

from service_accounts_smoke import Smoke


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('credentials', 'backend-env', 'state'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--mcp-url', default='http://127.0.0.1:8082/api/mcp/')
    args = parser.parse_args()
    args.cleanup, args.verify_only = False, True
    smoke = Smoke(args)
    state = smoke.state
    token = state['tokens'][1]['token']
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/json, text/event-stream'}
    seen = set()
    task_id = workflow_id = None
    service_id = state['users'][1]['id']
    original_roles = smoke.member('SERVICE', service_id)['roleIds']
    write_role = next(role for role in state['roles'] if role['template'] == 'READWRITE')
    checks = []

    def check(label, condition=True):
        assert condition, label
        checks.append(label)
        print('PASS', label, flush=True)

    def rpc(method, params, url=None, extra=None):
        response = httpx.post(url or args.mcp_url, headers={**headers, **(extra or {})},
                              json={'jsonrpc': '2.0', 'id': 1, 'method': method, 'params': params}, timeout=90)
        assert response.status_code == 200, (method, response.status_code)
        assert 'mcp-session-id' not in response.headers
        return response.json()['result']

    def call(name, arguments, error=False, extra=None):
        seen.add(name)
        result = rpc('tools/call', {'name': name, 'arguments': arguments}, extra=extra)
        assert bool(result.get('isError')) == error, (name, result.get('content'))
        return result.get('structuredContent') if not error else result

    def comment():
        return smoke.native('SELECT table_comment FROM information_schema.tables '
                            'WHERE table_schema=%s AND table_name=%s', (state['a'], 'orders'))[0]['TABLE_COMMENT']

    try:
        tools = rpc('tools/list', {})['tools']
        check('23 tools available on both concrete routes without sessions',
              len(tools) == len(rpc('tools/list', {}, args.mcp_url.rstrip('/'))['tools']) == 23)
        for url in (args.mcp_url, args.mcp_url.rstrip('/')):
            for method in ('GET', 'DELETE'):
                response = httpx.request(method, url, headers=headers, timeout=30)
                assert response.status_code == 405, (method, response.status_code)
        check('stateless GET and DELETE return 405')
        for bad in ({'params': {}}, {'workflow_id': 1}, {'unknown': True}):
            call('odw_list_workflows', bad, error=True)
        check('old wrappers, snake-case aliases and unknown fields rejected')
        database = state['a']
        tables = smoke.native('SELECT id FROM `' + smoke.platform_db + '`.data_table '
                              'WHERE cluster_id=%s AND db_name=%s AND table_name=%s AND deleted=0',
                              (state['source'], database, 'orders'))
        table_id = tables[0]['id']
        call('odw_search_tables', {'database': database})
        call('odw_get_lineage', {'tableId': table_id})
        call('odw_resolve_datasource', {'database': database, 'preferredEngine': 'mysql'})
        for kind in ('tables', 'lineage', 'datasource'):
            call('odw_export_metadata', {'kind': kind, 'database': database})
        call('odw_get_table_ddl', {'tableId': table_id})
        call('odw_analyze_sql', {'sql': 'SELECT id FROM orders', 'database': database,
                                 'clusterId': state['source']})
        bounded = call('odw_query_readonly', {'database': database, 'sql': "SELECT REPEAT('x',600000) AS big",
                                             'preferredEngine': 'mysql'})
        check('oversized first query row returns no partial row with notice',
              bounded['rows'] == [] and bounded['row_count'] == 0 and bounded['truncated_by_size'] and bounded['notice'])
        history = smoke.native('SELECT preview_row_count,result_preview,agent_id FROM `'
                               + smoke.platform_db + '`.data_query_history WHERE cluster_id=%s '
                               'AND sql_text=%s ORDER BY id DESC LIMIT 1',
                               (state['source'], "SELECT REPEAT('x',600000) AS big"))[0]
        check('audit stores exactly the bounded result and authenticated SERVICE',
              history['preview_row_count'] == bounded['row_count']
              and json.loads(history['result_preview']) == bounded['rows']
              and str(history['agent_id']) == str(service_id))
        plain = call('odw_query_readonly', {'database': database, 'sql': "SELECT id, amount FROM orders ORDER BY id",
                                           'preferredEngine': 'mysql'})
        check('query structuredContent keeps rows at top level', len(plain['rows']) == 2)
        before = comment()
        denied = call('odw_update_table_metadata', {'tableId': table_id, 'tableComment': 'must be denied'})
        check('physical DDL denied for read-only SERVICE without system fallback',
              bool(denied['failed']) and comment() == before)
        role = next(r for r in smoke.overview()['roles'] if r['id'] == write_role['id'])
        preview = smoke.api('POST', smoke.base + f"/roles/{role['id']}/preview", {
            'expectedRevision': role['revision'], 'grants': [
                {'resourceType': 'TABLE', 'database': database, 'tableName': 'orders', 'actions': ['QUERY', 'ALTER']}]})
        op = smoke.api('POST', smoke.base + f"/roles/{role['id']}/apply", {'previewId': preview['previewId']})
        assert op['state'] == 'SUCCESS'
        smoke.assign('SERVICE', service_id, [*state['roles'][:2], write_role])
        applied = call('odw_update_table_metadata', {'tableId': table_id, 'tableComment': 'MCP native DDL verified'},
                       extra={'X-Agent-User-Id': 'local:missing-smoke-human'})
        check('physical DDL uses SERVICE mapping even with invalid delegated reader',
              'table_comment' in applied['applied'] and not applied['failed'] and comment() == 'MCP native DDL verified')
        call('odw_update_table_metadata', {'tableId': table_id, 'tableComment': before or 'orders'})
        preview_args = {'dbName': database, 'layer': 'ods', 'businessDomain': 'test', 'dataDomain': 'test',
                        'customIdentifier': 'mcp_smoke', 'updateType': 'di',
                        'columns': [{'columnName': 'id', 'dataType': 'BIGINT', 'primaryKey': True}]}
        call('odw_preview_create_table', preview_args)
        call('odw_create_table', {**preview_args, 'dorisClusterId': state['source']}, error=True)
        check('Doris DDL preview works and MySQL creation is rejected explicitly')
        configs = smoke.api('GET', '/v1/settings/dolphin/configs')
        config = next(c for c in configs if c['isActive'] == 1)
        name = 'mcp_smoke_' + state['suffix']
        task = call('odw_create_task', {'task': {'taskName': name, 'taskCode': name, 'taskType': 'batch',
                                               'engine': 'dolphin', 'dolphinNodeType': 'SQL',
                                               'datasourceName': 'it_rt_sync_mysql', 'datasourceType': 'MYSQL',
                                               'taskSql': f'INSERT INTO {database}.orders VALUES (3,30)'},
                                       'inputTableIds': [], 'outputTableIds': [table_id]})
        task_id = task['id']
        call('odw_update_task', {'taskId': task_id, 'task': {'taskDesc': 'MCP update verified'}})
        call('odw_get_task', {'taskId': task_id})
        call('odw_list_tasks', {'keyword': name})
        definition = {'workflowName': name, 'dolphinConfigId': config['id'],
                      'tasks': [{'taskId': task_id, 'entry': True, 'exit': True}]}
        workflow = call('odw_create_workflow', {'workflow': definition})
        workflow_id = workflow['id']
        call('odw_update_workflow', {'workflowId': workflow_id, 'workflow': {**definition, 'description': 'smoke'}})
        call('odw_get_workflow', {'workflowId': workflow_id})
        call('odw_list_workflows', {'keyword': name})
        call('odw_preview_publish', {'workflowId': workflow_id})
        call('odw_publish_workflow', {'workflowId': workflow_id, 'operation': 'deploy', 'confirmDiff': True})
        call('odw_publish_workflow', {'workflowId': workflow_id, 'operation': 'online', 'confirmDiff': True})
        now = datetime.now()
        call('odw_upsert_schedule', {'workflowId': workflow_id, 'schedule': {
            'scheduleCron': '0 0 0 1 1 ? *', 'scheduleTimezone': 'America/Los_Angeles',
            'scheduleStartTime': now.strftime('%Y-%m-%d %H:%M:%S'),
            'scheduleEndTime': (now + timedelta(days=365)).strftime('%Y-%m-%d %H:%M:%S'),
            'scheduleAutoOnline': False}})
        call('odw_workflow_schedule_online', {'workflowId': workflow_id})
        call('odw_workflow_schedule_offline', {'workflowId': workflow_id})
        call('odw_publish_workflow', {'workflowId': workflow_id, 'operation': 'offline'})
        check('task/workflow draft, publish and schedule lifecycle complete')
        check('all 23 handlers reached through real HTTP MCP', seen == {t['name'] for t in tools})
        print('ACCEPTANCE CHECKS', len(checks), flush=True)
    finally:
        if workflow_id:
            smoke.api('DELETE', f'/v1/workflows/{workflow_id}?cascadeDeleteTasks=true')
        elif task_id:
            smoke.api('DELETE', f'/v1/tasks/{task_id}')
        member = smoke.member('SERVICE', service_id)
        if member and sorted(member['roleIds']) != sorted(original_roles):
            smoke.api('PUT', smoke.base + f'/members/SERVICE/{service_id}',
                      {'roleIds': original_roles, 'expectedRevision': member['revision']})
        smoke.close()


if __name__ == '__main__':
    main()
