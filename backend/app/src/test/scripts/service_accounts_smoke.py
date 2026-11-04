"""Real USER/SERVICE + native MySQL + HTTP MCP acceptance.

Use a prepared local runtime; this script never builds or restarts services.
--credentials supplies portal_url, admin_username/password, mysql_host/port/user/
password, source_host/port. --backend-env supplies the running backend's
SPRING_DATASOURCE_URL for guarded local assertions.
Credentials and state must remain outside the repository. --keep retains only
named disposable fixtures for browser verification; --cleanup is rerunnable.
Run with Python >=3.10 and httpx, pymysql, mcp installed.
"""
import argparse
import asyncio
import hashlib
import json
import re
import secrets
from pathlib import Path
from urllib.parse import urlparse

import httpx
import pymysql
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client


class Smoke:
    def __init__(self, args):
        self.args = args
        self.config = json.loads(args.credentials.read_text())
        self.env = json.loads(args.backend_env.read_text())
        self.state = json.loads(args.state.read_text()) if args.cleanup or args.verify_only else {
            'suffix': secrets.token_hex(3), 'roles': [], 'users': [],
            'members': [], 'schemas': [], 'native_users': [], 'checks': []}
        if not args.cleanup and not args.verify_only and args.state.exists():
            raise RuntimeError('State already exists; clean the recorded fixtures before rerun')
        # Direct expiry/storage assertions are allowed only against a local runtime.
        assert urlparse(self.config['portal_url']).hostname in ('localhost', '127.0.0.1')
        jdbc = self.env['SPRING_DATASOURCE_URL']
        parsed = urlparse(jdbc.removeprefix('jdbc:'))
        assert parsed.hostname in ('localhost', '127.0.0.1')
        self.platform_db = parsed.path.lstrip('/')
        assert re.fullmatch(r'[A-Za-z0-9_]+', self.platform_db)
        self.admin = httpx.Client(base_url=self.config['portal_url'], timeout=60)
        self.api('POST', '/auth/login', {'username': self.config['admin_username'], 'password': self.config['admin_password']})
        self.dba = pymysql.connect(host=self.config['mysql_host'], port=self.config['mysql_port'],
                                  user=self.config['mysql_user'], password=self.config['mysql_password'],
                                  autocommit=True, cursorclass=pymysql.cursors.DictCursor)

    def save(self):
        self.args.state.touch(mode=0o600, exist_ok=True)
        self.args.state.chmod(0o600)
        self.args.state.write_text(json.dumps(self.state, ensure_ascii=False, indent=2))

    def check(self, label, condition=True):
        assert condition, label
        if label not in self.state['checks']:
            self.state['checks'].append(label)
        self.save()
        print('PASS', label, flush=True)

    def api(self, method, path, body=None, status=200, client=None, headers=None):
        r = (client or self.admin).request(method, path, json=body, headers=headers)
        assert r.status_code == status, (method, path, r.status_code, r.json().get('message'))
        j = r.json()
        if status == 200:
            assert j.get('code') == 200, (method, path, j.get('message'))
            return j.get('data')
        return j

    def native(self, sql, params=None):
        with self.dba.cursor() as cursor:
            cursor.execute(sql, params)
            return cursor.fetchall() if cursor.description else cursor.rowcount

    @property
    def base(self):
        return f"/v1/doris-clusters/{self.state['source']}/permissions"

    def overview(self):
        return self.api('GET', self.base)

    def member(self, kind, subject):
        return next((m for m in self.overview()['members'] if m['subjectType'] == kind and str(m['subjectId']) == str(subject)), None)

    def assign(self, kind, subject, roles):
        old = self.member(kind, subject)
        result = self.api('PUT', self.base + f'/members/{kind}/{subject}',
                          {'roleIds': [r['id'] for r in roles], 'expectedRevision': old['revision'] if old else 0})
        pair = [kind, str(subject)]
        if pair not in self.state['members']:
            self.state['members'].append(pair)
            self.save()
        return result

    def check_account(self, kind, user):
        member = self.member(kind, user['id'])
        assert member['status'] == 'ACTIVE'
        assert member['account']['managed'] and member['account']['configured']
        native_name = member['account']['username']
        if native_name not in self.state['native_users']:
            self.state['native_users'].append(native_name)
            self.save()

    def issue(self, user, name):
        r = self.admin.post(f"/v1/users/{user['id']}/tokens", json={'name': name})
        assert r.status_code == 200 and r.headers.get('cache-control') == 'no-store'
        result = r.json()['data']
        assert re.fullmatch(r'odw_sa_[A-Za-z0-9_-]{43}', result['token'])
        return result

    def initialize_http(self, token=None, extra=None, expected=200):
        headers = {'Accept': 'application/json, text/event-stream'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        headers.update(extra or {})
        r = httpx.post(self.args.mcp_url, headers=headers, timeout=30, json={
            'jsonrpc': '2.0', 'id': 1, 'method': 'initialize',
            'params': {'protocolVersion': '2024-11-05', 'capabilities': {},
                       'clientInfo': {'name': 'service-account-smoke', 'version': '1'}}})
        assert r.status_code == expected, ('MCP authentication status', r.status_code, expected)

    async def query(self, token, database, sql, allowed=True, human=None, spoof=None, rows=None):
        headers = {'Authorization': 'Bearer ' + token}
        if human:
            headers['X-Agent-User-Id'] = human
        if spoof:
            headers['X-Agent-Id'] = str(spoof)
            headers['X-Agent-Operator'] = 'admin'
        async with streamablehttp_client(self.args.mcp_url, headers=headers) as (read, write, _):
            async with ClientSession(read, write) as session:
                await session.initialize()
                result = await session.call_tool('odw_query_readonly', {
                    'database': database, 'sql': sql, 'preferredEngine': 'mysql'})
                assert bool(result.isError) != allowed, (database, 'unexpected query permission result')
                if rows is not None:
                    assert result.structuredContent['rows'] == rows, 'Unexpected native result'

    def setup(self):
        s = self.state
        for letter in ('a', 'b', 'denied'):
            db = 'odwsvc' + letter + s['suffix']
            s[letter] = db
            s['schemas'].append(db)
            self.save()
            self.native('CREATE DATABASE `' + db + '`')
            self.native('CREATE TABLE `' + db + '`.orders(id INT PRIMARY KEY, amount DECIMAL(12,2))')
            self.native('INSERT INTO `' + db + '`.orders VALUES (1,10),(2,20)')
        source = self.api('POST', '/v1/doris-clusters', {
            'clusterName': '服务账号验收_' + s['suffix'], 'sourceType': 'MYSQL',
            'feHost': self.config['source_host'], 'fePort': self.config['source_port'],
            'username': self.config['mysql_user'], 'password': self.config['mysql_password'],
            'isDefault': 0, 'autoSync': 0, 'status': 'active'})
        s['source'] = source['id']
        self.save()
        self.api('PUT', self.base + '/management', {'username': self.config['mysql_user'], 'password': self.config['mysql_password']})
        for letter in ('a', 'b'):
            role = self.api('POST', self.base + '/roles', {'name': letter.upper() + '只读', 'template': 'READONLY'})
            s['roles'].append(role)
            self.save()
            preview = self.api('POST', self.base + f"/roles/{role['id']}/preview", {
                'expectedRevision': role['revision'], 'grants': [
                    {'resourceType': 'SCHEMA', 'database': s[letter], 'actions': ['QUERY']}]})
            op = self.api('POST', self.base + f"/roles/{role['id']}/apply", {'previewId': preview['previewId']})
            assert op['state'] == 'SUCCESS'
        password = secrets.token_urlsafe(18)
        user = self.api('POST', '/v1/users', {'accountType': 'USER', 'username': 'odwsvcuser' + s['suffix'],
                         'nickname': '服务验收用户', 'password': password, 'enabled': True})
        user['password'] = password
        s['users'].append(user)
        for name in ('服务一', '服务二'):
            service = self.api('POST', '/v1/users', {'accountType': 'SERVICE', 'nickname': name + s['suffix'], 'description': '临时验收', 'enabled': True})
            assert service['role'] == 'user' and not service.get('passwordHash')
            s['users'].append(service)
        self.save()
        human, service, other = s['users'][:3]
        self.assign('USER', human['id'], [s['roles'][0]])
        self.check_account('USER', human)
        self.assign('SERVICE', service['id'], s['roles'])
        self.check_account('SERVICE', service)
        self.assign('SERVICE', other['id'], [s['roles'][1]])
        self.check_account('SERVICE', other)
        for letter in ('a', 'b'):
            result = self.api('POST', f"/v1/tables/sync-metadata/database/{s[letter]}?clusterId={s['source']}")
            assert str(result.get('status')).upper() in ('SUCCESS', 'COMPLETED')
        self.check('independent USER and SERVICE native accounts and roles active; metadata collected')

    async def verify(self):
        s = self.state
        human, service, other = s['users'][:3]
        with httpx.Client(base_url=self.config['portal_url'], timeout=30) as client:
            self.api('POST', '/auth/login', {'username': human['username'], 'password': human['password']}, client=client)
            self.check('ordinary human logs in', self.api('GET', '/auth/me', client=client)['role'] == 'user')
            result = self.api('POST', '/v1/data-query/execute', {'clusterId': s['source'], 'database': s['a'],
                              'sql': 'SELECT COUNT(*) AS n FROM orders', 'limit': 20}, client=client)
            self.check('ordinary human query uses own native permissions', result['rows'] == [{'n': 2}])
            self.api('GET', f"/v1/users/{service['id']}/tokens", status=403, client=client)
            self.check('ordinary human cannot manage service tokens')
        with httpx.Client(base_url=self.config['portal_url'], timeout=30) as client:
            rejected = client.post('/auth/login', json={'username': service['username'], 'password': human['password']})
            self.check('service account cannot password login', rejected.status_code == 200 and rejected.json()['code'] == 400 and not rejected.cookies)
        for body in ({'password': human['password']}, {'role': 'admin'}, {'accountType': 'USER'}):
            self.api('PATCH', f"/v1/users/{service['id']}", body, status=400)
        self.api('POST', f"/v1/users/{service['id']}/reset-password", {'password': human['password']}, status=400)
        self.api('POST', f"/v1/users/{human['id']}/tokens", {'name': 'invalid'}, status=400)
        self.check('service has no password/admin/type conversion; human has no service token')
        for kind, sid in [('SERVICE', human['id']), ('USER', service['id']), ('SERVICE', 999999999), ('AGENT', 'external_bot')]:
            self.api('PUT', self.base + f'/members/{kind}/{sid}', {'roleIds': [s['roles'][0]['id']], 'expectedRevision': 0}, status=400)
        self.check('source membership rejects wrong type, nonexistent account and external agent ID')
        write_role = next((r for r in s['roles'] if r['template'] == 'READWRITE'), None)
        if write_role is None:
            write_role = self.api('POST', self.base + '/roles', {'name': '写入验证', 'template': 'READWRITE'})
            s['roles'].append(write_role)
            self.save()
            preview = self.api('POST', self.base + f"/roles/{write_role['id']}/preview", {
                'expectedRevision': write_role['revision'], 'grants': [
                    {'resourceType': 'TABLE', 'database': s['a'], 'tableName': 'orders', 'actions': ['WRITE']}]})
            op = self.api('POST', self.base + f"/roles/{write_role['id']}/apply", {'previewId': preview['previewId']})
            assert op['state'] == 'SUCCESS'
        previous_roles=self.member('SERVICE',service['id'])['roleIds']
        self.api('PUT', self.base + f"/members/SERVICE/{service['id']}", {
            'roleIds': [write_role['id']], 'expectedRevision': self.member('SERVICE',service['id'])['revision']})
        self.check('SERVICE may receive an explicitly authorized native write role')
        self.api('PUT', self.base + f"/members/SERVICE/{service['id']}", {
            'roleIds': previous_roles, 'expectedRevision': self.member('SERVICE',service['id'])['revision']})
        t1, t2, t_other, expired = self.issue(service, '客户端一'), self.issue(service, '客户端二'), self.issue(other, '另一个主体'), self.issue(service, '到期样本')
        s['tokens'] = [t1, t2, t_other, expired]
        self.save()
        table = '`' + self.platform_db + '`.service_account_token'
        stored = self.native('SELECT token_hash,TIMESTAMPDIFF(DAY,UTC_TIMESTAMP(),expires_at) AS days FROM ' + table + ' WHERE id=%s AND user_id=%s', (t1['id'], service['id']))[0]
        self.check('only SHA256 stored, default expiry 90 days, plaintext creation is no-store',
                   stored['token_hash'] == hashlib.sha256(t1['token'].encode()).hexdigest() and stored['days'] in (89, 90) and t1['expiresAt'].endswith('Z') and t1['createdAt'].endswith('Z'))
        listed = self.api('GET', f"/v1/users/{service['id']}/tokens")
        self.check('token lists expose metadata only', all('token' not in r and 'token_hash' not in r and 'tokenHash' not in r for r in listed))
        for days in (0, 366):
            self.api('POST', f"/v1/users/{service['id']}/tokens", {'name': 'invalid', 'expiresInDays': days}, status=400)
        self.check('token invalid expiry rejected')
        self.api('DELETE', f"/v1/users/{other['id']}/tokens/{t1['id']}", status=400)
        self.check('cannot revoke another account token through mismatched owner')
        sql = 'SELECT COUNT(*) AS n,SUM(amount) AS total FROM orders'
        for letter in ('a', 'b'):
            await self.query(t1['token'], s[letter], sql, rows=[{'n': 2, 'total': 30.0}])
        self.check('service-only bearer queries both schemas without human header')
        await self.query(t1['token'], s['a'], f"SELECT COUNT(*) AS n FROM `{s['a']}`.orders a JOIN `{s['b']}`.orders b ON a.id=b.id", rows=[{'n': 2}])
        self.check('one service native login combines multiple roles in cross-schema JOIN')
        await self.query(t1['token'], s['denied'], sql, allowed=False)
        await self.query(t1['token'], s['a'], 'DELETE FROM orders', allowed=False)
        self.check('service rejects unauthorized schema and non-read SQL')
        await self.query(t1['token'], s['a'], sql, human='local:' + human['username'], rows=[{'n': 2, 'total': 30.0}])
        await self.query(t1['token'], s['b'], sql, human='local:' + human['username'], allowed=False)
        await self.query(t1['token'], s['a'], sql, human='local:missing' + s['suffix'], allowed=False)
        self.check('explicit human header intersects native permissions; invalid human never falls back')
        await self.query(t_other['token'], s['b'], sql, spoof=service['id'], rows=[{'n': 2, 'total': 30.0}])
        await self.query(t_other['token'], s['a'], sql, spoof=service['id'], allowed=False)
        self.check('caller agent/operator headers cannot change bearer service identity')
        last = self.native('SELECT last_used_at FROM ' + table + ' WHERE id=%s', (t1['id'],))[0]
        self.check('real MCP use updates token last-used timestamp', last['last_used_at'] is not None)
        self.native('UPDATE ' + table + ' SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=%s AND user_id=%s', (expired['id'], service['id']))
        self.initialize_http(expired['token'], expected=401)
        self.check('expired token denied before MCP initialize')
        self.api('DELETE', f"/v1/users/{service['id']}/tokens/{t1['id']}")
        self.initialize_http(t1['token'], expected=401)
        self.initialize_http(t2['token'])
        self.check('revoking one token denies next request and preserves sibling token')
        self.api('PATCH', f"/v1/users/{service['id']}", {'enabled': False})
        self.initialize_http(t2['token'], expected=401)
        self.api('POST', f"/v1/users/{service['id']}/tokens", {'name': 'disabled'}, status=400)
        self.check('disabled service denies every token and cannot issue new token')
        self.api('PATCH', f"/v1/users/{service['id']}", {'enabled': True})
        self.initialize_http(t1['token'], expected=401)
        self.initialize_http(t2['token'])
        self.check('re-enabled service preserves valid token while revoked token stays revoked')
        self.initialize_http(expected=401)
        self.initialize_http('not-a-platform-token', expected=401)
        self.initialize_http(extra={'X-Agent-Service-Token': 'retired-shared-token'}, expected=401)
        self.check('missing/malformed/old shared frontdoor credentials denied')
        headers={'Authorization':'Bearer '+t2['token'],'Accept':'application/json, text/event-stream'}
        response=httpx.post(self.args.mcp_url,headers=headers,json={
            'jsonrpc':'2.0','id':2,'method':'tools/list','params':{}},timeout=30)
        self.check('authenticated service sees all 23 built-in tools',response.status_code==200 and len(response.json()['result']['tools'])==23)
        response=httpx.get(self.config['portal_url']+'/v1/users',headers={'Authorization':headers['Authorization']},timeout=30)
        self.check('service token cannot login to ordinary browser/admin API',response.status_code==401)
        # A third unbound SERVICE proves missing mapping has no manager fallback.
        unbound = self.api('POST', '/v1/users', {'accountType': 'SERVICE', 'nickname': '未绑定' + s['suffix'], 'enabled': True})
        s['users'].append(unbound)
        self.save()
        ut = self.issue(unbound, '未绑定')
        await self.query(ut['token'], s['a'], sql, allowed=False)
        self.api('DELETE', f"/v1/users/{unbound['id']}")
        s['deleted_users'] = [unbound['id']]
        self.save()
        self.initialize_http(ut['token'], expected=401)
        self.check('missing source mapping fails closed; deleting service invalidates token')
        s['accepted'] = True
        self.save()
        print('ACCEPTANCE CHECKS', len(s['checks']), flush=True)

    def cleanup(self):
        s = self.state
        if s.get('source'):
            table = '`' + self.platform_db + '`.'
            sources = self.native('SELECT cluster_name FROM ' + table + 'doris_cluster WHERE id=%s', (s['source'],))
            assert sources and sources[0]['cluster_name'] == '服务账号验收_' + s['suffix']
            rows = self.native('SELECT id,db_name,table_name FROM ' + table + 'data_table WHERE cluster_id=%s AND deleted=0', (s['source'],))
            for row in rows:
                assert row['db_name'] in s['schemas'], 'Unexpected non-fixture metadata on test source'
                self.api('DELETE', f"/v1/tables/{row['id']}?confirmTableName={row['table_name']}")
            self.native('UPDATE ' + table + 'data_field f JOIN ' + table + 'data_table t ON f.table_id=t.id SET f.deleted=1 WHERE t.cluster_id=%s', (s['source'],))
        if s.get('source') and not s.get('source_removed'):
            for kind, sid in list(reversed(s['members'])):
                if self.member(kind, sid):
                    self.api('DELETE', self.base + f'/members/{kind}/{sid}')
                s['members'].remove([kind, sid])
                self.save()
            for role in list(reversed(s['roles'])):
                if any(r['id'] == role['id'] for r in self.overview()['roles']):
                    op = self.api('DELETE', self.base + f"/roles/{role['id']}")
                    assert op['state'] == 'SUCCESS', 'Native role cleanup incomplete'
                s['roles'].remove(role)
                self.save()
            fixture = self.api('GET', f"/v1/doris-clusters/{s['source']}")
            fixture['feHost'] = 'removed-' + s['suffix'] + '.invalid'
            self.api('PUT', f"/v1/doris-clusters/{s['source']}", fixture)
            self.api('DELETE', f"/v1/doris-clusters/{s['source']}")
            s['source_removed'] = True
            self.save()
        visible_ids = {u['id'] for u in self.api('GET', '/v1/users')}
        for user in list(s['users']):
            if user['id'] in visible_ids:
                self.api('DELETE', f"/v1/users/{user['id']}")
            s['users'].remove(user)
            self.save()
        for name in s['native_users']:
            assert name.startswith('odwsvc') and name.endswith(s['suffix'])
            self.native('DROP USER IF EXISTS %s@%s', (name, '%'))
        for db in s['schemas']:
            assert db.startswith('odwsvc') and db.endswith(s['suffix'])
            self.native('DROP DATABASE IF EXISTS `' + db + '`')
        if s.get('source'):
            self.native('DELETE FROM `' + self.platform_db + '`.datasource_permission_manager WHERE source_id=%s', (s['source'],))
        s['tokens'] = []  # Expired/revoked digests remain as audit records; discard plaintext fixture state.
        s['cleaned'] = True
        self.save()
        print('CLEANUP complete', flush=True)

    def close(self):
        self.admin.close()
        self.dba.close()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--credentials', type=Path, required=True)
    p.add_argument('--backend-env', type=Path, required=True)
    p.add_argument('--state', type=Path, required=True)
    p.add_argument('--mcp-url', default='http://127.0.0.1:8082/api/mcp/')
    p.add_argument('--keep', action='store_true')
    p.add_argument('--cleanup', action='store_true')
    p.add_argument('--verify-only', action='store_true', help='Resume verification against kept fixtures')
    args = p.parse_args()
    smoke = Smoke(args)
    try:
        if args.cleanup:
            smoke.cleanup()
        else:
            if not args.verify_only:
                smoke.setup()
            asyncio.run(smoke.verify())
            if not args.keep:
                smoke.cleanup()
    except BaseException:
        smoke.save()
        print('Fixture state retained privately:', args.state, flush=True)
        raise
    finally:
        smoke.close()


if __name__ == '__main__':
    main()
