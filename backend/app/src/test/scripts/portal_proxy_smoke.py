"""Verify portal Nginx blocks MCP and explicit HTTPS origins support TLS offload.

Start the official backend image, mounted deploy Nginx config and a test TLS
terminator outside this script. Pass private --credentials and the TLS --ca-file.
Only authenticates as the prepared local administrator; no grants are modified.
"""
import argparse
import json
import ssl
from pathlib import Path
from urllib.parse import urlparse

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--credentials', type=Path, required=True)
    parser.add_argument('--http-url', default='http://127.0.0.1:18081')
    parser.add_argument('--https-url', default='https://127.0.0.1:18084')
    parser.add_argument('--backend-url', default='http://127.0.0.1:18082')
    parser.add_argument('--ca-file', type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.credentials.read_text())
    checks = []

    def check(label, condition):
        assert condition, label
        checks.append(label)
        print('PASS', label, flush=True)

    for base in (args.http_url, args.https_url, args.backend_url):
        assert urlparse(base).hostname in ('127.0.0.1', 'localhost')
    trust = ssl.create_default_context(cafile=str(args.ca_file))
    for base in (args.http_url, args.https_url):
        with httpx.Client(base_url=base, verify=trust, timeout=30) as client:
            check(base + ' serves production frontend', client.get('/').status_code == 200)
            check(base + ' proxies backend health', client.get('/api/v1/health').status_code == 200)
            login = client.post('/api/auth/login', headers={'Origin': base}, json={
                'username': config['admin_username'], 'password': config['admin_password']})
            check(base + ' supports browser login origin through HTTP upstream', login.status_code == 200)
            for path in ('/api/mcp', '/api/mcp/', '/api/mcp/nested.js'):
                check(base + ' rejects portal MCP path ' + path,
                      client.post(path, json={'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list'}).status_code == 404)
            check(base + ' rejects matrix MCP path', client.post('/api/mcp;v=1', json={}).status_code == 400)
    response = httpx.post(args.backend_url + '/api/mcp/', timeout=30, json={})
    check('direct private backend MCP remains routed and requires service credentials', response.status_code == 401)
    print('ACCEPTANCE CHECKS', len(checks), flush=True)


if __name__ == '__main__':
    main()
