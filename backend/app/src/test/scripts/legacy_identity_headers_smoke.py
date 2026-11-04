"""Verify obsolete headers cannot replace or change an authenticated MCP principal.

Read-only. --integration is a private JSON file with token/service_id fields.
Prepare backend and native grants externally; no runtime credentials are logged.
"""
import argparse
import json
from pathlib import Path
import httpx


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--integration', type=Path, required=True)
    parser.add_argument('--backend-url', default='http://127.0.0.1:8082/api')
    parser.add_argument('--mcp-url', default='http://127.0.0.1:8082/api/mcp/')
    parser.add_argument('--database', required=True)
    parser.add_argument('--human-id', required=True)
    args = parser.parse_args()
    identity = json.loads(args.integration.read_text())
    headers = {'Authorization': 'Bearer ' + identity['token'],
               'Accept': 'application/json, text/event-stream'}
    forged = {'X-Agent-Id': 'forged-service', 'X-Agent-Operator': 'admin'}
    checks = []

    def check(label, condition):
        assert condition, label
        checks.append(label)
        print('PASS', label, flush=True)

    def rpc(method, params, extra=None):
        return httpx.post(args.mcp_url, headers={**headers, **(extra or {})}, timeout=30,
                          json={'jsonrpc': '2.0', 'id': 1, 'method': method, 'params': params})

    tool_args = {'database': args.database, 'sql': 'SELECT COUNT(*) AS cnt FROM orders',
                 'preferredEngine': 'mysql', 'limit': 1}

    def query(extra=None):
        result = rpc('tools/call', {'name': 'odw_query_readonly', 'arguments': tool_args}, extra)
        assert result.status_code == 200
        body = result.json()['result']
        assert not body.get('isError'), 'read-only query rejected'
        return body['structuredContent']['rows']

    expected = query()
    check('obsolete identity headers cannot change the query result', query(forged) == expected)
    check('optional human header still narrows read permissions',
          query({'X-Agent-User-Id': args.human_id}) == expected)
    listing = rpc('tools/list', {}).json()['result']['tools']
    check('all 23 tools remain available', len(listing) == 23)
    response = httpx.get(args.backend_url + '/v1/users', headers=headers, timeout=30)
    check('service identity cannot enter browser administration', response.status_code == 401)
    for label, sent in [
        ('obsolete headers alone cannot authenticate', forged),
        ('invalid service token is rejected', {'Authorization': 'Bearer odw_sa_' + 'a' * 43, **forged}),
    ]:
        response = httpx.post(args.mcp_url, headers={'Accept': headers['Accept'], **sent}, timeout=30,
                              json={'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list', 'params': {}})
        check(label, response.status_code == 401)
    print('ACCEPTANCE CHECKS', len(checks), flush=True)


if __name__ == '__main__':
    main()
