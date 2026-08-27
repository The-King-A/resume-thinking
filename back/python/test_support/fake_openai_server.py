"""Deterministic fake OpenAI-compatible server for tests only.

This helper deliberately never represents a production provider.
"""

from __future__ import annotations

import json
from typing import Any

from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Thread


class FakeOpenAIHandler(BaseHTTPRequestHandler):
    response: Any = {"requirements": [], "suggestions": [], "score": {"skills": 0, "projectExperience": 0, "workContent": 0, "educationExperience": 0, "softSkills": 0, "composite": 0}}

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(length)
        payload = self.response if isinstance(self.response, str) else json.dumps({"choices": [{"message": {"content": json.dumps(self.response)}}]})
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload.encode())))
        self.end_headers()
        self.wfile.write(payload.encode())

    def log_message(self, *_: Any) -> None:
        return


class FakeOpenAIServer:
    def __init__(self, response: Any = None):
        handler = type("ConfiguredFakeOpenAIHandler", (FakeOpenAIHandler,), {"response": response if response is not None else FakeOpenAIHandler.response})
        self.server = HTTPServer(("127.0.0.1", 0), handler)
        self.thread = Thread(target=self.server.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_port}"

    def start(self) -> "FakeOpenAIServer":
        self.thread.start()
        return self

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def __enter__(self) -> "FakeOpenAIServer":
        return self.start()

    def __exit__(self, *_: Any) -> None:
        self.close()
