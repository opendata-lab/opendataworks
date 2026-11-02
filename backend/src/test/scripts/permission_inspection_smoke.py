"""Real MySQL inspection regression; start the backend outside this script.

Uses private JSON credentials (same contract as data_permissions_smoke.py) and an
existing empty-role source. Creates only a temporary native schema/roles/account
and platform USER; no existing account grants or management configuration change.
A private state file permits cleanup after interruption: rerun with --cleanup.
"""
import argparse
import json
import secrets
from pathlib import Path

import httpx
import pymysql

parser = argparse.ArgumentParser()
parser.add_argument('--credentials', type=Path, required=True)
parser.add_argument('--state', type=Path, required=True)
parser.add_argument('--source-id', type=int, required=True)
parser.add_argument('--cleanup', action='store_true')
args = parser.parse_args()
cfg = json.loads(args.credentials.read_text())
state = json.loads(args.state.read_text()) if args.state.exists() else {
    'suffix': secrets.token_hex(5), 'checks': [], 'source': args.source_id,
    'native_created': False, 'user_id': None, 'bound': False,
}
if not args.cleanup and state.get('cleaned'):
    state['suffix'] = secrets.token_hex(5)
assert state['source'] == args.source_id
prefix = f"/v1/doris-clusters/{args.source_id}/permissions"
client = httpx.Client(base_url=cfg['portal_url'], timeout=30)
dba = pymysql.connect(host=cfg['mysql_host'], port=cfg['mysql_port'],
                      user=cfg['mysql_user'], password=cfg['mysql_password'],
                      autocommit=True)
schema = 'odwinspect' + state['suffix']
username = 'odwinspect' + state['suffix']
roles = ['odwir' + state['suffix'] + ending for ending in ['a', 'b', 'c']]
password = secrets.token_urlsafe(24)


def persist():
    args.state.write_text(json.dumps(state, ensure_ascii=False, indent=2))
    args.state.chmod(0o600)


def check(name, valid):
    assert valid, name
    state['checks'].append(name)
    persist()
    print('PASS', name, flush=True)


def request(method, path, body=None, success=True):
    response = client.request(method, path, json=body) if body is not None else client.request(method, path)
    payload = response.json()
    if not success:
        return response.status_code, payload
    assert response.status_code == 200 and payload.get('code') == 200, (method, path, response.status_code, payload.get('message'))
    return payload.get('data')


def native(sql, params=None):
    with dba.cursor() as cursor:
        cursor.execute(sql, params)
        return cursor.fetchall() if cursor.description else None


def cleanup():
    if state['bound']:
        request('DELETE', prefix + f"/members/USER/{state['user_id']}")
        state['bound'] = False
        persist()
    if state['user_id'] is not None:
        request('DELETE', f"/v1/users/{state['user_id']}")
        state['user_id'] = None
        persist()
    if state['native_created']:
        native('DROP USER IF EXISTS %s@%s', (username, '%'))
        for name, host in zip(roles, ['localhost', '%', '%']):
            native('DROP ROLE IF EXISTS %s@%s', (name, host))
        native('DROP DATABASE IF EXISTS `' + schema + '`')
        state['native_created'] = False
        persist()
    state['cleaned'] = True
    persist()


