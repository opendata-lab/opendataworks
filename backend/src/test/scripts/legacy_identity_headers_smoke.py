"""Read-only live regression for removal of obsolete agent identity headers.

Prepare Java and MCP externally. --integration supplies token/service_id from a
private existing integration; --backend-env supplies AGENT_API_SERVICE_TOKEN.
No accounts, tokens, grants, workflows or business rows are created or changed.
"""
import argparse
import asyncio
import json
import logging
from pathlib import Path

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

parser = argparse.ArgumentParser()
parser.add_argument('--integration', type=Path, required=True)
parser.add_argument('--backend-env', type=Path, required=True)
parser.add_argument('--backend-url', default='http://127.0.0.1:8082/api')
parser.add_argument('--mcp-url', default='http://127.0.0.1:8801/mcp/')
parser.add_argument('--database', required=True)
parser.add_argument('--human-id', required=True)
args = parser.parse_args()
identity = json.loads(args.integration.read_text())
environment = json.loads(args.backend_env.read_text())
headers = {'Authorization': 'Bearer ' + identity['token'],
           'X-Agent-Service-Token': environment['AGENT_API_SERVICE_TOKEN']}
spoofed = {'X-Agent-Id': 'forged-service', 'X-Agent-Operator': 'admin'}
payload = {'database': args.database, 'sql': 'SELECT COUNT(*) AS cnt FROM orders', 'limit': 1}
checks = []
logging.basicConfig(level=logging.ERROR)


def check(name, condition):
    assert condition, name
    checks.append(name)
    print('PASS', name, flush=True)


with httpx.Client(base_url=args.backend_url, timeout=30) as client:
    result = client.get('/v1/ai/auth/identity', headers=headers)
    check('Java identity uses existing service Token',
          result.status_code == 200 and str(result.json()['serviceAccountId']) == str(identity['service_id']))
    result = client.get('/v1/ai/auth/identity', headers={**headers, **spoofed})
    check('legacy headers cannot replace authenticated service',
          result.status_code == 200 and str(result.json()['serviceAccountId']) == str(identity['service_id']))
    for name, sent in [
        ('hop and old headers cannot replace Bearer', {'X-Agent-Service-Token': headers['X-Agent-Service-Token'], **spoofed}),
        ('Bearer and old headers cannot replace internal hop', {'Authorization': headers['Authorization'], **spoofed}),
    ]:
        check(name, client.get('/v1/ai/auth/identity', headers=sent).status_code == 401)
    result = client.post('/v1/ai/query/read', headers=headers, json=payload)
    check('Java standalone service query succeeds', result.status_code == 200 and result.json()['row_count'] == 1)
    expected = result.json()['rows']
    result = client.post('/v1/ai/query/read', headers={**headers, **spoofed}, json=payload)
    check('old headers do not change query result', result.status_code == 200 and result.json()['rows'] == expected)
    # Nonexistent IDs and invalid empty drafts also avoid side effects if a guard regresses.
    sentinel = '9223372036854775806'
    for method, path in [
        ('POST', '/task'), ('PUT', '/task/' + sentinel),
        ('POST', '/table'), ('POST', '/table/preview'),
        ('POST', '/workflow'), ('PUT', '/workflow/' + sentinel),
        ('POST', '/workflow/' + sentinel + '/publish'),
        ('PUT', '/workflow/' + sentinel + '/schedule'),
        ('POST', '/workflow/' + sentinel + '/schedule/online'),
        ('POST', '/workflow/' + sentinel + '/schedule/offline'),
        ('POST', '/metadata/complete'),
    ]:
        result = client.request(method, '/v1/ai' + path, headers={**headers, **spoofed}, json={})
        check(method + ' ' + path + ' remains forbidden', result.status_code == 403)


async def query(extra=None):
    sent = {'Authorization': headers['Authorization'], **(extra or {})}
    async with streamablehttp_client(args.mcp_url, headers=sent) as (read, write, _):
        async with ClientSession(read, write) as session:
            await session.initialize()
            result = await session.call_tool('odw_query_readonly', {'params': {
                'database': args.database, 'sql': payload['sql'], 'preferred_engine': 'mysql', 'limit': 1}})
            assert not result.isError, 'MCP read-only query failed'
            return result.structuredContent['rows']


async def verify_mcp():
    check('MCP standalone service query equals Java result', await query() == expected)
    check('MCP drops forged legacy identity headers', await query(spoofed) == expected)
    check('optional existing human intersection still queries', await query({'X-Agent-User-Id': args.human_id}) == expected)


asyncio.run(verify_mcp())
initialize = {'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {
    'protocolVersion': '2024-11-05', 'capabilities': {},
    'clientInfo': {'name': 'legacy-header-smoke', 'version': '1'}}}
for name, sent in [
    ('MCP old identity headers alone are rejected', spoofed),
    ('MCP invalid account Token is rejected', {'Authorization': 'Bearer odw_sa_' + 'a' * 43, **spoofed}),
]:
    result = httpx.post(args.mcp_url, headers={**sent, 'Accept': 'application/json, text/event-stream'},
                        json=initialize, timeout=30)
    check(name, result.status_code == 401)
print('ACCEPTANCE CHECKS', len(checks), flush=True)
