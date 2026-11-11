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
    last_path = ""
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
        if self.path == "/api/strangers":
            return self._send(200, {"strangers": [{"stranger_id": 3, "name": "Person_03", "appearances": 4}], "count": 1})
        if self.path == "/api/settings/strangers":
            return self._send(200, {"threshold": 0.7, "max_clusters": 200})
        self._send(404, {"error": "not_found", "message": ""})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        FakeApi.last_body = self.rfile.read(length)
        FakeApi.last_path = self.path
        time.sleep(FakeApi.delay)
        if not self._authorized():
            return self._send(401, {"error": "unauthorized", "message": "nope"})
        if self.path.startswith("/api/recognize"):
            return self._send(200, {"faces": [], "face_count": 0, "recognized_count": 0, "inference_ms": 5})
        if self.path == "/api/strangers/3/promote":
            return self._send(200, {"person_id": 16, "name": "Nam", "stranger_name": "Person_03", "created": True})
        if self.path == "/api/persons/1/images":
            return self._send(200, {"person_id": 1, "name": "An", "images_accepted": 1, "images_rejected": []})
        if self.path.startswith("/api/persons/"):
            return self._send(404, {"error": "not_found", "message": "No person"})
        if self.path == "/api/enroll":
            return self._send(201, {"person_id": 2, "name": "Binh", "images_accepted": 2, "images_rejected": []})
        self._send(404, {"error": "not_found", "message": ""})

    def do_PUT(self):
        length = int(self.headers.get("Content-Length", "0"))
        FakeApi.last_body = self.rfile.read(length)
        if not self._authorized():
            return self._send(401, {"error": "unauthorized", "message": "nope"})
        threshold = json.loads(FakeApi.last_body)["threshold"]
        self._send(200, {"threshold": threshold, "max_clusters": 200})

    def do_DELETE(self):
        if not self._authorized():
            return self._send(401, {"error": "unauthorized", "message": "nope"})
        if self.path == "/api/strangers":
            return self._send(200, {"deleted": 2})
        if self.path == "/api/strangers/3":
            return self._send(200, {"deleted": 1})
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

    def test_recognize_v2_query_parameters(self):
        self.client.recognize(b"img")
        self.assertEqual("/api/recognize", FakeApi.last_path)
        self.client.recognize(b"img", liveness=True, person_box=[0.1, 0.2, 0.3, 0.4], cluster_strangers=True)
        self.assertEqual("/api/recognize?liveness=true&person_box=0.1,0.2,0.3,0.4&cluster_strangers=true",
                         FakeApi.last_path)
        with self.assertRaises(ValueError):
            self.client.recognize(b"img", person_box=[0.1, 0.2, 0.3])
        self.client.recognize(b"img", cluster_strangers=True, event_id="1794372518.917899-f7uo07")
        self.assertEqual("/api/recognize?cluster_strangers=true&event_id=1794372518.917899-f7uo07", FakeApi.last_path)
        for bad in ["", "a b", "a/b", "x" * 65, "abc\n"]:
            with self.assertRaises(ValueError):
                self.client.recognize(b"img", event_id=bad)

    def test_stranger_admin(self):
        self.assertEqual("Person_03", self.client.strangers()[0]["name"])
        promoted = self.client.promote_stranger(3, name="Nam")
        self.assertTrue(promoted["created"])
        self.assertEqual({"name": "Nam"}, json.loads(FakeApi.last_body))
        with self.assertRaises(ValueError):
            self.client.promote_stranger(3)
        with self.assertRaises(ValueError):
            self.client.promote_stranger(3, name="Nam", person_id=1)
        self.assertEqual(1, self.client.delete_stranger(3)["deleted"])
        self.assertEqual(2, self.client.delete_all_strangers()["deleted"])
        self.assertEqual(0.7, self.client.stranger_settings()["threshold"])
        self.assertEqual(0.75, self.client.set_stranger_threshold(0.75)["threshold"])

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
