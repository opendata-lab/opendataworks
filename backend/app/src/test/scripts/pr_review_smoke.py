"""Regression acceptance for PR review fixes on a prepared local MySQL source.

Uses service_accounts_smoke.py --keep fixtures and the same private credentials,
backend-env and state inputs. Creates only named temporary members/roles/schema;
restores the prepared source's DBA management connection in finally. Never logs
passwords or service tokens. Does not build or restart any service.
"""
import argparse
import json
import secrets
from pathlib import Path

import httpx
from service_accounts_smoke import Smoke


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('credentials', 'backend-env', 'state'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    args.cleanup, args.verify_only = False, True
    smoke = Smoke(args)
    source, config = smoke.state['source'], smoke.config
    suffix = secrets.token_hex(4)
    database = 'odwreview' + suffix
    limited = 'odw_review_' + suffix
    users, roles, checks = [], [], []
    token = None

    def check(label, condition=True):
        assert condition, label
        checks.append(label)
        print('PASS', label, flush=True)

    def manager(username, password):
        return smoke.api('PUT', smoke.base + '/management', {'username': username, 'password': password})

    def create_user(kind):
        payload = {'accountType': kind, 'nickname': 'Review ' + suffix, 'enabled': True}
        if kind == 'USER':
            payload.update(username='review' + secrets.token_hex(4), password='Aa1!' + secrets.token_urlsafe(20))
        user = smoke.api('POST', '/v1/users', payload)
        user['accountType'] = kind
        users.append(user)
        return user

    def assign(user, selected):
        member = smoke.member(user['accountType'], user['id'])
        return smoke.api('PUT', smoke.base + f"/members/{user['accountType']}/{user['id']}", {
            'roleIds': [role['id'] for role in selected], 'expectedRevision': member['revision'] if member else 0})

    def rpc(name, arguments=None, delegate=None, omitted=False):
        headers = {'Authorization': 'Bearer ' + token['token'], 'Accept': 'application/json, text/event-stream'}
        if delegate:
            headers['X-Agent-User-Id'] = delegate
        params = {'name': name}
        if not omitted:
            params['arguments'] = arguments or {}
        response = httpx.post(config['portal_url'] + '/mcp/', headers=headers, timeout=60,
                              json={'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call', 'params': params})
        assert response.status_code == 200, response.status_code
        return response.json()['result']

    try:
        assert smoke.overview()['management'] == {'username': config['mysql_user'], 'configured': True}
        for path in ('/mcp;v=1', '/mcp%3Bv=1', '/mcp/%2e/more', '//mcp', '/mcp%5cother'):
            response = smoke.admin.post(config['portal_url'] + path, json={'jsonrpc': '2.0', 'id': 1, 'method': 'initialize'})
            check('noncanonical HTTP path rejected: ' + path, response.status_code == 400)
        check('ordinary portal session cannot initialize MCP', smoke.admin.post('/mcp/', json={}).status_code == 401)

        smoke.native('CREATE DATABASE `' + database + '`')
        smoke.native('CREATE TABLE `' + database + '`.orders(id INT PRIMARY KEY)')
        smoke.native('INSERT INTO `' + database + '`.orders VALUES (1),(2)')
        synced = smoke.api('POST', f'/v1/tables/sync-metadata/database/{database}?clusterId={source}')
        assert str(synced.get('status')).upper() in ('SUCCESS', 'COMPLETED')
        for name in ('old', 'new'):
            role = smoke.api('POST', smoke.base + '/roles', {'name': 'review-' + suffix + name, 'template': 'READONLY'})
            roles.append(role)
            preview = smoke.api('POST', smoke.base + f"/roles/{role['id']}/preview", {
                'expectedRevision': role['revision'], 'grants': [{'resourceType': 'SCHEMA', 'database': database, 'actions': ['QUERY']}]})
            applied = smoke.api('POST', smoke.base + f"/roles/{role['id']}/apply", {'previewId': preview['previewId']})
            assert applied['state'] == 'SUCCESS'
        human, service = create_user('USER'), create_user('SERVICE')
        human_member = assign(human, roles[:1])
        service_member = assign(service, roles[:1])
        token = smoke.issue(service, 'review-smoke')
        for tool in ('odw_search_tables', 'odw_list_tasks', 'odw_list_workflows'):
            result = rpc(tool, omitted=True)
            check('MCP omitted arguments accepted: ' + tool, not result.get('isError'))
        check('MCP required arguments still enforced', rpc('odw_get_task', omitted=True).get('isError'))
        delegate = 'local:' + human['username']
        result = rpc('odw_query_readonly', {'database': database, 'sql': 'SELECT COUNT(*) AS n FROM orders'}, delegate)
        check('delegated query uses real USER explain and SERVICE execution',
              not result.get('isError') and result['structuredContent']['rows'] == [{'n': 2}])
        histories = smoke.native('SELECT executed_by,agent_id,check_account,execution_account FROM `'
                                 + smoke.platform_db + '`.data_query_history WHERE database_name=%s ORDER BY id DESC LIMIT 1', (database,))
        history = histories[0]
        check('audit records resolved user ID, explain login and service executor',
              history['executed_by'] == str(human['id']) and history['agent_id'] == str(service['id'])
              and history['check_account'] == human_member['account']['username']
              and history['execution_account'] == service_member['account']['username'])
        result = rpc('odw_resolve_datasource', {'database': database}, 'local:missing' + suffix)
        check('datasource resolution rejects unknown delegate', result.get('isError'))
        unmapped = create_user('USER')
        result = rpc('odw_resolve_datasource', {'database': database}, 'local:' + unmapped['username'])
        check('datasource resolution rejects unmapped delegate', result.get('isError'))

        # ROLE_ADMIN can grant a role but cannot SET DEFAULT ROLE for another user.
        password = 'Aa1!' + secrets.token_urlsafe(24)
        smoke.native('CREATE USER %s@%s IDENTIFIED BY %s', (limited, '%', password))
        smoke.native('GRANT ROLE_ADMIN ON *.* TO ' + "'" + limited + "'@'%'")
        smoke.native('GRANT SELECT ON mysql.* TO ' + "'" + limited + "'@'%'")
        manager(limited, password)
        response = smoke.admin.put(smoke.base + f"/members/USER/{human['id']}", json={
            'roleIds': [roles[1]['id']], 'expectedRevision': human_member['revision']})
        check('real grant followed by failed default activation reports PARTIAL',
              response.status_code == 400 and 'PARTIAL' in response.json()['message']
              and '授权管理连接' in response.json()['message'])
        failed = smoke.member('USER', human['id'])
        check('partial failure retains both protected platform role references',
              failed['status'] == 'ERROR' and set(failed['roleIds']) == {r['id'] for r in roles})
        actual = smoke.native('SELECT FROM_USER FROM mysql.role_edges WHERE TO_USER=%s', (human_member['account']['username'],))
        check('new role really exists in native membership after partial failure',
              roles[1]['nativeName'] in [row['FROM_USER'] for row in actual])
        denied = smoke.admin.delete(smoke.base + f"/roles/{roles[1]['id']}")
        check('cannot delete a role held after a partially successful native grant',
              denied.status_code == 400 and '成员' in denied.json()['message'])
        manager(config['mysql_user'], config['mysql_password'])
        recovered = assign(human, roles[1:])
        check('retry reconciles partial grant to exact target and ACTIVE',
              recovered['status'] == 'ACTIVE' and recovered['roleIds'] == [roles[1]['id']])
        print('ACCEPTANCE CHECKS', len(checks), flush=True)
    finally:
        manager(config['mysql_user'], config['mysql_password'])
        for user in reversed(users):
            if smoke.member(user['accountType'], user['id']):
                smoke.api('DELETE', smoke.base + f"/members/{user['accountType']}/{user['id']}")
            smoke.api('DELETE', '/v1/users/' + str(user['id']))
        for role in reversed(roles):
            op = smoke.api('DELETE', smoke.base + '/roles/' + str(role['id']))
            assert op['state'] == 'SUCCESS'
        smoke.native('DROP USER IF EXISTS %s@%s', (limited, '%'))
        smoke.native('DROP DATABASE IF EXISTS `' + database + '`')
        smoke.native('UPDATE `' + smoke.platform_db + '`.data_table SET deleted=1 WHERE cluster_id=%s AND db_name=%s', (source, database))
        smoke.close()


if __name__ == '__main__':
    main()
