"""Real MySQL acceptance for automatic native login provisioning.

Uses a disposable source prepared by service_accounts_smoke.py --keep. Inputs
and fixture state stay outside the repository; never logs generated passwords.
--key-file is the private JSON runtime environment containing ODW_CREDENTIAL_KEY.
"""
import argparse
import base64
import json
import secrets
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
import pymysql
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from service_accounts_smoke import Smoke


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('credentials', 'backend-env', 'state', 'key-file'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    args.cleanup, args.verify_only = False, True
    smoke = Smoke(args)
    source = smoke.state['source']
    suffix = secrets.token_hex(4)
    schemas, roles, users = [], [], []
    external = None
    checks = []
    key = base64.b64decode(json.loads(args.key_file.read_text())['ODW_CREDENTIAL_KEY'])
    config = smoke.config

    def check(label, condition=True):
        assert condition, label
        checks.append(label)
        print('PASS', label, flush=True)

    def native(sql, params=None):
        return smoke.native(sql, params)

    def mapping(user):
        rows = native('SELECT username,password,managed FROM `' + smoke.platform_db
                      + '`.datasource_subject_account WHERE source_id=%s AND subject_type=%s AND subject_id=%s',
                      (source, user['accountType'], str(user['id'])))
        return rows[0] if rows else None

    def member(user):
        return smoke.member(user['accountType'], user['id'])

    def assign(user, selected, expected=None):
        old = member(user)
        return smoke.api('PUT', smoke.base + f"/members/{user['accountType']}/{user['id']}", {
            'roleIds': [r['id'] for r in selected],
            'expectedRevision': (old['revision'] if old else 0) if expected is None else expected})

    def remove(user):
        smoke.api('DELETE', smoke.base + f"/members/{user['accountType']}/{user['id']}")

    def make_user(kind):
        body = {'accountType': kind, 'nickname': '自动建号验收' + suffix, 'enabled': True}
        if kind == 'USER':
            body.update(username='auto' + secrets.token_hex(5), password='Aa1!' + secrets.token_urlsafe(20))
        user = smoke.api('POST', '/v1/users', body)
        user['accountType'] = kind
        users.append(user)
        return user

    def plaintext(row):
        assert row['managed'] == 1 and row['password'].startswith('v1:')
        blob = base64.b64decode(row['password'][3:])
        return AESGCM(key).decrypt(blob[:12], blob[12:], None).decode()

    def connect(row, password=None):
        return pymysql.connect(host=config['mysql_host'], port=config['mysql_port'],
                               user=row['username'], password=password or plaintext(row), autocommit=True)

    def count(row, database, allowed=True):
        with connect(row) as c:
            try:
                with c.cursor() as cursor:
                    cursor.execute('SELECT COUNT(*) FROM `' + database + '`.orders')
                    result = cursor.fetchone()[0]
            except pymysql.err.OperationalError as error:
                assert not allowed and error.args[0] in (1044, 1142), error.args[0]
            else:
                assert allowed and result == 2

    def manager(username, password):
        smoke.api('PUT', smoke.base + '/management', {'username': username, 'password': password})

    try:
        assert smoke.overview()['management'] == {'username': config['mysql_user'], 'configured': True}, 'Use the prepared disposable smoke source'
        for letter in ('a', 'b'):
            database = 'odwauto' + suffix + letter
            native('CREATE DATABASE `' + database + '`')
            schemas.append(database)
            native('CREATE TABLE `' + database + '`.orders(id INTEGER PRIMARY KEY)')
            native('INSERT INTO `' + database + '`.orders VALUES (1),(2)')
            synced = smoke.api('POST', f'/v1/tables/sync-metadata/database/{database}?clusterId={source}')
            assert str(synced.get('status')).upper() in ('SUCCESS', 'COMPLETED')
            role = smoke.api('POST', smoke.base + '/roles', {'name': 'auto-' + suffix + letter, 'template': 'READONLY'})
            roles.append(role)
            preview = smoke.api('POST', smoke.base + f"/roles/{role['id']}/preview", {
                'expectedRevision': role['revision'],
                'grants': [{'resourceType': 'SCHEMA', 'database': database, 'actions': ['QUERY']}]})
            applied = smoke.api('POST', smoke.base + f"/roles/{role['id']}/apply", {'previewId': preview['previewId']})
            assert applied['state'] == 'SUCCESS'
        human, service = make_user('USER'), make_user('SERVICE')
        for user in (human, service):
            assigned = assign(user, roles[:1])
            row = mapping(user)
            check(user['accountType'] + ' first assignment creates and verifies one encrypted native login',
                  assigned['status'] == 'ACTIVE' and assigned['account']['managed']
                  and assigned['account']['username'] == row['username'] and len(row['username']) <= 32
                  and 'password' not in json.dumps(assigned))
            count(row, schemas[0])
            count(row, schemas[1], allowed=False)
            before = row.copy()
            assign(user, roles)
            row = mapping(user)
            check(user['accountType'] + ' second role reuses the same username and encrypted credential', row == before)
            count(row, schemas[1])
            assign(user, roles[:1])
            count(row, schemas[1], allowed=False)
            check(user['accountType'] + ' revoking a role takes effect on a fresh native connection')
            denied = smoke.admin.post(smoke.base + f"/members/{user['accountType']}/{user['id']}/account",
                                      json={'username': 'replacement', 'password': 'not-used',
                                            'expectedRevision': member(user)['revision']})
            check('managed login cannot be manually replaced', denied.status_code == 400 and mapping(user) == before)
        token = smoke.issue(service, 'auto-login-smoke')
        result = httpx.post(config['portal_url'] + '/mcp/', timeout=30,
                            headers={'Authorization': 'Bearer ' + token['token'],
                                     'Accept': 'application/json, text/event-stream'},
                            json={'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call', 'params': {
                                'name': 'odw_query_readonly', 'arguments': {
                                    'database': schemas[0], 'preferredEngine': 'mysql',
                                    'sql': 'SELECT COUNT(*) AS n FROM orders'}}}).json()['result']
        check('real MCP query decrypts the automatically provisioned SERVICE credential',
              not result.get('isError') and result['structuredContent']['rows'] == [{'n': 2}])
        raw = plaintext(mapping(service))
        operations = json.dumps(smoke.api('GET', smoke.base + '/operations'), ensure_ascii=False)
        check('operation records contain no generated password or secret-bearing CREATE statement',
              raw not in operations and 'IDENTIFIED BY' not in operations and '密码已隐藏' in operations)
        retry_user = make_user('USER')
        manager('opendataworks', 'opendataworks123')
        try:
            failed = smoke.admin.put(smoke.base + f"/members/USER/{retry_user['id']}",
                                     json={'roleIds': [roles[0]['id']], 'expectedRevision': 0})
            assert failed.status_code == 400
            staged = mapping(retry_user)
            check('denied provisioning retains an encrypted ERROR member and actionable message',
                  staged['managed'] == 1 and staged['password'].startswith('v1:')
                  and member(retry_user)['status'] == 'ERROR' and '授权管理连接' in failed.json()['message'])
        finally:
            manager(config['mysql_user'], config['mysql_password'])
        assign(retry_user, roles[:1])
        check('explicit retry reuses the staged identity and password', mapping(retry_user) == staged)
        count(mapping(retry_user), schemas[0])
        collision = make_user('USER')
        manager('opendataworks', 'opendataworks123')
        try:
            denied = smoke.admin.put(smoke.base + f"/members/USER/{collision['id']}",
                                     json={'roleIds': [roles[0]['id']], 'expectedRevision': 0})
            assert denied.status_code == 400
            collision_row = mapping(collision)
        finally:
            manager(config['mysql_user'], config['mysql_password'])
        external = collision_row['username']
        foreign_password = 'Aa1!' + secrets.token_urlsafe(24)
        native('CREATE USER %s@%s IDENTIFIED BY %s', (external, '%', foreign_password))
        denied = smoke.admin.put(smoke.base + f"/members/USER/{collision['id']}", json={
            'roleIds': [roles[0]['id']], 'expectedRevision': member(collision)['revision']})
        check('retry never adopts or resets a colliding external login', denied.status_code == 400)
        denied = smoke.admin.delete(smoke.base + f"/members/USER/{collision['id']}")
        with connect(collision_row, foreign_password) as c:
            with c.cursor() as cursor: cursor.execute('SELECT 1')
        check('removal cannot delete a colliding login without ownership proof', denied.status_code == 400)
        native('DROP USER %s@%s', (external, '%'))
        external = None
        remove(collision)
        concurrent = make_user('USER')
        def first_assign(_):
            return smoke.admin.put(smoke.base + f"/members/USER/{concurrent['id']}", json={
                'roleIds': [roles[0]['id']], 'expectedRevision': 0}).status_code
        with ThreadPoolExecutor(max_workers=2) as executor:
            statuses = sorted(executor.map(first_assign, range(2)))
        check('concurrent first assignments produce one account and one revision conflict', statuses == [200, 409])
        legacy = smoke.state['users'][0]
        legacy['accountType'] = 'USER'
        original = mapping(legacy)
        assert original['managed'] == 0
        old_roles = member(legacy)['roleIds']
        smoke.api('PUT', smoke.base + f"/members/USER/{legacy['id']}", {
            'roleIds': old_roles, 'expectedRevision': member(legacy)['revision']})
        check('existing DBA-managed mapping and password remain unchanged', mapping(legacy) == original)
        for user in (human, service, retry_user, concurrent):
            username = mapping(user)['username']
            remove(user)
            check('removing a managed member drops only its owned native login',
                  not native('SELECT User FROM mysql.user WHERE User=%s AND Host=%s', (username, '%'))
                  and mapping(user) is None)
        print('ACCEPTANCE CHECKS', len(checks), flush=True)
    finally:
        manager(config['mysql_user'], config['mysql_password'])
        if external:
            native('DROP USER IF EXISTS %s@%s', (external, '%'))
        for user in users:
            if member(user): remove(user)
            smoke.api('DELETE', '/v1/users/' + str(user['id']))
        for role in reversed(roles):
            op = smoke.api('DELETE', smoke.base + '/roles/' + str(role['id']))
            assert op['state'] == 'SUCCESS'
        for database in schemas:
            native('DROP DATABASE IF EXISTS `' + database + '`')
            native('UPDATE `' + smoke.platform_db + '`.data_table SET deleted=1 WHERE cluster_id=%s AND db_name=%s',
                   (source, database))
        smoke.close()


if __name__ == '__main__':
    main()
