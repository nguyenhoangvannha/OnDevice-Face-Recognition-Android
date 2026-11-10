#!/usr/bin/env python3
"""Client for the Xperia face coprocessor REST API.

Standard library only (fits a 1 GB Armbian box or a small container).
Configuration comes from the environment; nothing secret lives in this file:

    XPERIA_FACE_URL      base URL, e.g. http://<phone-ip>:8080   (required)
    XPERIA_FACE_TOKEN    bearer token shown in the app dashboard  (required)
    XPERIA_FACE_TIMEOUT  seconds per request, default 3.0         (optional)

API reference: docs/coprocessor/api.md
"""

import argparse
import json
import mimetypes
import os
import socket
import sys
import time
import urllib.error
import urllib.request
import uuid
from typing import Iterable, List, Optional, Tuple, Union

DEFAULT_TIMEOUT = 3.0

ImageInput = Union[str, bytes]


class CoprocessorError(Exception):
    """HTTP error returned by the API (status code + JSON error code)."""

    def __init__(self, status: int, error: str, message: str = ""):
        super().__init__("HTTP %d %s: %s" % (status, error, message))
        self.status = status
        self.error = error
        self.message = message


class XperiaFaceClient:
    def __init__(self, base_url: Optional[str] = None, token: Optional[str] = None,
                 timeout: Optional[float] = None):
        base_url = base_url or os.environ.get("XPERIA_FACE_URL")
        token = token or os.environ.get("XPERIA_FACE_TOKEN")
        if not base_url:
            raise ValueError("XPERIA_FACE_URL is not set")
        if not token:
            raise ValueError("XPERIA_FACE_TOKEN is not set")
        if timeout is None:
            timeout = float(os.environ.get("XPERIA_FACE_TIMEOUT", DEFAULT_TIMEOUT))
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._token = token

    def __repr__(self) -> str:  # never expose the token
        return "XperiaFaceClient(base_url=%r, timeout=%r)" % (self.base_url, self.timeout)

    # -- endpoints ---------------------------------------------------------

    def health(self) -> dict:
        return self._request("GET", "/api/health", auth=False)

    def status(self) -> dict:
        return self._request("GET", "/api/status")

    def recognize(self, image: ImageInput, liveness: bool = False) -> dict:
        body, content_type = _multipart([], [("image", _load(image))])
        path = "/api/recognize" + ("?liveness=true" if liveness else "")
        return self._request("POST", path, body, content_type)

    def enroll(self, name: str, images: Iterable[ImageInput]) -> dict:
        files = [("image", _load(img)) for img in images]
        body, content_type = _multipart([("name", name)], files)
        return self._request("POST", "/api/enroll", body, content_type)

    def add_images(self, person_id: int, images: Iterable[ImageInput]) -> dict:
        """Adds 1..10 images (one face each) to an existing person."""
        files = [("image", _load(img)) for img in images]
        body, content_type = _multipart([], files)
        return self._request("POST", "/api/persons/%d/images" % int(person_id), body, content_type)

    def persons(self) -> List[dict]:
        return self._request("GET", "/api/persons")["persons"]

    def delete_person(self, person_id: int) -> dict:
        return self._request("DELETE", "/api/persons/%d" % int(person_id))

    # -- plumbing ----------------------------------------------------------

    def _request(self, method: str, path: str, body: Optional[bytes] = None,
                 content_type: Optional[str] = None, auth: bool = True) -> dict:
        req = urllib.request.Request(self.base_url + path, data=body, method=method)
        if auth:
            req.add_header("Authorization", "Bearer " + self._token)
        if content_type:
            req.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read()
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                payload = json.loads(raw.decode("utf-8")) if raw else {}
            except ValueError:
                payload = {}
            raise CoprocessorError(e.code, payload.get("error", "http_%d" % e.code),
                                   payload.get("message", "")) from None
        return json.loads(raw.decode("utf-8")) if raw else {}


def _load(image: ImageInput) -> Tuple[str, bytes, str]:
    if isinstance(image, (bytes, bytearray)):
        return "snapshot.jpg", bytes(image), "image/jpeg"
    with open(image, "rb") as f:
        data = f.read()
    mime = mimetypes.guess_type(image)[0] or "application/octet-stream"
    return os.path.basename(image), data, mime


def _multipart(fields, files) -> Tuple[bytes, str]:
    boundary = "----xperia-face-" + uuid.uuid4().hex
    out = []
    for name, value in fields:
        out.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n" % (boundary, name)).encode())
        out.append(str(value).encode("utf-8") + b"\r\n")
    for name, (filename, data, mime) in files:
        out.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                    "Content-Type: %s\r\n\r\n" % (boundary, name, filename, mime)).encode())
        out.append(data + b"\r\n")
    out.append(("--%s--\r\n" % boundary).encode())
    return b"".join(out), "multipart/form-data; boundary=" + boundary


def recognize_snapshot(image: ImageInput, liveness: bool = False,
                       client: Optional[XperiaFaceClient] = None) -> Optional[dict]:
    """Event-pipeline helper: returns the result, or None on timeout, network or API error.

    The caller should treat None as "unknown person" and never block an alert on it.
    """
    started = time.monotonic()
    try:
        client = client or XperiaFaceClient()
        result = client.recognize(image, liveness=liveness)
    except (CoprocessorError, urllib.error.URLError, socket.timeout, OSError, ValueError) as e:
        print("[xperia-face] failed after %.0f ms: %s" % ((time.monotonic() - started) * 1000, e),
              file=sys.stderr)
        return None
    print("[xperia-face] inference_ms=%s roundtrip_ms=%.0f faces=%s known=%s" % (
        result.get("inference_ms"), (time.monotonic() - started) * 1000,
        result.get("face_count"), result.get("recognized_count")), file=sys.stderr)
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("health")
    sub.add_parser("status")
    sub.add_parser("persons")
    p = sub.add_parser("recognize")
    p.add_argument("image")
    p.add_argument("--liveness", action="store_true")
    p = sub.add_parser("enroll")
    p.add_argument("name")
    p.add_argument("images", nargs="+")
    p = sub.add_parser("add-images")
    p.add_argument("person_id", type=int)
    p.add_argument("images", nargs="+")
    p = sub.add_parser("delete")
    p.add_argument("person_id", type=int)
    args = parser.parse_args(argv)

    try:
        client = XperiaFaceClient()
        if args.cmd == "health":
            out = client.health()
        elif args.cmd == "status":
            out = client.status()
        elif args.cmd == "persons":
            out = client.persons()
        elif args.cmd == "recognize":
            out = client.recognize(args.image, liveness=args.liveness)
        elif args.cmd == "enroll":
            out = client.enroll(args.name, args.images)
        elif args.cmd == "add-images":
            out = client.add_images(args.person_id, args.images)
        else:
            out = client.delete_person(args.person_id)
    except (CoprocessorError, ValueError, OSError) as e:
        print("error: %s" % e, file=sys.stderr)
        return 1
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
