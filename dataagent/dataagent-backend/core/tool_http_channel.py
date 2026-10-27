"""Run-local HTTP tool channel keeps upstream service credentials out of child env."""
from __future__ import annotations

from contextvars import ContextVar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
import secrets
import threading

import httpx

from core.agent_identity import current_headers

_channel_url: ContextVar[str] = ContextVar("tool_channel_url", default="")


def channel_url() -> str:
    return _channel_url.get()


class ToolHttpChannel:
    def __enter__(self):
        base = str(os.getenv("ODW_BACKEND_BASE_URL") or "").rstrip("/")
        if base.endswith("/metadata"):
            base = base[:-9]
        upstream_headers = current_headers()
        upstream_headers[os.getenv("ODW_AGENT_SERVICE_TOKEN_HEADER_NAME", "X-Agent-Service-Token")] = str(
            os.getenv("ODW_AGENT_SERVICE_TOKEN") or "")
        prefix = "/" + secrets.token_urlsafe(32) + "/api/v1/ai"

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.forward()

            def do_POST(self):
                self.forward()

            def forward(self):
                suffix = self.path[len(prefix):] if self.path.startswith(prefix + "/") else ""
                if not (suffix.startswith("/metadata/") or suffix.split("?")[0] == "/query/read"):
                    self.send_error(404)
                    return
                if not base or not upstream_headers.get(os.getenv("ODW_AGENT_SERVICE_TOKEN_HEADER_NAME", "X-Agent-Service-Token")):
                    self.send_error(503, "Upstream query channel is not configured")
                    return
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                try:
                    with httpx.Client(timeout=180) as client:
                        response = client.request(self.command, base + suffix, content=body,
                                                  headers={**upstream_headers, "Content-Type": "application/json"})
                    self.send_response(response.status_code)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(response.content)))
                    self.end_headers()
                    self.wfile.write(response.content)
                except httpx.HTTPError:
                    self.send_error(502, "Upstream query channel is unavailable")

            def log_message(self, *_args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.token = _channel_url.set(f"http://127.0.0.1:{self.server.server_port}{prefix}")
        return self

    def __exit__(self, *_args):
        _channel_url.reset(self.token)
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
