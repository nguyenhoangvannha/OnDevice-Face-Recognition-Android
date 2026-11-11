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
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Iterable, List, Optional, Sequence, Tuple, Union

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

    def recognize(self, image: ImageInput, liveness: bool = False,
                  person_box: Optional[Sequence[float]] = None,
                  cluster_strangers: bool = False, event_id: Optional[str] = None) -> dict:
        """person_box: normalized [x, y, w, h] (e.g. Frigate event data.box); only faces inside it are kept.
        cluster_strangers: unknown faces get a persistent Person_NN label (is_stranger=true).
        event_id: e.g. the Frigate event id; frames of the same event are not counted twice (event_repeat)."""
        query = []
        if liveness:
            query.append(("liveness", "true"))
        if person_box is not None:
            query.append(("person_box", format_person_box(person_box)))
        if cluster_strangers:
            query.append(("cluster_strangers", "true"))
        if event_id is not None:
            if not _EVENT_ID.match(event_id):
                raise ValueError("event_id must be 1..64 chars of A-Z a-z 0-9 . _ : -")
            query.append(("event_id", event_id))
        body, content_type = _multipart([], [("image", _load(image))])
        path = "/api/recognize" + ("?" + urllib.parse.urlencode(query, safe=",") if query else "")
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

    def strangers(self) -> List[dict]:
        return self._request("GET", "/api/strangers")["strangers"]

    def promote_stranger(self, stranger_id: int, name: Optional[str] = None,
                         person_id: Optional[int] = None) -> dict:
        """Moves a stranger cluster into a household person (new name, existing name, or person_id)."""
        if (name is None) == (person_id is None):
            raise ValueError("give exactly one of name or person_id")
        payload = {"name": name} if name is not None else {"person_id": int(person_id)}
        return self._request("POST", "/api/strangers/%d/promote" % int(stranger_id),
                             json.dumps(payload).encode("utf-8"), "application/json")

    def delete_stranger(self, stranger_id: int) -> dict:
        return self._request("DELETE", "/api/strangers/%d" % int(stranger_id))

    def delete_all_strangers(self) -> dict:
        return self._request("DELETE", "/api/strangers")

    def stranger_settings(self) -> dict:
        return self._request("GET", "/api/settings/strangers")

    def set_stranger_threshold(self, threshold: float) -> dict:
        return self._request("PUT", "/api/settings/strangers",
                             json.dumps({"threshold": float(threshold)}).encode("utf-8"), "application/json")

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


_EVENT_ID = re.compile(r"^[A-Za-z0-9._:-]{1,64}\Z")


def format_person_box(box: Sequence[float]) -> str:
    """[x, y, w, h] in 0..1 -> "x,y,w,h" (validated again by the server)."""
    values = [float(v) for v in box]
    if len(values) != 4:
        raise ValueError("person_box needs 4 numbers: x, y, w, h")
    return ",".join(repr(round(v, 6)) for v in values)


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
                       client: Optional[XperiaFaceClient] = None,
                       person_box: Optional[Sequence[float]] = None,
                       cluster_strangers: bool = False,
                       event_id: Optional[str] = None) -> Optional[dict]:
    """Event-pipeline helper: returns the result, or None on timeout, network or API error.

    The caller should treat None as "unknown person" and never block an alert on it.
    """
    started = time.monotonic()
    try:
        client = client or XperiaFaceClient()
        result = client.recognize(image, liveness=liveness, person_box=person_box,
                                  cluster_strangers=cluster_strangers, event_id=event_id)
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
    p.add_argument("--person-box", help="x,y,w,h normalized, e.g. Frigate data.box")
    p.add_argument("--cluster-strangers", action="store_true")
    p.add_argument("--event-id", help="e.g. Frigate event id; repeated frames are not counted twice")
    sub.add_parser("strangers")
    p = sub.add_parser("promote")
    p.add_argument("stranger_id", type=int)
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--name")
    g.add_argument("--person-id", type=int)
    p = sub.add_parser("delete-stranger")
    p.add_argument("stranger_id", type=int)
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
            box = [float(v) for v in args.person_box.split(",")] if args.person_box else None
            out = client.recognize(args.image, liveness=args.liveness, person_box=box,
                                   cluster_strangers=args.cluster_strangers, event_id=args.event_id)
        elif args.cmd == "strangers":
            out = client.strangers()
        elif args.cmd == "promote":
            out = client.promote_stranger(args.stranger_id, name=args.name, person_id=args.person_id)
        elif args.cmd == "delete-stranger":
            out = client.delete_stranger(args.stranger_id)
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
