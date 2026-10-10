"""Local HTTP integration tests: deliberately never contact a live AI provider."""
from __future__ import annotations

import json
import os
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dana_gateway import GatewayHandler


class GatewayHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.env = mock.patch.dict(os.environ, {
            "DANA_GATEWAY_TOKEN": "local-integration-test-token-0123456",
            "OPENAI_API_KEY": "offline-only-placeholder",
            "DANA_LLM_MODEL": "offline-only-model"
        })
        cls.env.start()
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), GatewayHandler)
        cls.server.daemon_threads = True
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=3)
        cls.env.stop()

    def url(self, path):
        return f"http://127.0.0.1:{self.port}{path}"

    def post(self, path, body, token=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        return Request(self.url(path), data=json.dumps(body).encode(), headers=headers, method="POST")

    def test_health_endpoint(self):
        with urlopen(self.url("/health"), timeout=2) as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(json.load(response), {"status": "ready"})

    def test_token_is_required(self):
        with self.assertRaises(HTTPError) as error:
            urlopen(self.post("/dana/intent", {"language": "fa", "text": "رضا رو بگیر"}), timeout=2)
        self.assertEqual(error.exception.code, 401)

    def test_rejects_invalid_request_before_model_call(self):
        with self.assertRaises(HTTPError) as error:
            urlopen(self.post("/dana/intent", {"language": "fr", "text": "Bonjour"},
                              "local-integration-test-token-0123456"), timeout=2)
        self.assertEqual(error.exception.code, 400)

    def test_unknown_route_returns_404(self):
        with self.assertRaises(HTTPError) as error:
            urlopen(self.url("/unknown"), timeout=2)
        self.assertEqual(error.exception.code, 404)


if __name__ == "__main__":
    unittest.main()
