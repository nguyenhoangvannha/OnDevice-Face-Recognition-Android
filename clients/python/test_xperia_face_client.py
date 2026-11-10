"""Tests against a local fake server (stdlib only): python3 -m unittest -v test_xperia_face_client"""

import json
import os
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest import mock

import xperia_face_client as xfc

TOKEN = "test-token-not-a-secret"


class FakeApi(BaseHTTPRequestHandler):
    last_body = b""
    delay = 0.0

    def log_message(self, *args):
        pass

    def _send(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        return self.headers.get("Authorization") == "Bearer " + TOKEN

    def do_GET(self):
        if self.path == "/api/health":
            return self._send(200, {"status": "ok"})
        if not self._authorized():
            return self._send(401, {"error": "unauthorized", "message": "nope"})
        if self.path == "/api/persons":
            return self._send(200, {"persons": [{"person_id": 1, "name": "An"}], "count": 1})
        self._send(404, {"error": "not_found", "message": ""})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        FakeApi.last_body = self.rfile.read(length)
        time.sleep(FakeApi.delay)
        if not self._authorized():
            return self._send(401, {"error": "unauthorized", "message": "nope"})
        if self.path.startswith("/api/recognize"):
            return self._send(200, {"faces": [], "face_count": 0, "recognized_count": 0, "inference_ms": 5})
        if self.path == "/api/persons/1/images":
            return self._send(200, {"person_id": 1, "name": "An", "images_accepted": 1, "images_rejected": []})
        if self.path.startswith("/api/persons/"):
            return self._send(404, {"error": "not_found", "message": "No person"})
        if self.path == "/api/enroll":
            return self._send(201, {"person_id": 2, "name": "Binh", "images_accepted": 2, "images_rejected": []})
        self._send(404, {"error": "not_found", "message": ""})

    def do_DELETE(self):
        if not self._authorized():
            return self._send(401, {"error": "unauthorized", "message": "nope"})
        self._send(404, {"error": "not_found", "message": "No person with id 9"})


class ClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), FakeApi)
        cls.url = "http://127.0.0.1:%d" % cls.server.server_address[1]
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        FakeApi.delay = 0.0
        self.client = xfc.XperiaFaceClient(self.url, TOKEN, timeout=2.0)

    def test_env_configuration_is_required(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError):
                xfc.XperiaFaceClient()
        with mock.patch.dict(os.environ, {"XPERIA_FACE_URL": self.url, "XPERIA_FACE_TOKEN": TOKEN,
                                          "XPERIA_FACE_TIMEOUT": "1.5"}, clear=True):
            c = xfc.XperiaFaceClient()
            self.assertEqual(1.5, c.timeout)
            self.assertNotIn(TOKEN, repr(c))

    def test_health_and_persons(self):
        self.assertEqual("ok", self.client.health()["status"])
        self.assertEqual("An", self.client.persons()[0]["name"])

    def test_recognize_sends_multipart_image(self):
        result = self.client.recognize(b"\xff\xd8fake-jpeg")
        self.assertEqual(5, result["inference_ms"])
        self.assertIn(b'name="image"; filename="snapshot.jpg"', FakeApi.last_body)
        self.assertIn(b"\xff\xd8fake-jpeg", FakeApi.last_body)

    def test_enroll_sends_name_and_images(self):
        result = self.client.enroll("Binh", [b"a", b"b"])
        self.assertEqual(2, result["person_id"])
        self.assertIn(b'name="name"\r\n\r\nBinh', FakeApi.last_body)
        self.assertEqual(2, FakeApi.last_body.count(b'name="image"'))

    def test_add_images(self):
        result = self.client.add_images(1, [b"img"])
        self.assertEqual(1, result["images_accepted"])
        self.assertIn(b'name="image"', FakeApi.last_body)
        with self.assertRaises(xfc.CoprocessorError) as ctx:
            self.client.add_images(99, [b"img"])
        self.assertEqual(404, ctx.exception.status)

    def test_api_errors_raise(self):
        bad = xfc.XperiaFaceClient(self.url, "wrong", timeout=2.0)
        with self.assertRaises(xfc.CoprocessorError) as ctx:
            bad.persons()
        self.assertEqual(401, ctx.exception.status)
        with self.assertRaises(xfc.CoprocessorError) as ctx:
            self.client.delete_person(9)
        self.assertEqual("not_found", ctx.exception.error)

    def test_recognize_snapshot_returns_none_on_timeout(self):
        FakeApi.delay = 0.5
        slow = xfc.XperiaFaceClient(self.url, TOKEN, timeout=0.1)
        self.assertIsNone(xfc.recognize_snapshot(b"x", client=slow))

    def test_recognize_snapshot_returns_none_when_unreachable(self):
        dead = xfc.XperiaFaceClient("http://127.0.0.1:9", TOKEN, timeout=0.5)
        self.assertIsNone(xfc.recognize_snapshot(b"x", client=dead))


if __name__ == "__main__":
    unittest.main()
