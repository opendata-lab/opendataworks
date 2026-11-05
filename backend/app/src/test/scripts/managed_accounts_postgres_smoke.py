"""Disposable PostgreSQL 16 + platform HTTP acceptance for managed logins.

Use private --credentials, --backend-env, --postgres-credentials and --key-file
JSON files. All created sources, members, roles and schemas are removed on exit.
"""
import argparse
import base64
import json
import secrets
from pathlib import Path
from urllib.parse import urlparse

import httpx
import psycopg
import pymysql
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('credentials', 'backend-env', 'postgres-credentials', 'key-file'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    cfg = json.loads(args.credentials.read_text())
    pg_cfg = json.loads(args.postgres_credentials.read_text())
    platform_db = urlparse(json.loads(args.backend_env.read_text())['SPRING_DATASOURCE_URL'].removeprefix('jdbc:')).path.lstrip('/')
    assert urlparse(cfg['portal_url']).hostname in ('localhost', '127.0.0.1')
    assert cfg['mysql_host'] in ('localhost', '127.0.0.1') and pg_cfg['host'] in ('localhost', '127.0.0.1')
    key = base64.b64decode(json.loads(args.key_file.read_text())['ODW_CREDENTIAL_KEY'])
    client = httpx.Client(base_url=cfg['portal_url'], timeout=60)
    dba = psycopg.connect(**pg_cfg, autocommit=True)
    platform = pymysql.connect(host=cfg['mysql_host'], port=cfg['mysql_port'], user=cfg['mysql_user'],
                               password=cfg['mysql_password'], database=platform_db, autocommit=True,
                               cursorclass=pymysql.cursors.DictCursor)
    suffix = secrets.token_hex(4)
    schema = 'odwapg' + suffix
    limited = 'odwlimited' + suffix
    source = None
    roles, users, checks = [], [], []

    def api(method, path, body=None):
        response = client.request(method, path, json=body)
        result = response.json()
        assert response.status_code == 200 and result['code'] == 200, (path, result.get('message'))
        return result.get('data')

    def check(label, condition=True):
        assert condition, label
        checks.append(label)
        print('PASS', label, flush=True)

    def mapping(user):
        with platform.cursor() as cur:
            cur.execute('SELECT username,password,managed FROM datasource_subject_account '
                        'WHERE source_id=%s AND subject_type=%s AND subject_id=%s',
                        (source, user['accountType'], str(user['id'])))
            return cur.fetchone()

    def member(user):
        return next((m for m in api('GET', base)['members'] if m['subjectId'] == str(user['id'])
                     and m['subjectType'] == user['accountType']), None)

    def assign(user, desired):
        old = member(user)
        return api('PUT', base + f"/members/{user['accountType']}/{user['id']}", {
            'roleIds': [r['id'] for r in desired], 'expectedRevision': old['revision'] if old else 0})

    def make_user(kind):
        data = {'accountType': kind, 'nickname': 'PG自动建号验收' + suffix, 'enabled': True}
        if kind == 'USER': data.update(username='pgauto' + secrets.token_hex(5), password='Aa1!' + secrets.token_urlsafe(20))
        user = api('POST', '/v1/users', data)
        user['accountType'] = kind
        users.append(user)
        return user

    def secret(row):
        blob = base64.b64decode(row['password'][3:])
        return AESGCM(key).decrypt(blob[:12], blob[12:], None).decode()

    def connect(row):
        return psycopg.connect(**{**pg_cfg, 'user': row['username'], 'password': secret(row)}, autocommit=True)

    def remove(user):
        api('DELETE', base + f"/members/{user['accountType']}/{user['id']}")

    try:
        api('POST', '/auth/login', {'username': cfg['admin_username'], 'password': cfg['admin_password']})
        source = api('POST', '/v1/doris-clusters', {
            'clusterName': 'PG自动建号验收' + suffix, 'sourceType': 'POSTGRESQL', 'databaseName': pg_cfg['dbname'],
            'feHost': pg_cfg['host'], 'fePort': pg_cfg['port'], 'username': pg_cfg['user'],
            'password': pg_cfg['password'], 'isDefault': 0, 'autoSync': 0, 'status': 'active'})['id']
        base = f'/v1/doris-clusters/{source}/permissions'
        api('PUT', base + '/management', {'username': pg_cfg['user'], 'password': pg_cfg['password']})
        dba.execute('CREATE SCHEMA ' + schema)
        dba.execute('CREATE TABLE ' + schema + '.orders(id INTEGER PRIMARY KEY)')
        dba.execute('INSERT INTO ' + schema + '.orders VALUES(1),(2)')
        synced = api('POST', f'/v1/tables/sync-metadata/database/{schema}?clusterId={source}')
        assert str(synced.get('status')).upper() in ('SUCCESS', 'COMPLETED')
        for template, actions in [('READONLY', ['QUERY']), ('READWRITE', ['QUERY', 'CREATE'])]:
            role = api('POST', base + '/roles', {'name': 'PG-' + template + suffix, 'template': template})
            roles.append(role)
            preview = api('POST', base + f"/roles/{role['id']}/preview", {
                'expectedRevision': role['revision'], 'grants': [
                    {'resourceType': 'SCHEMA', 'database': schema, 'actions': actions}]})
            operation = api('POST', base + f"/roles/{role['id']}/apply", {'previewId': preview['previewId']})
            assert operation['state'] == 'SUCCESS', operation.get('message')
        human, service = make_user('USER'), make_user('SERVICE')
        for user in (human, service):
            assigned = assign(user, roles[:1])
            before = mapping(user)
            with connect(before) as login:
                assert login.execute('SELECT COUNT(*) FROM ' + schema + '.orders').fetchone()[0] == 2
                attrs = login.execute('SELECT rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls '
                                      'FROM pg_roles WHERE rolname=current_user').fetchone()
            check(user['accountType'] + ' automatically receives a verified encrypted LOGIN without admin attributes',
                  assigned['status'] == 'ACTIVE' and assigned['account']['managed'] and not any(attrs))
            assign(user, roles)
            check(user['accountType'] + ' multiple inherited roles reuse the same login and credential', mapping(user) == before)
            assign(user, roles[:1])
        token = api('POST', f"/v1/users/{service['id']}/tokens", {'name': 'managed-pg-smoke'})['token']
        response = client.post('/mcp/', headers={'Authorization': 'Bearer ' + token,
                               'Accept': 'application/json, text/event-stream'}, json={
            'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call', 'params': {
                'name': 'odw_query_readonly', 'arguments': {'database': schema, 'preferredEngine': 'postgresql',
                                                          'sql': 'SELECT COUNT(*) AS n FROM orders'}}})
        result = response.json()['result']
        check('PostgreSQL MCP query uses the generated SERVICE login',
              not result.get('isError') and result['structuredContent']['rows'] == [{'n': 2}])
        retry_user = make_user('USER')
        limited_password = 'Aa1!' + secrets.token_urlsafe(24)
        from psycopg import sql
        dba.execute(sql.SQL('CREATE ROLE {} LOGIN PASSWORD {}').format(sql.Identifier(limited), sql.Literal(limited_password)))
        api('PUT', base + '/management', {'username': limited, 'password': limited_password})
        try:
            rejected = client.put(base + f"/members/USER/{retry_user['id']}",
                                  json={'roleIds': [roles[0]['id']], 'expectedRevision': 0})
            pending = mapping(retry_user)
            check('PostgreSQL missing CREATEROLE returns configuration guidance and retains ERROR state',
                  rejected.status_code == 400 and '授权管理连接' in rejected.json()['message']
                  and member(retry_user)['status'] == 'ERROR')
        finally:
            api('PUT', base + '/management', {'username': pg_cfg['user'], 'password': pg_cfg['password']})
        assign(retry_user, roles[:1])
        check('PostgreSQL retry reuses the pending encrypted identity', mapping(retry_user) == pending)
        assign(human, roles)
        human_row = mapping(human)
        with connect(human_row) as login:
            login.execute('CREATE TABLE ' + schema + '.owned_by_member(id INTEGER)')
        rejected = client.delete(base + f"/members/USER/{human['id']}")
        check('owned PostgreSQL objects block login deletion without CASCADE or data loss',
              rejected.status_code == 400 and member(human)['status'] == 'ERROR'
              and dba.execute("SELECT to_regclass(%s)", (schema + '.owned_by_member',)).fetchone()[0] is not None)
        dba.execute(sql.SQL('ALTER TABLE {}.owned_by_member OWNER TO {}').format(sql.Identifier(schema), sql.Identifier(pg_cfg['user'])))
        for user in users:
            row = mapping(user)
            remove(user)
            check('PostgreSQL removal deletes only the owned managed LOGIN',
                  mapping(user) is None and not dba.execute('SELECT 1 FROM pg_roles WHERE rolname=%s', (row['username'],)).fetchall())
        print('ACCEPTANCE CHECKS', len(checks), flush=True)
    finally:
        if source:
            # Only disposable fixtures from this run; ownership is returned explicitly, never CASCADE.
            from psycopg import sql
            if dba.execute('SELECT to_regclass(%s)', (schema + '.owned_by_member',)).fetchone()[0]:
                dba.execute(sql.SQL('ALTER TABLE {}.owned_by_member OWNER TO {}').format(sql.Identifier(schema), sql.Identifier(pg_cfg['user'])))
            for user in users:
                if member(user): remove(user)
                api('DELETE', '/v1/users/' + str(user['id']))
            for role in reversed(roles):
                assert api('DELETE', base + '/roles/' + str(role['id']))['state'] == 'SUCCESS'
            dba.execute('DROP TABLE IF EXISTS ' + schema + '.owned_by_member')
            dba.execute('DROP TABLE IF EXISTS ' + schema + '.orders')
            dba.execute('DROP SCHEMA IF EXISTS ' + schema)
            dba.execute(sql.SQL('DROP ROLE IF EXISTS {}').format(sql.Identifier(limited)))
            with platform.cursor() as cur:
                cur.execute('UPDATE data_table SET deleted=1 WHERE cluster_id=%s', (source,))
                cur.execute('UPDATE doris_cluster SET fe_host=%s WHERE id=%s', ('deleted-pg-' + suffix, source))
            api('DELETE', '/v1/doris-clusters/' + str(source))
        dba.close(); platform.close(); client.close()


if __name__ == '__main__':
    main()