try:
    request('POST', '/auth/login', {'username': cfg['admin_username'], 'password': cfg['admin_password']})
    if args.cleanup:
        cleanup()
        print('CLEANUP complete', flush=True)
    else:
        state['checks'] = []
        state['cleaned'] = False
        assert not state['native_created'] and state['user_id'] is None, 'Run --cleanup for retained fixtures first'
        overview = request('GET', prefix)
        assert overview['engine'] == 'MYSQL' and not overview['roles'], 'Select a MySQL source with no managed roles'
        baseline = json.dumps(overview, sort_keys=True)
        for member in overview['members']:
            result = request('GET', prefix + f"/members/{member['subjectType']}/{member['subjectId']}/inspect")
            check('existing ' + member['subjectType'] + ' returns native grants',
                  result['username'] == member['account']['username'] and bool(result['grants']))
        source = request('GET', f"/v1/doris-clusters/{args.source_id}")
        assert source['feHost'] in ['localhost', '127.0.0.1']
        state['native_created'] = True
        persist()
        native('CREATE DATABASE `' + schema + '`')
        for name in ['direct_orders', 'role_orders', 'inherited_orders', 'inactive_orders']:
            native('CREATE TABLE `' + schema + '`.`' + name + '` (id INT)')
            native('INSERT INTO `' + schema + '`.`' + name + '` VALUES (1)')
        native('CREATE USER %s@%s IDENTIFIED BY %s', (username, '%', password))
        for role, host in zip(roles, ['localhost', '%', '%']):
            native('CREATE ROLE %s@%s', (role, host))
        native('GRANT SELECT ON `' + schema + '`.direct_orders TO %s@%s', (username, '%'))
        for role, host, table in zip(roles, ['localhost', '%', '%'], ['role_orders', 'inherited_orders', 'inactive_orders']):
            native('GRANT SELECT ON `' + schema + '`.`' + table + '` TO %s@%s', (role, host))
        native('GRANT %s@%s TO %s@%s', (roles[1], '%', roles[0], 'localhost'))
        native('GRANT %s@%s, %s@%s TO %s@%s', (roles[0], 'localhost', roles[2], '%', username, '%'))
        native('SET DEFAULT ROLE %s@%s TO %s@%s', (roles[0], 'localhost', username, '%'))
        user = request('POST', '/v1/users', {
            'username': username, 'nickname': '原生回读验收', 'password': password,
            'role': 'user', 'enabled': True,
        })
        state['user_id'] = user['id']
        persist()
        request('POST', prefix + f"/members/USER/{user['id']}/account", {
            'username': username, 'password': password, 'expectedRevision': 0,
        })
        state['bound'] = True
        persist()
        result = request('GET', prefix + f"/members/USER/{user['id']}/inspect")
        grants = '\n'.join(result['grants'])
        check('direct privilege returned', 'direct_orders' in grants)
        check('enabled role with non-percent host expanded', 'role_orders' in grants)
        check('nested enabled role expanded', 'inherited_orders' in grants)
        check('inactive role privilege not represented as active', 'inactive_orders' not in grants)
        with pymysql.connect(host=source['feHost'], port=source['fePort'], user=username,
                             password=password, autocommit=True) as member:
            with member.cursor() as cursor:
                cursor.execute('SELECT CURRENT_USER()')
                check('verified identity matches native session', result['nativeIdentity'] == "'" + cursor.fetchone()[0].replace('@', "'@'") + "'")
                cursor.execute('SELECT ROLE_NAME, ROLE_HOST FROM information_schema.ENABLED_ROLES ORDER BY ROLE_NAME, ROLE_HOST')
                enabled = cursor.fetchall()
                check('only default role is active, preserving its non-percent host', (roles[0], 'localhost') in enabled and (roles[2], '%') not in enabled)
                sql = 'SHOW GRANTS FOR CURRENT_USER USING ' + ', '.join("'" + name + "'@'" + host + "'" for name, host in enabled)
                cursor.execute(sql)
                expected = [row[0] for row in cursor.fetchall()]
                check('API native grants equal direct SHOW GRANTS result', len(expected) == len(result['grants']) and all(any(grant in row for row in result['grants']) for grant in expected))
                for table in ['direct_orders', 'role_orders', 'inherited_orders']:
                    cursor.execute('SELECT COUNT(*) FROM `' + schema + '`.`' + table + '`')
                    check('query succeeds for ' + table, cursor.fetchone()[0] == 1)
                denied = False
                try:
                    cursor.execute('SELECT * FROM `' + schema + '`.inactive_orders')
                except pymysql.MySQLError as error:
                    denied = error.args[0] == 1142
                check('inactive role query denied by MySQL', denied)
                denied = False
                try:
                    cursor.execute('SELECT * FROM mysql.role_edges')
                except pymysql.MySQLError as error:
                    denied = error.args[0] == 1142
                check('inspection works without mysql.role_edges privilege', denied)
        native('DROP USER %s@%s', (username, '%'))
        status, failed = request('GET', prefix + f"/members/USER/{user['id']}/inspect", success=False)
        check('invalid member login reports stage and native code, no fallback', status == 400 and '连接成员数据库账号失败' in failed.get('message', '') and '1045' in failed.get('message', ''))
        cleanup()
        check('existing source members and configuration unchanged', json.dumps(request('GET', prefix), sort_keys=True) == baseline)
        check('native fixtures removed', not native('SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s', (schema,)) and not native('SELECT User FROM mysql.user WHERE User IN (%s,%s,%s,%s)', (username, *roles)))
        print('ACCEPTANCE CHECKS', len(state['checks']), flush=True)
finally:
    client.close()
    dba.close()
